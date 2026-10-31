package dev.bettercoool.tv.ui.player.classic

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import coil.compose.AsyncImage
import dev.bettercoool.tv.R
import dev.bettercoool.tv.data.Channel
import dev.bettercoool.tv.data.EpgEntry
import dev.bettercoool.tv.ui.common.ReplayIcon
import dev.bettercoool.tv.ui.common.hhmm
import dev.bettercoool.tv.ui.theme.Palette
import dev.bettercoool.tv.ui.theme.Txt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The old app sized everything in pixels of a 1080-line screen and scaled by screen height;
 * [invoke] and [sp] do the same so the layout keeps its proportions on 720p and 4K UIs.
 */
@Immutable
internal class Px(private val unit: Dp, private val density: Density) {
    operator fun invoke(n: Number): Dp = unit * n.toFloat()
    fun sp(n: Number): TextUnit = with(density) { (unit * n.toFloat()).toSp() }

    fun text(size: Number, weight: FontWeight = FontWeight.Normal, color: Color = Palette.Text, align: TextAlign = TextAlign.Start) =
        TextStyle(color = color, fontSize = sp(size), lineHeight = sp(size.toFloat() * 1.35f), fontWeight = weight, textAlign = align)
}

// Heights of the overlay's lower block, top to bottom (1080p px).
internal const val PROGRESS_HEIGHT = 176
internal const val STRIP_HEIGHT = 230
internal const val EPG_HEIGHT = 290
/** On the progress bar only this much of the channel strip peeks out below it. */
internal const val STRIP_PEEK = 80

internal val Black70 = Color.Black.copy(alpha = 0.7f)
private val TileFill = Color.Black.copy(alpha = 0.45f)
private val Track = Color(0xFF2C2C2C)
private val CardTrack = Color.White.copy(alpha = 0.22f)
private val NumberShadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 2f), 6f)

/** Top fade behind the zap banner and the settings bar. */
internal val TopFade = Brush.verticalGradient(
    listOf(Color.Black.copy(alpha = 0.7f), Color.Black.copy(alpha = 0.5f), Color.Black.copy(alpha = 0.3f), Color.Black.copy(alpha = 0.1f), Color.Transparent),
)

/** Bottom-up darkening under the overlay; lower start when the lists are up. */
internal fun overlayGradient(lists: Boolean): Brush {
    val stops = if (lists) listOf(0.4f, 0.6f, 0.8f, 1f) else listOf(0.15f, 0.3f, 0.7f, 1f)
    val colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.3f), Color.Black.copy(alpha = 0.9f), Color.Black)
    return Brush.verticalGradient(*stops.zip(colors).toTypedArray())
}

/** How a tile shows focus: none, the accent outline, or a faint outline (focus is in the programme row). */
internal enum class TileFocus { None, Focused, Selected }

/** Square channel tile: replay mark, number and "playing" dot along the top, logo centred below. */
@Composable
internal fun ChannelTile(px: Px, channel: Channel, playing: Boolean, focus: TileFocus, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(px(16))
    Box(
        modifier
            .size(px(150))
            .alpha(if (channel.includedInPlan) 1f else 0.6f)
            .background(TileFill, shape)
            .then(
                when (focus) {
                    TileFocus.Focused -> Modifier.border(px(3), Palette.Accent, shape)
                    TileFocus.Selected -> Modifier.border(px(3), Palette.TextFaint, shape)
                    TileFocus.None -> Modifier
                },
            ),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = px(12), end = px(16), top = px(10)), verticalAlignment = Alignment.CenterVertically) {
            if (channel.hasTimeshift) ReplayIcon(px(26), Palette.Text) else Spacer(Modifier.size(px(26)))
            Spacer(Modifier.width(px(14)))
            Txt(channel.number.orEmpty(), px.text(25, FontWeight.SemiBold).copy(shadow = NumberShadow), Modifier.weight(1f), maxLines = 1)
            if (playing) Box(Modifier.size(px(17)).background(Palette.Accent, CircleShape))
        }
        // Logos are square PNGs with their own transparent margin; the old app drew them 125 px, bottom-centred.
        if (channel.logo != null) {
            AsyncImage(
                model = channel.logo,
                contentDescription = null,
                modifier = Modifier.align(Alignment.BottomCenter).size(px(125)),
            )
        }
    }
}

/**
 * Horizontal strip of 11 tiles (5 either side of the focused one), wrapping around the list,
 * with the focused channel's name below and a hint towards the playing channel when it's off-strip.
 */
@Composable
internal fun ChannelStrip(px: Px, channels: List<Channel>, focused: Int, playing: Int, focus: TileFocus, modifier: Modifier = Modifier) {
    val n = channels.size
    Column(modifier.fillMaxWidth().height(px(STRIP_HEIGHT)), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.wrapContentWidth(unbounded = true).padding(top = px(20), bottom = px(16)), horizontalArrangement = Arrangement.spacedBy(px(30))) {
            for (d in -5..5) {
                // Short lists: leave the slots empty rather than showing a channel twice.
                if (n == 0 || (n < 11 && (d < -(n - 1) / 2 || d > n / 2))) {
                    Spacer(Modifier.size(px(150)))
                    continue
                }
                val i = (focused + d).mod(n)
                ChannelTile(px, channels[i], playing = i == playing, focus = if (d == 0) focus else TileFocus.None)
            }
        }
        // Shortest way round to the playing channel; hinted once it's beyond the visible tiles.
        val away = if (n == 0) 0 else ((playing - focused).mod(n)).let { if (it > n / 2) it - n else it }
        Row(Modifier.fillMaxWidth().padding(horizontal = px(60)), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (away < -5) Txt("‹  " + channels[playing].name, px.text(24, color = Palette.Accent), maxLines = 1)
            }
            Txt(channels.getOrNull(focused)?.name.orEmpty(), px.text(30), Modifier.widthIn(max = px(900)), maxLines = 1)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (away > 5) Txt(channels[playing].name + "  ›", px.text(24, color = Palette.Accent), maxLines = 1)
            }
        }
    }
}

internal const val BAR_WIDTH = 1000

/**
 * Programme title over the timeline: played part, available part up to live, and (when
 * [playheadLabel] is set) the playhead with its label.
 */
@Composable
internal fun ProgressLayer(
    px: Px,
    title: String?,
    from: Long,
    to: Long,
    position: Long,
    playheadLabel: String?,
    modifier: Modifier = Modifier,
) {
    val now = System.currentTimeMillis()
    val span = (to - from).coerceAtLeast(1).toFloat()
    val played = ((position - from) / span).coerceIn(0f, 1f)
    val available = ((now - from) / span).coerceIn(0f, 1f)
    Column(modifier.fillMaxWidth().height(px(PROGRESS_HEIGHT)), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(px(14)))
        Txt(title.orEmpty(), px.text(42, align = TextAlign.Center), Modifier.widthIn(max = px(1200)), maxLines = 1)
        Spacer(Modifier.height(px(28)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(hhmm(from), px.text(30, align = TextAlign.End), Modifier.width(px(110)).padding(end = px(16)), maxLines = 1)
            Box(Modifier.width(px(BAR_WIDTH)).height(px(8))) {
                Box(
                    Modifier.matchParentSize().drawBehind {
                        drawRect(Track)
                        drawRect(Palette.Accent.copy(alpha = 0.35f), size = Size(size.width * available, size.height))
                        drawRect(Palette.Accent, size = Size(size.width * played, size.height))
                        if (playheadLabel != null) {
                            drawRect(Palette.Text, Offset(size.width * played - 1.5f, -size.height * 1.5f), Size(3f, size.height * 4))
                        }
                    },
                )
                // The bar is only 8 px tall; the label above it must not be squeezed into that.
                if (playheadLabel != null) {
                    Txt(
                        playheadLabel,
                        px.text(22, FontWeight.SemiBold, align = TextAlign.Center),
                        Modifier.offset(x = px(BAR_WIDTH * played) - px(100), y = -px(40)).wrapContentHeight(unbounded = true).width(px(200)),
                        maxLines = 1,
                    )
                }
            }
            Txt(hhmm(to), px.text(30), Modifier.width(px(110)).padding(start = px(16)), maxLines = 1)
        }
    }
}

private val dayMonth = DateTimeFormatter.ofPattern("dd.MM").withZone(ZoneId.systemDefault())

/** "Днес 31.10", "Вчера 30.10", or just the date further away. */
@Composable
internal fun dayLabel(ms: Long): String {
    val zone = ZoneId.systemDefault()
    val days = ChronoUnit.DAYS.between(LocalDate.now(zone), Instant.ofEpochMilli(ms).atZone(zone).toLocalDate())
    val word = when (days) {
        0L -> stringResource(R.string.day_today)
        -1L -> stringResource(R.string.day_yesterday)
        1L -> stringResource(R.string.day_tomorrow)
        else -> null
    }
    return listOfNotNull(word, dayMonth.format(Instant.ofEpochMilli(ms))).joinToString(" ")
}

private const val SIDE_CARD = 436
private const val CENTRE_CARD = 928
private const val CARD_GAP = 40

/**
 * Programme row for one channel: the focused programme wide in the middle with its description,
 * neighbours either side with their titles, each over a progress line. [active] = focus is in this row.
 */
@Composable
internal fun EpgRow(px: Px, programmes: List<EpgEntry>, focused: Int, clock: Long, active: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(px(EPG_HEIGHT)), contentAlignment = Alignment.TopCenter) {
        Row(Modifier.wrapContentWidth(unbounded = true), horizontalArrangement = Arrangement.spacedBy(px(CARD_GAP))) {
            for (d in -2..2) {
                val p = programmes.getOrNull(focused + d)
                if (p == null && (d != 0 || programmes.isNotEmpty())) {
                    Spacer(Modifier.width(px(if (d == 0) CENTRE_CARD else SIDE_CARD)))
                } else {
                    EpgCard(px, p, centre = d == 0, active = active && d == 0, clock)
                }
            }
        }
    }
}

@Composable
private fun EpgCard(px: Px, p: EpgEntry?, centre: Boolean, active: Boolean, clock: Long) {
    val shape = RoundedCornerShape(px(16))
    Column(Modifier.width(px(if (centre) CENTRE_CARD else SIDE_CARD)).height(px(EPG_HEIGHT))) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(if (active) Modifier.background(TileFill, shape).border(px(3), Palette.Accent, shape) else Modifier)
                .padding(horizontal = px(28), vertical = px(16)),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (p == null) {
                Txt(stringResource(R.string.epg_none), px.text(25, color = Palette.TextDim, align = TextAlign.Center))
                return@Column
            }
            Row {
                Txt(dayLabel(p.startTime) + " ", px.text(25, color = Palette.TextDim), maxLines = 1)
                Txt("${hhmm(p.startTime)} - ${hhmm(p.endTime)}", px.text(25, color = Palette.Accent), maxLines = 1)
            }
            Spacer(Modifier.height(px(8)))
            val description = p.description?.takeIf { it.isNotBlank() }
            when {
                !centre -> Txt(p.name, px.text(25, align = TextAlign.Center), maxLines = 1)
                // The title is already above the timeline unless this row has focus.
                active || description == null -> {
                    Txt(p.name, px.text(28, FontWeight.SemiBold, align = TextAlign.Center), maxLines = 1)
                    if (description != null) {
                        Spacer(Modifier.height(px(6)))
                        Txt(description, px.text(23, color = Palette.TextDim, align = TextAlign.Center), maxLines = 3)
                    }
                }
                else -> Txt(description, px.text(24, align = TextAlign.Center), maxLines = 4)
            }
        }
        val f = ((clock - p?.startTime.orZero()).toFloat() / ((p?.endTime ?: 0) - p?.startTime.orZero()).coerceAtLeast(1)).coerceIn(0f, 1f)
        Spacer(Modifier.height(px(12)))
        Box(
            Modifier.fillMaxWidth().height(px(5)).drawBehind {
                drawRect(CardTrack)
                if (p != null) drawRect(Palette.Accent, size = Size(size.width * f, size.height))
            },
        )
        Spacer(Modifier.height(px(12)))
    }
}

private fun Long?.orZero() = this ?: 0L

/** One of the three round buttons in the top bar, with its label pill when focused. */
@Composable
internal fun RoundButton(px: Px, glyph: String, label: String, focused: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(px(64))
                .background(if (focused) Palette.Accent else Black70, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Txt(glyph, px.text(26, FontWeight.Bold, if (focused) Palette.OnAccent else Palette.Text), maxLines = 1)
        }
        Spacer(Modifier.height(px(10)))
        Box(Modifier.height(px(44)), contentAlignment = Alignment.Center) {
            if (focused) {
                Txt(
                    label,
                    px.text(18),
                    Modifier.widthIn(min = px(120)).background(Color.Black, RoundedCornerShape(px(8))).padding(horizontal = px(14), vertical = px(6)),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Zap banner at the top: tile, channel name, programme and its time slot; "swap" adds the swap-back button. */
@Composable
internal fun ZapBanner(
    px: Px,
    channel: Channel,
    now: EpgEntry?,
    playing: Boolean,
    swap: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().background(TopFade).padding(start = px(60), end = px(60), top = px(20), bottom = px(60)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChannelTile(px, channel, playing = playing, focus = TileFocus.None)
        Spacer(Modifier.width(px(30)))
        Column(Modifier.weight(1f)) {
            Txt(channel.name, px.text(34, FontWeight.SemiBold), maxLines = 1)
            if (now != null) {
                Txt(now.name, px.text(24), maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(dayLabel(now.startTime) + "   ", px.text(24, color = Palette.TextDim), maxLines = 1)
                    Txt("${hhmm(now.startTime)} - ${hhmm(now.endTime)}", px.text(24, color = Palette.Accent), maxLines = 1)
                    if (channel.hasTimeshift) {
                        Spacer(Modifier.width(px(12)))
                        ReplayIcon(px(24), Palette.Accent)
                    }
                }
            }
        }
        if (swap) {
            Row(
                Modifier.border(px(3), Palette.Accent, RoundedCornerShape(px(30))).background(Black70, RoundedCornerShape(px(30)))
                    .padding(horizontal = px(24), vertical = px(12)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt("↩", px.text(28, FontWeight.Bold, Palette.Accent), maxLines = 1)
                Spacer(Modifier.width(px(10)))
                Txt(stringResource(R.string.classic_swap), px.text(20), maxLines = 1)
            }
        }
    }
}
