package io.github.hangillab.hangil.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.LocationRecordingController
import io.github.hangillab.hangil.core.domain.LocationRecordingState
import io.github.hangillab.hangil.core.domain.LocationUpdateGateway
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** UI permission/source receipts are separate from the controller's passive recording lifetime. */
@HiltViewModel
class LocationRecordingViewModel
    @Inject
    constructor(
        private val controller: LocationRecordingController,
        private val repository: PracticeSessionRepository,
        private val gateway: LocationUpdateGateway,
        private val vehicle: VehicleGateway,
        private val restrictions: DrivingRestrictionsProvider,
    ) : ViewModel() {
        private val lock = Any()
        private var revision = 0L
        private var sourceRevision = 0L
        private var readerOwner: Any? = null
        private var readerJob: Job? = null
        private var received: Source = Source.Loading
        private var permission = LocationPermissionState.UNKNOWN
        private var issued: StartAction? = null
        private val trigger = MutableStateFlow(0L)

        val recordingState: StateFlow<LocationRecordingState> = controller.state

        private val source =
            flow {
                val owner = Any()
                val job = currentCoroutineContext()[Job]
                synchronized(lock) {
                    readerOwner = owner
                    readerJob = job
                    received = Source.Loading
                    sourceRevision++
                    revision++
                    issued = null
                }
                emit(Unit)
                try {
                    repository.observeSessions().collect { records ->
                        val loaded = checkedSource(records.toList())
                        synchronized(lock) {
                            if (readerOwner === owner) {
                                val unchangedActive = loaded is Source.Active && (received as? Source.Active)?.session == loaded.session
                                if (!unchangedActive) {
                                    sourceRevision++
                                    issued = null
                                }
                                received = loaded
                                // Every receipt retires the displayed UI; the same active source keeps its accepted start.
                                revision++
                            }
                        }
                        emit(Unit)
                    }
                    receiveFailure(owner)
                    emit(Unit)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    receiveFailure(owner)
                    emit(Unit)
                } finally {
                    synchronized(lock) {
                        if (readerOwner === owner) {
                            readerOwner = null
                            readerJob = null
                            received = Source.Failed
                            sourceRevision++
                            revision++
                            issued = null
                        }
                    }
                }
            }

        val uiState: StateFlow<LocationRecordingUiState> =
            combine(
                source,
                vehicle.state.onEach { value ->
                    receiveGate(!allowsPracticeInteraction(value, restrictions.restrictions.value))
                },
                restrictions.restrictions.onEach { value ->
                    receiveGate(!allowsPracticeInteraction(vehicle.state.value, value))
                },
                controller.state.onEach { state ->
                    synchronized(lock) {
                        revision++
                        if (state !is LocationRecordingState.Starting && state !is LocationRecordingState.Recording &&
                            state !is LocationRecordingState.Stopping
                        ) {
                            issued = null
                        }
                    }
                },
                trigger,
            ) { _, _, _, _, _ -> render() }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), LocationRecordingUiState.Loading)

        /** ActivityResult booleans are never authority; read the framework again after returning. */
        fun refreshPermission() {
            val actual = readPermission()
            synchronized(lock) {
                permission = actual
                revision++
                trigger.value++
            }
        }

        fun canRequestPermission(expected: LocationRecordingUiState.Ready): Boolean = synchronized(lock) { currentReadyLocked(expected) }

        fun canStart(expected: LocationRecordingUiState.Ready): Boolean {
            if (!synchronized(lock) { currentReadyLocked(expected) }) return false
            val actual = readPermission()
            synchronized(lock) {
                if (permission != actual) {
                    permission = actual
                    revision++
                    trigger.value++
                }
                return actual == LocationPermissionState.GRANTED && currentReadyLocked(expected)
            }
        }

        fun start(
            expected: LocationRecordingUiState.Ready,
            canStart: () -> Boolean,
        ): Boolean {
            if (!this.canStart(expected) || !hostAllowed(canStart)) return false
            val action =
                synchronized(lock) {
                    if (!currentReadyLocked(expected)) return@synchronized null
                    val currentOwner = readerOwner ?: return@synchronized null
                    val currentJob = readerJob ?: return@synchronized null
                    StartAction(expected.source, sourceRevision, currentOwner, currentJob, canStart).also {
                        issued = it
                        // A consumed UI action never becomes fresh again after Starting -> Idle ABA.
                        revision++
                        trigger.value++
                    }
                } ?: return false
            val accepted =
                try {
                    controller.start(action.source) { startAllowed(action) }
                } catch (_: Exception) {
                    false
                }
            if (!accepted) synchronized(lock) { if (issued === action) issued = null }
            return accepted
        }

        /** Privacy cleanup is independent of current source, location permission and detailed UI gates. */
        fun stop(expectedRunId: String): Boolean {
            val stopped = controller.stop(expectedRunId)
            if (stopped) {
                synchronized(lock) {
                    issued = null
                    revision++
                    trigger.value++
                }
            }
            return stopped
        }

        private fun readPermission(): LocationPermissionState =
            try {
                if (gateway.hasPermission()) LocationPermissionState.GRANTED else LocationPermissionState.DENIED
            } catch (_: Exception) {
                LocationPermissionState.FAILED
            }

        private fun render(): LocationRecordingUiState =
            synchronized(lock) {
                val recording = controller.state.value
                if (!liveGate()) return@synchronized LocationRecordingUiState.Locked(recording)
                when (val current = received) {
                    Source.Loading -> LocationRecordingUiState.Loading
                    Source.Failed -> LocationRecordingUiState.Failed(recording)
                    Source.None ->
                        if (readerJob?.isActive == true) {
                            LocationRecordingUiState.NoActive(recording)
                        } else {
                            LocationRecordingUiState.Failed(recording)
                        }
                    is Source.Active ->
                        if (readerJob?.isActive == true) {
                            LocationRecordingUiState.Ready(current.session, revision, permission, recording)
                        } else {
                            LocationRecordingUiState.Failed(recording)
                        }
                }
            }

        private fun currentReadyLocked(expected: LocationRecordingUiState.Ready): Boolean =
            uiState.value === expected && revision == expected.revision && readerOwner != null && readerJob?.isActive == true &&
                (received as? Source.Active)?.session == expected.source && permission == expected.permission &&
                controller.state.value == expected.recording && liveGate()

        private fun startAllowed(action: StartAction): Boolean {
            if (!synchronized(lock) { keepStartLocked(action) }) return false
            if (!hostAllowed(action.canStart)) {
                synchronized(lock) { if (issued === action) issued = null }
                return false
            }
            return synchronized(lock) { keepStartLocked(action) }
        }

        private fun keepStartLocked(action: StartAction): Boolean {
            val current = currentStartLocked(action)
            if (!current && issued === action) issued = null
            return current
        }

        private fun currentStartLocked(action: StartAction): Boolean =
            issued === action && readerOwner === action.owner && readerJob === action.job && action.job.isActive &&
                sourceRevision == action.sourceRevision && (received as? Source.Active)?.session == action.source && liveGate()

        private fun hostAllowed(canStart: () -> Boolean): Boolean =
            try {
                canStart()
            } catch (_: Exception) {
                false
            }

        private fun liveGate(): Boolean = allowsPracticeInteraction(vehicle.state.value, restrictions.restrictions.value)

        private fun receiveGate(receivedDenial: Boolean) {
            synchronized(lock) {
                revision++
                if (receivedDenial || !liveGate()) issued = null
            }
        }

        private fun receiveFailure(owner: Any) {
            synchronized(lock) {
                if (readerOwner === owner) {
                    received = Source.Failed
                    sourceRevision++
                    revision++
                    issued = null
                }
            }
        }

        private fun checkedSource(records: List<PracticeSession>): Source {
            if (records.map { it.sessionId }.distinct().size != records.size || records.any { !validSession(it) }) return Source.Failed
            val active = records.filter { it.isActive }
            return when (active.size) {
                0 -> Source.None
                1 -> Source.Active(active.single())
                else -> Source.Failed
            }
        }

        private fun validSession(session: PracticeSession): Boolean {
            val ended = session.endedAtEpochMillis
            return session.sessionId.isNotBlank() && session.startedAtEpochMillis > 0 && (ended == null || ended > 0) &&
                session.goalTitle.isNotBlank() && session.courseTitle.isNotBlank() && session.taskTitle.isNotBlank() &&
                session.selection.goalId.isNotBlank() && session.selection.courseId.isNotBlank() &&
                session.selection.taskId.isNotBlank() && session.selection.courseVersion > 0 && session.selection.briefingVersion > 0
        }

        private sealed interface Source {
            data object Loading : Source

            data object Failed : Source

            data object None : Source

            data class Active(
                val session: PracticeSession,
            ) : Source
        }

        private class StartAction(
            val source: PracticeSession,
            val sourceRevision: Long,
            val owner: Any,
            val job: Job,
            val canStart: () -> Boolean,
        )
    }
