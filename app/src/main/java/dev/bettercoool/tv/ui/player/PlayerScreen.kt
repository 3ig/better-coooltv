package dev.bettercoool.tv.ui.player

import android.os.SystemClock
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import coil.compose.AsyncImage
import dev.bettercoool.tv.AppGraph
import dev.bettercoool.tv.R
import dev.bettercoool.tv.data.ApiException
import dev.bettercoool.tv.data.Capabilities
import dev.bettercoool.tv.data.Channel
import dev.bettercoool.tv.data.PlaybackSession
import dev.bettercoool.tv.ui.common.Badge
import dev.bettercoool.tv.ui.common.ReplayPill
import dev.bettercoool.tv.ui.common.NowNext
import dev.bettercoool.tv.ui.common.NumberEntry
import dev.bettercoool.tv.ui.common.ProgressBar
import dev.bettercoool.tv.ui.common.digitFor
import dev.bettercoool.tv.ui.common.hhmm
import dev.bettercoool.tv.ui.common.minuteClock
import dev.bettercoool.tv.ui.guide.Guide
import dev.bettercoool.tv.ui.theme.Palette
import dev.bettercoool.tv.ui.theme.requestFocusSoon
import dev.bettercoool.tv.ui.theme.Txt
import dev.bettercoool.tv.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val SEEK_STEP = 30_000L
private const val LIVE_LATENCY = 15_000L // typical HLS distance from real time
private const val ARCHIVE_DEPTH = 7L * 24 * 60 * 60_000L
private const val IDLE_AFTER = 4L * 60 * 60_000L // same as the original

/** What went wrong, resolved to text at display time. Server messages are already localized. */
private data class PlayError(@StringRes val fallback: Int, val serverMessage: String? = null, val detail: String? = null)

/**
 * Programme time of what's on screen during catch-up: start time + time actually spent playing
 * (buffering and pauses don't advance it). Timeshift playlists don't expose a usable timeline.
 */
private class CatchupClock {
    private var base = 0L
    private var accumulated = 0L
    private var segmentStart = -1L

    fun reset(base: Long) {
        this.base = base
        accumulated = 0
        segmentStart = -1
    }

    fun playing(isPlaying: Boolean) {
        val t = SystemClock.elapsedRealtime()
        if (isPlaying && segmentStart < 0) {
            segmentStart = t
        } else if (!isPlaying && segmentStart >= 0) {
            accumulated += t - segmentStart
            segmentStart = -1
        }
    }

    fun position(): Long = base + accumulated + if (segmentStart >= 0) SystemClock.elapsedRealtime() - segmentStart else 0
}

/**
 * Full-screen TV. Video renders on a SurfaceView (hardware overlay plane: required for smooth 4K
 * and HDR on weak boxes). Overlays (banner, guide) are only composed while visible.
 *
 * Remote: Up/Down zap · digits jump · OK banner→guide · Left/Right ∓30 s (timeshift channels,
 * also rewinds live) · Play/Pause pauses live TV and resumes from the archive · Back leaves replay first.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(graph: AppGraph, channels: List<Channel>, startIndex: Int, startFrom: Long?, onExit: () -> Unit) {
    val context = LocalContext.current
    val session = remember { PlaybackSession(graph.api, graph.device, graph.appScope) }
    val clock = remember { CatchupClock() }
    val player = remember {
        // Bounded by bytes as well as time: a 20 Mbps 4K stream must not eat the heap on 1–2 GB boxes.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 1_000, 2_500)
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        // Default track selector sizes the viewport from the physical display mode,
        // so 2160p variants are eligible on 4K outputs even when the UI is 1080p.
        ExoPlayer.Builder(context).setLoadControl(loadControl).build().apply { playWhenReady = true }
    }
    // Number zapping can leave a category list, so the playing list can change.
    var list by remember { mutableStateOf(channels) }
    var index by remember { mutableIntStateOf(startIndex) }
    var fromTime by remember { mutableStateOf(startFrom) } // null = live
    var paused by remember { mutableStateOf(false) }
    var pausedAt by remember { mutableLongStateOf(0L) }
    var pendingSeek by remember { mutableLongStateOf(0L) }
    var started by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var bannerKey by remember { mutableIntStateOf(0) }
    var showBanner by remember { mutableStateOf(true) }
    var guideOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<PlayError?>(null) }
    var streamInfo by remember { mutableStateOf<StreamInfo?>(null) }
    var digits by remember { mutableStateOf("") }
    var aspect by remember { mutableStateOf(0f) }
    var cues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var menuOpen by remember { mutableStateOf(false) }
    var listOpen by remember { mutableStateOf(false) }
    var listStart by remember { mutableIntStateOf(0) }
    var guideStart by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf<Int?>(null) } // transient one-line hint in the banner
    var okLongPressed by remember { mutableStateOf(false) }
    var favTick by remember { mutableIntStateOf(0) }
    var pictureMode by remember { mutableIntStateOf(graph.session.pictureMode) }
    var lastInput by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var idleCountdown by remember { mutableIntStateOf(0) } // > 0 while asking "still watching?"
    val profileId = graph.session.currentProfile
    val focus = remember { FocusRequester() }
    val channel = list[index]

    /** Programme time currently on screen. */
    fun position(): Long = when {
        paused -> pausedAt
        fromTime != null -> clock.position()
        // ExoPlayer's live offset depends on the stream's clock matching the TV's; on these streams it can be
        // off by tens of seconds (even negative), so only trust plausible values and assume typical HLS latency otherwise.
        else -> System.currentTimeMillis() - player.currentLiveOffset.let { if (it in 3_000L..90_000L) it else LIVE_LATENCY }
    }

    // Keeps TV screensavers (e.g. TCL's) away only while video is actually playing.
    val view = LocalView.current
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                clock.playing(isPlaying)
                view.keepScreenOn = isPlaying
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height
            }

            override fun onCues(cueGroup: CueGroup) {
                cues = cueGroup.cues
            }

            override fun onTracksChanged(t: Tracks) {
                tracks = t
            }

            override fun onPlayerError(e: PlaybackException) {
                val current = list[index]
                error = if (current.isUhd && !Capabilities.uhdHevcMain10) {
                    PlayError(R.string.err_uhd)
                } else {
                    PlayError(R.string.err_playback, detail = e.errorCodeName)
                }
                session.end()
            }
        }
        val analytics = object : AnalyticsListener {
            override fun onVideoInputFormatChanged(t: AnalyticsListener.EventTime, format: Format, r: DecoderReuseEvaluation?) {
                streamInfo = (streamInfo ?: StreamInfo()).copy(format = format)
            }

            override fun onVideoDecoderInitialized(t: AnalyticsListener.EventTime, decoderName: String, initMs: Long, durMs: Long) {
                streamInfo = (streamInfo ?: StreamInfo()).copy(decoder = decoderName)
            }

            override fun onDroppedVideoFrames(t: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                streamInfo = (streamInfo ?: StreamInfo()).let { it.copy(dropped = it.dropped + droppedFrames) }
            }
        }
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        onDispose {
            player.removeListener(listener)
            view.keepScreenOn = false
            player.removeAnalyticsListener(analytics)
            player.release()
        }
    }

    LifecycleStartEffect(Unit) {
        started++
        onStopOrDispose {
            if (fromTime != null || paused) fromTime = position()
            fromTime?.let { graph.session.setResumeAt(channel.id, it) }
            player.stop()
            session.end()
        }
    }

    LaunchedEffect(channel.id, fromTime, started, retry) {
        if (started == 0) return@LaunchedEffect
        error = null
        streamInfo = null
        paused = false
        cues = emptyList()
        clock.reset(fromTime ?: 0)
        // Per-channel audio/subtitle language; subtitles stay off unless chosen.
        val (audioLang, textLang) = profileId?.let { graph.session.trackPrefs(it, channel.id) } ?: (null to null)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setPreferredAudioLanguage(audioLang)
            .setPreferredTextLanguage(textLang?.takeIf { it != "off" })
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, textLang == null || textLang == "off")
            .build()
        try {
            player.setMediaItem(MediaItem.fromUri(session.streamUrl(channel, fromTime)))
            player.prepare()
            player.play()
            graph.session.currentProfile?.let { graph.session.setLastChannel(it, channel.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            player.stop()
            error = when (e.errorCode) {
                "MAX_ACTIVE_SESSIONS_REACHED" -> PlayError(R.string.err_sessions, e.serverMessage)
                "SERVICE_INACTIVE", "SERVICE_TRIAL_EXPIRED" -> PlayError(R.string.err_service, e.serverMessage)
                else -> PlayError(R.string.err_playback, e.serverMessage, e.errorCode ?: "HTTP ${e.status}")
            }
        } catch (e: Exception) {
            player.stop()
            error = PlayError(R.string.err_playback, detail = e.message)
        }
    }

    // Banner auto-hide; stays up while paused or seeking.
    LaunchedEffect(bannerKey) {
        showBanner = true
        delay(5_000)
        showBanner = false
    }

    // Debounced seek: one reload per burst of Left/Right presses.
    LaunchedEffect(pendingSeek) {
        if (pendingSeek == 0L) return@LaunchedEffect
        delay(800)
        val now = System.currentTimeMillis()
        val target = (position() + pendingSeek).coerceAtLeast(now - ARCHIVE_DEPTH)
        pendingSeek = 0
        fromTime = if (target >= now - LIVE_LATENCY / 2) null else target
        bannerKey++
    }

    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1_500)
        val inList = list.indexOfFirst { it.number == digits && it.playable }
        if (inList >= 0) {
            index = inList
            fromTime = null
        } else {
            val full = graph.catalog.snapshot?.channels.orEmpty()
            val inFull = full.indexOfFirst { it.number == digits && it.playable }
            if (inFull >= 0) {
                list = full
                index = inFull
                fromTime = null
            }
        }
        bannerKey++
        digits = ""
    }

    // Remember where we are in a replay so the guide can offer "Resume from …".
    LaunchedEffect(channel.id, fromTime) {
        if (fromTime == null) return@LaunchedEffect
        while (true) {
            delay(15_000)
            if (!paused) graph.session.setResumeAt(channel.id, position())
        }
    }

    // "Still watching?" after 4 h without a key press; stops playback (and the server session) if unanswered.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            if (idleCountdown != 0 || error != null || SystemClock.elapsedRealtime() - lastInput < IDLE_AFTER) continue
            idleCountdown = 60
            while (idleCountdown > 1) {
                delay(1_000)
                if (idleCountdown == 0) break
                idleCountdown--
            }
            if (idleCountdown > 0) {
                idleCountdown = 0
                player.stop()
                session.end()
                error = PlayError(R.string.idle_stopped)
            }
        }
    }

    LaunchedEffect(Unit) { session.keepAlive() }
    LaunchedEffect(Unit) {
        while (true) {
            graph.epg.ensure()
            delay(10 * 60_000)
        }
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
            fromTime != null || paused -> {
                fromTime = null
                bannerKey++
            }
            else -> onExit()
        }
    }

    fun zap(step: Int) {
        var i = index
        repeat(list.size) {
            i = (i + step).mod(list.size)
            if (list[i].playable) {
                index = i
                fromTime = null
                bannerKey++
                return
            }
        }
    }

    fun togglePause() {
        if (!paused) {
            pausedAt = position()
            paused = true
            player.pause()
        } else if (channel.hasTimeshift && System.currentTimeMillis() - pausedAt > 3_000) {
            // Resume from where we paused via the archive.
            val at = pausedAt
            paused = false
            if (fromTime == at) retry++ else fromTime = at
        } else {
            paused = false
            player.play()
        }
        bannerKey++
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) {
                    lastInput = SystemClock.elapsedRealtime()
                    if (idleCountdown > 0) {
                        idleCountdown = 0
                        return@onPreviewKeyEvent true
                    }
                }
                // A long-press OK opened an overlay; its release must not click the overlay's first item.
                if (okLongPressed && isOk(e.nativeKeyEvent.keyCode) && e.type == KeyEventType.KeyUp) {
                    okLongPressed = false
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
                        event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 1 && error == null -> {
                            okLongPressed = true
                            menuOpen = true
                        }
                        event.type == KeyEventType.KeyUp && !okLongPressed -> when {
                            error != null -> {
                                if (fromTime != null) fromTime = clock.position()
                                retry++
                            }
                            else -> {
                                listStart = index
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
                    AndroidKeyEvent.KEYCODE_CHANNEL_UP, AndroidKeyEvent.KEYCODE_PAGE_UP -> zap(+1)
                    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN, AndroidKeyEvent.KEYCODE_PAGE_DOWN -> zap(-1)
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                        val step = if (code == AndroidKeyEvent.KEYCODE_DPAD_UP) -1 else 1
                        listStart = (index + step).coerceIn(0, list.lastIndex)
                        listOpen = true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> {
                        if (channel.hasTimeshift) pendingSeek -= SEEK_STEP else notice = R.string.replay_unavailable
                        bannerKey++
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        if (fromTime != null) pendingSeek += SEEK_STEP
                        bannerKey++
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE -> togglePause()
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> if (!paused) togglePause()
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> if (paused) togglePause()
                    AndroidKeyEvent.KEYCODE_GUIDE -> {
                        guideStart = index
                        guideOpen = true
                    }
                    AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_CAPTIONS, AndroidKeyEvent.KEYCODE_SETTINGS -> menuOpen = true
                    AndroidKeyEvent.KEYCODE_INFO -> bannerKey++
                    else -> return@onKeyEvent false
                }
                true
            },
    ) {
        VideoSurface(player, aspect, pictureMode)

        val bannerShown = (showBanner || paused || pendingSeek != 0L || notice != null) && error == null && !overlayOpen
        Subtitles(cues, Modifier.align(Alignment.BottomCenter).padding(bottom = if (bannerShown) 200.dp else 56.dp))

        error?.let { ErrorCard(channel, it, Modifier.align(Alignment.Center)) }

        if (pendingSeek != 0L && !overlayOpen) {
            val target = (position() + pendingSeek) / 10_000
            key(target) {
                Thumbnail(
                    url = { preview -> session.thumbnailUrl(channel, target * 10_000, preview) },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = 196.dp).size(320.dp, 180.dp),
                )
            }
        }

        if (bannerShown) {
            InfoBar(
                graph = graph,
                channel = channel,
                replay = fromTime != null,
                paused = paused,
                pendingSeek = pendingSeek,
                notice = notice,
                position = ::position,
                info = streamInfo,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        } else if ((fromTime != null || paused) && error == null && !overlayOpen) {
            // Never leave it ambiguous that we're behind live.
            BehindLivePill(position = ::position, paused = paused, modifier = Modifier.align(Alignment.TopEnd).padding(32.dp))
        }

        if (listOpen) {
            ChannelListOverlay(
                graph = graph,
                channels = list,
                playingIndex = index,
                startIndex = listStart,
                onPick = { i ->
                    listOpen = false
                    if (i != index || fromTime != null) {
                        index = i
                        fromTime = null
                    }
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
            val all = graph.catalog.snapshot?.channels ?: list
            NumberEntry(digits, all.firstOrNull { it.number == digits }?.name, Modifier.align(Alignment.TopEnd).padding(48.dp))
        }

        if (guideOpen) {
            Guide(
                graph = graph,
                channels = list,
                startIndex = guideStart,
                translucent = true,
                onPick = { i, from ->
                    index = i
                    fromTime = from
                    guideOpen = false
                    bannerKey++
                },
                onClose = { guideOpen = false },
                thumbnail = { ch, at, preview -> session.thumbnailUrl(ch, at, preview) },
            )
        }

        if (menuOpen) {
            val isFav = remember(channel.id, favTick) { profileId != null && channel.id in graph.session.favourites(profileId) }
            PlayerMenu(
                tracks = tracks,
                isFavourite = isFav,
                pictureMode = pictureMode,
                onToggleFavourite = {
                    profileId?.let { graph.session.toggleFavourite(it, channel.id) }
                    favTick++
                },
                onAudio = { g, i ->
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                    profileId?.let { graph.session.setAudioPref(it, channel.id, g.getTrackFormat(i).language) }
                },
                onText = { g, i ->
                    val b = player.trackSelectionParameters.buildUpon()
                    if (g == null) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    else b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                    player.trackSelectionParameters = b.build()
                    profileId?.let { graph.session.setTextPref(it, channel.id, if (g == null) "off" else g.getTrackFormat(i).language ?: "und") }
                },
                onPicture = {
                    pictureMode = it
                    graph.session.pictureMode = it
                },
                onClose = { menuOpen = false },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        if (idleCountdown > 0) {
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 560.dp).background(Palette.Surface, RoundedCornerShape(20.dp)).padding(36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Txt(stringResource(R.string.idle_title), Type.Title)
                Spacer(Modifier.height(10.dp))
                Txt(stringResource(R.string.idle_body, idleCountdown), Type.BodyDim)
            }
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

private val secondsClock = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private fun isOk(code: Int) = code == AndroidKeyEvent.KEYCODE_DPAD_CENTER || code == AndroidKeyEvent.KEYCODE_ENTER

/** "12:30" or "1:02:03". */
private fun formatSpan(ms: Long): String {
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
