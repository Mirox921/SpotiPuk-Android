package com.project.puk.ui

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.MediaStore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.project.puk.core.Log

/**
 * Screenshot detection via a MediaStore ContentObserver instead of the
 * Android 14 Activity.ScreenCaptureCallback: the callback registration makes
 * the OS show a privacy dialog ("приложение обнаружило снимок экрана"), which
 * the user asked to remove. The observer only reacts while the app is open
 * and Now Playing is visible; it never scans the gallery history.
 */
@Composable
internal fun ScreenCaptureHint(enabled: Boolean, onCapture: () -> Unit) {
    if (!enabled) return
    val context = LocalContext.current
    val handler by rememberUpdatedState(onCapture)
    DisposableEffect(enabled) {
        val observer = object : ContentObserver(Handler(context.mainLooper)) {
            private var armedAt = System.currentTimeMillis() - 500L

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                val now = System.currentTimeMillis()
                if (now - armedAt < 800L) return
                val shot = runCatching { newestScreenshotPath(context, armedAt) }.getOrNull() ?: return
                if (shot) {
                    armedAt = now
                    Log.i("Capture", "screenshot noticed, suggesting a track card")
                    handler()
                }
            }
        }
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            observer,
        )
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
}

/** True when the newest media insert looks like a screenshot taken just now. */
private fun newestScreenshotPath(context: Context, sinceMs: Long): Boolean {
    val projection = arrayOf(
        MediaStore.Images.Media.RELATIVE_PATH,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_MODIFIED,
    )
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        null,
        null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC",
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return false
        val path = cursor.getString(0).orEmpty()
        val name = cursor.getString(1).orEmpty()
        val modifiedSeconds = cursor.getLong(2) * 1000L
        val looksLikeScreenshot = path.contains("Screenshot", ignoreCase = true) ||
            name.startsWith("Screenshot", ignoreCase = true) ||
            name.startsWith("screenshot", ignoreCase = true)
        return looksLikeScreenshot && modifiedSeconds >= sinceMs - 2000L
    }
    return false
}
