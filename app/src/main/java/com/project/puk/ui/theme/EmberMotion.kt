package com.project.puk.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material3.IconButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.invisibleToUser

internal object EmberMotion {
    const val Fast = 140
    const val Standard = 210
    const val Emphasized = 300
    const val Ambient = 420
}

@Composable
internal fun Modifier.emberPress(source: MutableInteractionSource, pressedScale: Float): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (pressed) tween(EmberMotion.Fast, easing = FastOutSlowInEasing)
        else spring(dampingRatio = Spring.DampingRatioLowBouncy + 0.2f, stiffness = Spring.StiffnessMedium),
        label = "Ember press",
    )
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

@Composable
internal fun EmberTransportButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable (MutableInteractionSource) -> Unit) {
    val press = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = press,
        content = { content(press) },
    )
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
internal fun Modifier.emberInactive(inactive: Boolean): Modifier =
    if (!inactive) this else this
        .clearAndSetSemantics { invisibleToUser() }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        }
