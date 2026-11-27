package com.project.puk.ui

import com.project.puk.core.Lang

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.project.puk.R
import com.project.puk.api.TrackRow
import com.project.puk.ui.theme.EmberIcons
import com.project.puk.ui.theme.EmberMotion
import com.project.puk.ui.theme.artworkAmbient
import java.text.Collator

internal fun mediaKindLabel(kind: String): String = when (kind) {
    "playlist" -> Lang.t(Lang.Key.JAM_PLAYLIST)
    "album" -> Lang.t(Lang.Key.LIB_ALBUM_SINGULAR)
    "artist" -> Lang.t(Lang.Key.LIB_ARTIST_SINGULAR)
    "show", "podcast" -> Lang.t(Lang.Key.PLAYER_PODCAST)
    "episode" -> Lang.t(Lang.Key.LIB_EPISODE_SINGULAR)
    "track" -> Lang.t(Lang.Key.LIB_TRACK_SINGULAR)
    "collection" -> Lang.t(Lang.Key.LIB_COLLECTION)
    "audiobook" -> Lang.t(Lang.Key.LIB_AUDIOBOOK)
    else -> Lang.t(Lang.Key.LIB_MUSIC)
}

internal enum class PlaylistSort(val label: String) {
    CUSTOM(Lang.t(Lang.Key.LIB_SORT_CUSTOM)), TITLE(Lang.t(Lang.Key.LIB_NAME_LABEL)), ARTIST(Lang.t(Lang.Key.LIB_ARTIST_SINGULAR)), ALBUM(Lang.t(Lang.Key.LIB_ALBUM_SINGULAR)), ADDED(Lang.t(Lang.Key.LIB_SORT_DATE_ADDED))
}
internal fun sortedPlaylist(rows: List<TrackRow>, order: PlaylistSort): List<TrackRow> {
    val collator = Collator.getInstance()
    return when (order) {
        PlaylistSort.CUSTOM -> rows
        PlaylistSort.TITLE -> rows.sortedWith(compareBy(collator) { it.name })
        PlaylistSort.ARTIST -> rows.sortedWith(compareBy(collator) { it.artists })
        PlaylistSort.ALBUM -> rows.sortedWith(compareBy(collator) { it.albumName })
        PlaylistSort.ADDED -> rows.sortedByDescending { it.addedAt }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TrackCollection(
    title: String, subtitle: String, imageUrl: String, tracks: List<TrackRow>, scroll: LazyListState,
    empty: Boolean, onBack: () -> Unit, onPlay: (List<TrackRow>, Int) -> Unit,
    onTrackActions: (TrackRow) -> Unit, liked: Boolean = false,
    actions: @Composable () -> Unit = {}, footer: @Composable () -> Unit = {},
    searchQuery: String = "", onSearchQuery: ((String) -> Unit)? = null, searchLoading: Boolean = false,
    playing: Boolean = false, currentTrackUri: String? = null, currentTrackPlaying: Boolean = false,
    onTogglePlayback: (() -> Unit)? = null,
) {
    val ambient by animateColorAsState(artworkAmbient(imageUrl), tween(EmberMotion.Ambient), label = "Collection artwork")
    val canvas = MaterialTheme.colorScheme.background
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val searching = searchQuery.isNotBlank()
    val resultScroll = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(searchQuery) { if (searching) resultScroll.scrollToItem(0) }
    val listScroll = if (searching) resultScroll else scroll
    var searchRevealed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val canSearch by rememberUpdatedState(onSearchQuery != null)
    val pullConnection = remember(listScroll, keyboard) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!canSearch || source != NestedScrollSource.UserInput) return Offset.Zero
                if (available.y < 0 && searchRevealed) {
                    searchRevealed = false
                    keyboard?.hide()
                } else if (available.y > 0 && listScroll.firstVisibleItemIndex == 0 &&
                    listScroll.firstVisibleItemScrollOffset == 0) {
                    searchRevealed = true
                }
                return Offset.Zero
            }
        }
    }
    val collapsed = searching || entityControlsCollapsed(scroll, "collection-controls", 3, 12.dp)
    Box(Modifier.fillMaxSize().drawBehind { drawRect(Brush.verticalGradient(listOf(ambient, canvas), endY = 420.dp.toPx())) }) {
    LazyColumn(state = listScroll, modifier = Modifier.fillMaxSize().nestedScroll(pullConnection),
        contentPadding = PaddingValues(bottom = LocalBrowseBottomPadding.current), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(key = "header-space") { Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars)); Spacer(Modifier.height(56.dp)) }
        item(key = "playlist-search") {
            AnimatedVisibility(
                visible = onSearchQuery != null && searchRevealed,
                enter = expandVertically(tween(EmberMotion.Standard)) + fadeIn(tween(EmberMotion.Standard)),
                exit = shrinkVertically(tween(EmberMotion.Standard)) + fadeOut(tween(EmberMotion.Fast)),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    TextField(
                        value = searchQuery, onValueChange = { onSearchQuery?.invoke(it) },
                        placeholder = { Text(Lang.t(Lang.Key.LIB_FIND_IN_PLAYLIST), maxLines = 1) },
                        leadingIcon = { Icon(EmberIcons.Search, null, Modifier.size(20.dp)) },
                        trailingIcon = if (searchQuery.isEmpty()) null else ({
                            IconButton(onClick = { onSearchQuery?.invoke(""); keyboard?.hide() }) {
                                Icon(EmberIcons.Close, Lang.t(Lang.Key.JAM_CLEAR_SEARCH))
                            }
                        }),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .10f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f),
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide() }),
                    )
                    if (searchLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        if (!searching) {
        item(key = "artwork") {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (liked) LikedArtwork(Modifier.size(220.dp), large = true) else Artwork(imageUrl, Modifier.sizeIn(maxWidth = 280.dp).fillMaxWidth(.72f).aspectRatio(1f))
                Spacer(Modifier.height(20.dp))
                Text(title, Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall)
                if (subtitle.isNotBlank()) Text(subtitle, Modifier.fillMaxWidth().padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "collection-controls") {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { actions() }
                EntityPlayButton(title, tracks.isNotEmpty(), 56.dp, playing) {
                    if (onTogglePlayback != null) onTogglePlayback() else onPlay(tracks, 0)
                }
            }
        }
        }
        itemsIndexed(tracks, key = { index, track -> "$index:${track.uri}" }) { index, track ->
            TrackRowView(track, { onPlay(tracks, index) }, { onTrackActions(track) },
                isCurrent = track.uri == currentTrackUri, playing = currentTrackPlaying)
        }
        if (searchQuery.isNotBlank() && tracks.isEmpty() && !searchLoading) item {
            Text(Lang.t(Lang.Key.JAM_NOTHING_FOUND), Modifier.padding(16.dp))
        } else if (empty) item { Text(Lang.t(Lang.Key.LIB_NO_TRACKS_YET), Modifier.padding(16.dp)) }
        item { footer() }
    }
    EntityBrowseHeader(title, ambient, collapsed, tracks.isNotEmpty(), onBack, playing) {
        if (onTogglePlayback != null) onTogglePlayback() else onPlay(tracks, 0)
    }

    }
}

@Composable
internal fun emberFilterColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
)


@Composable
internal fun entityControlsCollapsed(scroll: LazyListState, key: String, index: Int, bottomPadding: androidx.compose.ui.unit.Dp = 0.dp): Boolean {
    val buttonSize = with(LocalDensity.current) { 56.dp.roundToPx() }
    val bottomInset = with(LocalDensity.current) { bottomPadding.roundToPx() }
    val headerBottom = WindowInsets.statusBars.getTop(LocalDensity.current) + with(LocalDensity.current) { 56.dp.roundToPx() }
    return remember(scroll, key, index, headerBottom, bottomInset, buttonSize) {
        derivedStateOf {
            val controls = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
            if (controls != null) controls.offset + (controls.size - bottomInset + buttonSize) / 2 <= headerBottom
            else scroll.firstVisibleItemIndex > index
        }
    }.value
}

@Composable
internal fun EntityPlayButton(title: String, enabled: Boolean, size: androidx.compose.ui.unit.Dp, playing: Boolean = false, onPlay: () -> Unit) {
    FilledIconButton(onClick = onPlay, enabled = enabled, modifier = Modifier.size(size)) {
        Icon(if (playing) EmberIcons.Pause else EmberIcons.PlayArrow,
            if (playing) Lang.t(Lang.Key.LIB_PAUSE_TITLE, title) else Lang.t(Lang.Key.LIB_PLAY_TITLE, title), Modifier.size(28.dp))
    }
}

@Composable
internal fun EntityBrowseHeader(title: String, ambient: Color, collapsed: Boolean, playable: Boolean, onBack: () -> Unit, playing: Boolean = false, onPlay: () -> Unit) {
    val canvas = MaterialTheme.colorScheme.background
    val tint by animateColorAsState(if (collapsed) lerp(ambient, canvas, .72f) else ambient.copy(alpha = 0f), tween(EmberMotion.Standard), label = "Entity header")
    Row(Modifier.fillMaxWidth().background(tint).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(EmberIcons.ArrowBack, Lang.t(Lang.Key.JAM_BACK)) }
        Text(if (collapsed) title else "", Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        AnimatedVisibility(visible = collapsed && playable, enter = fadeIn(tween(EmberMotion.Fast)), exit = fadeOut(tween(EmberMotion.Fast))) {
            EntityPlayButton(title, playable, 48.dp, playing, onPlay)
        }
    }
}


@Composable
internal fun EmberTextAction(icon: Int, text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(painterResource(icon), null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}
