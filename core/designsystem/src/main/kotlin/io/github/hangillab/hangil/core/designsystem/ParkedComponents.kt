package io.github.hangillab.hangil.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight

/** Visual variants for the parked home and preparation screens; callers retain all safety gates. */
@Composable
fun ParkedPage(content: @Composable ColumnScope.() -> Unit) {
    PracticePage(
        maxWidth = HanGilDimensions.parkedPageMaxWidth,
        contentPadding = PaddingValues(HanGilSpacing.xl),
        verticalSpacing = HanGilSpacing.lg,
        content = content,
    )
}

@Composable
fun ParkedPanel(
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.padding(if (emphasized) HanGilSpacing.panelInset else HanGilSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(HanGilSpacing.md),
            content = content,
        )
    }
}

@Composable
fun ParkedBadge(
    text: String,
    emphasized: Boolean = false,
) {
    Surface(
        color = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = HanGilSpacing.sm, vertical = HanGilSpacing.xs),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun ParkedChoice(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showDescription: Boolean = true,
) {
    Surface(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (showDescription) HanGilDimensions.choiceMinHeight else HanGilDimensions.secondaryActionMinHeight)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border =
            if (selected) {
                BorderStroke(
                    HanGilDimensions.choiceSelectedBorderWidth,
                    if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            } else {
                null
            },
    ) {
        Row(
            Modifier.padding(HanGilSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(HanGilSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected, onClick = null, modifier = Modifier.clearAndSetSemantics {}, enabled = enabled)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (showDescription) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun ParkedLockedNotice() {
    ParkedPanel {
        Text(
            stringResource(R.string.practice_locked_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(stringResource(R.string.practice_locked_description), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.practice_locked_saved),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
