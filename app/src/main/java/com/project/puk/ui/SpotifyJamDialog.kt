package com.project.puk.ui

import com.project.puk.core.Lang

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private const val SPOTIFY_PACKAGE = "com.spotify.music"

@Composable
internal fun SpotifyJamDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var invitation by rememberSaveable { mutableStateOf("") }
    var invalidInvitation by rememberSaveable { mutableStateOf(false) }
    var spotifyUnavailable by rememberSaveable { mutableStateOf(false) }

    fun openSpotify(intent: Intent) {
        try {
            context.startActivity(intent)
            onDismiss()
        } catch (_: ActivityNotFoundException) {
            spotifyUnavailable = true
        }
    }

    EmberActionSheet(
        onDismissRequest = onDismiss,
        heading = "Spotify Jam",
        text = {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(Lang.t(Lang.Key.SJAM_PASTE_INVITE))
                OutlinedTextField(
                    value = invitation,
                    onValueChange = { invitation = it; invalidInvitation = false },
                    label = { Text(Lang.t(Lang.Key.SJAM_INVITE_LINK)) },
                    singleLine = true,
                    isError = invalidInvitation,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (invalidInvitation) {
                    Text(Lang.t(Lang.Key.SJAM_PASTE_LINK), color = MaterialTheme.colorScheme.error)
                }
                Text(
                    Lang.t(Lang.Key.SJAM_JOIN_HINT),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    openSpotify(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(SPOTIFY_PACKAGE))
                }) { Text(Lang.t(Lang.Key.SJAM_OPEN_SPOTIFY)) }
                if (spotifyUnavailable) {
                    Text(Lang.t(Lang.Key.SJAM_INSTALL_HINT), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { openSpotifyStore(context) }) { Text(Lang.t(Lang.Key.SJAM_INSTALL_SPOTIFY)) }
                }
            }
        },
        confirmButton = {
            Button(enabled = invitation.isNotBlank(), onClick = {
                val uri = spotifyInvitation(invitation)
                if (uri == null) invalidInvitation = true
                else openSpotify(Intent(Intent.ACTION_VIEW, uri).setPackage(SPOTIFY_PACKAGE))
            }) { Text(Lang.t(Lang.Key.SJAM_OPEN_INVITE)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Lang.t(Lang.Key.SHEET_CLOSE)) } },
    )
}

private fun spotifyInvitation(input: String): Uri? {
    val uri = Uri.parse(input.trim())
    if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1) return null
    val segments = uri.pathSegments
    val valid = when (uri.host) {
        "spotify.link" -> segments.size == 1
        "open.spotify.com" -> segments.size == 2 && segments.first() == "socialsession"
        else -> false
    }
    return uri.takeIf { valid && segments.last().matches(Regex("[A-Za-z0-9]+")) }
}

private fun openSpotifyStore(context: Context) {
    val listing = Uri.parse("https://play.google.com/store/apps/details?id=$SPOTIFY_PACKAGE")
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, listing))
    } catch (_: ActivityNotFoundException) {
        android.widget.Toast.makeText(context, Lang.t(Lang.Key.SJAM_PLAY_OPEN_FAILED), android.widget.Toast.LENGTH_SHORT).show()
    }
}
