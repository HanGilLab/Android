package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.ParkedButton
import io.github.hangillab.hangil.core.designsystem.ParkedPanel
import io.github.hangillab.hangil.core.domain.BackgroundRecordingState
import io.github.hangillab.hangil.core.domain.PracticeSession
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun BackgroundRecordingRoute(
    source: PracticeSession?,
    canPresent: () -> Boolean,
    viewModel: BackgroundRecordingViewModel = hiltViewModel(),
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var owner by remember(lifecycle, source) { mutableStateOf(AtomicBoolean(false)) }
    DisposableEffect(lifecycle, source) {
        fun stop() {
            owner.set(false)
        }

        fun start() {
            stop()
            owner = AtomicBoolean(true)
        }
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> start()
                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> stop()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose {
            stop()
            lifecycle.removeObserver(observer)
        }
    }
    val observed by viewModel.state.collectAsStateWithLifecycle()
    val displayed = observed
    val capturedOwner = owner
    val present = {
        capturedOwner.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            runCatching(canPresent).getOrDefault(false)
    }
    if (!present()) return
    val phase = displayed.background
    val runId =
        when (phase) {
            is BackgroundRecordingState.Starting -> phase.runId
            is BackgroundRecordingState.Active -> phase.runId
            is BackgroundRecordingState.Stopping -> phase.runId
            is BackgroundRecordingState.Failed -> phase.runId
            else -> null
        }
    if (source == null && runId == null) return
    val boundSession =
        when (phase) {
            is BackgroundRecordingState.Starting -> phase.sessionId
            is BackgroundRecordingState.Active -> phase.sessionId
            is BackgroundRecordingState.Stopping -> phase.sessionId
            is BackgroundRecordingState.Failed -> phase.sessionId
            is BackgroundRecordingState.Interrupted -> phase.sessionId
            else -> null
        }
    val foreign = source == null || (boundSession != null && boundSession != source.sessionId)
    val resource =
        if (source == null) {
            R.string.background_recording_cleanup
        } else if (foreign) {
            R.string.background_recording_foreign
        } else {
            when (phase) {
                BackgroundRecordingState.Disabled -> R.string.background_recording_disabled
                is BackgroundRecordingState.Starting -> R.string.background_recording_starting
                is BackgroundRecordingState.Active -> R.string.background_recording_active
                is BackgroundRecordingState.Stopping -> R.string.background_recording_stopping
                is BackgroundRecordingState.Failed -> R.string.background_recording_failed
                is BackgroundRecordingState.Interrupted -> R.string.background_recording_interrupted
            }
        }
    ParkedPanel(Modifier.testTag("background_recording_panel")) {
        Text(stringResource(R.string.background_recording_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(resource), Modifier.testTag("background_recording_state"), style = MaterialTheme.typography.bodyLarge)
        if (phase is BackgroundRecordingState.Interrupted && !foreign) {
            Text(
                stringResource(R.string.background_recording_unknown_end),
                Modifier.testTag("background_recording_interrupted"),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Text(
            stringResource(R.string.background_recording_scope),
            Modifier.testTag("background_recording_scope"),
            style = MaterialTheme.typography.bodyLarge,
        )
        key(displayed, source, capturedOwner) {
            if (source != null && !foreign &&
                (
                    phase is BackgroundRecordingState.Disabled || phase is BackgroundRecordingState.Failed ||
                        phase is BackgroundRecordingState.Interrupted
                )
            ) {
                val enable = {
                    viewModel.enable(displayed, source, present)
                    Unit
                }
                ParkedButton(
                    onClick = enable,
                    prominent = false,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                            .testTag("background_recording_enable")
                            .frozenObservationClick(enable),
                ) { Text(stringResource(R.string.background_recording_enable), style = MaterialTheme.typography.titleLarge) }
            }
            if (runId != null && phase !is BackgroundRecordingState.Stopping) {
                val stop = {
                    viewModel.stop(displayed, runId, present)
                    Unit
                }
                ParkedButton(
                    onClick = stop,
                    prominent = false,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                            .testTag("background_recording_stop")
                            .frozenObservationClick(stop),
                ) { Text(stringResource(R.string.background_recording_stop), style = MaterialTheme.typography.titleLarge) }
            }
        }
    }
}
