package dev.bettercoool.tv.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.bettercoool.tv.AppGraph
import dev.bettercoool.tv.R
import dev.bettercoool.tv.ui.theme.Palette
import dev.bettercoool.tv.ui.theme.Txt
import dev.bettercoool.tv.ui.theme.Type
import kotlinx.coroutines.delay

private const val PAIR_HOST = "coool.tv/device/add"
private const val PAIR_URL = "https://www.coool.tv/device/add?shortCode="

@Composable
fun PairingScreen(graph: AppGraph, onPaired: () -> Unit) {
    val state by produceState<PairingState>(PairingState.Loading) {
        runPairing(graph) { value = it }
    }
    LaunchedEffect(state) { if (state == PairingState.Paired) onPaired() }

    val code = (state as? PairingState.ShowCode)

    Row(
        Modifier.fillMaxSize().padding(horizontal = 80.dp, vertical = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Txt(stringResource(R.string.app_name).uppercase(), Type.Label, color = Palette.Accent)
            Spacer(Modifier.height(10.dp))
            Txt(stringResource(R.string.pair_title), Type.Display)
            Spacer(Modifier.height(36.dp))

            Step(1, stringResource(R.string.pair_step1, PAIR_HOST))
            Spacer(Modifier.height(18.dp))
            Step(2, stringResource(R.string.pair_step2))
            Spacer(Modifier.height(18.dp))
            Step(3, stringResource(R.string.pair_step3))
            Spacer(Modifier.height(14.dp))

            Box(
                Modifier.padding(start = 46.dp)
                    .background(Palette.Surface, RoundedCornerShape(14.dp))
                    .padding(horizontal = 28.dp, vertical = 14.dp),
            ) {
                when (state) {
                    is PairingState.ShowCode -> Txt(code!!.code, Type.Code)
                    PairingState.Retrying -> Txt(stringResource(R.string.pair_error), Type.BodyDim)
                    PairingState.Connecting, PairingState.Paired -> Txt(stringResource(R.string.pair_connecting), Type.BodyDim)
                    PairingState.Loading -> Txt(stringResource(R.string.pair_loading), Type.BodyDim)
                }
            }
            if (code != null) {
                Spacer(Modifier.height(12.dp))
                Countdown(code.expiresAtMillis, Modifier.padding(start = 46.dp))
            }
        }

        Spacer(Modifier.width(48.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(280.dp).background(Color.White, RoundedCornerShape(20.dp)).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (code != null) QrCode(PAIR_URL + code.code, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(14.dp))
            Txt(PAIR_HOST, Type.BodyDim)
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).background(Palette.SurfaceHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Txt(n.toString(), Type.Body) }
        Spacer(Modifier.width(16.dp))
        Txt(text, Type.Body)
    }
}

/** Only this small text recomposes every second. */
@Composable
private fun Countdown(expiresAtMillis: Long, modifier: Modifier = Modifier) {
    val remaining by produceState(expiresAtMillis - System.currentTimeMillis(), expiresAtMillis) {
        while (true) {
            value = expiresAtMillis - System.currentTimeMillis()
            delay(1_000)
        }
    }
    val secs = (remaining / 1000).coerceAtLeast(0)
    Txt(stringResource(R.string.pair_expires, "%d:%02d".format(secs / 60, secs % 60)), Type.Label, modifier)
}
