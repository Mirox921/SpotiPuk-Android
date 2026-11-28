package com.project.puk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.project.puk.api.Lyrics
import com.project.puk.api.Spotify
import com.project.puk.core.Lang
import com.project.puk.player.PukPlayer
import com.project.puk.ui.theme.EmberIconButton
import com.project.puk.ui.theme.EmberIcons

@Composable
internal fun EpisodeTranscriptSheet(
    uri: String,
    episodeTitle: String,
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
    EpisodeTranscriptPanel(episodeTitle, transcript, loading, playback, seek, onDismiss)
}

@Composable
internal fun EpisodeTranscriptPanel(
    episodeTitle: String,
    transcript: Lyrics?,
    loading: Boolean,
    playback: State<PukPlayer.State>,
    seek: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    EmberSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * .76f)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    EmberIcons.Transcript,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = colors.primary,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(Lang.t(Lang.Key.PLAYER_TRANSCRIPT), style = MaterialTheme.typography.titleLarge)
                    if (episodeTitle.isNotBlank()) {
                        Text(
                            episodeTitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                EmberIconButton(EmberIcons.Close, Lang.t(Lang.Key.SHEET_CLOSE_PANEL), onDismiss)
            }
            HorizontalDivider(color = colors.outlineVariant)
            val result = transcript
            when {
                loading -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                }
                result == null || result.lines.isEmpty() -> Text(
                    Lang.t(Lang.Key.POD_TRANSCRIPT_UNAVAILABLE),
                    Modifier.padding(20.dp),
                    color = colors.onSurfaceVariant,
                )
                else -> {
                    val scroll = rememberLazyListState()
                    val active by remember(result, playback) {
                        derivedStateOf {
                            if (result.synced) {
                                val position = playback.value.positionMs
                                result.lines.indexOfLast { it.startMs <= position }
                            } else -1
                        }
                    }
                    LaunchedEffect(result, active) {
                        if (active >= 0 && !scroll.isScrollInProgress && scroll.layoutInfo.visibleItemsInfo.none { it.index == active }) {
                            scroll.scrollToItem(active)
                        }
                    }
                    val timeStyle = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace)
                    val measurer = rememberTextMeasurer()
                    val lastTimestamp = remember(result) { podcastDuration(result.lines.last().startMs) }
                    val timeWidth = with(LocalDensity.current) {
                        measurer.measure(lastTimestamp, style = timeStyle, maxLines = 1).size.width.toDp() + 16.dp
                    }.coerceAtLeast(50.dp)
                    val shape = RoundedCornerShape(14.dp)
                    LazyColumn(
                        state = scroll,
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(result.lines) { index, line ->
                            val current = active == index
                            val highlight = if (current) Modifier
                                .background(colors.primary.copy(alpha = .10f))
                                .border(1.dp, colors.primary.copy(alpha = .35f), shape)
                            else Modifier
                            val seekAction = if (result.synced) Modifier
                                .semantics { selected = current }
                                .clickable(role = Role.Button) { seek(line.startMs) }
                            else Modifier
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape)
                                    .then(highlight).then(seekAction)
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                if (result.synced) {
                                    Text(
                                        podcastDuration(line.startMs),
                                        modifier = Modifier.width(timeWidth).padding(top = 2.dp),
                                        style = timeStyle,
                                        textAlign = TextAlign.End,
                                        color = if (current) colors.primary else colors.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                                Text(
                                    line.text,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
