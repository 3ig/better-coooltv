package dev.bettercoool.tv.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import dev.bettercoool.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Self-update from GitHub Releases (release builds only): checks the latest release, downloads its
 * APK and hands it to Android's PackageInstaller. Android always shows its own confirmation; the
 * first time it also asks the user to allow installs from this app.
 */
class Updater(private val context: Context) {

    data class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

    @Serializable
    private data class GhAsset(val name: String, val browser_download_url: String, val size: Long = 0)

    @Serializable
    private data class GhRelease(val tag_name: String, val body: String? = null, val draft: Boolean = false, val assets: List<GhAsset> = emptyList())

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)

    /** Updates only make sense for the release app ID/signature; dev builds are a different app. */
    val enabled: Boolean get() = BuildConfig.BUILD_TYPE == "release"

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Latest GitHub release if it's newer than this build, else null. Throws on network errors. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val gh = http.newCall(request).execute().use { r ->
            if (r.code == 404) return@withContext null // no releases yet
            if (!r.isSuccessful) throw IOException("GitHub HTTP ${r.code}")
            ApiJson.decodeFromString(GhRelease.serializer(), r.body!!.string())
        }
        prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
        val apk = gh.assets.firstOrNull { it.name.endsWith(".apk") } ?: return@withContext null
        val version = gh.tag_name.removePrefix("v")
        if (gh.draft || !isNewer(version, currentVersion)) return@withContext null
        Release(version, gh.body.orEmpty(), apk.browser_download_url, apk.size)
    }

    /** At most one automatic check every 12 hours. */
    val checkDue: Boolean get() = System.currentTimeMillis() - prefs.getLong("last_check", 0) > 12 * 60 * 60_000L

    suspend fun download(release: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "update.apk")
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download HTTP ${r.code}")
            val body = r.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: release.size
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
        file
    }

    /** Android 8+: has the user allowed this app to install packages? */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens the "install unknown apps" setting for this app (or general security settings as a fallback). */
    fun openInstallPermissionSettings() {
        val specific = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && specific.resolveActivity(context.packageManager) != null) {
            specific
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Streams the APK into a PackageInstaller session; the system then asks the user to confirm. */
    fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val callback = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or mutable,
            )
            session.commit(callback.intentSender)
        }
    }

    companion object {
        /** Compares dotted numeric versions; anything after '-' (e.g. "-ci.12", "-dev") is ignored. */
        fun isNewer(remote: String, local: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(remote)
            val b = parts(local)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        /**
         * Release notes are Markdown; show them as plain text with bullets. Lines that continue a
         * wrapped bullet in the source are joined back onto it; the trailing Install section is dropped.
         */
        fun plainNotes(markdown: String): String {
            val out = mutableListOf<String>()
            for (raw in markdown.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("<!--")) continue
                if (line.matches(Regex("^#+\\s*Install\\b.*"))) break // sideloading notes for the GitHub page only
                val isNew = line.startsWith("#") || line.matches(Regex("^[-*]\\s+.*"))
                val text = line.replace(Regex("^#+\\s*"), "")
                    .replace(Regex("^[-*]\\s+"), "• ")
                    .replace("**", "")
                    .replace("`", "")
                if (!isNew && out.isNotEmpty() && out.last().startsWith("• ")) out[out.lastIndex] = out.last() + " " + text
                else out += text
            }
            return out.joinToString("\n")
        }
    }
}

/** Receives PackageInstaller results; launches the system confirmation when user action is needed. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app is replaced and restarted by the system
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed"
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
