package dev.bettercoool.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Tokens and small per-device preferences. Keys mirror the original app's storage keys. */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    var accessToken: String?
        get() = prefs.getString("accessToken", null)
        set(value) = prefs.edit().putString("accessToken", value).apply()

    var refreshToken: String?
        get() = prefs.getString("refreshToken", null)
        set(value) = prefs.edit().putString("refreshToken", value).apply()

    /** Server-side device id returned by /devices/auth/establish (not the Android ID). */
    var serverDeviceId: String?
        get() = prefs.getString("did", null)
        set(value) = prefs.edit().putString("did", value).apply()

    var currentProfile: String?
        get() = prefs.getString("current_profile", null)
        set(value) = prefs.edit().putString("current_profile", value).apply()

    fun lastChannel(profile: String): String? = prefs.getString("current_channel_profile_$profile", null)

    fun setLastChannel(profile: String, channelId: String) =
        prefs.edit().putString("current_channel_profile_$profile", channelId).apply()

    val isPaired: Boolean get() = refreshToken != null && serverDeviceId != null

    private val _loggedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val loggedOut: SharedFlow<Unit> = _loggedOut

    fun saveTokens(access: String, refresh: String) {
        prefs.edit().putString("accessToken", access).putString("refreshToken", refresh).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
        _loggedOut.tryEmit(Unit)
    }
}
