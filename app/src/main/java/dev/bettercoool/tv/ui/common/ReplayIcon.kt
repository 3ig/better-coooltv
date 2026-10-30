package dev.bettercoool.tv.ui.common

import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.bettercoool.tv.ui.theme.Palette
import dev.bettercoool.tv.ui.theme.Txt
import dev.bettercoool.tv.ui.theme.Type

/**
 * The one symbol for "this channel/programme can be watched back" (timeshift / catch-up).
 * A vector (Material "replay" shape) so Compose rasterises it once and then blits a cached bitmap —
 * it appears in every list row, so it must be cheap to draw.
 */
val ReplayVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "replay",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(
            "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z",
        ),
        fill = SolidColor(Color.White),
    ).build()
}

@Composable
fun ReplayIcon(size: Dp = 14.dp, color: Color = Palette.Accent, modifier: Modifier = Modifier) {
    Image(
        painter = rememberVectorPainter(ReplayVector),
        contentDescription = null,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(color),
    )
}

/** Pill with the replay icon and a label, e.g. "Архив" or "−12:30 зад живо". */
@Composable
fun ReplayPill(text: String, modifier: Modifier = Modifier, filled: Boolean = false) {
    val fg = if (filled) Palette.OnAccent else Palette.Accent
    Row(
        modifier
            .background(if (filled) Palette.Accent else Palette.Accent.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReplayIcon(12.dp, fg)
        Spacer(Modifier.width(5.dp))
        Txt(text, Type.Label, maxLines = 1, color = fg)
    }
}
