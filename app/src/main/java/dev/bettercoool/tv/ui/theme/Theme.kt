package dev.bettercoool.tv.ui.theme

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * Deliberately tiny design system: flat colours, no shadows/blur/gradients,
 * focus shown with a solid fill + slight scale (cheap on weak GPUs).
 */
object Palette {
    // coool.tv design tokens from the original app: slate-900 #0E0E12, lime-500 #BFCD31.
    val Background = Color(0xFF0E0E12)
    val Surface = Color(0xFF17171D)
    val SurfaceHigh = Color(0xFF23232B)
    /**
     * User-selectable main colour (Settings → colour). Backed by Compose state, so changing it
     * recomposes/redraws everything that reads it. Default: coool.tv lime, toned down from #BFCD31.
     */
    var Accent: Color by mutableStateOf(AccentPresets.first().color)
    /** Focused items use the main colour with dark text. */
    val Focus: Color get() = Accent
    val OnFocus = Color(0xFF0E0E12)
    /** Text/shapes placed on [Accent]. */
    val OnAccent = Color(0xFF0E0E12)
    val Danger = Color(0xFFFF6B5E)
    val Text = Color(0xFFF2F2F5)
    val TextDim = Color(0xFF9A9CA8)
    val TextFaint = Color(0xFF5E606C)
    val Scrim = Color(0xCC0E0E12)
}

/** Main-colour presets. All are light enough for the dark [Palette.OnAccent] text on top. */
data class AccentPreset(val key: String, val color: Color)

val AccentPresets = listOf(
    AccentPreset("coool", Color(0xFFA8B43A)),
    AccentPreset("mint", Color(0xFF5FC9A2)),
    AccentPreset("sky", Color(0xFF63AEE8)),
    AccentPreset("lavender", Color(0xFFA192EA)),
    AccentPreset("coral", Color(0xFFEF8466)),
    AccentPreset("amber", Color(0xFFE6B450)),
    AccentPreset("rose", Color(0xFFE683AE)),
    AccentPreset("silver", Color(0xFFC6CAD3)),
)

object Type {
    val Display = TextStyle(color = Palette.Text, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
    val Title = TextStyle(color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    val Body = TextStyle(color = Palette.Text, fontSize = 16.sp, lineHeight = 22.sp)
    val BodyDim = Body.copy(color = Palette.TextDim)
    val Label = TextStyle(color = Palette.TextDim, fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp)
    val Code = TextStyle(color = Palette.Text, fontSize = 56.sp, fontWeight = FontWeight.Bold, letterSpacing = 10.sp)
}

@Composable
fun Txt(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, color: Color = Color.Unspecified) {
    BasicText(
        text = text,
        modifier = modifier,
        style = if (color == Color.Unspecified) style else style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
