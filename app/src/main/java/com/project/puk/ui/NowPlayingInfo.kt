package com.project.puk.ui

import com.project.puk.core.Lang

import android.text.Html
import android.util.LruCache
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.project.puk.api.AlbumPage
import com.project.puk.api.ArtistPage
import com.project.puk.api.Spotify
import com.project.puk.api.TrackCredits
import com.project.puk.player.PukPlayer
import kotlinx.coroutines.CancellationException

private val artistCache = LruCache<String, ArtistPage>(24)

@Composable
internal fun nowPlayingArtist(uri: String): ArtistPage? {
    val context = LocalContext.current
    var artist by remember(uri) { mutableStateOf(artistCache.get(uri)) }
    LaunchedEffect(uri) {
        if (uri.startsWith("spotify:artist:") && artist == null) {
            Spotify.artist(context, uri)?.let {
                artistCache.put(uri, it)
                artist = it
            }
        }
    }
    return artist
}

@Composable
internal fun NowPlayingArtistCard(item: PukPlayer.QueueItem, artist: ArtistPage?, onOpen: (String) -> Unit) {
    if (item.artist.isBlank() && artist?.name.isNullOrBlank()) return
    val biography = remember(artist?.biography) {
        artist?.biography?.takeIf { it.isNotBlank() }?.let {
            Html.fromHtml(it, Html.FROM_HTML_MODE_COMPACT).toString().trim()
        }.orEmpty()
    }
    var expanded by remember(item.artistUri) { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column {
            val photo = artist?.imageUrl.orEmpty()
            if (photo.isNotBlank()) Artwork(photo, Modifier.fillMaxWidth().aspectRatio(1.8f))
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Lang.t(Lang.Key.ART_ABOUT), style = MaterialTheme.typography.titleLarge)
                Text(artist?.name?.ifBlank { item.artist } ?: item.artist, style = MaterialTheme.typography.titleMedium)
                if (biography.isNotBlank()) {
                    Text(
                        biography, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (biography.length > 260) {
                        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) Lang.t(Lang.Key.PLAYER_COLLAPSE) else Lang.t(Lang.Key.NP_READ_MORE)) }
                    }
                }
                if (item.artistUri.startsWith("spotify:artist:")) {
                    TextButton(onClick = { onOpen(item.artistUri) }) { Text(Lang.t(Lang.Key.NP_OPEN_ARTIST)) }
                }
            }
        }
    }
}

@Composable
internal fun NowPlayingReleases(artist: ArtistPage?, albumUri: String, onOpenAlbum: (String) -> Unit) {
    val releases = artist?.albums.orEmpty().filter { it.kind == "album" && it.uri != albumUri }
        .distinctBy { it.uri }.take(10)
    if (releases.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(Lang.t(Lang.Key.NP_MORE_MUSIC), style = MaterialTheme.typography.titleLarge)
        Text(Lang.t(Lang.Key.NP_ARTIST_RELEASES), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(releases, key = { it.uri }) { release ->
                CardTile(release, onClick = { onOpenAlbum(release.uri) })
            }
        }
    }
}

@Composable
internal fun TrackDetailsCard(item: PukPlayer.QueueItem) {
    var open by remember(item.uri) { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().padding(top = 18.dp),
        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Lang.t(Lang.Key.NP_DETAILS), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { open = true }) { Text(Lang.t(Lang.Key.NP_SHOW_ALL)) }
        }
    }
    if (open) TrackDetailsSheet(item) { open = false }
}

@Composable
internal fun TrackDetailsSheet(item: PukPlayer.QueueItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var album by remember(item.albumUri) { mutableStateOf<AlbumPage?>(null) }
    var credits by remember(item.uri) { mutableStateOf<TrackCredits?>(null) }
    var loadingCredits by remember(item.uri) { mutableStateOf(true) }
    LaunchedEffect(item.albumUri) {
        if (item.albumUri.startsWith("spotify:album:")) album = Spotify.album(context, item.albumUri, limit = 1)
    }
    LaunchedEffect(item.uri) {
        try {
            credits = Spotify.credits(context, item.uri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            credits = null
        } finally {
            loadingCredits = false
        }
    }
    EmberActionSheet(onDismissRequest = onDismiss, heading = item.title) {
        val metadata = album
        if (item.artist.isNotBlank()) DetailField(Lang.t(Lang.Key.LIB_ARTISTS), item.artist)
        if (metadata != null) {
            DetailField(Lang.t(Lang.Key.NP_ALBUM), metadata.name)
            DetailField(Lang.t(Lang.Key.NP_ALBUM_RELEASE_DATE), metadata.releaseDate)
            DetailField(Lang.t(Lang.Key.NP_ALBUM_LABEL), metadata.label)
        }
        if (loadingCredits) {
            Text(Lang.t(Lang.Key.NP_LOADING_CREDITS), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (credits == null) {
            Text(Lang.t(Lang.Key.NP_CREDITS_UNAVAILABLE), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        credits?.let { loaded ->
            loaded.groups.forEach { group ->
                DetailField(group.displayRole, group.names.joinToString(", "))
            }
            if (loaded.sourceNames.isNotEmpty()) {
                DetailField(Lang.t(Lang.Key.NP_DATA_SOURCE), loaded.sourceNames.joinToString(", "))
            }
        }
        if (item.durationMs > 0) {
            val seconds = item.durationMs / 1_000
            DetailField(Lang.t(Lang.Key.NP_DURATION), "%d:%02d".format(seconds / 60, seconds % 60))
        }
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    if (value.isBlank()) return
    Column {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
