package app.farmsync.companion.sync

enum class SyncAction {
    /** Nothing in the vault and nothing here. */
    NONE,
    /** This device has the content of the newest version. */
    UP_TO_DATE,
    /** Played here since the last sync (or restored on purpose): upload as a new version. */
    PUSH,
    /** Not played here since a version the vault knows, and the vault has a newer one. */
    PULL,
    /** In the vault, never on this device: offer to download, never automatic. */
    REMOTE_ONLY,
    /** The user deleted it on this device after syncing: never re-create it here (R5). */
    DELETED_HERE,
}

/**
 * The sync decision for one farm, from these facts only:
 *  - [localFp]: fingerprint of the farm on this device (null = not on this device)
 *  - [base]: the version id this device had after its last sync of this farm (null = never)
 *  - [forced]: the user restored or imported this farm on purpose since the last sync
 *  - every version in the vault.
 * Decides by content and lineage, never by device clocks; never merges two saves.
 * Uploading first and deciding afterwards means a device never throws away its own new play:
 * new play is always pushed, and only then [VersionGraph.head] decides who continues.
 */
object SyncDecider {
    fun decide(localFp: String?, base: String?, forced: Boolean, versions: List<VersionEntry>): SyncAction {
        val head = VersionGraph.head(versions)
        if (localFp == null) {
            return when {
                head == null -> SyncAction.NONE
                base != null -> SyncAction.DELETED_HERE
                else -> SyncAction.REMOTE_ONLY
            }
        }
        if (head == null) return SyncAction.PUSH
        if (forced) return SyncAction.PUSH
        if (localFp == head.fingerprint) return SyncAction.UP_TO_DATE
        val baseVersion = versions.firstOrNull { it.id == base }
        // Unchanged since the last sync: nothing new here, take the newest.
        if (baseVersion != null && baseVersion.fingerprint == localFp) return SyncAction.PULL
        // Never synced (or the base is unknown) but the vault already has exactly this save.
        if (baseVersion == null && versions.any { it.fingerprint == localFp }) return SyncAction.PULL
        return SyncAction.PUSH
    }
}
