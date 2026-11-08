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
    }
)
