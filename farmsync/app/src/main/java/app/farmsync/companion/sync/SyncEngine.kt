package app.farmsync.companion.sync

import app.farmsync.companion.ops.ImportCandidate
import app.farmsync.companion.ops.ImportReader
import app.farmsync.companion.ops.OpBlocked
import app.farmsync.companion.ops.OpFailed
import app.farmsync.companion.ops.SaveOps
import app.farmsync.companion.ops.WriteJournal
import app.farmsync.companion.saves.Farm
import app.farmsync.companion.saves.SaveFs
import app.farmsync.companion.store.SnapshotOrigin
import app.farmsync.companion.store.SnapshotStore
import java.io.File

enum class Outcome {
    UP_TO_DATE, UPLOADED, DOWNLOADED, BLOCKED_GAME_RUNNING,
    /** Drive has a newer day, but this device has unsaved mid-day progress: wait for a sleep. */
    WAITING_MIDDAY,
    /** Played on two devices; decided by [ConflictPolicy]: this device's version continues. */
    RESOLVED_KEPT_LOCAL,
    /** Played on two devices; decided by [ConflictPolicy]: the other device's version was downloaded. */
    RESOLVED_TOOK_REMOTE,
    /** The farm was played on this device while the sync ran: synced next time, nothing replaced. */
    RETRY_LATER,
    /** An interrupted write to this farm has to be repaired before it syncs again. */
    NEEDS_REPAIR,
    AVAILABLE, DELETED_HERE, VAULT_CHANGED, FAILED,
}

data class FarmSyncResult(
    val farm: String,
    val outcome: Outcome,
    /** The vault's newest version (for status lines and the download list). */
    val remote: VersionEntry? = null,
    /** A short, non-personal reason code for failures (see Format.failureText). */
    val detail: String? = null,
)

/**
 * Executes the [SyncDecider] decision for one farm. Every change to saves goes through
 * [SaveOps] (snapshot first, game closed, staged and verified, journal, roll back).
 * Uploads only read the farm and add a new file to the vault; they never change existing ones.
 */
class SyncEngine(
    private val fs: SaveFs,
    private val store: SnapshotStore,
    private val vault: VersionVault,
    private val state: SyncState,
    private val tempDir: File,
    private val installedGameVersion: String?,
    private val journal: WriteJournal,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val me get() = state.deviceId
    private val ops get() = SaveOps(fs, store, tempDir, journal, clock)

    fun sync(local: Farm?, farm: String, allowMidday: Boolean = false): FarmSyncResult {
        if (local != null && journal.has(farm)) return FarmSyncResult(farm, Outcome.NEEDS_REPAIR)
        val versions = vault.versions(farm)
        val head = VersionGraph.head(versions)
        val base = state.base(farm)
        val forced = local != null && state.isForced(farm)
        return when (SyncDecider.decide(local?.fingerprint, base, forced, versions)) {
            SyncAction.NONE -> FarmSyncResult(farm, Outcome.UP_TO_DATE)
            SyncAction.REMOTE_ONLY -> FarmSyncResult(farm, Outcome.AVAILABLE, remote = head)
            SyncAction.DELETED_HERE -> FarmSyncResult(farm, Outcome.DELETED_HERE, remote = head)
            SyncAction.UP_TO_DATE -> {
                if (base != head!!.id) state.setBase(farm, head.id)
                FarmSyncResult(farm, Outcome.UP_TO_DATE, remote = head)
            }
            SyncAction.PULL -> pull(local!!, head!!, allowMidday)
            SyncAction.PUSH -> push(local!!, versions, base, forced)
        }
    }

    /** A farm that is only in the vault: add it to this device as a new folder. */
    fun downloadNew(farm: String): FarmSyncResult {
        val head = VersionGraph.head(vault.versions(farm)) ?: return FarmSyncResult(farm, Outcome.FAILED, detail = "no_version")
        return try {
            ops.addNew(fetch(head, farm), installedGameVersion)
                .also { File(tempDir, "sync").deleteRecursively() }
            state.setBase(farm, head.id)
            state.setEnabled(farm, true)
            FarmSyncResult(farm, Outcome.DOWNLOADED, remote = head)
        } catch (e: OpBlocked) {
            blocked(farm, e, head)
        } catch (e: OpFailed) {
            FarmSyncResult(farm, Outcome.FAILED, remote = head, detail = "write_failed")
        }
    }

    /**
     * New play (or a deliberate restore) here: upload it first, then let the shared version set
     * decide. Never loses anything: our version is in the vault before anything is replaced.
     */
    private fun push(local: Farm, versions: List<VersionEntry>, base: String?, forced: Boolean): FarmSyncResult {
        val farm = local.folder
        // Continues from the base and from every other upload of the very same save (two devices
        // that uploaded identical saves are one point in history, not two lines of play).
        val baseFp = versions.firstOrNull { it.id == base }?.fingerprint
        val equivalent = versions.filter { baseFp != null && it.fingerprint == baseFp }.map { it.id }
        // A deliberate restore replaces every current line of play by naming them all as its
        // parents, in one immutable upload: nothing changes in the vault until it is there (S01).
        val tips = if (forced) VersionGraph.tips(versions).filter { !it.lost }.sortedWith(ConflictPolicy.order.reversed()) else emptyList()
        val wanted = (listOfNotNull(base) + equivalent + tips.map { it.id }).distinct()
        val parents = wanted.take(VersionProps.MAX_PARENTS)
        // An upload of exactly this that an interrupted run left behind is reused, not repeated.
        val mine = versions.firstOrNull { it.deviceId == me && it.fingerprint == local.fingerprint && it.parents == parents }
            ?: upload(local, parents, deliberate = forced)

        val after = vault.versions(farm)
        // Report success only once the new version can be found by every device. Until then
        // nothing is recorded here, so the next run finds and reuses this upload.
        if (after.none { it.id == mine.id }) return FarmSyncResult(farm, Outcome.FAILED, detail = "not_visible")
        if (forced) {
            // Only if there were more lines than fit (practically never): the rest are marked
            // now, after the restore is safely visible.
            tips.filter { it.id !in parents }.forEach { runCatching { vault.markLost(it) } }
            state.setForced(farm, false)
        }
        state.setBase(farm, mine.id)
        val head = VersionGraph.head(after)!!
        val conflict = VersionGraph.inConflict(after)
        if (conflict) markLosers(after, head)
        if (head.fingerprint == local.fingerprint) {
            return FarmSyncResult(farm, if (conflict) Outcome.RESOLVED_KEPT_LOCAL else Outcome.UPLOADED, remote = head)
        }
        // Another device's line of play wins: ours stays in the vault (and as a snapshot here).
        val r = pull(local, head, allowMidday = false)
        return if (r.outcome == Outcome.DOWNLOADED && conflict) r.copy(outcome = Outcome.RESOLVED_TOOK_REMOTE) else r
    }

    private fun pull(local: Farm, head: VersionEntry, allowMidday: Boolean): FarmSyncResult {
        val farm = local.folder
        if (local.midday && !allowMidday) return FarmSyncResult(farm, Outcome.WAITING_MIDDAY, remote = head)
        return try {
            pullNow(local, head, allowMidday)
        } finally {
            File(tempDir, "sync").deleteRecursively() // no downloaded copy left behind (F14)
        }
    }

    private fun pullNow(local: Farm, head: VersionEntry, allowMidday: Boolean): FarmSyncResult {
        val farm = local.folder
        return try {
            val candidate = fetch(head, farm)
            // The farm must still be what this decision was based on (F3): if it was played
            // while the download ran, nothing is replaced and it syncs next time.
            ops.replace(candidate, installedGameVersion, SnapshotOrigin.BEFORE_SYNC,
                expectedFingerprint = local.fingerprint, allowMidday = allowMidday)
            state.setBase(farm, head.id)
            FarmSyncResult(farm, Outcome.DOWNLOADED, remote = head)
        } catch (e: OpBlocked) {
            blocked(farm, e, head)
        } catch (e: OpFailed) {
            when {
                e.keptCurrent -> FarmSyncResult(farm, Outcome.RETRY_LATER, remote = head) // the game saved meanwhile
                e.rolledBack -> FarmSyncResult(farm, Outcome.FAILED, remote = head, detail = "write_failed_rolled_back")
                else -> FarmSyncResult(farm, Outcome.NEEDS_REPAIR, remote = head, detail = "write_failed")
            }
        }
    }

    private fun markLosers(all: List<VersionEntry>, head: VersionEntry) {
        VersionGraph.tips(all).filter { !it.lost && it.id != head.id }.forEach { runCatching { vault.markLost(it) } }
    }

    private fun blocked(farm: String, e: OpBlocked, head: VersionEntry) = when (e.reason) {
        OpBlocked.Reason.GAME_RUNNING -> FarmSyncResult(farm, Outcome.BLOCKED_GAME_RUNNING, remote = head)
        OpBlocked.Reason.CHANGED -> FarmSyncResult(farm, Outcome.RETRY_LATER, remote = head)
        OpBlocked.Reason.MIDDAY -> FarmSyncResult(farm, Outcome.WAITING_MIDDAY, remote = head)
        OpBlocked.Reason.NEEDS_REPAIR -> FarmSyncResult(farm, Outcome.NEEDS_REPAIR, remote = head)
        else -> FarmSyncResult(farm, Outcome.FAILED, remote = head, detail = e.reason.name.lowercase())
    }

    /** Uploads the farm's current state (via a snapshot zip) as a new version. */
    private fun upload(local: Farm, parents: List<String>, deliberate: Boolean): VersionEntry {
        val snap = store.list(local.folder).firstOrNull { it.contentHash == local.contentHash }
            ?: store.create(fs, local, SnapshotOrigin.SYNC)
        check(snap.fingerprint == local.fingerprint) { "Snapshot does not match the farm" }
        val entry = VersionEntry(
            id = VersionEntry.newId(), fingerprint = local.fingerprint, fileId = "", parents = parents,
            deviceId = me, deviceName = state.deviceName, created = clock(),
            savedAt = local.files.firstOrNull { it.name == "SaveGameInfo" }?.mtime ?: 0L,
            info = local.info, deliberate = deliberate,
        )
        return vault.upload(local.folder, entry, store.zipFile(snap))
    }

    /** Downloads a version and validates it like any import (folder, ID, files, fingerprint). */
    private fun fetch(v: VersionEntry, farm: String): ImportCandidate {
        val work = File(tempDir, "sync").apply { deleteRecursively(); mkdirs() }
        val zip = File(work, "version.zip")
        vault.download(v, zip)
        val c = ImportReader.read(zip, File(work, "x"))
        check(c.farm == farm) { "Vault file belongs to another farm" }
        check(c.md5(farm) + c.md5("SaveGameInfo") == v.fingerprint) { "Downloaded version does not match its fingerprint" }
        return c
    }
}
