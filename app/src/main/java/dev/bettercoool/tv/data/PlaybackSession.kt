package dev.bettercoool.tv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Server-side viewing session. Stream URLs carry `token=<nowMillis>:<sessionUuid>`.
 * The server enforces MAX_ACTIVE_SESSIONS_REACHED, so sessions are ended when playback stops.
 */
class PlaybackSession(
    private val api: CooolApi,
    private val device: DeviceInfo,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var uuid: String? = null
    private var lastTouch = 0L

    /**
     * Live: `<stream>?token=…`. Catch-up from [fromMs]: `<streamTimeshift>/timeshift_abs-<unixSec>.m3u8?token=…`,
     * which plays from that moment onward toward live.
     */
    suspend fun streamUrl(channel: Channel, fromMs: Long? = null): String {
        val id = mutex.withLock { uuid ?: api.createSession(device).uuid.also { uuid = it; lastTouch = now() } }
        val token = "token=${now()}:$id"
        val timeshift = channel.streamTimeshift
        return if (fromMs != null && channel.hasTimeshift && timeshift != null) {
            "$timeshift/timeshift_abs-${fromMs / 1000}.m3u8?$token"
        } else {
            "${channel.stream}?$token"
        }
    }

    /** Heartbeat every 15 s; last-active at most every 5 min. Run while the player is visible. */
    suspend fun keepAlive() {
        while (true) {
            delay(15_000)
            runCatching { api.heartbeat() }
            val id = uuid ?: continue
            if (now() - lastTouch >= 5 * 60_000) {
                lastTouch = now()
                val closed = runCatching { api.touchSession(id).closed }.getOrDefault(true)
                if (closed) mutex.withLock { if (uuid == id) uuid = null }
            }
        }
    }

    fun end() {
        val id = uuid ?: return
        uuid = null
        scope.launch { runCatching { api.endSession(id) } }
    }

    private fun now() = System.currentTimeMillis()
}
