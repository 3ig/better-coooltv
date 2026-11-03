package dev.bettercoool.tv.ui.player.classic

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.CompositingStrategy
import kotlinx.coroutines.launch
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.media3.common.C
import androidx.media3.common.Tracks
import dev.bettercoool.tv.AppGraph
import dev.bettercoool.tv.R
import dev.bettercoool.tv.data.Channel
import dev.bettercoool.tv.data.EpgStore
import dev.bettercoool.tv.ui.common.ReplayIcon
import dev.bettercoool.tv.ui.common.digitFor
import dev.bettercoool.tv.ui.common.minuteClock
import dev.bettercoool.tv.ui.guide.Guide
import dev.bettercoool.tv.ui.player.ChannelListOverlay
import dev.bettercoool.tv.ui.player.PlayerController
import dev.bettercoool.tv.ui.player.PlayerStatus
import dev.bettercoool.tv.ui.player.Subtitles
import dev.bettercoool.tv.ui.player.Thumbnail
import dev.bettercoool.tv.ui.player.VideoSurface
import dev.bettercoool.tv.ui.player.isOk
import dev.bettercoool.tv.ui.player.rememberPlayerController
import dev.bettercoool.tv.ui.player.secondsClock
import dev.bettercoool.tv.ui.player.trackLabel
import dev.bettercoool.tv.ui.theme.Palette
import dev.bettercoool.tv.ui.theme.Txt
import dev.bettercoool.tv.ui.theme.requestFocusSoon
import kotlinx.coroutines.delay
import java.time.Instant

private const val OVERLAY_TIMEOUT = 7_000L
private const val BANNER_TIMEOUT = 5_000L
private const val NUMBER_TIMEOUT = 5_000L
/** How far (px) the programme row nudges when it changes; the cards differ in width, so it can't scroll exactly. */
private const val EPG_NUDGE = 80f
private const val EPG_BACK_STEP = 6 * 60 * 60_000L
/** Past programmes reachable in the row; the store keeps ~48 h in memory. Older ones are in the guide. */
private const val EPG_BACK_MAX = 36 * 60 * 60_000L

/** Overlay layers, top to bottom; Up/Down moves between them. */
private enum class Layer { Settings, Progress, Channels, Epg }

private enum class Banner { Current, Swap }

/**
 * The old Coool TV player's layout and remote keys, for people used to it (Settings → Player).
 * Same playback as [dev.bettercoool.tv.ui.player.PlayerScreen]; only the screen and keys differ.
 *
 * Bare player: OK progress bar · Down channel strip · Up swap-back banner · CH± zap · Left/Right seek
 * (10 s, faster when held) · long OK guide · digits number box · Back leaves replay, then exits.
 * Overlay: Up/Down between top bar, progress bar, channel strip and programme row · Back closes.
 */
@Composable
fun ClassicPlayerScreen(graph: AppGraph, channels: List<Channel>, startIndex: Int, startFrom: Long?, onExit: () -> Unit) {
    val c = rememberPlayerController(graph, channels, startIndex, startFrom)
    var layer by remember { mutableStateOf<Layer?>(null) }
    var banner by remember { mutableStateOf<Banner?>(Banner.Current) }
    var bannerKey by remember { mutableIntStateOf(0) }
    var lastKey by remember { mutableLongStateOf(0L) }
    var strip by remember { mutableIntStateOf(startIndex) }
    var epgAt by remember { mutableStateOf<Long?>(null) } // start of the focused programme; null = the one on now
    var epgBack by remember { mutableLongStateOf(EPG_BACK_STEP) } // how far back the programme row has loaded
    var settingsFocus by remember { mutableIntStateOf(1) } // subtitles first, as in the old app
    var digits by remember { mutableStateOf("") }
    var guideStart by remember { mutableStateOf<Int?>(null) }
    var listOpen by remember { mutableStateOf(false) }
    var okLongPressed by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    // Slide offsets: the strip in tile slots, the programme row in px; both settle to 0.
    val stripSlide = remember { Animatable(0f) }
    val epgSlide = remember { Animatable(0f) }
    // What to keep drawing while the overlay or banner fades out.
    var lastLayer by remember { mutableStateOf(Layer.Progress) }
    var lastBanner by remember { mutableStateOf(Banner.Current) }
    val revision by graph.epg.revision.collectAsState()
    val clock = minuteClock()
    val channel = c.channel
    val context = LocalContext.current

    LaunchedEffect(layer, lastKey) {
        if (layer == null) return@LaunchedEffect
        delay(OVERLAY_TIMEOUT)
        if (c.pendingSeek == 0L) layer = null
    }
    LaunchedEffect(banner, bannerKey) {
        if (banner == null) return@LaunchedEffect
        delay(BANNER_TIMEOUT)
        banner = null
    }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(NUMBER_TIMEOUT)
        if (c.playNumber(digits)) banner = Banner.Current
        bannerKey++
        digits = ""
    }
    val modal = guideStart != null || listOpen
    LaunchedEffect(modal) { if (!modal) focus.requestFocusSoon() }

    fun openLayer(l: Layer) {
        if (layer == null) {
            strip = c.index
            epgAt = null
        }
        banner = null
        layer = l
    }

    /**
     * Moves content [d] steps with a 200 ms slide, like the old app. While a key is held the slides
     * would pile up faster than these boxes can draw them, so repeats jump straight there.
     */
    fun slide(anim: Animatable<Float, *>, by: Float, repeat: Int) {
        scope.launch {
            anim.snapTo(if (repeat > 0) 0f else anim.value + by)
            if (repeat == 0) anim.animateTo(0f, tween(200))
        }
    }

    fun showBanner(b: Banner) {
        banner = b
        bannerKey++
    }

    fun seek(forward: Boolean, repeat: Int) {
        openLayer(Layer.Progress)
        if (!channel.hasTimeshift) return
        if (forward && c.fromTime == null && c.pendingSeek >= 0) return // already live
        // Held keys speed up like the old app: 10 s, then 30 s after 10 repeats, 60 s after 20.
        val step = when {
            repeat >= 20 -> 60_000L
            repeat >= 10 -> 30_000L
            else -> 10_000L
        }
        c.seekBy(if (forward) step else -step)
    }

    fun togglePause() {
        if (!channel.hasTimeshift) return
        openLayer(Layer.Progress)
        c.togglePause()
    }

    val programmes = remember(c.list, strip, revision) { c.list.getOrNull(strip)?.let { graph.epg.programmes(it.id) }.orEmpty() }
    // Focus is kept by start time: loading earlier programmes shifts every index.
    val epgIndex = epgAt?.let { at -> programmes.indexOfFirst { it.startTime == at }.takeIf { it >= 0 } }
        ?: programmes.indexOfLast { it.startTime <= clock }.coerceAtLeast(0)
    // The guide store only keeps what's around now; earlier programmes load as the row is scrolled back.
    LaunchedEffect(layer != null, epgBack) {
        if (layer != null) {
            val now = System.currentTimeMillis()
            graph.epg.ensureRange(now - epgBack, now + 3 * EpgStore.BLOCK)
        }
    }

    /** OK / long OK / arrows while the overlay is up. Returns false to let the bare-player mapping run. */
    fun overlayKey(code: Int, long: Boolean, repeat: Int): Boolean {
        val l = layer ?: return false
        when (code) {
            AndroidKeyEvent.KEYCODE_DPAD_UP -> layer = Layer.entries.getOrElse(l.ordinal - 1) { l }
            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> layer = Layer.entries.getOrElse(l.ordinal + 1) { l }.also {
                if (it == Layer.Epg && l != Layer.Epg) epgAt = null
            }
            AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                val d = if (code == AndroidKeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                when (l) {
                    Layer.Settings -> settingsFocus = (settingsFocus + d).coerceIn(0, 2)
                    Layer.Progress -> seek(d > 0, repeat)
                    Layer.Channels -> {
                        strip = (strip + d).mod(c.list.size)
                        epgAt = null
                        slide(stripSlide, d.toFloat(), repeat)
                        slide(epgSlide, d * EPG_NUDGE, repeat)
                    }
                    Layer.Epg -> {
                        val i = (epgIndex + d).coerceIn(0, (programmes.size - 1).coerceAtLeast(0))
                        if (i != epgIndex) slide(epgSlide, d * EPG_NUDGE, repeat)
                        epgAt = programmes.getOrNull(i)?.startTime
                        if (d < 0 && i <= 1 && epgBack < EPG_BACK_MAX) epgBack += EPG_BACK_STEP
                    }
                }
            }
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                seek(code == AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD, repeat)
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> when (l) {
                Layer.Settings -> when (settingsFocus) {
                    0 -> nextAudio(c)
                    1 -> nextSubtitle(c)
                    else -> c.setPicture((c.pictureMode + 1) % 3)
                }
                Layer.Progress -> togglePause()
                Layer.Channels -> if (long) {
                    layer = null
                    listOpen = true
                } else if (c.list[strip].playable && (strip != c.index || c.fromTime != null)) {
                    c.play(strip)
                }
                Layer.Epg -> {
                    val p = programmes.getOrNull(epgIndex)
                    val ch = c.list[strip]
                    when {
                        long -> {
                            layer = null
                            guideStart = strip
                        }
                        p == null || !ch.playable || p.startTime > System.currentTimeMillis() -> Unit
                        ch.hasTimeshift -> c.play(strip, p.startTime)
                        p.endTime > System.currentTimeMillis() -> c.play(strip)
                    }
                }
            }
            AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> {
                c.zap(if (code == AndroidKeyEvent.KEYCODE_CHANNEL_UP) 1 else -1)
                strip = c.index
                epgAt = null
                layer = Layer.Channels
            }
            else -> return false
        }
        return true
    }

    BackHandler(enabled = !modal) {
        when {
            digits.isNotEmpty() -> digits = ""
            layer != null -> layer = null
            banner != null -> banner = null
            c.fromTime != null || c.paused -> c.goLive()
            else -> onExit()
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) lastKey = System.nanoTime()
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
                if (modal) return@onKeyEvent false
                val code = event.nativeKeyEvent.keyCode
                val repeat = event.nativeKeyEvent.repeatCount
                // OK acts on release so a hold can become a long press instead.
                if (isOk(code)) {
                    when {
                        event.type == KeyEventType.KeyDown && repeat == 1 && !c.hasError -> {
                            okLongPressed = true
                            if (!overlayKey(code, long = true, repeat)) {
                                banner = null
                                guideStart = c.index
                            }
                        }
                        event.type == KeyEventType.KeyUp && !okLongPressed -> when {
                            c.hasError -> c.retryNow()
                            digits.isNotEmpty() -> {
                                if (c.playNumber(digits)) showBanner(Banner.Current)
                                digits = ""
                            }
                            overlayKey(code, long = false, 0) -> Unit
                            banner == Banner.Swap && c.previous != null -> {
                                c.swapBack()
                                showBanner(Banner.Current)
                            }
                            else -> openLayer(Layer.Progress)
                        }
                    }
                    return@onKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                digitFor(code)?.let { d ->
                    layer = null
                    if (digits.length < 3) digits += d
                    return@onKeyEvent true
                }
                if (overlayKey(code, long = false, repeat)) return@onKeyEvent true
                when (code) {
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> openLayer(Layer.Channels)
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> showBanner(if (c.previous != null) Banner.Swap else Banner.Current)
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> {
                        c.zap(+1)
                        showBanner(Banner.Current)
                    }
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> {
                        c.zap(-1)
                        showBanner(Banner.Current)
                    }
                    AndroidKeyEvent.KEYCODE_LAST_CHANNEL -> {
                        c.swapBack()
                        showBanner(Banner.Current)
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> seek(false, repeat)
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(true, repeat)
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE -> togglePause()
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> if (!c.paused) togglePause()
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> if (c.paused) togglePause()
                    AndroidKeyEvent.KEYCODE_GUIDE -> {
                        layer = null
                        banner = null
                        guideStart = c.index
                    }
                    // Not in the old app; kept so audio and subtitles are one key away on remotes that have it.
                    AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_CAPTIONS, AndroidKeyEvent.KEYCODE_SETTINGS -> openLayer(Layer.Settings)
                    AndroidKeyEvent.KEYCODE_INFO -> showBanner(Banner.Current)
                    else -> return@onKeyEvent false
                }
                true
            },
    ) {
        val px = Px(maxHeight / 1080f, LocalDensity.current)
        VideoSurface(c.player, c.aspect, c.pictureMode)
        Subtitles(c.cues, Modifier.align(Alignment.BottomCenter).padding(bottom = px(if (layer != null) 300 else 80)))
        PlayerStatus(c)

        val behindLive = (c.fromTime != null || c.paused) && !c.hasError
        if (behindLive && layer == null && banner == null) TimeshiftMark(px, Modifier.align(Alignment.TopEnd))

        LaunchedEffect(layer) { layer?.let { lastLayer = it } }
        LaunchedEffect(banner) { banner?.let { lastBanner = it } }
        // Overlay and banner fade and slide in and out (200 ms, as in the old app). Only alpha and
        // translation change per frame: no relayout, and ModulateAlpha avoids an offscreen buffer.
        // The animated values are read only in draw lambdas; composition just sees on/off.
        val overlayAnim = animateFloatAsState(if (layer != null) 1f else 0f, tween(200), label = "overlay")
        val bannerAnim = animateFloatAsState(if (banner != null) 1f else 0f, tween(200), label = "banner")
        val overlayShown by remember { derivedStateOf { overlayAnim.value > 0f } }
        val bannerShown by remember { derivedStateOf { bannerAnim.value > 0f } }
        val overlayIn = { overlayAnim.value }
        val bannerIn = { bannerAnim.value }

        if (overlayShown) {
            val l = layer ?: lastLayer
            // One Down shows the whole block (timeline, channel strip, programme row) like the old app;
            // on the progress bar it slides down so only the top of the channel strip peeks out.
            val shift = animateFloatAsState(
                when (l) {
                    Layer.Settings, Layer.Progress -> (STRIP_HEIGHT - STRIP_PEEK + EPG_HEIGHT).toFloat()
                    Layer.Channels, Layer.Epg -> 0f
                },
                tween(200),
                label = "layer",
            )
            val gradient = overlayGradient(lists = l == Layer.Channels || l == Layer.Epg)
            Box(Modifier.fillMaxSize().drawBehind { drawRect(gradient, alpha = overlayIn()) })
            if (l == Layer.Settings) {
                Row(
                    Modifier.fillMaxWidth().height(px(144)).fade(overlayIn).background(TopFade).padding(top = px(20)),
                    horizontalArrangement = Arrangement.spacedBy(px(40), Alignment.CenterHorizontally),
                ) {
                    val (audio, subtitle) = trackNames(c.tracks)
                    val pictures = listOf(R.string.classic_fit, R.string.classic_fill, R.string.classic_stretch)
                    RoundButton(px, "♪", stringResource(R.string.classic_audio, audio ?: "—"), settingsFocus == 0)
                    RoundButton(px, "CC", stringResource(R.string.classic_subtitles, subtitle ?: context.getString(R.string.menu_off)), settingsFocus == 1)
                    RoundButton(px, "⤢", stringResource(R.string.classic_resize, stringResource(pictures[c.pictureMode])), settingsFocus == 2)
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().graphicsLayer {
                    translationY = px(shift.value).toPx() + (1 - overlayIn()) * px(60).toPx()
                    alpha = overlayIn()
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
            ) {
                val pos by produceState(c.position(), c.channel.id, c.fromTime, c.paused) {
                    while (true) {
                        value = c.position()
                        delay(500)
                    }
                }
                val target = pos + c.pendingSeek
                val p = remember(channel.id, revision, target / 60_000) { graph.epg.programmeAt(channel.id, target) }
                val now = System.currentTimeMillis()
                // The playhead and its label belong to the progress bar; the lists show the plain timeline.
                val label = when {
                    c.pendingSeek != 0L || (c.fromTime != null && !c.paused) -> secondsClock.format(Instant.ofEpochMilli(target))
                    c.paused -> "❚❚"
                    else -> stringResource(R.string.live)
                }.takeIf { l == Layer.Progress || l == Layer.Settings || c.pendingSeek != 0L }
                val from = p?.startTime ?: (now - 60 * 60_000)
                val to = p?.endTime ?: now
                Box {
                    ProgressLayer(px, p?.name ?: channel.name, from, to, target, label)
                    if (c.pendingSeek != 0L) {
                        // DVR still above the playhead.
                        val played = ((target - from).toFloat() / (to - from).coerceAtLeast(1)).coerceIn(0f, 1f)
                        key(target / 10_000) {
                            Thumbnail(
                                url = { preview -> c.thumbnailUrl(channel, target / 10_000 * 10_000, preview) },
                                modifier = Modifier.align(Alignment.TopCenter).offset(x = px(BAR_WIDTH * played - BAR_WIDTH / 2), y = -px(200))
                                    .size(px(320), px(180)),
                            )
                        }
                    }
                }
                val tileFocus = when (l) {
                    Layer.Channels -> TileFocus.Focused
                    Layer.Epg -> TileFocus.Selected
                    else -> TileFocus.None
                }
                ChannelStrip(px, c.list, focused = strip, playing = c.index, focus = tileFocus, slide = { stripSlide.value })
                EpgRow(
                    px, programmes, epgIndex, clock, active = l == Layer.Epg,
                    modifier = Modifier.graphicsLayer { translationX = px(epgSlide.value).toPx() },
                )
            }
        }

        if (bannerShown) {
            val b = banner ?: lastBanner
            val shown = if (b == Banner.Swap) c.previous ?: channel else channel
            val now = remember(shown.id, revision, clock) { graph.epg.nowNext(shown.id, clock).first }
            ZapBanner(
                px, shown, now, playing = shown.id == channel.id, swap = b == Banner.Swap,
                modifier = Modifier.align(Alignment.TopCenter).graphicsLayer {
                    translationY = -(1 - bannerIn()) * px(40).toPx()
                    alpha = bannerIn()
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                },
            )
        }

        if (digits.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = px(100), end = px(30))
                    .background(Color.Black, RoundedCornerShape(px(8))).padding(horizontal = px(20), vertical = px(14)),
            ) {
                Txt(digits, px.text(48, FontWeight.SemiBold), maxLines = 1)
            }
        }

        if (listOpen) {
            ChannelListOverlay(
                graph = graph,
                channels = c.list,
                playingIndex = c.index,
                startIndex = strip,
                onPick = { i ->
                    listOpen = false
                    if (i != c.index || c.fromTime != null) c.play(i)
                    showBanner(Banner.Current)
                },
                onGuide = { i ->
                    listOpen = false
                    guideStart = i
                },
                onClose = { listOpen = false },
            )
        }

        guideStart?.let { start ->
            Guide(
                graph = graph,
                channels = c.list,
                startIndex = start,
                translucent = true,
                onPick = { i, from ->
                    c.play(i, from)
                    guideStart = null
                    showBanner(Banner.Current)
                },
                onClose = { guideStart = null },
                thumbnail = c::thumbnailUrl,
            )
        }
    }
}

/** Replay mark in the top-right corner while behind live; nudged by a pixel every 2 min against burn-in. */
@Composable
private fun TimeshiftMark(px: Px, modifier: Modifier) {
    val step by produceState(0) {
        while (true) {
            delay(120_000)
            value = (value + 1) % 9
        }
    }
    ReplayIcon(px(48), Palette.Accent, modifier.padding(top = px(40 + step / 3), end = px(40 + step % 3)))
}

private fun audioOptions(tracks: Tracks) = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }

private fun textOptions(tracks: Tracks) = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }

/** Names of the selected audio and subtitle tracks (null = none / off). */
@Composable
private fun trackNames(tracks: Tracks): Pair<String?, String?> {
    val ctx = LocalContext.current
    val name: (Int) -> String = { ctx.getString(R.string.menu_track, it) }
    val audio = audioOptions(tracks).withIndex().firstOrNull { (_, o) -> o.first.isTrackSelected(o.second) }
        ?.let { (n, o) -> trackLabel(o.first.getTrackFormat(o.second), n + 1, name) }
    val text = textOptions(tracks).withIndex().firstOrNull { (_, o) -> o.first.isTrackSelected(o.second) }
        ?.let { (n, o) -> trackLabel(o.first.getTrackFormat(o.second), n + 1, name) }
    return audio to text
}

private fun nextAudio(c: PlayerController) {
    val options = audioOptions(c.tracks)
    if (options.size < 2) return
    val current = options.indexOfFirst { (g, i) -> g.isTrackSelected(i) }
    val (g, i) = options[(current + 1) % options.size]
    c.selectAudio(g, i)
}

/** Off → first subtitle → … → last → off. */
private fun nextSubtitle(c: PlayerController) {
    val options = textOptions(c.tracks)
    if (options.isEmpty()) return
    val next = options.indexOfFirst { (g, i) -> g.isTrackSelected(i) } + 1
    if (next >= options.size) c.selectText(null, 0) else options[next].let { (g, i) -> c.selectText(g, i) }
}

/** Alpha only, applied without an offscreen buffer. */
private fun Modifier.fade(alpha: () -> Float): Modifier = graphicsLayer {
    this.alpha = alpha()
    compositingStrategy = CompositingStrategy.ModulateAlpha
}
