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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        private val actions = MutableStateFlow<SessionFinishAction?>(null)
        val finishActions: StateFlow<SessionFinishAction?> = actions.asStateFlow()
        private val receipts = MutableStateFlow<SessionFinishReceipt?>(null)
        val finishReceipt: StateFlow<SessionFinishReceipt?> = receipts.asStateFlow()
        private var finishSerial = 0L
        private var finishEpoch = 0L
        private var finishHostResumed = false
        private var acknowledgedFinish: Pair<PracticeSession, SessionFinishReceipt.Kind>? = null
        private var acceptedFinish: PendingFinish? = null
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
                            retireFinishLocked(clearAcknowledgement = true)
                        }
                        emit(SessionLoadState.Loading)
                    }.onCompletion { cause ->
                        if (cause == null) emit(SessionLoadState.Failed)
                    }.catch {
                        currentCoroutineContext().ensureActive()
                        emit(SessionLoadState.Failed)
                    }.onEach { load ->
                        synchronized(disclosureLock) {
                            if (recordsOwner === owner) {
                                receivedRecords = load
                                receiveFinishSourceLocked()
                            }
                        }
                    }.onCompletion {
                        synchronized(disclosureLock) {
                            if (recordsOwner === owner) {
                                recordsOwner = null
                                recordsJob = null
                                retireFinishLocked(clearAcknowledgement = true)
                            }
                        }
                    }
            }
        val uiState =
            combine(vehicle.state, restrictions.restrictions, preparation, records, operation) { v, r, p, s, o ->
                SessionUiState(allowsPracticeInteraction(v, r), p, s, o)
            }.onEach { state ->
                synchronized(disclosureLock) {
                    if (!state.allowsInteraction || !canCommit()) retireFinishLocked() else publishFinishPresentationLocked()
                }
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
                    if (readerJob === completing) {
                        readerJob = null
                        retireFinishLocked(clearAcknowledgement = true)
                    }
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
                    SessionFinishResult.SourceChanged -> SessionFeedback.SAVE_FAILED
                    SessionFinishResult.Missing -> SessionFeedback.MISSING
                    SessionFinishResult.Blocked -> SessionFeedback.BLOCKED
                    SessionFinishResult.InvalidTime -> SessionFeedback.INVALID_TIME
                }
            }
        }

        /** Lifecycle presentation ownership is separate from a durable finish acknowledgement. */
        fun setFinishHostResumed(resumed: Boolean) =
            synchronized(disclosureLock) {
                if (finishHostResumed == resumed) return@synchronized
                finishHostResumed = resumed
                retireFinishLocked()
                if (resumed) publishFinishPresentationLocked()
            }

        fun finish(
            action: SessionFinishAction,
            canKeepOpen: () -> Boolean,
        ): Boolean {
            val pending =
                synchronized(disclosureLock) {
                    if (actions.value !== action || !finishAccessLocked(canKeepOpen)) return@synchronized null
                    val current = currentFinishRecordsLocked() ?: return@synchronized null
                    if (actions.value !== action) return@synchronized null
                    if (current.singleOrNull { it.isActive } != action.source || !mutation.tryLock()) return@synchronized null
                    actions.value = null
                    receipts.value = null
                    acknowledgedFinish = null
                    PendingFinish(action, recordsOwner, readerJob, canKeepOpen).also {
                        acceptedFinish = it
                        operation.value = SessionOperationState.Saving
                    }
                } ?: return false
            val job =
                viewModelScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val requestedTime = clock.millis()
                        val result = sessions.finish(action.source, requestedTime) { canCommitFinish(pending) }
                        currentCoroutineContext().ensureActive()
                        synchronized(disclosureLock) {
                            if (!ownsFinishLocked(pending) || !finishAccessLocked(pending.canKeepOpen) ||
                                !ownsFinishLocked(pending)
                            ) {
                                return@synchronized
                            }
                            val source =
                                when (result) {
                                    is SessionFinishResult.Finished -> result.session
                                    is SessionFinishResult.AlreadyFinished -> result.session
                                    else -> null
                                }
                            val endedAt = source?.endedAtEpochMillis
                            if (source != null && endedAt != null &&
                                endedAt >= source.startedAtEpochMillis && source.startedAtEpochMillis > 0 &&
                                source.copy(endedAtEpochMillis = null) == action.source &&
                                (result !is SessionFinishResult.Finished || endedAt == requestedTime)
                            ) {
                                acknowledgedFinish = source to
                                    if (result is SessionFinishResult.Finished) {
                                        SessionFinishReceipt.Kind.FINISHED
                                    } else {
                                        SessionFinishReceipt.Kind.ALREADY_FINISHED
                                    }
                                operation.value =
                                    SessionOperationState.Feedback(
                                        if (result is SessionFinishResult.Finished) {
                                            SessionFeedback.FINISHED
                                        } else {
                                            SessionFeedback.ALREADY_FINISHED
                                        },
                                    )
                            } else {
                                operation.value =
                                    SessionOperationState.Feedback(
                                        when (result) {
                                            SessionFinishResult.Missing -> SessionFeedback.MISSING
                                            SessionFinishResult.Blocked -> SessionFeedback.BLOCKED
                                            SessionFinishResult.InvalidTime -> SessionFeedback.INVALID_TIME
                                            else -> SessionFeedback.SAVE_FAILED
                                        },
                                    )
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        synchronized(disclosureLock) {
                            if (acceptedFinish === pending) operation.value = SessionOperationState.Feedback(SessionFeedback.SAVE_FAILED)
                        }
                        throw cancelled
                    } catch (_: Exception) {
                        synchronized(disclosureLock) {
                            if (acceptedFinish === pending) operation.value = SessionOperationState.Feedback(SessionFeedback.SAVE_FAILED)
                        }
                    } finally {
                        synchronized(disclosureLock) {
                            if (acceptedFinish === pending) {
                                acceptedFinish = null
                                if (operation.value == SessionOperationState.Saving) {
                                    operation.value = SessionOperationState.Feedback(SessionFeedback.SAVE_FAILED)
                                }
                            }
                            mutation.unlock()
                            publishFinishPresentationLocked()
                        }
                    }
                }
            pending.job = job
            job.start()
            return true
        }

        fun canReviewFinished(receipt: SessionFinishReceipt): Boolean =
            synchronized(disclosureLock) {
                !mutation.isLocked && operation.value != SessionOperationState.Saving &&
                    receipts.value === receipt && finishAccessLocked { true } && receipts.value === receipt &&
                    currentFinishRecordsLocked()?.singleOrNull { it.sessionId == receipt.source.sessionId } == receipt.source
            }

        fun consumeFinishedReview(
            receipt: SessionFinishReceipt,
            canKeepOpen: () -> Boolean,
        ): PracticeSession? =
            synchronized(disclosureLock) {
                if (mutation.isLocked || operation.value == SessionOperationState.Saving ||
                    receipts.value !== receipt || !finishAccessLocked(canKeepOpen) || receipts.value !== receipt ||
                    currentFinishRecordsLocked()?.singleOrNull { it.sessionId == receipt.source.sessionId } != receipt.source
                ) {
                    return@synchronized null
                }
                receipts.value = null
                acknowledgedFinish = null
                receipt.source
            }

        private fun canCommitFinish(pending: PendingFinish): Boolean =
            synchronized(disclosureLock) {
                ownsFinishLocked(pending) && finishAccessLocked(pending.canKeepOpen) && ownsFinishLocked(pending) &&
                    currentFinishRecordsLocked()?.singleOrNull { it.isActive } == pending.action.source
            }

        private fun ownsFinishLocked(pending: PendingFinish): Boolean =
            acceptedFinish === pending && !pending.revoked && recordsOwner === pending.recordsOwner &&
                readerJob === pending.reader && pending.reader?.isActive == true && pending.job?.isActive == true

        private fun finishAccessLocked(host: () -> Boolean): Boolean {
            val allowed =
                try {
                    finishHostResumed && host() && canCommit()
                } catch (_: Exception) {
                    false
                }
            if (!allowed) retireFinishLocked()
            return allowed && finishHostResumed && readerJob?.isActive == true && recordsJob?.isActive == true
        }

        private fun currentFinishRecordsLocked(): List<PracticeSession>? =
            (receivedRecords as? SessionLoadState.Loaded)?.sessions?.takeIf {
                readerJob?.isActive == true && recordsJob?.isActive == true &&
                    it.map { row -> row.sessionId }.distinct().size == it.size && it.count { row -> row.isActive } <= 1
            }

        private fun receiveFinishSourceLocked() {
            val records = currentFinishRecordsLocked()
            val pending = acceptedFinish
            if (records == null) {
                retireFinishLocked(clearAcknowledgement = true)
                return
            }
            if (pending != null) {
                val actual = records.singleOrNull { it.sessionId == pending.action.source.sessionId }
                if (actual == null || actual.copy(endedAtEpochMillis = null) != pending.action.source) pending.revoked = true
            }
            val acknowledged = acknowledgedFinish
            if (acknowledged != null && records.singleOrNull { it.sessionId == acknowledged.first.sessionId } != acknowledged.first) {
                // A still-active row may precede delivery of the actual committed end receipt.
                val actual = records.singleOrNull { it.sessionId == acknowledged.first.sessionId }
                if (actual != acknowledged.first.copy(endedAtEpochMillis = null)) acknowledgedFinish = null
                receipts.value = null
            }
            publishFinishPresentationLocked()
        }

        private fun publishFinishPresentationLocked() {
            val before = finishEpoch
            if (!finishHostResumed || !canCommit() || before != finishEpoch || !finishHostResumed) return
            val records = currentFinishRecordsLocked() ?: return
            val active = records.singleOrNull { it.isActive }
            if (acceptedFinish == null && !mutation.isLocked && active != null) {
                if (actions.value?.source != active) actions.value = SessionFinishAction(active, ++finishSerial)
            } else {
                actions.value = null
            }
            val acknowledged = acknowledgedFinish
            if (acknowledged != null && records.singleOrNull { it.sessionId == acknowledged.first.sessionId } == acknowledged.first) {
                if (receipts.value?.source != acknowledged.first || receipts.value?.kind != acknowledged.second) {
                    receipts.value = SessionFinishReceipt(acknowledged.first, acknowledged.second, ++finishSerial)
                }
            } else {
                receipts.value = null
            }
        }

        private fun retireFinishLocked(clearAcknowledgement: Boolean = false) {
            finishEpoch++
            actions.value = null
            receipts.value = null
            acceptedFinish?.revoked = true
            if (clearAcknowledgement) acknowledgedFinish = null
        }

        private class PendingFinish(
            val action: SessionFinishAction,
            val recordsOwner: Any?,
            val reader: Job?,
            val canKeepOpen: () -> Boolean,
        ) {
            var revoked = false
            var job: Job? = null
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
            synchronized(disclosureLock) { retireFinishLocked(clearAcknowledgement = true) }
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
                    synchronized(disclosureLock) { publishFinishPresentationLocked() }
                }
            }
        }
    }
