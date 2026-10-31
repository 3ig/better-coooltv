package dev.bettercoool.tv.ui.player

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import dev.bettercoool.tv.AppGraph
import dev.bettercoool.tv.R
import dev.bettercoool.tv.data.ApiException
import dev.bettercoool.tv.data.Capabilities
import dev.bettercoool.tv.data.Channel
import dev.bettercoool.tv.data.PlaybackSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

internal const val LIVE_LATENCY = 15_000L // typical HLS distance from real time
internal const val ARCHIVE_DEPTH = 7L * 24 * 60 * 60_000L
private const val IDLE_AFTER = 4L * 60 * 60_000L // same as the original
private const val AUTO_RETRIES = 6 // 1+2+4+8+8+8 s of quiet retrying before an error shows

/** What went wrong, resolved to text at display time. Server messages are already localized. */
internal data class PlayError(@StringRes val fallback: Int, val serverMessage: String? = null, val detail: String? = null)

/**
 * Programme time of what's on screen during catch-up: start time + time actually spent playing
 * (buffering and pauses don't advance it). Timeshift playlists don't expose a usable timeline.
 */
internal class CatchupClock {
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
 * Everything a player screen does that isn't drawing: the ExoPlayer, the server session, what's
 * playing (channel, live or replay, paused), seeking, retries, tracks and the idle check. The
 * Modern and Classic layouts are two skins over one of these ([rememberPlayerController]).
 */
@OptIn(UnstableApi::class)
@Stable
class PlayerController internal constructor(
    private val graph: AppGraph,
    channels: List<Channel>,
    startIndex: Int,
    startFrom: Long?,
    context: Context,
    private val scope: CoroutineScope,
) {
    val session = PlaybackSession(graph.api, graph.device, graph.appScope)
    internal val clock = CatchupClock()
    val player: ExoPlayer = run {
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
    var list by mutableStateOf(channels)
        private set
    var index by mutableIntStateOf(startIndex)
        private set
    var fromTime by mutableStateOf(startFrom) // null = live
        private set
    var paused by mutableStateOf(false)
        private set
    private var pausedAt by mutableLongStateOf(0L)
    /** Seek not yet applied; Left/Right presses add up and are applied together ([seekDelayMs] after the last). */
    var pendingSeek by mutableLongStateOf(0L)
        private set
    /** Bumped each time a pending seek lands. */
    var seeks by mutableIntStateOf(0)
        private set
    internal var started by mutableIntStateOf(0)
    internal var retry by mutableIntStateOf(0)
    internal var error by mutableStateOf<PlayError?>(null)
    var reconnecting by mutableStateOf(false)
        private set
    private var autoRetries = 0
    var streamInfo by mutableStateOf<StreamInfo?>(null)
        private set
    var aspect by mutableStateOf(0f)
        private set
    var cues by mutableStateOf<List<Cue>>(emptyList())
        private set
    var tracks by mutableStateOf(Tracks.EMPTY)
        private set
    var pictureMode by mutableIntStateOf(graph.session.pictureMode)
        private set
    private var favTick by mutableIntStateOf(0)
    private var lastInput = SystemClock.elapsedRealtime()
    var idleCountdown by mutableIntStateOf(0) // > 0 while asking "still watching?"
        private set
    /** The channel watched before the current one (for "swap back"). */
    var previous by mutableStateOf<Channel?>(null)
        private set
    var seekDelayMs = 800L

    val profileId: String? = graph.session.currentProfile
    val channel: Channel get() = list[index]
    val hasError: Boolean get() = error != null
    val isFavourite: Boolean get() = favTick.let { profileId != null && channel.id in graph.session.favourites(profileId) }

    /** Programme time currently on screen. */
    fun position(): Long = when {
        paused -> pausedAt
        fromTime != null -> clock.position()
        // ExoPlayer's live offset depends on the stream's clock matching the TV's; on these streams it can be
        // off by tens of seconds (even negative), so only trust plausible values and assume typical HLS latency otherwise.
        else -> System.currentTimeMillis() - player.currentLiveOffset.let { if (it in 3_000L..90_000L) it else LIVE_LATENCY }
    }

    /**
     * Network failures retry quietly with backoff before an error shows: after standby the TV's Wi-Fi/DNS
     * can take a while to come back, and a brand-new session's first request sometimes gets a 404.
     * Returns false once retries are used up.
     */
    internal fun retrySoon(max: Int = AUTO_RETRIES): Boolean {
        if (autoRetries >= max) {
            reconnecting = false
            return false
        }
        val wait = 1_000L shl autoRetries.coerceAtMost(3)
        autoRetries++
        reconnecting = true
        scope.launch {
            delay(wait)
            if (fromTime != null) fromTime = clock.position()
            retry++
        }
        return true
    }

    /** Retry after an error (OK on the error card). */
    fun retryNow() {
        if (fromTime != null) fromTime = clock.position()
        autoRetries = 0
        retry++
    }

    /** Records a key press; returns true if it only dismissed the "still watching?" question. */
    fun onInput(): Boolean {
        lastInput = SystemClock.elapsedRealtime()
        if (idleCountdown > 0) {
            idleCountdown = 0
            return true
        }
        return false
    }

    /** Plays [i] of the current list, live or from [from]. */
    fun play(i: Int, from: Long? = null) {
        if (i != index) previous = channel
        index = i
        fromTime = from
    }

    /** Next/previous playable channel, wrapping around. */
    fun zap(step: Int) {
        var i = index
        repeat(list.size) {
            i = (i + step).mod(list.size)
            if (list[i].playable) {
                play(i)
                return
            }
        }
    }

    /** Jumps to a channel number, falling back to the full catalog. Returns false if there's no such channel. */
    fun playNumber(number: String): Boolean {
        val inList = list.indexOfFirst { it.number == number && it.playable }
        if (inList >= 0) {
            play(inList)
            return true
        }
        val full = graph.catalog.snapshot?.channels.orEmpty()
        val inFull = full.indexOfFirst { it.number == number && it.playable }
        if (inFull < 0) return false
        previous = channel
        list = full
        index = inFull
        fromTime = null
        return true
    }

    fun channelNamed(number: String): Channel? = (graph.catalog.snapshot?.channels ?: list).firstOrNull { it.number == number }

    /** Back to the channel watched before this one. */
    fun swapBack() {
        val prev = previous ?: return
        val i = list.indexOfFirst { it.id == prev.id }
        if (i >= 0) play(i) else playNumber(prev.number ?: return)
    }

    fun goLive() {
        fromTime = null
    }

    fun seekBy(ms: Long) {
        pendingSeek += ms
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
    }

    fun toggleFavourite() {
        profileId?.let { graph.session.toggleFavourite(it, channel.id) }
        favTick++
    }

    fun selectAudio(g: Tracks.Group, i: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
        profileId?.let { graph.session.setAudioPref(it, channel.id, g.getTrackFormat(i).language) }
    }

    /** [g] null = subtitles off. */
    fun selectText(g: Tracks.Group?, i: Int) {
        val b = player.trackSelectionParameters.buildUpon()
        if (g == null) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        else b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
        player.trackSelectionParameters = b.build()
        profileId?.let { graph.session.setTextPref(it, channel.id, if (g == null) "off" else g.getTrackFormat(i).language ?: "und") }
    }

    fun setPicture(mode: Int) {
        pictureMode = mode
        graph.session.pictureMode = mode
    }

    fun thumbnailUrl(ch: Channel, at: Long, preview: Boolean): String? = session.thumbnailUrl(ch, at, preview)

    internal fun bindListeners(onPlaying: (Boolean) -> Unit): () -> Unit {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                clock.playing(isPlaying)
                onPlaying(isPlaying)
                if (isPlaying) {
                    reconnecting = false
                    autoRetries = 0
                }
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
                session.end()
                // IO errors (2xxx) are worth retrying; an HTTP error status only briefly (session hiccup vs. dead stream).
                val max = if (e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) 2 else AUTO_RETRIES
                if (e.errorCode in 2000..2999 && retrySoon(max)) return
                error = if (channel.isUhd && !Capabilities.uhdHevcMain10) {
                    PlayError(R.string.err_uhd)
                } else {
                    PlayError(R.string.err_playback, detail = e.errorCodeName)
                }
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
        return {
            player.removeListener(listener)
            player.removeAnalyticsListener(analytics)
            player.release()
        }
    }

    internal fun onStop() {
        if (fromTime != null || paused) fromTime = position()
        fromTime?.let { graph.session.setResumeAt(channel.id, it) }
        player.stop()
        session.end()
    }

    internal suspend fun load() {
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
            if (e is IOException && retrySoon()) return
            error = PlayError(R.string.err_playback, detail = e.message)
        }
    }

    internal suspend fun applySeekSoon() {
        if (pendingSeek == 0L) return
        delay(seekDelayMs)
        val now = System.currentTimeMillis()
        val target = (position() + pendingSeek).coerceAtLeast(now - ARCHIVE_DEPTH)
        pendingSeek = 0
        fromTime = if (target >= now - LIVE_LATENCY / 2) null else target
        seeks++
    }

    // Remember where we are in a replay so the guide can offer "Resume from …".
    internal suspend fun saveResumePoints() {
        if (fromTime == null) return
        while (true) {
            delay(15_000)
            if (!paused) graph.session.setResumeAt(channel.id, position())
        }
    }

    // "Still watching?" after 4 h without a key press; stops playback (and the server session) if unanswered.
    internal suspend fun watchIdle() {
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

    internal suspend fun refreshEpg() {
        while (true) {
            graph.epg.ensure()
            delay(10 * 60_000)
        }
    }
}

/** Creates the controller and runs its effects for as long as the calling screen is shown. */
@Composable
fun rememberPlayerController(graph: AppGraph, channels: List<Channel>, startIndex: Int, startFrom: Long?): PlayerController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = remember { PlayerController(graph, channels, startIndex, startFrom, context, scope) }

    // Keeps TV screensavers (e.g. TCL's) away only while video is actually playing.
    val view = LocalView.current
    DisposableEffect(c) {
        val unbind = c.bindListeners { view.keepScreenOn = it }
        onDispose {
            view.keepScreenOn = false
            unbind()
        }
    }
    LifecycleStartEffect(Unit) {
        c.started++
        onStopOrDispose { c.onStop() }
    }
    LaunchedEffect(c.channel.id, c.fromTime, c.started, c.retry) {
        if (c.started > 0) c.load()
    }
    // Debounced seek: one reload per burst of Left/Right presses.
    LaunchedEffect(c.pendingSeek) { c.applySeekSoon() }
    LaunchedEffect(c.channel.id, c.fromTime) { c.saveResumePoints() }
    LaunchedEffect(Unit) { c.watchIdle() }
    LaunchedEffect(Unit) { c.session.keepAlive() }
    LaunchedEffect(Unit) { c.refreshEpg() }
    return c
}
