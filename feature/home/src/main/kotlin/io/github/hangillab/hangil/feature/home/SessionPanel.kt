package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.ParkedBadge
import io.github.hangillab.hangil.core.designsystem.ParkedButton
import io.github.hangillab.hangil.core.designsystem.ParkedDisclosure
import io.github.hangillab.hangil.core.designsystem.ParkedPanel
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
    recordingState: VehicleRecordingState,
    onRetryRecording: () -> Unit,
    viewModel: SessionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (state.allowsInteraction) {
        SessionPanel(
            state,
            viewModel::start,
            viewModel::finish,
            viewModel::retryLoading,
            onPrepare,
            canPrepare,
            recordingState,
            onRetryRecording,
        )
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
    recordingState: VehicleRecordingState,
    onRetryRecording: () -> Unit,
) {
    if (!state.allowsInteraction) return
    val outdated = (state.preparation as? PreparationLoadState.Loaded)?.preparation == Preparation.Outdated
    ParkedPanel(
        Modifier.testTag("home_journey"),
        emphasized = true,
    ) {
        HomeJourneyGuide(state)
        if (state.active == null) {
            Text(
                stringResource(R.string.session_record_source),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.nextStep == HomeJourneyStep.PREPARE || state.nextStep == HomeJourneyStep.REPEAT) {
            ParkedButton(
                onClick = onPrepare,
                enabled = canPrepare && state.operation != SessionOperationState.Saving,
                prominent = true,
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
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        when (state.records) {
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
                    if (active.courseReviewStatus == CourseReviewStatus.DEMO) ParkedBadge(stringResource(R.string.demo_course))
                    VehicleRecordingStatus(recordingState, onRetryRecording)
                    ParkedButton(
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
                            if (state.ready != null) {
                                ParkedButton(
                                    onClick = onStart,
                                    enabled = state.canStart,
                                    prominent = state.nextStep == HomeJourneyStep.READY,
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
        if (state.nextStep != HomeJourneyStep.PREPARE && state.nextStep != HomeJourneyStep.REPEAT && canPrepare) {
            ParkedButton(
                onClick = onPrepare,
                enabled = state.operation != SessionOperationState.Saving,
                prominent = false,
                modifier = Modifier.testTag("home_prepare"),
            ) {
                Text(stringResource(R.string.prepare_change), style = MaterialTheme.typography.titleMedium)
            }
        }
        key(state.nextStep, state.active?.sessionId) {
            ParkedDisclosure(stringResource(R.string.record_guide), Modifier.testTag("home_record_guide")) {
                Text(stringResource(R.string.preparation_scope), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.session_scope), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.journey_flow),
                    Modifier.testTag("home_journey_flow"),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(stringResource(R.string.journey_scope), style = MaterialTheme.typography.bodyLarge)
                state.active?.let { active ->
                    Text(stringResource(R.string.session_goal_task, active.goalTitle, active.taskTitle))
                    Text(
                        stringResource(R.string.session_started_at, displayTime(active.startedAtEpochMillis)),
                        Modifier.testTag("session_started_at"),
                    )
                }
                if (state.nextStep == HomeJourneyStep.REPEAT) Text(stringResource(R.string.journey_repeat_hint))
            }
        }
    }
    LatestSessionPanel(state)
}

@Composable
private fun VehicleRecordingStatus(
    state: VehicleRecordingState,
    onRetry: () -> Unit,
) {
    Text(
        stringResource(
            when (state) {
                VehicleRecordingState.Idle, VehicleRecordingState.Paused -> R.string.recording_paused
                VehicleRecordingState.Loading -> R.string.recording_loading
                VehicleRecordingState.Recording -> R.string.recording_active
                is VehicleRecordingState.Failed -> R.string.recording_failed
            },
        ),
        Modifier.testTag("vehicle_recording_status"),
        style = MaterialTheme.typography.bodyLarge,
        color = if (state is VehicleRecordingState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state is VehicleRecordingState.Failed) {
        TextButton(
            onClick = onRetry,
            modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight).testTag("vehicle_recording_retry"),
        ) { Text(stringResource(R.string.recording_retry)) }
    }
}

@Composable
private fun LatestSessionPanel(state: SessionUiState) {
    val records = state.records as? SessionLoadState.Loaded ?: return
    val latest = state.latestFinished
    if (latest != null) {
        ParkedPanel(Modifier.testTag("home_recent_session")) {
            Text(stringResource(R.string.session_latest), style = MaterialTheme.typography.titleLarge)
            Text(latest.courseTitle, Modifier.testTag("session_latest_course"), style = MaterialTheme.typography.headlineSmall)
            if (latest.courseReviewStatus == CourseReviewStatus.DEMO) ParkedBadge(stringResource(R.string.demo_course))
            latest.endedAtEpochMillis?.let {
                Text(stringResource(R.string.session_finished_at, displayTime(it)), Modifier.testTag("session_latest_finished"))
            }
            key(latest.sessionId) {
                ParkedDisclosure(stringResource(R.string.session_details), Modifier.testTag("home_recent_details")) {
                    Text(stringResource(R.string.session_goal_task, latest.goalTitle, latest.taskTitle))
                    Text(
                        stringResource(R.string.session_started_at, displayTime(latest.startedAtEpochMillis)),
                        Modifier.testTag("session_latest_started"),
                    )
                }
            }
        }
    } else if (records.sessions.isEmpty()) {
        ParkedPanel {
            Text(stringResource(R.string.session_empty), style = MaterialTheme.typography.bodyLarge)
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
