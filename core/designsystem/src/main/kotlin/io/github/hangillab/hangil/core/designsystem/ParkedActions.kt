package io.github.hangillab.hangil.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Prominence changes appearance only; enablement and callbacks are always supplied by the screen. */
@Composable
fun ParkedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    prominent: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val sized =
        modifier
            .heightIn(min = if (prominent) HanGilDimensions.primaryActionMinHeight else HanGilDimensions.secondaryActionMinHeight)
            .widthIn(min = HanGilDimensions.actionMinWidth)
    val padding = PaddingValues(horizontal = HanGilSpacing.xl, vertical = HanGilSpacing.md)
    if (prominent) {
        Button(
            onClick,
            sized,
            enabled,
            shape = MaterialTheme.shapes.medium,
            contentPadding = padding,
            content = content,
        )
    } else {
        OutlinedButton(
            onClick,
            sized,
            enabled,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            contentPadding = padding,
            content = content,
        )
    }
}
