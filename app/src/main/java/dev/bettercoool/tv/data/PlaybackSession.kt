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

    suspend fun streamUrl(channel: Channel): String {
        val id = mutex.withLock { uuid ?: api.createSession(device).uuid.also { uuid = it; lastTouch = now() } }
        return "${channel.stream}?token=${now()}:$id"
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
