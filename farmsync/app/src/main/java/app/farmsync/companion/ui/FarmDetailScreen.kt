package app.farmsync.companion.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.farmsync.companion.R
import app.farmsync.companion.saves.Farm as SaveFarm
import app.farmsync.companion.store.SnapshotMeta
import app.farmsync.companion.sync.FarmSyncResult
import app.farmsync.companion.sync.Outcome
import app.farmsync.companion.ui.components.ButtonKind
import app.farmsync.companion.ui.components.DayTile
import app.farmsync.companion.ui.components.PixelButton
import app.farmsync.companion.ui.components.PixelIcon
import app.farmsync.companion.ui.components.PixelSwitch
import app.farmsync.companion.ui.components.StatusLine
import app.farmsync.companion.ui.components.card
import app.farmsync.companion.ui.theme.Farm
import app.farmsync.companion.ui.theme.FarmType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FarmDetailScreen(
    farm: SaveFarm?,
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
    onShareSnapshot: (SnapshotMeta) -> Unit,
    driveLinked: Boolean,
    syncEnabled: Boolean,
    syncResult: FarmSyncResult?,
    onSyncEnabled: (Boolean) -> Unit,
    onDownloadAnyway: () -> Unit,
) {
    val c = Farm.colors
    val info = farm?.info ?: snapshots.firstOrNull()?.info
    // Survives rotation: only the id is saved, and the snapshot is looked up again (it may be gone).
    var confirmId by rememberSaveable { mutableStateOf<String?>(null) }
    val confirm = snapshots.firstOrNull { it.id == confirmId }
    var showTech by remember { mutableStateOf(false) }
    confirm?.let { s ->
        if (farm != null) {
            RestoreDialog(farm, s, gameRunning, driveLinked, onConfirm = { confirmId = null; onRestore(s) }, onDismiss = { confirmId = null })
        }
    }
    Scaffold(
        containerColor = c.sky,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.sky),
                navigationIcon = {
                    IconButton(onClick = onBack) { PixelIcon(R.drawable.pi_arrow_left, stringResource(R.string.back)) }
                },
                title = { Text(info?.farmName ?: folder, style = FarmType.screenTitle, color = c.ink) },
                actions = {
                    if (farm != null) {
                        var menu by remember { mutableStateOf(false) }
                        IconButton(onClick = { menu = true }) { PixelIcon(R.drawable.pi_more_vertical, stringResource(R.string.more_options)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.share_farm)) },
                                leadingIcon = { PixelIcon(R.drawable.pi_share, null) },
                                onClick = { menu = false; onExport() },
                                enabled = !busy && farm.isComplete,
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (farm != null) {
                PixelButton(stringResource(R.string.create_snapshot), onSnapshot, icon = R.drawable.pi_plus,
                    kind = ButtonKind.FILLED, enabled = !busy && farm.isComplete, modifier = Modifier.height(56.dp))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DayTile(info, big = true)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(info?.farmName ?: folder, style = FarmType.heroName, color = c.ink)
                        info?.let {
                            Text(it.farmer, fontSize = 15.sp, color = c.ink2)
                            Text(stringResource(R.string.played, playTime(it)), fontSize = 15.sp, color = c.ink2)
                        }
                        if (farm == null) Text(stringResource(R.string.archived_hint), fontSize = 14.sp, color = c.harvest)
                        if (farm?.midday == true) StatusLine(R.drawable.pi_clock, stringResource(R.string.badge_midday), c.harvest)
                    }
                }
            }
            if (driveLinked && farm != null) {
                item { SyncCard(syncEnabled, syncResult, busy, onSyncEnabled, onDownloadAnyway) }
            }
            item { SectionTitle(stringResource(R.string.backups_title)) }
            if (snapshots.isEmpty()) {
                item { Text(stringResource(R.string.no_snapshots), color = c.ink2) }
            } else {
                item {
                    Column(Modifier.fillMaxWidth().card()) {
                        snapshots.forEachIndexed { i, s ->
                            if (i > 0) HorizontalDivider(color = c.shade, thickness = 2.dp)
                            BackupRow(
                                s,
                                isCurrent = farm?.fingerprint == s.fingerprint,
                                canRestore = farm != null && !busy,
                                onRestore = { confirmId = s.id },
                                onPin = { onPin(s, !s.pinned) },
                                onShare = { onShareSnapshot(s) },
                            )
                        }
                    }
                }
            }
            if (farm != null) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { showTech = !showTech }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PixelIcon(if (showTech) R.drawable.pi_chevron_down else R.drawable.pi_chevron_right, null, tint = c.ink2)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.tech_details), fontSize = 15.sp, color = c.ink2)
                    }
                }
                if (showTech) {
                    item {
                        Column(Modifier.fillMaxWidth().card().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TechLine(stringResource(R.string.tech_folder), farm.folder)
                            info?.let { TechLine(stringResource(R.string.tech_game_version), it.gameVersion) }
                            farm.files.forEach { f -> TechLine(f.name, sizeText(f.size)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TechLine(label: String, value: String) {
    val c = Farm.colors
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, color = c.ink2, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = c.ink, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SyncCard(
    enabled: Boolean,
    r: FarmSyncResult?,
    busy: Boolean,
    onEnabled: (Boolean) -> Unit,
    onDownloadAnyway: () -> Unit,
) {
    val c = Farm.colors
    Column(Modifier.fillMaxWidth().card().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // One accessible switch: the label and the toggle are a single node with a 48 dp target.
        Row(
            Modifier.fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .toggleable(value = enabled, enabled = !busy, role = Role.Switch, onValueChange = onEnabled),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.sync_switch), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.ink,
                modifier = Modifier.weight(1f))
            PixelSwitch(enabled, onCheckedChange = null, enabled = !busy)
        }
        val look = syncLook(enabled, r)
        StatusLine(look.icon, look.text, look.color)
        r?.detail?.takeIf { r.outcome == Outcome.FAILED }?.let { Text(failureText(it), fontSize = 13.sp, color = c.alert) }
        if (r?.outcome == Outcome.WAITING_MIDDAY) {
            Text(stringResource(R.string.midday_pull_explain), fontSize = 14.sp, color = c.ink2)
            PixelButton(stringResource(R.string.download_anyway), onDownloadAnyway, icon = R.drawable.pi_download, enabled = !busy)
        }
    }
}

@Composable
private fun BackupRow(
    s: SnapshotMeta,
    isCurrent: Boolean,
    canRestore: Boolean,
    onRestore: () -> Unit,
    onPin: () -> Unit,
    onShare: () -> Unit,
) {
    val c = Farm.colors
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        PixelIcon(originIcon(s.origin), originText(s.origin), tint = c.bark)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(whenText(s.createdAt), fontSize = 15.sp, color = c.ink)
            Text((s.info?.let { gameDay(it) } ?: "") + " · " + originText(s.origin), fontSize = 13.sp, color = c.ink2)
            if (isCurrent) Text(stringResource(R.string.same_as_device), fontSize = 12.sp, color = c.meadow)
        }
        if (s.pinned) PixelIcon(R.drawable.pi_bookmark, stringResource(R.string.pinned), tint = c.bark, size = 20.dp)
        Box {
            IconButton(onClick = { menu = true }) { PixelIcon(R.drawable.pi_more_vertical, stringResource(R.string.more_options), tint = c.ink2) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (canRestore && !isCurrent) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.restore_button)) },
                        leadingIcon = { PixelIcon(R.drawable.pi_undo, null) }, onClick = { menu = false; onRestore() })
                }
                DropdownMenuItem(text = { Text(stringResource(if (s.pinned) R.string.unpin else R.string.pin)) },
                    leadingIcon = { PixelIcon(R.drawable.pi_bookmark, null) }, onClick = { menu = false; onPin() })
                DropdownMenuItem(text = { Text(stringResource(R.string.share)) },
                    leadingIcon = { PixelIcon(R.drawable.pi_share, null) }, onClick = { menu = false; onShare() })
            }
        }
    }
}

@Composable
private fun RestoreDialog(
    farm: SaveFarm,
    snapshot: SnapshotMeta,
    gameRunning: Boolean,
    syncOn: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val now = farm.info
    val then = snapshot.info
    val c = Farm.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_title), style = FarmType.section) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.restore_now_label), fontSize = 13.sp, color = c.ink2)
                Text(now?.let { gameDate(it) } ?: "?")
                Text(stringResource(R.string.restore_then_label, whenText(snapshot.createdAt)), fontSize = 13.sp, color = c.ink2)
                Text(then?.let { gameDate(it) } ?: "?")
                if (now != null && then != null && then.playedMs < now.playedMs) {
                    Text(stringResource(R.string.restore_goes_back), color = c.harvest)
                }
                if (farm.midday) Text(stringResource(R.string.restore_midday_warning), color = c.harvest)
                if (syncOn) Text(stringResource(R.string.restore_shared_note), fontSize = 13.sp, color = c.ink2)
                Text(stringResource(R.string.restore_safety_note), fontSize = 13.sp, color = c.ink2)
                if (gameRunning) Text(stringResource(R.string.game_running), color = c.alert)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !gameRunning) { Text(stringResource(R.string.restore_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
