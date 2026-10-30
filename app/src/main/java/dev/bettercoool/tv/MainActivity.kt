package dev.bettercoool.tv

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.bettercoool.tv.data.Channel
import dev.bettercoool.tv.data.Updater
import dev.bettercoool.tv.ui.update.UpdateDialog
import dev.bettercoool.tv.ui.guide.Guide
import dev.bettercoool.tv.ui.home.HomeScreen
import dev.bettercoool.tv.ui.pairing.PairingScreen
import dev.bettercoool.tv.ui.player.PlayerScreen
import dev.bettercoool.tv.ui.settings.SettingsScreen
import dev.bettercoool.tv.ui.theme.KeyRepeat
import dev.bettercoool.tv.ui.theme.Palette

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as CooolApp).graph
        setContent { AppRoot(graph) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            KeyRepeat.active = event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0
        }
        return super.dispatchKeyEvent(normalizeGuideKey(event))
    }

    /**
     * Some TV makers map the remote's Guide/EPG button to their own key code (TCL sends 4001) instead
     * of KEYCODE_GUIDE. The Linux scan code is still KEY_EPG (365) or KEY_PROGRAM (362), so use that.
     */
    private fun normalizeGuideKey(e: KeyEvent): KeyEvent =
        if (e.keyCode != KeyEvent.KEYCODE_GUIDE && (e.scanCode == SCAN_KEY_EPG || e.scanCode == SCAN_KEY_PROGRAM)) {
            KeyEvent(e.downTime, e.eventTime, e.action, KeyEvent.KEYCODE_GUIDE, e.repeatCount, e.metaState, e.deviceId, e.scanCode, e.flags, e.source)
        } else {
            e
        }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        perfScroll(intent)
    }

    /**
     * Dev/profile builds only: `am start ... --ei perf_scroll N` holds Down for N repeats, then Up for N
     * (50 ms repeat, like a held remote key) from inside the app, so frame timing can be measured
     * without adb's per-key process launches stealing the CPU.
     */
    private fun perfScroll(intent: Intent) {
        if (BuildConfig.BUILD_TYPE == "release") return
        val n = intent.getIntExtra("perf_scroll", 0)
        if (n <= 0) return
        val handler = Handler(Looper.getMainLooper())
        listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP).forEachIndexed { pass, code ->
            val start = 50L * (n + 2) * pass
            for (i in 0 until n) {
                handler.postDelayed({
                    val t = SystemClock.uptimeMillis()
                    dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, i))
                }, start + 50L * i)
            }
            handler.postDelayed({
                val t = SystemClock.uptimeMillis()
                dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
            }, start + 50L * n)
        }
    }

    override fun onStart() {
        super.onStart()
        (application as CooolApp).graph.events.start()
    }

    override fun onStop() {
        (application as CooolApp).graph.events.stop()
        super.onStop()
    }
}

private const val SCAN_KEY_PROGRAM = 362 // Linux input-event-codes.h
private const val SCAN_KEY_EPG = 365

private sealed interface Screen {
    data object Pairing : Screen
    data object Home : Screen
    data class Player(val channels: List<Channel>, val index: Int, val from: Long? = null) : Screen
    data class Guide(val channels: List<Channel>, val index: Int) : Screen
    data object Settings : Screen
}

/** No navigation library: a handful of screens and a back stack of one don't need it. */
@Composable
private fun AppRoot(graph: AppGraph) {
    var screen by remember { mutableStateOf<Screen>(if (graph.session.isPaired) Screen.Home else Screen.Pairing) }
    var updateOffer by remember { mutableStateOf<Updater.Release?>(null) }

    // Release builds look for a newer GitHub release at most every 12 h, only on the home screen.
    LaunchedEffect(screen == Screen.Home) {
        if (screen == Screen.Home && graph.updater.enabled && graph.updater.checkDue) {
            updateOffer = runCatching { graph.updater.check() }.getOrNull()
        }
    }

    LaunchedEffect(Unit) {
        graph.session.loggedOut.collect { screen = Screen.Pairing }
    }

    // No background here: the window background is already this colour (avoids a second full-screen fill per frame).
    Box(Modifier.fillMaxSize()) {
        when (val s = screen) {
            Screen.Pairing -> PairingScreen(graph, onPaired = {
                graph.events.start()
                screen = Screen.Home
            })
            Screen.Home -> HomeScreen(
                graph,
                onPlay = { channels, index -> screen = Screen.Player(channels, index) },
                onGuide = { channels, index -> screen = Screen.Guide(channels, index) },
                onSettings = { screen = Screen.Settings },
            )
            is Screen.Player -> PlayerScreen(graph, s.channels, s.index, s.from, onExit = { screen = Screen.Home })
            is Screen.Guide -> Guide(
                graph, s.channels, s.index,
                onPick = { index, from -> screen = Screen.Player(s.channels, index, from) },
                onClose = { screen = Screen.Home },
            )
            Screen.Settings -> SettingsScreen(graph, onBack = { screen = Screen.Home })
        }
        if (screen == Screen.Home) {
            updateOffer?.let { UpdateDialog(graph.updater, it, onDismiss = { updateOffer = null }) }
        }
    }
}
