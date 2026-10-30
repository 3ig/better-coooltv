package dev.bettercoool.tv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

/**
 * Request focus, retrying for a few frames: lazy-list items may not be composed/attached yet
 * when the requesting effect first runs, and a TV UI with nothing focused swallows every key.
 */
suspend fun FocusRequester.requestFocusSoon() {
    repeat(10) {
        if (runCatching { requestFocus() }.isSuccess) return
        withFrameNanos { }
    }
}

/**
 * Handle Back inside an overlay before Compose's focus system does. Otherwise the first Back on a
 * focused child only clears focus (and is consumed), leaving the overlay open with nothing focused.
 */
fun Modifier.onBack(action: () -> Unit): Modifier = onPreviewKeyEvent { e ->
    if (e.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
    if (e.type == KeyEventType.KeyUp) action()
    true
}

/**
 * D-pad focus target. Focus = solid light fill + 3% scale on the graphics layer
 * (no relayout, no shadow). Content gets `focused` to flip its text colours.
 */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    idleColor: Color = Color.Transparent,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .graphicsLayer {
                val s = if (focused) 1.03f else 1f
                scaleX = s
                scaleY = s
            }
            .background(if (focused) Palette.Focus else idleColor, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        content(focused)
    }
}
