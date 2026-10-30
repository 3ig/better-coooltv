package dev.bettercoool.tv.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings

/** What the original app reports about the device (react-native-device-info equivalents). */
data class DeviceInfo(
    val uniqueId: String,
    val os: String,
    val osVersion: String,
    val model: String,
    val name: String,
    val vendor: String,
) {
    companion object {
        @SuppressLint("HardwareIds")
        fun from(context: Context): DeviceInfo {
            val resolver = context.contentResolver
            val vendor = Build.MANUFACTURER.orEmpty()
            val model = Build.MODEL.orEmpty()
            val isFireTv = vendor.equals("Amazon", ignoreCase = true) || model.startsWith("AFT")
            return DeviceInfo(
                uniqueId = Settings.Secure.getString(resolver, Settings.Secure.ANDROID_ID) ?: "unknown",
                os = if (isFireTv) "firetv" else "androidtv",
                osVersion = Build.VERSION.RELEASE.orEmpty(),
                model = model,
                name = Settings.Global.getString(resolver, "device_name") ?: model,
                vendor = vendor,
            )
        }
    }
}

/**
 * The server gates features on the original app's version (minimum-versions check, pairing),
 * so we identify as the release we reverse engineered.
 */
object ClientVersion {
    const val APP_VERSION = "2.0.13"
    const val APP_BUILD_NUMBER = 2400126
}
