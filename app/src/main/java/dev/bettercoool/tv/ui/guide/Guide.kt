package dev.bettercoool.tv.ui.guide

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.bettercoool.tv.AppGraph
import dev.bettercoool.tv.R
import dev.bettercoool.tv.data.Channel
import dev.bettercoool.tv.data.EpgEntry
import dev.bettercoool.tv.ui.common.Badge
import dev.bettercoool.tv.ui.common.NumberEntry
import dev.bettercoool.tv.ui.common.digitFor
import dev.bettercoool.tv.ui.common.hhmm
import dev.bettercoool.tv.ui.common.minuteClock
import dev.bettercoool.tv.ui.theme.Palette
import dev.bettercoool.tv.ui.theme.Txt
import dev.bettercoool.tv.ui.theme.Type
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val SLOT = 30 * 60_000L
private const val SPAN = 2 * 60 * 60_000L // visible time range
private const val RANGE = 7L * 24 * 60 * 60_000L // how far back/forward the guide goes
private const val ROWS = 7
private val ROW_H = 50.dp
private val CHANNEL_COL = 230.dp

private fun floorSlot(t: Long) = Math.floorDiv(t, SLOT) * SLOT

private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM")

/**
 * Programme grid. The whole grid is one focus target that handles the D-pad itself and one Canvas
 * that draws the visible cells, so it stays fast on weak hardware no matter how many channels or
 * programmes there are (no per-cell composables, no per-cell focus nodes).
 *
 * [onPick] gets the channel index and, for a past programme on a timeshift channel, its start time.
 */
@Composable
fun Guide(
    graph: AppGraph,
    channels: List<Channel>,
    startIndex: Int,
    onPick: (index: Int, fromMs: Long?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openedAt = remember { System.currentTimeMillis() }
    var row by remember { mutableIntStateOf(startIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))) }
    var firstRow by remember { mutableIntStateOf((row - 2).coerceIn(0, (channels.size - ROWS).coerceAtLeast(0))) }
    var focusTime by remember { mutableLongStateOf(openedAt) }
    var windowStart by remember { mutableLongStateOf(floorSlot(openedAt) - SLOT) }
    var digits by remember { mutableStateOf("") }
    val revision by graph.epg.revision.collectAsState()
    val now = minuteClock()
    val focus = remember { FocusRequester() }

    LaunchedEffect(windowStart) { graph.epg.ensureRange(windowStart - SLOT, windowStart + SPAN + SLOT) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler { if (digits.isNotEmpty()) digits = "" else onClose() }

    if (channels.isEmpty()) return
    val channel = channels[row]
    val focused: EpgEntry? = remember(row, focusTime, revision) { graph.epg.programmeAt(channel.id, focusTime) }

    fun keepVisible() {
        if (row < firstRow) firstRow = row
        if (row >= firstRow + ROWS) firstRow = row - ROWS + 1
        focusTime = focusTime.coerceIn(openedAt - RANGE, openedAt + RANGE)
        if (focusTime < windowStart || focusTime >= windowStart + SPAN) windowStart = floorSlot(focusTime) - SLOT
    }

    fun moveRow(delta: Int) {
        row = (row + delta).coerceIn(0, channels.lastIndex)
        keepVisible()
    }

    fun moveTime(dir: Int) {
        val list = graph.epg.programmes(channel.id)
        val cur = list.firstOrNull { it.startTime <= focusTime && focusTime < it.endTime }
        focusTime = when {
            cur == null -> focusTime + dir * SLOT
            dir > 0 -> list.firstOrNull { it.startTime >= cur.endTime }?.startTime ?: cur.endTime
            else -> {
                val prev = list.lastOrNull { it.endTime <= cur.startTime }
                when {
                    prev == null || prev.endTime < cur.startTime -> cur.startTime - 1
                    prev.endTime > windowStart -> maxOf(prev.startTime, windowStart)
                    else -> maxOf(prev.startTime, prev.endTime - SLOT)
                }
            }
        }
        keepVisible()
    }

    fun pick() {
        if (!channel.playable) return
        val p = focused
        val t = System.currentTimeMillis()
        when {
            p == null || (p.startTime <= t && t < p.endTime) -> onPick(row, null)
            p.endTime <= t && channel.hasTimeshift -> onPick(row, p.startTime)
        }
    }

    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1_200)
        val i = channels.indexOfFirst { it.number == digits }
        if (i >= 0) {
            row = i
            keepVisible()
        }
        digits = ""
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Palette.Background.copy(alpha = 0.96f))
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                digitFor(code)?.let {
                    if (digits.length < 4) digits += it
                    return@onKeyEvent true
                }
                when (code) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> moveRow(-1)
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> moveRow(+1)
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> moveRow(-ROWS)
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> moveRow(+ROWS)
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> moveTime(-1)
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> moveTime(+1)
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { focusTime -= 6 * SLOT; keepVisible() }
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { focusTime += 6 * SLOT; keepVisible() }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> pick()
                    AndroidKeyEvent.KEYCODE_GUIDE -> onClose()
                    else -> return@onKeyEvent false
                }
                true
            },
    ) {
        Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 24.dp)) {
            Details(channel, focused, now, Modifier.fillMaxWidth().height(128.dp))
            Ruler(windowStart, now, Modifier.fillMaxWidth().height(28.dp))
            Row(Modifier.fillMaxWidth().height(ROW_H * ROWS)) {
                ChannelColumn(channels, firstRow, row, Modifier.width(CHANNEL_COL))
                Grid(graph, channels, firstRow, row, focused, windowStart, now, revision, Modifier.weight(1f).fillMaxSize())
            }
        }
        if (digits.isNotEmpty()) {
            NumberEntry(digits, channels.firstOrNull { it.number == digits }?.name, Modifier.align(Alignment.TopEnd).padding(32.dp))
        }
    }
}

@Composable
private fun Details(channel: Channel, p: EpgEntry?, now: Long, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(listOfNotNull(channel.number, channel.name).joinToString("  "), Type.Label)
            if (channel.isUhd) {
                Spacer(Modifier.width(8.dp))
                Badge("4K")
            }
        }
        Spacer(Modifier.height(4.dp))
        if (p == null) {
            Txt(stringResource(R.string.epg_none), Type.Title, color = Palette.TextDim)
            return@Column
        }
        Txt(p.name, Type.Title, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        val t = System.currentTimeMillis()
        val action = when {
            !channel.playable -> stringResource(R.string.not_in_plan)
            p.startTime <= t && t < p.endTime -> stringResource(R.string.guide_watch)
            p.endTime <= t && channel.hasTimeshift -> stringResource(R.string.guide_watch_start)
            p.endTime <= t -> stringResource(R.string.guide_no_catchup)
            else -> stringResource(R.string.guide_starts, hhmm(p.startTime))
        }
        Row {
            Txt("${dayLabel(p.startTime)} · ${hhmm(p.startTime)} – ${hhmm(p.endTime)}", Type.Label)
            Spacer(Modifier.width(16.dp))
            Txt(action, Type.Label, color = Palette.Accent)
        }
        if (!p.description.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Txt(p.description, Type.BodyDim, maxLines = 2)
        }
    }
}

@Composable
private fun dayLabel(ms: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    return if (date == LocalDate.now(zone)) stringResource(R.string.today) else dayFormat.format(date)
}

@Composable
private fun Ruler(windowStart: Long, now: Long, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val label = Type.Label
    val day = dayLabel(windowStart)
    Row(modifier) {
        Box(Modifier.width(CHANNEL_COL).fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Txt(day, Type.Label, color = Palette.Text)
        }
        Canvas(Modifier.weight(1f).fillMaxSize()) {
            val pxPerMs = size.width / SPAN
            var t = windowStart
            while (t < windowStart + SPAN) {
                val x = (t - windowStart) * pxPerMs
                drawLine(Palette.SurfaceHigh, Offset(x, size.height - 6.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
                drawText(measurer.measure(hhmm(t), label), color = label.color, topLeft = Offset(x + 4.dp.toPx(), 2.dp.toPx()))
                t += SLOT
            }
            if (now in windowStart until windowStart + SPAN) {
                val x = (now - windowStart) * pxPerMs
                drawCircle(Palette.Accent, 3.dp.toPx(), Offset(x, size.height - 3.dp.toPx()))
            }
        }
    }
}

@Composable
private fun ChannelColumn(channels: List<Channel>, firstRow: Int, row: Int, modifier: Modifier) {
    Column(modifier) {
        for (r in 0 until ROWS) {
            val i = firstRow + r
            val ch = channels.getOrNull(i) ?: break
            val selected = i == row
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(ROW_H)
                    .padding(end = 6.dp, top = 2.dp, bottom = 2.dp)
                    .background(if (selected) Palette.SurfaceHigh else Color.Transparent, RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(ch.number.orEmpty(), Type.Label, Modifier.width(32.dp))
                Box(Modifier.size(width = 48.dp, height = 28.dp), contentAlignment = Alignment.Center) {
                    if (ch.logo != null) AsyncImage(model = ch.logo, contentDescription = null, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(10.dp))
                Txt(ch.name, Type.Label, maxLines = 1, color = if (ch.playable) Palette.Text else Palette.TextFaint)
            }
        }
    }
}

@Composable
private fun Grid(
    graph: AppGraph,
    channels: List<Channel>,
    firstRow: Int,
    row: Int,
    focused: EpgEntry?,
    windowStart: Long,
    now: Long,
    revision: Int,
    modifier: Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 96)
    val title = remember { TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium) }
    val time = remember { TextStyle(fontSize = 12.sp) }
    val windowEnd = windowStart + SPAN

    Canvas(modifier) {
        // Captured so the draw lambda changes (and redraws) when new guide data arrives.
        if (revision < 0) return@Canvas
        val pxPerMs = size.width / SPAN
        val rowH = ROW_H.toPx()
        val gap = 2.dp.toPx()
        val pad = 8.dp.toPx()
        val radius = CornerRadius(6.dp.toPx())

        for (r in 0 until ROWS) {
            val ch = channels.getOrNull(firstRow + r) ?: break
            val y = r * rowH
            val isRow = firstRow + r == row
            val list = graph.epg.programmes(ch.id).filter { it.endTime > windowStart && it.startTime < windowEnd }

            if (list.isEmpty()) {
                drawRoundRect(
                    if (isRow) Palette.Focus.copy(alpha = 0.25f) else Palette.Surface,
                    Offset(gap, y + gap), Size(size.width - 2 * gap, rowH - 2 * gap), radius,
                )
                continue
            }
            for (p in list) {
                val x0 = (maxOf(p.startTime, windowStart) - windowStart) * pxPerMs + gap
                val x1 = (minOf(p.endTime, windowEnd) - windowStart) * pxPerMs - gap
                val w = x1 - x0
                if (w < 2f) continue
                val isFocused = isRow && focused?.id == p.id
                val past = p.endTime <= now
                val live = p.startTime <= now && now < p.endTime
                val replayable = past && ch.hasTimeshift && ch.includedInPlan
                val fill = when {
                    isFocused -> Palette.Focus
                    live -> Palette.SurfaceHigh
                    past -> Palette.Surface.copy(alpha = 0.6f)
                    else -> Palette.Surface
                }
                drawRoundRect(fill, Offset(x0, y + gap), Size(w, rowH - 2 * gap), radius)
                if (replayable && !isFocused) {
                    drawRect(Palette.Accent.copy(alpha = 0.8f), Offset(x0, y + rowH - gap - 3.dp.toPx()), Size(w, 2.dp.toPx()))
                }
                if (w > 3 * pad) {
                    val color = when {
                        isFocused -> Palette.OnFocus
                        past -> Palette.TextDim
                        else -> Palette.Text
                    }
                    // Measure then draw with an explicit colour: the measurer's cache ignores colour,
                    // so drawText(text, style) can reuse a layout carrying a stale colour.
                    val cell = Constraints(maxWidth = (w - 2 * pad).toInt(), maxHeight = 20.dp.roundToPx())
                    drawText(
                        measurer.measure(p.name, title, TextOverflow.Ellipsis, maxLines = 1, constraints = cell),
                        color = color,
                        topLeft = Offset(x0 + pad, y + 7.dp.toPx()),
                    )
                    drawText(
                        measurer.measure(hhmm(p.startTime), time, TextOverflow.Clip, maxLines = 1, constraints = cell),
                        color = if (isFocused) Palette.OnFocus.copy(alpha = 0.7f) else Palette.TextFaint,
                        topLeft = Offset(x0 + pad, y + 27.dp.toPx()),
                    )
                }
            }
        }

        if (now in windowStart until windowEnd) {
            val x = (now - windowStart) * pxPerMs
            drawLine(Palette.Accent, Offset(x, 0f), Offset(x, ROWS * rowH), 2.dp.toPx())
        }
    }
}
