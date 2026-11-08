package app.farmsync.companion.ops

import android.os.ParcelFileDescriptor
import app.farmsync.companion.ISaveFs
import app.farmsync.companion.saves.Farm
import app.farmsync.companion.saves.FarmScanner
import app.farmsync.companion.saves.GameVersion
import app.farmsync.companion.shizuku.SavePaths
import app.farmsync.companion.store.SnapshotMeta
import app.farmsync.companion.store.SnapshotOrigin
import app.farmsync.companion.store.SnapshotStore
import java.io.File

class OpBlocked(val reason: Reason, detail: String = reason.name) : Exception(detail) {
    enum class Reason { GAME_RUNNING, FARM_MISSING, FARM_EXISTS, NEWER_GAME_VERSION, INCOMPLETE }
}

/**
 * Writing failed after it started. [rolledBack] tells whether the previous state was put back
 * and verified. [before] holds that state (null for a new farm, which had no previous state).
 */
class OpFailed(detail: String, val before: SnapshotMeta?, val rolledBack: Boolean) : Exception(detail)

data class OpResult(val before: SnapshotMeta?, val pointerRemoved: Boolean)

/** Where the new file contents come from: a snapshot or an import. */
interface FileSource {
    val farm: String
    val gameVersion: String
    /** File names available (any; only standard files are used). */
    val names: Set<String>
    /** Writes the file to [dest] and returns its verified md5. */
    fun copyTo(name: String, dest: File): String
    fun md5(name: String): String
}

class SnapshotSource(private val store: SnapshotStore, private val meta: SnapshotMeta) : FileSource {
    override val farm get() = meta.farm
    override val gameVersion get() = meta.info?.gameVersion.orEmpty()
    override val names get() = meta.files.map { it.name }.toSet()
    override fun copyTo(name: String, dest: File) = store.extract(meta, name, dest)
    override fun md5(name: String) = meta.files.first { it.name == name }.md5
}

/**
 * The only place that changes saves. Every write follows R1 (snapshot first), R2 (game not in
 * memory), R3 (atomic per file, done by the service), R6 (no version downgrade),
 * R7 (BACKUP_SAVE put aside) and R9 (verify afterwards, roll back on mismatch).
 */
class SaveOps(
    private val fs: ISaveFs,
    private val store: SnapshotStore,
    private val tempDir: File,
) {
    fun restore(snapshot: SnapshotMeta, installedGameVersion: String?) =
        replace(SnapshotSource(store, snapshot), installedGameVersion, SnapshotOrigin.BEFORE_RESTORE)

    /** Replaces the standard files of an existing farm with [source]. */
    fun replace(source: FileSource, installedGameVersion: String?, origin: SnapshotOrigin): OpResult {
        val farm = currentFarm(source.farm) ?: throw OpBlocked(OpBlocked.Reason.FARM_MISSING)
        val files = checkPreconditions(source, installedGameVersion)

        val before = store.create(fs, farm, origin) // R1
        try {
            writeFiles(source, files)
            val pointerRemoved = fs.removeMiddayPointer(source.farm) // R7, the pointer is in `before`
            verify(source, files) // R9
            return OpResult(before, pointerRemoved)
        } catch (e: Exception) {
            val back = SnapshotSource(store, before)
            val rollback = runCatching { standardFiles(back).let { writeFiles(back, it); verify(back, it) } }
            throw OpFailed(e.message ?: e.javaClass.simpleName, before, rolledBack = rollback.isSuccess)
        }
    }

    /** Adds a farm that is not on this device yet, as a new folder. */
    fun addNew(source: FileSource, installedGameVersion: String?): OpResult {
        if (currentFarm(source.farm) != null) throw OpBlocked(OpBlocked.Reason.FARM_EXISTS)
        val files = checkPreconditions(source, installedGameVersion)
        fs.createFarmFolder(source.farm)
        try {
            writeFiles(source, files)
            verify(source, files)
            return OpResult(before = null, pointerRemoved = false)
        } catch (e: Exception) {
            // No delete exists on purpose. SaveGameInfo is written last, so an aborted folder
            // usually lacks it and the game does not list it. Reported to the user.
            throw OpFailed(e.message ?: e.javaClass.simpleName, before = null, rolledBack = false)
        }
    }

    private fun checkPreconditions(source: FileSource, installedGameVersion: String?): List<String> {
        if (fs.isGameRunning()) throw OpBlocked(OpBlocked.Reason.GAME_RUNNING) // R2
        if (installedGameVersion != null && source.gameVersion.isNotEmpty() &&
            !GameVersion.canLoad(source.gameVersion, installedGameVersion) // R6
        ) throw OpBlocked(OpBlocked.Reason.NEWER_GAME_VERSION, "${source.gameVersion} > $installedGameVersion")
        val files = standardFiles(source)
        if (source.farm !in files || "SaveGameInfo" !in files) throw OpBlocked(OpBlocked.Reason.INCOMPLETE)
        return files
    }

    private fun writeFiles(source: FileSource, files: List<String>) {
        // SaveGameInfo last: the load menu reads it, so the new day only shows once all is in place.
        for (name in files.sortedBy { if (it == "SaveGameInfo") 1 else 0 }) {
            val tmp = File(tempDir, "write-$name")
            try {
                val md5 = source.copyTo(name, tmp)
                ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    fs.writeStandardFile(source.farm, name, pfd, md5)
                }
            } finally {
                tmp.delete()
            }
        }
    }

    private fun verify(source: FileSource, files: List<String>) {
        for (name in files) {
            check(fs.md5("${source.farm}/$name") == source.md5(name)) { "Verification failed for $name" }
        }
    }

    private fun currentFarm(folder: String): Farm? = FarmScanner(fs).scan().firstOrNull { it.folder == folder }

    private fun standardFiles(source: FileSource): List<String> =
        source.names.filter { it in SavePaths.standardFiles(source.farm) }
}
