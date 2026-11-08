package app.farmsync.companion.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.farmsync.companion.R
import app.farmsync.companion.saves.SaveInfo
import app.farmsync.companion.ui.theme.Farm
import app.farmsync.companion.ui.theme.FarmType

/** A rectangle whose corners are cut in two pixel steps, like a pixel-art panel. */
class PixelShape(private val step: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val s = with(density) { step.toPx() }
        val w = size.width
        val h = size.height
        return Outline.Generic(Path().apply {
            moveTo(0f, 2 * s); lineTo(s, 2 * s); lineTo(s, s); lineTo(2 * s, s); lineTo(2 * s, 0f)
            lineTo(w - 2 * s, 0f); lineTo(w - 2 * s, s); lineTo(w - s, s); lineTo(w - s, 2 * s); lineTo(w, 2 * s)
            lineTo(w, h - 2 * s); lineTo(w - s, h - 2 * s); lineTo(w - s, h - s); lineTo(w - 2 * s, h - s); lineTo(w - 2 * s, h)
            lineTo(2 * s, h); lineTo(2 * s, h - s); lineTo(s, h - s); lineTo(s, h - 2 * s); lineTo(0f, h - 2 * s)
            close()
        })
    }
}

/** Pixel panel: stepped frame, flat fill, one-step bevel (light top edge, shaded bottom edge). */
fun Modifier.pixelFrame(
    fill: Color,
    frame: Color,
    shade: Color? = null,
    highlight: Color? = null,
    border: Dp = 4.dp,
): Modifier = this
    .clip(PixelShape(border))
    .background(frame)
    .padding(border)
    .clip(PixelShape(border / 2))
    .background(fill)
    .drawBehind {
        val b = border.toPx()
        highlight?.let { drawRect(it, Offset.Zero, Size(size.width, b)) }
        shade?.let { drawRect(it, Offset(0f, size.height - b), Size(size.width, b)) }
    }

@Composable
fun Modifier.card(): Modifier =
    pixelFrame(Farm.colors.card, Farm.colors.frame, Farm.colors.shade, Farm.colors.highlight)

@Composable
fun PixelIcon(
    @DrawableRes id: Int,
    contentDescription: String?,
    tint: Color = Farm.colors.ink,
    size: Dp = 24.dp,
) {
    Icon(painterResource(id), contentDescription, Modifier.size(size), tint = tint)
}

/** The signature element: a season-tinted calendar tile with the in-game day. */
@Composable
fun DayTile(info: SaveInfo?, big: Boolean = false) {
    val c = Farm.colors
    val seasons = stringArrayResource(R.array.seasons)
    val season = info?.season ?: -1
    val fill = when (season) {
        0 -> c.spring; 1 -> c.summer; 2 -> c.fall; 3 -> c.winter; else -> c.card
    }
    val description = if (info != null) {
        stringResource(R.string.a11y_day_tile, info.day, seasons.getOrElse(season) { "?" }, info.year)
    } else {
        stringResource(R.string.a11y_day_unknown)
    }
    Column(
        modifier = Modifier
            .size(if (big) 96.dp else 64.dp, if (big) 108.dp else 76.dp)
            .pixelFrame(fill, c.tileFrame, border = 3.dp)
            .clearAndSetSemantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            info?.day?.toString() ?: "?",
            style = if (big) FarmType.tileNumberBig else FarmType.tileNumber,
            color = c.tileInk,
        )
        Text(seasons.getOrElse(season) { "" }, fontSize = 12.sp, color = c.tileInk, modifier = Modifier.padding(top = 3.dp))
        if (info != null) Text(stringResource(R.string.year_short, info.year), fontSize = 11.sp, color = c.tileInk)
    }
}

/** Icon + short text, coloured by meaning; the text always carries the meaning too. */
@Composable
fun StatusLine(@DrawableRes icon: Int, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PixelIcon(icon, null, tint = color, size = 18.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

enum class ButtonKind { FILLED, TONAL }

@Composable
fun PixelButton(
    text: String,
    onClick: () -> Unit,
    @DrawableRes icon: Int? = null,
    kind: ButtonKind = ButtonKind.TONAL,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val c = Farm.colors
    val (fill, fg) = when (kind) {
        ButtonKind.FILLED -> c.meadow to c.onMeadow
        ButtonKind.TONAL -> c.meadowBg to c.ink
    }
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .pixelFrame(if (enabled) fill else c.shade, c.buttonFrame, border = 3.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let { PixelIcon(it, null, tint = fg, size = 20.dp); Spacer(Modifier.width(8.dp)) }
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

/** Square pixel toggle; behaves and announces like a standard switch. */
@Composable
fun PixelSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = Farm.colors
    Box(
        modifier = Modifier
            .size(60.dp, 36.dp)
            .pixelFrame(if (checked) c.meadow else c.shade, if (checked) c.buttonFrame else c.ink2, border = 3.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(22.dp).background(if (checked) c.onMeadow else c.ink2))
    }
}

/** Decorative pixel hill along the bottom of the farm list (hidden from screen readers). */
@Composable
fun PixelHill(modifier: Modifier = Modifier) {
    val color = Farm.colors.hill
    Canvas(modifier.fillMaxWidth().height(24.dp).clearAndSetSemantics { }) {
        val px = 4.dp.toPx()
        drawRect(color, Offset(0f, 3 * px), Size(size.width, size.height - 3 * px))
        var x = 0f
        var i = 0
        while (x < size.width) {
            val bumps = intArrayOf(2, 1, 0, 1, 2, 2, 1, 1)
            val top = bumps[i % bumps.size] * px
            drawRect(color, Offset(x, top), Size(6 * px, 3 * px - top + 1))
            x += 6 * px
            i++
        }
    }
}

/** App mark for the top bar: the two launcher-icon layers, clipped to a pixel shape. */
@Composable
fun AppMark(size: Dp = 36.dp) {
    Box(Modifier.size(size).clip(PixelShape(3.dp)).semantics { }) {
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
    }
}
