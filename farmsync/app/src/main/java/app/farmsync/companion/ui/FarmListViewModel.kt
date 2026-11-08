package app.farmsync.companion.ui

import android.app.Application
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
}

class FarmListViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    private val store = SnapshotStore(File(app.filesDir, "snapshots"))
    private val cacheDir get() = getApplication<Application>().cacheDir

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
}
