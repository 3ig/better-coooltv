package dev.bettercoool.tv.ui.player

import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import dev.bettercoool.tv.R
import dev.bettercoool.tv.ui.theme.FocusCard
import dev.bettercoool.tv.ui.theme.onBack
import dev.bettercoool.tv.ui.theme.requestFocusSoon
import dev.bettercoool.tv.ui.theme.Palette
import dev.bettercoool.tv.ui.theme.Txt
import dev.bettercoool.tv.ui.theme.Type
import java.util.Locale

/**
 * SurfaceView sized for the picture mode: 0 fit (letterbox), 1 fill (crop), 2 stretch.
 * [aspect] is the display aspect ratio of the video (0 until known).
 */
@Composable
fun VideoSurface(player: ExoPlayer, aspect: Float, mode: Int, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        val boxW = constraints.maxWidth.toFloat()
        val boxH = constraints.maxHeight.toFloat()
        val screen = boxW / boxH
        val (w, h) = when {
            mode == 2 || aspect <= 0f -> boxW to boxH
            mode == 0 -> if (aspect > screen) boxW to boxW / aspect else boxH * aspect to boxH
            else -> if (aspect > screen) boxH * aspect to boxH else boxW to boxW / aspect
        }
        val density = LocalDensity.current
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).also { player.setVideoSurfaceView(it) } },
            modifier = with(density) { Modifier.requiredSize(w.toDp(), h.toDp()) },
        )
    }
}

private val subtitleStyle = TextStyle(
    color = Color.White,
    fontSize = 26.sp,
    fontWeight = FontWeight.Medium,
    textAlign = TextAlign.Center,
    lineHeight = 34.sp,
)

/** Renders text and bitmap (DVB) cues near the bottom of the picture. */
@Composable
fun Subtitles(cues: List<Cue>, modifier: Modifier = Modifier) {
    if (cues.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(horizontal = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        cues.forEach { cue ->
            cue.text?.let {
                BasicText(
                    it.toString(),
                    Modifier.background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
                    style = subtitleStyle,
                )
            }
            cue.bitmap?.let { bmp ->
                val fraction = if (cue.size != Cue.DIMEN_UNSET && cue.size > 0f) cue.size else 0.6f
                Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxWidth(fraction))
            }
        }
    }
}

/** DVR still that falls back to the `-preview.jpg` variant on error. */
@Composable
fun Thumbnail(url: (preview: Boolean) -> String?, modifier: Modifier = Modifier) {
    var preview by remember { mutableStateOf(false) }
    val model = url(preview) ?: return
    AsyncImage(
        model = model,
        contentDescription = null,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Surface),
        onError = { if (!preview) preview = true },
    )
}

private sealed interface MenuRow {
    data class Header(val text: String) : MenuRow
    data class Option(val text: String, val selected: Boolean, val onClick: () -> Unit) : MenuRow
}

fun trackLabel(format: Format, n: Int, fallback: (Int) -> String): String {
    val name = format.label
        ?: format.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }
            ?.replaceFirstChar { it.uppercase() }
        ?: fallback(n)
    val extra = when {
        format.channelCount >= 6 -> "5.1"
        else -> null
    }
    return listOfNotNull(name, extra).joinToString(" · ")
}

/** Right-hand drawer: favourite, audio, subtitles, picture. */
@Composable
fun PlayerMenu(
    tracks: Tracks,
    isFavourite: Boolean,
    pictureMode: Int,
    onToggleFavourite: () -> Unit,
    onAudio: (Tracks.Group, Int) -> Unit,
    onText: (Tracks.Group?, Int) -> Unit,
    onPicture: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val trackName: (Int) -> String = { ctx.getString(R.string.menu_track, it) }
    val rows = buildList {
        add(MenuRow.Option(ctx.getString(if (isFavourite) R.string.fav_remove else R.string.fav_add), false, onToggleFavourite))

        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val audioOptions = audio.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
        if (audioOptions.size > 1) {
            add(MenuRow.Header(ctx.getString(R.string.menu_audio)))
            audioOptions.forEachIndexed { n, (g, i) ->
                add(MenuRow.Option(trackLabel(g.getTrackFormat(i), n + 1, trackName), g.isTrackSelected(i)) { onAudio(g, i) })
            }
        }

        val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val textOptions = text.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
        if (textOptions.isNotEmpty()) {
            add(MenuRow.Header(ctx.getString(R.string.menu_subtitles)))
            add(MenuRow.Option(ctx.getString(R.string.menu_off), textOptions.none { (g, i) -> g.isTrackSelected(i) }) { onText(null, 0) })
            textOptions.forEachIndexed { n, (g, i) ->
                add(MenuRow.Option(trackLabel(g.getTrackFormat(i), n + 1, trackName), g.isTrackSelected(i)) { onText(g, i) })
            }
        }

        add(MenuRow.Header(ctx.getString(R.string.menu_picture)))
        listOf(R.string.menu_fit, R.string.menu_fill, R.string.menu_stretch).forEachIndexed { mode, label ->
            add(MenuRow.Option(ctx.getString(label), pictureMode == mode) { onPicture(mode) })
        }
    }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusSoon() }

    Box(modifier.fillMaxHeight().width(380.dp).background(Palette.Background.copy(alpha = 0.95f)).onBack(onClose)) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(rows) { index, row ->
                when (row) {
                    is MenuRow.Header -> Txt(row.text.uppercase(), Type.Label, Modifier.padding(top = 18.dp, bottom = 6.dp, start = 12.dp))
                    is MenuRow.Option -> FocusCard(
                        onClick = row.onClick,
                        modifier = Modifier.fillMaxWidth().then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                        shape = RoundedCornerShape(10.dp),
                    ) { focused ->
                        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Txt(row.text, Type.Body, Modifier.weight(1f), maxLines = 1, color = if (focused) Palette.OnFocus else Palette.Text)
                            if (row.selected) Txt("✓", Type.Body, color = if (focused) Palette.OnFocus else Palette.Accent)
                        }
                    }
                }
            }
        }
    }
}
