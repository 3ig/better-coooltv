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

/**
 * Programme guide loaded in 3-hour blocks (UTC-aligned), on demand. A block for all channels is
 * ~300 KB of JSON; the original app pulled up to ±7 days at once and held it on the JS thread.
 *
 * Blocks are cached on disk under the server's EPG cache-version and dropped when it changes.
 * At most [MAX_BLOCKS] blocks stay in memory (the ones nearest to now win).
 */
class EpgStore(private val api: CooolApi, cacheDir: File) {

    private val dir = File(cacheDir, "epg").apply { mkdirs() }
    private val versionFile = File(dir, "version")
    private val mutex = Mutex()

    private val blocks = HashMap<Long, Map<String, List<EpgEntry>>>()
    @Volatile private var merged: Map<String, List<EpgEntry>> = emptyMap()
    private var version: Int? = runCatching { versionFile.readText().trim().toInt() }.getOrNull()
    private var versionCheckedAt = 0L

    private val _revision = MutableStateFlow(0)
    /** Bumps whenever guide data changes; collect it to recompose. */
    val revision: StateFlow<Int> = _revision

    fun programmes(channelId: String): List<EpgEntry> = merged[channelId].orEmpty()

    fun programmeAt(channelId: String, at: Long): EpgEntry? =
        programmes(channelId).firstOrNull { it.startTime <= at && at < it.endTime }

    fun nowNext(channelId: String, at: Long = ServerClock.now()): Pair<EpgEntry?, EpgEntry?> {
        val list = programmes(channelId)
        val i = list.indexOfFirst { at < it.endTime }
        if (i < 0) return null to null
        val current = list[i].takeIf { it.startTime <= at }
        val next = if (current != null) list.getOrNull(i + 1) else list[i]
        return current to next
    }

    /** Guide data around now. Cheap when the blocks are already loaded. */
    suspend fun ensure() {
        val now = ServerClock.now()
        ensureRange(now - 30 * 60_000, now + 3 * BLOCK)
    }

    suspend fun ensureRange(from: Long, to: Long) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val needed = blockStarts(from, to).filter { it !in blocks }
            if (needed.isEmpty()) return@withContext
            refreshVersion()
            var changed = false
            for (start in needed) {
                val file = File(dir, "$start.json")
                val raw = runCatching { file.readText() }.getOrNull()
                    ?: runCatching { api.epgRaw(iso(start), iso(start + BLOCK), version) }.getOrNull()
                        ?.also { runCatching { file.writeText(it) } }
                    ?: continue
                val parsed = runCatching { ApiJson.decodeFromString(SERIALIZER, raw) }.getOrNull() ?: continue
                blocks[start] = parsed
                changed = true
            }
            if (changed) {
                evict()
                remerge()
            }
        }
    }

    /** Server says the guide changed (SSE `epg_updated`): drop everything and reload around now. */
    suspend fun invalidate() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                clearAll()
                versionCheckedAt = 0
            }
        }
        ensure()
    }

    private suspend fun refreshVersion() {
        val now = ServerClock.now()
        if (now - versionCheckedAt < 10 * 60_000) return
        val server = runCatching { api.epgVersion() }.getOrNull() ?: return
        versionCheckedAt = now
        if (server != version) {
            clearAll()
            version = server
            runCatching { versionFile.writeText(server.toString()) }
        }
    }

    private fun clearAll() {
        blocks.clear()
        dir.listFiles { f -> f.name.endsWith(".json") }?.forEach { it.delete() }
        merged = emptyMap()
        _revision.value++
    }

    private fun evict() {
        val now = ServerClock.now()
        if (blocks.size > MAX_BLOCKS) {
            blocks.keys.sortedByDescending { kotlin.math.abs(it + BLOCK / 2 - now) }
                .take(blocks.size - MAX_BLOCKS)
                .forEach { blocks.remove(it) }
        }
        val files = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        if (files.size > MAX_DISK_BLOCKS) {
            files.sortedByDescending { f -> kotlin.math.abs((f.nameWithoutExtension.toLongOrNull() ?: 0) - now) }
                .take(files.size - MAX_DISK_BLOCKS)
                .forEach { it.delete() }
        }
    }

    private fun remerge() {
        val out = HashMap<String, MutableList<EpgEntry>>()
        for (block in blocks.values) {
            for ((channel, entries) in block) out.getOrPut(channel) { ArrayList() }.addAll(entries)
        }
        merged = out.mapValues { (_, v) -> v.distinctBy { it.id }.sortedBy { it.startTime } }
        _revision.value++
    }

    private fun blockStarts(from: Long, to: Long): List<Long> {
        val first = Math.floorDiv(from, BLOCK) * BLOCK
        return generateSequence(first) { it + BLOCK }.takeWhile { it < to }.toList()
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    companion object {
        const val BLOCK = 3 * 60 * 60_000L
        private const val MAX_BLOCKS = 16 // 48 h in memory
        private const val MAX_DISK_BLOCKS = 40
        private val SERIALIZER = MapSerializer(String.serializer(), ListSerializer(EpgEntry.serializer()))
    }
}
