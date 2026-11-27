package com.project.puk.ui

import com.project.puk.core.Lang

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.project.puk.player.PukPlayer
import com.project.puk.ui.theme.shareArtworkArgb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SharePalette(val label: String) {
    VIBRANT(Lang.t(Lang.Key.SHEET_PALETTE_VIBRANT)), DEEP(Lang.t(Lang.Key.SHEET_PALETTE_DEEP)), NEUTRAL("Ember"), CUSTOM(Lang.t(Lang.Key.SHEET_PALETTE_CUSTOM)),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TrackShareSheet(track: PukPlayer.QueueItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var mode by remember { mutableStateOf(SharePalette.VIBRANT) }
    var hex by remember { mutableStateOf("683B78") }
    var artworkTone by remember(track.imageUrl) { mutableStateOf<Int?>(null) }
    var artwork by remember(track.imageUrl) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(track.imageUrl) { mutableStateOf(track.imageUrl.isNotBlank()) }
    var sharing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val customArgb = hex.takeIf { it.length == 6 }?.toIntOrNull(16)?.or(0xff000000.toInt())
    val trackId = track.uri.removePrefix("spotify:track:").takeIf {
        track.uri.startsWith("spotify:track:") && it.matches(Regex("[A-Za-z0-9]{22}"))
    }
    LaunchedEffect(track.imageUrl) {
        if (loading) {
            try {
                val image = context.imageLoader.execute(
                    ImageRequest.Builder(context).data(track.imageUrl).size(720).allowHardware(false).build(),
                ) as? SuccessResult
                val bitmap = (image?.drawable as? BitmapDrawable)?.bitmap
                artwork = bitmap
                artworkTone = bitmap?.let {
                    withContext(Dispatchers.Default) {
                        androidx.palette.graphics.Palette.from(it).resizeBitmapArea(4096).generate().let { palette ->
                            palette.vibrantSwatch?.rgb ?: palette.dominantSwatch?.rgb
                        }
                    }
                }
                if (artwork == null) message = Lang.t(Lang.Key.SHEET_ARTWORK_UNAVAILABLE)
            } finally {
                loading = false
            }
        }
    }
    val base = when (mode) {
        SharePalette.VIBRANT -> artworkTone?.let { shareArtworkArgb(it, vibrant = true) }
        SharePalette.DEEP -> artworkTone?.let { shareArtworkArgb(it, vibrant = false) }
        SharePalette.NEUTRAL -> null
        SharePalette.CUSTOM -> customArgb?.let { shareArtworkArgb(it, vibrant = true) }
    } ?: colors.surfaceContainerHigh.toArgb()
    val foreground = colors.onSurface.toArgb()
    val accent = colors.primary.toArgb()
    val picture = artwork
    val preview = remember(track.title, track.artist, picture, base, foreground, accent) {
        renderTrackCard(track, picture, base, foreground, accent, 360)
    }
    EmberActionSheet(
        onDismissRequest = onDismiss, heading = Lang.t(Lang.Key.SHEET_TRACK_CARD_TITLE), dismissEnabled = !sharing,
        confirmButton = {
            Button(enabled = !sharing && !loading && (mode != SharePalette.CUSTOM || customArgb != null), onClick = {
                sharing = true
                scope.launch {
                    try {
                        shareEmberImage(
                            context,
                            linkText = "https://open.spotify.com/" +
                                if (track.uri.startsWith("spotify:episode:")) "episode/${track.uri.substringAfterLast(':')}"
                                else "track/${track.uri.substringAfterLast(':')}",
                        ) { renderTrackCard(track, picture, base, foreground, accent, 1080) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        message = Lang.t(Lang.Key.SHEET_SHARE_CARD_FAILED)
                    } finally {
                        sharing = false
                    }
                }
            }) { Text(Lang.t(Lang.Key.JAM_SHARE)) }
        },
    ) {
        Image(preview.asImageBitmap(), Lang.t(Lang.Key.SHARE_PREVIEW, track.title, track.artist),
            Modifier.fillMaxWidth().aspectRatio(0.9f).clip(RoundedCornerShape(16.dp)))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SharePalette.entries.forEach { option ->
                FilterChip(selected = mode == option, onClick = { mode = option }, label = { Text(option.label) })
            }
        }
        if (mode == SharePalette.CUSTOM) {
            OutlinedTextField(
                value = hex, onValueChange = { hex = it.trimStart('#').take(6) },
                label = { Text(Lang.t(Lang.Key.SHEET_HEX_COLOR_LABEL)) }, prefix = { Text("#") },
                singleLine = true, isError = customArgb == null,
            )
        }
        if (trackId != null) {
            TextButton(onClick = {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(track.title, "https://open.spotify.com/track/$trackId"))
                message = Lang.t(Lang.Key.JAM_LINK_COPIED)
            }) { Text(Lang.t(Lang.Key.SHEET_COPY_LINK)) }
        }
        if (loading) Text(Lang.t(Lang.Key.SHEET_LOADING_ARTWORK))
        if (message.isNotBlank()) Text(message, color = colors.onSurfaceVariant)
    }
}

private fun renderTrackCard(
    track: PukPlayer.QueueItem, artwork: Bitmap?, base: Int, foreground: Int, accent: Int, width: Int,
): Bitmap {
    val bitmap = Bitmap.createBitmap(width, width * 10 / 9, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.scale(width / 900f, width / 900f)
    canvas.drawColor(base)
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    fun text(value: String, top: Float, size: Float, color: Int, bold: Boolean = false) {
        paint.textSize = size
        paint.color = color
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        canvas.drawText(TextUtils.ellipsize(value, paint, 756f, TextUtils.TruncateAt.END).toString(), 72f, top, paint)
    }
    text("SpotiPuk", 100f, 38f, accent, true)
    if (artwork != null) {
        val ratio = minOf(600f / artwork.width, 600f / artwork.height)
        val w = artwork.width * ratio
        val h = artwork.height * ratio
        canvas.drawBitmap(artwork, null, RectF(450f - w / 2, 455f - h / 2, 450f + w / 2, 455f + h / 2), paint)
    }
    text(track.title, 835f, 62f, foreground, true)
    text(track.artist, 900f, 42f, foreground)
    return bitmap
}
