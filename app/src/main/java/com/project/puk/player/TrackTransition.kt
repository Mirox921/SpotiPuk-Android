package com.project.puk.player

internal const val TRACK_FADE_MS = 750L

internal fun trackTransitionGain(positionMs: Long, durationMs: Long, fadeIn: Boolean, fadeOut: Boolean): Float {
    val incoming = if (fadeIn) (positionMs.toFloat() / TRACK_FADE_MS).coerceIn(0f, 1f) else 1f
    val outgoing = if (fadeOut && durationMs > TRACK_FADE_MS * 2) {
        ((durationMs - positionMs).toFloat() / TRACK_FADE_MS).coerceIn(0f, 1f)
    } else 1f
    return minOf(incoming, outgoing)
}
