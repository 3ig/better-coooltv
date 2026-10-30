package dev.bettercoool.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

/** [serverMessage] is the server's own (Bulgarian) user-facing text from `detail.messages`. */
class ApiException(val status: Int, val errorCode: String?, val serverMessage: String?, message: String) : IOException(message)

data class SseEvent(val type: String, val data: String)

/** Client for https://tvapi.coool.tv/api/v2 — see docs/re/spec_auth.md and spec_content.md. */
class CooolApi(private val store: SessionStore) {

    private val base: HttpUrl = BASE_URL.toHttpUrl()
    private val jsonType = "application/json".toMediaType()

    private val plainClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(DefaultHeaders())
        .build()

    private val client: OkHttpClient = plainClient.newBuilder()
        .addInterceptor(AuthHeader())
        .authenticator(TokenRefresher())
        .build()

    /** Long-lived streams: the server pings roughly every 25 s, so allow a generous read gap. */
    private val sseFactory = EventSources.createFactory(
        client.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    )

    // ---- auth / pairing ----

    suspend fun generateAuthCode(device: DeviceInfo): AuthCode = post(
        "devices/generate-auth-code",
        ApiJson.encodeToString(
            GenerateCodeRequest.serializer(),
            GenerateCodeRequest(
                deviceId = device.uniqueId,
                os = device.os,
                osVersion = device.osVersion,
                model = device.model,
                name = device.name,
                vendor = device.vendor,
                appBuildNumber = ClientVersion.APP_BUILD_NUMBER,
                appVersion = ClientVersion.APP_VERSION,
                identifier = device.uniqueId,
            ),
        ),
    ) { ApiJson.decodeFromString(AuthCode.serializer(), it) }

    fun authCodeEvents(shortCode: String): Flow<SseEvent> =
        events("devices/auth-code/$shortCode/events", authenticated = false)

    suspend fun establish(access: String, deviceId: String): EstablishResponse = post(
        "devices/auth/establish",
        ApiJson.encodeToString(EstablishRequest.serializer(), EstablishRequest(access, deviceId)),
    ) { ApiJson.decodeFromString(EstablishResponse.serializer(), it) }

    suspend fun logout() {
        runCatching { post("auth/logout", "{}") { } }
        store.clear()
    }

    // ---- content ----

    suspend fun profiles(serverDeviceId: String): List<Profile> =
        get("devices/$serverDeviceId/profiles", "limit" to "12") {
            ApiJson.decodeFromString(ListSerializer(Profile.serializer()), it)
        }

    suspend fun channels(serverDeviceId: String, profileUuid: String): List<Channel> =
        get("devices/$serverDeviceId/profiles/$profileUuid/channels") {
            ApiJson.decodeFromString(ListSerializer(Channel.serializer()), it)
        }

    // ---- EPG ----

    suspend fun epgVersion(): Int =
        get("epg/cache-version") { ApiJson.decodeFromString(EpgVersion.serializer(), it).version }

    /** Raw JSON of `{channelId: [EpgEntry…]}` for all channels; caller caches it on disk. */
    suspend fun epgRaw(fromIso: String, toIso: String, version: Int?): String =
        get("epg/filter", *listOfNotNull("from" to fromIso, "to" to toIso, version?.let { "v" to it.toString() }).toTypedArray()) { it }

    // ---- playback sessions ----

    suspend fun createSession(device: DeviceInfo): PlaybackSessionInfo = post(
        "devices/sessions",
        ApiJson.encodeToString(
            CreateSessionRequest.serializer(),
            CreateSessionRequest(
                deviceId = device.uniqueId,
                deviceOsVersion = device.osVersion,
                appBuildNumber = ClientVersion.APP_BUILD_NUMBER.toString(),
                appVersion = ClientVersion.APP_VERSION,
            ),
        ),
    ) { ApiJson.decodeFromString(PlaybackSessionInfo.serializer(), it) }

    suspend fun touchSession(uuid: String): LastActiveResponse =
        patch("devices/sessions/$uuid/last-active") {
            if (it.isBlank()) LastActiveResponse() else ApiJson.decodeFromString(LastActiveResponse.serializer(), it)
        }

    suspend fun endSession(uuid: String) {
        patch("devices/sessions/$uuid/end") { }
    }

    suspend fun heartbeat() {
        get("heartbeat") { }
    }

    // ---- plumbing ----

    fun events(path: String, authenticated: Boolean = true): Flow<SseEvent> = callbackFlow {
        val request = Request.Builder()
            .url(base.resolve(path)!!)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .apply { if (!authenticated) tag(NoAuth::class.java, NoAuth) }
            .build()
        val source = sseFactory.newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val name = type ?: "message"
                if (name != "ping" && name != "keepalive") trySend(SseEvent(name, data))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(t ?: IOException("SSE failed: HTTP ${response?.code}"))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        })
        awaitClose { source.cancel() }
    }

    private suspend fun <T> get(path: String, vararg query: Pair<String, String>, parse: (String) -> T): T {
        val url = base.resolve(path)!!.newBuilder().apply { query.forEach { addQueryParameter(it.first, it.second) } }.build()
        return execute(Request.Builder().url(url).get().build(), parse)
    }

    private suspend fun <T> post(path: String, json: String, parse: (String) -> T): T =
        execute(Request.Builder().url(base.resolve(path)!!).post(json.toRequestBody(jsonType)).build(), parse)

    private suspend fun <T> patch(path: String, parse: (String) -> T): T =
        execute(Request.Builder().url(base.resolve(path)!!).patch(EMPTY_BODY).build(), parse)

    private suspend fun <T> execute(request: Request, parse: (String) -> T): T = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw apiError(response.code, body)
            parse(body)
        }
    }

    private fun apiError(status: Int, body: String): ApiException {
        // Error bodies look like {"detail": {"errorCode": "...", "messages": ...}}
        val detail = runCatching { ApiJson.parseToJsonElement(body).jsonObject["detail"] }.getOrNull() as? JsonObject
        val code = runCatching { detail?.get("errorCode")?.jsonPrimitive?.contentOrNull }.getOrNull()
        val text = runCatching {
            (detail?.get("messages") as? JsonArray)?.joinToString("\n") { it.jsonPrimitive.content }
        }.getOrNull()
        return ApiException(status, code, text, "HTTP $status${code?.let { " $it" } ?: ""}")
    }

    private object NoAuth
    private object AuthRetried

    /** Server error messages come back in the app's language (bg or en). */
    private inner class DefaultHeaders : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(
            chain.request().newBuilder()
                .header("Accept", chain.request().header("Accept") ?: "application/json")
                .header("Accept-Language", store.language)
                .build()
        )
    }

    private inner class AuthHeader : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val token = store.accessToken
            if (token == null || request.tag(NoAuth::class.java) != null || request.header("Authorization") != null) {
                return chain.proceed(request)
            }
            return chain.proceed(request.newBuilder().header("Authorization", "JWT $token").build())
        }
    }

    /** On 401: refresh once (single flight), replay; a second 401 or a failed refresh logs out. */
    private inner class TokenRefresher : Authenticator {
        private val lock = Any()

        override fun authenticate(route: Route?, response: Response): Request? {
            val request = response.request
            if (request.tag(NoAuth::class.java) != null) return null
            val path = request.url.encodedPath
            if (path.endsWith("/auth/logout") || path.endsWith("/debug/message")) return null
            if (request.tag(AuthRetried::class.java) != null) {
                store.clear()
                return null
            }
            if (response.peekBody(16_384).string().contains("DEVICE_NOT_FOUND")) {
                store.clear()
                return null
            }
            synchronized(lock) {
                val sent = request.header("Authorization")?.removePrefix("JWT ")
                val current = store.accessToken
                // Another request already refreshed while we waited.
                val token = if (current != null && current != sent) current else refreshBlocking()
                if (token == null) {
                    store.clear()
                    return null
                }
                return request.newBuilder()
                    .header("Authorization", "JWT $token")
                    .tag(AuthRetried::class.java, AuthRetried)
                    .build()
            }
        }

        private fun refreshBlocking(): String? {
            val refresh = store.refreshToken ?: return null
            val body = ApiJson.encodeToString(RefreshRequest.serializer(), RefreshRequest(refresh))
            val request = Request.Builder()
                .url(base.resolve("devices/auth/refresh")!!)
                .post(body.toRequestBody(jsonType))
                .build()
            return runCatching {
                plainClient.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) return null
                    val pair = ApiJson.decodeFromString(TokenPair.serializer(), r.body!!.string())
                    store.saveTokens(pair.access, pair.refresh)
                    pair.access
                }
            }.getOrNull()
        }
    }

    companion object {
        // Trailing slash so relative paths resolve under /api/v2/.
        const val BASE_URL = "https://tvapi.coool.tv/api/v2/"
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
    }
}
