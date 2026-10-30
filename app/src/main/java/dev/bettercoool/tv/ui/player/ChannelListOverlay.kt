package dev.bettercoool.tv.ui.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.bettercoool.tv.AppGraph
import dev.bettercoool.tv.R
import dev.bettercoool.tv.data.Channel
import dev.bettercoool.tv.ui.common.Badge
import dev.bettercoool.tv.ui.common.ProgressBar
import dev.bettercoool.tv.ui.common.ReplayIcon
import dev.bettercoool.tv.ui.common.minuteClock
import dev.bettercoool.tv.ui.theme.FocusCard
import dev.bettercoool.tv.ui.theme.Palette
import dev.bettercoool.tv.ui.theme.SnappyScroll
import dev.bettercoool.tv.ui.theme.Txt
import dev.bettercoool.tv.ui.theme.Type
import dev.bettercoool.tv.ui.theme.onBack
import dev.bettercoool.tv.ui.theme.requestFocusSoon

/**
 * Translucent channel list over the video: browse with Up/Down without switching, OK to watch,
 * Right for that channel's guide, Back to close. The playing channel is marked.
 */
@Composable
fun ChannelListOverlay(
    graph: AppGraph,
    channels: List<Channel>,
    playingIndex: Int,
    startIndex: Int,
    onPick: (Int) -> Unit,
    onGuide: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val start = startIndex.coerceIn(0, (channels.size - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
    val focus = remember { FocusRequester() }
    val revision by graph.epg.revision.collectAsState()
    val clock = minuteClock()
    var target by remember { mutableIntStateOf(start) }
    var focused by remember { mutableIntStateOf(start) }
    var jumpKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(jumpKey) {
        if (jumpKey > 0) listState.scrollToItem((target - 3).coerceAtLeast(0))
        focus.requestFocusSoon()
    }

    Box(
        Modifier.fillMaxSize().onBack(onClose).onPreviewKeyEvent { e ->
            // Wrap around: Down on the last channel → first, Up on the first → last.
            val code = e.nativeKeyEvent.keyCode
            val wrapTo = when {
                e.type != KeyEventType.KeyDown || channels.isEmpty() -> null
                code == AndroidKeyEvent.KEYCODE_DPAD_DOWN && focused == channels.lastIndex -> 0
                code == AndroidKeyEvent.KEYCODE_DPAD_UP && focused == 0 -> channels.lastIndex
                else -> null
            }
            if (wrapTo != null) {
                target = wrapTo
                jumpKey++
            }
            wrapTo != null
        },
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(520.dp)
                .background(Palette.Background.copy(alpha = 0.86f))
                .padding(top = 28.dp, start = 28.dp, end = 20.dp),
        ) {
            Txt(stringResource(R.string.channels_title).uppercase(), Type.Label, Modifier.padding(start = 12.dp))
            Spacer(Modifier.height(10.dp))
            Box(Modifier.weight(1f)) {
                SnappyScroll {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                ) {
                    itemsIndexed(channels, key = { _, c -> c.id }) { i, ch ->
                        val now = remember(ch.id, revision, clock) { graph.epg.nowNext(ch.id, clock).first }
                        FocusCard(
                            onClick = { if (ch.playable) onPick(i) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(62.dp)
                                .then(if (i == target) Modifier.focusRequester(focus) else Modifier)
                                .onFocusChanged { if (it.isFocused) focused = i }
                                .onPreviewKeyEvent { e ->
                                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT) {
                                        onGuide(i)
                                        true
                                    } else {
                                        false
                                    }
                                },
                            shape = RoundedCornerShape(10.dp),
                        ) { focused ->
                            val fg = if (focused) Palette.OnFocus else if (ch.playable) Palette.Text else Palette.TextFaint
                            val dim = if (focused) Palette.OnFocus.copy(alpha = 0.7f) else Palette.TextDim
                            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Txt(ch.number.orEmpty(), Type.Label, Modifier.width(34.dp), color = dim)
                                Box(
                                    Modifier.size(width = 54.dp, height = 32.dp).background(Palette.SurfaceHigh, RoundedCornerShape(6.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (ch.logo != null) AsyncImage(model = ch.logo, contentDescription = null, modifier = Modifier.padding(4.dp).fillMaxSize())
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Txt(ch.name, Type.Body, Modifier.weight(1f, fill = false), maxLines = 1, color = fg)
                                        if (i == playingIndex) {
                                            Spacer(Modifier.width(8.dp))
                                            Badge(stringResource(R.string.live), color = if (focused) Palette.OnFocus else Palette.Accent,
                                                textColor = if (focused) Palette.Focus else Palette.OnAccent)
                                        }
                                    }
                                    if (now != null) {
                                        Txt(now.name, Type.Label, maxLines = 1, color = dim)
                                        Spacer(Modifier.height(3.dp))
                                        val span = (now.endTime - now.startTime).coerceAtLeast(1)
                                        ProgressBar((clock - now.startTime).toFloat() / span, Modifier.width(220.dp))
                                    }
                                }
                                if (ch.hasTimeshift) {
                                    Spacer(Modifier.width(8.dp))
                                    ReplayIcon(14.dp, if (focused) Palette.OnFocus else Palette.Accent)
                                }
                                if (ch.isUhd) {
                                    Spacer(Modifier.width(8.dp))
                                    Badge("4K", color = if (focused) Palette.OnFocus else Palette.Accent,
                                        textColor = if (focused) Palette.Focus else Palette.OnAccent)
                                }
                            }
                        }
                    }
                }
                }
            }
            Row(Modifier.padding(start = 12.dp, top = 6.dp, bottom = 18.dp)) {
                Txt(stringResource(R.string.guide_watch), Type.Label)
                Spacer(Modifier.width(18.dp))
                Txt(stringResource(R.string.hint_guide_right), Type.Label)
            }
        }
    }
}
