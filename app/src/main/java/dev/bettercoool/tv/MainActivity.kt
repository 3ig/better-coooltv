package dev.bettercoool.tv

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
import dev.bettercoool.tv.ui.guide.Guide
import dev.bettercoool.tv.ui.home.HomeScreen
import dev.bettercoool.tv.ui.pairing.PairingScreen
import dev.bettercoool.tv.ui.player.PlayerScreen
import dev.bettercoool.tv.ui.settings.SettingsScreen
import dev.bettercoool.tv.ui.theme.Palette

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as CooolApp).graph
        setContent { AppRoot(graph) }
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

    LaunchedEffect(Unit) {
        graph.session.loggedOut.collect { screen = Screen.Pairing }
    }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
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
    }
}
