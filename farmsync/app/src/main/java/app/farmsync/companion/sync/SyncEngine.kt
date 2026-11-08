package app.farmsync.companion.sync

import app.farmsync.companion.ISaveFs
import app.farmsync.companion.ops.ImportReader
import app.farmsync.companion.ops.OpBlocked
import app.farmsync.companion.ops.SaveOps
import app.farmsync.companion.saves.Farm
import app.farmsync.companion.store.SnapshotOrigin
import app.farmsync.companion.store.SnapshotStore
import java.io.File

enum class Outcome {
    UP_TO_DATE, UPLOADED, DOWNLOADED, CONFLICT, BLOCKED_GAME_RUNNING,
    /** Drive has a newer day, but this device has unsaved mid-day progress: wait for a sleep. */
    WAITING_MIDDAY,
    /** Conflict decided by [ConflictPolicy]: this device's version continues. */
    RESOLVED_KEPT_LOCAL,
    /** Conflict decided by [ConflictPolicy]: the other device's version was downloaded. */
    RESOLVED_TOOK_REMOTE,
    AVAILABLE, DELETED_HERE, VAULT_CHANGED, FAILED,
}

data class FarmSyncResult(
    val farm: String,
    val outcome: Outcome,
    /** The vault's newest version (for conflict / download dialogs). */
    val remote: VersionEntry? = null,
    /** This device's version when it is in conflict. */
    val local: VersionEntry? = null,
    val detail: String? = null,
)

/**
 * Executes the [SyncDecider] decision for one farm. Every change to saves goes through
 * [SaveOps] (snapshot first, game must be closed, verify, roll back). Uploads only read.
 */
class SyncEngine(
    private val fs: ISaveFs,
    private val store: SnapshotStore,
    private val vault: Vault,
    private val prefs: SyncPrefs,
    private val tempDir: File,
    private val installedGameVersion: String?,
) {
    private val me get() = prefs.deviceId

    fun sync(local: Farm?, farm: String, allowMidday: Boolean = false): FarmSyncResult {
        val manifest = vault.readManifest(farm)
        val base = prefs.base(farm)
        return when (SyncDecider.decide(local?.fingerprint, base, manifest)) {
            SyncAction.NONE -> FarmSyncResult(farm, Outcome.UP_TO_DATE)
            SyncAction.CREATE, SyncAction.PUSH -> push(local!!, manifest, base)
            SyncAction.UP_TO_DATE -> {
                if (manifest!!.devices[me]?.has != manifest.head || base != manifest.head) {
                    prefs.setBase(farm, manifest.head)
                    writeDevice(farm, manifest.head, local!!.midday)
                }
                FarmSyncResult(farm, Outcome.UP_TO_DATE, remote = manifest.headVersion)
            }
            SyncAction.PULL -> pull(local!!, manifest!!, allowMidday)
            SyncAction.CONFLICT -> resolveAutomatically(local!!, manifest!!, base)
            SyncAction.REMOTE_ONLY -> FarmSyncResult(farm, Outcome.AVAILABLE, remote = manifest!!.headVersion)
            SyncAction.DELETED_HERE -> {
                if (manifest!!.devices[me]?.deletedHere != true) writeDevice(farm, base, false, deletedHere = true)
                FarmSyncResult(farm, Outcome.DELETED_HERE, remote = manifest.headVersion)
            }
        }
    }

    /**
     * Both this device and another one moved the farm on. Decided by [ConflictPolicy] without
     * asking. The losing version is kept: in the vault always, and as a local snapshot when this
     * device's version is replaced.
     */
    private fun resolveAutomatically(local: Farm, m: Manifest, base: String?): FarmSyncResult {
        val farm = local.folder
        val head = m.headVersion ?: return push(local, m, base)
        val localSavedAt = local.files.firstOrNull { it.name == "SaveGameInfo" }?.mtime ?: 0L
        val keepMine = ConflictPolicy.keepLocal(local.info, local.midday, localSavedAt, head.info, head.created)
        return if (keepMine) {
            keepLocal(local).copy(outcome = Outcome.RESOLVED_KEPT_LOCAL, remote = head)
        } else {
            // Park this device's version in the vault (not as head) before replacing it here.
            val mine = m.versions[local.fingerprint] ?: upload(local, base, conflict = false)
            val fresh = vault.readManifest(farm) ?: m
            vault.writeManifest(fresh.copy(versions = fresh.versions + (mine.id to mine.copy(conflict = false))))
            val r = pull(local, vault.readManifest(farm)!!, allowMidday = false)
            if (r.outcome == Outcome.DOWNLOADED) r.copy(outcome = Outcome.RESOLVED_TOOK_REMOTE, local = mine) else r
        }
    }

    /** Conflict resolved by the user: the version on this device becomes the newest everywhere. */
    fun keepLocal(local: Farm): FarmSyncResult {
        val farm = local.folder
        val fresh = vault.readManifest(farm) ?: Manifest.empty(farm)
        val entry = fresh.versions[local.fingerprint] ?: upload(local, prefs.base(farm), conflict = false)
        val now = System.currentTimeMillis()
        val versions = fresh.versions + (entry.id to entry.copy(conflict = false))
        val updated = fresh.copy(
            head = entry.id,
            versions = versions,
            devices = fresh.devices + (me to DeviceEntry(prefs.deviceName, entry.id, now, local.midday)),
        )
        vault.writeManifest(updated.mergedOnto(vault.readManifest(farm)))
        prefs.setBase(farm, entry.id)
        return FarmSyncResult(farm, Outcome.UPLOADED, remote = entry)
    }

    /** Conflict resolved by the user: take the vault's newest version (a safety snapshot keeps ours). */
    fun keepRemote(local: Farm): FarmSyncResult {
        val farm = local.folder
        val m = vault.readManifest(farm) ?: return FarmSyncResult(farm, Outcome.FAILED, detail = "no manifest")
        m.versions[local.fingerprint]?.takeIf { it.conflict }?.let { mine ->
            vault.writeManifest(m.copy(versions = m.versions + (mine.id to mine.copy(conflict = false)))
                .mergedOnto(vault.readManifest(farm)))
        }
        return pull(local, vault.readManifest(farm)!!, allowMidday = true)
    }

    /** A farm that is only in the vault: add it to this device as a new folder. */
    fun downloadNew(farm: String): FarmSyncResult {
        val m = vault.readManifest(farm) ?: return FarmSyncResult(farm, Outcome.FAILED, detail = "no manifest")
        val head = m.headVersion ?: return FarmSyncResult(farm, Outcome.FAILED, detail = "no version")
        return try {
            val candidate = fetch(head, farm)
            SaveOps(fs, store, tempDir).addNew(candidate, installedGameVersion)
            prefs.setBase(farm, head.id)
            prefs.setEnabled(farm, true)
            writeDevice(farm, head.id, false)
            FarmSyncResult(farm, Outcome.DOWNLOADED, remote = head)
        } catch (e: OpBlocked) {
            blocked(farm, e, head)
        }
    }

    private fun push(local: Farm, seen: Manifest?, base: String?): FarmSyncResult {
        val farm = local.folder
        val entry = upload(local, base, conflict = false)
        val fresh = vault.readManifest(farm)
        if (fresh?.head != seen?.head) {
            // Another device moved the farm on while we uploaded: do not overwrite its head.
            return resolveAutomatically(local, fresh!!, base)
        }
        val now = System.currentTimeMillis()
        val updated = (fresh ?: Manifest.empty(farm)).let { m ->
            m.copy(
                head = entry.id,
                versions = m.versions + (entry.id to entry),
                devices = m.devices + (me to DeviceEntry(prefs.deviceName, entry.id, now, local.midday)),
            )
        }
        vault.writeManifest(updated)
        prefs.setBase(farm, entry.id)
        return FarmSyncResult(farm, Outcome.UPLOADED, remote = entry)
    }

    private fun pull(local: Farm, m: Manifest, allowMidday: Boolean): FarmSyncResult {
        val farm = local.folder
        val head = m.headVersion ?: return FarmSyncResult(farm, Outcome.FAILED, detail = "no head")
        if (local.midday && !allowMidday) return FarmSyncResult(farm, Outcome.WAITING_MIDDAY, remote = head)
        return try {
            val candidate = fetch(head, farm)
            SaveOps(fs, store, tempDir).replace(candidate, installedGameVersion, SnapshotOrigin.BEFORE_SYNC)
            prefs.setBase(farm, head.id)
            writeDevice(farm, head.id, false)
            FarmSyncResult(farm, Outcome.DOWNLOADED, remote = head)
        } catch (e: OpBlocked) {
            blocked(farm, e, head)
        }
    }

    private fun blocked(farm: String, e: OpBlocked, head: VersionEntry) =
        if (e.reason == OpBlocked.Reason.GAME_RUNNING) FarmSyncResult(farm, Outcome.BLOCKED_GAME_RUNNING, remote = head)
        else FarmSyncResult(farm, Outcome.FAILED, remote = head, detail = e.reason.name)

    /** Uploads the farm's current state (via a snapshot zip) and returns its version entry. */
    private fun upload(local: Farm, parent: String?, conflict: Boolean): VersionEntry {
        val snap = store.list(local.folder).firstOrNull { it.contentHash == local.contentHash }
            ?: store.create(fs, local, SnapshotOrigin.SYNC)
        check(snap.fingerprint == local.fingerprint) { "Snapshot does not match the farm" }
        val fileId = vault.uploadVersion(local.folder, local.fingerprint, store.zipFile(snap).readBytes())
        return VersionEntry(
            id = local.fingerprint, fileId = fileId, parent = parent, deviceId = me,
            deviceName = prefs.deviceName, created = System.currentTimeMillis(), info = local.info,
            conflict = conflict,
        )
    }

    /** Downloads a version and validates it like any import (folder, ID, files, fingerprint). */
    private fun fetch(v: VersionEntry, farm: String): app.farmsync.companion.ops.ImportCandidate {
        val work = File(tempDir, "sync").apply { deleteRecursively(); mkdirs() }
        val zip = File(work, "version.zip")
        vault.downloadVersion(v.fileId, zip)
        val c = ImportReader.read(zip, File(work, "x"))
        check(c.farm == farm) { "Vault file belongs to ${c.farm}, expected $farm" }
        check(c.md5(farm) + c.md5("SaveGameInfo") == v.id) { "Downloaded version does not match its fingerprint" }
        return c
    }

    private fun writeDevice(farm: String, has: String?, midday: Boolean, deletedHere: Boolean = false) {
        val fresh = vault.readManifest(farm) ?: return
        val entry = DeviceEntry(prefs.deviceName, has, System.currentTimeMillis(), midday, deletedHere)
        vault.writeManifest(fresh.copy(devices = fresh.devices + (me to entry)))
    }
}
