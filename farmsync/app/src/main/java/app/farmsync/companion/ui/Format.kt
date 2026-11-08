package app.farmsync.companion.ui

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import app.farmsync.companion.R
import app.farmsync.companion.saves.SaveInfo
import app.farmsync.companion.store.SnapshotOrigin
import app.farmsync.companion.sync.FarmSyncResult
import app.farmsync.companion.sync.Outcome
import app.farmsync.companion.ui.theme.Farm
import java.util.Calendar
import java.util.Date

/** "24 de verano · año 1" */
@Composable
fun gameDay(info: SaveInfo): String {
    val season = stringArrayResource(R.array.seasons).getOrElse(info.season) { "?" }
    return stringResource(R.string.game_day, info.day, season, info.year)
}

/** "13 h 47 min" */
@Composable
fun playTime(info: SaveInfo): String {
    val h = info.playedMs / 3_600_000
    val m = info.playedMs / 60_000 % 60
    return stringResource(R.string.play_time, h, m)
}

/** Day plus play time, for dialogs. */
@Composable
fun gameDate(info: SaveInfo): String = gameDay(info) + " · " + playTime(info)

/** "Hoy, 16:12" / "Ayer, 09:03" / "3 nov, 18:40" */
@Composable
fun whenText(ms: Long): String {
    val ctx = LocalContext.current
    val time = DateFormat.getTimeFormat(ctx).format(Date(ms))
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun same(a: Calendar, b: Calendar) =
        a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        same(then, today) -> stringResource(R.string.today_at, time)
        same(then, yesterday) -> stringResource(R.string.yesterday_at, time)
        else -> DateUtils.formatDateTime(ctx, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH) + ", " + time
    }
}

/** "hace 2 min", or "ahora mismo" within the first minute (never "hace 0 minutos"). */
@Composable
fun relative(ms: Long): String =
    if (System.currentTimeMillis() - ms < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.just_now)
    else DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

fun dateTime(ms: Long): String =
    java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(Date(ms))

fun sizeText(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

@DrawableRes
fun originIcon(origin: SnapshotOrigin): Int = when (origin) {
    SnapshotOrigin.MANUAL -> R.drawable.pi_human
    SnapshotOrigin.AUTO_OPEN -> R.drawable.pi_clock
    SnapshotOrigin.BEFORE_RESTORE -> R.drawable.pi_undo
    SnapshotOrigin.BEFORE_IMPORT -> R.drawable.pi_download
    SnapshotOrigin.SYNC -> R.drawable.pi_upload
    SnapshotOrigin.BEFORE_SYNC -> R.drawable.pi_download
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

/** How one farm's sync state is shown: icon, short text, colour (never colour alone). */
data class StatusLook(@DrawableRes val icon: Int, val text: String, val color: Color)

@Composable
fun syncLook(enabled: Boolean, r: FarmSyncResult?): StatusLook {
    val c = Farm.colors
    if (!enabled) return StatusLook(R.drawable.pi_cloud, stringResource(R.string.st_off), c.ink2)
    if (r == null) return StatusLook(R.drawable.pi_cloud, stringResource(R.string.st_not_yet), c.ink2)
    return when (r.outcome) {
        Outcome.UP_TO_DATE -> StatusLook(R.drawable.pi_cloud, stringResource(R.string.st_synced), c.meadow)
        Outcome.UPLOADED -> StatusLook(R.drawable.pi_upload, stringResource(R.string.st_uploaded), c.meadow)
        Outcome.DOWNLOADED -> StatusLook(R.drawable.pi_download, stringResource(R.string.st_downloaded), c.meadow)
        Outcome.RESOLVED_KEPT_LOCAL -> StatusLook(R.drawable.pi_check, stringResource(R.string.st_resolved_local), c.meadow)
        Outcome.RESOLVED_TOOK_REMOTE -> StatusLook(R.drawable.pi_download, stringResource(R.string.st_resolved_remote), c.meadow)
        Outcome.WAITING_MIDDAY -> StatusLook(R.drawable.pi_clock, stringResource(R.string.st_waiting_midday), c.harvest)
        Outcome.BLOCKED_GAME_RUNNING -> StatusLook(R.drawable.pi_pause, stringResource(R.string.st_game_open), c.harvest)
        Outcome.RETRY_LATER -> StatusLook(R.drawable.pi_clock, stringResource(R.string.st_retry_later), c.harvest)
        Outcome.NEEDS_REPAIR -> StatusLook(R.drawable.pi_square_alert, stringResource(R.string.st_needs_repair), c.alert)
        Outcome.FAILED, Outcome.VAULT_CHANGED -> StatusLook(R.drawable.pi_square_alert, stringResource(R.string.st_failed), c.alert)
        Outcome.AVAILABLE -> StatusLook(R.drawable.pi_download, stringResource(R.string.st_available), c.ink2)
        Outcome.DELETED_HERE -> StatusLook(R.drawable.pi_cloud, stringResource(R.string.st_deleted_here), c.ink2)
    }
}

/** Short status text (used by dialogs). */
@Composable
fun syncStatusText(enabled: Boolean, r: FarmSyncResult?): String = syncLook(enabled, r).text

/** A failure reason code (SyncRunner.reasonCode, SyncEngine) in the user's language. */
@Composable
fun failureText(code: String): String = stringResource(
    when {
        code == "offline" -> R.string.fail_offline
        code.startsWith("drive_401") || code.startsWith("drive_403_insufficient") || code.startsWith("drive_403_auth") -> R.string.fail_drive_auth
        code.startsWith("drive_403") || code.startsWith("drive_429") || code.startsWith("drive_5") -> R.string.fail_drive_quota
        code == "not_visible" -> R.string.fail_not_visible
        code.startsWith("write_failed") -> R.string.fail_write
        code == "game_version_unknown" -> R.string.blocked_version_unknown
        code == "newer_game_version" -> R.string.blocked_newer_version
        else -> R.string.fail_generic
    }
)
