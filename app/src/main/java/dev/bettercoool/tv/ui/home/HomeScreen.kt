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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import dev.bettercoool.tv.AppGraph
import dev.bettercoool.tv.R
import dev.bettercoool.tv.data.Catalog
import dev.bettercoool.tv.data.Channel
import dev.bettercoool.tv.data.Profile
import dev.bettercoool.tv.ui.theme.FocusCard
import dev.bettercoool.tv.ui.theme.Palette
import dev.bettercoool.tv.ui.theme.Txt
import dev.bettercoool.tv.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(graph: AppGraph, onPlay: (List<Channel>, Int) -> Unit) {
    var snapshot by remember { mutableStateOf(graph.catalog.snapshot) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(attempt) {
        if (snapshot != null) return@LaunchedEffect
        failed = false
        try {
            snapshot = graph.catalog.load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }

    val selectProfile: (Profile) -> Unit = { profile ->
        if (!profile.isLocked) scope.launch {
            snapshot = null
            snapshot = runCatching { graph.catalog.selectProfile(profile) }.getOrNull()
            failed = snapshot == null
        }
    }

    val s = snapshot
    when {
        failed -> Message(stringResource(R.string.home_error), action = stringResource(R.string.home_retry)) { attempt++ }
        s == null -> Message(stringResource(R.string.home_loading))
        s.profile == null -> ProfilePicker(s.profiles, selectProfile)
        else -> ChannelBrowser(graph, s, selectProfile, onPlay)
    }
}

@Composable
private fun ChannelBrowser(
    graph: AppGraph,
    snapshot: Catalog.Snapshot,
    onProfile: (Profile) -> Unit,
    onPlay: (List<Channel>, Int) -> Unit,
) {
    val profile = snapshot.profile!!
    val channels = snapshot.channels
    val startIndex = remember(channels) {
        channels.indexOfFirst { it.id == graph.session.lastChannel(profile.uuid) }.coerceAtLeast(0)
    }
    var focusedIndex by remember { mutableIntStateOf(startIndex) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (startIndex - 3).coerceAtLeast(0))
    val initialFocus = remember { FocusRequester() }

    LaunchedEffect(channels) {
        if (channels.isNotEmpty()) runCatching { initialFocus.requestFocus() }
    }

    Row(Modifier.fillMaxSize().padding(start = 48.dp, top = 36.dp, end = 48.dp)) {
        Column(Modifier.width(420.dp).fillMaxHeight()) {
            Header(snapshot, onProfile)
            Spacer(Modifier.height(16.dp))
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = 36.dp),
            ) {
                itemsIndexed(channels, key = { _, c -> c.id }) { index, channel ->
                    ChannelRow(
                        channel = channel,
                        modifier = Modifier
                            .then(if (index == startIndex) Modifier.focusRequester(initialFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) focusedIndex = index },
                        onClick = {
                            if (channel.playable) {
                                graph.session.setLastChannel(profile.uuid, channel.id)
                                onPlay(channels, index)
                            }
                        },
                    )
                }
            }
        }
        Spacer(Modifier.width(48.dp))
        Preview(channels.getOrNull(focusedIndex), Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun Header(snapshot: Catalog.Snapshot, onProfile: (Profile) -> Unit) {
    Column {
        Txt(stringResource(R.string.app_name).uppercase(), Type.Label, color = Palette.Accent)
        Spacer(Modifier.height(4.dp))
        Txt(stringResource(R.string.home_channels, snapshot.channels.size), Type.Label)
        if (snapshot.profiles.size > 1) {
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(snapshot.profiles, key = { _, p -> p.uuid }) { _, p ->
                    val current = p.uuid == snapshot.profile?.uuid
                    FocusCard(
                        onClick = { onProfile(p) },
                        shape = RoundedCornerShape(50),
                        idleColor = if (current) Palette.SurfaceHigh else Palette.Surface,
                    ) { focused ->
                        Txt(
                            p.name + if (p.isLocked) " 🔒" else "",
                            Type.Label,
                            Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            color = if (focused) Palette.OnFocus else if (current) Palette.Text else Palette.TextDim,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(channel: Channel, modifier: Modifier, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = modifier.fillMaxWidth().height(56.dp)) { focused ->
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
            Txt(channel.name, Type.Body, Modifier.weight(1f), maxLines = 1, color = main)
        }
    }
}

@Composable
private fun Preview(channel: Channel?, modifier: Modifier) {
    Column(modifier.padding(top = 64.dp)) {
        if (channel == null) return@Column
        Box(
            Modifier.size(width = 200.dp, height = 112.dp).background(Palette.Surface, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (channel.logo != null) {
                AsyncImage(model = channel.logo, contentDescription = null, modifier = Modifier.padding(18.dp).fillMaxSize())
            }
        }
        Spacer(Modifier.height(24.dp))
        Txt(listOfNotNull(channel.number, channel.name).joinToString("  "), Type.Display)
        Spacer(Modifier.height(8.dp))
        if (!channel.includedInPlan) Txt(stringResource(R.string.not_in_plan), Type.BodyDim)
        // Now/next from the EPG lands here in milestone 5.
    }
}

@Composable
private fun ProfilePicker(profiles: List<Profile>, onSelect: (Profile) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
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
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Spacer(Modifier.height(20.dp))
            FocusCard(onClick = onAction, modifier = Modifier.focusRequester(focus), shape = RoundedCornerShape(50), idleColor = Palette.Surface) { focused ->
                Txt(action, Type.Body, Modifier.padding(horizontal = 24.dp, vertical = 10.dp), color = if (focused) Palette.OnFocus else Palette.Text)
            }
        }
    }
}
