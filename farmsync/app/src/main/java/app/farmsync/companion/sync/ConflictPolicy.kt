package app.farmsync.companion.sync

import app.farmsync.companion.saves.SaveInfo

/**
 * Decides a conflict (farm played on this device AND elsewhere since the last sync) without
 * asking the user. Nothing is ever merged and nothing is lost: the losing version stays in the
 * vault, and if this device's version loses, a local snapshot keeps it too.
 */
object ConflictPolicy {
    /**
     * True if this device's version should continue.
     *  1. Unsaved mid-day progress here → keep this device (it holds more than its saved day).
     *  2. More play time wins (play time only grows while playing; clocks are not trusted).
     *  3. Equal play time → the later in-game date, then the later save time.
     */
    fun keepLocal(
        local: SaveInfo?,
        localMidday: Boolean,
        localSavedAt: Long,
        remote: SaveInfo?,
        remoteSavedAt: Long,
    ): Boolean {
        if (localMidday) return true
        if (local == null) return false
        if (remote == null) return true
        if (local.playedMs != remote.playedMs) return local.playedMs > remote.playedMs
        val dayCmp = compareValuesBy(local, remote, { it.year }, { it.season }, { it.day })
        if (dayCmp != 0) return dayCmp > 0
        return localSavedAt >= remoteSavedAt
    }
}
