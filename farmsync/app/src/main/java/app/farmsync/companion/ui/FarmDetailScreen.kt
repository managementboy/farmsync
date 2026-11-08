package app.farmsync.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.farmsync.companion.R
import androidx.compose.material3.Switch
import app.farmsync.companion.saves.Farm
import app.farmsync.companion.sync.FarmSyncResult
import app.farmsync.companion.sync.Outcome
import app.farmsync.companion.store.SnapshotMeta

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmDetailScreen(
    farm: Farm?,
    folder: String,
    snapshots: List<SnapshotMeta>,
    busy: Boolean,
    gameRunning: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onSnapshot: () -> Unit,
    onPin: (SnapshotMeta, Boolean) -> Unit,
    onRestore: (SnapshotMeta) -> Unit,
    onExport: () -> Unit,
    driveLinked: Boolean,
    syncEnabled: Boolean,
    syncResult: FarmSyncResult?,
    onSyncEnabled: (Boolean) -> Unit,
    onResolve: (keepLocal: Boolean) -> Unit,
) {
    val info = farm?.info ?: snapshots.firstOrNull()?.info
    var confirm by remember { mutableStateOf<SnapshotMeta?>(null) }
    confirm?.let { s ->
        if (farm != null) {
            RestoreDialog(
                farm = farm,
                snapshot = s,
                gameRunning = gameRunning,
                onConfirm = { confirm = null; onRestore(s) },
                onDismiss = { confirm = null },
            )
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(info?.let { "${it.farmName} · ${it.farmer}" } ?: folder) },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (farm == null) {
                            Text(stringResource(R.string.archived_hint), color = MaterialTheme.colorScheme.tertiary)
                        }
                        info?.let {
                            Text(gameDate(it))
                            Text(stringResource(R.string.farm_version, it.gameVersion), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(folder, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        farm?.files?.forEach { f ->
                            Text(
                                "${f.name}  ${sizeText(f.size)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        if (farm != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onSnapshot, enabled = !busy && farm.isComplete) {
                                    Text(stringResource(R.string.snapshot_now))
                                }
                                OutlinedButton(onClick = onExport, enabled = !busy && farm.isComplete) {
                                    Text(stringResource(R.string.export_button))
                                }
                            }
                        }
                    }
                }
            }
            if (driveLinked && farm != null) {
                item { SyncSection(syncEnabled, syncResult, busy, onSyncEnabled, onResolve) }
            }
            item {
                Text(stringResource(R.string.snapshots_title, snapshots.size), style = MaterialTheme.typography.titleSmall)
            }
            if (snapshots.isEmpty()) item { Text(stringResource(R.string.no_snapshots)) }
            items(snapshots, key = { it.id }) { s ->
                val isCurrent = farm?.fingerprint == s.fingerprint
                SnapshotRow(
                    s, isCurrent, onPin,
                    onRestore = if (farm != null && !isCurrent && !busy) ({ confirm = s }) else null,
                )
            }
        }
    }
}

@Composable
private fun SyncSection(
    enabled: Boolean,
    r: FarmSyncResult?,
    busy: Boolean,
    onEnabled: (Boolean) -> Unit,
    onResolve: (keepLocal: Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sync_switch), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Switch(checked = enabled, onCheckedChange = onEnabled, enabled = !busy)
            }
            Text(syncStatusText(enabled, r), style = MaterialTheme.typography.bodySmall)
            r?.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            when (r?.outcome) {
                Outcome.CONFLICT -> {
                    Text(stringResource(R.string.conflict_explain), color = MaterialTheme.colorScheme.tertiary)
                    r.local?.info?.let { Text(stringResource(R.string.conflict_here) + " " + gameDate(it)) }
                    r.remote?.let { v ->
                        Text(stringResource(R.string.conflict_drive, v.deviceName) + " " + (v.info?.let { gameDate(it) } ?: "?"))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onResolve(true) }, enabled = !busy) { Text(stringResource(R.string.keep_this_device)) }
                        OutlinedButton(onClick = { onResolve(false) }, enabled = !busy) { Text(stringResource(R.string.keep_drive)) }
                    }
                    Text(stringResource(R.string.conflict_safe_note), style = MaterialTheme.typography.bodySmall)
                }
                Outcome.NEEDS_CONFIRM_MIDDAY -> {
                    Text(stringResource(R.string.midday_pull_explain), color = MaterialTheme.colorScheme.tertiary)
                    Button(onClick = { onResolve(false) }, enabled = !busy) { Text(stringResource(R.string.download_anyway)) }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun RestoreDialog(
    farm: Farm,
    snapshot: SnapshotMeta,
    gameRunning: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val now = farm.info
    val then = snapshot.info
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.restore_now_label), style = MaterialTheme.typography.labelMedium)
                Text(now?.let { gameDate(it) } ?: "?")
                Text(stringResource(R.string.restore_then_label, dateTime(snapshot.createdAt)),
                    style = MaterialTheme.typography.labelMedium)
                Text(then?.let { gameDate(it) } ?: "?")
                if (now != null && then != null && then.playedMs < now.playedMs) {
                    Text(stringResource(R.string.restore_goes_back), color = MaterialTheme.colorScheme.tertiary)
                }
                if (farm.midday) {
                    Text(stringResource(R.string.restore_midday_warning), color = MaterialTheme.colorScheme.tertiary)
                }
                Text(stringResource(R.string.restore_safety_note), style = MaterialTheme.typography.bodySmall)
                if (gameRunning) {
                    Text(stringResource(R.string.game_running), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !gameRunning) { Text(stringResource(R.string.restore_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SnapshotRow(
    s: SnapshotMeta,
    isCurrent: Boolean,
    onPin: (SnapshotMeta, Boolean) -> Unit,
    onRestore: (() -> Unit)?,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(dateTime(s.createdAt), style = MaterialTheme.typography.titleSmall)
                s.info?.let { Text(gameDate(it), style = MaterialTheme.typography.bodySmall) }
                Text(
                    "${originText(s.origin)} · ${sizeText(s.sizeBytes)} · ${s.fingerprint.take(8)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (isCurrent) {
                    Text(stringResource(R.string.same_as_device), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                onRestore?.let { TextButton(onClick = it) { Text(stringResource(R.string.restore_button)) } }
                TextButton(onClick = { onPin(s, !s.pinned) }) {
                    Text(stringResource(if (s.pinned) R.string.unpin else R.string.pin))
                }
            }
        }
    }
}
