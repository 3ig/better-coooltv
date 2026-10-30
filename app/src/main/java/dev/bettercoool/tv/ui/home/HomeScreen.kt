@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.bettercoool.tv.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import dev.bettercoool.tv.data.EpgEntry
import dev.bettercoool.tv.ui.common.ProgressBar
import dev.bettercoool.tv.ui.common.ReplayIcon
import dev.bettercoool.tv.ui.common.ReplayPill
import dev.bettercoool.tv.ui.common.hhmm
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
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
import dev.bettercoool.tv.data.Capabilities
import dev.bettercoool.tv.data.Catalog
import dev.bettercoool.tv.data.Channel
import dev.bettercoool.tv.data.EpgStore
import dev.bettercoool.tv.data.Profile
import dev.bettercoool.tv.ui.common.Badge
import dev.bettercoool.tv.ui.common.CategoryOrder
import dev.bettercoool.tv.ui.common.NowNext
import dev.bettercoool.tv.ui.common.NumberEntry
import dev.bettercoool.tv.ui.common.categoryLabel
import dev.bettercoool.tv.ui.common.digitFor
import dev.bettercoool.tv.ui.common.minuteClock
import dev.bettercoool.tv.ui.theme.FocusCard
import dev.bettercoool.tv.ui.theme.Palette
import dev.bettercoool.tv.ui.theme.requestFocusSoon
import dev.bettercoool.tv.ui.theme.onBack
import dev.bettercoool.tv.ui.theme.Txt
import dev.bettercoool.tv.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import dev.bettercoool.tv.ui.theme.SnappyScroll
import kotlinx.coroutines.launch

private const val FILTER_UHD = "__uhd"
private const val FILTER_FAV = "__fav"
private const val FILTER_REPLAY = "__replay"

@Composable
fun HomeScreen(
    graph: AppGraph,
    onPlay: (List<Channel>, Int) -> Unit,
    onGuide: (List<Channel>, Int) -> Unit,
    onSettings: () -> Unit,
) {
    var snapshot by remember { mutableStateOf(graph.catalog.snapshot) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    // Server pushes (channel/profile changes) invalidate the catalog and bump its revision.
    val catalogRevision by graph.catalog.revision.collectAsState()
    var pinFor by remember { mutableStateOf<Profile?>(null) }

    LaunchedEffect(attempt, catalogRevision) {
        graph.catalog.snapshot?.let {
            snapshot = it
            return@LaunchedEffect
        }
        failed = false
        try {
            snapshot = graph.catalog.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }

    // Guide window refresh; ensure() is a no-op while the cached window is still good.
    LaunchedEffect(Unit) {
        while (true) {
            graph.epg.ensure()
            delay(10 * 60_000)
        }
    }

    val openProfile: (Profile) -> Unit = { profile ->
        scope.launch {
            snapshot = null
            snapshot = runCatching { graph.catalog.selectProfile(profile) }.getOrNull()
            failed = snapshot == null
        }
    }
    val selectProfile: (Profile) -> Unit = { profile ->
        if (profile.uuid == snapshot?.profile?.uuid) Unit
        else if (profile.isLocked) pinFor = profile
        else openProfile(profile)
    }

    val s = snapshot
    Box(Modifier.fillMaxSize()) {
        when {
            failed -> Message(stringResource(R.string.home_error), action = stringResource(R.string.home_retry)) { attempt++ }
            s == null -> Message(stringResource(R.string.home_loading))
            s.profile == null -> ProfilePicker(s.profiles, selectProfile)
            else -> ChannelBrowser(graph, s, selectProfile, onPlay, onGuide, onSettings)
        }
        pinFor?.let { p ->
            PinDialog(
                profile = p,
                onSuccess = {
                    pinFor = null
                    openProfile(p)
                },
                onCancel = { pinFor = null },
            )
        }
    }
}

@Composable
private fun ChannelBrowser(
    graph: AppGraph,
    snapshot: Catalog.Snapshot,
    onProfile: (Profile) -> Unit,
    onPlay: (List<Channel>, Int) -> Unit,
    onGuide: (List<Channel>, Int) -> Unit,
    onSettings: () -> Unit,
) {
    val profile = snapshot.profile!!
    val all = snapshot.channels
    var favs by remember(profile.uuid) { mutableStateOf(graph.session.favourites(profile.uuid)) }
    var filter by remember { mutableStateOf<String?>(null) }
    val visible = remember(all, filter, favs) {
        when (filter) {
            null -> all
            FILTER_UHD -> all.filter { it.isUhd }
            FILTER_FAV -> all.filter { it.id in favs }
            FILTER_REPLAY -> all.filter { it.hasTimeshift }
            else -> all.filter { it.category == filter }
        }
    }
    val filters = remember(all, favs.isEmpty()) {
        val present = all.mapNotNull { it.category }.toSet()
        listOf<String?>(null) + (if (favs.isNotEmpty()) listOf(FILTER_FAV) else emptyList()) +
            (if (all.any { it.hasTimeshift }) listOf(FILTER_REPLAY) else emptyList()) +
            CategoryOrder.filter { it in present } +
            (present - CategoryOrder.toSet()).sorted() +
            (if (all.any { it.isUhd }) listOf(FILTER_UHD) else emptyList())
    }

    val startIndex = remember(all) {
        all.indexOfFirst { it.id == graph.session.lastChannel(profile.uuid) }.coerceAtLeast(0)
    }
    var focusedIndex by remember { mutableIntStateOf(startIndex) }
    var focusTarget by remember { mutableIntStateOf(startIndex) }
    var jumpKey by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 3).coerceAtLeast(0))
    val targetFocus = remember { FocusRequester() }
    val filterFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(jumpKey) {
        if (visible.isEmpty()) return@LaunchedEffect
        listState.scrollToItem((focusTarget - 3).coerceAtLeast(0))
        withFrameNanos { }
        targetFocus.requestFocusSoon()
    }

    // Number entry: type a channel number anywhere on this screen.
    var digits by remember { mutableStateOf("") }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1_200)
        val inVisible = visible.indexOfFirst { it.number == digits }
        if (inVisible >= 0) {
            focusTarget = inVisible
        } else {
            val inAll = all.indexOfFirst { it.number == digits }
            if (inAll >= 0) {
                filter = null
                focusTarget = inAll
            }
        }
        jumpKey++
        digits = ""
    }

    val epgRevision by graph.epg.revision.collectAsState()
    val clock = minuteClock()
    val openGuide = { onGuide(visible, focusedIndex.coerceIn(0, (visible.size - 1).coerceAtLeast(0))) }
    var swallowOkUp by remember { mutableStateOf(false) }
    var listFocused by remember { mutableStateOf(false) }
    val toggleFavourite = {
        visible.getOrNull(focusedIndex)?.let {
            graph.session.toggleFavourite(profile.uuid, it.id)
            favs = graph.session.favourites(profile.uuid)
            if (filter == FILTER_FAV && favs.isEmpty()) filter = null
        }
    }

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            val code = e.nativeKeyEvent.keyCode
            val d = digitFor(code)
            val ok = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER
            when {
                // The list wraps: Down on the last channel → first, Up on the first → last.
                listFocused && e.type == KeyEventType.KeyDown && visible.isNotEmpty() &&
                    ((code == android.view.KeyEvent.KEYCODE_DPAD_DOWN && focusedIndex == visible.lastIndex) ||
                        (code == android.view.KeyEvent.KEYCODE_DPAD_UP && focusedIndex == 0)) -> {
                    focusTarget = if (focusedIndex == 0 && code == android.view.KeyEvent.KEYCODE_DPAD_UP) visible.lastIndex else 0
                    jumpKey++
                    true
                }
                // Hold OK on a channel = favourite (remotes without MENU). Its release must not also play.
                ok && e.type == KeyEventType.KeyUp && swallowOkUp -> {
                    swallowOkUp = false
                    true
                }
                ok && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0 -> {
                    if (e.nativeKeyEvent.repeatCount == 1 && focusedIndex in visible.indices) {
                        toggleFavourite()
                        swallowOkUp = true
                    }
                    swallowOkUp
                }
                e.type != KeyEventType.KeyDown -> false
                d != null -> {
                    if (digits.length < 4) digits += d
                    true
                }
                code == android.view.KeyEvent.KEYCODE_GUIDE -> {
                    openGuide()
                    true
                }
                code == android.view.KeyEvent.KEYCODE_MENU -> {
                    toggleFavourite()
                    true
                }
                else -> false
            }
        },
    ) {
        Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 22.dp)) {
            TopBar(snapshot, clock, onProfile, onSettings, openGuide)
            Spacer(Modifier.height(16.dp))
            FilterRow(filters, filter, filterFocus) {
                filter = it
                focusedIndex = 0
                focusTarget = 0
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxSize()) {
                SnappyScroll {
                    LazyColumn(
                        // Back in the list jumps to the top controls; Back there leaves the app.
                        modifier = Modifier
                        .width(500.dp)
                        .fillMaxHeight()
                        // Up/Down never leave the list (it wraps instead); Right goes to the top menu.
                        .focusProperties {
                            exit = { direction ->
                                when (direction) {
                                    FocusDirection.Up, FocusDirection.Down -> FocusRequester.Cancel
                                    FocusDirection.Right -> filterFocus
                                    else -> FocusRequester.Default
                                }
                            }
                        }
                        .focusGroup()
                        .onFocusChanged { listFocused = it.hasFocus }
                        .onBack {
                            scope.launch {
                                listState.scrollToItem(0)
                                filterFocus.requestFocusSoon()
                            }
                        },
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(bottom = 32.dp),
                    ) {
                        itemsIndexed(visible, key = { _, c -> c.id }) { index, channel ->
                            ChannelRow(
                                channel = channel,
                                isFavourite = channel.id in favs,
                                now = remember(channel.id, epgRevision, clock) { graph.epg.nowNext(channel.id, clock).first },
                                clock = clock,
                                modifier = Modifier
                                    .then(if (index == focusTarget) Modifier.focusRequester(targetFocus) else Modifier)
                                    .onFocusChanged { if (it.isFocused) focusedIndex = index },
                                onClick = {
                                    if (channel.playable) {
                                        graph.session.setLastChannel(profile.uuid, channel.id)
                                        onPlay(visible, index)
                                    }
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(44.dp))
                // Reads the highlight via a lambda so moving it doesn't recompose this whole screen.
                DebouncedPreview(visible, { focusedIndex }, graph.epg, epgRevision, clock, Modifier.weight(1f).fillMaxHeight())
            }
        }

        if (digits.isNotEmpty()) {
            NumberEntry(digits, all.firstOrNull { it.number == digits }?.name, Modifier.align(Alignment.TopEnd).padding(40.dp))
        }
    }
}

/** Lime rounded square with a play mark — same motif as the launcher icon. */
@Composable
private fun BrandMark(size: Dp = 34.dp) {
    Box(Modifier.size(size).background(Palette.Accent, RoundedCornerShape(size / 3.4f))) {
        Canvas(Modifier.fillMaxSize()) {
            val s = this.size.minDimension
            val path = Path().apply {
                moveTo(s * 0.38f, s * 0.28f)
                lineTo(s * 0.74f, s * 0.5f)
                lineTo(s * 0.38f, s * 0.72f)
                close()
            }
            drawPath(path, Palette.OnAccent)
        }
    }
}

@Composable
private fun TopBar(
    snapshot: Catalog.Snapshot,
    clock: Long,
    onProfile: (Profile) -> Unit,
    onSettings: () -> Unit,
    onGuide: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BrandMark()
        Spacer(Modifier.width(12.dp))
        Column {
            Txt("coool tv", Type.Title.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold))
            Txt(stringResource(R.string.home_channels, snapshot.channels.size), Type.Label)
        }
        Spacer(Modifier.weight(1f))
        Txt(hhmm(clock), Type.Title.copy(color = Palette.TextDim))
        Spacer(Modifier.width(20.dp))
        Chip(stringResource(R.string.guide), selected = false, onClick = onGuide)
        Spacer(Modifier.width(6.dp))
        if (snapshot.profiles.size > 1) {
            snapshot.profiles.forEach { p ->
                Chip(p.name + if (p.isLocked) " 🔒" else "", selected = p.uuid == snapshot.profile?.uuid) { onProfile(p) }
                Spacer(Modifier.width(6.dp))
            }
        }
        Chip("⚙", selected = false, onClick = onSettings)
    }
}

@Composable
private fun FilterRow(filters: List<String?>, selected: String?, focus: FocusRequester, onSelect: (String?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(filters, key = { it ?: "" }) { f ->
            val label = when (f) {
                null -> stringResource(R.string.cat_all)
                FILTER_UHD -> stringResource(R.string.cat_uhd)
                FILTER_FAV -> stringResource(R.string.cat_favourites)
                FILTER_REPLAY -> stringResource(R.string.replay_filter)
                else -> categoryLabel(f)
            }
            Chip(
                label,
                selected = f == selected,
                modifier = if (f == selected) Modifier.focusRequester(focus) else Modifier,
                icon = if (f == FILTER_REPLAY) ({ color -> ReplayIcon(12.dp, color) }) else null,
                onClick = { onSelect(f) },
            )
        }
    }
}

@Composable
private fun Chip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    icon: (@Composable (Color) -> Unit)? = null,
    onClick: () -> Unit,
) {
    FocusCard(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        idleColor = if (selected) Palette.Accent.copy(alpha = 0.16f) else Palette.Surface,
    ) { focused ->
        val color = when {
            focused -> Palette.OnFocus
            selected -> Palette.Accent
            else -> Palette.TextDim
        }
        Row(Modifier.padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                icon(color)
                Spacer(Modifier.width(6.dp))
            }
            Txt(text, Type.Label, color = color)
        }
    }
}

@Composable
private fun ChannelRow(channel: Channel, isFavourite: Boolean, now: EpgEntry?, clock: Long, modifier: Modifier, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = modifier.fillMaxWidth().height(64.dp)) { focused ->
        val main = when {
            focused -> Palette.OnFocus
            channel.playable -> Palette.Text
            else -> Palette.TextFaint
        }
        val sub = if (focused) Palette.OnFocus.copy(alpha = 0.7f) else Palette.TextDim
        val mark = if (focused) Palette.OnFocus else Palette.Accent
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(channel.number.orEmpty(), Type.Label, Modifier.width(36.dp), color = sub)
            Box(
                Modifier.size(width = 64.dp, height = 38.dp).background(Palette.SurfaceHigh, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logo != null) {
                    AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.padding(5.dp).fillMaxSize())
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(channel.name, Type.Body, maxLines = 1, color = main)
                if (now != null) {
                    Txt(now.name, Type.Label, maxLines = 1, color = sub)
                    Spacer(Modifier.height(4.dp))
                    val span = (now.endTime - now.startTime).coerceAtLeast(1)
                    ProgressBar((clock - now.startTime).toFloat() / span, Modifier.width(200.dp))
                }
            }
            if (isFavourite) {
                Spacer(Modifier.width(8.dp))
                Txt("★", Type.Body, color = mark)
            }
            if (channel.hasTimeshift) {
                Spacer(Modifier.width(8.dp))
                ReplayIcon(14.dp, mark)
            }
            if (channel.isUhd) {
                Spacer(Modifier.width(8.dp))
                Badge(
                    "4K",
                    color = if (focused) Palette.OnFocus else if (Capabilities.uhdHevcMain10) Palette.Accent else Palette.TextFaint,
                    textColor = if (focused) Palette.Focus else Palette.OnAccent,
                )
            }
        }
    }
}

/**
 * Detail panel that follows the highlight only once it settles (~120 ms), so holding Down through
 * the list doesn't re-layout the description and schedule on every step.
 */
@Composable
private fun DebouncedPreview(
    visible: List<Channel>,
    index: () -> Int,
    epg: EpgStore,
    epgRevision: Int,
    clock: Long,
    modifier: Modifier,
) {
    val shown by produceState(Snapshot.withoutReadObservation(index), visible) {
        snapshotFlow(index).collectLatest {
            delay(120)
            value = it
        }
    }
    Preview(visible.getOrNull(shown), epg, epgRevision, clock, modifier)
}

@Composable
private fun Preview(channel: Channel?, epg: EpgStore, epgRevision: Int, clock: Long, modifier: Modifier) {
    Column(modifier) {
        if (channel == null) return@Column
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 150.dp, height = 84.dp).background(Palette.Surface, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logo != null) {
                    AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.padding(14.dp).fillMaxSize())
                }
            }
            Spacer(Modifier.width(20.dp))
            Column {
                Txt(listOfNotNull(channel.number, channel.name).joinToString("  "), Type.Display.copy(fontSize = 28.sp), maxLines = 1)
                channel.category?.let {
                    Spacer(Modifier.height(4.dp))
                    Txt(categoryLabel(it), Type.Label)
                }
            }
        }
        if (channel.isUhd || channel.hasTimeshift) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (channel.isUhd) {
                    Badge("4K")
                    Spacer(Modifier.width(8.dp))
                }
                if (channel.hasTimeshift) ReplayPill(stringResource(R.string.replay_available))
            }
        }
        when {
            !channel.includedInPlan -> {
                Spacer(Modifier.height(10.dp))
                Txt(stringResource(R.string.not_in_plan), Type.BodyDim, color = Palette.Danger)
            }
            channel.isUhd && !Capabilities.uhdHevcMain10 -> {
                Spacer(Modifier.height(10.dp))
                Txt(stringResource(R.string.uhd_unsupported), Type.BodyDim, color = Palette.Danger)
            }
        }

        Spacer(Modifier.height(26.dp))
        val programmes = remember(channel.id, epgRevision) { epg.programmes(channel.id) }
        val now = programmes.firstOrNull { it.startTime <= clock && clock < it.endTime }
        val later = programmes.filter { it.startTime >= (now?.endTime ?: clock) }.take(2)
        if (now != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(stringResource(R.string.whats_on).uppercase(), Type.Label, color = Palette.Accent)
                Spacer(Modifier.width(10.dp))
                Txt("${hhmm(now.startTime)} – ${hhmm(now.endTime)}", Type.Label)
            }
            Spacer(Modifier.height(6.dp))
            Txt(now.name, Type.Display.copy(fontSize = 26.sp), maxLines = 1)
            Spacer(Modifier.height(10.dp))
            ProgressBar((clock - now.startTime).toFloat() / (now.endTime - now.startTime).coerceAtLeast(1), Modifier.width(380.dp))
            if (!now.description.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Txt(now.description, Type.BodyDim, maxLines = 2)
            }
        } else {
            Txt(stringResource(R.string.epg_none), Type.BodyDim)
        }
        if (later.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Txt(stringResource(R.string.later_today).uppercase(), Type.Label)
            Spacer(Modifier.height(6.dp))
            later.forEach { p ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Txt(hhmm(p.startTime), Type.Body.copy(color = Palette.TextDim), Modifier.width(60.dp))
                    Txt(p.name, Type.Body, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.padding(bottom = 24.dp)) {
            Txt(stringResource(R.string.hint_menu_right), Type.Label, color = Palette.Accent)
            Spacer(Modifier.width(22.dp))
            Txt(stringResource(R.string.hint_menu_fav), Type.Label, color = Palette.TextFaint)
        }
    }
}

@Composable
private fun ProfilePicker(profiles: List<Profile>, onSelect: (Profile) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocusSoon() }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            itemsIndexed(profiles, key = { _, p -> p.uuid }) { i, p ->
                FocusCard(
                    onClick = { onSelect(p) },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    shape = RoundedCornerShape(20.dp),
                    idleColor = Palette.Surface,
                ) { focused ->
                    Column(Modifier.size(width = 180.dp, height = 200.dp).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(110.dp).background(Palette.SurfaceHigh, RoundedCornerShape(55.dp)), contentAlignment = Alignment.Center) {
                            val avatar = p.whiteImageUrl ?: p.imageUrl
                            if (avatar != null) AsyncImage(model = avatar, contentDescription = null, modifier = Modifier.padding(22.dp).fillMaxSize())
                            else Txt(p.name.take(1).uppercase(), Type.Display)
                        }
                        Spacer(Modifier.height(14.dp))
                        Txt(p.name, Type.Title, maxLines = 1, color = if (focused) Palette.OnFocus else Palette.Text)
                        if (p.isLocked) Txt(stringResource(R.string.locked), Type.Label)
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, action: String? = null, onAction: () -> Unit = {}) {
    val focus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Txt(text, Type.Title)
        if (action != null) {
            LaunchedEffect(Unit) { focus.requestFocusSoon() }
            Spacer(Modifier.height(20.dp))
            FocusCard(onClick = onAction, modifier = Modifier.focusRequester(focus), shape = RoundedCornerShape(50), idleColor = Palette.Surface) { focused ->
                Txt(action, Type.Body, Modifier.padding(horizontal = 24.dp, vertical = 10.dp), color = if (focused) Palette.OnFocus else Palette.Text)
            }
        }
    }
}
