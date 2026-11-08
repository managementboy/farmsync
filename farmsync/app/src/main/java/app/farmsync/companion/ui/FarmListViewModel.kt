package app.farmsync.companion.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.farmsync.companion.ops.Exporter
import app.farmsync.companion.ops.ImportCandidate
import app.farmsync.companion.ops.ImportException
import app.farmsync.companion.ops.ImportReader
import app.farmsync.companion.ops.OpBlocked
import app.farmsync.companion.ops.OpFailed
import app.farmsync.companion.ops.Recovery
import app.farmsync.companion.ops.SaveOps
import app.farmsync.companion.ops.WriteJournal
import app.farmsync.companion.saves.AidlSaveFs
import app.farmsync.companion.saves.Farm
import app.farmsync.companion.saves.FarmScanner
import app.farmsync.companion.saves.SaveFs
import app.farmsync.companion.shizuku.SaveFsService
import app.farmsync.companion.shizuku.ShizukuGate
import app.farmsync.companion.shizuku.ShizukuStatus
import app.farmsync.companion.store.SnapshotMeta
import app.farmsync.companion.store.SnapshotOrigin
import app.farmsync.companion.store.SnapshotStore
import app.farmsync.companion.sync.AuthOutcome
import app.farmsync.companion.sync.DriveApi
import app.farmsync.companion.sync.DriveAuth
import app.farmsync.companion.sync.FarmSyncResult
import app.farmsync.companion.sync.Outcome
import app.farmsync.companion.sync.SyncEngine
import app.farmsync.companion.sync.SyncPrefs
import app.farmsync.companion.sync.SyncRunner
import kotlinx.coroutines.sync.withLock
import app.farmsync.companion.sync.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the import dialog shows before anything is written. */
data class ImportPreview(
    val candidate: ImportCandidate,
    /** The same farm (same folder incl. ID) on this device, if present. */
    val onDevice: Farm?,
    /** Not on the device but known from snapshots: deleted here before (rule R5). */
    val wasDeletedHere: Boolean,
)

data class UiState(
    val shizuku: ShizukuStatus = ShizukuStatus.NOT_RUNNING,
    val gameVersion: String? = null,
    val gameRunning: Boolean = false,
    val farms: List<Farm> = emptyList(),
    val snapshots: Map<String, List<SnapshotMeta>> = emptyMap(),
    /** Farms that have snapshots but are no longer on the device (rule R5: shown, never re-created). */
    val archived: List<String> = emptyList(),
    val selectedFarm: String? = null,
    val importPreview: ImportPreview? = null,
    /** One-shot: a file to hand to the share sheet. */
    val shareFile: File? = null,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val message: Message? = null,
    val error: String? = null,
    val sync: SyncUi = SyncUi(),
    /** Settings / diagnostics screen open. */
    val showSettings: Boolean = false,
    val showAbout: Boolean = false,
)

data class SyncUi(
    /** The linked vault account, or null if Drive is not linked on this device. */
    val email: String? = null,
    /** Farms switched OFF for sync on this device; everything else syncs. */
    val disabled: Set<String> = emptySet(),
    val results: Map<String, FarmSyncResult> = emptyMap(),
    /** Farms in the vault that are not on this device (never downloaded automatically). */
    val available: List<FarmSyncResult> = emptyList(),
    /** Linked account has no vault yet: ask before creating one. */
    val confirmCreateVaultFor: String? = null,
    /** One-shot: Google needs the user (account chooser / consent). */
    val pendingAuth: PendingIntent? = null,
    val lastSync: Long? = null,
)

sealed interface Message {
    data class SnapshotsCreated(val count: Int) : Message
    data object NothingChanged : Message
    data class Failed(val detail: String) : Message
    data class Restored(val pointerRemoved: Boolean) : Message
    data class Imported(val isNew: Boolean, val pointerRemoved: Boolean) : Message
    data class Blocked(val reason: OpBlocked.Reason) : Message
    data class ImportRejected(val reason: ImportException.Reason) : Message
    data class WriteFailed(val detail: String, val rolledBack: Boolean, val beforeTime: Long?) : Message
    data class DriveLinked(val email: String, val newVault: Boolean) : Message
    data object VaultMismatch : Message
    data object ShizukuMissing : Message
    data class SyncDone(val uploaded: Int, val downloaded: Int, val resolved: Int, val attention: Int) : Message
    /** Interrupted writes found at start and what was done; [since] = when each write began. */
    /** Google sign-in came back after Android closed the app, and the request is unknown. */
    data object AuthRetry : Message
    data class Recovered(val items: List<Pair<String, Recovery>>, val since: Map<String, Long>) : Message
}

class FarmListViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private val store = SnapshotStore(File(app.filesDir, "snapshots"), "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    private val journal = WriteJournal(File(app.filesDir, "journal"))
    private val cacheDir get() = getApplication<Application>().cacheDir
    private val syncPrefs = SyncPrefs(app)
    /** What to run once the user finished Google's chooser/consent. */
    private var afterAuth: ((String) -> Unit)? = null
    /** A Google authorization is in progress: further requests wait for it instead of racing (F17). */
    private var authInFlight = false

    private fun ops(fs: SaveFs) = SaveOps(fs, store, cacheDir, journal, onDeliberate = ::markDeliberate)

    init {
        // Show the last known sync status per farm (also after the app was closed).
        val log = SyncRunner.log(app)
        val last = log.latestPerFarm().mapValues { (farm, e) ->
            FarmSyncResult(farm, e.outcome, detail = e.detail)
        }
        _state.update {
            it.copy(sync = it.sync.copy(email = syncPrefs.email, disabled = syncPrefs.disabled, results = last,
                lastSync = log.lastTime()))
        }
    }

    // ---------------------------------------------------------------- Google Drive

    /** Links this device to a Google account's vault. Google shows its account chooser. */
    fun linkDrive() = withToken(email = null, action = ACTION_LINK) { token -> finishLink(token) }

    fun onAuthResult(data: Intent?) {
        authInFlight = false
        // After Android recreated the app while Google's screen was open, the original callback
        // is gone; the action itself was saved, so exactly that action continues (F17, R08).
        val action = saved.get<String>(KEY_PENDING_AUTH)
        saved.remove<String>(KEY_PENDING_AUTH)
        val next = afterAuth ?: resumeAction(action)
        afterAuth = null
        if (next == null) {
            _state.update { it.copy(message = Message.AuthRetry) }
            return
        }
        try {
            next(DriveAuth.tokenFromResult(getApplication(), data))
        } catch (e: Exception) {
            _state.update { it.copy(message = Message.Failed(SyncRunner.reasonCode(e))) }
        }
    }

    fun authLaunched() = _state.update { it.copy(sync = it.sync.copy(pendingAuth = null)) }

    fun confirmCreateVault() {
        _state.update { it.copy(sync = it.sync.copy(confirmCreateVaultFor = null)) }
        withToken(syncPrefs.email ?: pendingEmail, ACTION_LINK) { token ->
            runBusyNoFs {
                val api = DriveApi(token)
                val vault = Vault.find(api) ?: Vault.create(api)
                syncPrefs.email = api.aboutEmail()
                syncPrefs.vaultId = vault.vaultId
                _state.update { it.copy(sync = it.sync.copy(email = syncPrefs.email)) }
                Message.DriveLinked(syncPrefs.email!!, newVault = true)
            }
        }
    }

    fun cancelCreateVault() = _state.update { it.copy(sync = it.sync.copy(confirmCreateVaultFor = null)) }

    fun unlinkDrive() {
        afterAuth = null
        authInFlight = false
        syncPrefs.unlink()
        _state.update { it.copy(sync = SyncUi(disabled = syncPrefs.disabled)) }
    }

    fun setSyncEnabled(farm: String, on: Boolean) {
        syncPrefs.setEnabled(farm, on)
        _state.update { it.copy(sync = it.sync.copy(disabled = syncPrefs.disabled)) }
    }

    /** Syncs every farm switched on for sync; lists vault farms that are not on this device. */
    /**
     * Syncs every farm switched on for sync. Runs automatically whenever the app opens or comes
     * back ([quiet] = only report if something happened), and from the button.
     */
    fun syncNow(quiet: Boolean = false) = withDrive(ACTION_SYNC) { engine, vault ->
        val unfinished = _state.value.farms.map { it.folder }.filter { journal.get(it)?.kind == app.farmsync.companion.ops.JournalEntry.Kind.NEW_FARM }.toSet()
        val report = SyncRunner.lock.withLock { SyncRunner.run(engine, vault, _state.value.farms, syncPrefs::isEnabled, unfinished) }
        val results = report.results
        SyncRunner.log(getApplication()).append(if (quiet) "auto" else "button", results.values)
        _state.update {
            it.copy(sync = it.sync.copy(results = it.sync.results + results, available = report.available,
                lastSync = System.currentTimeMillis()))
        }
        val done = Message.SyncDone(
            uploaded = results.values.count { it.outcome == Outcome.UPLOADED },
            downloaded = results.values.count { it.outcome == Outcome.DOWNLOADED },
            resolved = results.values.count { it.outcome in setOf(Outcome.RESOLVED_KEPT_LOCAL, Outcome.RESOLVED_TOOK_REMOTE) },
            attention = results.values.count {
                it.outcome in setOf(Outcome.BLOCKED_GAME_RUNNING, Outcome.FAILED, Outcome.NEEDS_REPAIR)
            },
        )
        if (quiet && done.uploaded + done.downloaded + done.resolved + done.attention == 0) null else done
    }

    private var lastAutoSync = 0L

    /** Called after every refresh: sync on open/return, at most every 30 seconds. */
    private fun autoSync() {
        if (syncPrefs.email == null || syncPrefs.vaultId == null) return
        val now = System.currentTimeMillis()
        if (now - lastAutoSync < 30_000) return
        lastAutoSync = now
        syncNow(quiet = true)
    }

    /** The user allowed a newer day to replace this device's unsaved mid-day progress. */
    fun downloadAnyway(farm: String) = withDrive(ACTION_MIDDAY + farm) { engine, _ ->
        val local = _state.value.farms.first { it.folder == farm }
        val r = SyncRunner.lock.withLock { engine.sync(local, farm, allowMidday = true) }
        SyncRunner.log(getApplication()).append("user", listOf(r))
        _state.update { it.copy(sync = it.sync.copy(results = it.sync.results + (farm to r))) }
        null
    }

    fun downloadFromVault(farm: String) = withDrive(ACTION_DOWNLOAD + farm) { engine, _ ->
        val r = SyncRunner.lock.withLock { engine.downloadNew(farm) }
        SyncRunner.log(getApplication()).append("user", listOf(r))
        _state.update {
            it.copy(sync = it.sync.copy(
                results = it.sync.results + (farm to r),
                // Only a finished download leaves the list; otherwise it stays there to retry.
                available = if (r.outcome == Outcome.DOWNLOADED) it.sync.available.filterNot { a -> a.farm == farm } else it.sync.available,
                disabled = syncPrefs.disabled,
            ))
        }
        null
    }

    private var pendingEmail: String? = null

    /** Linking only talks to Drive: it must work even while Shizuku is not running. */
    private fun finishLink(token: String) = runBusyNoFs {
        val api = DriveApi(token)
        val email = api.aboutEmail()
        pendingEmail = email
        val vault = Vault.find(api)
        when {
            vault == null -> {
                _state.update { it.copy(sync = it.sync.copy(confirmCreateVaultFor = email)) }
                null
            }
            syncPrefs.vaultId != null && syncPrefs.vaultId != vault.vaultId -> Message.VaultMismatch
            else -> {
                syncPrefs.email = email
                syncPrefs.vaultId = vault.vaultId
                _state.update { it.copy(sync = it.sync.copy(email = email)) }
                Message.DriveLinked(email, newVault = false)
            }
        }
    }

    /** Gets a Drive token for [email]; if Google needs the user, launches its UI first. */
    /** The saved action as a callback for a fresh token (see [onAuthResult]). */
    private fun resumeAction(action: String?): ((String) -> Unit)? = when {
        action == ACTION_LINK -> { token -> finishLink(token) }
        action == ACTION_SYNC -> { _ -> syncNow() }
        action?.startsWith(ACTION_DOWNLOAD) == true -> { _ -> downloadFromVault(action.removePrefix(ACTION_DOWNLOAD)) }
        action?.startsWith(ACTION_MIDDAY) == true -> { _ -> downloadAnyway(action.removePrefix(ACTION_MIDDAY)) }
        else -> null
    }

    private fun withToken(email: String?, action: String, block: (String) -> Unit) {
        if (authInFlight) return // one Google request at a time; this one comes back by itself
        authInFlight = true
        viewModelScope.launch {
            try {
                when (val o = DriveAuth.authorize(getApplication(), email)) {
                    is AuthOutcome.Token -> {
                        authInFlight = false
                        block(o.accessToken)
                    }
                    is AuthOutcome.NeedsUser -> {
                        afterAuth = block // stays in flight until onAuthResult
                        saved[KEY_PENDING_AUTH] = action // survives Android closing the app meanwhile
                        _state.update { it.copy(sync = it.sync.copy(pendingAuth = o.intent)) }
                    }
                }
            } catch (e: Exception) {
                authInFlight = false
                _state.update { it.copy(message = Message.Failed(SyncRunner.reasonCode(e))) }
            }
        }
    }

    /** Runs a sync job with an engine bound to the linked vault (refuses a different vault). */
    private fun withDrive(action: String, block: suspend (SyncEngine, Vault) -> Message?) {
        val email = syncPrefs.email ?: return
        withToken(email, action) { token ->
            runBusy { fs ->
                val api = DriveApi(token)
                val vault = Vault.find(api)
                if (vault == null || vault.vaultId != syncPrefs.vaultId) return@runBusy Message.VaultMismatch
                block(SyncEngine(fs, store, vault, syncPrefs, cacheDir, _state.value.gameVersion, journal), vault)
            }
        }
    }


    // ---------------------------------------------------------------- local

    fun refresh() {
        val ctx = getApplication<Application>()
        val shizuku = ShizukuGate.status(ctx)
        val gameVersion = try {
            ctx.packageManager.getPackageInfo(SaveFsService.GAME_PACKAGE, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        _state.update { it.copy(shizuku = shizuku, gameVersion = gameVersion, error = null) }
        if (shizuku != ShizukuStatus.READY || _state.value.loading || _state.value.busy) return

        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val fs: SaveFs = withContext(Dispatchers.IO) { AidlSaveFs(ShizukuGate.saveFs()) }
                // First of all: finish or undo writes that were interrupted (app killed, phone
                // off), before any snapshot or sync looks at those farms.
                recoverInterrupted(fs)
                scanInto(fs)
                // On every return to the app, not just at start: a farm played in between gets
                // its snapshot even if Farm Sync stayed in memory. Unchanged farms are skipped.
                snapshotChanged(fs, _state.value.farms.filter { !journal.has(it.folder) }, SnapshotOrigin.AUTO_OPEN, quietIfNone = true)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(loading = false) }
            }
            if (_state.value.error == null) autoSync()
        }
    }

    fun select(farm: String?) = _state.update { it.copy(selectedFarm = farm) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Manual snapshot of one farm, or of all farms when [farm] is null. Always creates one. */
    fun snapshotNow(farm: String?) = runBusy { fs ->
        val targets = _state.value.farms.filter { it.isComplete && !journal.has(it.folder) && (farm == null || it.folder == farm) }
        targets.forEach { store.create(fs, it, SnapshotOrigin.MANUAL) }
        Message.SnapshotsCreated(targets.size)
    }

    /** Puts [snapshot] back into its farm folder. All safety rules live in [SaveOps]. */
    fun restore(snapshot: SnapshotMeta) = runBusy { fs ->
        writeOp {
            val r = ops(fs).restore(snapshot, _state.value.gameVersion) // deliberate: recorded with the write
            Message.Restored(r.pointerRemoved)
        }
    }

    fun export(folder: String) = runBusy { fs ->
        val farm = _state.value.farms.first { it.folder == folder }
        val file = Exporter.export(fs, store, farm, File(cacheDir, "exports"))
        _state.update { it.copy(shareFile = file) }
        null
    }

    fun shareHandled() = _state.update { it.copy(shareFile = null) }

    /** Shares one snapshot zip as it was stored (read-only on saves). */
    fun exportSnapshot(meta: SnapshotMeta) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "exports").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                File(dir, "${meta.farm}_${meta.id}.zip").also { store.zipFile(meta).copyTo(it, overwrite = true) }
            }
            _state.update { it.copy(shareFile = file) }
        }
    }

    fun openSettings(open: Boolean) = _state.update { it.copy(showSettings = open) }
    fun openAbout(open: Boolean) = _state.update { it.copy(showAbout = open) }

    /** Newest sync results first, for the diagnostics screen. */
    suspend fun syncLogEntries(): List<app.farmsync.companion.sync.SyncLog.Entry> = withContext(Dispatchers.IO) {
        SyncRunner.log(getApplication()).entries().asReversed().take(30)
    }

    /** Copies the picked zip into the cache and validates it. Nothing is written to saves here. */
    fun prepareImport(uri: Uri) = runBusy { _ ->
        val ctx = getApplication<Application>()
        val work = File(cacheDir, "import").apply { deleteRecursively(); mkdirs() }
        val zip = File(work, "picked.zip")
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input -> ImportReader.copyLimited(input, zip) }
                ?: return@runBusy Message.ImportRejected(ImportException.Reason.NOT_A_ZIP)
            val candidate = ImportReader.read(zip, File(work, "x"))
            // A folder left by an interrupted import or download of this farm is finished, not replaced.
            val onDevice = _state.value.farms.firstOrNull { it.folder == candidate.farm && journal.get(it.folder)?.kind != app.farmsync.companion.ops.JournalEntry.Kind.NEW_FARM }
            val wasDeleted = onDevice == null && candidate.farm in _state.value.archived
            _state.update { it.copy(importPreview = ImportPreview(candidate, onDevice, wasDeleted)) }
            null
        } catch (e: ImportException) {
            Message.ImportRejected(e.reason)
        } finally {
            zip.delete()
        }
    }

    fun cancelImport() = _state.update { it.copy(importPreview = null) }

    fun confirmImport() {
        val preview = _state.value.importPreview ?: return
        _state.update { it.copy(importPreview = null) }
        runBusy { fs ->
            writeOp {
                val ops = ops(fs)
                if (preview.onDevice == null) {
                    ops.addNew(preview.candidate, _state.value.gameVersion, deliberate = true)
                    Message.Imported(isNew = true, pointerRemoved = false)
                } else {
                    // Replaces exactly the farm the preview showed; if it changed since, nothing is written.
                    val r = ops.replace(preview.candidate, _state.value.gameVersion, SnapshotOrigin.BEFORE_IMPORT,
                        expectedFingerprint = preview.onDevice.fingerprint, deliberate = true)
                    Message.Imported(isNew = false, pointerRemoved = r.pointerRemoved)
                }
            }
        }
    }

    fun setPinned(meta: SnapshotMeta, pinned: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.setPinned(meta, pinned) }
            reloadSnapshots()
        }
    }

    fun requestShizukuPermission() = ShizukuGate.requestPermission()

    private inline fun writeOp(block: () -> Message): Message = try {
        block()
    } catch (e: OpBlocked) {
        Message.Blocked(e.reason)
    } catch (e: OpFailed) {
        Message.WriteFailed(e.message.orEmpty(), e.rolledBack, e.before?.createdAt)
    }

    /**
     * A restore or import is the user's explicit choice: with Drive linked, the next sync makes
     * this farm the shared version everywhere instead of undoing it (F13, decision 2026-11-08).
     */
    private fun markDeliberate(farm: String) {
        if (syncPrefs.email != null) syncPrefs.setForced(farm, true)
    }

    private suspend fun recoverInterrupted(fs: SaveFs) {
        val pending = journal.all()
        if (pending.isEmpty()) return
        val results = withContext(Dispatchers.IO) { pending.map { it.farm to ops(fs).recover(it) } }
        val shown = results.filter { it.second != Recovery.NOTHING_CHANGED }
        if (shown.isNotEmpty()) {
            _state.update { it.copy(message = Message.Recovered(shown, pending.associate { e -> e.farm to e.startedAt })) }
        }
    }

    private suspend fun scanInto(fs: SaveFs) {
        val (farms, running) = withContext(Dispatchers.IO) { FarmScanner(fs).scan() to fs.isGameRunning() }
        _state.update { it.copy(farms = farms, gameRunning = running) }
        reloadSnapshots()
    }

    private suspend fun reloadSnapshots() {
        val (snaps, archived) = withContext(Dispatchers.IO) {
            val onDevice = _state.value.farms.map { it.folder }.toSet()
            val stored = File(getApplication<Application>().filesDir, "snapshots").listFiles()
                ?.filter { it.isDirectory }?.map { it.name }.orEmpty()
            val all = (onDevice + stored).associateWith { store.list(it) }.filterValues { it.isNotEmpty() }
            all to stored.filter { it !in onDevice && all.containsKey(it) }.sorted()
        }
        _state.update { it.copy(snapshots = snaps, archived = archived) }
    }

    /** Snapshots every complete farm whose content differs from its newest snapshot. */
    private suspend fun snapshotChanged(fs: SaveFs, farms: List<Farm>, origin: SnapshotOrigin, quietIfNone: Boolean) {
        try {
            val created = withContext(Dispatchers.IO) {
                farms.filter { it.isComplete && it.contentHash.isNotEmpty() }
                    .filter { store.latest(it.folder)?.contentHash != it.contentHash }
                    .onEach { store.create(fs, it, origin) }
                    .size
            }
            reloadSnapshots()
            if (created > 0 || !quietIfNone) {
                _state.update {
                    it.copy(message = if (created > 0) Message.SnapshotsCreated(created) else Message.NothingChanged)
                }
            }
        } catch (e: Exception) {
            _state.update { it.copy(message = Message.Failed(SyncRunner.reasonCode(e))) }
        }
    }

    /** Runs [block] off the main thread with a progress bar; a returned message is shown. */
    private fun runBusy(block: suspend (SaveFs) -> Message?) {
        // Also not while the start-up scan runs: it may be snapshotting or repairing a farm.
        if (_state.value.busy || _state.value.loading) return
        // Say it plainly instead of Shizuku's internal "binder" errors.
        if (ShizukuGate.status(getApplication()) != ShizukuStatus.READY) {
            _state.update { it.copy(message = Message.ShizukuMissing) }
            refresh()
            return
        }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val msg = try {
                val fs: SaveFs = withContext(Dispatchers.IO) { AidlSaveFs(ShizukuGate.saveFs()) }
                withContext(Dispatchers.IO) { block(fs) }.also { scanInto(fs) }
            } catch (e: Exception) {
                Message.Failed(SyncRunner.reasonCode(e))
            }
            _state.update { it.copy(busy = false, message = msg ?: it.message) }
        }
    }

    /** Like [runBusy] for work that only talks to Google Drive and never touches saves. */
    private fun runBusyNoFs(block: suspend () -> Message?) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val msg = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                Message.Failed(SyncRunner.reasonCode(e))
            }
            _state.update { it.copy(busy = false, message = msg ?: it.message) }
        }
    }

    companion object {
        private const val KEY_PENDING_AUTH = "pendingAuth"
        private const val ACTION_LINK = "link"
        private const val ACTION_SYNC = "sync"
        private const val ACTION_DOWNLOAD = "download:"
        private const val ACTION_MIDDAY = "midday:"
    }
}
