package app.farmsync.companion.sync

import app.farmsync.companion.saves.SaveInfo

/**
 * Decides a conflict (farm played on this device AND elsewhere since the last sync) without
 * asking the user. Nothing is ever merged and nothing is lost: the losing version stays in the
 * vault, and if this device's version loses, a local snapshot keeps it too.
 */
object ConflictPolicy {
    /** Unsaved mid-day progress is at most about one in-game day of real play time. */
    const val MIDDAY_BONUS_MS = 20 * 60 * 1000L

    /**
     * True if this device's version should continue.
     *  1. More play time wins (play time only grows while playing; clocks are not trusted).
     *     Unsaved mid-day progress here counts as up to [MIDDAY_BONUS_MS] extra, so it wins
     *     close calls but never beats a device that clearly played further.
     *  2. Equal → the later in-game date, then the later save time.
     */
    fun keepLocal(
        local: SaveInfo?,
        localMidday: Boolean,
        localSavedAt: Long,
        remote: SaveInfo?,
        remoteSavedAt: Long,
    ): Boolean {
        if (local == null) return false
        if (remote == null) return true
        val localPlayed = local.playedMs + if (localMidday) MIDDAY_BONUS_MS else 0L
        if (localPlayed != remote.playedMs) return localPlayed > remote.playedMs
        val dayCmp = compareValuesBy(local, remote, { it.year }, { it.season }, { it.day })
        if (dayCmp != 0) return dayCmp > 0
        return localSavedAt >= remoteSavedAt
    }
}
