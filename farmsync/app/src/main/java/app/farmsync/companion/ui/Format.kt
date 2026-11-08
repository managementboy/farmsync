package app.farmsync.companion.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import app.farmsync.companion.R
import app.farmsync.companion.saves.SaveInfo
import app.farmsync.companion.store.SnapshotOrigin
import java.text.DateFormat
import java.util.Date

@Composable
fun gameDate(info: SaveInfo): String {
    val season = stringArrayResource(R.array.seasons).getOrElse(info.season) { "?" }
    val h = info.playedMs / 3_600_000
    val m = info.playedMs / 60_000 % 60
    return stringResource(R.string.farm_date, info.year, season, info.day, h, m)
}

fun dateTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

fun sizeText(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

@Composable
fun originText(origin: SnapshotOrigin): String = stringResource(
    when (origin) {
        SnapshotOrigin.MANUAL -> R.string.origin_manual
        SnapshotOrigin.AUTO_OPEN -> R.string.origin_auto_open
        SnapshotOrigin.BEFORE_RESTORE -> R.string.origin_before_restore
        SnapshotOrigin.BEFORE_IMPORT -> R.string.origin_before_import
        SnapshotOrigin.SYNC -> R.string.origin_sync
        SnapshotOrigin.BEFORE_SYNC -> R.string.origin_before_sync
    }
)

@Composable
fun syncStatusText(enabled: Boolean, r: app.farmsync.companion.sync.FarmSyncResult?): String {
    if (!enabled) return stringResource(R.string.sync_off)
    if (r == null) return stringResource(R.string.sync_not_yet)
    return stringResource(
        when (r.outcome) {
            app.farmsync.companion.sync.Outcome.UP_TO_DATE -> R.string.sync_up_to_date
            app.farmsync.companion.sync.Outcome.UPLOADED -> R.string.sync_uploaded
            app.farmsync.companion.sync.Outcome.DOWNLOADED -> R.string.sync_downloaded
            app.farmsync.companion.sync.Outcome.CONFLICT -> R.string.sync_conflict
            app.farmsync.companion.sync.Outcome.NEEDS_CONFIRM_MIDDAY -> R.string.sync_needs_confirm
            app.farmsync.companion.sync.Outcome.BLOCKED_GAME_RUNNING -> R.string.sync_blocked_game
            app.farmsync.companion.sync.Outcome.AVAILABLE -> R.string.sync_available
            app.farmsync.companion.sync.Outcome.DELETED_HERE -> R.string.sync_deleted_here
            app.farmsync.companion.sync.Outcome.VAULT_CHANGED -> R.string.sync_vault_changed
            app.farmsync.companion.sync.Outcome.FAILED -> R.string.sync_failed
        }
    )
}
