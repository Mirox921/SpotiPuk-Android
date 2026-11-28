package com.project.puk.ui

import com.project.puk.core.Lang

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.project.puk.ui.theme.EmberIconButton
import com.project.puk.ui.theme.EmberIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EmberSheet(
    onDismissRequest: () -> Unit,
    dismissEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val canDismiss by rememberUpdatedState(dismissEnabled)
    ModalBottomSheet(
        onDismissRequest = { if (canDismiss) onDismissRequest() },
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || canDismiss },
        ),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        scrimColor = Color.Black.copy(alpha = 0.64f),
        content = content,
    )
}

@Composable
internal fun EmberActionSheet(
    onDismissRequest: () -> Unit,
    heading: String,
    dismissEnabled: Boolean = true,
    scrollBody: Boolean = true,
    dismissButton: (@Composable () -> Unit)? = null,
    confirmButton: (@Composable () -> Unit)? = null,
    headingIcon: Painter? = null,
    text: @Composable ColumnScope.() -> Unit,
) {
    EmberSheet(onDismissRequest, dismissEnabled) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (headingIcon != null) {
                    Icon(
                        headingIcon,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 12.dp).size(24.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(heading, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                EmberIconButton(EmberIcons.Close, Lang.t(Lang.Key.SHEET_CLOSE_PANEL), onDismissRequest, enabled = dismissEnabled)
            }
            val scroll = if (scrollBody) Modifier.verticalScroll(rememberScrollState()) else Modifier
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth().then(scroll).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = text,
            )
            if (dismissButton != null || confirmButton != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    dismissButton?.invoke()
                    confirmButton?.invoke()
                }
            }
        }
    }
}

@Composable
internal fun EmberSheetCard(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun EmberSheetChoice(
    label: String,
    selected: Boolean,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = if (icon != null) {
            { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) }
        } else null,
        trailingContent = {
            if (selected) Icon(EmberIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            else Spacer(Modifier.size(24.dp))
        },
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
