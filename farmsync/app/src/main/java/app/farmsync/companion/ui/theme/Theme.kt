package app.farmsync.companion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.farmsync.companion.R

/**
 * "Valley sky" palette (research/04-design-plan.md). Every text pair meets WCAG AA (most 7:1):
 * ink on card 14.6:1 / 13.7:1, meadow buttons 5.4:1 / 10.8:1, harvest with ink 8.7:1.
 */
@Immutable
data class FarmColors(
    val sky: Color,
    val card: Color,
    val ink: Color,
    val ink2: Color,
    val meadow: Color,
    val onMeadow: Color,
    val meadowBg: Color,
    val harvest: Color,
    val harvestBg: Color,
    val bark: Color,
    val alert: Color,
    val alertBg: Color,
    val frame: Color,
    val buttonFrame: Color,
    val shade: Color,
    val highlight: Color,
    val tileFrame: Color,
    val tileInk: Color,
    val offerFrame: Color,
    val hill: Color,
    val spring: Color,
    val summer: Color,
    val fall: Color,
    val winter: Color,
)

val LightFarm = FarmColors(
    sky = Color(0xFFEEF7FA), card = Color(0xFFFFFDF6), ink = Color(0xFF1C2B33), ink2 = Color(0xFF4A5E66),
    meadow = Color(0xFF1F7A33), onMeadow = Color(0xFFFFFFFF), meadowBg = Color(0xFFDDF2E0),
    harvest = Color(0xFF8A5A0B), harvestBg = Color(0xFFFCEFC5), bark = Color(0xFF7A4B26),
    alert = Color(0xFFB3261E), alertBg = Color(0xFFFCE4E1),
    frame = Color(0xFF8A5A33), buttonFrame = Color(0xFF14521F), shade = Color(0xFFE8DCC4), highlight = Color(0xFFFFFFFF),
    tileFrame = Color(0xFF5E3A1E), tileInk = Color(0xFF1C2B33), offerFrame = Color(0xFFB9C9CF), hill = Color(0xFFA6DC88),
    spring = Color(0xFFCDEFC4), summer = Color(0xFFFCE7A6), fall = Color(0xFFF6C9A0), winter = Color(0xFFD3E6F7),
)

val DarkFarm = FarmColors(
    sky = Color(0xFF0F0D2A), card = Color(0xFF1B2140), ink = Color(0xFFE8F1F4), ink2 = Color(0xFFA9B8C4),
    meadow = Color(0xFF7BD88A), onMeadow = Color(0xFF0F0D2A), meadowBg = Color(0xFF1F3B32),
    harvest = Color(0xFFF4C04A), harvestBg = Color(0xFF3B3216), bark = Color(0xFFD9A77A),
    alert = Color(0xFFFFB4AB), alertBg = Color(0xFF4A1F24),
    frame = Color(0xFF5E4A7A), buttonFrame = Color(0xFF3E8A4B), shade = Color(0xFF121733), highlight = Color(0xFF2A3157),
    tileFrame = Color(0xFF8A6A4A), tileInk = Color(0xFFE8F1F4), offerFrame = Color(0xFF3A4270), hill = Color(0xFF1F3B32),
    spring = Color(0xFF2E4A2E), summer = Color(0xFF4A3F1C), fall = Color(0xFF4A2F1E), winter = Color(0xFF22344A),
)

val LocalFarmColors = staticCompositionLocalOf { LightFarm }

/** Pixel display face, only for titles, farm names and day numbers (19 sp and up). */
val Pixelify = FontFamily(
    Font(R.font.pixelify_sans, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.pixelify_sans, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

object FarmType {
    val appTitle = TextStyle(fontFamily = Pixelify, fontWeight = FontWeight.Bold, fontSize = 28.sp)
    val screenTitle = TextStyle(fontFamily = Pixelify, fontWeight = FontWeight.Bold, fontSize = 24.sp)
    val section = TextStyle(fontFamily = Pixelify, fontWeight = FontWeight.Bold, fontSize = 19.sp)
    val farmName = TextStyle(fontFamily = Pixelify, fontWeight = FontWeight.Bold, fontSize = 21.sp)
    val heroName = TextStyle(fontFamily = Pixelify, fontWeight = FontWeight.Bold, fontSize = 28.sp)
    val tileNumber = TextStyle(fontFamily = Pixelify, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 30.sp)
    val tileNumberBig = TextStyle(fontFamily = Pixelify, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 44.sp)
}

object Farm {
    val colors: FarmColors @Composable get() = LocalFarmColors.current
}

@Composable
fun FarmSyncTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) DarkFarm else LightFarm
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.meadow, onPrimary = c.onMeadow, primaryContainer = c.meadowBg, onPrimaryContainer = c.ink,
            secondary = c.bark, background = c.sky, onBackground = c.ink, surface = c.card, onSurface = c.ink,
            surfaceVariant = c.card, onSurfaceVariant = c.ink2, surfaceContainer = c.card, surfaceContainerHigh = c.card,
            surfaceContainerHighest = c.card, error = c.alert, outline = c.frame,
        )
    } else {
        lightColorScheme(
            primary = c.meadow, onPrimary = c.onMeadow, primaryContainer = c.meadowBg, onPrimaryContainer = c.ink,
            secondary = c.bark, background = c.sky, onBackground = c.ink, surface = c.card, onSurface = c.ink,
            surfaceVariant = c.card, onSurfaceVariant = c.ink2, surfaceContainer = c.card, surfaceContainerHigh = c.card,
            surfaceContainerHighest = c.card, error = c.alert, outline = c.frame,
        )
    }
    CompositionLocalProvider(LocalFarmColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
