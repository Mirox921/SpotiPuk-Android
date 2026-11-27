package com.project.puk.ui.theme

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.android.material.color.utilities.Hct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ArtworkAmbientColors(val primary: Color, val secondary: Color)

internal object ArtworkColorEngine {
    private val colors = LruCache<String, ArtworkAmbientColors>(64)

    fun cached(url: String): ArtworkAmbientColors? = colors.get(url)

    suspend fun resolve(context: Context, url: String): ArtworkAmbientColors? = withContext(Dispatchers.Default) {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(96)
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return@withContext null
        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@withContext null
        val palette = Palette.from(bitmap)
            .setRegion(0, 0, bitmap.width, (bitmap.height * 0.67f).toInt().coerceAtLeast(1))
            .clearFilters()
            .maximumColorCount(16)
            .generate()
        val swatches = palette.swatches.map { it.rgb to it.population }
        val primary = representativeArtworkArgb(swatches) ?: return@withContext null
        val secondary = secondaryArtworkArgb(primary, swatches) ?: primary
        ArtworkAmbientColors(Color(ambientArgb(primary)), Color(ambientArgb(secondary)))
            .also { colors.put(url, it) }
    }
}

@android.annotation.SuppressLint("RestrictedApi")
internal fun representativeArtworkArgb(swatches: List<Pair<Int, Int>>): Int? {
    val population = swatches.sumOf { it.second }.coerceAtLeast(1)
    val candidates = swatches.filter { it.second.toDouble() / population >= 0.035 }
    return candidates.maxByOrNull { (argb, count) ->
        val color = Hct.fromInt(argb)
        // Favour saturated swatches strongly so vivid album art stays vivid.
        val chromaWeight = 0.3 + color.chroma.coerceAtMost(80.0) / 95.0
        val toneWeight = 1.0 - (kotlin.math.abs(color.tone - 58.0) / 75.0).coerceAtMost(0.75)
        kotlin.math.sqrt(count.toDouble() / population) * chromaWeight * toneWeight
    }?.first ?: swatches.maxByOrNull { it.second }?.first
}

@android.annotation.SuppressLint("RestrictedApi")
internal fun secondaryArtworkArgb(primary: Int, swatches: List<Pair<Int, Int>>): Int? {
    val primaryColor = Hct.fromInt(primary)
    if (primaryColor.chroma < 12) return null
    val population = swatches.sumOf { it.second }.coerceAtLeast(1)
    return swatches.filter { (argb, count) ->
        val color = Hct.fromInt(argb)
        val distance = kotlin.math.abs(color.hue - primaryColor.hue)
        count.toDouble() / population >= 0.07 && color.chroma >= 22 &&
            color.tone in 25.0..85.0 && minOf(distance, 360.0 - distance) >= 42.0
    }.maxByOrNull { it.second }?.first
}

// Material 1.12 provides the HCT solver used by this adapter.
// Tone 42 keeps white text readable while letting the hue show vividly;
// wide chroma range prevents the muddy, desaturated washes tone 24 produced.
@android.annotation.SuppressLint("RestrictedApi")
internal fun ambientArgb(argb: Int): Int {
    val source = Hct.fromInt(argb)
    if (source.chroma < 8) return EmberColors.SurfaceContainerHigh.toArgb()
    return Hct.from(source.hue, source.chroma.coerceIn(28.0, 72.0), 42.0).toInt()
}

@Composable
internal fun artworkAmbient(url: String): Color = artworkAmbientColors(url).primary

@Composable
internal fun artworkAmbientColors(url: String): ArtworkAmbientColors {
    val context = LocalContext.current.applicationContext
    var color by remember(url) { mutableStateOf(ArtworkColorEngine.cached(url)) }
    LaunchedEffect(url) {
        if (url.isNotBlank() && color == null) color = ArtworkColorEngine.resolve(context, url)
    }
    return color ?: ArtworkAmbientColors(EmberColors.SurfaceContainerHigh, EmberColors.SurfaceContainerHigh)
}

@android.annotation.SuppressLint("RestrictedApi")
internal fun shareArtworkArgb(argb: Int, vibrant: Boolean): Int {
    val source = Hct.fromInt(argb)
    return Hct.from(
        source.hue,
        if (vibrant) source.chroma.coerceAtMost(62.0) else source.chroma.coerceAtMost(32.0),
        if (vibrant) 34.0 else 19.0,
    ).toInt()
}
