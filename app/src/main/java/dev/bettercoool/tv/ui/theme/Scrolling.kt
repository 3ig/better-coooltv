package dev.bettercoool.tv.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import kotlin.math.abs

/** True while a D-pad key is auto-repeating (held down). Set from MainActivity.dispatchKeyEvent. */
object KeyRepeat {
    @Volatile
    var active = false
}

/**
 * D-pad list scrolling tuned for TVs: scroll only when the focused row would get within one row
 * of an edge (so the next row is always visible), and finish quickly — a held key repeats every
 * ~50 ms, and the default spring would keep piling up long animations behind it.
 */
@OptIn(ExperimentalFoundationApi::class)
private object SnappyBringIntoView : BringIntoViewSpec {
    private val glide: AnimationSpec<Float> = tween(durationMillis = 110, easing = FastOutSlowInEasing)
    private val instant: AnimationSpec<Float> = snap()

    // While a key is held, jump straight to each row: on this hardware the display path can't
    // guarantee every in-between animation frame, and finished positions never look like stutter.
    override val scrollAnimationSpec: AnimationSpec<Float> get() = if (KeyRepeat.active) instant else glide

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val margin = minOf(size, containerSize / 4)
        val top = offset - margin
        val bottom = offset + size + margin
        return when {
            top >= 0 && bottom <= containerSize -> 0f
            size + 2 * margin > containerSize -> offset - (containerSize - size) / 2
            abs(top) < abs(bottom - containerSize) -> top
            else -> bottom - containerSize
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SnappyScroll(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides SnappyBringIntoView, content = content)
}
