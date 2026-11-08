package app.farmsync.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.farmsync.companion.R

/** Shows what an import would do. Nothing is written until the user confirms. */
@Composable
fun ImportDialog(
    preview: ImportPreview,
    gameRunning: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val incoming = preview.candidate.info
    val device = preview.onDevice
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (device == null) R.string.import_title_new else R.string.import_title_replace))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${incoming.farmName} · ${incoming.farmer}", style = MaterialTheme.typography.titleSmall)
                Text(preview.candidate.farm, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
                Text(stringResource(R.string.import_incoming_label), style = MaterialTheme.typography.labelMedium)
                Text(gameDate(incoming))
                Text(stringResource(R.string.farm_version, incoming.gameVersion), style = MaterialTheme.typography.bodySmall)
                device?.info?.let { now ->
                    Text(stringResource(R.string.restore_now_label), style = MaterialTheme.typography.labelMedium)
                    Text(gameDate(now))
                    if (incoming.playedMs < now.playedMs) {
                        Text(stringResource(R.string.import_older_warning), color = MaterialTheme.colorScheme.error)
                    }
                    if (device.midday) {
                        Text(stringResource(R.string.restore_midday_warning), color = MaterialTheme.colorScheme.tertiary)
                    }
                    Text(stringResource(R.string.restore_safety_note), style = MaterialTheme.typography.bodySmall)
                }
                if (preview.wasDeletedHere) {
                    Text(stringResource(R.string.import_was_deleted), color = MaterialTheme.colorScheme.tertiary)
                }
                if (preview.candidate.ignored.isNotEmpty()) {
                    Text(stringResource(R.string.import_ignored_note), style = MaterialTheme.typography.bodySmall)
                }
                if (gameRunning) Text(stringResource(R.string.game_running), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !gameRunning) {
                Text(stringResource(if (device == null) R.string.import_confirm_new else R.string.import_confirm_replace))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
