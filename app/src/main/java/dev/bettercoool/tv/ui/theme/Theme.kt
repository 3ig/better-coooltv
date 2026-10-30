package dev.bettercoool.tv.ui.theme

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
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
    val Background = Color(0xFF0B0B10)
    val Surface = Color(0xFF16161D)
    val SurfaceHigh = Color(0xFF22222C)
    val Focus = Color(0xFFF2F2F5)
    val OnFocus = Color(0xFF0B0B10)
    val Accent = Color(0xFFFF5A36)
    val Text = Color(0xFFF2F2F5)
    val TextDim = Color(0xFF9A9AA8)
    val TextFaint = Color(0xFF5C5C68)
    val Scrim = Color(0xCC0B0B10)
}

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
