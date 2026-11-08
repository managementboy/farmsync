package app.farmsync.companion.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.farmsync.companion.R
import app.farmsync.companion.ops.ImportException
import app.farmsync.companion.ops.OpBlocked
import app.farmsync.companion.ops.Recovery
import app.farmsync.companion.saves.Farm as SaveFarm
import app.farmsync.companion.shizuku.ShizukuStatus
import app.farmsync.companion.store.SnapshotMeta
import app.farmsync.companion.sync.FarmSyncResult
import app.farmsync.companion.sync.Outcome
import app.farmsync.companion.ui.components.AppMark
import app.farmsync.companion.ui.components.ButtonKind
import app.farmsync.companion.ui.components.DayTile
import app.farmsync.companion.ui.components.PixelButton
import app.farmsync.companion.ui.components.PixelHill
import app.farmsync.companion.ui.components.PixelIcon
import app.farmsync.companion.ui.components.StatusLine
import app.farmsync.companion.ui.components.card
import app.farmsync.companion.ui.components.pixelFrame
import app.farmsync.companion.ui.theme.Farm
import app.farmsync.companion.ui.theme.FarmType

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
            val send = Intent(Intent.ACTION_SEND).setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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

    if (state.showAbout) {
        BackHandler { vm.openAbout(false) }
        AboutScreen(onBack = { vm.openAbout(false) })
        return
    }
    if (state.showSettings) {
        BackHandler { vm.openSettings(false) }
        SettingsScreen(state, vm, snackbar)
        return
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
            onShareSnapshot = vm::exportSnapshot,
            driveLinked = state.sync.email != null,
            syncEnabled = selected !in state.sync.disabled,
            syncResult = state.sync.results[selected],
            onSyncEnabled = { vm.setSyncEnabled(selected, it) },
            onDownloadAnyway = { vm.downloadAnyway(selected) },
        )
        return
    }

    val c = Farm.colors
    Scaffold(
        containerColor = c.sky,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.sky),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppMark()
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.app_name), style = FarmType.appTitle, color = c.ink)
                    }
                },
                actions = {
                    if (state.shizuku == ShizukuStatus.READY) {
                        IconButton(onClick = { if (state.sync.email != null) vm.syncNow() else vm.refresh() }, enabled = !state.busy) {
                            PixelIcon(R.drawable.pi_reload, stringResource(R.string.sync_now))
                        }
                    }
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { PixelIcon(R.drawable.pi_more_vertical, stringResource(R.string.more_options)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (state.shizuku == ShizukuStatus.READY) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.import_button)) },
                                leadingIcon = { PixelIcon(R.drawable.pi_download, null) },
                                onClick = {
                                    menu = false
                                    pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.snapshot_all)) },
                                leadingIcon = { PixelIcon(R.drawable.pi_save, null) },
                                onClick = { menu = false; vm.snapshotNow(null) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_title)) },
                            leadingIcon = { PixelIcon(R.drawable.pi_settings_cog, null) },
                            onClick = { menu = false; vm.openSettings(true) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.about_title)) },
                            leadingIcon = { AppMark(size = 24.dp) },
                            onClick = { menu = false; vm.openAbout(true) },
                        )
                    }
                },
            )
        },
        bottomBar = { PixelHill() },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = vm::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item { StatusBanner(state, vm) }
                if (state.farms.isNotEmpty()) item { SectionTitle(stringResource(R.string.your_farms)) }
                items(state.farms, key = { it.folder }) { farm ->
                    FarmRow(
                        farm = farm,
                        look = if (state.sync.email != null) syncLook(farm.folder !in state.sync.disabled, state.sync.results[farm.folder]) else null,
                        lastSnapshot = state.snapshots[farm.folder]?.firstOrNull(),
                        midday = farm.midday,
                    ) { vm.select(farm.folder) }
                }
                if (state.shizuku == ShizukuStatus.READY && !state.loading && state.farms.isEmpty() && state.error == null) {
                    item { Text(stringResource(R.string.no_farms), color = c.ink2, modifier = Modifier.padding(8.dp)) }
                }
                if (state.sync.available.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.available_title)) }
                    items(state.sync.available, key = { "drive-${it.farm}" }) { r ->
                        AvailableRow(r, state.busy) { vm.downloadFromVault(r.farm) }
                    }
                }
                if (state.archived.isNotEmpty()) {
                    item { SectionTitle(stringResource(R.string.archived_title)) }
                    items(state.archived, key = { "archived-$it" }) { folder ->
                        ArchivedRow(folder, state.snapshots[folder].orEmpty()) { vm.select(folder) }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text, style = FarmType.section, color = Farm.colors.ink,
        modifier = Modifier.padding(top = 8.dp, start = 2.dp).semantics { heading() },
    )
}

/** One place for the app's overall state; problems replace it, each with one action. */
@Composable
private fun StatusBanner(state: UiState, vm: FarmListViewModel) {
    val c = Farm.colors
    val context = LocalContext.current
    val openDevSettings = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
    val results = state.sync.results.filterKeys { k -> state.farms.any { it.folder == k } }.values
    val attention = results.count { it.outcome in setOf(Outcome.FAILED, Outcome.VAULT_CHANGED, Outcome.NEEDS_REPAIR) }
    val waitingGame = results.any { it.outcome == Outcome.BLOCKED_GAME_RUNNING }
    when {
        state.shizuku == ShizukuStatus.NOT_INSTALLED ->
            Banner(R.drawable.pi_square_alert, stringResource(R.string.shizuku_not_installed), null, c.alertBg, c.alert)
        state.shizuku == ShizukuStatus.NOT_RUNNING ->
            Banner(R.drawable.pi_square_alert, stringResource(R.string.banner_shizuku_off), stringResource(R.string.banner_shizuku_off_hint),
                c.harvestBg, c.harvest, stringResource(R.string.open_developer_options), openDevSettings)
        state.shizuku == ShizukuStatus.NO_PERMISSION ->
            Banner(R.drawable.pi_square_alert, stringResource(R.string.shizuku_no_permission), null,
                c.harvestBg, c.harvest, stringResource(R.string.grant_permission), vm::requestShizukuPermission)
        state.sync.email == null ->
            Banner(R.drawable.pi_cloud, stringResource(R.string.drive_not_linked), stringResource(R.string.drive_not_linked_hint),
                c.card, c.ink2, stringResource(R.string.drive_link), vm::linkDrive)
        state.busy || state.loading ->
            Banner(R.drawable.pi_reload, stringResource(R.string.banner_syncing), null, c.meadowBg, c.meadow)
        attention > 0 ->
            Banner(R.drawable.pi_square_alert, pluralStringResource(R.plurals.banner_attention, attention, attention),
                stringResource(R.string.banner_attention_hint), c.alertBg, c.alert)
        waitingGame || state.gameRunning ->
            Banner(R.drawable.pi_pause, stringResource(R.string.banner_close_game), stringResource(R.string.banner_close_game_hint),
                c.harvestBg, c.harvest)
        else -> Banner(
            R.drawable.pi_check, stringResource(R.string.banner_all_synced),
            state.sync.lastSync?.let { stringResource(R.string.banner_last_sync, relative(it)) },
            c.meadowBg, c.meadow,
        )
    }
}

@Composable
private fun Banner(
    icon: Int,
    title: String,
    subtitle: String?,
    fill: androidx.compose.ui.graphics.Color,
    accent: androidx.compose.ui.graphics.Color,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = Farm.colors
    Column(
        Modifier.fillMaxWidth().pixelFrame(fill, accent, border = 3.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelIcon(icon, null, tint = accent)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontSize = 15.sp, color = c.ink)
                subtitle?.let { Text(it, fontSize = 13.sp, color = c.ink2) }
            }
        }
        if (action != null && onAction != null) {
            PixelButton(action, onAction, kind = ButtonKind.FILLED)
        }
    }
}

@Composable
private fun FarmRow(farm: SaveFarm, look: StatusLook?, lastSnapshot: SnapshotMeta?, midday: Boolean, onClick: () -> Unit) {
    val c = Farm.colors
    Row(
        Modifier.fillMaxWidth().card().clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DayTile(farm.info)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(farm.info?.farmName ?: farm.folder, style = FarmType.farmName, color = c.ink)
            farm.info?.let { Text("${it.farmer} · ${playTime(it)}", fontSize = 14.sp, color = c.ink2) }
            Spacer(Modifier.padding(top = 4.dp))
            when {
                look != null -> StatusLine(look.icon, look.text, look.color)
                lastSnapshot != null -> StatusLine(R.drawable.pi_save, stringResource(R.string.snapshot_of, whenText(lastSnapshot.createdAt)), c.ink2)
            }
            if (midday) StatusLine(R.drawable.pi_clock, stringResource(R.string.badge_midday), c.harvest)
            if (!farm.isComplete) StatusLine(R.drawable.pi_square_alert, stringResource(R.string.farm_incomplete), c.alert)
        }
        PixelIcon(R.drawable.pi_chevron_right, null, tint = c.ink2)
    }
}

@Composable
private fun AvailableRow(r: FarmSyncResult, busy: Boolean, onDownload: () -> Unit) {
    val c = Farm.colors
    Row(
        Modifier.fillMaxWidth().pixelFrame(c.sky, c.offerFrame).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DayTile(r.remote?.info)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(r.remote?.info?.farmName ?: r.farm, style = FarmType.farmName, color = c.ink)
            r.remote?.let { Text(stringResource(R.string.from_device, it.deviceName, relative(it.created)), fontSize = 14.sp, color = c.ink2) }
            PixelButton(stringResource(R.string.download_to_device), onDownload, icon = R.drawable.pi_download, enabled = !busy)
        }
    }
}

@Composable
private fun ArchivedRow(folder: String, snapshots: List<SnapshotMeta>, onClick: () -> Unit) {
    val c = Farm.colors
    val info = snapshots.firstOrNull()?.info
    Row(
        Modifier.fillMaxWidth().pixelFrame(c.sky, c.offerFrame).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DayTile(info)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(info?.farmName ?: folder, style = FarmType.farmName, color = c.ink)
            Text(stringResource(R.string.archived_hint_short), fontSize = 14.sp, color = c.ink2)
        }
        PixelIcon(R.drawable.pi_chevron_right, null, tint = c.ink2)
    }
}

@Composable
fun messageText(m: Message): String = when (m) {
    is Message.SnapshotsCreated -> pluralStringResource(R.plurals.snapshots_created, m.count, m.count)
    Message.NothingChanged -> stringResource(R.string.nothing_changed)
    is Message.Failed -> failureText(m.detail)
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
            OpBlocked.Reason.GAME_VERSION_UNKNOWN -> R.string.blocked_version_unknown
            OpBlocked.Reason.CHANGED -> R.string.blocked_changed
            OpBlocked.Reason.MIDDAY -> R.string.blocked_midday
            OpBlocked.Reason.NEEDS_REPAIR -> R.string.blocked_needs_repair
            OpBlocked.Reason.INVALID_SAVE -> R.string.blocked_invalid_save
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
            ImportException.Reason.AMBIGUOUS -> R.string.import_ambiguous
            ImportException.Reason.MALFORMED -> R.string.import_malformed
        }
    )
    is Message.DriveLinked -> stringResource(if (m.newVault) R.string.drive_linked_new else R.string.drive_linked, m.email)
    Message.VaultMismatch -> stringResource(R.string.vault_mismatch)
    Message.ShizukuMissing -> stringResource(R.string.shizuku_not_running)
    is Message.SyncDone -> syncDoneText(m)
    Message.AuthRetry -> stringResource(R.string.auth_retry)
    is Message.Recovered -> m.items.map { (farm, r) ->
        val name = farm.substringBeforeLast('_')
        when (r) {
            Recovery.COMPLETED -> stringResource(R.string.recovered_completed, name)
            Recovery.ROLLED_BACK -> stringResource(R.string.recovered_rolled_back, name)
            Recovery.WAITING_FOR_GAME -> stringResource(R.string.recovered_waiting, name)
            Recovery.INCOMPLETE_NEW_FARM -> stringResource(R.string.recovered_incomplete_new, name)
            Recovery.NEEDS_HELP -> stringResource(R.string.recovered_needs_help, name, m.since[farm]?.let { dateTime(it) } ?: "?")
            Recovery.KEPT_CURRENT -> stringResource(R.string.recovered_kept_current, name)
            Recovery.NOTHING_CHANGED -> ""
        }
    }.filter { it.isNotEmpty() }.joinToString("\n")
    is Message.WriteFailed -> when {
        m.keptCurrent -> stringResource(R.string.write_kept_current)
        m.beforeTime == null -> stringResource(R.string.import_new_failed, m.detail)
        m.rolledBack -> stringResource(R.string.restore_failed_rolled_back, m.detail, dateTime(m.beforeTime))
        else -> stringResource(R.string.restore_failed_no_rollback, m.detail, dateTime(m.beforeTime))
    }
}

/** Only says what happened: "1 granja subida · 1 descargada". */
@Composable
private fun syncDoneText(m: Message.SyncDone): String {
    val parts = buildList {
        if (m.uploaded > 0) add(pluralStringResource(R.plurals.done_uploaded, m.uploaded, m.uploaded))
        if (m.downloaded > 0) add(pluralStringResource(R.plurals.done_downloaded, m.downloaded, m.downloaded))
        if (m.resolved > 0) add(pluralStringResource(R.plurals.done_resolved, m.resolved, m.resolved))
        if (m.attention > 0) add(pluralStringResource(R.plurals.done_attention, m.attention, m.attention))
    }
    return if (parts.isEmpty()) stringResource(R.string.banner_all_synced) else parts.joinToString(" · ")
}
