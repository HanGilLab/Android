package io.github.hangillab.hangil.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.time.Clock
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionViewModel
    @Inject
    constructor(
        private val vehicle: VehicleGateway,
        private val restrictions: DrivingRestrictionsProvider,
        preparationRepository: PracticeRepository,
        catalog: PracticeCatalog,
        private val sessions: PracticeSessionRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val retry = MutableStateFlow(0)
        private val operation = MutableStateFlow<SessionOperationState>(SessionOperationState.Idle)
        private val mutation = Mutex()
        private val preparation =
            retry.flatMapLatest {
                preparationRepository
                    .observeSelection()
                    .map<_, PreparationLoadState> { PreparationLoadState.Loaded(catalog.resolve(it)) }
                    .onStart { emit(PreparationLoadState.Loading) }
                    .catch { emit(PreparationLoadState.Failed) }
            }
        private val records =
            retry.flatMapLatest {
                sessions
                    .observeSessions()
                    .map<_, SessionLoadState> { SessionLoadState.Loaded(it) }
                    .onStart { emit(SessionLoadState.Loading) }
                    .catch { emit(SessionLoadState.Failed) }
            }
        val uiState =
            combine(vehicle.state, restrictions.restrictions, preparation, records, operation) { v, r, p, s, o ->
                SessionUiState(allowsPracticeInteraction(v, r), p, s, o)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), SessionUiState())

        fun start() {
            if (!canCommit() || !uiState.value.canStart) return
            val practice = uiState.value.ready ?: return
            mutate {
                when (sessions.start(practice, clock.millis(), ::canCommit)) {
                    is SessionStartResult.Started -> SessionFeedback.STARTED
                    is SessionStartResult.AlreadyActive -> SessionFeedback.ALREADY_ACTIVE
                    SessionStartResult.PreparationChanged -> SessionFeedback.PREPARATION_CHANGED
                    SessionStartResult.Blocked -> SessionFeedback.BLOCKED
                    SessionStartResult.InvalidTime -> SessionFeedback.INVALID_TIME
                }
            }
        }

        fun finish(sessionId: String) {
            if (!canCommit() || uiState.value.records !is SessionLoadState.Loaded) return
            mutate {
                when (sessions.finish(sessionId, clock.millis(), ::canCommit)) {
                    is SessionFinishResult.Finished -> SessionFeedback.FINISHED
                    is SessionFinishResult.AlreadyFinished -> SessionFeedback.ALREADY_FINISHED
                    SessionFinishResult.Missing -> SessionFeedback.MISSING
                    SessionFinishResult.Blocked -> SessionFeedback.BLOCKED
                    SessionFinishResult.InvalidTime -> SessionFeedback.INVALID_TIME
                }
            }
        }

        fun retryLoading() {
            if (canCommit() && !mutation.isLocked) {
                operation.value = SessionOperationState.Idle
                retry.value += 1
            }
        }

        private fun canCommit() = allowsPracticeInteraction(vehicle.state.value, restrictions.restrictions.value)

        private fun mutate(action: suspend () -> SessionFeedback) {
            if (!mutation.tryLock()) return
            operation.value = SessionOperationState.Saving
            viewModelScope.launch {
                try {
                    operation.value = SessionOperationState.Feedback(action())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    operation.value = SessionOperationState.Feedback(SessionFeedback.SAVE_FAILED)
                } finally {
                    mutation.unlock()
                }
            }
        }
    }
