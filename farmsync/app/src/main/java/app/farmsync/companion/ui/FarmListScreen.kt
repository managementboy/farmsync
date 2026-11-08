package app.farmsync.companion.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import app.farmsync.companion.sync.FarmSyncResult
import androidx.core.content.FileProvider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.farmsync.companion.R
import app.farmsync.companion.ops.ImportException
import app.farmsync.companion.ops.OpBlocked
import app.farmsync.companion.saves.Farm
import app.farmsync.companion.shizuku.ShizukuStatus
import app.farmsync.companion.store.SnapshotMeta

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmListScreen(vm: FarmListViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val messageText = state.message?.let { messageText(it) }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            vm.dismissMessage()
        }
    }

    val context = LocalContext.current
    LaunchedEffect(state.shareFile) {
        state.shareFile?.let { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, file.name))
            vm.shareHandled()
        }
    }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::prepareImport)
    }
    val googleAuth = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        vm.onAuthResult(r.data)
    }
    LaunchedEffect(state.sync.pendingAuth) {
        state.sync.pendingAuth?.let {
            googleAuth.launch(IntentSenderRequest.Builder(it).build())
            vm.authLaunched()
        }
    }
    state.sync.confirmCreateVaultFor?.let { email ->
        AlertDialog(
            onDismissRequest = vm::cancelCreateVault,
            title = { Text(stringResource(R.string.vault_create_title)) },
            text = { Text(stringResource(R.string.vault_create_text, email)) },
            confirmButton = { TextButton(onClick = vm::confirmCreateVault) { Text(stringResource(R.string.vault_create_confirm)) } },
            dismissButton = { TextButton(onClick = vm::cancelCreateVault) { Text(stringResource(R.string.cancel)) } },
        )
    }
    state.importPreview?.let { preview ->
        ImportDialog(preview, state.gameRunning, onConfirm = vm::confirmImport, onDismiss = vm::cancelImport)
    }

    val selected = state.selectedFarm
    if (selected != null) {
        BackHandler { vm.select(null) }
        FarmDetailScreen(
            farm = state.farms.firstOrNull { it.folder == selected },
            folder = selected,
            snapshots = state.snapshots[selected].orEmpty(),
            busy = state.busy,
            gameRunning = state.gameRunning,
            snackbar = snackbar,
            onBack = { vm.select(null) },
            onSnapshot = { vm.snapshotNow(selected) },
            onPin = vm::setPinned,
            onRestore = vm::restore,
            onExport = { vm.export(selected) },
            driveLinked = state.sync.email != null,
            syncEnabled = selected in state.sync.enabled,
            syncResult = state.sync.results[selected],
            onSyncEnabled = { vm.setSyncEnabled(selected, it) },
            onResolve = { keepLocal -> vm.resolve(selected, keepLocal) },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (state.shizuku == ShizukuStatus.READY) {
                        TextButton(
                            onClick = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                            enabled = !state.busy,
                        ) { Text(stringResource(R.string.import_button)) }
                        if (state.farms.isNotEmpty()) {
                            TextButton(onClick = { vm.snapshotNow(null) }, enabled = !state.busy) {
                                Text(stringResource(R.string.snapshot_all))
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = vm::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item { StatusCard(state, vm) }
                if (state.shizuku == ShizukuStatus.READY) item { SyncCard(state.sync, state.busy, vm) }
                state.error?.let { err ->
                    item { Text(stringResource(R.string.error_prefix, err), color = MaterialTheme.colorScheme.error) }
                }
                if (state.shizuku == ShizukuStatus.READY && !state.loading && state.farms.isEmpty() && state.error == null) {
                    item { Text(stringResource(R.string.no_farms)) }
                }
                items(state.farms, key = { it.folder }) { farm ->
                    FarmCard(
                        farm, state.snapshots[farm.folder].orEmpty(),
                        syncLine = if (state.sync.email == null) null
                        else syncStatusText(farm.folder in state.sync.enabled, state.sync.results[farm.folder]),
                    ) { vm.select(farm.folder) }
                }
                if (state.sync.available.isNotEmpty()) {
                    item { Text(stringResource(R.string.available_title), style = MaterialTheme.typography.titleSmall) }
                    items(state.sync.available, key = { "drive-${it.farm}" }) { r ->
                        AvailableCard(r, state.busy) { vm.downloadFromVault(r.farm) }
                    }
                }
                if (state.archived.isNotEmpty()) {
                    item {
                        Text(stringResource(R.string.archived_title), style = MaterialTheme.typography.titleSmall)
                    }
                    items(state.archived, key = { "archived-$it" }) { folder ->
                        ArchivedCard(folder, state.snapshots[folder].orEmpty()) { vm.select(folder) }
                    }
                }
            }
        }
    }
}

@Composable
private fun messageText(m: Message): String = when (m) {
    is Message.SnapshotsCreated -> pluralStringResource(R.plurals.snapshots_created, m.count, m.count)
    Message.NothingChanged -> stringResource(R.string.nothing_changed)
    is Message.Failed -> stringResource(R.string.error_prefix, m.detail)
    is Message.Restored -> stringResource(if (m.pointerRemoved) R.string.restored_with_pointer else R.string.restored)
    is Message.Imported -> stringResource(
        when {
            m.isNew -> R.string.imported_new
            m.pointerRemoved -> R.string.imported_replaced_with_pointer
            else -> R.string.imported_replaced
        }
    )
    is Message.Blocked -> stringResource(
        when (m.reason) {
            OpBlocked.Reason.GAME_RUNNING -> R.string.blocked_game_running
            OpBlocked.Reason.FARM_MISSING -> R.string.blocked_farm_missing
            OpBlocked.Reason.FARM_EXISTS -> R.string.blocked_farm_exists
            OpBlocked.Reason.NEWER_GAME_VERSION -> R.string.blocked_newer_version
            OpBlocked.Reason.INCOMPLETE -> R.string.blocked_incomplete
        }
    )
    is Message.ImportRejected -> stringResource(
        when (m.reason) {
            ImportException.Reason.NOT_A_ZIP -> R.string.import_not_zip
            ImportException.Reason.NO_FARM_FOLDER -> R.string.import_no_farm
            ImportException.Reason.SEVERAL_FARMS -> R.string.import_several
            ImportException.Reason.UNSAFE_PATH -> R.string.import_unsafe
            ImportException.Reason.MISSING_FILES -> R.string.import_missing
            ImportException.Reason.ID_MISMATCH -> R.string.import_id_mismatch
            ImportException.Reason.TOO_LARGE -> R.string.import_too_large
        }
    )
    is Message.DriveLinked -> stringResource(if (m.newVault) R.string.drive_linked_new else R.string.drive_linked, m.email)
    Message.VaultMismatch -> stringResource(R.string.vault_mismatch)
    Message.ShizukuMissing -> stringResource(R.string.shizuku_not_running)
    is Message.SyncDone -> stringResource(R.string.sync_done, m.uploaded, m.downloaded, m.attention)
    is Message.WriteFailed -> when {
        m.beforeTime == null -> stringResource(R.string.import_new_failed, m.detail)
        m.rolledBack -> stringResource(R.string.restore_failed_rolled_back, m.detail, dateTime(m.beforeTime))
        else -> stringResource(R.string.restore_failed_no_rollback, m.detail, dateTime(m.beforeTime))
    }
}

@Composable
private fun StatusCard(state: UiState, vm: FarmListViewModel) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val (label, action) = when (state.shizuku) {
                ShizukuStatus.NOT_INSTALLED -> R.string.shizuku_not_installed to null
                ShizukuStatus.NOT_RUNNING -> R.string.shizuku_not_running to {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }
                ShizukuStatus.NO_PERMISSION -> R.string.shizuku_no_permission to { vm.requestShizukuPermission() }
                ShizukuStatus.READY -> R.string.shizuku_ready to null
            }
            Text(stringResource(label), style = MaterialTheme.typography.titleSmall)
            action?.let {
                Button(onClick = it) {
                    Text(
                        stringResource(
                            if (state.shizuku == ShizukuStatus.NO_PERMISSION) R.string.grant_permission
                            else R.string.open_developer_options
                        )
                    )
                }
            }
            Text(
                state.gameVersion?.let { stringResource(R.string.game_version, it) }
                    ?: stringResource(R.string.game_not_installed),
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.gameRunning) {
                Text(
                    stringResource(R.string.game_running),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (state.shizuku != ShizukuStatus.READY) {
                TextButton(onClick = vm::refresh) { Text(stringResource(R.string.check_again)) }
            }
        }
    }
}

@Composable
private fun SyncCard(sync: SyncUi, busy: Boolean, vm: FarmListViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (sync.email == null) {
                Text(stringResource(R.string.drive_not_linked), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.drive_not_linked_hint), style = MaterialTheme.typography.bodySmall)
                Button(onClick = vm::linkDrive, enabled = !busy) { Text(stringResource(R.string.drive_link)) }
            } else {
                Text(stringResource(R.string.drive_linked_as, sync.email), style = MaterialTheme.typography.titleSmall)
                Text(
                    pluralStringResource(R.plurals.sync_enabled_count, sync.enabled.size, sync.enabled.size) +
                        (sync.lastSync?.let { " · " + stringResource(R.string.last_sync, dateTime(it)) } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::syncNow, enabled = !busy) { Text(stringResource(R.string.sync_now)) }
                    TextButton(onClick = vm::unlinkDrive, enabled = !busy) { Text(stringResource(R.string.drive_unlink)) }
                }
            }
        }
    }
}

@Composable
private fun AvailableCard(r: FarmSyncResult, busy: Boolean, onDownload: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val info = r.remote?.info
            Text(if (info != null) "${info.farmName} · ${info.farmer}" else r.farm, style = MaterialTheme.typography.titleMedium)
            info?.let { Text(gameDate(it)) }
            r.remote?.let { Text(stringResource(R.string.from_device, it.deviceName, dateTime(it.created)), style = MaterialTheme.typography.bodySmall) }
            Button(onClick = onDownload, enabled = !busy) { Text(stringResource(R.string.download_to_device)) }
        }
    }
}

@Composable
private fun FarmCard(farm: Farm, snapshots: List<SnapshotMeta>, syncLine: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (farm.isTestFarm) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val info = farm.info
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (info != null) "${info.farmName} · ${info.farmer}" else farm.folder,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (farm.isTestFarm) {
                    AssistChip(onClick = onClick, label = { Text(stringResource(R.string.badge_test)) })
                    Spacer(Modifier.width(4.dp))
                }
                if (farm.midday) AssistChip(onClick = onClick, label = { Text(stringResource(R.string.badge_midday)) })
            }
            if (info != null) {
                Text(gameDate(info))
                Text(stringResource(R.string.farm_version, info.gameVersion), style = MaterialTheme.typography.bodySmall)
            }
            SnapshotSummary(snapshots, current = farm.contentHash)
            syncLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            Text(farm.folder, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            if (!farm.isComplete) {
                Text(stringResource(R.string.farm_incomplete), color = MaterialTheme.colorScheme.error)
            }
            farm.error?.let { Text(stringResource(R.string.error_prefix, it), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ArchivedCard(folder: String, snapshots: List<SnapshotMeta>, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val info = snapshots.firstOrNull()?.info
            Text(if (info != null) "${info.farmName} · ${info.farmer}" else folder, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.archived_hint), style = MaterialTheme.typography.bodySmall)
            SnapshotSummary(snapshots, current = null)
        }
    }
}

@Composable
private fun SnapshotSummary(snapshots: List<SnapshotMeta>, current: String?) {
    val latest = snapshots.firstOrNull()
    val text = if (latest == null) stringResource(R.string.no_snapshots)
    else stringResource(R.string.last_snapshot, dateTime(latest.createdAt), snapshots.size)
    Text(text, style = MaterialTheme.typography.bodySmall)
    if (latest != null && current != null && current.isNotEmpty() && latest.contentHash != current) {
        Text(stringResource(R.string.changed_since_snapshot), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary)
    }
}
