package app.farmsync.companion.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.farmsync.companion.BuildConfig
import app.farmsync.companion.R
import app.farmsync.companion.shizuku.ShizukuStatus
import app.farmsync.companion.ui.components.ButtonKind
import app.farmsync.companion.ui.components.PixelButton
import app.farmsync.companion.ui.components.PixelIcon
import app.farmsync.companion.ui.components.card
import app.farmsync.companion.ui.theme.Farm
import app.farmsync.companion.ui.theme.FarmType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: UiState, vm: FarmListViewModel, snackbar: SnackbarHostState) {
    val c = Farm.colors
    val context = LocalContext.current
    // Read off the main thread; refreshed after each sync.
    val log by produceState(emptyList<app.farmsync.companion.sync.SyncLog.Entry>(), state.sync.lastSync) { value = vm.syncLogEntries() }
    Scaffold(
        containerColor = c.sky,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.sky),
                navigationIcon = {
                    IconButton(onClick = { vm.openSettings(false) }) { PixelIcon(R.drawable.pi_arrow_left, stringResource(R.string.back)) }
                },
                title = { Text(stringResource(R.string.settings_title), style = FarmType.screenTitle, color = c.ink) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item { SectionTitle(stringResource(R.string.settings_drive)) }
            item {
                Column(Modifier.fillMaxWidth().card().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.sync.email != null) {
                        Line(stringResource(R.string.settings_account), state.sync.email)
                        state.sync.lastSync?.let { Line(stringResource(R.string.settings_last_sync), relative(it)) }
                        PixelButton(stringResource(R.string.drive_unlink), vm::unlinkDrive)
                    } else {
                        Text(stringResource(R.string.drive_not_linked_hint), fontSize = 14.sp, color = c.ink2)
                        PixelButton(stringResource(R.string.drive_link), vm::linkDrive, kind = ButtonKind.FILLED)
                    }
                }
            }
            item { SectionTitle(stringResource(R.string.settings_diagnostics)) }
            item {
                Column(Modifier.fillMaxWidth().card().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Line("Shizuku", stringResource(
                        when (state.shizuku) {
                            ShizukuStatus.READY -> R.string.diag_ready
                            ShizukuStatus.NOT_RUNNING -> R.string.diag_not_running
                            ShizukuStatus.NO_PERMISSION -> R.string.diag_no_permission
                            ShizukuStatus.NOT_INSTALLED -> R.string.diag_not_installed
                        }
                    ))
                    Line("Stardew Valley", state.gameVersion ?: stringResource(R.string.diag_not_installed))
                    Line(stringResource(R.string.app_name), BuildConfig.VERSION_NAME)
                    Text(stringResource(R.string.diag_wifi_note), fontSize = 13.sp, color = c.ink2)
                    PixelButton(stringResource(R.string.open_developer_options), {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    })
                }
            }
            item { SectionTitle(stringResource(R.string.settings_sync_log)) }
            item {
                Column(Modifier.fillMaxWidth().card().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (log.isEmpty()) Text(stringResource(R.string.sync_log_empty), fontSize = 14.sp, color = c.ink2)
                    log.forEach { e ->
                        val look = syncLook(true, app.farmsync.companion.sync.FarmSyncResult(e.farm, e.outcome, detail = e.detail))
                        Column {
                            Text("${whenText(e.time)} · ${e.farm.substringBeforeLast('_')}", fontSize = 13.sp, color = c.ink2)
                            Text(look.text + (e.detail?.let { " (" + failureText(it) + ")" } ?: ""), fontSize = 14.sp, color = look.color)
                        }
                    }
                }
            }
            item {
                PixelButton(
                    stringResource(R.string.about_title), onClick = { vm.openAbout(true) },
                    icon = R.drawable.pi_chevron_right, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    val c = Farm.colors
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 14.sp, color = c.ink2, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = c.ink)
    }
}
