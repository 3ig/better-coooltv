package dev.bettercoool.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.min

/**
 * Server push channel `/devices/sse/device/{did}`, kept open only while the app is in the
 * foreground. Event names come from the SSE `event:` field, or a `type` field in the payload.
 */
class DeviceEvents(
    private val api: CooolApi,
    private val session: SessionStore,
    private val catalog: Catalog,
    private val epg: EpgStore,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        val did = session.serverDeviceId ?: return
        job = scope.launch {
            var attempt = 0
            while (isActive) {
                try {
                    api.events("devices/sse/device/$did").collect {
                        attempt = 0
                        handle(it)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
                if (session.serverDeviceId == null) return@launch // logged out
                delay(min(1_000L shl attempt.coerceAtMost(5), 30_000L))
                attempt++
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun handle(event: SseEvent) {
        val type = if (event.type != "message") event.type else payloadType(event.data) ?: return
        when {
            type == "epg_updated" -> epg.invalidate()
            type == "device_deleted" -> session.clear()
            type.startsWith("channel_") || type.startsWith("profile_") ||
                type == "extra_package_changed" || type == "account_entitlement_changed" -> catalog.invalidate()
        }
    }

    private fun payloadType(data: String): String? = runCatching {
        val obj = ApiJson.parseToJsonElement(data) as JsonObject
        ((obj["type"] ?: obj["event"]) as? JsonPrimitive)?.content
    }.getOrNull()
}
