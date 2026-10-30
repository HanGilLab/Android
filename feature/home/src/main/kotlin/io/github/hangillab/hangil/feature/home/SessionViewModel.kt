package io.github.hangillab.hangil.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
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

        // Source receipts are checked before combine can publish a newer UI snapshot.
        private val disclosureLock = Any()
        private var readerJob: Job? = null
        private var preparationOwner: Any? = null
        private var preparationJob: Job? = null
        private var receivedPreparation: PreparationLoadState = PreparationLoadState.Loading
        private var recordsOwner: Any? = null
        private var recordsJob: Job? = null
        private var receivedRecords: SessionLoadState = SessionLoadState.Loading
        private val preparation =
            retry.flatMapLatest {
                val owner = Any()
                flow { emitAll(preparationRepository.observeSelection()) }
                    .map<_, PreparationLoadState> { PreparationLoadState.Loaded(catalog.resolve(it)) }
                    .onStart {
                        val starting = currentCoroutineContext()[Job]
                        synchronized(disclosureLock) {
                            preparationOwner = owner
                            preparationJob = starting
                            receivedPreparation = PreparationLoadState.Loading
                        }
                        emit(PreparationLoadState.Loading)
                    }.onCompletion { cause ->
                        if (cause == null) emit(PreparationLoadState.Failed)
                    }.catch {
                        currentCoroutineContext().ensureActive()
                        emit(PreparationLoadState.Failed)
                    }.onEach { load ->
                        synchronized(disclosureLock) {
                            if (preparationOwner === owner) receivedPreparation = load
                        }
                    }.onCompletion {
                        synchronized(disclosureLock) {
                            if (preparationOwner === owner) {
                                preparationOwner = null
                                preparationJob = null
                            }
                        }
                    }
            }
        private val records =
            retry.flatMapLatest {
                val owner = Any()
                flow { emitAll(sessions.observeSessions()) }
                    .map<_, SessionLoadState> { SessionLoadState.Loaded(it.toList()) }
                    .onStart {
                        val starting = currentCoroutineContext()[Job]
                        synchronized(disclosureLock) {
                            recordsOwner = owner
                            recordsJob = starting
                            receivedRecords = SessionLoadState.Loading
                        }
                        emit(SessionLoadState.Loading)
                    }.onCompletion { cause ->
                        if (cause == null) emit(SessionLoadState.Failed)
                    }.catch {
                        currentCoroutineContext().ensureActive()
                        emit(SessionLoadState.Failed)
                    }.onEach { load ->
                        synchronized(disclosureLock) {
                            if (recordsOwner === owner) receivedRecords = load
                        }
                    }.onCompletion {
                        synchronized(disclosureLock) {
                            if (recordsOwner === owner) {
                                recordsOwner = null
                                recordsJob = null
                            }
                        }
                    }
            }
        val uiState =
            combine(vehicle.state, restrictions.restrictions, preparation, records, operation) { v, r, p, s, o ->
                SessionUiState(allowsPracticeInteraction(v, r), p, s, o)
            }.onStart {
                val starting = currentCoroutineContext()[Job]
                synchronized(disclosureLock) {
                    readerJob = starting
                    receivedPreparation = PreparationLoadState.Loading
                    receivedRecords = SessionLoadState.Loading
                }
            }.onCompletion {
                val completing = currentCoroutineContext()[Job]
                synchronized(disclosureLock) {
                    if (readerJob === completing) readerJob = null
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), SessionUiState())

        internal fun canExpandGuide(
            expectedStep: HomeJourneyStep,
            expectedActive: PracticeSession?,
        ): Boolean =
            synchronized(disclosureLock) {
                if (readerJob?.isActive != true || !canCommit()) return@synchronized false
                val currentPreparation =
                    if (receivedPreparation is PreparationLoadState.Loaded && preparationJob?.isActive != true) {
                        PreparationLoadState.Failed
                    } else {
                        receivedPreparation
                    }
                val currentRecords =
                    if (receivedRecords is SessionLoadState.Loaded && recordsJob?.isActive != true) {
                        SessionLoadState.Failed
                    } else {
                        receivedRecords
                    }
                val current = SessionUiState(true, currentPreparation, currentRecords, operation.value)
                current.nextStep == expectedStep && current.active == expectedActive
            }

        internal fun canExpandRecent(expected: PracticeSession): Boolean =
            synchronized(disclosureLock) {
                val current = (receivedRecords as? SessionLoadState.Loaded)?.sessions
                readerJob?.isActive == true && recordsJob?.isActive == true && canCommit() &&
                    current?.firstOrNull { !it.isActive } == expected
            }

        fun canPrepareNext(expectedSessionId: String): Boolean {
            if (!canCommit() || mutation.isLocked || operation.value == SessionOperationState.Saving) return false
            val records = (uiState.value.records as? SessionLoadState.Loaded)?.sessions ?: return false
            return records.none { it.isActive } && records.firstOrNull { !it.isActive }?.sessionId == expectedSessionId
        }

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
