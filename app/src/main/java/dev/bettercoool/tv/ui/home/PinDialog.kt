package dev.bettercoool.tv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.bettercoool.tv.R
import dev.bettercoool.tv.data.Profile
import dev.bettercoool.tv.ui.common.digitFor
import dev.bettercoool.tv.ui.theme.FocusCard
import dev.bettercoool.tv.ui.theme.Palette
import dev.bettercoool.tv.ui.theme.onBack
import dev.bettercoool.tv.ui.theme.requestFocusSoon
import dev.bettercoool.tv.ui.theme.Txt
import dev.bettercoool.tv.ui.theme.Type
import kotlinx.coroutines.delay

/**
 * 4-digit PIN for a protected profile. Digits come from the remote's number keys or the on-screen
 * pad (for remotes without digits). Checked locally against `pinCode`, as the original app does.
 */
@Composable
fun PinDialog(profile: Profile, onSuccess: () -> Unit, onCancel: () -> Unit) {
    var entered by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) { first.requestFocusSoon() }
    BackHandler(onBack = onCancel)

    fun type(c: Char) {
        if (entered.length < 4) entered += c
        wrong = false
    }

    LaunchedEffect(entered) {
        if (entered.length < 4) return@LaunchedEffect
        delay(150)
        if (entered == profile.pinCode) {
            onSuccess()
        } else {
            wrong = true
            entered = ""
        }
    }

    Box(
        Modifier.fillMaxSize().background(Palette.Scrim).onBack(onCancel).onPreviewKeyEvent { e ->
            val d = digitFor(e.nativeKeyEvent.keyCode)
            if (d != null && e.type == KeyEventType.KeyDown) {
                type(d)
                true
            } else {
                false
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.background(Palette.Surface, RoundedCornerShape(24.dp)).padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Txt(stringResource(R.string.pin_title, profile.name), Type.Title)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(4) { i ->
                    Box(
                        Modifier.size(16.dp).background(
                            if (i < entered.length) Palette.Text else Palette.SurfaceHigh,
                            CircleShape,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Txt(if (wrong) stringResource(R.string.pin_wrong) else " ", Type.Label, color = Palette.Danger)
            Spacer(Modifier.height(10.dp))
            val keys = listOf("123", "456", "789", "⌫0")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                keys.forEachIndexed { r, line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        line.forEach { k ->
                            FocusCard(
                                onClick = { if (k == '⌫') entered = entered.dropLast(1) else type(k) },
                                modifier = if (r == 0 && k == '1') Modifier.focusRequester(first) else Modifier,
                                shape = RoundedCornerShape(12.dp),
                                idleColor = Palette.SurfaceHigh,
                            ) { focused ->
                                Box(Modifier.size(64.dp, 48.dp), contentAlignment = Alignment.Center) {
                                    Txt(k.toString(), Type.Title, color = if (focused) Palette.OnFocus else Palette.Text)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
