package dev.bettercoool.tv.ui.pairing

import dev.bettercoool.tv.AppGraph
import dev.bettercoool.tv.data.ApiJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.min

sealed interface PairingState {
    data object Loading : PairingState
    data class ShowCode(val code: String, val expiresAtMillis: Long) : PairingState
    data object Connecting : PairingState
    data object Retrying : PairingState
    data object Paired : PairingState
}

private data class Grant(val access: String, val deviceId: String)

/**
 * generate-auth-code → wait on the code's SSE stream for a token → establish → store tokens.
 * Codes are regenerated when they expire. Runs until paired or cancelled.
 */
suspend fun runPairing(graph: AppGraph, emit: (PairingState) -> Unit) {
    while (true) {
        emit(PairingState.Loading)
        val code = try {
            graph.api.generateAuthCode(graph.device)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(PairingState.Retrying)
            delay(5_000)
            continue
        }
        val expiresAt = code.expiresAtMillis ?: (System.currentTimeMillis() + 5 * 60_000)
        emit(PairingState.ShowCode(code.shortCode, expiresAt))

        val ttl = (expiresAt - System.currentTimeMillis()).coerceAtLeast(15_000)
        val grant = withTimeoutOrNull(ttl) {
            graph.api.authCodeEvents(code.shortCode)
                .retryWhen { cause, attempt ->
                    if (cause is CancellationException) return@retryWhen false
                    delay(min(1_000L shl attempt.toInt().coerceAtMost(5), 30_000L))
                    true
                }
                .mapNotNull { findGrant(it.data) }
                .first()
        } ?: continue // expired: get a fresh code

        emit(PairingState.Connecting)
        try {
            val result = graph.api.establish(grant.access, grant.deviceId)
            graph.session.saveTokens(result.token.access, result.token.refresh)
            graph.session.serverDeviceId = result.deviceId ?: grant.deviceId
            emit(PairingState.Paired)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(PairingState.Retrying)
            delay(3_000)
        }
    }
}

/**
 * The original accepts any event whose payload carries token.access and deviceId; the envelope
 * isn't confirmed, so search the JSON tree for an object with both.
 */
private fun findGrant(data: String): Grant? {
    val root = runCatching { ApiJson.parseToJsonElement(data) }.getOrNull() ?: return null
    return search(root)
}

private fun search(e: JsonElement): Grant? = when (e) {
    is JsonObject -> {
        val access = ((e["token"] as? JsonObject)?.get("access") as? JsonPrimitive)?.content
        val deviceId = (e["deviceId"] as? JsonPrimitive)?.content
        if (access != null && deviceId != null) Grant(access, deviceId) else e.values.firstNotNullOfOrNull(::search)
    }
    is JsonArray -> e.firstNotNullOfOrNull(::search)
    else -> null
}
