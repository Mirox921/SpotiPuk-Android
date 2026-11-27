package com.project.puk.ui

import com.project.puk.core.Lang

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.project.puk.api.Lyrics
import com.project.puk.api.Spotify
import com.project.puk.player.PukPlayer

@Composable
internal fun EpisodeTranscriptSheet(
    uri: String,
    playback: State<PukPlayer.State>,
    seek: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var loading by remember(uri) { mutableStateOf(true) }
    var transcript by remember(uri) { mutableStateOf<Lyrics?>(null) }
    LaunchedEffect(uri) {
        transcript = Spotify.transcript(context, uri)
        loading = false
    }
    EmberSheet(onDismissRequest = onDismiss) {
        Text(Lang.t(Lang.Key.PLAYER_TRANSCRIPT), Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
        val result = transcript
        when {
            loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            result == null -> Text(Lang.t(Lang.Key.POD_TRANSCRIPT_UNAVAILABLE), Modifier.padding(16.dp))
            else -> {
                val scroll = rememberLazyListState()
                val active by remember(result, playback) {
                    derivedStateOf {
                        if (result.synced) result.lines.indexOfLast { it.startMs <= playback.value.positionMs } else -1
                    }
                }
                LaunchedEffect(result, active) {
                    if (active >= 0 && !scroll.isScrollInProgress && scroll.layoutInfo.visibleItemsInfo.none { it.index == active }) {
                        scroll.scrollToItem(active)
                    }
                }
                LazyColumn(
                    state = scroll,
                    modifier = Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .65f),
                ) {
                    itemsIndexed(result.lines) { index, line ->
                        val color = if (active == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        if (result.synced) TextButton(onClick = { seek(line.startMs) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${podcastDuration(line.startMs)}  ${line.text}", color = color)
                        } else Text(line.text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = color)
                    }
                }
            }
        }
    }
}
