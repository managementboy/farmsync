package app.farmsync.companion.sync

enum class SyncAction {
    /** Nothing in the vault and nothing here. */
    NONE,
    /** Farm is here but not yet in the vault: upload as the first version. */
    CREATE,
    /** This device has exactly the vault's newest version. */
    UP_TO_DATE,
    /** Played here since the last sync, nobody else moved on: upload, it becomes the newest. */
    PUSH,
    /** Not played here since a known version, the vault has a newer one: download it. */
    PULL,
    /** Played here AND the vault moved on (or the lineage is unknown): the user must choose. */
    CONFLICT,
    /** In the vault, never on this device: offer to download, never automatic. */
    REMOTE_ONLY,
    /** The user deleted it on this device after syncing: never re-create it here (R5). */
    DELETED_HERE,
}

/**
 * The sync decision for one farm, from three facts only:
 *  - [localFp]: fingerprint of the farm on this device (null = not on this device)
 *  - [base]: the version this device had after its last sync of this farm (null = never synced)
 *  - the vault manifest.
 * Decides by lineage, never by clocks; never merges two states.
 */
object SyncDecider {
    fun decide(localFp: String?, base: String?, manifest: Manifest?): SyncAction {
        val head = manifest?.head
        if (manifest == null || head == null) {
            return if (localFp != null) SyncAction.CREATE else SyncAction.NONE
        }
        if (localFp == null) {
            return if (base != null) SyncAction.DELETED_HERE else SyncAction.REMOTE_ONLY
        }
        if (localFp == head) return SyncAction.UP_TO_DATE
        val known = manifest.versions[localFp]
        if (known != null) {
            // Local is a version the vault already knows, so nothing was played here since.
            // An unresolved conflict version stays a conflict until the user chooses.
            return if (known.conflict) SyncAction.CONFLICT else SyncAction.PULL
        }
        // Local is new: played here. Fast-forward only if nobody else moved on since our base.
        return if (base != null && base == head) SyncAction.PUSH else SyncAction.CONFLICT
    }
}
