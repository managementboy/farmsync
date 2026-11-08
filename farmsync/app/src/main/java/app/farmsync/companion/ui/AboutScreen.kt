package app.farmsync.companion.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.farmsync.companion.BuildConfig
import app.farmsync.companion.R
import app.farmsync.companion.ui.components.AppMark
import app.farmsync.companion.ui.components.PixelIcon
import app.farmsync.companion.ui.components.card
import app.farmsync.companion.ui.components.pixelFrame
import app.farmsync.companion.ui.theme.Farm
import app.farmsync.companion.ui.theme.FarmType

private const val URL_PROJECT = "https://elkin.de/farm-sync/"
private const val URL_SOURCE = "https://github.com/managementboy/farmsync"
private const val URL_PRIVACY = "https://elkin.de/farm-sync-privacy/"
private const val URL_GAME = "https://www.stardewvalley.net/"

/** Name, version, links, copyright, open-source licences (shipped in assets/licenses) and trademark note. */
private val LICENSES = listOf(
    Triple("Farm Sync", "MIT · © 2026 Elkin Fricke", "licenses/FarmSync-MIT.txt"),
    Triple("Pixelify Sans", "SIL Open Font License 1.1", "licenses/PixelifySans-OFL.txt"),
    Triple("Silkscreen", "SIL Open Font License 1.1", "licenses/Silkscreen-OFL.txt"),
    Triple("Pixelarticons", "MIT · © 2019 Gerrit Halfmann", "licenses/Pixelarticons-MIT.txt"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val c = Farm.colors
    val context = LocalContext.current
    val open = { url: String -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    var expanded by remember { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = c.sky,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = c.sky),
                navigationIcon = { IconButton(onClick = onBack) { PixelIcon(R.drawable.pi_arrow_left, stringResource(R.string.back)) } },
                title = { Text(stringResource(R.string.about_title), style = FarmType.screenTitle, color = c.ink) },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    AppMark(size = 96.dp)
                    Spacer(Modifier.padding(top = 10.dp))
                    Text(stringResource(R.string.app_name), style = FarmType.heroName, color = c.ink)
                    Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME), fontSize = 14.sp, color = c.ink2)
                    Spacer(Modifier.padding(top = 8.dp))
                    Text(stringResource(R.string.about_blurb), fontSize = 15.sp, color = c.ink, textAlign = TextAlign.Center)
                }
            }
            item {
                // Deliberately prominent: people should know who (and what) wrote the code they trust with their farms.
                Column(
                    Modifier.fillMaxWidth().pixelFrame(c.harvestBg, c.harvest, border = 3.dp).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(stringResource(R.string.about_ai_title), style = FarmType.section, color = c.ink,
                        modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.about_ai_text), fontSize = 15.sp, color = c.ink)
                }
            }
            item {
                Column(Modifier.fillMaxWidth().card()) {
                    LinkRow(R.drawable.pi_external_link, stringResource(R.string.about_project_page)) { open(URL_PROJECT) }
                    HorizontalDivider(color = c.shade, thickness = 2.dp)
                    LinkRow(R.drawable.pi_external_link, stringResource(R.string.about_source_code)) { open(URL_SOURCE) }
                    HorizontalDivider(color = c.shade, thickness = 2.dp)
                    LinkRow(R.drawable.pi_external_link, stringResource(R.string.about_privacy)) { open(URL_PRIVACY) }
                }
            }
            item { Text(stringResource(R.string.about_copyright), fontSize = 14.sp, color = c.ink, modifier = Modifier.padding(horizontal = 4.dp)) }
            item { SectionTitle(stringResource(R.string.about_licenses)) }
            item {
                Column(Modifier.fillMaxWidth().card()) {
                    LICENSES.forEachIndexed { i, (name, license, asset) ->
                        if (i > 0) HorizontalDivider(color = c.shade, thickness = 2.dp)
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable(enabled = asset != null, role = Role.Button) { expanded = if (expanded == name) null else name }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(name, fontSize = 15.sp, color = c.ink)
                                Text(license, fontSize = 13.sp, color = c.ink2)
                            }
                            if (asset != null) {
                                PixelIcon(if (expanded == name) R.drawable.pi_chevron_down else R.drawable.pi_chevron_right, null, tint = c.ink2)
                            }
                        }
                        if (asset != null && expanded == name) {
                            val text = remember(asset) {
                                runCatching { context.assets.open(asset).bufferedReader().readText() }.getOrDefault("")
                            }
                            Text(text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = c.ink2,
                                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp))
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.about_trademark), fontSize = 13.sp, color = c.ink2,
                    modifier = Modifier.clickable(role = Role.Button) { open(URL_GAME) }.padding(4.dp),
                )
            }
        }
    }
}

@Composable
private fun LinkRow(icon: Int, text: String, onClick: () -> Unit) {
    val c = Farm.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontSize = 15.sp, color = c.meadow, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        PixelIcon(icon, null, tint = c.meadow, size = 20.dp)
    }
}
