package io.github.hangillab.hangil.feature.home

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleObservationRepository
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** Cold detail queries are owned by the expanded, lifecycle-aware route, not a permanent VM job. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RecentVehicleObservationViewModel
    @Inject
    constructor(
        private val vehicle: VehicleGateway,
        private val restrictions: DrivingRestrictionsProvider,
        private val sessions: PracticeSessionRepository,
        private val observations: VehicleObservationRepository,
    ) : ViewModel() {
        private val retryEpoch = MutableStateFlow(0L)

        /** Keep one remembered flow and one collector per visible detail route. */
        fun observe(sessionId: String): Flow<RecentVehicleObservationUiState> =
            retryEpoch.flatMapLatest { observeAttempt(sessionId) }.buffer(0)

        fun canRead(): Boolean = allowsPracticeInteraction(vehicle.state.value, restrictions.restrictions.value)

        fun retry(): Boolean {
            if (!canRead()) return false
            retryEpoch.update { it + 1 }
            return true
        }

        private fun observeAttempt(sessionId: String): Flow<RecentVehicleObservationUiState> =
            combine(vehicle.state, restrictions.restrictions) { currentVehicle, currentRestrictions ->
                if (allowsPracticeInteraction(currentVehicle, currentRestrictions)) {
                    RecentVehicleObservationUiState.Loading(sessionId)
                } else {
                    RecentVehicleObservationUiState.Locked(sessionId)
                }
            }.distinctUntilChanged()
                .onCompletion { cause ->
                    if (cause == null) {
                        emit(failed(sessionId, RecentVehicleObservationUiState.FailureReason.ACCESS_STREAM_COMPLETED))
                    }
                }.catch {
                    currentCoroutineContext().ensureActive()
                    emit(failed(sessionId, RecentVehicleObservationUiState.FailureReason.ACCESS_READ))
                }.flatMapLatest { access ->
                    if (access is RecentVehicleObservationUiState.Loading) observeSession(sessionId) else flowOf(access)
                }.buffer(0)

        private fun observeSession(sessionId: String): Flow<RecentVehicleObservationUiState> =
            flow { emitAll(sessions.observeSessions()) }
                .map<_, RecentVehicleObservationUiState> { records ->
                    if (records.firstOrNull { !it.isActive }?.sessionId == sessionId) {
                        RecentVehicleObservationUiState.Loading(sessionId)
                    } else {
                        RecentVehicleObservationUiState.SessionChanged(sessionId)
                    }
                }.distinctUntilChanged()
                .onCompletion { cause ->
                    if (cause == null) {
                        emit(failed(sessionId, RecentVehicleObservationUiState.FailureReason.SESSION_STREAM_COMPLETED))
                    }
                }.catch {
                    currentCoroutineContext().ensureActive()
                    emit(failed(sessionId, RecentVehicleObservationUiState.FailureReason.SESSION_READ))
                }.flatMapLatest { target ->
                    if (target is RecentVehicleObservationUiState.Loading) observeRows(sessionId) else flowOf(target)
                }.onStart { emit(RecentVehicleObservationUiState.Loading(sessionId)) }
                .buffer(0)

        private fun observeRows(sessionId: String): Flow<RecentVehicleObservationUiState> =
            flow { emitAll(observations.observe(sessionId)) }
                .map<_, RecentVehicleObservationUiState> { rows ->
                    validateRows(sessionId, rows)
                    if (rows.isEmpty()) {
                        RecentVehicleObservationUiState.Empty(sessionId)
                    } else {
                        RecentVehicleObservationUiState.Ready(sessionId, rows.toList())
                    }
                }.onStart { emit(RecentVehicleObservationUiState.Loading(sessionId)) }
                .onCompletion { cause ->
                    if (cause == null) {
                        emit(failed(sessionId, RecentVehicleObservationUiState.FailureReason.OBSERVATION_STREAM_COMPLETED))
                    }
                }.catch { failure ->
                    currentCoroutineContext().ensureActive()
                    emit(
                        failed(
                            sessionId,
                            if (failure is InvalidObservations) {
                                RecentVehicleObservationUiState.FailureReason.INVALID_OBSERVATIONS
                            } else {
                                RecentVehicleObservationUiState.FailureReason.OBSERVATION_READ
                            },
                        ),
                    )
                }

        private fun validateRows(
            sessionId: String,
            rows: List<SessionVehicleObservation>,
        ) {
            var previousId = 0L
            for (row in rows) {
                if (row.sessionId != sessionId || row.id <= previousId || row.observation.observedAtEpochMillis <= 0) {
                    throw InvalidObservations()
                }
                previousId = row.id
            }
        }

        private fun failed(
            sessionId: String,
            reason: RecentVehicleObservationUiState.FailureReason,
        ) = RecentVehicleObservationUiState.Failed(sessionId, reason)

        private class InvalidObservations : IllegalStateException()
    }
