package io.github.hangillab.hangil.feature.home

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleObservationRecorder
import io.github.hangillab.hangil.core.domain.VehicleObservationRecordingResult
import io.github.hangillab.hangil.core.domain.VehicleObservationRepository
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionRecordingViewModel
    @Inject
    constructor(
        private val vehicleGateway: VehicleGateway,
        private val sessionRepository: PracticeSessionRepository,
        observationRepository: VehicleObservationRepository,
        private val restrictionsProvider: DrivingRestrictionsProvider,
        clock: Clock,
    ) : ViewModel() {
        private val recorder = VehicleObservationRecorder(vehicleGateway, observationRepository, clock::millis)
        private val host = Mutex()
        private val retries = MutableStateFlow(0L)
        private val currentState = MutableStateFlow<VehicleRecordingState>(VehicleRecordingState.Paused)
        val state = currentState.asStateFlow()

        /**
         * Called by the Activity's STARTED lifecycle, independently of screens and UI subscribers.
         * The caller owns cancellation on STOP. Overlapping configuration hosts are serialized,
         * so a new host waits for the previous recorder and its pending append to be cancelled.
         */
        suspend fun recordWhileStarted() =
            host.withLock {
                try {
                    retries.collectLatest {
                        currentState.value = VehicleRecordingState.Loading
                        try {
                            sessionRepository
                                .observeSessions()
                                .onCompletion { cause ->
                                    // collectLatest otherwise waits for its infinite recorder child
                                    // after a finite upstream completes and cannot report lost monitoring.
                                    if (cause == null) error("Session observation completed")
                                }.map { sessions ->
                                    val active = sessions.filter { it.isActive }
                                    check(active.size <= 1)
                                    active.singleOrNull()?.sessionId
                                }.distinctUntilChanged()
                                .collectLatest { sessionId ->
                                    if (sessionId == null) {
                                        currentState.value = VehicleRecordingState.Idle
                                    } else {
                                        recordSession(sessionId)
                                    }
                                }
                            // A completed flow cannot notify us of later session changes.
                            currentState.value = VehicleRecordingState.Failed(VehicleRecordingState.Reason.SESSION_READ)
                        } catch (cancelled: CancellationException) {
                            if (currentCoroutineContext().isActive) {
                                // An independently cancelled read is not lifecycle cancellation.
                                currentState.value = VehicleRecordingState.Failed(VehicleRecordingState.Reason.SESSION_READ)
                            }
                            throw cancelled
                        } catch (_: Exception) {
                            currentState.value = VehicleRecordingState.Failed(VehicleRecordingState.Reason.SESSION_READ)
                        }
                        // No SDK update or hot session stream can turn a failed read into a retry.
                        awaitCancellation()
                    }
                } finally {
                    currentState.value = VehicleRecordingState.Paused
                }
            }

        /** User retries are detailed interactions and must recheck both current live inputs. */
        fun retry(): Boolean {
            if (currentState.value !is VehicleRecordingState.Failed ||
                !allowsPracticeInteraction(vehicleGateway.state.value, restrictionsProvider.restrictions.value)
            ) {
                return false
            }
            retries.value += 1
            return true
        }

        private suspend fun recordSession(sessionId: String) {
            currentState.value = VehicleRecordingState.Recording
            val result =
                try {
                    recorder.record(sessionId)
                } catch (cancelled: CancellationException) {
                    if (currentCoroutineContext().isActive) {
                        // A local timeout or standalone cancellation can end only this child.
                        // Keep it visible as failed while preserving the original cancellation.
                        currentState.value = VehicleRecordingState.Failed(VehicleRecordingState.Reason.OBSERVATION)
                    }
                    throw cancelled
                } catch (_: Exception) {
                    currentState.value = VehicleRecordingState.Failed(VehicleRecordingState.Reason.OBSERVATION)
                    return
                }
            val reason =
                when (result) {
                    VehicleObservationRecordingResult.AlreadyRunning -> VehicleRecordingState.Reason.ALREADY_RUNNING
                    is VehicleObservationRecordingResult.MissingSession -> VehicleRecordingState.Reason.SESSION_MISSING
                    is VehicleObservationRecordingResult.FinishedSession -> VehicleRecordingState.Reason.SESSION_FINISHED
                    is VehicleObservationRecordingResult.InvalidTime -> VehicleRecordingState.Reason.INVALID_TIME
                    is VehicleObservationRecordingResult.WriteFailed -> VehicleRecordingState.Reason.WRITE
                }
            currentState.value = VehicleRecordingState.Failed(reason)
        }
    }
