package app.farmsync.companion.ops

import app.farmsync.companion.saves.Farm
import app.farmsync.companion.saves.FarmScanner
import app.farmsync.companion.saves.GameVersion
import app.farmsync.companion.saves.SaveFs
import app.farmsync.companion.saves.SaveXml
import app.farmsync.companion.saves.SaveXmlException
import app.farmsync.companion.shizuku.SavePaths
import app.farmsync.companion.store.SnapshotMeta
import app.farmsync.companion.store.SnapshotOrigin
import app.farmsync.companion.store.SnapshotStore
import java.io.File

class OpBlocked(val reason: Reason, detail: String = reason.name) : Exception(detail) {
    enum class Reason {
        GAME_RUNNING, FARM_MISSING, FARM_EXISTS, NEWER_GAME_VERSION, INCOMPLETE,
        /** The installed game's or the save's version is unknown, so R6 can't be checked. */
        GAME_VERSION_UNKNOWN,
        /** The farm changed on this device after the operation was planned (played meanwhile). */
        CHANGED,
        /** The farm has unsaved mid-day progress and the caller did not allow replacing it. */
        MIDDAY,
        /** An earlier write to this farm was interrupted and is not repaired yet. */
        NEEDS_REPAIR,
        /** The new files are not a complete, valid save for this farm (wrong root, ID, broken XML). */
        INVALID_SAVE,
    }
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

/** What [SaveOps.recover] did with an interrupted write. */
enum class Recovery {
    /** The write had finished; only the bookkeeping was left. */
    COMPLETED,
    /** Nothing had changed yet. */
    NOTHING_CHANGED,
    /**
     * The farm changed in a way the interrupted write can't explain (it was played since):
     * the current state is kept as it is, with a fresh snapshot. Nothing is overwritten.
     */
    KEPT_CURRENT,
    /** The farm was put back to the state before the write, verified. */
    ROLLED_BACK,
    /** Stardew is running; try again once it's closed. */
    WAITING_FOR_GAME,
    /** A new farm that was only partly written; downloading or importing it again finishes it. */
    INCOMPLETE_NEW_FARM,
    /** Could not repair automatically; the pre-write snapshot is still there. */
    NEEDS_HELP,
}

/**
 * The only place that changes saves. Every write follows R1 (snapshot first), R2 (game not in
 * memory), R3 (all files staged and verified before any live file changes, then renamed with
 * SaveGameInfo last), R6 (no version downgrade, unknown versions blocked), R7 (BACKUP_SAVE put
 * aside, and put back on rollback) and R9 (verify afterwards, roll back on mismatch).
 *
 * A [WriteJournal] entry exists from just before the first live change until the farm is
 * verified. If the app dies in between, [recover] finishes or undoes the write on the next start.
 */
class SaveOps(
    private val fs: SaveFs,
    private val store: SnapshotStore,
    private val tempDir: File,
    private val journal: WriteJournal,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Called once a deliberate write (restore, import) is complete, before its journal entry
     * goes, so the sync intent survives a crash (it is called again by [recover] then).
     */
    private val onDeliberate: (farm: String) -> Unit = {},
) {
    fun restore(snapshot: SnapshotMeta, installedGameVersion: String?) =
        replace(SnapshotSource(store, snapshot), installedGameVersion, SnapshotOrigin.BEFORE_RESTORE, deliberate = true)

    /**
     * Replaces the standard files of an existing farm with [source].
     * [expectedFingerprint]: the farm state the caller planned with (sync); if the farm changed
     * since, nothing is written. [allowMidday] false: a farm with mid-day progress is left alone.
     */
    fun replace(
        source: FileSource,
        installedGameVersion: String?,
        origin: SnapshotOrigin,
        expectedFingerprint: String? = null,
        allowMidday: Boolean = true,
        deliberate: Boolean = false,
    ): OpResult {
        val name = source.farm
        if (journal.has(name)) throw OpBlocked(OpBlocked.Reason.NEEDS_REPAIR)
        val files = checkPreconditions(source, installedGameVersion)
        val farm = currentFarm(name) ?: throw OpBlocked(OpBlocked.Reason.FARM_MISSING)
        if (expectedFingerprint != null && farm.fingerprint != expectedFingerprint) throw OpBlocked(OpBlocked.Reason.CHANGED)

        val before = store.create(fs, farm, origin) // R1
        if (before.fingerprint != farm.fingerprint) throw OpBlocked(OpBlocked.Reason.CHANGED)
        val beforeMd5 = before.files.filter { it.name in SavePaths.stageable(name) }.associate { it.name to it.md5 }
        if (!allowMidday && SavePaths.POINTER in beforeMd5) throw OpBlocked(OpBlocked.Reason.MIDDAY)

        stageAll(source, files) // nothing live has changed if this throws
        val entry = JournalEntry(name, JournalEntry.Kind.REPLACE, before.id, beforeMd5,
            files.associateWith { source.md5(it) }, clock(), deliberate)
        journal.save(entry)

        // Commit boundary: the farm must still be exactly what the snapshot holds (R2, and no
        // progress played since the plan was made).
        val blocked = when {
            fs.isGameRunning() -> OpBlocked.Reason.GAME_RUNNING
            !matches(name, beforeMd5, SavePaths.stageable(name)) -> OpBlocked.Reason.CHANGED
            else -> null
        }
        if (blocked != null) {
            runCatching { fs.discardStaged(name) }
            journal.clear(name)
            throw OpBlocked(blocked)
        }

        try {
            fs.commit(name, commitOrder(files))
            val pointerRemoved = fs.removeMiddayPointer(name) // R7, the pointer is in `before`
            check(matches(name, entry.targetMd5, entry.targetMd5.keys)) { "Verification failed" } // R9
            if (deliberate) onDeliberate(name)
            journal.clear(name)
            return OpResult(before, pointerRemoved)
        } catch (e: Exception) {
            runCatching { fs.discardStaged(name) }
            val back = runCatching { rollback(entry, before) }.isSuccess
            if (back) journal.clear(name)
            throw OpFailed(e.message ?: e.javaClass.simpleName, before, rolledBack = back)
        }
    }

    /** Adds a farm that is not on this device yet, as a new folder. */
    fun addNew(source: FileSource, installedGameVersion: String?, deliberate: Boolean = false): OpResult {
        val name = source.farm
        val pending = journal.get(name)
        if (pending != null && pending.kind != JournalEntry.Kind.NEW_FARM) throw OpBlocked(OpBlocked.Reason.NEEDS_REPAIR)
        val existing = currentFarm(name)
        // A folder left by an interrupted download of this farm may be finished, but only while
        // it still holds nothing but files from that download (never a farm played since).
        if (existing != null && (pending == null || !isOnlyPartOf(name, pending.targetMd5))) {
            throw OpBlocked(OpBlocked.Reason.FARM_EXISTS)
        }
        val files = checkPreconditions(source, installedGameVersion)

        // Recorded before the folder exists, so a folder this app creates is always known as its own.
        val entry = JournalEntry(name, JournalEntry.Kind.NEW_FARM, null, emptyMap(),
            files.associateWith { source.md5(it) }, clock(), deliberate)
        journal.save(entry)
        try {
            if (existing == null) fs.createFarmFolder(name)
            fs.discardStaged(name)
            stageAll(source, files)
            if (fs.isGameRunning()) throw OpBlocked(OpBlocked.Reason.GAME_RUNNING)
            fs.commit(name, commitOrder(files))
            check(matches(name, entry.targetMd5, entry.targetMd5.keys)) { "Verification failed" }
            if (deliberate) onDeliberate(name)
            journal.clear(name)
            return OpResult(before = null, pointerRemoved = false)
        } catch (e: OpBlocked) {
            if (currentFarm(name) == null) journal.clear(name)
            throw e
        } catch (e: Exception) {
            runCatching { fs.discardStaged(name) }
            if (currentFarm(name) == null) journal.clear(name)
            // No delete exists on purpose. The journal keeps the folder marked as ours, so
            // downloading or importing the farm again finishes it.
            throw OpFailed(e.message ?: e.javaClass.simpleName, before = null, rolledBack = false)
        }
    }

    /**
     * Finishes or undoes an interrupted write. Run before any snapshot or sync of that farm.
     * It only ever undoes states the interrupted write itself can explain (every file is either
     * its old or its new version). Anything else means the farm was played since: that state is
     * kept, with a fresh snapshot, and nothing is overwritten.
     */
    fun recover(entry: JournalEntry): Recovery {
        val name = entry.farm
        if (fs.isGameRunning()) return Recovery.WAITING_FOR_GAME
        val farm = currentFarm(name)
        if (farm == null) {
            journal.clear(name) // the folder is gone (or was never created): nothing left to repair
            return Recovery.NOTHING_CHANGED
        }
        runCatching { fs.discardStaged(name) }
        val all = SavePaths.stageable(name)
        val pointerNow = fs.md5("$name/${SavePaths.POINTER}")
        val oldPointer = entry.beforeMd5[SavePaths.POINTER]
        return when {
            // The write finished; at most the old pointer was still to be removed.
            matches(name, entry.targetMd5, entry.targetMd5.keys) && (pointerNow == "" || pointerNow == oldPointer) -> {
                if (oldPointer != null && pointerNow == oldPointer) fs.removeMiddayPointer(name)
                if (entry.deliberate) onDeliberate(name)
                journal.clear(name)
                Recovery.COMPLETED
            }
            entry.kind == JournalEntry.Kind.NEW_FARM -> {
                if (isOnlyPartOf(name, entry.targetMd5)) {
                    Recovery.INCOMPLETE_NEW_FARM // downloading or importing it again finishes it
                } else {
                    keepCurrent(farm, name)
                }
            }
            matches(name, entry.beforeMd5, all) -> {
                journal.clear(name)
                Recovery.NOTHING_CHANGED
            }
            explainedBy(name, entry) -> {
                val before = store.list(name).firstOrNull { it.id == entry.beforeSnapshot } ?: return Recovery.NEEDS_HELP
                if (runCatching { rollback(entry, before) }.isSuccess) {
                    journal.clear(name)
                    Recovery.ROLLED_BACK
                } else {
                    Recovery.NEEDS_HELP
                }
            }
            else -> keepCurrent(farm, name)
        }
    }

    /** The farm was played since the interruption: keep it exactly, with a snapshot. */
    private fun keepCurrent(farm: Farm, name: String): Recovery {
        if (farm.isComplete) runCatching { store.create(fs, farm, SnapshotOrigin.AUTO_OPEN) }
        journal.clear(name)
        return Recovery.KEPT_CURRENT
    }

    /** Every file is either its state before the write or the one the write put there. */
    private fun explainedBy(farm: String, e: JournalEntry) = SavePaths.stageable(farm).all { n ->
        val now = fs.md5("$farm/$n")
        now == (e.beforeMd5[n] ?: "") || (e.targetMd5[n] != null && now == e.targetMd5[n])
    }

    /** The folder holds only (some of) [target]'s files and no pointer: nothing played in it. */
    private fun isOnlyPartOf(farm: String, target: Map<String, String>): Boolean {
        val f = currentFarm(farm) ?: return true
        if (f.files.any { it.name == SavePaths.POINTER }) return false
        return f.files.all { file -> target[file.name]?.let { it == fs.md5("$farm/${file.name}") } == true }
    }

    /** True if the farm is an unfinished new folder from an interrupted download or import. */
    fun isUnfinishedNewFarm(farm: String) = journal.get(farm)?.kind == JournalEntry.Kind.NEW_FARM

    /**
     * Puts back the exact files from before the write: the standard files that existed, then
     * BACKUP_SAVE if there was one (R7), removes `_old` files the write added, and verifies
     * that every file is exactly as before (present or absent).
     */
    private fun rollback(entry: JournalEntry, before: SnapshotMeta) {
        val name = entry.farm
        if (fs.isGameRunning()) error("Stardew is running")
        val back = SnapshotSource(store, before)
        val names = entry.beforeMd5.keys.filter { it in back.names }
        stageAll(back, names)
        fs.commit(name, commitOrder(names))
        // `_old` files the failed write added (absent before) are removed again, but only if they
        // are still exactly what the write put there.
        for (n in entry.targetMd5.keys - entry.beforeMd5.keys) {
            if (fs.md5("$name/$n") == entry.targetMd5[n]) fs.removeIntroducedFile(name, n, entry.targetMd5.getValue(n))
        }
        check(matches(name, entry.beforeMd5, SavePaths.stageable(name))) { "Rollback verification failed" }
    }

    /**
     * R2, R6, R8 on the actual bytes that would be written (not on stored metadata): both
     * required files must be complete, valid saves of this farm, and their own version tag
     * must be one the installed game can open.
     */
    private fun checkPreconditions(source: FileSource, installedGameVersion: String?): List<String> {
        if (fs.isGameRunning()) throw OpBlocked(OpBlocked.Reason.GAME_RUNNING) // R2
        val files = source.names.filter { it in SavePaths.standardFiles(source.farm) }
        if (source.farm !in files || "SaveGameInfo" !in files) throw OpBlocked(OpBlocked.Reason.INCOMPLETE)
        val dir = File(tempDir, "check").apply { deleteRecursively(); mkdirs() }
        val checked = try {
            source.copyTo(source.farm, File(dir, source.farm))
            source.copyTo("SaveGameInfo", File(dir, "SaveGameInfo"))
            SaveXml.check(source.farm, dir)
        } catch (e: SaveXmlException) {
            throw OpBlocked(OpBlocked.Reason.INVALID_SAVE, e.message ?: "invalid")
        } finally {
            dir.deleteRecursively()
        }
        if (!GameVersion.isValid(installedGameVersion) || !GameVersion.isValid(checked.gameVersion)) {
            throw OpBlocked(OpBlocked.Reason.GAME_VERSION_UNKNOWN) // R6 fails closed
        }
        if (!GameVersion.canLoad(checked.gameVersion, installedGameVersion)) {
            throw OpBlocked(OpBlocked.Reason.NEWER_GAME_VERSION, "${checked.gameVersion} > $installedGameVersion")
        }
        return files
    }

    /** Copies every file into the farm's staging area and checks it; live files are untouched. */
    private fun stageAll(source: FileSource, files: List<String>) {
        try {
            for (name in files) {
                val tmp = File(tempDir, "stage-$name").apply { parentFile?.mkdirs() }
                try {
                    val md5 = source.copyTo(name, tmp)
                    fs.stage(source.farm, name, tmp, md5)
                } finally {
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            runCatching { fs.discardStaged(source.farm) }
            throw e
        }
    }

    /** SaveGameInfo after the main file (the load menu reads it), the pointer last of all. */
    private fun commitOrder(files: Collection<String>) =
        files.sortedBy { when (it) { "SaveGameInfo" -> 1; SavePaths.POINTER -> 2; else -> 0 } }

    /** Every name in [names] has the md5 in [expected] (absent from [expected] = must not exist). */
    private fun matches(farm: String, expected: Map<String, String>, names: Collection<String>) =
        names.all { fs.md5("$farm/$it") == (expected[it] ?: "") }

    private fun currentFarm(folder: String): Farm? = FarmScanner(fs).scan().firstOrNull { it.folder == folder }
}
