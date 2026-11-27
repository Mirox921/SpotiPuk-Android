package com.project.puk.ui

import com.project.puk.core.Lang

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.project.puk.player.PukPlayer
import com.project.puk.ui.theme.EmberIcons
import androidx.compose.ui.platform.LocalContext
import com.project.puk.api.Spotify
import kotlinx.coroutines.launch

@Composable
internal fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreated: (Spotify.CreatedPlaylist, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    EmberActionSheet(
        onDismissRequest = onDismiss,
        dismissEnabled = !busy,
        heading = Lang.t(Lang.Key.PL_NEW_PLAYLIST),
        text = {
            Column {
                OutlinedTextField(
                    name, { name = it.take(100); failed = false }, label = { Text(Lang.t(Lang.Key.LIB_NAME_LABEL)) },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(), isError = failed,
                )
                if (failed) Text(Lang.t(Lang.Key.PL_CREATE_NOT_CONFIRMED), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank() && !busy, onClick = {
                busy = true
                val title = name.trim()
                scope.launch {
                    val created = Spotify.createPlaylist(context, title)
                    busy = false
                    if (created == null) failed = true else onCreated(created, title)
                }
            }) { Text(if (busy) Lang.t(Lang.Key.PL_CREATING) else Lang.t(Lang.Key.PLAYER_CREATE)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(Lang.t(Lang.Key.JAM_CANCEL)) } },
    )
}

@Composable
internal fun AddToPlaylistDialog(track: PukPlayer.QueueItem, onDismiss: () -> Unit, onCreated: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val trackUri = track.uri
    var query by remember(trackUri) { mutableStateOf("") }
    var playlists by remember { mutableStateOf<List<Spotify.PlaylistChoice>?>(null) }
    var next by remember { mutableStateOf<Int?>(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    fun load(offset: Int) {
        busy = true
        message = ""
        scope.launch {
            val page = Spotify.editablePlaylists(context, trackUri, offset)
            if (page == null) message = Lang.t(Lang.Key.PL_LOAD_FAILED)
            else {
                playlists = (playlists.orEmpty() + page.items).distinctBy { it.uri }
                next = page.next
            }
            busy = false
        }
    }
    LaunchedEffect(trackUri) { load(0) }
    if (creating) {
        CreatePlaylistDialog(onDismiss = { creating = false }) { created, name ->
            creating = false
            busy = true
            scope.launch {
                val added = Spotify.addToPlaylist(context, created.uri, trackUri)
                onCreated()
                playlists = listOf(Spotify.PlaylistChoice(created.uri, name, added)) + playlists.orEmpty()
                message = when {
                    !created.registered && !added -> Lang.t(Lang.Key.PL_CREATED_SAVE_ADD_FAILED)
                    !created.registered -> Lang.t(Lang.Key.PL_ADDED_SAVE_FAILED)
                    added -> Lang.t(Lang.Key.PL_TRACK_ADDED)
                    else -> Lang.t(Lang.Key.PL_CREATED_TAP_TO_ADD)
                }
                busy = false
            }
        }
    } else EmberActionSheet(
        onDismissRequest = onDismiss,
        dismissEnabled = !busy,
        heading = Lang.t(Lang.Key.PL_ADD_TO),
        scrollBody = false,
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track.imageUrl, Modifier.size(56.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(
                query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(Lang.t(Lang.Key.PL_SEARCH_LOADED)) },
                leadingIcon = { Icon(EmberIcons.Search, null) },
            )
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotEmpty()) Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            val visible = playlists.orEmpty().filter { it.name.contains(query.trim(), ignoreCase = true) }
            if (!busy && playlists != null && visible.isEmpty()) {
                Text(if (query.isBlank()) Lang.t(Lang.Key.PL_NONE_AVAILABLE) else Lang.t(Lang.Key.PL_NO_MATCHES))
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(visible, key = { it.uri }) { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(EmberIcons.MusicNote, null) },
                        trailingContent = if (playlist.containsTrack) {
                            { Icon(EmberIcons.Check, Lang.t(Lang.Key.PL_ALREADY_ADDED), tint = MaterialTheme.colorScheme.primary) }
                        } else null,
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !busy && !playlist.containsTrack) {
                            busy = true
                            scope.launch {
                                val added = Spotify.addToPlaylist(context, playlist.uri, trackUri)
                                if (added) {
                                    playlists = playlists.orEmpty().map { if (it.uri == playlist.uri) it.copy(containsTrack = true) else it }
                                    message = Lang.t(Lang.Key.PL_TRACK_ADDED)
                                    onCreated()
                                } else message = Lang.t(Lang.Key.PL_ADD_NOT_CONFIRMED)
                                busy = false
                            }
                        },
                    )
                }
                if (next != null && !busy) item {
                    TextButton(onClick = { next?.let(::load) }) { Text(if (playlists == null) Lang.t(Lang.Key.LIB_RETRY) else Lang.t(Lang.Key.PL_LOAD_MORE)) }
                }
            }
        },
        confirmButton = { Button(enabled = !busy, onClick = { creating = true }) { Text(Lang.t(Lang.Key.PL_NEW_PLAYLIST)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(Lang.t(Lang.Key.PLAYER_CLOSE)) } },
    )
}
