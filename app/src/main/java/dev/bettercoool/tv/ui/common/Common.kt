package dev.bettercoool.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.bettercoool.tv.R
import dev.bettercoool.tv.data.EpgEntry
import dev.bettercoool.tv.ui.theme.Palette
import dev.bettercoool.tv.ui.theme.Txt
import dev.bettercoool.tv.ui.theme.Type
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Category order for the filter row; server keys → labels. */
val CategoryOrder = listOf("information", "news", "sports", "cinema", "entertainments", "science", "children's", "music")

@Composable
fun categoryLabel(key: String): String = when (key) {
    "information" -> stringResource(R.string.cat_information)
    "news" -> stringResource(R.string.cat_news)
    "sports" -> stringResource(R.string.cat_sports)
    "cinema" -> stringResource(R.string.cat_cinema)
    "entertainments" -> stringResource(R.string.cat_entertainments)
    "science" -> stringResource(R.string.cat_science)
    "children's" -> stringResource(R.string.cat_childrens)
    "music" -> stringResource(R.string.cat_music)
    else -> key.replaceFirstChar { it.uppercase() }
}

private val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

fun hhmm(epochMs: Long): String = clock.format(Instant.ofEpochMilli(epochMs))

/** Wall clock that only ticks once a minute (on the minute), so progress bars stay cheap. */
@Composable
fun minuteClock(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun Badge(text: String, color: Color = Palette.Accent, textColor: Color = Palette.OnAccent) {
    Box(Modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
        Txt(text, Type.Label, color = textColor)
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    // Two rounded rects in one draw call; no clip (clip paths are slow on TV GPUs and this is in every row).
    val f = fraction.coerceIn(0f, 1f)
    Spacer(
        modifier.height(3.dp).drawBehind {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(Palette.SurfaceHigh, cornerRadius = r)
            if (f > 0f) drawRoundRect(Palette.Accent, size = Size(size.width * f, size.height), cornerRadius = r)
        },
    )
}

/** "Now" with time range + progress, optional description, then "Next". */
@Composable
fun NowNext(now: EpgEntry?, next: EpgEntry?, clockMs: Long, modifier: Modifier = Modifier, descriptionLines: Int = 0) {
    Column(modifier) {
        if (now == null && next == null) {
            Txt(stringResource(R.string.epg_none), Type.BodyDim)
            return@Column
        }
        if (now != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(stringResource(R.string.epg_now).uppercase(), Type.Label, color = Palette.Accent)
                Spacer(Modifier.width(10.dp))
                Txt("${hhmm(now.startTime)} – ${hhmm(now.endTime)}", Type.Label)
            }
            Spacer(Modifier.height(4.dp))
            Txt(now.name, Type.Title, maxLines = 2)
            Spacer(Modifier.height(8.dp))
            val span = (now.endTime - now.startTime).coerceAtLeast(1)
            ProgressBar((clockMs - now.startTime).toFloat() / span, Modifier.width(320.dp))
            if (descriptionLines > 0 && !now.description.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Txt(now.description, Type.BodyDim, maxLines = descriptionLines)
            }
        }
        if (next != null) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(stringResource(R.string.epg_next).uppercase(), Type.Label)
                Spacer(Modifier.width(10.dp))
                Txt(hhmm(next.startTime), Type.Label)
                Spacer(Modifier.width(10.dp))
                Txt(next.name, Type.Body, maxLines = 1)
            }
        }
    }
}

/** Big digits shown while the user types a channel number. */
@Composable
fun NumberEntry(digits: String, label: String?, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Palette.Scrim, RoundedCornerShape(16.dp)).padding(horizontal = 28.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Txt(digits, Type.Code)
        if (label != null) Txt(label, Type.Body, maxLines = 1)
    }
}

/** Maps a key code to a digit, or null. */
fun digitFor(keyCode: Int): Char? = when (keyCode) {
    in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> '0' + (keyCode - android.view.KeyEvent.KEYCODE_0)
    in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 -> '0' + (keyCode - android.view.KeyEvent.KEYCODE_NUMPAD_0)
    else -> null
}
