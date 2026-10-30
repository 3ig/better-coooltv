package dev.bettercoool.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Programme guide kept as a small rolling window (1 h back, 5 h ahead) instead of the original's
 * ±7 days for every channel (6 h ≈ 600 KB of JSON, so a week would be tens of MB on the JS thread).
 * The raw response is cached on disk and reused while the server's cache-version is unchanged.
 */
class EpgStore(private val api: CooolApi, cacheDir: File) {

    private val file = File(cacheDir, "epg.json")
    private val meta = File(cacheDir, "epg.meta") // "<version> <fromMs> <toMs>"
    private val mutex = Mutex()

    @Volatile private var byChannel: Map<String, List<EpgEntry>> = emptyMap()
    @Volatile private var windowEnd = 0L

    private val _revision = MutableStateFlow(0)
    /** Bumps whenever new guide data is available; collect to recompose. */
    val revision: StateFlow<Int> = _revision

    fun programmes(channelId: String): List<EpgEntry> = byChannel[channelId].orEmpty()

    fun nowNext(channelId: String, at: Long = System.currentTimeMillis()): Pair<EpgEntry?, EpgEntry?> {
        val list = programmes(channelId)
        val i = list.indexOfFirst { at < it.endTime }
        if (i < 0) return null to null
        val current = list[i].takeIf { it.startTime <= at }
        val next = if (current != null) list.getOrNull(i + 1) else list[i]
        return current to next
    }

    /** Make sure the window covers at least the next 90 minutes. Cheap when it already does. */
    suspend fun ensure() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (windowEnd - now > MIN_AHEAD) return@withContext

            val serverVersion = runCatching { api.epgVersion() }.getOrNull()
            val cached = readMeta()
            if (cached != null && cached.to - now > MIN_AHEAD && cached.from <= now &&
                (serverVersion == null || serverVersion == cached.version)
            ) {
                runCatching { publish(file.readText(), cached.to) }.onSuccess { return@withContext }
            }

            val from = Instant.ofEpochMilli(now).truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS)
            val to = from.plus(6, ChronoUnit.HOURS)
            val raw = runCatching { api.epgRaw(from.toString(), to.toString(), serverVersion) }.getOrNull()
                ?: return@withContext
            publish(raw, to.toEpochMilli())
            runCatching {
                file.writeText(raw)
                meta.writeText("${serverVersion ?: 0} ${from.toEpochMilli()} ${to.toEpochMilli()}")
            }
        }
    }

    private fun publish(raw: String, end: Long) {
        val parsed = ApiJson.decodeFromString(SERIALIZER, raw)
        byChannel = parsed.mapValues { (_, v) -> v.sortedBy { it.startTime } }
        windowEnd = end
        _revision.value++
    }

    private data class Meta(val version: Int, val from: Long, val to: Long)

    private fun readMeta(): Meta? = runCatching {
        val (v, f, t) = meta.readText().trim().split(' ')
        Meta(v.toInt(), f.toLong(), t.toLong())
    }.getOrNull()

    companion object {
        private const val MIN_AHEAD = 90 * 60_000L
        private val SERIALIZER = MapSerializer(String.serializer(), ListSerializer(EpgEntry.serializer()))
    }
}
