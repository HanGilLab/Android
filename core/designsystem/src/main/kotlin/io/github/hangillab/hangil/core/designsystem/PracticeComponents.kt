package io.github.hangillab.hangil.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics

@Composable
fun PracticePage(content: @Composable ColumnScope.() -> Unit) {
    Scaffold { inset ->
        Box(Modifier.fillMaxSize().padding(inset).verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = HanGilDimensions.pageMaxWidth).fillMaxWidth().padding(HanGilSpacing.pageInset),
                verticalArrangement = Arrangement.spacedBy(HanGilSpacing.xl),
                content = content,
            )
        }
    }
}

@Composable
fun PracticeColumns(
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= HanGilDimensions.wideBreakpoint) {
            Row(horizontalArrangement = Arrangement.spacedBy(HanGilSpacing.xl)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) { first() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) { second() }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.xl)) {
                first()
                second()
            }
        }
    }
}

@Composable
fun PracticePanel(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = contentColorFor(containerColor),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = containerColor, contentColor = contentColor) {
        Column(Modifier.padding(HanGilSpacing.panelInset), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.md), content = content)
    }
}

@Composable
fun PracticeBadge(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Text(
            text,
            Modifier.padding(horizontal = HanGilSpacing.badgeHorizontal, vertical = HanGilSpacing.xs),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
fun PracticeChoice(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val disabledContentColor = ButtonDefaults.buttonColors().disabledContentColor
    Surface(
        modifier
            .fillMaxWidth()
            .heightIn(min = HanGilDimensions.choiceMinHeight)
            .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor =
            when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                selected -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
        border =
            BorderStroke(
                if (selected) HanGilDimensions.choiceSelectedBorderWidth else HanGilDimensions.choiceBorderWidth,
                when {
                    !enabled -> disabledContentColor
                    selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
            ),
    ) {
        Row(
            Modifier.padding(HanGilSpacing.xl),
            horizontalArrangement = Arrangement.spacedBy(HanGilSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (selected) "●" else "○",
                Modifier.clearAndSetSemantics {},
                color = if (enabled) MaterialTheme.colorScheme.primary else disabledContentColor,
                style = MaterialTheme.typography.headlineSmall,
            )
            Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(description, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
fun PracticeLockedNotice() {
    PracticePanel {
        Text(stringResource(R.string.practice_locked_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.practice_locked_description), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.practice_locked_saved), style = MaterialTheme.typography.bodyLarge)
    }
}
