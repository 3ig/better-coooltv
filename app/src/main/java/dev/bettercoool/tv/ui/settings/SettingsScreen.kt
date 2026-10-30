package dev.bettercoool.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.bettercoool.tv.AppGraph
import dev.bettercoool.tv.BuildConfig
import dev.bettercoool.tv.R
import dev.bettercoool.tv.data.Capabilities
import dev.bettercoool.tv.ui.theme.FocusCard
import dev.bettercoool.tv.ui.theme.Palette
import dev.bettercoool.tv.ui.theme.requestFocusSoon
import dev.bettercoool.tv.ui.theme.Txt
import dev.bettercoool.tv.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(graph: AppGraph, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var guideReloaded by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { first.requestFocusSoon() }
    LaunchedEffect(confirmSignOut) {
        if (confirmSignOut) {
            delay(4_000)
            confirmSignOut = false
        }
    }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().padding(horizontal = 80.dp, vertical = 56.dp)) {
        Txt(stringResource(R.string.app_name).uppercase(), Type.Label, color = Palette.Accent)
        Spacer(Modifier.height(8.dp))
        Txt(stringResource(R.string.settings), Type.Display)
        Spacer(Modifier.height(32.dp))

        Txt(stringResource(R.string.settings_device).uppercase(), Type.Label)
        Spacer(Modifier.height(8.dp))
        Info(stringResource(R.string.settings_device), "${graph.device.vendor} ${graph.device.model} · Android ${graph.device.osVersion}")
        Info(
            stringResource(R.string.settings_uhd),
            stringResource(if (Capabilities.uhdHevcMain10) R.string.settings_supported else R.string.settings_unsupported),
        )
        Info(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)

        Spacer(Modifier.height(28.dp))
        Action(
            text = stringResource(R.string.settings_clear_guide) + if (guideReloaded) "  ✓" else "",
            modifier = Modifier.focusRequester(first),
        ) {
            scope.launch {
                graph.epg.invalidate()
                guideReloaded = true
            }
        }
        Spacer(Modifier.height(8.dp))
        Action(
            text = stringResource(if (confirmSignOut) R.string.settings_signout_confirm else R.string.settings_signout),
            danger = true,
        ) {
            if (!confirmSignOut) {
                confirmSignOut = true
            } else {
                graph.events.stop()
                // api.logout() clears local state even if the call fails; AppRoot then shows pairing.
                graph.appScope.launch { graph.api.logout() }
            }
        }
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Txt(label, Type.Body, Modifier.width(260.dp), color = Palette.TextDim)
        Txt(value, Type.Body)
    }
}

@Composable
private fun Action(text: String, modifier: Modifier = Modifier, danger: Boolean = false, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(50), idleColor = Palette.Surface) { focused ->
        Txt(
            text,
            Type.Body,
            Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            color = when {
                focused -> Palette.OnFocus
                danger -> Palette.Accent
                else -> Palette.Text
            },
        )
    }
}
