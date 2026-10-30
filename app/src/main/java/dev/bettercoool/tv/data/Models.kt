package dev.bettercoool.tv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Lenient: several field types (ids, numbers, timestamps) are unconfirmed until we see live responses. */
val ApiJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
data class GenerateCodeRequest(
    val deviceId: String,
    val os: String,
    val osVersion: String,
    val model: String,
    val name: String,
    val vendor: String,
    val appBuildNumber: Int,
    val appVersion: String,
    val identifier: String,
)

@Serializable
data class AuthCode(val shortCode: String, val expiresAt: JsonElement? = null) {
    val expiresAtMillis: Long? get() = parseEpochMillis(expiresAt)
}

@Serializable
data class TokenPair(val access: String, val refresh: String)

@Serializable
data class EstablishRequest(val access: String, val deviceId: String)

@Serializable
data class EstablishResponse(
    val authenticated: Boolean = false,
    val deviceId: String? = null,
    val token: TokenPair,
)

@Serializable
data class RefreshRequest(val refresh: String)

@Serializable
data class Profile(
    val uuid: String,
    val name: String,
    val imageUrl: String? = null,
    val pinCode: String? = null,
    val isProtected: Boolean = false,
) {
    val isLocked: Boolean get() = isProtected || !pinCode.isNullOrBlank()
}

@Serializable
data class Channel(
    val id: String,
    val name: String,
    val number: String? = null,
    val logo: String? = null,
    val stream: String? = null,
    val streamTimeshift: String? = null,
    val hasTimeshift: Boolean = false,
    val includedInPlan: Boolean = true,
    /** Sent by the server, ignored by the original app: information, news, sports, cinema, … */
    val category: String? = null,
    /** "sd" | "hd" | "uhd" */
    val resolution: String? = null,
) {
    val playable: Boolean get() = includedInPlan && !stream.isNullOrBlank()
    val isUhd: Boolean get() = resolution == "uhd"
}

/** `/epg/filter` entry: times in epoch ms, duration in seconds. */
@Serializable
data class EpgEntry(
    val id: Long,
    val name: String,
    val description: String? = null,
    val startTime: Long,
    val endTime: Long,
)

@Serializable
data class EpgVersion(val version: Int)

@Serializable
data class CreateSessionRequest(
    val deviceId: String,
    val deviceOsVersion: String,
    val appBuildNumber: String,
    val appVersion: String,
)

@Serializable
data class PlaybackSessionInfo(val uuid: String)

@Serializable
data class LastActiveResponse(val closed: Boolean = false)

/** Accepts epoch seconds, epoch millis, or ISO-8601 with or without an offset (UTC assumed). */
fun parseEpochMillis(element: JsonElement?): Long? {
    val p = element as? JsonPrimitive ?: return null
    if (!p.isString) return p.longOrNull?.let { if (it < 100_000_000_000L) it * 1000 else it }
    val s = p.content
    return runCatching { Instant.parse(s).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        .recoverCatching { LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli() }
        .getOrNull()
}
