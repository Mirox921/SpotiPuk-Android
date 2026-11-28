package com.project.puk.ui

import com.project.puk.core.Lang

import androidx.compose.animation.animateContentSize
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.project.puk.R
import com.project.puk.api.EpisodeDetail
import androidx.compose.runtime.collectAsState
import com.project.puk.player.PukPlayer
import com.project.puk.ui.theme.EmberIcons
import com.project.puk.ui.theme.EmberMotion
import com.project.puk.api.EpisodeRow
import com.project.puk.api.ShowDetail
import com.project.puk.api.Spotify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Formats ms as h:mm:ss when over an hour, else m:ss. */
internal fun podcastDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Show page: header (art, publisher, expandable description) + paginated episode list. */
@Composable
fun ShowScreen(uri: String, onBack: () -> Unit, onPlayEpisode: (EpisodeRow, String) -> Unit, onOpenEpisode: (String) -> Unit, onEpisodeActions: (PukPlayer.QueueItem) -> Unit) {
    val context = LocalContext.current
    var page by remember(uri) { mutableStateOf<ShowDetail?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    var retry by remember(uri) { mutableStateOf(0) }
    var expandedDescription by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(uri, retry) {
        if (page != null && retry == 0) return@LaunchedEffect
        failed = false
        page = runCatching { Spotify.show(context, uri) }.getOrNull()
        if (page == null) failed = true
    }

    LoadingWhen(page == null && !failed, label = Lang.t(Lang.Key.PLAYER_PODCAST)) {
        if (failed) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(Lang.t(Lang.Key.POD_PODCAST_UNAVAILABLE), style = MaterialTheme.typography.titleMedium)
                TextButtonLite(Lang.t(Lang.Key.LIB_RETRY)) { retry++ }
            }
            return@LoadingWhen
        }
        val show = page!!
        val showCard = com.project.puk.api.Card(show.uri, show.name, show.publisher, show.imageUrl, "show")
        val showActions = cardActions(showCard, onOpen = { expandedDescription = !expandedDescription })
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                bottom = LocalBrowseBottomPadding.current + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Column(Modifier.padding(16.dp)) {
                    TextButtonLite(Lang.t(Lang.Key.LIB_BACK_ARROW), onClick = onBack)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = if (showActions == null) Modifier else Modifier.contextGesture(
                            show.name, { expandedDescription = !expandedDescription }, showActions),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(url = show.imageUrl, modifier = Modifier.size(128.dp))
                        Column(Modifier.weight(1f)) {
                            Text(show.name, style = MaterialTheme.typography.headlineSmall)
                            if (show.publisher.isNotBlank()) {
                                Text(
                                    show.publisher,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (show.mediaType == "VIDEO") {
                                Text(
                                    Lang.t(Lang.Key.POD_VIDEO_AUDIO),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (show.description.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            show.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (expandedDescription) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .animateContentSize()
                                .clickable { expandedDescription = !expandedDescription },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        Lang.t(Lang.Key.POD_EPISODES_COUNT, show.total),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            episodeItems(show.episodes, key = { _, e -> e.uri }) { _, episode ->
                EpisodeRowView(episode = episode, onClick = { onOpenEpisode(episode.uri) }, onPlay = { onPlayEpisode(episode, show.name) }, onActions = { onEpisodeActions(episode.asQueueItem(show.name, show.uri)) })
            }
            if (show.nextOffset != null) {
                item {
                    var loadingMore by remember(show.uri) { mutableStateOf(false) }
                    androidx.compose.runtime.LaunchedEffect(show.nextOffset, loadingMore) {
                        if (loadingMore) {
                            try {
                                val more = Spotify.show(context, uri, page)
                                if (more != null) page = more
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                delay(1500L)
                            }
                            loadingMore = false
                        }
                    }
                    TextButtonLite(
                        if (loadingMore) Lang.t(Lang.Key.LIB_LOADING) else Lang.t(Lang.Key.LIB_SHOW_MORE),
                        onClick = { loadingMore = true },
                    )
                }
            }
        }
    }
}

/** One episode row inside the show page. */
@Composable
internal fun EpisodeRowView(episode: EpisodeRow, onClick: () -> Unit, onPlay: () -> Unit, onActions: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .contextGesture(episode.name, onClick, onActions)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Artwork(url = episode.imageUrl, modifier = Modifier.size(64.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    episode.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(episode.releaseDate)
                        if (episode.durationMs > 0) {
                            append(" · ")
                            append(podcastDuration(episode.durationMs))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (episode.playable) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onPlay),
                ) {
                    androidx.compose.material3.Icon(
                        EmberIcons.PlayArrow,
                        contentDescription = Lang.t(Lang.Key.JAM_PLAY),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
        // Listening progress under the row.
        val fraction = if (episode.played || episode.durationMs <= 0) {
            if (episode.played) 1f else 0f
        } else {
            (episode.positionMs.toFloat() / episode.durationMs).coerceIn(0f, 1f)
        }
        if (fraction > 0f) {
            Spacer(Modifier.height(6.dp))
            androidx.compose.material3.LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}

/** Episode page: art, description, play/resume, share, chapters. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeScreen(
    uri: String,
    player: PukPlayer,
    onBack: () -> Unit,
    onPlay: (EpisodeDetail) -> Unit,
    onOpenShow: (String) -> Unit,
) {
    val context = LocalContext.current
    var detail by remember(uri) { mutableStateOf<EpisodeDetail?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    var retry by remember(uri) { mutableStateOf(0) }
    var episodeActions by remember(uri) { mutableStateOf(false) }
    var chapters by remember(uri) { mutableStateOf<List<com.project.puk.api.ChapterSpan>?>(null) }

    androidx.compose.runtime.LaunchedEffect(uri, retry) {
        if (detail != null && retry == 0) return@LaunchedEffect
        failed = false
        detail = runCatching { Spotify.episode(context, uri) }.getOrNull()
        if (detail == null) failed = true
    }
    androidx.compose.runtime.LaunchedEffect(detail) {
        if (detail != null && chapters == null) {
            chapters = runCatching { Spotify.chapters(context, uri) }.getOrNull().takeIf { it.isNullOrEmpty() == false }
        }
    }

    LoadingWhen(detail == null && !failed, label = Lang.t(Lang.Key.LIB_EPISODE_SINGULAR)) {
        if (failed) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(Lang.t(Lang.Key.POD_EPISODE_UNAVAILABLE), style = MaterialTheme.typography.titleMedium)
                TextButtonLite(Lang.t(Lang.Key.LIB_RETRY)) { retry++ }
            }
            return@LoadingWhen
        }
        val episode = detail!!
        if (episodeActions) {
            EpisodeActionSheet(episode.asQueueItem(), onDismiss = { episodeActions = false },
                onEnqueue = { player.enqueue(episode.asQueueItem()) }, onOpenShow = onOpenShow,
                onLibraryChanged = LocalContextLibraryChanged.current,
                onPlay = if (episode.playable) ({ onPlay(episode) }) else null,
                onPlayNext = if (player.canEditQueue()) ({ player.enqueueNext(episode.asQueueItem()) }) else null,
                sharePositionMs = episode.positionMs.takeIf { it > 0 })
        }

        // Reading-progress position drives the active chapter highlight.
        val playback by player.state.collectAsState()
        val activeChapter by remember(chapters) {
            androidx.compose.runtime.derivedStateOf {
                podcastChaptersCurrent(
                    chapters,
                    playback.positionMs,
                )
            }
        }
        var expandedDescription by remember(uri) { mutableStateOf(false) }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                bottom = LocalBrowseBottomPadding.current + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Column(Modifier.padding(16.dp)) {
                    TextButtonLite(Lang.t(Lang.Key.LIB_BACK_ARROW), onClick = onBack)
                    Spacer(Modifier.height(8.dp))
                    Artwork(url = episode.imageUrl, modifier = Modifier.fillMaxWidth().height(220.dp).contextGesture(episode.name, { episodeActions = true }, { episodeActions = true }))
                    Spacer(Modifier.height(14.dp))
                    AdaptiveEpisodeTitle(episode.name)
                    Text(
                        episode.showName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = episode.showUri.isNotBlank()) { onOpenShow(episode.showUri) }
                            .padding(vertical = 4.dp),
                    )
                    Text(
                        buildString {
                            append(episode.releaseDate)
                            if (episode.durationMs > 0) {
                                append(" · ")
                                append(podcastDuration(episode.durationMs))
                            }
                            if (!episode.playable) append(" " + Lang.t(Lang.Key.POD_UNAVAILABLE_SUFFIX))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (episode.playable) {
                        androidx.compose.material3.Button(
                            onClick = { onPlay(episode) },
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (episode.positionMs > 0L) Lang.t(Lang.Key.POD_CONTINUE_AT, podcastDuration(episode.positionMs)) else Lang.t(Lang.Key.POD_LISTEN))
                        }
                    } else {
                        Text(
                            Lang.t(Lang.Key.POD_AUDIO_SOURCE_UNAVAILABLE),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextButtonLite(Lang.t(Lang.Key.JAM_SHARE)) {
                            val share = android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_TEXT, episode.shareUrl)
                            context.startActivity(
                                android.content.Intent.createChooser(share, null),
                            )
                        }
                        if (episode.showUri.isNotBlank()) {
                            TextButtonLite(Lang.t(Lang.Key.POD_OPEN_PODCAST)) { onOpenShow(episode.showUri) }
                        }
                    }
                }
            }
            // Chapters come before the long description so they are always reachable.
            chapters?.takeIf { it.isNotEmpty() }?.let { spans ->
                item {
                    Text(
                        Lang.t(Lang.Key.PLAYER_CHAPTERS),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                chapterItems(episode.uri, spans, activeChapter, onSeek = { position ->
                    if (player.current()?.uri == episode.uri) player.seekTo(position)
                    else if (episode.playable) onPlay(episode.copy(positionMs = position))
                })
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    if (episode.description.isNotBlank()) {
                        val description = linkifiedDescription(episode.description, MaterialTheme.colorScheme.primary)
                        androidx.compose.foundation.text.ClickableText(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            maxLines = if (expandedDescription) Int.MAX_VALUE else 5,
                            overflow = TextOverflow.Ellipsis,
                            onClick = { offset ->
                                description.getStringAnnotations(URL_TAG, offset, offset)
                                    .firstOrNull()?.let { annotation ->
                                        runCatching {
                                            context.startActivity(
                                                android.content.Intent(
                                                    android.content.Intent.ACTION_VIEW,
                                                    android.net.Uri.parse(annotation.item),
                                                ),
                                            )
                                        }
                                    }
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .animateContentSize(androidx.compose.animation.core.tween(EmberMotion.Standard)),
                        )
                        Spacer(Modifier.height(4.dp))
                        TextButtonLite(
                            if (expandedDescription) Lang.t(Lang.Key.PLAYER_COLLAPSE) else Lang.t(Lang.Key.POD_SHOW_FULL),
                            onClick = { expandedDescription = !expandedDescription },
                        )
                    }
                }
            }
        }
    }
}

/** Two or three admissible title sizes chosen by overflow, never by raw length. */
@Composable
internal fun AdaptiveEpisodeTitle(title: String) {
    var compact by remember(title) { mutableStateOf(false) }
    Text(
        title,
        style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { result ->
            if (!compact && result != null && result.hasVisualOverflow) compact = true
        },
    )
}

/** Makes bare URLs inside the description tappable without breaking line wrapping. */
private const val URL_TAG = "episode-url"

@Composable
private fun linkifiedDescription(text: String, accent: androidx.compose.ui.graphics.Color) =
    androidx.compose.runtime.remember(text, accent) { annotatedEpisodeDescription(text, accent) }

internal fun annotatedEpisodeDescription(text: String, accent: androidx.compose.ui.graphics.Color) =
    androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        Regex("https?://[\\w./%#?=+&~-]+[\\w/#?=-]").findAll(text).forEach { match ->
            val end = match.range.last + 1
            addStringAnnotation(URL_TAG, match.value, match.range.first, end)
            addStyle(
                androidx.compose.ui.text.SpanStyle(
                    color = accent,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                ),
                match.range.first, end,
            )
        }
    }

private fun androidx.compose.foundation.lazy.LazyListScope.episodeItems(
    items: List<EpisodeRow>,
    key: (Int, EpisodeRow) -> String,
    itemContent: @Composable (Int, EpisodeRow) -> Unit,
) {
    items(items.size, key = { index -> key(index, items[index]) }) { index ->
        itemContent(index, items[index])
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.chapterItems(
    uri: String,
    spans: List<com.project.puk.api.ChapterSpan>,
    activeIndex: Int,
    onSeek: (Long) -> Unit,
) {
    items(spans.size, key = { index -> spans[index].startMs }) { index ->
        val chapter = spans[index]
        val active = index == activeIndex
        var showFullTitle by remember(chapter.startMs) { mutableStateOf(false) }
        Row(
            Modifier
                .fillMaxWidth()
                .contextGesture(
                    title = chapter.title,
                    onClick = { onSeek(chapter.startMs) },
                    onActions = { showFullTitle = true },
                )
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                podcastDuration(chapter.startMs),
                style = MaterialTheme.typography.labelMedium,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(88.dp),
            )
            Text(
                chapter.title,
                style = if (active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (showFullTitle) {
            ChapterActionSheet(uri, chapter,
                onSeek = { onSeek(chapter.startMs) }, onDismiss = { showFullTitle = false })
        }
    }
}

/** Index of the chapter containing [positionMs]; -1 when none is active. */
internal fun podcastChaptersCurrent(spans: List<com.project.puk.api.ChapterSpan>?, positionMs: Long): Int {
    if (spans.isNullOrEmpty()) return -1
    var active = -1
    for (index in spans.indices) {
        if (spans[index].startMs <= positionMs) active = index else break
    }
    return active
}

/** Episodes reuse the music queue item; "artist" carries the show name. */
internal fun EpisodeRow.asQueueItem(showName: String, showUri: String = "") = PukPlayer.QueueItem(
    uri = uri, title = name, artist = showName, albumUri = showUri,
    imageUrl = imageUrl, durationMs = durationMs, artistUri = "",
)

internal fun EpisodeDetail.asQueueItem() = PukPlayer.QueueItem(
    uri = uri, title = name, artist = showName, albumUri = showUri,
    imageUrl = imageUrl, durationMs = durationMs, artistUri = "",
)

/**
 * Context sheet for a podcast episode: save, queue, share, sleep timer and
 * the show route. Only actions with real handlers are listed.
 */
@Composable
internal fun EpisodeActionSheet(
    item: PukPlayer.QueueItem,
    onDismiss: () -> Unit,
    onEnqueue: () -> Unit,
    onOpenShow: (String) -> Unit,
    onLibraryChanged: () -> Unit,
    onSleepTimer: (() -> Unit)? = null,
    onOpenEpisode: (() -> Unit)? = null,
    onPlay: (() -> Unit)? = null,
    onPlayNext: (() -> Unit)? = null,
    sharePositionMs: Long? = null,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var saved by remember(item.uri) { mutableStateOf<Boolean?>(null) }
    var saving by remember(item.uri) { mutableStateOf(false) }
    LaunchedEffect(item.uri) { saved = runCatching { Spotify.isSaved(context, item.uri) }.getOrNull() }

    com.project.puk.ui.EmberSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Artwork(url = item.imageUrl, modifier = Modifier.size(96.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                item.title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (item.artist.isNotBlank()) {
                Text(
                    item.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(16.dp))
            onOpenEpisode?.let { open ->
                SheetAction(R.drawable.ember_ctx_episode_open, Lang.t(Lang.Key.POD_OPEN_EPISODE)) { onDismiss(); open() }
            }
            onPlay?.let { play ->
                SheetAction(R.drawable.ember_ctx_episode_resume, Lang.t(Lang.Key.POD_LISTEN_RESUME)) { onDismiss(); play() }
            }
            // Full-width action rows stay readable with larger accessibility fonts.
            Column(Modifier.fillMaxWidth()) {
                SheetAction(
                    icon = if (saved == true) EmberIcons.Bookmark else EmberIcons.BookmarkBorder,
                    label = if (saving) "…" else if (saved == true) Lang.t(Lang.Key.POD_IN_LIBRARY) else Lang.t(Lang.Key.LIB_SAVE),
                    enabled = saved != null && !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            try {
                                val current = saved ?: false
                                if (Spotify.setSaved(context, item.uri, !current)) {
                                    saved = !current
                                    onLibraryChanged()
                                }
                            } finally {
                                saving = false
                            }
                        }
                    },
                )
                SheetAction(iconRes = R.drawable.ember_ctx_add_to_queue, label = Lang.t(Lang.Key.POD_ADD_TO_QUEUE), onClick = {
                    if (runLocalQueueAction(context, onEnqueue)) onDismiss()
                })
                onPlayNext?.let { next ->
                    SheetAction(R.drawable.ember_ctx_play_next, Lang.t(Lang.Key.PLAYER_PLAY_NEXT)) {
                        if (runLocalQueueAction(context, next)) onDismiss()
                    }
                }
                SheetAction(iconRes = R.drawable.ember_ctx_share_item, label = Lang.t(Lang.Key.JAM_SHARE), onClick = {
                    val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(
                            Intent.EXTRA_TEXT,
                            "https://open.spotify.com/episode/${item.uri.substringAfterLast(':')}",
                        )
                    context.startActivity(Intent.createChooser(share, null))
                })
            }
            Divider(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            val link = episodeMomentUrl(item.uri, 0)?.substringBefore('?')
            if (link != null) {
                SheetAction(R.drawable.ember_ctx_copy_link, Lang.t(Lang.Key.SHEET_COPY_LINK)) { copyContextText(context, item.title, link) }
            }
            if (sharePositionMs != null) {
                episodeMomentUrl(item.uri, sharePositionMs)?.let { url ->
                    SheetAction(R.drawable.ember_ctx_episode_share_moment, Lang.t(Lang.Key.POD_SHARE_MOMENT, podcastDuration(sharePositionMs))) {
                        shareContextText(context, url)
                    }
                }
            }
            if (item.albumUri.startsWith("spotify:show:")) {
                SheetAction(R.drawable.ember_ctx_go_to_show, Lang.t(Lang.Key.POD_OPEN_PODCAST)) {
                    onDismiss()
                    onOpenShow(item.albumUri)
                }
            }
            onSleepTimer?.let { timer ->
                SheetAction(R.drawable.ember_ctx_sleep_timer, Lang.t(Lang.Key.PLAYER_SLEEP_TIMER)) { onDismiss(); timer() }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
