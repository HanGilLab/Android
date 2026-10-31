package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.designsystem.ParkedBadge
import io.github.hangillab.hangil.core.designsystem.ParkedButton
import io.github.hangillab.hangil.core.domain.RecordingRunStatus

/** Render creation order and stored marker times, without exposing identities or inferring coverage. */
@Composable
internal fun StoredRecordingRunContent(
    state: SessionHistoryRecordingRunUiState.Ready,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val run = state.run
    Column(Modifier.fillMaxWidth().testTag("history_recording_run_ready"), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
        Column(Modifier.testTag("history_recording_run_source")) { ParkedBadge(stringResource(R.string.history_recording_run_source)) }
        if (state.hasInterrupted) {
            Text(
                stringResource(R.string.history_recording_run_interrupted),
                Modifier.testTag("history_recording_run_interrupted"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            stringResource(R.string.history_recording_run_position, state.index + 1, state.runs.size),
            Modifier.testTag("history_recording_run_position"),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(
                when (run.status) {
                    RecordingRunStatus.STOPPED -> R.string.history_recording_run_stopped
                    RecordingRunStatus.FAILED -> R.string.history_recording_run_failed_status
                    RecordingRunStatus.INTERRUPTED -> R.string.history_recording_run_interrupted_status
                    RecordingRunStatus.OPEN -> R.string.history_recording_run_open
                },
            ),
            Modifier.testTag("history_recording_run_status"),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.history_recording_run_started, displayTime(run.startedAtEpochMillis)),
            Modifier.testTag("history_recording_run_started"),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            run.endedAtEpochMillis?.let { stringResource(R.string.history_recording_run_ended, displayTime(it)) }
                ?: stringResource(R.string.history_recording_run_ended_unknown),
            Modifier.testTag("history_recording_run_ended"),
            style = MaterialTheme.typography.bodyLarge,
        )
        RecordingRunNavigation(state.canPrevious, state.canNext, onPrevious, onNext)
    }
}

@Composable
private fun RecordingRunNavigation(
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val previous: @Composable () -> Unit = {
        ParkedButton(
            onClick = onPrevious,
            enabled = hasPrevious,
            prominent = false,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                    .testTag("history_recording_run_previous")
                    .frozenObservationClick(onPrevious, hasPrevious),
        ) { Text(stringResource(R.string.history_recording_run_previous), style = MaterialTheme.typography.titleMedium) }
    }
    val next: @Composable () -> Unit = {
        ParkedButton(
            onClick = onNext,
            enabled = hasNext,
            prominent = false,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                    .testTag("history_recording_run_next")
                    .frozenObservationClick(onNext, hasNext),
        ) { Text(stringResource(R.string.history_recording_run_next), style = MaterialTheme.typography.titleMedium) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 520.dp || LocalDensity.current.fontScale > 1.2f) {
            Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
                previous()
                next()
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
                Column(Modifier.weight(1f)) { previous() }
                Column(Modifier.weight(1f)) { next() }
            }
        }
    }
}
