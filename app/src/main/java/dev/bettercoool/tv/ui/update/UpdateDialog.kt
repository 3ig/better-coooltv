package dev.bettercoool.tv.ui.update

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.bettercoool.tv.R
import dev.bettercoool.tv.data.Updater
import dev.bettercoool.tv.ui.common.ProgressBar
import dev.bettercoool.tv.ui.theme.FocusCard
import dev.bettercoool.tv.ui.theme.Palette
import dev.bettercoool.tv.ui.theme.Txt
import dev.bettercoool.tv.ui.theme.Type
import dev.bettercoool.tv.ui.theme.onBack
import dev.bettercoool.tv.ui.theme.requestFocusSoon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface Phase {
    data object Offer : Phase
    data class Downloading(val progress: Float) : Phase
    data object NeedsPermission : Phase
    data object Installing : Phase
    data object Failed : Phase
}

/** "Version X is available": changelog, then download + hand off to Android's installer. */
@Composable
fun UpdateDialog(updater: Updater, release: Updater.Release, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<Phase>(Phase.Offer) }
    val notes = remember(release) { Updater.plainNotes(release.notes) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusSoon() }

    fun start() {
        if (!updater.canInstall()) {
            phase = Phase.NeedsPermission
            updater.openInstallPermissionSettings()
            return
        }
        scope.launch {
            phase = Phase.Downloading(0f)
            try {
                val apk = updater.download(release) { phase = Phase.Downloading(it) }
                phase = Phase.Installing
                updater.install(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Updater", "Update failed", e)
                phase = Phase.Failed
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Palette.Scrim).onBack { if (phase !is Phase.Downloading) onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = 640.dp).background(Palette.Surface, RoundedCornerShape(20.dp)).padding(36.dp)) {
            Txt(stringResource(R.string.update_title, release.version), Type.Title)
            Spacer(Modifier.height(4.dp))
            Txt(stringResource(R.string.update_current, updater.currentVersion), Type.Label)
            if (notes.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Txt(notes, Type.BodyDim, maxLines = 10)
            }
            Spacer(Modifier.height(20.dp))
            when (val p = phase) {
                is Phase.Downloading -> {
                    Txt(stringResource(R.string.update_downloading, (p.progress * 100).toInt()), Type.Label, color = Palette.Text)
                    Spacer(Modifier.height(8.dp))
                    ProgressBar(p.progress, Modifier.width(400.dp))
                }
                Phase.Installing -> Txt(stringResource(R.string.update_installing), Type.Label, color = Palette.Accent)
                Phase.NeedsPermission -> Txt(stringResource(R.string.update_allow), Type.Label, color = Palette.Accent)
                Phase.Failed -> Txt(stringResource(R.string.update_failed), Type.Label, color = Palette.Danger)
                Phase.Offer -> Unit
            }
            if (phase !is Phase.Downloading) {
                Spacer(Modifier.height(20.dp))
                Row {
                    Button(stringResource(R.string.update_now), Modifier.focusRequester(focus)) { start() }
                    Spacer(Modifier.width(10.dp))
                    Button(stringResource(R.string.update_later), onClick = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun Button(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(50), idleColor = Palette.SurfaceHigh) { focused ->
        Txt(text, Type.Body, Modifier.padding(horizontal = 24.dp, vertical = 11.dp), color = if (focused) Palette.OnFocus else Palette.Text)
    }
}
