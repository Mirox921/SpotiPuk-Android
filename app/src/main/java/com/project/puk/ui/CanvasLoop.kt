package com.project.puk.ui

import android.net.Uri
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.project.puk.api.Spotify
import com.project.puk.player.VideoSource

internal data class CanvasLoad(val source: CanvasLoopSource? = null, val loading: Boolean = false)

internal data class CanvasLoopSource(val uri: Uri, val dash: Boolean, val fallback: Uri? = null)

@Composable
internal fun rememberAvailableCanvas(trackUri: String): Spotify.CanvasMedia? {
    val context = LocalContext.current
    val accountGeneration = com.project.puk.core.Auth.generation
    var canvas by remember(trackUri, accountGeneration) { mutableStateOf<Spotify.CanvasMedia?>(null) }
    LaunchedEffect(trackUri, accountGeneration) {
        if (trackUri.startsWith("spotify:track:")) canvas = Spotify.canvas(context, trackUri)
    }
    return canvas
}

@Composable
internal fun rememberCanvasMedia(canvas: Spotify.CanvasMedia?, enabled: Boolean): CanvasLoad {
    val context = LocalContext.current
    var result by remember(canvas, enabled) { mutableStateOf(CanvasLoad(loading = enabled && canvas != null)) }
    LaunchedEffect(canvas, enabled) {
        if (!enabled || canvas == null) return@LaunchedEffect
        com.project.puk.core.Log.i("Canvas", "resolved file=${canvas.fileId != null} path=${canvas.url?.path}")
        val direct = canvas.url?.takeUnless { it.path.orEmpty().contains("/inits/") }
        val manifest = canvas.fileId?.let { VideoSource.resolveManifest(context, it) }
        val profile = manifest?.videoProfiles?.filter { it.height in 1..720 }?.maxByOrNull { it.height }
        val file = if (manifest != null && !manifest.isEncrypted && profile != null && manifest.durationMs > 0) {
            VideoSource.buildDashFile(context, manifest.copy(videoProfiles = listOf(profile), audioProfiles = emptyList()))
        } else null
        val source = if (file != null) CanvasLoopSource(file.toUri(), dash = true, fallback = direct)
        else direct?.let { CanvasLoopSource(it, dash = false) }
        result = CanvasLoad(source)
    }
    return result
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun CanvasLoop(source: CanvasLoopSource, trackPlaying: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { owner, _ ->
            foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!foreground) return
    var visible by remember(source) { mutableStateOf(false) }
    val preview = remember(context, source) {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setAudioAttributes(AudioAttributes.DEFAULT, false)
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        }
    }
    DisposableEffect(preview) {
        var attemptedFallback = false
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                com.project.puk.core.Log.i("Canvas", "first video frame")
                visible = true
            }
            override fun onPlayerError(error: PlaybackException) {
                com.project.puk.core.Log.i("Canvas", "playback error=${error.errorCodeName}")
                visible = false
                val fallback = source.fallback
                if (!attemptedFallback && fallback != null) {
                    attemptedFallback = true
                    preview.setMediaItem(MediaItem.fromUri(fallback))
                    preview.prepare()
                }
            }
        }
        preview.addListener(listener)
        val item = MediaItem.Builder().setUri(source.uri)
        if (source.dash) item.setMimeType(MimeTypes.APPLICATION_MPD)
        preview.setMediaItem(item.build())
        preview.prepare()
        onDispose {
            preview.removeListener(listener)
            preview.release()
        }
    }
    LaunchedEffect(preview, trackPlaying) { preview.playWhenReady = trackPlaying }
    AndroidView(
        modifier = modifier,
        factory = { viewContext -> TextureView(viewContext).also(preview::setVideoTextureView) },
        update = { it.alpha = if (visible) 1f else 0f },
    )
}
