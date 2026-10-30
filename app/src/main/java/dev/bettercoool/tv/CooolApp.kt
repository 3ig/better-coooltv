package dev.bettercoool.tv

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import dev.bettercoool.tv.data.Catalog
import dev.bettercoool.tv.data.CooolApi
import dev.bettercoool.tv.data.DeviceEvents
import dev.bettercoool.tv.data.DeviceInfo
import dev.bettercoool.tv.data.EpgStore
import dev.bettercoool.tv.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CooolApp : Application(), ImageLoaderFactory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
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
}
