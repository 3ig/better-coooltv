package dev.bettercoool.tv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import dev.bettercoool.tv.AppGraph
import dev.bettercoool.tv.R
import dev.bettercoool.tv.data.Channel
import dev.bettercoool.tv.data.PlaybackSession
import dev.bettercoool.tv.ui.theme.Palette
import dev.bettercoool.tv.ui.theme.Txt
import dev.bettercoool.tv.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

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
        // Default track selector sizes the viewport from the physical display mode,
        // so 2160p variants are eligible on 4K outputs even when the UI is 1080p.
        ExoPlayer.Builder(context).build().apply { playWhenReady = true }
    }
    var index by remember { mutableIntStateOf(startIndex) }
    var started by remember { mutableIntStateOf(0) }
    var showBanner by remember { mutableStateOf(true) }
    var bannerKey by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    var streamInfo by remember { mutableStateOf<StreamInfo?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                error = e.errorCodeName
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

    LaunchedEffect(index, started) {
        if (started == 0) return@LaunchedEffect
        val channel = channels[index]
        error = null
        streamInfo = null
        try {
            player.setMediaItem(MediaItem.fromUri(session.streamUrl(channel)))
            player.prepare()
            graph.session.currentProfile?.let { graph.session.setLastChannel(it, channel.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        }
    }

    LaunchedEffect(bannerKey) {
        showBanner = true
        delay(4_000)
        showBanner = false
    }

    LaunchedEffect(Unit) { session.keepAlive() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    BackHandler(onBack = onExit)

    fun zap(step: Int) {
        var i = index
        repeat(channels.size) {
            i = (i + step).mod(channels.size)
            if (channels[i].playable) {
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
                when (event.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> { zap(+1); true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); true }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_INFO -> { bannerKey++; true }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).also { player.setVideoSurfaceView(it) } },
            modifier = Modifier.fillMaxSize(),
        )

        if (showBanner || error != null) {
            ChannelBanner(channels[index], error, streamInfo, Modifier.align(Alignment.BottomStart))
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
private fun ChannelBanner(channel: Channel, error: String?, info: StreamInfo?, modifier: Modifier) {
    Box(modifier.fillMaxWidth().background(Palette.Scrim).padding(horizontal = 56.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 96.dp, height = 54.dp).background(Palette.SurfaceHigh, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logo != null) AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.padding(6.dp).fillMaxSize())
            }
            Spacer(Modifier.width(20.dp))
            Column {
                Txt(listOfNotNull(channel.number, channel.name).joinToString("  "), Type.Title)
                if (info != null) {
                    Spacer(Modifier.height(4.dp))
                    Txt(info.describe(), Type.Label)
                }
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Txt("${stringResource(R.string.player_error)} · $error", Type.BodyDim, color = Palette.Accent)
                }
            }
        }
    }
}
