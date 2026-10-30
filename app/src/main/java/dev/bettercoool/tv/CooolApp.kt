package dev.bettercoool.tv

import android.app.Application
import android.content.Context
import dev.bettercoool.tv.data.Catalog
import dev.bettercoool.tv.data.CooolApi
import dev.bettercoool.tv.data.DeviceInfo
import dev.bettercoool.tv.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class CooolApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** Hand-rolled dependency graph; the app is small enough not to need a DI framework. */
class AppGraph(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val session = SessionStore(context)
    val device = DeviceInfo.from(context)
    val api = CooolApi(session)
    val catalog = Catalog(api, session)
}
