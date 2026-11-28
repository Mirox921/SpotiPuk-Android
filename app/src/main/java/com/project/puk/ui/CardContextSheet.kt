package com.project.puk.ui

import com.project.puk.core.Lang

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.project.puk.R
import com.project.puk.api.Card
import com.project.puk.api.Spotify
import com.project.puk.api.TrackRow
import com.project.puk.ui.theme.EmberIcons
import kotlinx.coroutines.launch

internal val LocalContextPlayCollection =
    staticCompositionLocalOf<((String, List<TrackRow>) -> Unit)?> { null }

internal val LocalContextEnqueueCollection =
    staticCompositionLocalOf<((List<TrackRow>) -> Boolean)?> { null }

internal val LocalContextOpenTarget = staticCompositionLocalOf<((Target) -> Unit)?> { null }

/** Активный SpotiPuk Jam: добавить плейлист целиком в общую очередь. */
internal val LocalContextJamAddCollection =
    staticCompositionLocalOf<((String, List<TrackRow>) -> Unit)?> { null }

internal val LocalArtistPolicyChanged = staticCompositionLocalOf<(String, Boolean) -> Unit> { { _, _ -> } }

internal val LocalContextLibraryChanged = staticCompositionLocalOf<() -> Unit> { {} }

internal fun Card.contextShareUrl(): String? {
    val entityKind = if (kind == "podcast") "show" else kind
    if (entityKind !in setOf("album", "playlist", "artist", "show", "audiobook")) return null
    val prefix = "spotify:$entityKind:"
    if (!uri.startsWith(prefix)) return null
    val id = uri.removePrefix(prefix)
    return "https://open.spotify.com/$entityKind/$id".takeIf {
        id.length == 22 && id.all { char -> char in '0'..'9' || char in 'A'..'Z' || char in 'a'..'z' }
    }
}

@Composable
private fun CardContextSheet(
    card: Card,
    shareUrl: String,
    actions: @Composable () -> Unit,
    onOpen: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    EmberSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Artwork(card.imageUrl, Modifier.size(104.dp), circular = card.kind == "artist")
            Text(
                card.displayTitle,
                Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (card.subtitle.isNotBlank()) {
                Text(
                    card.subtitle,
                    Modifier.padding(horizontal = 24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (onOpen != null || card.kind == "audiobook") SheetAction(when (card.kind) {
                "show", "podcast" -> R.drawable.ember_ctx_podcast_show_open
                "audiobook" -> R.drawable.ember_ctx_audiobook_open
                else -> R.drawable.ember_ctx_open_item
            }, Lang.t(Lang.Key.SHEET_OPEN)) {
                onDismiss()
                if (card.kind == "audiobook") openExternalSpotify(context, shareUrl) else onOpen?.invoke()
            }
            actions()
            SheetAction(if (card.uri.startsWith("spotify:show:")) R.drawable.ember_ctx_podcast_share else R.drawable.ember_ctx_share_item, Lang.t(Lang.Key.JAM_SHARE)) {
                val share = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, shareUrl)
                context.startActivity(Intent.createChooser(share, null))
            }
            SheetAction(R.drawable.ember_ctx_copy_link, Lang.t(Lang.Key.SHEET_COPY_LINK)) {
                copyContextText(context, card.displayTitle, shareUrl)
            }
        }
    }
}

@Composable
internal fun cardActions(
    card: Card,
    onOpen: (() -> Unit)?,
    trackActions: (() -> Unit)? = null,
    inLibrary: Boolean = false,
    onPlaylistUpdated: (String) -> Unit = {},
    onMoveInLibrary: (() -> Unit)? = null,
    libraryFolder: String? = null,
): (() -> Unit)? {
    if (trackActions != null) return trackActions
    val shareUrl = card.contextShareUrl() ?: return null
    var visible by remember(card.uri) { mutableStateOf(false) }
    if (visible) {
        val libraryChanged = LocalContextLibraryChanged.current
        val playCollection = LocalContextPlayCollection.current
        val enqueueCollection = LocalContextEnqueueCollection.current
        val jamAddCollection = LocalContextJamAddCollection.current
        CardContextSheet(
            card = card,
            shareUrl = shareUrl,
            actions = {
                if (playCollection != null && (card.kind == "album" || card.kind == "playlist")) {
                    CardPlaybackActions(
                        card,
                        onPlay = { tracks ->
                            visible = false
                            playCollection(card.uri, tracks)
                        },
                        onEnqueue = enqueueCollection?.let { enqueue ->
                            { tracks ->
                                enqueue(tracks).also { accepted -> if (accepted) visible = false }
                            }
                        },
                    )
                }
                LibraryPinAction(card, libraryFolder) { visible = false; libraryChanged() }
                if (onMoveInLibrary != null) {
                    SheetAction(R.drawable.ember_ctx_move_folder, Lang.t(Lang.Key.SHEET_MOVE_TO_SPOTIFY_FOLDER)) {
                        visible = false
                        onMoveInLibrary()
                    }
                }
                if (card.kind == "artist") {
                    ArtistRadioAction(card) { visible = false }
                    ArtistBlockAction(card)
                }
                if (card.uri.startsWith("spotify:show:")) {
                    ShowPlaybackActions(card, onStarted = { visible = false })
                }
                if (jamAddCollection != null && card.kind == "playlist") {
                    SheetAction(EmberIcons.Groups, Lang.t(Lang.Key.SHEET_ADD_PLAYLIST_TO_JAM)) {
                        jamAddCollection(card.uri, emptyList())
                        visible = false
                    }
                }
                if (card.kind == "playlist") {
                    OwnedPlaylistActions(card.uri) { name ->
                        onPlaylistUpdated(name)
                        visible = false
                        libraryChanged()
                    }
                }
                CardLibraryAction(card, inLibrary) {
                    visible = false
                    libraryChanged()
                }
            },
            onOpen = onOpen,
            onDismiss = { visible = false },
        )
    }
    return { visible = true }
}


internal fun cardLibraryLabel(kind: String, saved: Boolean): String? = when (kind) {
    "album", "playlist", "show", "podcast", "audiobook" -> if (saved) Lang.t(Lang.Key.SHEET_REMOVE_FROM_LIBRARY) else Lang.t(Lang.Key.SHEET_SAVE_TO_LIBRARY)
    "artist" -> if (saved) Lang.t(Lang.Key.SHEET_UNFOLLOW_ARTIST) else Lang.t(Lang.Key.SHEET_FOLLOW_ARTIST)
    else -> null
}

@Composable
internal fun CardLibraryAction(card: Card, inLibrary: Boolean, onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val knownPlaylist = inLibrary && card.kind == "playlist"
    var saved by remember(card.uri, knownPlaylist) { mutableStateOf<Boolean?>(true.takeIf { knownPlaylist }) }
    var updating by remember(card.uri) { mutableStateOf(false) }
    var failed by remember(card.uri) { mutableStateOf(false) }
    var confirmRemoval by remember(card.uri) { mutableStateOf(false) }
    LaunchedEffect(card.uri, knownPlaylist) {
        // isSaved может не ответить (станции/миксы Spotify) — тогда считаем
        // несохранённым и всё равно показываем действие, а не прячем его.
        if (!knownPlaylist) saved = Spotify.isSaved(context, card.uri) ?: false
    }
    // Reserve the action row while the lookup runs so the sheet keeps its opening anchor.
    val current = saved == true
    val label = cardLibraryLabel(card.kind, current) ?: return
    val removesPlaylist = current && card.kind == "playlist"
    fun updateLibrary() {
        if (updating || saved == null) return
        updating = true
        failed = false
        scope.launch {
            try {
                val changed = if (removesPlaylist) Spotify.removePlaylistFromLibrary(context, card.uri)
                else Spotify.setSaved(context, card.uri, !current)
                if (changed) onChanged() else failed = true
            } finally {
                updating = false
            }
        }
    }
    SheetAction(
        when {
            card.kind == "artist" -> ImageVector.vectorResource(
                if (current) R.drawable.ember_ctx_unfollow_artist else R.drawable.ember_ctx_follow_artist,
            )
            current -> EmberIcons.LibraryRemove
            else -> EmberIcons.LibraryAdd
        },
        if (updating) Lang.t(Lang.Key.SHEET_SAVING_CHANGES) else label,
        enabled = !updating && saved != null,
    ) {
        if (removesPlaylist) confirmRemoval = true else updateLibrary()
    }
    if (confirmRemoval) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text(Lang.t(Lang.Key.SHEET_REMOVE_PLAYLIST_CONFIRM)) },
            text = { Text(Lang.t(Lang.Key.SHEET_REMOVE_CONFIRM, card.displayTitle)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoval = false
                    updateLibrary()
                }) { Text(Lang.t(Lang.Key.JAM_DELETE), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoval = false }) { Text(Lang.t(Lang.Key.JAM_CANCEL)) }
            },
        )
    }
    if (failed) {
        Text(
            Lang.t(Lang.Key.SHEET_LIBRARY_UPDATE_FAILED),
            Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

private enum class CardPlaybackAction { Play, Enqueue }

@Composable
private fun CardPlaybackActions(
    card: Card,
    onPlay: (List<TrackRow>) -> Unit,
    onEnqueue: ((List<TrackRow>) -> Boolean)?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentEnqueue by androidx.compose.runtime.rememberUpdatedState(onEnqueue)
    var loading by remember(card.uri) { mutableStateOf<CardPlaybackAction?>(null) }
    var error by remember(card.uri) { mutableStateOf<String?>(null) }
    fun start(action: CardPlaybackAction) {
        if (loading != null) return
        loading = action
        error = null
        scope.launch {
            try {
                val tracks = loadContextTracks { offset ->
                    if (card.kind == "album") {
                        Spotify.album(context, card.uri, offset = offset)?.let { Spotify.Page(it.tracks, it.next) }
                    } else {
                        Spotify.playlist(context, card.uri, limit = 50, offset = offset)?.let { Spotify.Page(it.tracks, it.next) }
                    }
                }
                if (tracks.isNullOrEmpty()) {
                    error = Lang.t(Lang.Key.SHEET_LOAD_TRACKS_FAILED)
                } else if (action == CardPlaybackAction.Play) {
                    onPlay(tracks)
                } else if (currentEnqueue?.invoke(tracks) != true) {
                    error = Lang.t(Lang.Key.SHEET_LOCAL_QUEUE_UNAVAILABLE)
                }
            } finally {
                loading = null
            }
        }
    }
    SheetAction(
        R.drawable.ember_ctx_play_collection,
        if (loading == CardPlaybackAction.Play) Lang.t(Lang.Key.SHEET_LOADING_TRACKS) else Lang.t(Lang.Key.SHEET_PLAY),
        enabled = loading == null,
    ) { start(CardPlaybackAction.Play) }
    if (onEnqueue != null || loading == CardPlaybackAction.Enqueue) {
        SheetAction(
            R.drawable.ember_ctx_add_to_queue,
            if (loading == CardPlaybackAction.Enqueue) Lang.t(Lang.Key.SHEET_LOADING_TRACKS) else Lang.t(Lang.Key.SHEET_ADD_TO_QUEUE),
            enabled = loading == null,
        ) { start(CardPlaybackAction.Enqueue) }
    }
    error?.let {
        Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
    }
}

internal suspend fun loadContextTracks(
    fetch: suspend (Int) -> Spotify.Page<TrackRow>?,
): List<TrackRow>? {
    val tracks = mutableListOf<TrackRow>()
    var offset: Int? = 0
    while (offset != null) {
        val page = fetch(offset) ?: return null
        val next = page.next
        if (next != null && next <= offset) return null
        tracks.addAll(page.items)
        offset = next
    }
    return tracks
}


@Composable
private fun LibraryPinAction(card: Card, folder: String?, onChanged: () -> Unit) {
    val pinned = card.pinned ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    SheetAction(R.drawable.ember_ctx_pin_library, if (pinned) Lang.t(Lang.Key.SHEET_UNPIN) else Lang.t(Lang.Key.SHEET_PIN), enabled = !busy) {
        busy = true
        error = false
        scope.launch {
            try {
                if (Spotify.setLibraryPinned(context, card.uri, !pinned, folder)) onChanged()
                else error = true
            } finally {
                busy = false
            }
        }
    }
    if (error) Text(Lang.t(Lang.Key.SHEET_CHANGE_NOT_CONFIRMED), color = MaterialTheme.colorScheme.error)
}


@Composable
private fun ArtistBlockAction(card: Card) {
    val context = LocalContext.current
    val onChanged = LocalArtistPolicyChanged.current
    val scope = rememberCoroutineScope()
    var local by remember(card.uri) { mutableStateOf(card.uri in Spotify.localBlockedArtists(context)) }
    var remote by remember(card.uri) { mutableStateOf<Boolean?>(null) }
    var loaded by remember(card.uri) { mutableStateOf(false) }
    var busy by remember(card.uri) { mutableStateOf(false) }
    var error by remember(card.uri) { mutableStateOf(false) }
    LaunchedEffect(card.uri) {
        remote = Spotify.artistBlocked(context, card.uri)
        loaded = true
    }
    if (!loaded) return
    val previous = local || remote == true
    val label = when {
        remote == null && previous -> Lang.t(Lang.Key.SHEET_ALLOW_ON_THIS_DEVICE)
        remote == null -> Lang.t(Lang.Key.SHEET_NO_PLAYBACK_THIS_DEVICE)
        previous -> Lang.t(Lang.Key.SHEET_ALLOW_PLAYBACK)
        else -> Lang.t(Lang.Key.SHEET_DONT_PLAY_ARTIST)
    }
    SheetAction(R.drawable.ember_ctx_block_artist, label, enabled = !busy) {
        busy = true
        error = false
        scope.launch {
            try {
                val desired = !previous
                val currentRemote = remote
                if (currentRemote == null) {
                    if (!Spotify.setLocalArtistBlocked(context, card.uri, desired)) {
                        error = true
                        return@launch
                    }
                    local = desired
                } else {
                    if (currentRemote != desired && !Spotify.setArtistBlocked(context, card.uri, desired)) {
                        error = true
                        return@launch
                    }
                    remote = desired
                    if (local && Spotify.setLocalArtistBlocked(context, card.uri, false)) local = false
                }
                onChanged(card.uri, desired)
            } finally {
                busy = false
            }
        }
    }
    if (error) Text(Lang.t(Lang.Key.SHEET_CHANGE_NOT_CONFIRMED), color = MaterialTheme.colorScheme.error)
}
