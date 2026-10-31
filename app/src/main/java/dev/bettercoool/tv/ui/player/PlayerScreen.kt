package dev.bettercoool.tv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Format
import coil.compose.AsyncImage
import dev.bettercoool.tv.AppGraph
import dev.bettercoool.tv.R
import dev.bettercoool.tv.data.Channel
import dev.bettercoool.tv.ui.common.Badge
import dev.bettercoool.tv.ui.common.ReplayPill
import dev.bettercoool.tv.ui.common.NowNext
import dev.bettercoool.tv.ui.common.NumberEntry
import dev.bettercoool.tv.ui.common.digitFor
import dev.bettercoool.tv.ui.common.hhmm
import dev.bettercoool.tv.ui.common.minuteClock
import dev.bettercoool.tv.ui.guide.Guide
import dev.bettercoool.tv.ui.theme.Palette
import dev.bettercoool.tv.ui.theme.requestFocusSoon
import dev.bettercoool.tv.ui.theme.Txt
import dev.bettercoool.tv.ui.theme.Type
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val SEEK_STEP = 30_000L

/**
 * Full-screen TV. Video renders on a SurfaceView (hardware overlay plane: required for smooth 4K
 * and HDR on weak boxes). Overlays (banner, guide) are only composed while visible.
 *
 * Remote: Up/Down zap · digits jump · OK banner→guide · Left/Right ∓30 s (timeshift channels,
 * also rewinds live) · Play/Pause pauses live TV and resumes from the archive · Back leaves replay first.
 */
@Composable
fun PlayerScreen(graph: AppGraph, channels: List<Channel>, startIndex: Int, startFrom: Long?, onExit: () -> Unit) {
    val c = rememberPlayerController(graph, channels, startIndex, startFrom)
    var bannerKey by remember { mutableIntStateOf(0) }
    var showBanner by remember { mutableStateOf(true) }
    var guideOpen by remember { mutableStateOf(false) }
    var digits by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var listOpen by remember { mutableStateOf(false) }
    var listStart by remember { mutableIntStateOf(0) }
    var guideStart by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<Int?>(null) } // transient one-line hint in the banner
    var okLongPressed by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val channel = c.channel

    // Banner auto-hide; stays up while paused or seeking. Also shown when a seek lands.
    LaunchedEffect(bannerKey, c.seeks) {
        showBanner = true
        delay(5_000)
        showBanner = false
    }

    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1_500)
        c.playNumber(digits)
        bannerKey++
        digits = ""
    }

    val overlayOpen = guideOpen || menuOpen || listOpen
    LaunchedEffect(overlayOpen) { if (!overlayOpen) focus.requestFocusSoon() }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(3_500)
            notice = null
        }
    }

    BackHandler(enabled = menuOpen) { menuOpen = false }
    BackHandler(enabled = !overlayOpen) {
        when {
            digits.isNotEmpty() -> digits = ""
            c.fromTime != null || c.paused -> {
                c.goLive()
                bannerKey++
            }
            else -> onExit()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && c.onInput()) return@onPreviewKeyEvent true
                // A long-press OK opened something (guide, menu). The rest of that press — further
                // auto-repeats and the release — must not reach it: the guide plays on OK key-down.
                if (okLongPressed && isOk(e.nativeKeyEvent.keyCode)) {
                    if (e.type == KeyEventType.KeyUp) okLongPressed = false
                    return@onPreviewKeyEvent true
                }
                false
            }
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (overlayOpen) return@onKeyEvent false
                val code = event.nativeKeyEvent.keyCode
                // OK acts on release (so the release can't land on the overlay it opens);
                // holding OK opens the options — for remotes without a MENU key.
                if (isOk(code)) {
                    when {
                        event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 1 && !c.hasError -> {
                            okLongPressed = true
                            menuOpen = true
                        }
                        event.type == KeyEventType.KeyUp && !okLongPressed -> when {
                            c.hasError -> c.retryNow()
                            else -> {
                                listStart = c.index
                                listOpen = true
                            }
                        }
                    }
                    return@onKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                digitFor(code)?.let { d ->
                    if (digits.length < 4) digits += d
                    return@onKeyEvent true
                }
                when (code) {
                    // Dedicated channel keys zap; the D-pad never changes channel by itself.
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> {
                        c.zap(+1)
                        bannerKey++
                    }
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> {
                        c.zap(-1)
                        bannerKey++
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        val step = if (code == AndroidKeyEvent.KEYCODE_DPAD_UP) -1 else 1
                        listStart = (c.index + step).coerceIn(0, c.list.lastIndex)
                        listOpen = true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                        if (channel.hasTimeshift) c.seekBy(-SEEK_STEP) else notice = R.string.replay_unavailable
                        bannerKey++
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        if (c.fromTime != null) c.seekBy(SEEK_STEP)
                        bannerKey++
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE -> {
                        c.togglePause()
                        bannerKey++
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> if (!c.paused) {
                        c.togglePause()
                        bannerKey++
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> if (c.paused) {
                        c.togglePause()
                        bannerKey++
                    }
                    AndroidKeyEvent.KEYCODE_GUIDE -> {
                        guideStart = c.index
                        guideOpen = true
                    }
                    AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_CAPTIONS, AndroidKeyEvent.KEYCODE_SETTINGS -> menuOpen = true
                    AndroidKeyEvent.KEYCODE_INFO -> bannerKey++
                    else -> return@onKeyEvent false
                }
                true
            },
    ) {
        VideoSurface(c.player, c.aspect, c.pictureMode)

        val bannerShown = (showBanner || c.paused || c.pendingSeek != 0L || notice != null) && !c.hasError && !overlayOpen
        Subtitles(c.cues, Modifier.align(Alignment.BottomCenter).padding(bottom = if (bannerShown) 200.dp else 56.dp))

        PlayerStatus(c)

        if (c.pendingSeek != 0L && !overlayOpen) {
            val target = (c.position() + c.pendingSeek) / 10_000
            key(target) {
                Thumbnail(
                    url = { preview -> c.thumbnailUrl(channel, target * 10_000, preview) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = 196.dp).size(320.dp, 180.dp),
                )
            }
        }

        if (bannerShown) {
            InfoBar(
                graph = graph,
                channel = channel,
                replay = c.fromTime != null,
                paused = c.paused,
                pendingSeek = c.pendingSeek,
                notice = notice,
                position = c::position,
                info = c.streamInfo,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        } else if ((c.fromTime != null || c.paused) && !c.hasError && !overlayOpen) {
            // Never leave it ambiguous that we're behind live.
            BehindLivePill(position = c::position, paused = c.paused, modifier = Modifier.align(Alignment.TopEnd).padding(32.dp))
        }

        if (listOpen) {
            ChannelListOverlay(
                graph = graph,
                channels = c.list,
                playingIndex = c.index,
                startIndex = listStart,
                onPick = { i ->
                    listOpen = false
                    if (i != c.index || c.fromTime != null) c.play(i)
                    bannerKey++
                },
                onGuide = { i ->
                    listOpen = false
                    guideStart = i
                    guideOpen = true
                },
                onClose = { listOpen = false },
            )
        }

        if (digits.isNotEmpty()) {
            NumberEntry(digits, c.channelNamed(digits)?.name, Modifier.align(Alignment.TopEnd).padding(48.dp))
        }

        if (guideOpen) {
            Guide(
                graph = graph,
                channels = c.list,
                startIndex = guideStart,
                translucent = true,
                onPick = { i, from ->
                    c.play(i, from)
                    guideOpen = false
                    bannerKey++
                },
                onClose = { guideOpen = false },
                thumbnail = c::thumbnailUrl,
            )
        }

        if (menuOpen) {
            PlayerMenu(
                tracks = c.tracks,
                isFavourite = c.isFavourite,
                pictureMode = c.pictureMode,
                onToggleFavourite = c::toggleFavourite,
                onAudio = c::selectAudio,
                onText = c::selectText,
                onPicture = c::setPicture,
                onClose = { menuOpen = false },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        IdleQuestion(c)
    }
}

/** Error card or the quiet "Connecting…" pill, centred; shared by both player layouts. */
@Composable
internal fun BoxScope.PlayerStatus(c: PlayerController) {
    c.error?.let { ErrorCard(c.channel, it, Modifier.align(Alignment.Center)) }
    if (c.reconnecting && c.error == null) {
        Txt(
            stringResource(R.string.reconnecting),
            Type.Label,
            Modifier.align(Alignment.Center).background(Palette.Scrim, RoundedCornerShape(50)).padding(horizontal = 24.dp, vertical = 12.dp),
            color = Palette.Text,
        )
    }
}

/** "Still watching?" countdown; shared by both player layouts. */
@Composable
internal fun BoxScope.IdleQuestion(c: PlayerController) {
    if (c.idleCountdown > 0) {
        Column(
            Modifier.align(Alignment.Center).widthIn(max = 560.dp).background(Palette.Surface, RoundedCornerShape(20.dp)).padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Txt(stringResource(R.string.idle_title), Type.Title)
            Spacer(Modifier.height(10.dp))
            Txt(stringResource(R.string.idle_body, c.idleCountdown), Type.BodyDim)
        }
    }
}

data class StreamInfo(val format: Format? = null, val decoder: String? = null, val dropped: Int = 0) {
    fun describe(): String = listOfNotNull(
        format?.takeIf { it.width > 0 }?.let { "${it.width}×${it.height}" },
        format?.codecs ?: format?.sampleMimeType,
        format?.frameRate?.takeIf { it > 0 }?.let { "%.0f fps".format(it) },
        format?.bitrate?.takeIf { it > 0 }?.let { "%.1f Mbps".format(it / 1_000_000f) },
        decoder,
        dropped.takeIf { it > 0 }?.let { "$it dropped" },
    ).joinToString("  ·  ")
}

internal val secondsClock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

internal fun isOk(code: Int) = code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER

/** "12:30" or "1:02:03". */
internal fun formatSpan(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
}

/**
 * Bottom bar. Live: channel, now/next, what the keys do. Timeshift (replay, paused or seeking):
 * a transport bar showing the programme, what's available up to live, where the playhead is
 * (or will land after a pending seek) and how far behind live that is.
 */
@Composable
private fun InfoBar(
    graph: AppGraph,
    channel: Channel,
    replay: Boolean,
    paused: Boolean,
    pendingSeek: Long,
    @StringRes notice: Int?,
    position: () -> Long,
    info: StreamInfo?,
    modifier: Modifier,
) {
    val revision by graph.epg.revision.collectAsState()
    val minute = minuteClock()
    val timeshift = replay || paused || pendingSeek != 0L
    // Sub-second ticking only while the transport bar is showing.
    val pos by produceState(position(), timeshift) {
        while (timeshift) {
            value = position()
            delay(500)
        }
        value = position()
    }
    val target = pos + pendingSeek
    val behind = System.currentTimeMillis() - target

    Column(modifier.fillMaxWidth().background(Palette.Scrim).padding(horizontal = 56.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(width = 112.dp, height = 63.dp).background(Palette.SurfaceHigh, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logo != null) AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.padding(8.dp).fillMaxSize())
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(listOfNotNull(channel.number, channel.name).joinToString("  "), Type.Label, color = Palette.Text)
                    if (channel.isUhd) {
                        Spacer(Modifier.width(8.dp))
                        Badge("4K")
                    }
                    Spacer(Modifier.width(8.dp))
                    when {
                        timeshift -> ReplayPill(stringResource(R.string.replay_behind, formatSpan(behind)), filled = true)
                        else -> Badge(stringResource(R.string.live), color = Palette.Danger, textColor = Palette.Text)
                    }
                    if (!timeshift && channel.hasTimeshift) {
                        Spacer(Modifier.width(8.dp))
                        ReplayPill(stringResource(R.string.replay_badge))
                    }
                    if (paused) {
                        Spacer(Modifier.width(8.dp))
                        Badge(stringResource(R.string.paused), color = Palette.Text, textColor = Palette.OnAccent)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (timeshift) {
                    TransportBar(graph, channel, revision, target)
                } else {
                    val (now, next) = remember(channel.id, revision, minute) { graph.epg.nowNext(channel.id, minute) }
                    NowNext(now, next, minute)
                }
            }
            if (info != null) {
                Spacer(Modifier.width(24.dp))
                Txt(info.describe(), Type.Label, Modifier.widthIn(max = 240.dp), color = Palette.TextFaint)
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.padding(start = 136.dp), verticalAlignment = Alignment.CenterVertically) {
            if (notice != null) {
                Txt(stringResource(notice), Type.Label, color = Palette.Danger)
                Spacer(Modifier.width(24.dp))
            }
            val hints = if (timeshift) {
                listOf(R.string.hint_seek, R.string.hint_pause, R.string.hint_back_live)
            } else {
                listOfNotNull(
                    R.string.hint_rewind.takeIf { channel.hasTimeshift },
                    R.string.hint_channels, R.string.hint_guide, R.string.hint_menu,
                )
            }
            hints.forEach {
                Txt(stringResource(it), Type.Label, color = Palette.TextFaint)
                Spacer(Modifier.width(22.dp))
            }
        }
    }
}

@Composable
private fun TransportBar(graph: AppGraph, channel: Channel, revision: Int, target: Long) {
    val now = System.currentTimeMillis()
    val p = remember(channel.id, revision, target / 60_000) { graph.epg.programmeAt(channel.id, target) }
    // Programme span, or a 1 h window ending at live when there's no guide data.
    val from = p?.startTime ?: (now - 60 * 60_000)
    val to = p?.endTime ?: now
    val span = (to - from).coerceAtLeast(1).toFloat()
    val played = ((target - from) / span).coerceIn(0f, 1f)
    val available = ((now - from) / span).coerceIn(0f, 1f)

    if (p != null) {
        Txt(p.name, Type.Title, maxLines = 1)
        Spacer(Modifier.height(10.dp))
    }
    // One canvas so track, available range, played part and playhead share exact coordinates.
    Canvas(Modifier.width(640.dp).height(16.dp)) {
        val h = 4.dp.toPx()
        val y = size.height / 2
        val r = CornerRadius(h / 2)
        drawRoundRect(Palette.SurfaceHigh, Offset(0f, y - h / 2), Size(size.width, h), r)
        drawRoundRect(Palette.TextFaint, Offset(0f, y - h / 2), Size(size.width * available, h), r)
        drawRoundRect(Palette.Accent, Offset(0f, y - h / 2), Size(size.width * played, h), r)
        drawCircle(Palette.Accent, 7.dp.toPx(), Offset(size.width * played, y))
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.width(640.dp)) {
        Txt(secondsClock.format(Instant.ofEpochMilli(target)), Type.Label, color = Palette.Text)
        Spacer(Modifier.weight(1f))
        Txt("${hhmm(from)} – ${hhmm(to)}", Type.Label)
    }
}

/** Shown in the corner while behind live and the bar is hidden. */
@Composable
private fun BehindLivePill(position: () -> Long, paused: Boolean, modifier: Modifier) {
    val pos by produceState(position()) {
        while (true) {
            value = position()
            delay(1_000)
        }
    }
    ReplayPill(
        if (paused) stringResource(R.string.paused)
        else stringResource(R.string.replay_behind, formatSpan(System.currentTimeMillis() - pos)),
        modifier.background(Palette.Scrim, RoundedCornerShape(50)),
    )
}

@Composable
private fun ErrorCard(channel: Channel, error: PlayError, modifier: Modifier) {
    Column(
        modifier.widthIn(max = 620.dp).background(Palette.Surface, RoundedCornerShape(20.dp)).padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Txt(listOfNotNull(channel.number, channel.name).joinToString("  "), Type.Label)
        Spacer(Modifier.height(12.dp))
        Txt(error.serverMessage ?: stringResource(error.fallback), Type.Title)
        error.detail?.let {
            Spacer(Modifier.height(6.dp))
            Txt(it, Type.Label, color = Palette.TextFaint)
        }
        Spacer(Modifier.height(20.dp))
        Txt(stringResource(R.string.press_ok_retry), Type.Label, color = Palette.Accent)
    }
}
