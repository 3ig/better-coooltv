package dev.bettercoool.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Tokens and small per-device preferences. Keys mirror the original app's storage keys. */
class SessionStore(context: Context) {
    private val appContext = context.applicationContext
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

    // ---- local-only features (not in the original app or the API) ----

    fun favourites(profile: String): Set<String> = prefs.getStringSet("fav_$profile", null)?.toSet().orEmpty()

    /** Returns true if the channel is now a favourite. */
    fun toggleFavourite(profile: String, channelId: String): Boolean {
        val set = favourites(profile).toMutableSet()
        val added = if (channelId in set) { set.remove(channelId); false } else { set.add(channelId); true }
        prefs.edit().putStringSet("fav_$profile", set).apply()
        return added
    }

    /** Key of the chosen main-colour preset (see AccentPresets), or null for the default. */
    var accentKey: String?
        get() = prefs.getString("accent", null)
        set(value) = prefs.edit().putString("accent", value).apply()

    /** 0 = fit (letterbox), 1 = fill (crop), 2 = stretch. */
    var pictureMode: Int
        get() = prefs.getInt("picture_mode", 0)
        set(value) = prefs.edit().putInt("picture_mode", value).apply()

    /** Player layout: false = this app's own, true = the old Coool TV app's layout and remote keys. */
    var classicPlayer: Boolean
        get() = prefs.getBoolean("classic_player", false)
        set(value) = prefs.edit().putBoolean("classic_player", value).apply()

    /** Per-channel audio / subtitle language, like the original's `chs-{profile}-{channel}`. Text "off" = disabled. */
    fun trackPrefs(profile: String, channelId: String): Pair<String?, String?> =
        prefs.getString("chs_a_${profile}_$channelId", null) to prefs.getString("chs_t_${profile}_$channelId", null)

    fun setAudioPref(profile: String, channelId: String, lang: String?) =
        prefs.edit().putString("chs_a_${profile}_$channelId", lang).apply()

    fun setTextPref(profile: String, channelId: String, lang: String?) =
        prefs.edit().putString("chs_t_${profile}_$channelId", lang).apply()

    /** Last replay position per channel (epoch ms of programme time). */
    fun resumeAt(channelId: String): Long? = prefs.getLong("resume_$channelId", 0L).takeIf { it > 0 }

    fun setResumeAt(channelId: String, ms: Long) = prefs.edit().putLong("resume_$channelId", ms).apply()

    /** Language for server messages: the app's effective UI language ("bg" or "en"). */
    val language: String get() = dev.bettercoool.tv.AppLocale.effective(appContext)

    val isPaired: Boolean get() = refreshToken != null && serverDeviceId != null

    private val _loggedOut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val loggedOut: SharedFlow<Unit> = _loggedOut

    fun saveTokens(access: String, refresh: String) {
        prefs.edit().putString("accessToken", access).putString("refreshToken", refresh).apply()
    }

    fun clear() {
        // Keep the user's language, colour and player layout choices across sign-out.
        val pinned = prefs.getString("language", null)
        val accent = prefs.getString("accent", null)
        val classic = classicPlayer
        prefs.edit().clear().putString("language", pinned).putString("accent", accent).putBoolean("classic_player", classic).apply()
        _loggedOut.tryEmit(Unit)
    }
}
