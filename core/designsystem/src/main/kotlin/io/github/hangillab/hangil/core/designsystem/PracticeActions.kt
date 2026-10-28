package io.github.hangillab.hangil.core.designsystem

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Material button behavior with shared minimum sizing; the caller owns product enablement. */
@Composable
fun PracticeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier =
            modifier
                .heightIn(min = HanGilDimensions.primaryActionMinHeight)
                .widthIn(min = HanGilDimensions.actionMinWidth),
        enabled = enabled,
        content = content,
    )
}

/** The caller supplies text and actions; this does not infer a safe vehicle or retry state. */
@Composable
fun PracticeTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier =
            modifier
                .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                .widthIn(min = HanGilDimensions.actionMinWidth),
        enabled = enabled,
        content = content,
    )
}
