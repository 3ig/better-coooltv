package dev.bettercoool.tv.data

import kotlin.math.abs

/**
 * Wall clock corrected against the API server's `Date` header. Some TVs (e.g. a 2015 Sony) wake from
 * standby with a clock that is hours off until network time catches up; stream tokens carry a timestamp
 * the CDN validates, so a skewed clock breaks playback and puts the guide's "now" in the wrong place.
 */
object ServerClock {
    /** Skew below this is left alone: the header has 1 s resolution and normal clocks are fine. */
    private const val TOLERANCE = 5_000L

    @Volatile private var offset = 0L

    /** True once a server response has been seen since start-up or the last system clock change. */
    @Volatile var synced = false
        private set

    fun now(): Long = System.currentTimeMillis() + offset

    /** [serverMs] from a response sent at [sentAt] and received at [receivedAt] (local clock). */
    fun update(serverMs: Long, sentAt: Long, receivedAt: Long) {
        val skew = serverMs - (sentAt + receivedAt) / 2
        offset = if (abs(skew) > TOLERANCE) skew else 0
        synced = true
    }

    /** The system clock was changed (e.g. network time arrived): forget the old correction. */
    fun reset() {
        offset = 0
        synced = false
    }
}
