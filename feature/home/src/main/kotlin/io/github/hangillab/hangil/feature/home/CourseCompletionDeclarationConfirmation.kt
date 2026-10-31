package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.designsystem.ParkedButton
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.PracticeSession

@Composable
internal fun CourseCompletionDeclarationConfirmation(
    source: PracticeSession,
    saving: Boolean,
    confirm: () -> Unit,
    dismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = dismiss,
        modifier = Modifier.testTag("history_course_completion_dialog"),
        title = { Text(stringResource(R.string.course_completion_confirm_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(
                        max = LocalConfiguration.current.screenHeightDp.dp * 0.45f,
                    ).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg),
            ) {
                Text(
                    source.goalTitle,
                    Modifier.fillMaxWidth().testTag("history_course_completion_confirm_goal"),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    source.courseTitle,
                    Modifier.fillMaxWidth().testTag("history_course_completion_confirm_course"),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    source.taskTitle,
                    Modifier.fillMaxWidth().testTag("history_course_completion_confirm_task"),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(
                        when (source.courseReviewStatus) {
                            CourseReviewStatus.DEMO -> R.string.demo_course
                        },
                    ),
                    Modifier.fillMaxWidth().testTag("history_course_completion_confirm_demo"),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.session_finished_at, displayTime(checkNotNull(source.endedAtEpochMillis))),
                    Modifier.fillMaxWidth().testTag("history_course_completion_confirm_finished"),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.course_completion_confirm_scope),
                    Modifier.fillMaxWidth().testTag("history_course_completion_confirm_scope"),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (saving) {
                    Text(
                        stringResource(R.string.course_completion_saving),
                        Modifier.fillMaxWidth().testTag("history_course_completion_saving"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        },
        confirmButton = {
            ParkedButton(
                onClick = confirm,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().testTag("history_course_completion_confirm").frozenObservationClick(confirm, !saving),
            ) { Text(stringResource(R.string.course_completion_record), style = MaterialTheme.typography.titleMedium) }
        },
        dismissButton = {
            TextButton(
                onClick = dismiss,
                enabled = !saving,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(
                            min = HanGilDimensions.secondaryActionMinHeight,
                        ).testTag("history_course_completion_cancel")
                        .frozenObservationClick(dismiss, !saving),
            ) {
                Text(stringResource(R.string.course_completion_cancel), style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}
