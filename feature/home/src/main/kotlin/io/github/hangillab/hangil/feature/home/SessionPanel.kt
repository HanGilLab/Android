package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.PracticeBadge
import io.github.hangillab.hangil.core.designsystem.PracticePanel
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.Preparation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun SessionRoute(
    onPrepare: () -> Unit,
    canPrepare: Boolean,
    viewModel: SessionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (state.allowsInteraction) {
        SessionPanel(state, viewModel::start, viewModel::finish, viewModel::retryLoading, onPrepare, canPrepare)
    }
}

@Composable
internal fun SessionPanel(
    state: SessionUiState,
    onStart: () -> Unit,
    onFinish: (String) -> Unit,
    onRetry: () -> Unit,
    onPrepare: () -> Unit,
    canPrepare: Boolean,
) {
    if (!state.allowsInteraction) return
    val outdated = (state.preparation as? PreparationLoadState.Loaded)?.preparation == Preparation.Outdated
    PracticePanel(Modifier.testTag("home_journey")) {
        HomeJourneyGuide(state)
        if (state.nextStep == HomeJourneyStep.PREPARE || state.nextStep == HomeJourneyStep.REPEAT) {
            Button(
                onClick = onPrepare,
                enabled = canPrepare && state.operation != SessionOperationState.Saving,
                modifier =
                    Modifier.fillMaxWidth().heightIn(min = HanGilDimensions.primaryActionMinHeight).testTag("home_prepare"),
            ) {
                Text(
                    stringResource(
                        when {
                            state.nextStep == HomeJourneyStep.REPEAT -> R.string.journey_prepare_again
                            outdated -> R.string.journey_prepare_update
                            state.latestFinished != null -> R.string.journey_prepare_again
                            else -> R.string.prepare_start
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        } else if (canPrepare) {
            TextButton(
                onClick = onPrepare,
                enabled = state.operation != SessionOperationState.Saving,
                modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight).testTag("home_prepare"),
            ) {
                Text(stringResource(R.string.prepare_change), style = MaterialTheme.typography.titleLarge)
            }
        }
        Text(stringResource(R.string.session_scope), style = MaterialTheme.typography.bodyLarge)
        when (val records = state.records) {
            SessionLoadState.Loading -> Text(stringResource(R.string.session_loading))
            SessionLoadState.Failed -> {
                Text(stringResource(R.string.session_load_failed), Modifier.testTag("session_load_failed"))
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight),
                ) { Text(stringResource(R.string.retry)) }
            }
            is SessionLoadState.Loaded -> {
                val active = state.active
                if (active != null) {
                    Text(
                        stringResource(R.string.session_active),
                        Modifier.testTag("session_active"),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(active.courseTitle, Modifier.testTag("session_course"), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.session_goal_task, active.goalTitle, active.taskTitle))
                    if (active.courseReviewStatus == CourseReviewStatus.DEMO) PracticeBadge(stringResource(R.string.demo_course))
                    Text(
                        stringResource(R.string.session_started_at, displayTime(active.startedAtEpochMillis)),
                        Modifier.testTag("session_started_at"),
                    )
                    Button(
                        onClick = {
                            onFinish(active.sessionId)
                        },
                        enabled = state.canFinish,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = HanGilDimensions.primaryActionMinHeight)
                                .testTag("session_finish"),
                    ) {
                        Text(stringResource(R.string.session_finish), style = MaterialTheme.typography.titleLarge)
                    }
                } else {
                    when (state.preparation) {
                        PreparationLoadState.Loading -> Text(stringResource(R.string.loading_preparation))
                        PreparationLoadState.Failed -> {
                            Text(stringResource(R.string.load_failed))
                            TextButton(
                                onClick = onRetry,
                                modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight),
                            ) { Text(stringResource(R.string.retry)) }
                        }
                        is PreparationLoadState.Loaded -> {
                            if (state.ready == null) {
                                Text(stringResource(R.string.session_prepare_first))
                            } else {
                                Text(stringResource(R.string.session_ready, state.ready!!.course.title))
                                Button(
                                    onClick = onStart,
                                    enabled = state.canStart,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = HanGilDimensions.primaryActionMinHeight)
                                            .testTag("session_start"),
                                ) {
                                    Text(stringResource(R.string.session_start), style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        }
                    }
                }
                val latest = state.latestFinished
                if (latest != null) {
                    Text(stringResource(R.string.session_latest), style = MaterialTheme.typography.titleLarge)
                    Text(latest.courseTitle, Modifier.testTag("session_latest_course"))
                    Text(stringResource(R.string.session_goal_task, latest.goalTitle, latest.taskTitle))
                    if (latest.courseReviewStatus == CourseReviewStatus.DEMO) PracticeBadge(stringResource(R.string.demo_course))
                    Text(stringResource(R.string.session_started_at, displayTime(latest.startedAtEpochMillis)))
                    latest.endedAtEpochMillis?.let {
                        Text(stringResource(R.string.session_finished_at, displayTime(it)), Modifier.testTag("session_latest_finished"))
                    }
                } else if (records.sessions.isEmpty()) {
                    Text(stringResource(R.string.session_empty))
                }
            }
        }
        when (val operation = state.operation) {
            SessionOperationState.Idle -> Unit
            SessionOperationState.Saving -> Text(stringResource(R.string.session_saving), Modifier.testTag("session_saving"))
            is SessionOperationState.Feedback -> {
                Text(stringResource(feedbackText(operation.value)), Modifier.testTag("session_feedback"))
                if (operation.value == SessionFeedback.MISSING) {
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight),
                    ) { Text(stringResource(R.string.retry)) }
                }
            }
        }
    }
}

private fun displayTime(epochMillis: Long): String =
    DateTimeFormatter
        .ofLocalizedDateTime(
            FormatStyle.MEDIUM,
        ).withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))

private fun feedbackText(feedback: SessionFeedback): Int =
    when (feedback) {
        SessionFeedback.STARTED -> R.string.session_started
        SessionFeedback.FINISHED -> R.string.session_finished
        SessionFeedback.ALREADY_ACTIVE -> R.string.session_already_active
        SessionFeedback.ALREADY_FINISHED -> R.string.session_already_finished
        SessionFeedback.PREPARATION_CHANGED -> R.string.session_preparation_changed
        SessionFeedback.BLOCKED -> R.string.session_blocked
        SessionFeedback.INVALID_TIME -> R.string.session_invalid_time
        SessionFeedback.MISSING -> R.string.session_missing
        SessionFeedback.SAVE_FAILED -> R.string.session_save_failed
    }
