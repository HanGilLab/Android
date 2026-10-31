package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.designsystem.ParkedButton
import io.github.hangillab.hangil.core.domain.PracticeSession

/** The expanded, started history host owns this declaration query and its explicit confirmation. */
@Composable
internal fun HistoryCourseCompletionRoute(
    source: PracticeSession,
    historyGeneration: Long,
    canKeepOpen: () -> Boolean,
    viewModel: SessionHistoryViewModel,
) {
    val declarations =
        remember(source, historyGeneration, canKeepOpen, viewModel) {
            viewModel.observeCourseCompletion(source, historyGeneration, canKeepOpen)
        }
    val observed by declarations.collectAsStateWithLifecycle(
        SessionHistoryCourseCompletionUiState.Loading(source, historyGeneration, SessionHistoryCourseCompletionUiState.Token(0, 0, 0)),
    )
    val displayed = observed
    if (displayed.source != source || displayed.historyGeneration != historyGeneration) return
    if (displayed.token.readerId != 0L) {
        // The owner records a denied host/gate before this composition hides its old actions.
        if (!viewModel.canReadCourseCompletion(displayed.token)) return
    } else {
        val hostAllowed =
            try {
                canKeepOpen()
            } catch (_: Exception) {
                false
            }
        if (!hostAllowed) return
    }
    if (!viewModel.canRevealCourseCompletion(source.sessionId, historyGeneration)) return

    Column(
        Modifier.fillMaxWidth().testTag("history_course_completion_content"),
        verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg),
    ) {
        Text(
            stringResource(R.string.course_completion_source),
            Modifier.fillMaxWidth().testTag("history_course_completion_source"),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        when (displayed) {
            is SessionHistoryCourseCompletionUiState.Loading ->
                Text(
                    stringResource(R.string.course_completion_loading),
                    Modifier.fillMaxWidth().testTag("history_course_completion_loading"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            is SessionHistoryCourseCompletionUiState.Locked, is SessionHistoryCourseCompletionUiState.Unavailable -> Unit
            is SessionHistoryCourseCompletionUiState.Absent -> {
                Text(
                    stringResource(R.string.course_completion_absent),
                    Modifier.fillMaxWidth().testTag("history_course_completion_absent"),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (!displayed.canDeclare) {
                    Text(
                        stringResource(R.string.course_completion_not_available),
                        Modifier.fillMaxWidth().testTag("history_course_completion_active"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                val request: () -> Unit = { viewModel.requestCourseCompletion(displayed.token) }
                ParkedButton(
                    onClick = request,
                    enabled = displayed.canDeclare,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("history_course_completion_request")
                            .frozenObservationClick(request, displayed.canDeclare),
                ) { Text(stringResource(R.string.course_completion_record), style = MaterialTheme.typography.titleMedium) }
            }
            is SessionHistoryCourseCompletionUiState.Confirming ->
                CourseCompletionDeclarationConfirmation(
                    displayed.source,
                    false,
                    { viewModel.confirmCourseCompletion(displayed.token, displayed.nonce) },
                    { viewModel.dismissCourseCompletion(displayed.token, displayed.nonce) },
                )
            is SessionHistoryCourseCompletionUiState.Saving -> CourseCompletionDeclarationConfirmation(displayed.source, true, {}, {})
            is SessionHistoryCourseCompletionUiState.Ready -> CourseCompletionDeclarationContent(displayed)
            is SessionHistoryCourseCompletionUiState.Failed -> {
                Text(
                    stringResource(courseCompletionFailureText(displayed.reason)),
                    Modifier.fillMaxWidth().testTag("history_course_completion_failed"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                val retry: () -> Unit = { viewModel.retryCourseCompletion(displayed.token) }
                TextButton(
                    onClick = retry,
                    modifier =
                        Modifier
                            .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                            .testTag("history_course_completion_retry")
                            .frozenObservationClick(retry),
                ) { Text(stringResource(R.string.course_completion_retry), style = MaterialTheme.typography.titleMedium) }
            }
        }
        Text(
            stringResource(R.string.course_completion_scope),
            Modifier.fillMaxWidth().testTag("history_course_completion_scope"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun courseCompletionFailureText(reason: SessionHistoryCourseCompletionUiState.FailureReason): Int =
    when (reason) {
        SessionHistoryCourseCompletionUiState.FailureReason.ACTIVE_SESSION -> R.string.course_completion_active
        SessionHistoryCourseCompletionUiState.FailureReason.MISSING_SESSION,
        SessionHistoryCourseCompletionUiState.FailureReason.SOURCE_CHANGED,
        -> R.string.course_completion_changed
        SessionHistoryCourseCompletionUiState.FailureReason.BLOCKED -> R.string.course_completion_blocked
        SessionHistoryCourseCompletionUiState.FailureReason.WRITE_UNCONFIRMED,
        SessionHistoryCourseCompletionUiState.FailureReason.INVALID_SAVE_RESULT,
        -> R.string.course_completion_write_unconfirmed
        SessionHistoryCourseCompletionUiState.FailureReason.INVALID_TIME -> R.string.course_completion_invalid_time
        else -> R.string.course_completion_read_failed
    }
