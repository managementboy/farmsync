package app.farmsync.companion.sync

/**
 * Decides a conflict (the farm was played on two devices from the same starting point) without
 * asking. Nothing is ever merged and nothing is lost: the losing version stays in the vault, and
 * the device that had it also keeps a local snapshot.
 *
 * The decision uses only what is recorded in the versions themselves, so every device reaches
 * the same answer no matter which one compares, when the uploads happened, or how far the
 * device clocks are apart:
 *  1. More play time wins (play time only grows while playing).
 *  2. Equal → the later in-game date.
 *  3. Equal → the later save time recorded with each version (both from the save files).
 *  4. Equal → the version id, so there is always exactly one answer.
 *
 * Unsaved mid-day progress is not part of this: a device with mid-day progress does not take a
 * download until the player sleeps (WAITING_MIDDAY); its next save then counts in full.
 */
object ConflictPolicy {
    val order: Comparator<VersionEntry> = compareBy<VersionEntry>(
        { it.info?.playedMs ?: -1L },
        { it.info?.year ?: -1 },
        { it.info?.season ?: -1 },
        { it.info?.day ?: -1 },
        { it.savedAt },
        { it.id },
    )

    /** The winner among [candidates] (the highest in [order]). */
    fun winner(candidates: List<VersionEntry>): VersionEntry? = candidates.maxWithOrNull(order)
}
