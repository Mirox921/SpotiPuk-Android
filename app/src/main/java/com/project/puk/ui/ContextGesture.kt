package com.project.puk.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.project.puk.core.Lang

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.contextGesture(
    title: String,
    onClick: () -> Unit,
    onActions: () -> Unit,
    interactionSource: MutableInteractionSource? = null,
): Modifier {
    val haptics = LocalHapticFeedback.current
    return combinedClickable(
        interactionSource = interactionSource,
        indication = LocalIndication.current,
        onClick = onClick,
        onLongClickLabel = contextActionLabel(title),
        onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onActions()
        },
    )
}

@Composable
internal fun contextActionLabel(title: String): String = Lang.t(Lang.Key.CONTEXT_MORE_ACTIONS, title)
