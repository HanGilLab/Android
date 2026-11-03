package io.github.hangillab.hangil.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.ParkedButton
import io.github.hangillab.hangil.core.designsystem.ParkedPage
import io.github.hangillab.hangil.core.designsystem.ParkedPanel
import io.github.hangillab.hangil.core.domain.LocationRecordingState
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun LocationRecordingRoute(
    onClose: () -> Unit,
    onRequestPermission: () -> Unit,
    viewModel: LocationRecordingViewModel = hiltViewModel(),
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val routeOpen = remember(lifecycle, viewModel) { AtomicBoolean(true) }
    var owner by remember(lifecycle, viewModel) { mutableStateOf(AtomicBoolean(false)) }
    var resumed by remember(lifecycle, viewModel) { mutableStateOf(false) }
    DisposableEffect(lifecycle, viewModel) {
        fun invalidate() {
            owner.set(false)
            resumed = false
        }

        fun resume() {
            invalidate()
            if (!routeOpen.get()) return
            owner = AtomicBoolean(true)
            resumed = true
            viewModel.refreshPermission()
        }
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> resume()
                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> invalidate()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState == Lifecycle.State.RESUMED && !resumed) resume()
        onDispose {
            routeOpen.set(false)
            invalidate()
            lifecycle.removeObserver(observer)
        }
    }
    val observed by viewModel.uiState.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val observedRecording by viewModel.recordingState.collectAsStateWithLifecycle()
    val displayed = observed
    val recording = observedRecording
    val capturedOwner = owner
    val present =
        remember(capturedOwner, lifecycle, routeOpen) {
            { routeOpen.get() && capturedOwner.get() && lifecycle.currentState == Lifecycle.State.RESUMED }
        }
    val close: () -> Unit = {
        // Retire before navigation, even if disposal or a lifecycle notification is delayed.
        capturedOwner.set(false)
        if (owner === capturedOwner && routeOpen.compareAndSet(true, false)) {
            resumed = false
            onClose()
        }
    }
    BackHandler(onBack = close)
    ParkedPage {
        TextButton(
            onClick = close,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                    .testTag("location_recording_back")
                    .frozenObservationClick(close),
        ) {
            Text(
                stringResource(R.string.location_recording_back),
                Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Column(Modifier.fillMaxWidth().testTag("location_recording_screen")) {
            Text(
                stringResource(R.string.location_recording_title),
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.headlineLarge,
            )
        }
        LocationRecordingStatusContent(recording, { viewModel.stop(it) }, "location_recording")
        if (!resumed || !present()) {
            LocationRecordingNotice(R.string.location_recording_locked, "location_recording_locked")
        } else {
            when (displayed) {
                LocationRecordingUiState.Loading ->
                    LocationRecordingNotice(R.string.location_recording_loading, "location_recording_loading")
                is LocationRecordingUiState.Locked ->
                    LocationRecordingNotice(R.string.location_recording_locked, "location_recording_locked")
                is LocationRecordingUiState.NoActive ->
                    LocationRecordingNotice(R.string.location_recording_no_active, "location_recording_no_active")
                is LocationRecordingUiState.Failed ->
                    LocationRecordingNotice(R.string.location_recording_read_failed, "location_recording_failed")
                is LocationRecordingUiState.Ready ->
                    key(displayed, capturedOwner) {
                        LocationRecordingReady(displayed, recording, present, onRequestPermission, viewModel)
                    }
            }
        }
        ParkedPanel {
            Text(
                stringResource(R.string.location_recording_scope),
                Modifier.fillMaxWidth().testTag("location_recording_scope"),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.location_recording_source_warning),
                Modifier.fillMaxWidth().testTag("location_recording_source_warning"),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

/** Main keeps this privacy action outside all parked-only detail screens. */
@Composable
fun LocationRecordingStatusRow(
    state: LocationRecordingState,
    onStop: (String) -> Unit,
) {
    LocationRecordingStatusContent(state, onStop, "location_recording_global")
}

@Composable
private fun LocationRecordingStatusContent(
    state: LocationRecordingState,
    onStop: (String) -> Unit,
    prefix: String,
) {
    val message =
        when (state) {
            is LocationRecordingState.Starting -> R.string.location_recording_starting
            is LocationRecordingState.Recording -> R.string.location_recording_on
            is LocationRecordingState.Stopping -> R.string.location_recording_stopping
            is LocationRecordingState.Failed -> locationRecordingFailureMessage(state)
            LocationRecordingState.Idle, LocationRecordingState.Paused, is LocationRecordingState.Stopped -> return
        }
    val exactRun =
        when (state) {
            is LocationRecordingState.Starting -> state.runId
            is LocationRecordingState.Recording -> state.run.runId
            is LocationRecordingState.Stopping -> state.runId
            is LocationRecordingState.Failed -> state.runId
            else -> null
        }
    ParkedPanel(Modifier.testTag("${prefix}_status")) {
        Text(stringResource(message), Modifier.fillMaxWidth().testTag("${prefix}_state"), style = MaterialTheme.typography.bodyLarge)
        if (exactRun != null) {
            key(state) {
                val stop: () -> Unit = { onStop(exactRun) }
                ParkedButton(
                    stop,
                    prominent = false,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                            .testTag("${prefix}_stop")
                            .frozenObservationClick(stop),
                ) {
                    Text(
                        stringResource(R.string.location_recording_stop),
                        Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun LocationRecordingReady(
    displayed: LocationRecordingUiState.Ready,
    recording: LocationRecordingState,
    present: () -> Boolean,
    onRequestPermission: () -> Unit,
    viewModel: LocationRecordingViewModel,
) {
    if (!viewModel.canRequestPermission(displayed)) {
        LocationRecordingNotice(R.string.location_recording_locked, "location_recording_locked")
        return
    }
    val action = remember(displayed, present) { AtomicBoolean(true) }
    val busy =
        recording is LocationRecordingState.Starting || recording is LocationRecordingState.Recording ||
            recording is LocationRecordingState.Stopping || (recording is LocationRecordingState.Failed && recording.storageUnknown)
    ParkedPanel(Modifier.testTag("location_recording_source")) {
        Text(stringResource(R.string.location_recording_target), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge)
        Text(displayed.source.goalTitle, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
        Text(displayed.source.courseTitle, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
        Text(displayed.source.taskTitle, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.location_recording_course_source),
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (busy) {
            Text(
                stringResource(R.string.location_recording_busy),
                Modifier.fillMaxWidth().testTag("location_recording_busy"),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            val permissionMessage =
                when (displayed.permission) {
                    LocationPermissionState.UNKNOWN -> R.string.location_recording_permission_unknown
                    LocationPermissionState.GRANTED -> R.string.location_recording_permission_granted
                    LocationPermissionState.DENIED -> R.string.location_recording_permission_denied
                    LocationPermissionState.FAILED -> R.string.location_recording_permission_failed
                }
            Text(
                stringResource(permissionMessage),
                Modifier.fillMaxWidth().testTag("location_recording_permission"),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (displayed.permission == LocationPermissionState.GRANTED) {
                val start: () -> Unit = {
                    if (present() && action.compareAndSet(true, false)) viewModel.start(displayed, present)
                }
                LocationRecordingAction(R.string.location_recording_start, "location_recording_start", start)
            } else if (displayed.permission == LocationPermissionState.DENIED) {
                val request: () -> Unit = {
                    if (present() && viewModel.canRequestPermission(displayed) && action.compareAndSet(true, false) &&
                        present() && viewModel.canRequestPermission(displayed)
                    ) {
                        onRequestPermission()
                    }
                }
                LocationRecordingAction(R.string.location_recording_permission_request, "location_recording_permission_request", request)
            } else {
                val refresh: () -> Unit = {
                    if (present() && viewModel.canRequestPermission(displayed) && action.compareAndSet(true, false) &&
                        present() && viewModel.canRequestPermission(displayed)
                    ) {
                        viewModel.refreshPermission()
                    }
                }
                LocationRecordingAction(R.string.location_recording_permission_refresh, "location_recording_permission_refresh", refresh)
            }
        }
    }
}

@Composable
private fun LocationRecordingNotice(
    message: Int,
    tag: String,
) {
    ParkedPanel(Modifier.testTag(tag)) {
        Text(stringResource(message), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LocationRecordingAction(
    label: Int,
    tag: String,
    action: () -> Unit,
) {
    ParkedButton(
        action,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                .testTag(tag)
                .frozenObservationClick(action),
    ) {
        Text(
            stringResource(label),
            Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

private fun locationRecordingFailureMessage(state: LocationRecordingState.Failed): Int {
    if (state.storageUnknown) return R.string.location_recording_end_unknown
    return when (state.reason) {
        LocationRecordingState.Reason.PERMISSION_DENIED -> R.string.location_recording_denied
        LocationRecordingState.Reason.PERMISSION_CHECK -> R.string.location_recording_permission_failed
        LocationRecordingState.Reason.UNSUPPORTED -> R.string.location_recording_unsupported
        LocationRecordingState.Reason.NO_UPDATE -> R.string.location_recording_no_update
        LocationRecordingState.Reason.SDK_ERROR -> R.string.location_recording_sdk_error
        LocationRecordingState.Reason.SOURCE_READ -> R.string.location_recording_read_failed
        LocationRecordingState.Reason.SOURCE_CHANGED,
        LocationRecordingState.Reason.SESSION_FINISHED,
        -> R.string.location_recording_source_changed
        LocationRecordingState.Reason.OPEN, LocationRecordingState.Reason.WRITE, LocationRecordingState.Reason.END,
        LocationRecordingState.Reason.RECOVERY,
        -> R.string.location_recording_storage_failed
        else -> R.string.location_recording_error
    }
}
