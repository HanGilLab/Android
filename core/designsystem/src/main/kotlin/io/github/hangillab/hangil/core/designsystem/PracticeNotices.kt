package io.github.hangillab.hangil.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun PracticeLoadingNotice(
    message: String,
    modifier: Modifier = Modifier,
) {
    PracticePanel(modifier) {
        CircularProgressIndicator(Modifier.size(HanGilDimensions.loadingIndicatorSize))
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun PracticeEmptyNotice(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    PracticePanel(modifier) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(description, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Presentation only. The caller supplies an action only when it is actually available. */
@Composable
fun PracticeErrorNotice(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(
            Modifier.padding(HanGilSpacing.panelInset),
            verticalArrangement = Arrangement.spacedBy(HanGilSpacing.md),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(description, style = MaterialTheme.typography.bodyLarge)
            action()
        }
    }
}
