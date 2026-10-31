package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing

@Composable
internal fun CourseCompletionDeclarationContent(state: SessionHistoryCourseCompletionUiState.Ready) {
    Column(
        Modifier.fillMaxWidth().testTag("history_course_completion_ready"),
        verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg),
    ) {
        Text(
            stringResource(
                if (state.feedback ==
                    SessionHistoryCourseCompletionUiState.Feedback.ALREADY_DECLARED
                ) {
                    R.string.course_completion_already_declared
                } else {
                    R.string.course_completion_declared
                },
            ),
            Modifier.fillMaxWidth().testTag("history_course_completion_declared"),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.course_completion_declared_at, displayTime(state.declaration.declaredAtEpochMillis)),
            Modifier.fillMaxWidth().testTag("history_course_completion_declared_at"),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
