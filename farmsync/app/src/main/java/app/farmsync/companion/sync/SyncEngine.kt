package app.farmsync.companion.sync

import app.farmsync.companion.ops.ImportCandidate
import app.farmsync.companion.ops.ImportReader
import app.farmsync.companion.ops.OpBlocked
import app.farmsync.companion.ops.OpFailed
import app.farmsync.companion.ops.SaveOps
import app.farmsync.companion.ops.WriteJournal
import app.farmsync.companion.saves.Farm
import app.farmsync.companion.saves.FarmScanner
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

    /**
     * The Download action for a farm that is only in the vault. It only ever downloads, never
     * uploads (U01): it adds the farm as a new folder, or finishes this app's own interrupted
     * download of it. A farm that is on this device for any other reason (an import since the
     * row appeared, say) is left exactly as it is, and so is the vault.
     */
    fun downloadNew(farm: String): FarmSyncResult {
        val head = VersionGraph.head(vault.versions(farm)) ?: return FarmSyncResult(farm, Outcome.FAILED, detail = "no_version")
        return try {
            val candidate = fetch(head, farm)
            try {
                ops.addNew(candidate, installedGameVersion)
            } catch (e: OpBlocked) {
                if (e.reason != OpBlocked.Reason.FINISHED_EARLIER) throw e
                // Our earlier download ended up usable and unchanged: update it like a pull,
                // with a verified snapshot and the live state rechecked right before the commit.
                val local = FarmScanner(fs).scan().firstOrNull { it.folder == farm } ?: throw OpBlocked(OpBlocked.Reason.CHANGED)
                ops.replace(candidate, installedGameVersion, SnapshotOrigin.BEFORE_SYNC,
                    expectedFingerprint = local.fingerprint, allowMidday = false)
            }
            state.setBase(farm, head.id)
            state.setEnabled(farm, true)
            FarmSyncResult(farm, Outcome.DOWNLOADED, remote = head)
        } catch (e: OpBlocked) {
            if (e.reason == OpBlocked.Reason.FARM_EXISTS) FarmSyncResult(farm, Outcome.FAILED, remote = head, detail = "exists_here")
            else blocked(farm, e, head)
        } catch (e: OpFailed) {
            when {
                e.keptCurrent -> FarmSyncResult(farm, Outcome.RETRY_LATER, remote = head)
                else -> FarmSyncResult(farm, Outcome.FAILED, remote = head, detail = "write_failed")
            }
        } finally {
            File(tempDir, "sync").deleteRecursively()
        }
    }

    /**
     * New play (or a deliberate restore) here: upload it first, then let the shared version set
     * decide. Never loses anything: our version is in the vault before anything is replaced.
     */
    private fun push(local: Farm, versions: List<VersionEntry>, base: String?, forced: Boolean): FarmSyncResult {
        val farm = local.folder
        val allTips = VersionGraph.tips(versions)
        val wanted = if (forced) {
            // A deliberate restore replaces exactly the current, open lines of play: it names
            // them all as its parents, in one immutable upload, so nothing changes in the vault
            // until it is there (S01). Older history and copies are already covered by those
            // lines and are not listed (U03). The restore is flagged deliberate, so it is a new
            // end of history even when its save matches an old one.
            allTips.filter { !it.lost }.ifEmpty { listOfNotNull(VersionGraph.head(versions)) }
                .sortedWith(ConflictPolicy.order.reversed()).map { it.id }
        } else {
            // Normal play continues from the base and from any other upload of the very same save
            // that is still an end (identical uploads are one point in history, not two lines).
            val baseFp = versions.firstOrNull { it.id == base }?.fingerprint
            val copies = allTips.filter { baseFp != null && it.fingerprint == baseFp }.map { it.id }
            (listOfNotNull(base) + copies).distinct()
        }
        // A restore must name every open line it replaces. If that can't be written (more than
        // 72 open lines, practically never), nothing is published and it stays pending (T02).
        if (forced && wanted.size > VersionProps.MAX_PARENTS) return FarmSyncResult(farm, Outcome.FAILED, detail = "too_many_branches")
        val parents = wanted.take(VersionProps.MAX_PARENTS)
        // An upload of exactly this that an interrupted run left behind is reused, not repeated.
        val mine = versions.firstOrNull { it.deviceId == me && it.fingerprint == local.fingerprint && it.parents == parents }
            ?: upload(local, parents, deliberate = forced)

        val after = vault.versions(farm)
        // Report success only once the new version can be found by every device. Until then
        // nothing is recorded here, so the next run finds and reuses this upload.
        if (after.none { it.id == mine.id }) return FarmSyncResult(farm, Outcome.FAILED, detail = "not_visible")
        if (forced) state.setForced(farm, false)
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
