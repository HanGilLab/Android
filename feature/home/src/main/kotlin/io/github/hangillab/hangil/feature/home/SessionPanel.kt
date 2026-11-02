package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.ParkedBadge
import io.github.hangillab.hangil.core.designsystem.ParkedButton
import io.github.hangillab.hangil.core.designsystem.ParkedDisclosure
import io.github.hangillab.hangil.core.designsystem.ParkedPanel
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.VehicleRecordingState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun SessionRoute(
    onPrepare: () -> Unit,
    onPrepareNext: (String) -> Unit,
    canPrepare: Boolean,
    recordingState: VehicleRecordingState,
    onRetryRecording: () -> Unit,
    viewModel: SessionViewModel = hiltViewModel(),
    onFinishedReview: ((PracticeSession) -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val finishAction by viewModel.finishActions.collectAsStateWithLifecycle()
    val receipt by viewModel.finishReceipt.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumeOwner by remember(viewModel, lifecycle) { mutableStateOf(AtomicBoolean(false)) }
    DisposableEffect(viewModel, lifecycle) {
        fun retire() {
            resumeOwner.set(false)
            viewModel.setFinishHostResumed(false)
        }

        fun resume() {
            retire()
            resumeOwner = AtomicBoolean(true)
            viewModel.setFinishHostResumed(true)
        }
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> resume()
                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> retire()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState == Lifecycle.State.RESUMED && !resumeOwner.get()) resume()
        onDispose {
            retire()
            lifecycle.removeObserver(observer)
        }
    }
    val clickOwner = resumeOwner
    val canKeepOpen =
        remember(clickOwner, lifecycle) {
            { clickOwner.get() && lifecycle.currentState == Lifecycle.State.RESUMED }
        }
    val displayedAction = finishAction
    val displayedReceipt = receipt
    val backgroundViewModel: BackgroundRecordingViewModel = hiltViewModel()
    val displayed = state
    val background by backgroundViewModel.state.collectAsStateWithLifecycle()
    val ownedBackgroundFailure =
        (background.background as? io.github.hangillab.hangil.core.domain.BackgroundRecordingState.Failed)?.runId != null
    if (state.allowsInteraction) {
        SessionPanel(
            state,
            viewModel::start,
            { id ->
                displayedAction
                    ?.takeIf { it.source == displayed.active && it.source.sessionId == id }
                    ?.let { viewModel.finish(it, canKeepOpen) }
            },
            viewModel::retryLoading,
            onPrepare,
            { id -> if (viewModel.canPrepareNext(id)) onPrepareNext(id) },
            viewModel::canExpandGuide,
            viewModel::canExpandRecent,
            canPrepare,
            recordingState,
            onRetryRecording,
            allowRecordingRetry = !ownedBackgroundFailure,
            finishedReviewContent = {
                if (onFinishedReview != null && displayedReceipt != null && canKeepOpen() &&
                    viewModel.canReviewFinished(displayedReceipt)
                ) {
                    val reviewClick = {
                        viewModel.consumeFinishedReview(displayedReceipt, canKeepOpen)?.let(onFinishedReview)
                        Unit
                    }
                    ParkedButton(
                        onClick = reviewClick,
                        modifier = Modifier.fillMaxWidth().testTag("session_finish_review").frozenObservationClick(reviewClick),
                    ) {
                        Text(
                            stringResource(R.string.finish_review_open),
                            Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            },
            backgroundContent = {
                BackgroundRecordingRoute(
                    displayed.active,
                    { viewModel.canExpandGuide(displayed.nextStep, displayed.active) },
                    backgroundViewModel,
                )
            },
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
    onPrepareNext: (String) -> Unit,
    canExpandGuide: (HomeJourneyStep, PracticeSession?) -> Boolean,
    canExpandRecent: (PracticeSession) -> Boolean,
    canPrepare: Boolean,
    recordingState: VehicleRecordingState,
    onRetryRecording: () -> Unit,
    allowRecordingRetry: Boolean = true,
    backgroundContent: @Composable () -> Unit = {},
    finishedReviewContent: @Composable () -> Unit = {},
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
                    VehicleRecordingStatus(recordingState, onRetryRecording, allowRecordingRetry)
                    val finishClick = { onFinish(active.sessionId) }
                    ParkedButton(
                        onClick = finishClick,
                        enabled = state.canFinish,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = HanGilDimensions.primaryActionMinHeight)
                                .testTag("session_finish")
                                .frozenObservationClick(finishClick, state.canFinish),
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
        finishedReviewContent()
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
        key(state.nextStep, state.active) {
            ParkedDisclosure(
                stringResource(R.string.record_guide),
                { canExpandGuide(state.nextStep, state.active) },
                Modifier.testTag("home_record_guide"),
            ) {
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
    backgroundContent()
    LatestSessionPanel(state, onPrepareNext, canExpandRecent)
}

@Composable
private fun VehicleRecordingStatus(
    state: VehicleRecordingState,
    onRetry: () -> Unit,
    allowRetry: Boolean,
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
    if (state is VehicleRecordingState.Failed && allowRetry) {
        TextButton(
            onClick = onRetry,
            modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight).testTag("vehicle_recording_retry"),
        ) { Text(stringResource(R.string.recording_retry)) }
    }
}

@Composable
private fun LatestSessionPanel(
    state: SessionUiState,
    onPrepareNext: (String) -> Unit,
    canExpandRecent: (PracticeSession) -> Boolean,
) {
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
            key(latest) {
                ParkedDisclosure(
                    stringResource(R.string.session_details),
                    { canExpandRecent(latest) },
                    Modifier.testTag("home_recent_details"),
                ) {
                    Text(stringResource(R.string.session_goal_task, latest.goalTitle, latest.taskTitle))
                    Text(
                        stringResource(R.string.session_started_at, displayTime(latest.startedAtEpochMillis)),
                        Modifier.testTag("session_latest_started"),
                    )
                    RecentVehicleObservationRoute(latest.sessionId)
                }
            }
            key(latest) {
                ParkedDisclosure(
                    stringResource(R.string.memo_heading),
                    { canExpandRecent(latest) },
                    Modifier.testTag("home_recent_memo"),
                ) {
                    SessionMemoEditorRoute(latest.sessionId)
                }
            }
            if (state.active == null) {
                ParkedButton(
                    onClick = { onPrepareNext(latest.sessionId) },
                    enabled = state.operation != SessionOperationState.Saving,
                    prominent = false,
                    modifier = Modifier.fillMaxWidth().testTag("home_next_practice"),
                ) { Text(stringResource(R.string.next_practice_open), style = MaterialTheme.typography.titleLarge) }
            }
        }
    } else if (records.sessions.isEmpty()) {
        ParkedPanel {
            Text(stringResource(R.string.session_empty), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

internal fun displayTime(epochMillis: Long): String =
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
