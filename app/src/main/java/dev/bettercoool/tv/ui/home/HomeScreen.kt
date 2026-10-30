package dev.bettercoool.tv.ui.home

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
import kotlinx.coroutines.launch

private const val FILTER_UHD = "__uhd"
private const val FILTER_FAV = "__fav"

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
            else -> all.filter { it.category == filter }
        }
    }
    val filters = remember(all, favs.isEmpty()) {
        val present = all.mapNotNull { it.category }.toSet()
        listOf<String?>(null) + (if (favs.isNotEmpty()) listOf(FILTER_FAV) else emptyList()) +
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

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            val code = e.nativeKeyEvent.keyCode
            val d = digitFor(code)
            when {
                e.type != KeyEventType.KeyDown -> false
                d != null -> {
                    if (digits.length < 4) digits += d
                    true
                }
                code == android.view.KeyEvent.KEYCODE_GUIDE -> {
                    onGuide(visible, focusedIndex.coerceIn(0, (visible.size - 1).coerceAtLeast(0)))
                    true
                }
                code == android.view.KeyEvent.KEYCODE_MENU -> {
                    visible.getOrNull(focusedIndex)?.let {
                        graph.session.toggleFavourite(profile.uuid, it.id)
                        favs = graph.session.favourites(profile.uuid)
                        if (filter == FILTER_FAV && favs.isEmpty()) filter = null
                    }
                    true
                }
                else -> false
            }
        },
    ) {
        Row(Modifier.fillMaxSize().padding(start = 48.dp, top = 32.dp, end = 48.dp)) {
            Column(Modifier.width(440.dp).fillMaxHeight()) {
                Header(snapshot, onProfile, onSettings = onSettings, onGuide = { onGuide(visible, focusedIndex.coerceIn(0, (visible.size - 1).coerceAtLeast(0))) })
                Spacer(Modifier.height(14.dp))
                FilterRow(filters, filter, filterFocus) {
                    filter = it
                    focusedIndex = 0
                    focusTarget = 0
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    // Back in the list jumps to the top controls; Back there leaves the app.
                    modifier = Modifier.onBack {
                        scope.launch {
                            listState.scrollToItem(0)
                            filterFocus.requestFocusSoon()
                        }
                    },
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(bottom = 36.dp),
                ) {
                    itemsIndexed(visible, key = { _, c -> c.id }) { index, channel ->
                        ChannelRow(
                            channel = channel,
                            isFavourite = channel.id in favs,
                            nowTitle = remember(channel.id, epgRevision, clock) {
                                graph.epg.nowNext(channel.id, clock).first?.name
                            },
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
            Spacer(Modifier.width(56.dp))
            Preview(visible.getOrNull(focusedIndex), graph.epg, epgRevision, clock, Modifier.weight(1f).fillMaxHeight())
        }

        if (digits.isNotEmpty()) {
            NumberEntry(digits, all.firstOrNull { it.number == digits }?.name, Modifier.align(Alignment.TopEnd).padding(40.dp))
        }
    }
}

@Composable
private fun Header(snapshot: Catalog.Snapshot, onProfile: (Profile) -> Unit, onSettings: () -> Unit, onGuide: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(stringResource(R.string.app_name).uppercase(), Type.Label, color = Palette.Accent)
            Spacer(Modifier.height(2.dp))
            Txt(stringResource(R.string.home_channels, snapshot.channels.size), Type.Label)
        }
        Chip(stringResource(R.string.guide), selected = false, onClick = onGuide)
        Spacer(Modifier.width(6.dp))
        Chip("⚙", selected = false, onClick = onSettings)
        Spacer(Modifier.width(6.dp))
        if (snapshot.profiles.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(snapshot.profiles, key = { it.uuid }) { p ->
                    val current = p.uuid == snapshot.profile?.uuid
                    Chip(p.name + if (p.isLocked) " 🔒" else "", selected = current) { onProfile(p) }
                }
            }
        }
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
                else -> categoryLabel(f)
            }
            Chip(label, selected = f == selected, Modifier.then(if (f == selected) Modifier.focusRequester(focus) else Modifier)) { onSelect(f) }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusCard(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        idleColor = if (selected) Palette.SurfaceHigh else Palette.Surface,
    ) { focused ->
        Txt(
            text,
            Type.Label,
            Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            color = when {
                focused -> Palette.OnFocus
                selected -> Palette.Text
                else -> Palette.TextDim
            },
        )
    }
}

@Composable
private fun ChannelRow(channel: Channel, isFavourite: Boolean, nowTitle: String?, modifier: Modifier, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = modifier.fillMaxWidth().height(60.dp)) { focused ->
        val main = when {
            focused -> Palette.OnFocus
            channel.playable -> Palette.Text
            else -> Palette.TextFaint
        }
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(channel.number.orEmpty(), Type.Label, Modifier.width(36.dp), color = if (focused) Palette.OnFocus else Palette.TextDim)
            Box(
                Modifier.size(width = 60.dp, height = 36.dp).background(Palette.SurfaceHigh, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logo != null) {
                    AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.padding(4.dp).fillMaxSize())
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(channel.name, Type.Body, maxLines = 1, color = main)
                if (nowTitle != null) {
                    Txt(nowTitle, Type.Label, maxLines = 1, color = if (focused) Palette.OnFocus.copy(alpha = 0.7f) else Palette.TextFaint)
                }
            }
            if (isFavourite) {
                Spacer(Modifier.width(8.dp))
                Txt("★", Type.Body, color = if (focused) Palette.OnFocus else Palette.Accent)
            }
            if (channel.isUhd) {
                Spacer(Modifier.width(8.dp))
                Badge("4K", color = if (Capabilities.uhdHevcMain10) Palette.Accent else Palette.TextFaint)
            }
        }
    }
}

@Composable
private fun Preview(channel: Channel?, epg: EpgStore, epgRevision: Int, clock: Long, modifier: Modifier) {
    Column(modifier.padding(top = 56.dp)) {
        if (channel == null) return@Column
        Box(
            Modifier.size(width = 200.dp, height = 112.dp).background(Palette.Surface, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.logo != null) {
                AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.padding(18.dp).fillMaxSize())
            }
        }
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(listOfNotNull(channel.number, channel.name).joinToString("  "), Type.Display, Modifier.weight(1f, fill = false), maxLines = 1)
            if (channel.isUhd) {
                Spacer(Modifier.width(12.dp))
                Badge("4K")
            }
        }
        channel.category?.let {
            Spacer(Modifier.height(4.dp))
            Txt(categoryLabel(it), Type.Label)
        }
        if (!channel.includedInPlan) {
            Spacer(Modifier.height(8.dp))
            Txt(stringResource(R.string.not_in_plan), Type.BodyDim)
        } else if (channel.isUhd && !Capabilities.uhdHevcMain10) {
            Spacer(Modifier.height(8.dp))
            Txt(stringResource(R.string.uhd_unsupported), Type.BodyDim, color = Palette.Accent)
        }
        Spacer(Modifier.height(28.dp))
        val (now, next) = remember(channel.id, epgRevision, clock) { epg.nowNext(channel.id, clock) }
        NowNext(now, next, clock, descriptionLines = 4)
        Spacer(Modifier.height(24.dp))
        Txt(stringResource(R.string.hint_menu_fav), Type.Label, color = Palette.TextFaint)
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
                            if (p.imageUrl != null) AsyncImage(model = p.imageUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
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
