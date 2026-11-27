package com.project.puk.ui

import com.project.puk.core.Lang

import kotlinx.coroutines.flow.map
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.semantics.stateDescription

import com.project.puk.ui.theme.EmberIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.platform.LocalDensity
import com.project.puk.ui.theme.EmberMotion
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import com.project.puk.ui.theme.artworkAmbient
import com.project.puk.ui.theme.EmberColors
import com.project.puk.ui.theme.emberPress
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.project.puk.api.AlbumPage
import com.project.puk.api.ArtistPage
import com.project.puk.api.Card
import com.project.puk.api.HomeSectionLayout
import com.project.puk.api.Section
import com.project.puk.api.Spotify
import com.project.puk.api.TrackRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import com.project.puk.catalog.EditorialCatalogProvider
import com.project.puk.catalog.HomeCatalogProvider
import com.project.puk.catalog.CatalogRepository
import com.project.puk.catalog.RecentCatalogProvider

/** Where a card/row click should navigate. */
sealed interface Target {
    data class Playlist(val uri: String) : Target
    data class Album(val uri: String) : Target
    data class Artist(val uri: String) : Target
    data class Track(val uri: String) : Target
    data class Show(val uri: String) : Target
    data class Episode(val uri: String) : Target
    data class Category(val id: String) : Target
    data class SpotifyLink(val url: String) : Target
    data object Liked : Target
}

/** Converts supported Spotify web links and URIs into native routes, preserving ID case. */
fun spotifyLinkTarget(raw: String): Target? {
    val uri = runCatching { java.net.URI(raw.trim()) }.getOrNull() ?: return null
    val segments = when (uri.scheme?.lowercase()) {
        "spotify" -> uri.schemeSpecificPart.split(':')
        "http", "https" -> {
            if (uri.host?.lowercase() !in setOf("open.spotify.com", "www.spotify.com", "spotify.com")) return null
            uri.path.orEmpty().trim('/').split('/').let { parts ->
                if (parts.firstOrNull()?.matches(Regex("intl-[a-zA-Z]{2}(-[a-zA-Z]{2})?")) == true) parts.drop(1) else parts
            }
        }
        else -> return null
    }
    val kind = segments.firstOrNull()?.lowercase()
    val id = segments.getOrNull(1)?.takeIf { it.matches(Regex("[A-Za-z0-9]{22}")) } ?: return null
    if (segments.size != 2) return null
    return when (kind) {
        "track" -> Target.Track("spotify:track:$id")
        "album" -> Target.Album("spotify:album:$id")
        "playlist" -> Target.Playlist("spotify:playlist:$id")
        "artist" -> Target.Artist("spotify:artist:$id")
        "show" -> Target.Show("spotify:show:$id")
        "episode" -> Target.Episode("spotify:episode:$id")
        else -> null
    }
}

fun Card.target(): Target? = when (kind) {
    "playlist" -> Target.Playlist(uri)
    "album" -> Target.Album(uri)
    "artist" -> Target.Artist(uri)
    "collection" -> Target.Liked
    "show", "podcast" -> Target.Show(uri)
    "episode" -> Target.Episode(uri)
    "audiobook" -> Target.SpotifyLink("https://open.spotify.com/audiobook/${uri.substringAfterLast(':')}")
    else -> null
}

fun TrackRow.toQueueItem() = com.project.puk.player.PukPlayer.QueueItem(
    uri = uri,
    title = name,
    artist = artists,
    albumUri = albumUri,
    imageUrl = imageUrl,
    durationMs = durationMs,
    artistUri = artistUri,
    artistUris = artistUris,
)

// ---- home ----

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    sections: List<Section>?,
    sourceUnavailable: Boolean,
    serverFacets: List<com.project.puk.api.HomeFacet>,
    serverFacet: String,
    onServerFacet: (String) -> Unit,
    recentTracks: List<Card>,
    onRetry: () -> Unit,
    onOpen: (Target) -> Unit,
    onProfile: () -> Unit,
    accountName: String,
    accountImageUrl: String = "",
    onPlay: (List<TrackRow>, Int) -> Unit,
    onTrackActions: (TrackRow) -> Unit,
) {
    var facet by rememberSaveable { mutableStateOf("all") }
    val headerScroll = rememberScrollState()
    val podcastKinds = setOf("show", "podcast", "episode", "audiobook")
    val available = sections.orEmpty().filter { it.items.isNotEmpty() }
    val activeFacet = if (serverFacets.isEmpty()) facet else serverFacet
    val selectedParent = serverFacets.firstOrNull { it.id == serverFacet || it.children.any { child -> child.id == serverFacet } }
    val facetChoices = if (serverFacets.isEmpty()) listOf("all" to Lang.t(Lang.Key.LIB_FACET_ALL), "music" to Lang.t(Lang.Key.LIB_MUSIC), "podcasts" to Lang.t(Lang.Key.LIB_PODCASTS))
    else (listOf("" to Lang.t(Lang.Key.LIB_FACET_ALL)) + serverFacets.map { it.id to it.label } + selectedParent?.children.orEmpty().map { it.id to it.label }).distinctBy { it.first }
    val visible = available.mapNotNull { section ->
        val items = section.items.filter { card ->
            val supported = card.kind == "track" || card.target() != null
            supported && when (activeFacet) {
                "music" -> card.kind !in podcastKinds
                "podcasts" -> card.kind in podcastKinds
                else -> true
            }
        }
        section.copy(items = items).takeIf { items.isNotEmpty() }
    }
    val shortcutSections = visible.filter { it.layout == HomeSectionLayout.SHORTCUTS }
    val preferredShortcuts = shortcutSections.flatMap { it.items }.filter { it.target() != null }
    val shortcuts = preferredShortcuts.ifEmpty { visible.flatMap { it.items }.filter { it.target() != null } }
        .distinctBy(Card::uri).take(9)
    val shelves = if (preferredShortcuts.isEmpty()) visible else visible.filterNot { it.layout == HomeSectionLayout.SHORTCUTS }
    val rows = if (shelves.isEmpty() && activeFacet in setOf("", "all", "music") && recentTracks.isNotEmpty()) {
        listOf(Section(Lang.t(Lang.Key.LIB_RECENT_ON_SPOTIPUK), recentTracks, "spotipuk:recent", HomeSectionLayout.SHELF))
    } else shelves
    val sectionKeys = remember(rows) { homeSectionKeys(rows) }
    val canvas = MaterialTheme.colorScheme.background
    val ambient = artworkAmbient(shortcuts.firstOrNull { it.imageUrl.isNotBlank() }?.imageUrl.orEmpty())
    LoadingWhen(sections == null && serverFacets.isEmpty(), label = Lang.t(Lang.Key.LIB_HOME)) {
        val homeState = rememberLazyListState()
        Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = homeState,
            modifier = Modifier.fillMaxSize().drawBehind {
                drawRect(Brush.verticalGradient(listOf(ambient, canvas), endY = 360.dp.toPx()))
            },
            contentPadding = PaddingValues(bottom = LocalBrowseBottomPadding.current),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            stickyHeader {
                val headerColor = androidx.compose.ui.graphics.lerp(canvas, ambient, 0.18f)
                // Controlled frosted glass, two states: airy at rest, denser
                // once content scrolls beneath. Deliberate translucency
                // fallback — no expensive backdrop blur.
                val targetGlass = if (homeState.firstVisibleItemIndex > 0 || homeState.firstVisibleItemScrollOffset > 4) 1f else 0f
                val glassProgress by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = targetGlass,
                    animationSpec = androidx.compose.animation.core.tween(EmberMotion.Standard),
                    label = "Ember home glass",
                )
                val surfaceAlpha = 0.52f + 0.30f * glassProgress
                val tintAlpha = 0.10f + 0.22f * glassProgress
                val scrimAlpha = 0.16f + 0.36f * glassProgress
                val hairline = (MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f + 0.12f * glassProgress)).toArgb()
                Column(
                    Modifier.fillMaxWidth().drawBehind {
                        drawRect(headerColor.copy(alpha = surfaceAlpha))
                        drawRect(Color.Black.copy(alpha = tintAlpha))
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0f),
                                0.72f to Color.Black.copy(alpha = 0f),
                                1f to Color.Black.copy(alpha = scrimAlpha),
                            ),
                        )
                        // Bottom hairline separates the panel from content.
                        drawLine(
                            Color(hairline),
                            Offset(0f, size.height - 0.5f),
                            Offset(size.width, size.height - 0.5f),
                            strokeWidth = 1f,
                        )
                    },
                ) {
                Row(
                    Modifier.fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProfileAvatar(onProfile, accountName, accountImageUrl)
                    Row(Modifier.weight(1f).horizontalScroll(headerScroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((id, label) in facetChoices) {
                            val press = remember { MutableInteractionSource() }
                            FilterChip(
                                modifier = Modifier.emberPress(press, 1.03f),
                                interactionSource = press,
                                selected = activeFacet == id,
                                onClick = { if (serverFacets.isEmpty()) facet = id else onServerFacet(id) },
                                label = { Text(label, maxLines = 1) },
                                shape = CircleShape,
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                                border = BorderStroke(0.dp, Color.Transparent),
                            )
                        }
                    }
                }
                }
            }
            if (sections == null) item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            if (accountName.isNotBlank()) {
                item { SectionHeader(Lang.t(Lang.Key.LIB_GREETING, accountName)) }
            }
            item {
                Column(
                    Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val cards = if (activeFacet == "podcasts") shortcuts else listOf(null) + shortcuts
                    cards.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { card ->
                                val open = { onOpen(card?.target() ?: Target.Liked) }
                                val actions = if (card != null) cardActions(card, open) else null
                                Row(
                                    modifier = Modifier.weight(1f).height(60.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White.copy(alpha = 0.10f))
                                        .then(
                                            if (card != null && actions != null) Modifier.contextGesture(card.title, open, actions)
                                            else Modifier.clickable(onClick = open),
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (card == null) {
                                        LikedArtwork(Modifier.size(60.dp))
                                    } else {
                                        Artwork(card.imageUrl, Modifier.size(60.dp))
                                    }
                                    Text(
                                        card?.title ?: Lang.t(Lang.Key.JAM_LIKED_TRACKS),
                                        modifier = Modifier.padding(horizontal = 8.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            if (sections != null && (sourceUnavailable || rows.isEmpty())) {
                item {
                    SectionHeader(Lang.t(Lang.Key.LIB_RECS_UNAVAILABLE))
                    TextButtonLite(Lang.t(Lang.Key.LIB_RETRY), onClick = onRetry)
                    TextButtonLite(Lang.t(Lang.Key.LIB_OPEN_SPOTIPUK_PICKS)) { onOpen(Target.Category("pop")) }
                }
            }
            rows.forEachIndexed { sectionIndex, section ->
                val sectionKey = sectionKeys[sectionIndex]
                if (section.usesRecommendationRows()) {
                    if (rows.getOrNull(sectionIndex - 1)?.usesRecommendationRows() != true) {
                        item(key = "recommendations:$sectionKey") { SectionHeader(Lang.t(Lang.Key.LIB_RECOMMENDATIONS)) }
                    }
                    items(section.items.distinctBy(Card::uri), key = { "recommendation:$sectionKey:${it.uri}" }) { card ->
                        val track = card.takeIf { it.uri.startsWith("spotify:track:") }?.let {
                            TrackRow(it.uri, it.uri.substringAfterLast(':'), it.title, it.subtitle, "", "", it.imageUrl, 0L)
                        }
                        HomeRecommendationRow(card, reason = section.title.takeUnless { it == Lang.t(Lang.Key.LIB_FOR_YOU) }.orEmpty(),
                            onTrackActions = track?.let { { onTrackActions(it) } },
                            onOpen = { if (track != null) onPlay(listOf(track), 0) else card.target()?.let(onOpen) })
                    }
                } else {
                    item(key = "section:$sectionKey") {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    item(key = "shelf:$sectionKey") {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                        ) {
                            val tracks = section.items.filter { it.uri.startsWith("spotify:track:") }
                                .distinctBy { it.uri }
                                .map { TrackRow(it.uri, it.uri.substringAfterLast(':'), it.title, it.subtitle, "", "", it.imageUrl, 0L) }
                            items(section.items.distinctBy(Card::uri), key = Card::uri) { card ->
                                val trackIndex = tracks.indexOfFirst { it.uri == card.uri }
                                HomeMediaCard(card, tracks.getOrNull(trackIndex),
                                    onPlayTrack = { if (trackIndex >= 0) onPlay(tracks, trackIndex) },
                                    onTrackActions = tracks.getOrNull(trackIndex)?.let { track -> { onTrackActions(track) } },
                                    onOpen = { if (trackIndex >= 0) onPlay(tracks, trackIndex) else card.target()?.let(onOpen) })
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

// ---- library ----

internal class PagedList<T> {
    var items by mutableStateOf(emptyList<T>())
    var total by mutableStateOf<Int?>(null)
        private set
    var offset by mutableStateOf<Int?>(0)
    var failed by mutableStateOf(false)
    var retry by mutableStateOf(0)
    var loading by mutableStateOf(false)
        private set

    suspend fun load(fetch: suspend (Int) -> Spotify.Page<T>?) {
        val start = offset ?: return
        if (loading) return
        loading = true
        failed = false
        val page = try {
            fetch(start).also { coroutineContext.ensureActive() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally { loading = false }
        if (page == null || (page.next != null && page.next <= start)) {
            failed = true
            return
        }
        page.total?.let { total = it }
        items = items + page.items
        offset = page.next
    }
}

@Composable
internal fun <T> PageFooter(pages: PagedList<T>, errorLabel: String = Lang.t(Lang.Key.LIB_LOAD_FAILED_RETRY), fetch: suspend (Int) -> Spotify.Page<T>?) {
    if (pages.offset == null) return
    LaunchedEffect(pages, pages.offset, pages.retry) { pages.load(fetch) }
    if (pages.failed) {
        TextButtonLite(errorLabel) { pages.retry++ }
    } else {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

private val Spotify.SearchType.displayName: String
    get() = when (this) {
        Spotify.SearchType.TRACKS -> Lang.t(Lang.Key.JAM_TAB_TRACKS)
        Spotify.SearchType.SHOWS -> Lang.t(Lang.Key.LIB_PODCASTS)
        Spotify.SearchType.EPISODES -> Lang.t(Lang.Key.LIB_EPISODES)
        Spotify.SearchType.ARTISTS -> Lang.t(Lang.Key.LIB_ARTISTS)
        Spotify.SearchType.ALBUMS -> Lang.t(Lang.Key.LIB_ALBUMS)
        Spotify.SearchType.PLAYLISTS -> Lang.t(Lang.Key.JAM_TAB_PLAYLISTS)
    }

// ---- search ----

@Composable
fun SearchScreen(searchCache: android.util.LruCache<String, Spotify.SearchResults>, recentTracks: List<Card>, homeSections: List<Section>?, onOpen: (Target) -> Unit, onProfile: () -> Unit, accountName: String, onPlay: (List<TrackRow>, Int) -> Unit, onTrackActions: (TrackRow) -> Unit, accountImageUrl: String = "") {
    val context = LocalContext.current
    var term by rememberSaveable { mutableStateOf("") }
    val browseState = rememberLazyListState()
    val query = term.trim()
    val resultState = androidx.compose.runtime.key(query) { rememberLazyListState() }
    var nextRequest by remember(query, searchCache) { mutableStateOf<Spotify.SearchType?>(null) }
    var failedPage by remember(query, searchCache) { mutableStateOf<Spotify.SearchType?>(null) }
    var results by remember(query, searchCache) { mutableStateOf(searchCache.get(query)) }
    var searchFailed by remember(query) { mutableStateOf(false) }
    var retry by remember(query) { mutableStateOf(0) }
    var searching by remember(query, searchCache) { mutableStateOf(false) }
    var suggestions by remember(query, searchCache) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(query, searchCache) {
        if (query.length < 2) return@LaunchedEffect
        delay(220)
        suggestions = Spotify.searchSuggestions(context, query)
    }

    LaunchedEffect(query, retry, searchCache) {
        if (query.length < 2 || (retry == 0 && results != null)) return@LaunchedEffect
        searchFailed = false
        searching = true
        try {
            delay(350L)
            val found = Spotify.search(context, query)
            coroutineContext.ensureActive()
            if (found.pages.isEmpty()) searchFailed = true
            else {
                results = found
                if (found.failedSections == 0) searchCache.put(query, found)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            coroutineContext.ensureActive()
            searchFailed = true
        } finally {
            searching = false
        }
    }

    LaunchedEffect(query, searchCache, nextRequest) {
        val type = nextRequest ?: return@LaunchedEffect
        val current = results ?: return@LaunchedEffect
        val section = current.pages[type] ?: return@LaunchedEffect
        val offset = section.next ?: return@LaunchedEffect
        val page = try {
            Spotify.searchPage(context, query, type, offset)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        coroutineContext.ensureActive()
        if (page == null) {
            failedPage = type
        } else {
            val updated = current.copy(pages = current.pages + (type to section.append(page)))
            results = updated
            if (updated.failedSections == 0) searchCache.put(query, updated)
        }
        nextRequest = null
    }

    Box(Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        BrowseHeader(Lang.t(Lang.Key.LIB_SEARCH), onProfile, accountName, accountImageUrl = accountImageUrl)
        OutlinedTextField(
            value = term,
            onValueChange = { term = it },
            placeholder = { Text(Lang.t(Lang.Key.LIB_SEARCH_HINT)) },
            leadingIcon = { Icon(EmberIcons.Search, contentDescription = null) },
            trailingIcon = if (term.isNotEmpty()) {
                {
                    IconButton(onClick = { term = "" }) {
                        Icon(EmberIcons.Close, contentDescription = Lang.t(Lang.Key.JAM_CLEAR_SEARCH))
                    }
                }
            } else null,
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.76f),
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
                unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
        if (query.length >= 2 && results == null && !searchFailed) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(8.dp),
            )
        }
        if (searchFailed) {
            SectionHeader(Lang.t(Lang.Key.LIB_SEARCH_FAILED))
            TextButtonLite(Lang.t(Lang.Key.LIB_RETRY)) { retry++ }
        }
        if (query.isBlank()) {
            SectionHeader(Lang.t(Lang.Key.LIB_ALL_CATEGORIES))
            val catalog = remember(context, homeSections, recentTracks) {
                CatalogRepository(
                    HomeCatalogProvider(homeSections.orEmpty()),
                    RecentCatalogProvider(recentTracks),
                    EditorialCatalogProvider(context.applicationContext),
                )
            }
            LazyColumn(
                state = browseState,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = LocalBrowseBottomPadding.current + 12.dp),
            ) {
                items(catalog.categories().chunked(2)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (category in row) {
                            CategoryTile(category, Modifier.weight(1f)) { onOpen(Target.Category(category.id)) }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (results != null || suggestions.isNotEmpty()) {
            val r = results
            LazyColumn(
                state = resultState,
                contentPadding = PaddingValues(bottom = LocalBrowseBottomPadding.current),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (suggestions.isNotEmpty()) {
                    items(suggestions, key = { "suggestion:$it" }) { suggestion ->
                        TextButton(onClick = { term = suggestion }, modifier = Modifier.padding(start = 8.dp)) {
                            Icon(EmberIcons.Search, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(suggestion)
                        }
                    }
                }
                if (r == null) return@LazyColumn
                if (r.failedSections > 0) {
                    item { TextButtonLite(Lang.t(Lang.Key.LIB_RESULTS_PARTIAL_RETRY)) { if (nextRequest == null && !searching) retry++ } }
                }
                if (r.failedSections == 0 && r.pages.values.all { it.tracks.isEmpty() && it.cards.isEmpty() && it.next == null }) {
                    item { SectionHeader(Lang.t(Lang.Key.JAM_NOTHING_FOUND)) }
                }
                for ((type, section) in r.pages) {
                    if (section.tracks.isEmpty() && section.cards.isEmpty() && section.next == null) continue
                    item(key = "heading:$type") { SectionHeader(type.displayName) }
                    itemsIndexed(section.tracks, key = { _, track -> "$type:${track.uri}" }) { index, track ->
                        TrackRowView(track, onClick = { onPlay(section.tracks, index) }, onActions = { onTrackActions(track) })
                    }
                    items(section.cards, key = { "$type:${it.uri}" }) { card ->
                        ListRow(card = card, onClick = { card.target()?.let(onOpen) })
                    }
                    if (section.next != null) {
                        item(key = "more:$type") {
                            androidx.compose.material3.TextButton(
                                enabled = nextRequest == null && !searching,
                                onClick = { failedPage = null; nextRequest = type },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            ) {
                                Text(when (type) {
                                    nextRequest -> Lang.t(Lang.Key.LIB_LOADING)
                                    failedPage -> Lang.t(Lang.Key.LIB_LOAD_FAILED_RETRY_SHORT)
                                    else -> Lang.t(Lang.Key.LIB_SHOW_MORE)
                                })
                            }
                        }
                    }
                }
            }
        }
    }
    StatusBarScrim(scrolled = resultState.firstVisibleItemIndex > 0 || resultState.firstVisibleItemScrollOffset > 4)
    }
}

// ---- detail screens ----

private fun trackCountLabel(total: Int): String {
    val noun = when {
        total % 100 in 11..14 -> Lang.t(Lang.Key.LIB_COUNT_TRACKS_MANY)
        total % 10 == 1 -> Lang.t(Lang.Key.LIB_COUNT_TRACKS_ONE)
        total % 10 in 2..4 -> Lang.t(Lang.Key.LIB_COUNT_TRACKS_FEW)
        else -> Lang.t(Lang.Key.LIB_COUNT_TRACKS_MANY)
    }
    return "$total $noun"
}

internal fun playlistCountLabel(items: List<TrackRow>, hasMore: Boolean, totalCount: Int? = null): String {
    val reportedTotal = totalCount?.takeIf { hasMore && it >= items.size }
    val total = reportedTotal ?: items.size
    val forms = when {
        items.isNotEmpty() && items.all { it.uri.startsWith("spotify:episode:") } ->
            listOf(Lang.t(Lang.Key.LIB_COUNT_EPISODES_ONE), Lang.t(Lang.Key.LIB_COUNT_EPISODES_FEW), Lang.t(Lang.Key.LIB_COUNT_EPISODES_MANY))
        items.any { it.uri.startsWith("spotify:episode:") } ->
            listOf(Lang.t(Lang.Key.LIB_COUNT_ITEMS_ONE), Lang.t(Lang.Key.LIB_COUNT_ITEMS_FEW), Lang.t(Lang.Key.LIB_COUNT_ITEMS_MANY))
        else -> listOf(Lang.t(Lang.Key.LIB_COUNT_TRACKS_ONE), Lang.t(Lang.Key.LIB_COUNT_TRACKS_FEW), Lang.t(Lang.Key.LIB_COUNT_TRACKS_MANY))
    }
    val noun = when {
        total % 100 in 11..14 -> forms[2]
        total % 10 == 1 -> forms[0]
        total % 10 in 2..4 -> forms[1]
        else -> forms[2]
    }
    val label = "$total $noun"
    return if (hasMore && reportedTotal == null) Lang.t(Lang.Key.LIB_LOADED_COUNT, label) else label
}

internal class ContentRoute(val target: Target) {
    val scroll = LazyListState()
    var tracks by mutableStateOf(PagedList<TrackRow>())
    var playlist by mutableStateOf<com.project.puk.api.PlaylistPage?>(null)
    var album by mutableStateOf<AlbumPage?>(null)
    var artist by mutableStateOf<ArtistPage?>(null)
    var playlistSort by mutableStateOf(PlaylistSort.CUSTOM)
    var playlistQuery by mutableStateOf("")
    val discography = DiscographyState()
}

@Composable
internal fun PlaylistScreen(route: ContentRoute, uri: String, player: com.project.puk.player.PukPlayer, onBack: () -> Unit, onPlay: (List<TrackRow>, Int) -> Unit, onTrackActions: (TrackRow) -> Unit) {
    val context = LocalContext.current
    val playback by remember(player) {
        player.state.map { state ->
            Triple(state.queueContext, state.queue.getOrNull(state.index)?.uri, state.playing)
        }.distinctUntilChanged()
    }.collectAsState(initial = Triple(null, null, false))
    val activeCollection = playback.first == uri && playback.second != null
    val pages = route.tracks
    val page = route.playlist
    val scope = rememberCoroutineScope()
    var editAllowed by remember(uri, pages) { mutableStateOf(false) }
    var editedRow by remember(uri) { mutableStateOf<TrackRow?>(null) }
    var editing by remember(uri) { mutableStateOf(false) }
    var editError by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri, pages, page != null) {
        if (page != null) editAllowed = Spotify.ownedPlaylistDetails(context, uri) != null
    }
    fun edit(row: TrackRow, step: Int) {
        if (editing) return
        editing = true
        editError = false
        scope.launch {
            try {
                if (Spotify.editPlaylistTrack(context, uri, row, step)) {
                    editedRow = null
                    route.tracks = PagedList()
                } else editError = true
            } finally { editing = false }
        }
    }
    var showSort by remember { mutableStateOf(false) }
    val tracks = remember(pages.items, route.playlistSort, route.playlistQuery) {
        sortedPlaylist(pages.items, route.playlistSort).filter { row ->
            val query = route.playlistQuery.trim()
            row.name.contains(query, true) || row.artists.contains(query, true) || row.albumName.contains(query, true)
        }
    }
    // Global ordering needs the whole collection, not separately sorted pages.
    LaunchedEffect(route.playlistSort, route.playlistQuery, pages) {
        if (route.playlistSort == PlaylistSort.CUSTOM && route.playlistQuery.isBlank()) return@LaunchedEffect
        while (pages.offset != null && !pages.failed) {
            androidx.compose.runtime.snapshotFlow { pages.loading }.first { !it }
            coroutineContext.ensureActive()
            pages.load { offset ->
                Spotify.playlist(context, uri, limit = 50, offset = offset)?.let {
                    if (offset == 0) route.playlist = it
                    Spotify.Page(it.tracks, it.next, it.total)
                }
            }
        }
    }
    val actions = page?.let {
        cardActions(Card(uri, it.name, it.owner, it.imageUrl, "playlist"), onOpen = {}, onPlaylistUpdated = { name ->
            route.playlist = route.playlist?.copy(name = name)
        })
    }
    TrackCollection(
        playing = activeCollection && playback.third,
        currentTrackUri = playback.second,
        currentTrackPlaying = playback.third,
        onTogglePlayback = if (activeCollection) ({ player.togglePlayPause() }) else null,
        title = page?.name ?: Lang.t(Lang.Key.JAM_PLAYLIST),
        subtitle = listOfNotNull(page?.owner?.takeIf { it.isNotBlank() },
            playlistCountLabel(pages.items, pages.offset != null, pages.total),
            pages.items.sumOf { it.durationMs }.takeIf { it > 0 }?.let { (if (pages.offset != null) Lang.t(Lang.Key.LIB_LOADED_COUNT) + " " else "") + "${it / 60000} " + Lang.t(Lang.Key.LIB_MIN_SHORT) }).joinToString(" • "),
        imageUrl = page?.imageUrl.orEmpty(), tracks = tracks, scroll = route.scroll,
        searchQuery = route.playlistQuery, onSearchQuery = { route.playlistQuery = it },
        searchLoading = route.playlistQuery.isNotBlank() && pages.offset != null && !pages.failed,
        empty = pages.offset == null && pages.items.isEmpty(), onBack = onBack, onPlay = onPlay,
        onTrackActions = { row ->
            if (editAllowed && row.playlistUid.isNotBlank()) {
                editError = false
                editedRow = row
            } else onTrackActions(row)
        },
        actions = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showSort = true }) { Icon(androidx.compose.ui.res.painterResource(com.project.puk.R.drawable.ember_ctx_sort), Lang.t(Lang.Key.LIB_SORT_BY, route.playlistSort.label), Modifier.size(24.dp)) }
                if (actions != null) IconButton(onClick = actions) { Icon(EmberIcons.MoreVert, Lang.t(Lang.Key.LIB_PLAYLIST_ACTIONS)) }
            }
            if (route.playlistSort != PlaylistSort.CUSTOM && pages.offset != null) Text(Lang.t(Lang.Key.LIB_SORT_PARTIAL_NOTICE), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        },
        footer = { PageFooter(pages) { offset -> Spotify.playlist(context, uri, limit = 50, offset = offset)?.let {
            if (offset == 0) route.playlist = it
            Spotify.Page(it.tracks, it.next, it.total)
        } } },
    )
    editedRow?.let { row ->
        val move = route.playlistSort == PlaylistSort.CUSTOM
        EmberActionSheet(onDismissRequest = { if (!editing) editedRow = null }, heading = row.name) {
            SheetAction(com.project.puk.R.drawable.ember_ctx_open_item, Lang.t(Lang.Key.LIB_TRACK_ACTIONS), enabled = !editing) {
                editedRow = null
                onTrackActions(row)
            }
            if (move && row.playlistIndex > 0) SheetAction(com.project.puk.R.drawable.ember_ctx_sort, Lang.t(Lang.Key.JAM_MOVE_UP), enabled = !editing) { edit(row, -1) }
            if (move && row.playlistIndex < (pages.total ?: pages.items.size) - 1) SheetAction(com.project.puk.R.drawable.ember_ctx_sort, Lang.t(Lang.Key.JAM_MOVE_DOWN), enabled = !editing) { edit(row, 1) }
            SheetAction(com.project.puk.R.drawable.ember_ctx_delete_playlist, Lang.t(Lang.Key.LIB_REMOVE_FROM_PLAYLIST), enabled = !editing) { edit(row, 0) }
            if (editError) TextButtonLite(Lang.t(Lang.Key.LIB_CHANGE_UNCONFIRMED_REFRESH)) {
                editedRow = null
                route.tracks = PagedList()
            }
        }
    }
    if (showSort) EmberActionSheet(onDismissRequest = { showSort = false }, heading = Lang.t(Lang.Key.LIB_SORT_BY), text = {
        PlaylistSort.entries.filter { it != PlaylistSort.ADDED || pages.items.any { row -> row.addedAt.isNotBlank() } }.forEach { order ->
            SheetAction(com.project.puk.R.drawable.ember_ctx_sort, (if (route.playlistSort == order) "✓ " else "") + order.label) { route.playlistSort = order; showSort = false }
        }
    })
}

@Composable
internal fun AlbumScreen(route: ContentRoute, uri: String, onBack: () -> Unit, onPlay: (List<TrackRow>, Int) -> Unit, onTrackActions: (TrackRow) -> Unit) {
    val context = LocalContext.current
    val pages = route.tracks
    val page = route.album
    // ⋯ на экране альбома: тот же шит, что у карточек (сохранение в медиатеку и т.д.).
    val albumActions = page?.let { cardActions(Card(uri, it.name, it.artists, it.imageUrl, "album"), onOpen = null) }
    TrackCollection(
        title = page?.name ?: Lang.t(Lang.Key.LIB_ALBUM_SINGULAR),
        subtitle = listOfNotNull(
            page?.artists?.takeIf { it.isNotBlank() },
            pages.total?.let(::trackCountLabel),
        ).joinToString(" • "),
        imageUrl = page?.imageUrl.orEmpty(),
        tracks = pages.items,
        scroll = route.scroll,
        empty = pages.offset == null && pages.items.isEmpty(),
        onBack = onBack,
        onPlay = onPlay,
        onTrackActions = onTrackActions,
        actions = {
            if (albumActions != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = albumActions) {
                        Icon(EmberIcons.MoreVert, Lang.t(Lang.Key.LIB_COLLECTION_ACTIONS), Modifier.size(24.dp))
                    }
                }
            }
        },
        footer = {
            PageFooter(pages) { offset ->
                Spotify.album(context, uri, offset = offset)?.let {
                    if (offset == 0) route.album = it
                    Spotify.Page(it.tracks, it.next, it.total)
                }
            }
        },
    )
}

/** Real web-player URL of the Liked Songs page (the collection has no other canonical link). */
internal const val LIKED_COLLECTION_URL = "https://open.spotify.com/collection/tracks"

/** Queue context assigned when the engine plays the liked-songs collection. */
internal const val LIKED_COLLECTION_URI = "spotify:collection:tracks"

@Composable
internal fun LikedScreen(
    route: ContentRoute,
    player: com.project.puk.player.PukPlayer,
    onBack: () -> Unit,
    onPlay: (List<TrackRow>, Int) -> Unit,
    onTrackActions: (TrackRow) -> Unit,
) {    val context = LocalContext.current
    val playback by remember(player) {
        player.state.map { state ->
            Triple(state.queueContext, state.queue.getOrNull(state.index)?.uri, state.playing)
        }.distinctUntilChanged()
    }.collectAsState(initial = Triple(null, null, false))
    val activeCollection = playback.first == LIKED_COLLECTION_URI && playback.second != null
    val pages = route.tracks
    val query = route.playlistQuery
    var showActions by remember { mutableStateOf(false) }
    val tracks = remember(pages.items, query, route.playlistSort) {
        sortedPlaylist(pages.items, route.playlistSort).filter { row ->
            val q = query.trim()
            row.name.contains(q, true) || row.artists.contains(q, true) || row.albumName.contains(q, true)
        }
    }
    // Глобальная сортировка требует всю коллекцию: дочитываем страницы,
    // как на плейлистах (реальный обработчик, не декорация).
    LaunchedEffect(route.playlistSort, query, pages) {
        if (route.playlistSort == PlaylistSort.CUSTOM && query.isBlank()) return@LaunchedEffect
        while (pages.offset != null && !pages.failed) {
            androidx.compose.runtime.snapshotFlow { pages.loading }.first { !it }
            coroutineContext.ensureActive()
            pages.load { offset -> Spotify.likedPage(context, offset) }
        }
    }
    // Перемешивание: случайно выбранный старт + shuffle-режим движка
    // (existing handler, не декорация).
    val onShuffle: () -> Unit = {
        val pool = pages.items
        if (pool.isNotEmpty()) {
            player.setShuffle(com.project.puk.player.PukPlayer.Shuffle.ON)
            onPlay(pool, kotlin.random.Random.nextInt(pool.size))
        }
    }
    TrackCollection(
        title = Lang.t(Lang.Key.JAM_LIKED_TRACKS),
        subtitle = pages.total?.let(::trackCountLabel).orEmpty(),
        imageUrl = "",
        playing = activeCollection && playback.third,
        currentTrackUri = playback.second,
        currentTrackPlaying = playback.third,
        onTogglePlayback = if (activeCollection) ({ player.togglePlayPause() }) else null,
        tracks = tracks,
        searchQuery = query, onSearchQuery = { route.playlistQuery = it },
        searchLoading = query.isNotBlank() && pages.offset != null && !pages.failed,
        scroll = route.scroll,
        empty = pages.offset == null && pages.items.isEmpty(),
        liked = true,
        onBack = onBack,
        onPlay = onPlay,
        onTrackActions = onTrackActions,
        actions = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onShuffle,
                    enabled = pages.items.isNotEmpty(),
                ) { Icon(EmberIcons.Shuffle, Lang.t(Lang.Key.LIB_SHUFFLE_LIKED), Modifier.size(24.dp)) }
                // ⋯ коллекции: реальные действия (сортировка/обновление);
                // переименование/владелец/публичность недоступны —
                // «Любимые треки» это служебная коллекция Spotify.
                IconButton(onClick = { showActions = true }) {
                    Icon(EmberIcons.MoreVert, Lang.t(Lang.Key.LIB_COLLECTION_ACTIONS), Modifier.size(24.dp))
                }
            }
        },
        footer = { PageFooter(pages) { offset -> Spotify.likedPage(context, offset) } },
    )
    if (showActions) EmberActionSheet(onDismissRequest = { showActions = false }, heading = Lang.t(Lang.Key.JAM_LIKED_TRACKS), text = {
        PlaylistSort.entries.filter { it != PlaylistSort.ADDED || pages.items.any { row -> row.addedAt.isNotBlank() } }.forEach { order ->
            SheetAction(
                com.project.puk.R.drawable.ember_ctx_sort,
                (if (route.playlistSort == order) "✓ " else "") + order.label,
            ) {
                route.playlistSort = order
                showActions = false
            }
        }
        // Ссылка на страницу «Любимые треки» в веб-плеере Spotify —
        // единственный реальный URL этой служебной коллекции.
        SheetAction(com.project.puk.R.drawable.ember_ctx_copy_link, Lang.t(Lang.Key.SHEET_COPY_LINK)) {
            val clip = context.getSystemService(android.content.ClipboardManager::class.java)
            clip.setPrimaryClip(android.content.ClipData.newPlainText("SpotiPuk", LIKED_COLLECTION_URL))
            android.widget.Toast.makeText(context, Lang.t(Lang.Key.JAM_LINK_COPIED), android.widget.Toast.LENGTH_SHORT).show()
            showActions = false
        }
        SheetAction(com.project.puk.R.drawable.ember_ctx_share_item, Lang.t(Lang.Key.JAM_SHARE)) {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                .putExtra(android.content.Intent.EXTRA_TEXT, Lang.t(Lang.Key.LIB_SHARE_LIKED_TEXT, LIKED_COLLECTION_URL))
                .setType("text/plain")
            context.startActivity(android.content.Intent.createChooser(send, Lang.t(Lang.Key.JAM_SHARE)))
            showActions = false
        }
        SheetAction(com.project.puk.R.drawable.ember_ctx_select_item, Lang.t(Lang.Key.LIB_REFRESH_LIST)) {
            showActions = false
            route.tracks = PagedList()
        }
    })
}

@Composable
internal fun BrowseHeader(
    title: String,
    onProfile: () -> Unit,
    accountName: String,
    onCreatePlaylist: (() -> Unit)? = null,
    accountImageUrl: String = "",
) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, top = 12.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(onProfile, accountName, accountImageUrl)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
        if (onCreatePlaylist != null) {
            IconButton(onClick = onCreatePlaylist) { Icon(androidx.compose.ui.res.painterResource(com.project.puk.R.drawable.ember_ctx_create_playlist), Lang.t(Lang.Key.PLAYER_CREATE), Modifier.size(24.dp)) }
        }
    }
}

@Composable
internal fun ProfileAvatar(onProfile: () -> Unit, accountName: String, accountImageUrl: String = "") {
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onProfile).semantics { contentDescription = Lang.t(Lang.Key.LIB_PROFILE) },
        contentAlignment = Alignment.Center,
    ) {
        Text(accountName.firstOrNull()?.uppercase() ?: "S", style = MaterialTheme.typography.titleMedium)
        if (accountImageUrl.isNotBlank()) Artwork(accountImageUrl, Modifier.fillMaxSize(), circular = true)
    }
}

// ---- shared pieces ----

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
fun CardTile(card: Card, showPlay: Boolean = false, onTrackActions: (() -> Unit)? = null, modifier: Modifier = Modifier.width(160.dp), onPlay: (() -> Unit)? = null, playLoading: Boolean = false, onMoveInLibrary: (() -> Unit)? = null, libraryFolder: String? = null, onClick: () -> Unit) {
    val actions = cardActions(card, onClick, onTrackActions, onMoveInLibrary = onMoveInLibrary, libraryFolder = libraryFolder)
    val press = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .emberPress(press, 0.985f)
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (actions == null) Modifier.clickable(press, LocalIndication.current, onClick = onClick)
                else Modifier.contextGesture(card.title, onClick, actions, press),
            )
            .padding(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            if (card.target() == Target.Liked) LikedArtwork(Modifier.fillMaxSize(), large = true)
            else Artwork(url = card.imageUrl, modifier = Modifier.fillMaxSize(), circular = card.kind == "artist")
            if (card.kind != "artist") Text(if (card.savedEpisodes) Lang.t(Lang.Key.LIB_SAVED_EPISODES) else mediaKindLabel(card.kind), Modifier.align(Alignment.TopStart).padding(6.dp).background(MaterialTheme.colorScheme.background.copy(alpha = .88f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall)
            if (showPlay) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(4.dp).size(48.dp)
                        .clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                        .clickable(enabled = !playLoading, onClickLabel = Lang.t(Lang.Key.LIB_PLAY_TITLE, card.title), onClick = onPlay ?: onClick)
                        .semantics { contentDescription = if (playLoading) Lang.t(Lang.Key.LIB_LOADING_TITLE, card.title) else Lang.t(Lang.Key.LIB_PLAY_TITLE, card.title) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (playLoading) CircularProgressIndicator(Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp) else Icon(EmberIcons.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(card.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (card.subtitle.isNotBlank()) {
            Text(
                card.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ListRow(card: Card, inLibrary: Boolean = false, showKind: Boolean = true, onMoveInLibrary: (() -> Unit)? = null, libraryFolder: String? = null, onClick: () -> Unit) {
    val actions = cardActions(card, onClick, inLibrary = inLibrary, onMoveInLibrary = onMoveInLibrary, libraryFolder = libraryFolder)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (actions == null) Modifier.clickable(onClick = onClick) else Modifier.contextGesture(card.title, onClick, actions))
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        if (card.target() == Target.Liked) {
            LikedArtwork(Modifier.size(64.dp))
        } else {
            Artwork(url = card.imageUrl, modifier = Modifier.size(64.dp), circular = card.kind == "artist")
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(card.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (card.subtitle.isNotBlank()) {
                Text(
                    if (showKind) "${if (card.savedEpisodes) "Сохранённые эпизоды" else mediaKindLabel(card.kind)} • ${card.subtitle}" else card.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    if (card.savedEpisodes) Lang.t(Lang.Key.LIB_SAVED_EPISODES) else mediaKindLabel(card.kind),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

    }
}

@Composable
internal fun TrackRowView(track: TrackRow, onClick: () -> Unit, onActions: () -> Unit, isCurrent: Boolean = false, playing: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { if (isCurrent) stateDescription = if (playing) Lang.t(Lang.Key.PLAYER_NOW_PLAYING) else Lang.t(Lang.Key.LIB_STATE_PAUSED) }
            .background(if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = .08f) else Color.Transparent)
            .contextGesture(track.name, onClick, onActions)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Artwork(url = track.imageUrl, modifier = Modifier.size(48.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(track.name, style = MaterialTheme.typography.titleSmall, color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                track.artists,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}


@Composable
internal fun LikedArtwork(modifier: Modifier, large: Boolean = false) {
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(
            Brush.linearGradient(listOf(EmberColors.PrimaryPressed, EmberColors.PrimaryLight)),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(EmberIcons.Favorite, contentDescription = null, tint = EmberColors.OnPrimary,
            modifier = if (large) Modifier.fillMaxSize(.34f) else Modifier.size(24.dp))
    }
}

@Composable
fun Artwork(url: String, modifier: Modifier = Modifier, circular: Boolean = false) {
    val shape = if (circular) CircleShape else RoundedCornerShape(8.dp)
    val frame = modifier.clip(shape)
    if (url.isBlank()) {
        ArtworkPlaceholder(frame, circular)
    } else {
        // Plain AsyncImage over an icon placeholder: no subcomposition per
        // image. SubcomposeAsyncImage measured measurably heavy on long
        // scrolls of large grids (tablet Home), the placeholder shows until
        // the bitmap lands and covers it.
        Box(frame) {
            ArtworkPlaceholder(Modifier.fillMaxSize(), circular)
            AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun ArtworkPlaceholder(modifier: Modifier, circular: Boolean) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (circular) EmberIcons.Person else EmberIcons.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun Header(title: String, subtitle: String, imageUrl: String, onBack: () -> Unit, liked: Boolean = false) {
    Column(modifier = Modifier.padding(16.dp)) {
        TextButtonLite(Lang.t(Lang.Key.LIB_BACK_ARROW), onClick = onBack)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (liked) {
                LikedArtwork(Modifier.size(120.dp))
            } else {
                Artwork(url = imageUrl, modifier = Modifier.size(120.dp))
            }
            Column {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun TextButtonLite(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
fun LoadingWhen(empty: Boolean, label: String, content: @Composable () -> Unit) {
    if (empty) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        content()
    }
}

/**
 * Soft darkening of the status-bar strip while list content scrolls beneath
 * it, keeping the system icons readable without an opaque band.
 */
@Composable
internal fun StatusBarScrim(scrolled: Boolean, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(
        targetValue = if (scrolled) 0.45f else 0f,
        animationSpec = tween(EmberMotion.Standard),
        label = "Ember status scrim",
    )
    if (alpha <= 0.01f) return
    val stripHeight = with(LocalDensity.current) {
        WindowInsets.statusBars.getTop(this).toDp() + 20.dp
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(stripHeight)
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = alpha), Color.Transparent),
                ),
            ),
    )
}
