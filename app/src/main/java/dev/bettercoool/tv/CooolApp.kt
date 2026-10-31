package dev.bettercoool.tv

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import dev.bettercoool.tv.ui.theme.AccentPresets
import dev.bettercoool.tv.ui.theme.Palette
import dev.bettercoool.tv.data.Catalog
import dev.bettercoool.tv.data.CooolApi
import dev.bettercoool.tv.data.DeviceEvents
import dev.bettercoool.tv.data.DeviceInfo
import dev.bettercoool.tv.data.EpgStore
import dev.bettercoool.tv.data.ServerClock
import dev.bettercoool.tv.data.SessionStore
import dev.bettercoool.tv.data.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CooolApp : Application(), ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        AccentPresets.firstOrNull { it.key == graph.session.accentKey }?.let { Palette.Accent = it.color }
        // Network time arriving after standby corrects the system clock; re-measure against the server then.
        val timeChanged = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = ServerClock.reset()
        }
        ContextCompat.registerReceiver(this, timeChanged, IntentFilter(Intent.ACTION_TIME_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(SvgDecoder.Factory()) }
        .crossfade(false)
        .build()
}

/** Hand-rolled dependency graph; the app is small enough not to need a DI framework. */
class AppGraph(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val session = SessionStore(context)
    val device = DeviceInfo.from(context)
    val api = CooolApi(session)
    val catalog = Catalog(api, session)
    val epg = EpgStore(api, context.cacheDir)
    val events = DeviceEvents(api, session, catalog, epg, appScope)
    val updater = Updater(context)
}
