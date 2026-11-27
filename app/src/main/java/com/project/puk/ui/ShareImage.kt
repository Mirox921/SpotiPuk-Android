package com.project.puk.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal suspend fun shareEmberImage(context: Context, linkText: String? = null, render: suspend () -> Bitmap) {
    val uri = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "ember_cards").apply { mkdirs() }
        val expiry = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory.listFiles()?.filter { it.lastModified() < expiry }?.forEach { it.delete() }
        val file = File(directory, "${UUID.randomUUID()}.png")
        val image = render()
        try {
            file.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            image.recycle()
        }
        FileProvider.getUriForFile(context, "${context.packageName}.ember.cards", file)
    }
    val clip = ClipData.newUri(context.contentResolver, "SpotiPuk", uri)
    if (!linkText.isNullOrBlank()) clip.addItem(ClipData.Item(linkText))
    val share = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .apply {
            // The caption travels with the picture: apps like Telegram and
            // WhatsApp show the card and the track link together, and the
            // link is a plain caption, not Spotify's own link preview.
            if (!linkText.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, linkText)
        }
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = clip }
    context.startActivity(Intent.createChooser(share, null))
}
