package com.project.puk.ui

import com.project.puk.core.Lang

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.project.puk.R
import com.project.puk.api.Card
import com.project.puk.api.Spotify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ArtistRadioAction(card: Card, onDismiss: () -> Unit) {
    val open = LocalContextOpenTarget.current ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(card.uri) { mutableStateOf(false) }
    var failed by remember(card.uri) { mutableStateOf(false) }
    SheetAction(R.drawable.ember_ctx_artist_radio, if (busy) Lang.t(Lang.Key.PLAYER_RADIO_LOADING) else Lang.t(Lang.Key.PLAYER_ARTIST_RADIO), enabled = !busy) {
        busy = true
        failed = false
        scope.launch {
            try {
                val uri = Spotify.artistRadio(context, card.uri)
                if (uri == null) failed = true
                else { onDismiss(); open(Target.Playlist(uri)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
    if (failed) Text(Lang.t(Lang.Key.PLAYER_RADIO_LOAD_FAILED), color = MaterialTheme.colorScheme.error)
}
