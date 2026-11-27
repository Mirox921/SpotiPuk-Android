package com.project.puk.ui

import com.project.puk.core.Lang

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.project.puk.api.Card
import com.project.puk.api.Spotify
import com.project.puk.api.TrackRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Card navigation and collection playback share the existing account/session callbacks. */
@Composable
internal fun HomeMediaCard(card: Card, track: TrackRow?, onPlayTrack: () -> Unit, onTrackActions: (() -> Unit)?, onOpen: () -> Unit) {
    val context = LocalContext.current
    val playCollection = LocalContextPlayCollection.current
    val scope = rememberCoroutineScope()
    var loading by remember(card.uri) { mutableStateOf(false) }
    var error by remember(card.uri) { mutableStateOf(false) }
    val playableCollection = playCollection != null && card.kind in setOf("playlist", "album")
    fun play() {
        if (track != null) { onPlayTrack(); return }
        if (!playableCollection || loading) return
        loading = true
        scope.launch {
            try {
                val tracks = loadContextTracks { offset ->
                    if (card.kind == "album") Spotify.album(context, card.uri, offset = offset)?.let { Spotify.Page(it.tracks, it.next) }
                    else Spotify.playlist(context, card.uri, limit = 50, offset = offset)?.let { Spotify.Page(it.tracks, it.next) }
                }
                if (tracks.isNullOrEmpty()) error = true else playCollection?.invoke(card.uri, tracks)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
            finally { loading = false }
        }
    }
    CardTile(card, showPlay = track != null || playableCollection, onTrackActions = onTrackActions, onPlay = ::play, playLoading = loading, onClick = onOpen)
    if (error) AlertDialog(onDismissRequest = { error = false }, title = { Text(Lang.t(Lang.Key.PLAYER_COLLECTION_LOAD_FAILED)) },
        text = { Text(Lang.t(Lang.Key.PLAYER_QUEUE_SAVED_RETRY)) }, confirmButton = { TextButton(onClick = { error = false; play() }) { Text(Lang.t(Lang.Key.MAIN_RETRY)) } },
        dismissButton = { TextButton(onClick = { error = false }) { Text(Lang.t(Lang.Key.MAIN_CLOSE)) } })
}


/** Single-item recommendation sections use one compact row instead of a whole artwork shelf. */
@Composable
internal fun HomeRecommendationRow(card: Card, reason: String, onTrackActions: (() -> Unit)?, onOpen: () -> Unit) {
    val actions = cardActions(card, onOpen, onTrackActions)
    Row(
        Modifier.fillMaxWidth()
            .then(if (actions == null) Modifier.clickable(onClick = onOpen)
                else Modifier.contextGesture(card.displayTitle, onOpen, actions))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(card.imageUrl, Modifier.size(56.dp), circular = card.kind == "artist")
        Column(Modifier.weight(1f)) {
            if (reason.isNotBlank()) Text(reason, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(card.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (card.subtitle.isNotBlank()) Text(card.subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
