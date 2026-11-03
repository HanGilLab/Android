package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.designsystem.ParkedButton
import io.github.hangillab.hangil.core.designsystem.ParkedDisclosure
import io.github.hangillab.hangil.core.designsystem.ParkedPage
import io.github.hangillab.hangil.core.designsystem.ParkedPanel
import io.github.hangillab.hangil.core.domain.LocationRecordingRunStatus
import io.github.hangillab.hangil.core.domain.LocationRecordingStopReason
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.PracticeSession
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun LocationHistoryRoute(
    expectedSource: PracticeSession,
    onClose: () -> Unit,
    viewModel: LocationHistoryViewModel = hiltViewModel(),
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var hostOwner by remember(lifecycle, expectedSource, viewModel) { mutableStateOf(AtomicBoolean(false)) }
    var resumed by remember(lifecycle, expectedSource, viewModel) { mutableStateOf(false) }
    DisposableEffect(lifecycle, expectedSource, viewModel) {
        fun stop() {
            hostOwner.set(false)
            resumed = false
        }

        fun start() {
            stop()
            hostOwner = AtomicBoolean(true)
            resumed = true
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
        if (lifecycle.currentState == Lifecycle.State.RESUMED) start()
        onDispose {
            stop()
            lifecycle.removeObserver(observer)
        }
    }
    // Each RESUME owns a new immutable receipt; an old callback never borrows the next host.
    val capturedHost = hostOwner
    val canKeepOpen = remember(capturedHost, lifecycle) { { capturedHost.get() && lifecycle.currentState == Lifecycle.State.RESUMED } }
    val records = remember(expectedSource, canKeepOpen, viewModel) { viewModel.observe(expectedSource, canKeepOpen) }
    val observed by records.collectAsStateWithLifecycle(
        LocationHistoryUiState.Loading(expectedSource, LocationHistoryUiState.Token(0, 0, 0)),
        minActiveState = Lifecycle.State.RESUMED,
    )
    val displayed = observed
    val visible =
        when {
            displayed.source != expectedSource || !resumed -> LocationHistoryUiState.Unavailable(expectedSource, displayed.token)
            displayed is LocationHistoryUiState.Ready &&
                !viewModel.canRead(
                    displayed.token,
                )
            -> LocationHistoryUiState.Locked(expectedSource, displayed.token)
            else -> displayed
        }
    LocationHistoryScreen(visible, onClose, viewModel)
}

@Composable
internal fun LocationHistoryScreen(
    state: LocationHistoryUiState,
    onClose: () -> Unit,
    viewModel: LocationHistoryViewModel,
) {
    ParkedPage {
        TextButton(
            onClick = onClose,
            modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight).testTag("location_history_back"),
        ) {
            Text(
                stringResource(R.string.location_history_back),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Column(Modifier.fillMaxWidth().testTag("location_history_screen"), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.xl)) {
            Text(
                stringResource(R.string.location_history_title),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.headlineLarge,
            )
            when (state) {
                is LocationHistoryUiState.Loading -> LocationHistoryNotice(R.string.location_history_loading, "location_history_loading")
                is LocationHistoryUiState.Locked -> LocationHistoryNotice(R.string.location_history_locked, "location_history_locked")
                is LocationHistoryUiState.Unavailable ->
                    LocationHistoryNotice(
                        R.string.location_history_unavailable,
                        "location_history_unavailable",
                    )
                is LocationHistoryUiState.Empty -> LocationHistoryNotice(R.string.location_history_empty, "location_history_empty")
                is LocationHistoryUiState.Failed -> {
                    LocationHistoryNotice(R.string.location_history_failed, "location_history_failed")
                    val retry: () -> Unit = { viewModel.retry(state.token) }
                    ParkedButton(
                        retry,
                        modifier = Modifier.fillMaxWidth().testTag("location_history_retry").frozenLocationHistoryClick(retry),
                    ) {
                        Text(
                            stringResource(R.string.location_history_retry),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
                is LocationHistoryUiState.Ready ->
                    if (viewModel.canRead(state.token)) {
                        key(state.token) { LocationHistoryReady(state, viewModel) }
                    }
            }
        }
    }
}

@Composable
private fun LocationHistoryNotice(
    text: Int,
    tag: String,
) {
    ParkedPanel(Modifier.testTag(tag)) {
        Text(stringResource(text), modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LocationHistoryReady(
    state: LocationHistoryUiState.Ready,
    viewModel: LocationHistoryViewModel,
) {
    val run = state.run
    val event = state.event
    ParkedPanel(Modifier.testTag("location_history_content")) {
        Text(state.source.courseTitle, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge)
        Text(state.source.taskTitle, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.location_history_source),
            Modifier.fillMaxWidth().testTag("location_history_source"),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.location_history_uncertain),
            Modifier.fillMaxWidth().testTag("location_history_source_warning"),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (state.hasInterrupted) {
            Text(
                stringResource(R.string.location_history_interrupted_warning),
                Modifier.fillMaxWidth().testTag("location_history_interrupted"),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
    ParkedPanel {
        Text(
            stringResource(R.string.location_history_run_position, state.runIndex + 1, state.snapshot.runs.size),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(
                when (run.status) {
                    LocationRecordingRunStatus.OPEN -> R.string.location_history_open
                    LocationRecordingRunStatus.STOPPED -> R.string.location_history_stopped
                    LocationRecordingRunStatus.FAILED -> R.string.location_history_run_failed
                    LocationRecordingRunStatus.INTERRUPTED -> R.string.location_history_run_interrupted
                },
            ),
            Modifier.fillMaxWidth().testTag("location_history_run_state"),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.location_history_start_time, locationHistoryTime(run.startedAtEpochMillis)),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge,
        )
        val endedAt = run.endedAtEpochMillis
        if (endedAt ==
            null
        ) {
            Text(
                stringResource(R.string.location_history_end_unknown),
                Modifier.fillMaxWidth().testTag("location_history_unknown_end"),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Text(
                stringResource(R.string.location_history_end_time, locationHistoryTime(endedAt)),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (run.stopReason ==
            LocationRecordingStopReason.LIMIT_REACHED
        ) {
            Text(
                stringResource(R.string.location_history_limit),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Text(
            stringResource(R.string.location_history_marker_meaning),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(
                R.string.location_history_counts,
                state.events.count {
                    it.reading is LocationUpdateReading.Sample
                },
                state.events.size,
            ),
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyLarge,
        )
        val previous: () -> Unit = { viewModel.previousRun(state.token, run.runId) }
        val next: () -> Unit = { viewModel.nextRun(state.token, run.runId) }
        ParkedButton(
            previous,
            modifier = Modifier.fillMaxWidth().testTag("location_history_run_previous").frozenLocationHistoryClick(previous),
            enabled = state.canPreviousRun,
            prominent = false,
        ) {
            Text(
                stringResource(R.string.location_history_run_previous),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        ParkedButton(
            next,
            modifier = Modifier.fillMaxWidth().testTag("location_history_run_next").frozenLocationHistoryClick(next),
            enabled = state.canNextRun,
            prominent = false,
        ) {
            Text(
                stringResource(R.string.location_history_run_next),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
    ParkedPanel {
        if (event == null) {
            Text(
                stringResource(R.string.location_history_no_events),
                Modifier.fillMaxWidth().testTag("location_history_no_events"),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Text(
                stringResource(R.string.location_history_event_position, state.eventIndex + 1, state.events.size),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(
                    when (event.reading) {
                        is LocationUpdateReading.Sample -> R.string.location_history_sample
                        is LocationUpdateReading.Empty -> R.string.location_history_callback_empty
                        is LocationUpdateReading.PermissionDenied -> R.string.location_history_permission_denied
                        is LocationUpdateReading.Unsupported -> R.string.location_history_unsupported
                        is LocationUpdateReading.NoUpdate -> R.string.location_history_no_update
                        is LocationUpdateReading.Error -> R.string.location_history_error
                    },
                ),
                Modifier.fillMaxWidth().testTag("location_history_event_state"),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.location_history_record_time, locationHistoryTime(event.observedAtEpochMillis)),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
            )
            val sample = (event.reading as? LocationUpdateReading.Sample)?.sample
            if (sample != null) {
                ParkedDisclosure(stringResource(R.string.location_history_raw_details), {
                    viewModel.canRead(state.token)
                }, Modifier.testTag("location_history_raw_details")) {
                    Column(Modifier.testTag("location_history_raw_content"), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
                        Text(
                            stringResource(R.string.location_history_raw_warning),
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(R.string.location_history_latitude, sample.latitude.toString()),
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(R.string.location_history_longitude, sample.longitude.toString()),
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(R.string.location_history_altitude, sample.altitudeRaw.toString()),
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(R.string.location_history_heading, sample.headingRaw.toString()),
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(R.string.location_history_confidence, sample.confidenceRaw.toString()),
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(R.string.location_history_sdk_time, sample.sdkTimestampMillis.toString()),
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            } else {
                Text(
                    stringResource(R.string.location_history_no_point),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            val previous: () -> Unit = { viewModel.previousEvent(state.token, run.runId, event.id) }
            val next: () -> Unit = { viewModel.nextEvent(state.token, run.runId, event.id) }
            ParkedButton(
                previous,
                modifier = Modifier.fillMaxWidth().testTag("location_history_event_previous").frozenLocationHistoryClick(previous),
                enabled = state.canPreviousEvent,
                prominent = false,
            ) {
                Text(
                    stringResource(R.string.location_history_event_previous),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            ParkedButton(
                next,
                modifier = Modifier.fillMaxWidth().testTag("location_history_event_next").frozenLocationHistoryClick(next),
                enabled = state.canNextEvent,
                prominent = false,
            ) {
                Text(
                    stringResource(R.string.location_history_event_next),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
    }
}

@Composable
private fun locationHistoryTime(epochMillis: Long): String {
    val fallback = stringResource(R.string.location_history_time_unknown)
    return runCatching {
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss"))
    }.getOrDefault(fallback)
}

private fun Modifier.frozenLocationHistoryClick(action: () -> Unit): Modifier =
    semantics {
        onClick {
            action()
            true
        }
    }
