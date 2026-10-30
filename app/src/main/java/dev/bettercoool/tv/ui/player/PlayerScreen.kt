package dev.bettercoool.tv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.annotation.StringRes
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
import dev.bettercoool.tv.ui.common.NowNext
import dev.bettercoool.tv.ui.common.NumberEntry
import dev.bettercoool.tv.ui.common.digitFor
import dev.bettercoool.tv.ui.common.minuteClock
import dev.bettercoool.tv.ui.theme.Palette
import dev.bettercoool.tv.ui.theme.Txt
import dev.bettercoool.tv.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** What went wrong, resolved to text at display time. Server messages are already localized. */
private data class PlayError(@StringRes val fallback: Int, val serverMessage: String? = null, val detail: String? = null)

/**
 * Full-screen live TV. Video renders on a SurfaceView (hardware overlay plane: required for
 * smooth 4K and HDR on weak boxes). The channel banner is only composed while visible.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(graph: AppGraph, channels: List<Channel>, startIndex: Int, onExit: () -> Unit) {
    val context = LocalContext.current
    val session = remember { PlaybackSession(graph.api, graph.device, graph.appScope) }
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
    var started by remember { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var showBanner by remember { mutableStateOf(true) }
    var bannerKey by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<PlayError?>(null) }
    var streamInfo by remember { mutableStateOf<StreamInfo?>(null) }
    var digits by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val channel = list[index]

    DisposableEffect(player) {
        val listener = object : Player.Listener {
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
            player.removeAnalyticsListener(analytics)
            player.release()
        }
    }

    LifecycleStartEffect(Unit) {
        started++
        onStopOrDispose {
            player.stop()
            session.end()
        }
    }

    LaunchedEffect(channel.id, started, retry) {
        if (started == 0) return@LaunchedEffect
        error = null
        streamInfo = null
        try {
            player.setMediaItem(MediaItem.fromUri(session.streamUrl(channel)))
            player.prepare()
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

    LaunchedEffect(bannerKey) {
        showBanner = true
        delay(5_000)
        showBanner = false
    }

    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1_500)
        val inList = list.indexOfFirst { it.number == digits && it.playable }
        if (inList >= 0) {
            index = inList
        } else {
            val full = graph.catalog.snapshot?.channels.orEmpty()
            val inFull = full.indexOfFirst { it.number == digits && it.playable }
            if (inFull >= 0) {
                list = full
                index = inFull
            }
        }
        bannerKey++
        digits = ""
    }

    LaunchedEffect(Unit) { session.keepAlive() }
    LaunchedEffect(Unit) {
        while (true) {
            graph.epg.ensure()
            delay(10 * 60_000)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    BackHandler {
        if (digits.isNotEmpty()) digits = "" else onExit()
    }

    fun zap(step: Int) {
        var i = index
        repeat(list.size) {
            i = (i + step).mod(list.size)
            if (list[i].playable) {
                index = i
                bannerKey++
                return
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val code = event.nativeKeyEvent.keyCode
                digitFor(code)?.let { d ->
                    if (digits.length < 4) digits += d
                    return@onKeyEvent true
                }
                when (code) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> { zap(+1); true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); true }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_INFO -> {
                        if (error != null) retry++ else bannerKey++
                        true
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).also { player.setVideoSurfaceView(it) } },
            modifier = Modifier.fillMaxSize(),
        )

        error?.let { ErrorCard(channel, it, Modifier.align(Alignment.Center)) }

        if (showBanner && error == null) {
            ChannelBanner(graph, channel, streamInfo, Modifier.align(Alignment.BottomStart))
        }

        if (digits.isNotEmpty()) {
            val all = graph.catalog.snapshot?.channels ?: list
            NumberEntry(digits, all.firstOrNull { it.number == digits }?.name, Modifier.align(Alignment.TopEnd).padding(48.dp))
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

@Composable
private fun ChannelBanner(graph: AppGraph, channel: Channel, info: StreamInfo?, modifier: Modifier) {
    val revision by graph.epg.revision.collectAsState()
    val clock = minuteClock()
    val (now, next) = remember(channel.id, revision, clock) { graph.epg.nowNext(channel.id, clock) }
    Box(modifier.fillMaxWidth().background(Palette.Scrim).padding(horizontal = 56.dp, vertical = 28.dp)) {
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
                }
                Spacer(Modifier.height(8.dp))
                NowNext(now, next, clock)
            }
            if (info != null) {
                Spacer(Modifier.width(24.dp))
                Txt(info.describe(), Type.Label, Modifier.widthIn(max = 260.dp), color = Palette.TextFaint)
            }
        }
    }
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
