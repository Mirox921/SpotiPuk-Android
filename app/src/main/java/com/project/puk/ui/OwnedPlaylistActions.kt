package com.project.puk.ui

import com.project.puk.core.Lang

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.project.puk.R
import com.project.puk.api.Spotify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun OwnedPlaylistActions(uri: String, onUpdated: (String) -> Unit) {
    val context = LocalContext.current
    var details by remember(uri) { mutableStateOf<Spotify.OwnedPlaylistDetails?>(null) }
    var editing by remember(uri) { mutableStateOf(false) }
    var changeVisibility by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) { details = Spotify.ownedPlaylistDetails(context, uri) }
    val playlist = details ?: return
    SheetAction(R.drawable.ember_ctx_edit_playlist, Lang.t(Lang.Key.PL_EDIT)) { editing = true }
    if (playlist.isPublic != null && !playlist.collaborative) {
        SheetAction(if (playlist.isPublic) R.drawable.ember_ctx_make_private else R.drawable.ember_ctx_make_public,
            if (playlist.isPublic) Lang.t(Lang.Key.PL_MAKE_PRIVATE) else Lang.t(Lang.Key.PL_MAKE_PUBLIC)) { changeVisibility = true }
    }
    if (editing || changeVisibility) {
        EditOwnedPlaylistDialog(playlist, changeVisibility, onDismiss = { editing = false; changeVisibility = false }, onUpdated)
    }
}

@Composable
private fun EditOwnedPlaylistDialog(
    playlist: Spotify.OwnedPlaylistDetails, toggleVisibility: Boolean, onDismiss: () -> Unit, onUpdated: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(playlist.uri) { mutableStateOf(playlist.name) }
    var description by remember(playlist.uri) { mutableStateOf(playlist.description) }
    var isPublic by remember(playlist.uri, toggleVisibility) {
        mutableStateOf(playlist.isPublic?.let { if (toggleVisibility) !it else it })
    }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    EmberActionSheet(onDismissRequest = onDismiss, heading = Lang.t(Lang.Key.PL_EDIT), dismissEnabled = !busy,
        confirmButton = {
            Button(enabled = !busy && name.isNotBlank(), onClick = {
                busy = true
                failed = false
                scope.launch {
                    try {
                        val visibilityChange = isPublic.takeIf { it != playlist.isPublic }
                        if (Spotify.updateOwnedPlaylist(context, playlist.uri, name, description, visibilityChange)) {
                            onUpdated(name.trim())
                        }
                        else failed = true
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                    finally { busy = false }
                }
            }) { Text(if (busy) Lang.t(Lang.Key.PL_SAVING) else Lang.t(Lang.Key.LIB_SAVE)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(Lang.t(Lang.Key.JAM_CANCEL)) } },
    ) {
        OutlinedTextField(name, { name = it.take(100) }, label = { Text(Lang.t(Lang.Key.LIB_NAME_LABEL)) }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(description, { description = it.take(300) }, label = { Text(Lang.t(Lang.Key.PL_DESCRIPTION)) }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        if (isPublic != null && !playlist.collaborative) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(Lang.t(Lang.Key.PL_PUBLIC_TOGGLE), Modifier.weight(1f))
                Switch(isPublic == true, { isPublic = it }, enabled = !busy)
            }
            Text(Lang.t(Lang.Key.PL_PUBLIC_HINT), style = MaterialTheme.typography.bodySmall)
        }
        if (failed) Text(Lang.t(Lang.Key.PL_UPDATE_NOT_CONFIRMED), color = MaterialTheme.colorScheme.error)
    }
}
