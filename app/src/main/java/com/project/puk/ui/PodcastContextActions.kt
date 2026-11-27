package com.project.puk.ui

import com.project.puk.core.Lang

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.project.puk.R
import com.project.puk.api.Card
import com.project.puk.api.ChapterSpan
import com.project.puk.api.EpisodeRow
import com.project.puk.api.Spotify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal val LocalContextPlayEpisode = staticCompositionLocalOf<((EpisodeRow, Card) -> Unit)?> { null }

internal fun episodeMomentUrl(uri: String, positionMs: Long): String? {
    val id = uri.removePrefix("spotify:episode:")
    if (!uri.startsWith("spotify:episode:") || !id.matches(Regex("[A-Za-z0-9]{22}"))) return null
    return "https://open.spotify.com/episode/$id?t=${positionMs.coerceAtLeast(0) / 1000}"
}

internal fun copyContextText(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, Lang.t(Lang.Key.POD_COPIED), Toast.LENGTH_SHORT).show()
}

internal fun shareContextText(context: Context, text: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text), null))
}

internal fun openExternalSpotify(context: Context, url: String) {
    val view = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
    // Unsupported native types must not loop back through our own deep-link activity.
    val chooser = Intent.createChooser(view, Lang.t(Lang.Key.POD_OPEN_IN_SPOTIFY_BROWSER)).putExtra(
        Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(android.content.ComponentName(context, MainActivity::class.java)))
    try { context.startActivity(chooser) }
    catch (_: android.content.ActivityNotFoundException) {
        Toast.makeText(context, Lang.t(Lang.Key.POD_OPEN_LINK_FAILED), Toast.LENGTH_SHORT).show()
    }
}

internal fun runLocalQueueAction(context: Context, action: () -> Unit): Boolean {
    val device = com.project.puk.connect.Connect.clusterActiveDevice.value
    if (device.isNotBlank() && !com.project.puk.connect.Connect.isLocalDevice(device)) {
        Toast.makeText(context, Lang.t(Lang.Key.POD_REMOTE_QUEUE_LOCKED), Toast.LENGTH_SHORT).show()
        return false
    }
    action()
    return true
}

/** Scan real pages, without assuming a server sort or treating a partial response as complete. */
internal suspend fun loadShowPlaybackEpisode(
    resume: Boolean,
    fetch: suspend (Int) -> com.project.puk.api.EpisodePage?,
): EpisodeRow? {
    var offset = 0
    var selected: EpisodeRow? = null
    while (true) {
        val page = fetch(offset) ?: return null
        val candidate = page.rows.filter { it.playable && (!resume || (!it.played && it.positionMs > 0)) }
            .maxByOrNull { it.releaseDate }
        if (candidate != null && (selected == null || candidate.releaseDate > selected.releaseDate)) selected = candidate
        val next = page.nextOffset ?: return selected
        if (next <= offset) return null
        offset = next
    }
}

@Composable
internal fun ShowPlaybackActions(card: Card, onStarted: () -> Unit) {
    val play = LocalContextPlayEpisode.current ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember(card.uri) { mutableStateOf<Boolean?>(null) }
    var error by remember(card.uri) { mutableStateOf<String?>(null) }
    fun start(resume: Boolean) {
        if (loading != null) return
        loading = resume
        error = null
        scope.launch {
            try {
                val row = loadShowPlaybackEpisode(resume) { Spotify.episodesPage(context, card.uri, it) }
                if (row == null) error = if (resume) Lang.t(Lang.Key.POD_RESUME_NOT_FOUND) else Lang.t(Lang.Key.POD_LOAD_EPISODE_FAILED)
                else {
                    play(if (resume) row else row.copy(positionMs = 0), card)
                    onStarted()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = Lang.t(Lang.Key.POD_LOAD_EPISODES_RETRY)
            } finally {
                loading = null
            }
        }
    }
    SheetAction(R.drawable.ember_ctx_podcast_latest_episode,
        if (loading == false) Lang.t(Lang.Key.POD_SEARCHING_LATEST) else Lang.t(Lang.Key.POD_LATEST_EPISODE), enabled = loading == null) { start(false) }
    SheetAction(R.drawable.ember_ctx_podcast_resume_show,
        if (loading == true) Lang.t(Lang.Key.POD_SEARCHING_RESUME) else Lang.t(Lang.Key.POD_RESUME_PODCAST), enabled = loading == null) { start(true) }
    error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
}

@Composable
internal fun ChapterActionSheet(uri: String, chapter: ChapterSpan, onSeek: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    EmberActionSheet(onDismissRequest = onDismiss, heading = chapter.title.ifBlank { Lang.t(Lang.Key.POD_CHAPTER) }) {
        Text(podcastDuration(chapter.startMs))
        SheetAction(R.drawable.ember_ctx_chapter_seek, Lang.t(Lang.Key.POD_SEEK_CHAPTER)) { onDismiss(); onSeek() }
        SheetAction(R.drawable.ember_ctx_chapter_copy_timestamp, Lang.t(Lang.Key.POD_COPY_CHAPTER_TIME)) {
            copyContextText(context, chapter.title, podcastDuration(chapter.startMs))
        }
        episodeMomentUrl(uri, chapter.startMs)?.let { url ->
            SheetAction(R.drawable.ember_ctx_chapter_share_moment, Lang.t(Lang.Key.POD_SHARE_CHAPTER)) { shareContextText(context, url) }
        }
    }
}
