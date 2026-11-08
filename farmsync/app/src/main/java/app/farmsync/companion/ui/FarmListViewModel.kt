package app.farmsync.companion.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.farmsync.companion.ISaveFs
import app.farmsync.companion.ops.Exporter
import app.farmsync.companion.ops.ImportCandidate
import app.farmsync.companion.ops.ImportException
import app.farmsync.companion.ops.ImportReader
import app.farmsync.companion.ops.OpBlocked
import app.farmsync.companion.ops.OpFailed
import app.farmsync.companion.ops.SaveOps
import app.farmsync.companion.saves.Farm
import app.farmsync.companion.saves.FarmScanner
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
}

class FarmListViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private val store = SnapshotStore(File(app.filesDir, "snapshots"))
    private val cacheDir get() = getApplication<Application>().cacheDir
    private val syncPrefs = SyncPrefs(app)
    /** What to run once the user finished Google's chooser/consent. */
    private var afterAuth: ((String) -> Unit)? = null

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
    fun linkDrive() = withToken(email = null) { token -> finishLink(token) }

    fun onAuthResult(data: Intent?) {
        val next = afterAuth ?: return
        afterAuth = null
        try {
            next(DriveAuth.tokenFromResult(getApplication(), data))
        } catch (e: Exception) {
            _state.update { it.copy(message = Message.Failed(e.message ?: "Drive")) }
        }
    }

    fun authLaunched() = _state.update { it.copy(sync = it.sync.copy(pendingAuth = null)) }

    fun confirmCreateVault() {
        _state.update { it.copy(sync = it.sync.copy(confirmCreateVaultFor = null)) }
        withToken(syncPrefs.email ?: pendingEmail) { token ->
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
    fun syncNow(quiet: Boolean = false) = withDrive { engine, vault ->
        val report = SyncRunner.lock.withLock { SyncRunner.run(engine, vault, _state.value.farms, syncPrefs) }
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
                it.outcome in setOf(Outcome.CONFLICT, Outcome.BLOCKED_GAME_RUNNING, Outcome.FAILED)
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

    /** The user decided a conflict (or allowed a download over mid-day progress). */
    fun resolve(farm: String, keepLocal: Boolean) = withDrive { engine, _ ->
        val local = _state.value.farms.first { it.folder == farm }
        val r = SyncRunner.lock.withLock { if (keepLocal) engine.keepLocal(local) else engine.keepRemote(local) }
        SyncRunner.log(getApplication()).append("user", listOf(r))
        _state.update { it.copy(sync = it.sync.copy(results = it.sync.results + (farm to r))) }
        null
    }

    fun downloadFromVault(farm: String) = withDrive { engine, _ ->
        val r = SyncRunner.lock.withLock { engine.downloadNew(farm) }
        SyncRunner.log(getApplication()).append("user", listOf(r))
        _state.update {
            it.copy(sync = it.sync.copy(
                results = it.sync.results + (farm to r),
                available = it.sync.available.filterNot { a -> a.farm == farm },
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
    private fun withToken(email: String?, block: (String) -> Unit) {
        viewModelScope.launch {
            try {
                when (val o = DriveAuth.authorize(getApplication(), email)) {
                    is AuthOutcome.Token -> block(o.accessToken)
                    is AuthOutcome.NeedsUser -> {
                        afterAuth = block
                        _state.update { it.copy(sync = it.sync.copy(pendingAuth = o.intent)) }
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(message = Message.Failed(e.message ?: "Drive")) }
            }
        }
    }

    /** Runs a sync job with an engine bound to the linked vault (refuses a different vault). */
    private fun withDrive(block: suspend (SyncEngine, Vault) -> Message?) {
        val email = syncPrefs.email ?: return
        withToken(email) { token ->
            runBusy { fs ->
                val api = DriveApi(token)
                val vault = Vault.find(api)
                if (vault == null || vault.vaultId != syncPrefs.vaultId) return@runBusy Message.VaultMismatch
                block(SyncEngine(fs, store, vault, syncPrefs, cacheDir, _state.value.gameVersion), vault)
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
                val fs = withContext(Dispatchers.IO) { ShizukuGate.saveFs() }
                scanInto(fs)
                // On every return to the app, not just at start: a farm played in between gets
                // its snapshot even if Farm Sync stayed in memory. Unchanged farms are skipped.
                snapshotChanged(fs, _state.value.farms, SnapshotOrigin.AUTO_OPEN, quietIfNone = true)
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
        val targets = _state.value.farms.filter { it.isComplete && (farm == null || it.folder == farm) }
        targets.forEach { store.create(fs, it, SnapshotOrigin.MANUAL) }
        Message.SnapshotsCreated(targets.size)
    }

    /** Puts [snapshot] back into its farm folder. All safety rules live in [SaveOps]. */
    fun restore(snapshot: SnapshotMeta) = runBusy { fs ->
        writeOp { SaveOps(fs, store, cacheDir).restore(snapshot, _state.value.gameVersion).let { Message.Restored(it.pointerRemoved) } }
    }

    fun export(folder: String) = runBusy { fs ->
        val farm = _state.value.farms.first { it.folder == folder }
        val file = Exporter.export(fs, store, farm, File(cacheDir, "exports"))
        _state.update { it.copy(shareFile = file) }
        null
    }

    fun shareHandled() = _state.update { it.copy(shareFile = null) }

    /** Copies the picked zip into the cache and validates it. Nothing is written to saves here. */
    fun prepareImport(uri: Uri) = runBusy { _ ->
        val ctx = getApplication<Application>()
        val work = File(cacheDir, "import").apply { deleteRecursively(); mkdirs() }
        val zip = File(work, "picked.zip")
        ctx.contentResolver.openInputStream(uri)?.use { input -> zip.outputStream().use { input.copyTo(it) } }
            ?: return@runBusy Message.ImportRejected(ImportException.Reason.NOT_A_ZIP)
        try {
            val candidate = ImportReader.read(zip, File(work, "x"))
            val onDevice = _state.value.farms.firstOrNull { it.folder == candidate.farm }
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
                val ops = SaveOps(fs, store, cacheDir)
                if (preview.onDevice == null) {
                    ops.addNew(preview.candidate, _state.value.gameVersion)
                    Message.Imported(isNew = true, pointerRemoved = false)
                } else {
                    val r = ops.replace(preview.candidate, _state.value.gameVersion, SnapshotOrigin.BEFORE_IMPORT)
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

    private suspend fun scanInto(fs: ISaveFs) {
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
    private suspend fun snapshotChanged(fs: ISaveFs, farms: List<Farm>, origin: SnapshotOrigin, quietIfNone: Boolean) {
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
            _state.update { it.copy(message = Message.Failed(e.message ?: e.javaClass.simpleName)) }
        }
    }

    /** Runs [block] off the main thread with a progress bar; a returned message is shown. */
    private fun runBusy(block: suspend (ISaveFs) -> Message?) {
        if (_state.value.busy) return
        // Say it plainly instead of Shizuku's internal "binder" errors.
        if (ShizukuGate.status(getApplication()) != ShizukuStatus.READY) {
            _state.update { it.copy(message = Message.ShizukuMissing) }
            refresh()
            return
        }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val msg = try {
                val fs = withContext(Dispatchers.IO) { ShizukuGate.saveFs() }
                withContext(Dispatchers.IO) { block(fs) }.also { scanInto(fs) }
            } catch (e: Exception) {
                Message.Failed(e.message ?: e.javaClass.simpleName)
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
                Message.Failed(e.message ?: e.javaClass.simpleName)
            }
            _state.update { it.copy(busy = false, message = msg ?: it.message) }
        }
    }
}
