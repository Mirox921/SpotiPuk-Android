package com.project.puk.ui

import com.project.puk.core.Lang

import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.ViewList
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import com.project.puk.api.*
import com.project.puk.ui.theme.EmberIcons
import com.project.puk.ui.theme.EmberMotion
import com.project.puk.ui.theme.artworkAmbient
import kotlinx.coroutines.CancellationException

internal class DiscographyState {
    var open by mutableStateOf(false)
    var filter by mutableStateOf("all")
    var alphabetical by mutableStateOf(false)
    var grid by mutableStateOf(false)
    val pages = PagedList<ArtistRelease>()
    val positions = mutableMapOf<String, LazyListState>()
}
internal fun filteredReleases(releases: List<ArtistRelease>, filter: String, alphabetical: Boolean): List<ArtistRelease> {
    val filtered = releases.distinctBy { it.card.uri }.filter {
        when (filter) { "popular" -> it.popular; "album" -> it.type == "album"; "single" -> it.type in setOf("single", "ep"); else -> true }
    }
    return if (alphabetical) filtered.sortedWith(compareBy(java.text.Collator.getInstance()) { it.card.title }) else filtered.sortedByDescending { it.date }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ArtistDetails(route: ContentRoute, uri: String, onBack: () -> Unit, onOpen: (Target) -> Unit,
    onPlay: (List<TrackRow>, Int) -> Unit, onTrackActions: (TrackRow) -> Unit) {
    val context = LocalContext.current
    var failed by remember(route) { mutableStateOf(false) }
    var retry by remember(route) { mutableStateOf(0) }
    var about by remember { mutableStateOf(false) }
    var libraryActionRevision by remember(uri) { mutableStateOf(0) }
    val libraryChanged = LocalContextLibraryChanged.current
    LaunchedEffect(route, retry) {
        if (route.artist != null) return@LaunchedEffect
        failed = false
        route.artist = try { Spotify.artist(context, uri) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        failed = route.artist == null
    }
    val page = route.artist
    val title = page?.name?.ifBlank { Lang.t(Lang.Key.LIB_ARTIST_SINGULAR) } ?: Lang.t(Lang.Key.LIB_ARTIST_SINGULAR)
    val card = Card(uri, title, "", page?.imageUrl.orEmpty(), "artist")
    val actions = cardActions(card, {})
    val ambient by animateColorAsState(artworkAmbient(card.imageUrl), tween(EmberMotion.Ambient), label = "Artist ambient")
    val canvas = MaterialTheme.colorScheme.background
    val collapsed = entityControlsCollapsed(route.scroll, "artist-controls", 2)
    Box(Modifier.fillMaxSize().drawBehind { drawRect(Brush.verticalGradient(listOf(ambient, canvas), endY = 420.dp.toPx())) }) {
    LazyColumn(state = route.scroll, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = LocalBrowseBottomPadding.current), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "header-space") { Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars)); Spacer(Modifier.height(56.dp)) }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).then(if (actions != null) Modifier.contextGesture(title, {}, actions) else Modifier)) {
                Box(Modifier.fillMaxWidth().height(280.dp)) {
                    Artwork(card.imageUrl, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color.Transparent, canvas))))
                    Text(title, Modifier.align(Alignment.BottomStart).padding(12.dp), style = MaterialTheme.typography.headlineLarge)
                }
                if (page?.verified == true) Text(Lang.t(Lang.Key.ART_VERIFIED_ARTIST), style = MaterialTheme.typography.labelMedium)
                page?.monthlyListeners?.let { Text(Lang.t(Lang.Key.ART_MONTHLY_LISTENERS, java.text.NumberFormat.getIntegerInstance().format(it)), style = MaterialTheme.typography.bodyMedium) }
            }
        }
        item(key = "artist-controls") {
            Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                key(libraryActionRevision) { if (page != null) CardLibraryAction(card, false) { libraryActionRevision++; libraryChanged() } }
            }
            if (actions != null) IconButton(onClick = actions) { Icon(EmberIcons.MoreVert, Lang.t(Lang.Key.ART_ACTIONS)) }
            EntityPlayButton(title, !page?.topTracks.isNullOrEmpty(), 56.dp) { page?.topTracks?.let { onPlay(it, 0) } }
            }
        }
        when {
            failed -> item { TextButtonLite(Lang.t(Lang.Key.LIB_LOAD_FAILED_RETRY)) { retry++ } }
            page == null -> item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            else -> {
                if (page.topTracks.isNotEmpty()) {
                    item { ArtistSectionTitle(Lang.t(Lang.Key.ART_POPULAR_TRACKS)) }
                    itemsIndexed(page.topTracks, key = { index, track -> "top:$index:${track.uri}" }) { index, track ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.labelMedium)
                            Box(Modifier.weight(1f)) { TrackRowView(track, { onPlay(page.topTracks, index) }, { onTrackActions(track) }) }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(Lang.t(Lang.Key.ART_RELEASES), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        EmberTextAction(com.project.puk.R.drawable.ember_ctx_go_to_album, Lang.t(Lang.Key.ART_DISCOGRAPHY)) { route.discography.open = true }
                    }
                }
                if (page.releases.isNotEmpty()) item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(page.releases.take(12), key = { it.card.uri }) { release -> CardTile(release.card.copy(subtitle = releaseSubtitle(release))) { release.card.target()?.let(onOpen) } }
                    }
                }
                for (shelf in page.shelves) {
                    val cards = shelf.items.filter { it.target() != null }
                    if (cards.isEmpty()) continue
                    item(key = shelf.sourceId) {
                        ArtistSectionTitle(shelf.title)
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(cards, key = Card::uri) { related -> CardTile(related) { related.target()?.let(onOpen) } }
                        }
                    }
                }
                if (page.biography.isNotBlank() || page.followers != null || page.links.isNotEmpty()) item {
                    Card(onClick = { about = true }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Column(Modifier.padding(20.dp)) {
                            Text(Lang.t(Lang.Key.ART_ABOUT), style = MaterialTheme.typography.titleLarge)
                            page.followers?.let { Text(Lang.t(Lang.Key.ART_FOLLOWERS, java.text.NumberFormat.getIntegerInstance().format(it))) }
                            if (page.biography.isNotBlank()) Text(remember(page.biography) { HtmlCompat.fromHtml(page.biography, HtmlCompat.FROM_HTML_MODE_COMPACT).toString() }, maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
                if (page.gallery.isNotEmpty()) item {
                    ArtistSectionTitle(Lang.t(Lang.Key.ART_GALLERY))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(page.gallery.distinct()) { Artwork(it, Modifier.size(220.dp)) } }
                }
            }
        }
    }
    EntityBrowseHeader(title, ambient, collapsed, !page?.topTracks.isNullOrEmpty(), onBack) { page?.topTracks?.let { onPlay(it, 0) } }
    }
    if (route.discography.open && page != null) ArtistDiscography(uri, page, route.discography, onOpen)
    if (about && page != null) EmberActionSheet(onDismissRequest = { about = false }, heading = Lang.t(Lang.Key.ART_ABOUT), text = {
        page.monthlyListeners?.let { Text(Lang.t(Lang.Key.ART_MONTHLY_LISTENERS, java.text.NumberFormat.getIntegerInstance().format(it))) }
        page.followers?.let { Text(Lang.t(Lang.Key.ART_FOLLOWERS, java.text.NumberFormat.getIntegerInstance().format(it))) }
        if (page.biography.isNotBlank()) Text(remember(page.biography) { HtmlCompat.fromHtml(page.biography, HtmlCompat.FROM_HTML_MODE_COMPACT).toString() })
        page.links.forEach { (label, url) -> SheetAction(com.project.puk.R.drawable.ember_ctx_open_external, label) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    })
}

private fun releaseSubtitle(release: ArtistRelease): String = listOf(release.date.take(4), when (release.type) { "single", "ep" -> Lang.t(Lang.Key.ART_SINGLE_EP); "album" -> Lang.t(Lang.Key.LIB_ALBUM_SINGULAR); "compilation" -> Lang.t(Lang.Key.ART_COMPILATION); else -> Lang.t(Lang.Key.ART_RELEASE) }).filter(String::isNotBlank).joinToString(" • ")

@Composable
private fun ArtistSectionTitle(title: String) { Text(title, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge) }

@Composable
private fun ArtistDiscography(uri: String, page: ArtistPage, state: DiscographyState, onOpen: (Target) -> Unit) {
    val context = LocalContext.current
    val releases = remember(page.releases, state.pages.items, state.filter, state.alphabetical) {
        filteredReleases(state.pages.items.map { release -> release.copy(popular = page.releases.any { it.card.uri == release.card.uri && it.popular }) } + page.releases.filter { old -> state.pages.items.none { it.card.uri == old.card.uri } }, state.filter, state.alphabetical)
    }
    val scroll = remember(state, state.filter, state.grid, state.alphabetical) { state.positions.getOrPut("${state.filter}:${state.grid}:${state.alphabetical}") { LazyListState() } }
    EmberSheet(onDismissRequest = { state.open = false }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.92f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(Lang.t(Lang.Key.ART_DISCOGRAPHY), style = MaterialTheme.typography.headlineSmall); Text(page.name, style = MaterialTheme.typography.bodySmall) }
                IconButton(onClick = { state.open = false }) { Icon(EmberIcons.Close, Lang.t(Lang.Key.ART_CLOSE_DISCOGRAPHY)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val filters = listOf("all" to Lang.t(Lang.Key.ART_FILTER_ALL)) + (if (page.releases.any { it.popular }) listOf("popular" to Lang.t(Lang.Key.ART_FILTER_POPULAR)) else emptyList()) + listOf("album" to Lang.t(Lang.Key.LIB_ALBUMS), "single" to Lang.t(Lang.Key.ART_FILTER_SINGLES_EP))
                filters.forEach { (id, label) -> FilterChip(selected = state.filter == id, onClick = { state.filter = id }, label = { Text(label) }, colors = emberFilterColors()) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { state.alphabetical = !state.alphabetical }, modifier = Modifier.weight(1f)) {
                    Icon(androidx.compose.ui.res.painterResource(com.project.puk.R.drawable.ember_ctx_sort), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp)); Text(if (state.alphabetical) Lang.t(Lang.Key.LIB_ORDER_BY_NAME) else Lang.t(Lang.Key.ART_SORT_NEWEST))
                }
                IconButton(onClick = { state.grid = !state.grid }) {
                    Icon(if (state.grid) androidx.compose.material.icons.Icons.Outlined.ViewList else androidx.compose.material.icons.Icons.Outlined.GridView, if (state.grid) Lang.t(Lang.Key.LIB_SHOW_LIST) else Lang.t(Lang.Key.LIB_SHOW_GRID))
                }
            }
            LazyColumn(state = scroll, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (state.grid) items(releases.chunked(2), key = { "grid:${it.first().card.uri}" }) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { release -> Box(Modifier.weight(1f)) { CardTile(release.card.copy(subtitle = releaseSubtitle(release)), modifier = Modifier.fillMaxWidth()) { release.card.target()?.let(onOpen) } } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                } else items(releases, key = { it.card.uri }) { release -> ListRow(release.card.copy(subtitle = releaseSubtitle(release)), showKind = false) { release.card.target()?.let(onOpen) } }
                if (releases.isEmpty() && state.pages.offset == null) item { Text(Lang.t(Lang.Key.ART_NO_RELEASES), Modifier.padding(20.dp)) }
                item { PageFooter(state.pages, errorLabel = Lang.t(Lang.Key.ART_LOAD_RELEASES_RETRY)) { offset -> Spotify.artistReleases(context, uri, offset) } }
            }
        }
    }
}
