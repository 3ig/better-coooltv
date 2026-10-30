package dev.bettercoool.tv.ui.settings

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import dev.bettercoool.tv.ui.theme.AccentPreset
import dev.bettercoool.tv.ui.theme.AccentPresets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.bettercoool.tv.AppGraph
import dev.bettercoool.tv.AppLocale
import dev.bettercoool.tv.BuildConfig
import dev.bettercoool.tv.R
import dev.bettercoool.tv.data.Capabilities
import dev.bettercoool.tv.data.Updater
import dev.bettercoool.tv.ui.update.UpdateDialog
import dev.bettercoool.tv.ui.theme.FocusCard
import dev.bettercoool.tv.ui.theme.Palette
import dev.bettercoool.tv.ui.theme.requestFocusSoon
import dev.bettercoool.tv.ui.theme.Txt
import dev.bettercoool.tv.ui.theme.Type
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class UpdateState { Idle, Checking, UpToDate, Error }

@Composable
fun SettingsScreen(graph: AppGraph, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as Activity
    val first = remember { FocusRequester() }
    var guideReloaded by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var updateState by remember { mutableStateOf(UpdateState.Idle) }
    var found by remember { mutableStateOf<Updater.Release?>(null) }

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

        // Two columns so everything fits on one TV screen (nothing to scroll to).
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(0.42f)) {
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
                if (graph.updater.enabled) {
                    Action(
                        text = stringResource(R.string.settings_check_updates) + when (updateState) {
                            UpdateState.Checking -> " · " + stringResource(R.string.settings_checking)
                            UpdateState.UpToDate -> " · " + stringResource(R.string.settings_up_to_date)
                            UpdateState.Error -> " · " + stringResource(R.string.settings_update_error)
                            UpdateState.Idle -> ""
                        },
                    ) {
                        if (updateState != UpdateState.Checking) scope.launch {
                            updateState = UpdateState.Checking
                            updateState = try {
                                found = graph.updater.check()
                                if (found == null) UpdateState.UpToDate else UpdateState.Idle
                            } catch (e: Exception) {
                                UpdateState.Error
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
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
            Spacer(Modifier.width(40.dp))
            Column(Modifier.weight(0.58f)) {
                Txt(stringResource(R.string.settings_language).uppercase(), Type.Label)
                Spacer(Modifier.height(8.dp))
                Row {
                    val pinned = AppLocale.get(activity)
                    listOf(null to stringResource(R.string.settings_language_system), AppLocale.BULGARIAN to "Български", AppLocale.ENGLISH to "English").forEach { (code, name) ->
                        val current = pinned == code
                        Action(name + if (current) "  ✓" else "") {
                            if (!current) {
                                AppLocale.set(activity, code)
                                activity.recreate()
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                }

                Spacer(Modifier.height(28.dp))
                var hovered by remember { mutableStateOf<AccentPreset?>(null) }
                val current = AccentPresets.firstOrNull { it.color == Palette.Accent } ?: AccentPresets.first()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Txt(stringResource(R.string.settings_color).uppercase(), Type.Label)
                    Spacer(Modifier.width(12.dp))
                    Txt(presetName(hovered ?: current), Type.Label, color = Palette.Text)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccentPresets.forEach { preset ->
                        Swatch(
                            color = preset.color,
                            selected = preset == current,
                            onFocused = { hovered = if (it) preset else hovered.takeIf { h -> h != preset } },
                            onClick = {
                                Palette.Accent = preset.color
                                graph.session.accentKey = preset.key
                            },
                        )
                    }
                }
            }
        }
    }
    found?.let { UpdateDialog(graph.updater, it, onDismiss = { found = null }) }
}

@Composable
private fun presetName(p: AccentPreset): String = stringResource(
    when (p.key) {
        "mint" -> R.string.color_mint
        "sky" -> R.string.color_sky
        "lavender" -> R.string.color_lavender
        "coral" -> R.string.color_coral
        "amber" -> R.string.color_amber
        "rose" -> R.string.color_rose
        "silver" -> R.string.color_silver
        else -> R.string.color_coool
    },
)

/** Colour dot: grows with a white ring when focused, ✓ when it's the current colour. */
@Composable
private fun Swatch(color: Color, selected: Boolean, onFocused: (Boolean) -> Unit, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { onFocused(focused) }
    Box(
        Modifier
            .size(46.dp)
            .graphicsLayer {
                val s = if (focused) 1.12f else 1f
                scaleX = s
                scaleY = s
            }
            .then(if (focused) Modifier.border(3.dp, Palette.Text, CircleShape) else Modifier)
            .padding(5.dp)
            .background(color, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Txt("✓", Type.Title, color = Palette.OnAccent)
    }
}

@Composable
private fun Info(label: String, value: String) {
    Row(Modifier.padding(vertical = 6.dp)) {
        Txt(label, Type.Body, Modifier.width(170.dp), color = Palette.TextDim)
        Txt(value, Type.Body, Modifier.weight(1f))
    }
}

@Composable
private fun Action(text: String, modifier: Modifier = Modifier, danger: Boolean = false, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(50), idleColor = Palette.Surface) { focused ->
        Txt(
            text,
            Type.Body,
            Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
            maxLines = 1,
            color = when {
                focused -> Palette.OnFocus
                danger -> Palette.Danger
                else -> Palette.Text
            },
        )
    }
}
