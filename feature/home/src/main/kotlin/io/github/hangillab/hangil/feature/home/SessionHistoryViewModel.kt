package io.github.hangillab.hangil.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.CourseCompletionRepository
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.HistorySessionMemoWriter
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.RecordingRunRepository
import io.github.hangillab.hangil.core.domain.SessionCourseSummary
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionDeletionRepository
import io.github.hangillab.hangil.core.domain.SessionDeletionResult
import io.github.hangillab.hangil.core.domain.SessionDeletionTarget
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleObservationRepository
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import io.github.hangillab.hangil.feature.home.SessionHistoryObservationUiState.Token
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import javax.inject.Inject

/** Queries exist only while visible. Actions use the received source state, never a delayed UI copy. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionHistoryViewModel
    @Inject
    constructor(
        private val vehicle: VehicleGateway,
        private val restrictions: DrivingRestrictionsProvider,
        private val sessions: PracticeSessionRepository,
        private val deletions: SessionDeletionRepository,
        observations: VehicleObservationRepository,
        memos: SessionMemoRepository,
        recordingRuns: RecordingRunRepository,
        courseCompletions: CourseCompletionRepository,
        historyMemoWriter: HistorySessionMemoWriter,
    ) : ViewModel() {
        private val lock = Any()
        private val storedObservations = SessionHistoryObservationReader(lock, observations, ::canRead)
        private val storedMemos = SessionHistoryMemoReader(lock, memos, historyMemoWriter, ::canRead)
        private val storedRecordingRuns = SessionHistoryRecordingRunReader(lock, recordingRuns, ::canRead)
        private val courseCompletion = SessionHistoryCourseCompletionReader(lock, courseCompletions, ::canRead)
        private val deletionDetailReads = MutableStateFlow(0L)
        private var receivedHasActiveSession: Boolean? = null
        private val published = MutableStateFlow<SessionHistoryUiState>(SessionHistoryUiState.Loading)
        private val requests = MutableStateFlow(0L)
        private var reader: Reader? = null
        private var epoch = 0L
        private var generation = 0L
        private var selectedId: String? = null
        private var selectionLost = false
        private var ended: List<PracticeSession>? = null
        private val deletionPublished = MutableStateFlow<SessionDeletionUiState>(SessionDeletionUiState.Idle)
        val deletionState: StateFlow<SessionDeletionUiState> = deletionPublished.asStateFlow()
        private var deletionNonce = 0L
        private var deletion: DeletionAction? = null

        private var reviewEntry: SessionHistoryEntry.FinishedReview? = null
        private var reviewUnavailable = false

        val uiState =
            entryFlow(SessionHistoryEntry.Explore) { true }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), SessionHistoryUiState.Loading)

        /** Each exact route supplies a new RESUMED owner. Null never opts into general history. */
        fun observeEntry(
            entry: SessionHistoryEntry,
            canKeepOpen: () -> Boolean,
        ): Flow<SessionHistoryUiState> = entryFlow(entry, canKeepOpen)

        private fun entryFlow(
            entry: SessionHistoryEntry,
            canKeepOpen: () -> Boolean,
        ): Flow<SessionHistoryUiState> =
            flow {
                coroutineScope {
                    val owner = Reader(checkNotNull(currentCoroutineContext()[Job]), entry, canKeepOpen)
                    val installed =
                        synchronized(lock) {
                            owner.job.ensureActive()
                            if (entry is SessionHistoryEntry.FinishedReview && !hostAllowed(owner)) return@synchronized false
                            owner.job.ensureActive()
                            invalidateDeletionLocked()
                            reader = owner
                            ended = null
                            if (entry is SessionHistoryEntry.FinishedReview) {
                                if (reviewEntry != entry) {
                                    reviewEntry = entry
                                    reviewUnavailable = entry.expectedSource == null || entry.expectedSource.isActive
                                }
                                selectedId = entry.expectedSource?.sessionId
                                selectionLost = false
                            }
                            publishLocked(
                                when {
                                    entry is SessionHistoryEntry.FinishedReview && reviewUnavailable -> SessionHistoryUiState.Unavailable
                                    else -> SessionHistoryUiState.Loading
                                },
                            )
                            true
                        }
                    if (!installed) {
                        emit(SessionHistoryUiState.Locked)
                        return@coroutineScope
                    }
                    val queryJob =
                        launch {
                            if (entry is SessionHistoryEntry.FinishedReview && reviewUnavailable) return@launch
                            if (!hostAllowed(owner)) return@launch
                            requests.flatMapLatest { watchAccess(owner, it) }.collect()
                        }
                    try {
                        emitAll(published)
                    } finally {
                        synchronized(lock) {
                            if (reader === owner) {
                                invalidateDeletionLocked()
                                owner.query = null
                                reader = null
                                ended = null
                                publishLocked(SessionHistoryUiState.Loading)
                            }
                        }
                        queryJob.cancel()
                    }
                }
            }

        fun canRead(): Boolean =
            synchronized(lock) {
                allowsPracticeInteraction(vehicle.state.value, restrictions.restrictions.value) &&
                    (reader?.let(::hostAllowed) != false)
            }

        private fun hostAllowed(owner: Reader): Boolean =
            try {
                owner.canKeepOpen()
            } catch (_: Exception) {
                false
            }

        /** Rechecks the exact displayed summary at the detail action and composition boundaries. */
        fun canRevealSummary(
            expectedSessionId: String,
            expectedGeneration: Long,
        ): Boolean =
            synchronized(lock) {
                val owner = reader ?: return@synchronized false
                val query = owner.query ?: return@synchronized false
                if (!currentQuery(owner, query) || !canRead()) return@synchronized false
                val current = published.value as? SessionHistoryUiState.Ready ?: return@synchronized false
                val records = ended ?: return@synchronized false
                !selectionLost && selectedId == expectedSessionId &&
                    current.session.sessionId == expectedSessionId && current.generation == expectedGeneration &&
                    records.getOrNull(current.index) == current.session && current.summary.source == current.session
            }

        /** A rejected old detail opening cannot borrow a new P context after observing denial. */
        fun canRevealStoredObservations(
            expectedSessionId: String,
            expectedGeneration: Long,
        ): Boolean =
            synchronized(lock) {
                val owner = reader ?: return@synchronized false
                val query = owner.query ?: return@synchronized false
                if (selectionSnapshotLocked(expectedSessionId, expectedGeneration) == null) return@synchronized false
                if (!canRead()) {
                    restartDeniedQueryLocked(owner, query)
                    return@synchronized false
                }
                true
            }

        fun observeStoredObservations(
            expected: PracticeSession,
            historyGeneration: Long,
            canKeepOpen: () -> Boolean,
        ): Flow<SessionHistoryObservationUiState> =
            storedObservations.observe(expected, historyGeneration, canKeepOpen) {
                val owner = reader
                val query = owner?.query
                if (owner == null || query == null || selectionSnapshotLocked(expected.sessionId, historyGeneration)?.session != expected) {
                    null
                } else {
                    SessionHistoryObservationReader.Binding(
                        isCurrent = {
                            currentQuery(owner, query) &&
                                selectionSnapshotLocked(expected.sessionId, historyGeneration)?.session == expected
                        },
                        denyAccess = {
                            if (currentQuery(owner, query)) restartDeniedQueryLocked(owner, query)
                        },
                    )
                }
            }

        /** A run detail has its own cold query, bound to the same received history selection. */
        fun canRevealStoredRecordingRuns(
            expectedSessionId: String,
            expectedGeneration: Long,
        ): Boolean =
            synchronized(lock) {
                val owner = reader ?: return@synchronized false
                val query = owner.query ?: return@synchronized false
                if (selectionSnapshotLocked(expectedSessionId, expectedGeneration) == null) return@synchronized false
                if (!canRead()) {
                    restartDeniedQueryLocked(owner, query)
                    return@synchronized false
                }
                true
            }

        fun observeStoredRecordingRuns(
            expected: PracticeSession,
            historyGeneration: Long,
            canKeepOpen: () -> Boolean,
        ): Flow<SessionHistoryRecordingRunUiState> =
            storedRecordingRuns.observe(expected, historyGeneration, canKeepOpen) {
                val owner = reader
                val query = owner?.query
                if (owner == null || query == null || selectionSnapshotLocked(expected.sessionId, historyGeneration)?.session != expected) {
                    null
                } else {
                    SessionHistoryRecordingRunReader.Binding(
                        isCurrent = {
                            currentQuery(owner, query) &&
                                selectionSnapshotLocked(expected.sessionId, historyGeneration)?.session == expected
                        },
                        denyAccess = {
                            if (currentQuery(owner, query)) restartDeniedQueryLocked(owner, query)
                        },
                    )
                }
            }

        fun canReadStoredRecordingRuns(token: SessionHistoryRecordingRunUiState.Token): Boolean = storedRecordingRuns.canRead(token)

        fun previousStoredRecordingRun(
            token: SessionHistoryRecordingRunUiState.Token,
            expectedRunId: String,
        ): Boolean = storedRecordingRuns.previous(token, expectedRunId)

        fun nextStoredRecordingRun(
            token: SessionHistoryRecordingRunUiState.Token,
            expectedRunId: String,
        ): Boolean = storedRecordingRuns.next(token, expectedRunId)

        fun retryStoredRecordingRuns(
            token: SessionHistoryRecordingRunUiState.Token,
            expectedRunId: String?,
        ): Boolean = storedRecordingRuns.retry(token, expectedRunId)

        fun canReadStoredObservations(token: Token): Boolean = storedObservations.canRead(token)

        fun previousStoredObservation(
            token: Token,
            expectedRowId: Long,
        ): Boolean = storedObservations.previous(token, expectedRowId)

        fun nextStoredObservation(
            token: Token,
            expectedRowId: Long,
        ): Boolean = storedObservations.next(token, expectedRowId)

        fun retryStoredObservations(
            token: Token,
            expectedRowId: Long?,
        ): Boolean = storedObservations.retry(token, expectedRowId)

        fun canRevealStoredMemo(
            expectedSessionId: String,
            expectedGeneration: Long,
        ): Boolean = canRevealStoredObservations(expectedSessionId, expectedGeneration)

        fun observeStoredMemo(
            expected: PracticeSession,
            historyGeneration: Long,
            canKeepOpen: () -> Boolean,
        ): Flow<SessionHistoryMemoUiState> =
            deletionDetailReads.flatMapLatest {
                storedMemos.observe(expected, historyGeneration, canKeepOpen) {
                    val owner = reader
                    val query = owner?.query
                    if (owner == null || query == null ||
                        selectionSnapshotLocked(expected.sessionId, historyGeneration)?.session != expected
                    ) {
                        null
                    } else {
                        SessionHistoryMemoReader.Binding(
                            isCurrent = {
                                currentQuery(owner, query) &&
                                    selectionSnapshotLocked(expected.sessionId, historyGeneration)?.session == expected
                            },
                            denyAccess = {
                                if (currentQuery(owner, query)) restartDeniedQueryLocked(owner, query)
                            },
                            canWrite = {
                                receivedHasActiveSession == false &&
                                    deletion?.phase !in listOf(DeletionPhase.PREPARING, DeletionPhase.CONFIRMING, DeletionPhase.DELETING)
                            },
                        )
                    }
                }
            }

        fun canRevealCourseCompletion(
            expectedSessionId: String,
            expectedGeneration: Long,
        ): Boolean = canRevealStoredObservations(expectedSessionId, expectedGeneration)

        fun observeCourseCompletion(
            expected: PracticeSession,
            historyGeneration: Long,
            canKeepOpen: () -> Boolean,
        ): Flow<SessionHistoryCourseCompletionUiState> =
            deletionDetailReads.flatMapLatest {
                courseCompletion.observe(expected, historyGeneration, canKeepOpen) {
                    val owner = reader
                    val query = owner?.query
                    if (owner == null || query == null ||
                        selectionSnapshotLocked(expected.sessionId, historyGeneration)?.session != expected
                    ) {
                        null
                    } else {
                        SessionHistoryCourseCompletionReader.Binding(
                            isCurrent = {
                                currentQuery(owner, query) &&
                                    selectionSnapshotLocked(expected.sessionId, historyGeneration)?.session == expected
                            },
                            denyAccess = { if (currentQuery(owner, query)) restartDeniedQueryLocked(owner, query) },
                            canDeclare = {
                                receivedHasActiveSession == false &&
                                    deletion?.phase !in listOf(DeletionPhase.PREPARING, DeletionPhase.CONFIRMING, DeletionPhase.DELETING)
                            },
                        )
                    }
                }
            }

        fun canReadCourseCompletion(token: SessionHistoryCourseCompletionUiState.Token): Boolean = courseCompletion.canRead(token)

        fun requestCourseCompletion(token: SessionHistoryCourseCompletionUiState.Token): Boolean = courseCompletion.request(token)

        fun confirmCourseCompletion(
            token: SessionHistoryCourseCompletionUiState.Token,
            nonce: Long,
        ): Boolean = courseCompletion.confirm(token, nonce)

        fun dismissCourseCompletion(
            token: SessionHistoryCourseCompletionUiState.Token,
            nonce: Long,
        ): Boolean = courseCompletion.dismiss(token, nonce)

        fun retryCourseCompletion(token: SessionHistoryCourseCompletionUiState.Token): Boolean = courseCompletion.retry(token)

        fun canReadStoredMemo(token: SessionHistoryMemoUiState.Token): Boolean = storedMemos.canRead(token)

        fun retryStoredMemo(token: SessionHistoryMemoUiState.Token): Boolean = storedMemos.retry(token)

        fun beginStoredMemoEdit(token: SessionHistoryMemoUiState.Token): Boolean = storedMemos.beginEdit(token)

        fun editStoredMemo(
            token: SessionHistoryMemoUiState.Token,
            nonce: Long,
            text: String,
        ): Boolean = storedMemos.edit(token, nonce, text)

        fun cancelStoredMemoEdit(
            token: SessionHistoryMemoUiState.Token,
            nonce: Long,
        ): Boolean = storedMemos.cancelEdit(token, nonce)

        fun saveStoredMemo(
            token: SessionHistoryMemoUiState.Token,
            nonce: Long,
            expectedText: String,
        ): Boolean = storedMemos.save(token, nonce, expectedText)

        /** No additional database read exists until a visible, source-bound request is accepted. */
        fun requestDeletion(
            expectedSessionId: String,
            expectedGeneration: Long,
            canKeepOpen: () -> Boolean,
        ): Boolean {
            val work =
                synchronized(lock) {
                    val previous = deletion
                    if (previous != null) {
                        val existingContext =
                            when {
                                previous.phase == DeletionPhase.DELETED ||
                                    (previous.phase == DeletionPhase.DELETING && previous.sourceAbsent) ->
                                    currentDeletionOwnerLocked(previous)
                                else -> currentDeletionContextLocked(previous)
                            }
                        if (!existingContext) {
                            // Observing a denial must revoke old consent, even if D/P later conflate.
                            invalidateDeletionLocked()
                            return@synchronized null
                        }
                    }
                    val current = currentSelectionLocked(expectedSessionId, expectedGeneration) ?: return@synchronized null
                    if (!canKeepOpen()) return@synchronized null
                    if (storedMemos.isEditing()) return@synchronized null
                    if (deletion?.phase in listOf(DeletionPhase.PREPARING, DeletionPhase.CONFIRMING, DeletionPhase.DELETING)) {
                        return@synchronized null
                    }
                    invalidateDeletionLocked()
                    val owner = checkNotNull(reader)
                    val query = checkNotNull(owner.query)
                    courseCompletion.invalidate()
                    storedMemos.invalidate()
                    val action = DeletionAction(++deletionNonce, owner, query, current.session, current.generation, canKeepOpen)
                    val job =
                        viewModelScope.launch(start = CoroutineStart.LAZY) {
                            yield()
                            prepareDeletion(action)
                        }
                    action.job = job
                    deletion = action
                    deletionPublished.value = SessionDeletionUiState.Preparing(action.nonce, action.session)
                    job
                } ?: return false
            work.start()
            return true
        }

        fun confirmDeletion(nonce: Long): Boolean {
            val work =
                synchronized(lock) {
                    val action = deletion?.takeIf { it.nonce == nonce } ?: return@synchronized null
                    if (action.phase != DeletionPhase.CONFIRMING) return@synchronized null
                    if (!currentDeletionContextLocked(action)) {
                        invalidateDeletionLocked()
                        return@synchronized null
                    }
                    val target = action.target ?: return@synchronized null
                    val job = viewModelScope.launch(start = CoroutineStart.LAZY) { deleteSession(action, target) }
                    action.phase = DeletionPhase.DELETING
                    action.job = job
                    deletionPublished.value = SessionDeletionUiState.Deleting(nonce, target)
                    job
                } ?: return false
            work.start()
            return true
        }

        fun dismissDeletion(nonce: Long): Boolean =
            synchronized(lock) {
                val action = deletion?.takeIf { it.nonce == nonce } ?: return@synchronized false
                val recoverDetails = takeDetailReadRecoveryLocked(action)
                invalidateDeletionLocked()
                if (recoverDetails) deletionDetailReads.value++
                true
            }

        /** Read-only composition guard, separate from the transaction's stricter precommit guard. */
        fun canPresentDeletion(nonce: Long): Boolean =
            synchronized(lock) {
                val action = deletion?.takeIf { it.nonce == nonce } ?: return@synchronized false
                if (!currentDeletionOwnerLocked(action)) return@synchronized false
                when (action.phase) {
                    DeletionPhase.DELETED -> true
                    DeletionPhase.DELETING ->
                        action.job?.isActive == true &&
                            (action.sourceAbsent || currentDeletionContextLocked(action))
                    DeletionPhase.PREPARING -> action.job?.isActive == true && currentDeletionContextLocked(action)
                    DeletionPhase.CONFIRMING, DeletionPhase.FAILED -> currentDeletionContextLocked(action)
                }
            }

        private suspend fun prepareDeletion(action: DeletionAction) {
            val job = checkNotNull(currentCoroutineContext()[Job])
            try {
                val result = deletions.prepare(action.session) { canPrepareDeletion(action, job) }
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (!canPrepareDeletionLocked(action, job)) return@synchronized
                    when (result) {
                        is SessionDeletionPreparation.Ready -> {
                            if (result.target.session != action.session || result.target.observationCount < 0) {
                                failDeletionLocked(action, SessionDeletionUiState.FailureReason.CHANGED)
                            } else {
                                action.target = result.target
                                action.phase = DeletionPhase.CONFIRMING
                                action.job = null
                                deletionPublished.value = SessionDeletionUiState.Confirming(action.nonce, result.target)
                            }
                        }
                        SessionDeletionPreparation.Missing -> failDeletionLocked(action, SessionDeletionUiState.FailureReason.MISSING)
                        SessionDeletionPreparation.Active -> failDeletionLocked(action, SessionDeletionUiState.FailureReason.ACTIVE)
                        SessionDeletionPreparation.Changed -> failDeletionLocked(action, SessionDeletionUiState.FailureReason.CHANGED)
                        SessionDeletionPreparation.Blocked -> failDeletionLocked(action, SessionDeletionUiState.FailureReason.BLOCKED)
                    }
                }
            } catch (cancelled: CancellationException) {
                synchronized(lock) {
                    if (deletion === action && action.job === job) {
                        action.job = null
                        invalidateDeletionLocked()
                    }
                }
                throw cancelled
            } catch (_: Exception) {
                synchronized(lock) {
                    if (canPrepareDeletionLocked(action, job)) {
                        failDeletionLocked(action, SessionDeletionUiState.FailureReason.PREPARE_FAILED)
                    }
                }
            } finally {
                synchronized(lock) {
                    if (deletion === action && action.job === job) {
                        action.job = null
                        if (action.phase == DeletionPhase.PREPARING || action.phase == DeletionPhase.DELETING) {
                            invalidateDeletionLocked()
                        }
                    }
                }
            }
        }

        private suspend fun deleteSession(
            action: DeletionAction,
            target: SessionDeletionTarget,
        ) {
            val job = checkNotNull(currentCoroutineContext()[Job])
            try {
                val result = deletions.delete(target) { canCommitDeletion(action, job) }
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (!currentDeletionOwnerLocked(action) || action.phase != DeletionPhase.DELETING || action.job !== job) {
                        return@synchronized
                    }
                    when (result) {
                        SessionDeletionResult.Deleted -> {
                            action.phase = DeletionPhase.DELETED
                            action.job = null
                            selectionLost = true
                            // Preserve the deleted cursor until the user explicitly accepts another record.
                            selectedId = action.session.sessionId
                            if (action.owner.entry is SessionHistoryEntry.FinishedReview) {
                                reviewUnavailable = true
                                publishLocked(SessionHistoryUiState.Unavailable)
                            } else {
                                published.value = SessionHistoryUiState.SelectionChanged(nextGenerationLocked())
                            }
                            deletionPublished.value = SessionDeletionUiState.Deleted(action.nonce, action.session)
                        }
                        SessionDeletionResult.Missing -> failDeletionLocked(action, SessionDeletionUiState.FailureReason.MISSING)
                        SessionDeletionResult.Active -> failDeletionLocked(action, SessionDeletionUiState.FailureReason.ACTIVE)
                        SessionDeletionResult.Changed -> failDeletionLocked(action, SessionDeletionUiState.FailureReason.CHANGED)
                        SessionDeletionResult.Blocked -> failDeletionLocked(action, SessionDeletionUiState.FailureReason.BLOCKED)
                    }
                }
            } catch (cancelled: CancellationException) {
                synchronized(lock) {
                    if (deletion === action && action.job === job) {
                        action.job = null
                        invalidateDeletionLocked()
                    }
                }
                throw cancelled
            } catch (_: Exception) {
                synchronized(lock) {
                    if (currentDeletionOwnerLocked(action) && action.phase == DeletionPhase.DELETING && action.job === job) {
                        failDeletionLocked(action, SessionDeletionUiState.FailureReason.DELETE_FAILED)
                    }
                }
            } finally {
                synchronized(lock) {
                    if (deletion === action && action.job === job) {
                        action.job = null
                        if (action.phase == DeletionPhase.PREPARING || action.phase == DeletionPhase.DELETING) {
                            invalidateDeletionLocked()
                        }
                    }
                }
            }
        }

        private fun canPrepareDeletion(
            action: DeletionAction,
            job: Job,
        ): Boolean = synchronized(lock) { canPrepareDeletionLocked(action, job) }

        private fun canPrepareDeletionLocked(
            action: DeletionAction,
            job: Job,
        ): Boolean = action.phase == DeletionPhase.PREPARING && action.job === job && job.isActive && currentDeletionContextLocked(action)

        private fun canCommitDeletion(
            action: DeletionAction,
            job: Job,
        ): Boolean =
            synchronized(lock) {
                action.phase == DeletionPhase.DELETING && action.job === job && job.isActive && currentDeletionContextLocked(action)
            }

        private fun currentDeletionOwnerLocked(action: DeletionAction): Boolean =
            deletion === action && currentQuery(action.owner, action.query) &&
                selectedId == action.session.sessionId && canRead() && action.canKeepOpen()

        private fun currentDeletionContextLocked(action: DeletionAction): Boolean =
            currentDeletionOwnerLocked(action) && !selectionLost &&
                currentSelectionLocked(action.session.sessionId, action.generation)?.session == action.session

        private fun currentSelectionLocked(
            expectedSessionId: String,
            expectedGeneration: Long,
        ): SessionHistoryUiState.Ready? {
            if (!canRead()) return null
            return selectionSnapshotLocked(expectedSessionId, expectedGeneration)
        }

        /** Same received source check, without conflating a denied live value with an expired source. */
        private fun selectionSnapshotLocked(
            expectedSessionId: String,
            expectedGeneration: Long,
        ): SessionHistoryUiState.Ready? {
            val owner = reader ?: return null
            val query = owner.query ?: return null
            if (!currentQuery(owner, query) || selectionLost || selectedId != expectedSessionId) return null
            val current = published.value as? SessionHistoryUiState.Ready ?: return null
            val records = ended ?: return null
            return current.takeIf {
                it.session.sessionId == expectedSessionId && it.generation == expectedGeneration &&
                    records.getOrNull(it.index) == it.session && it.summary.source == it.session
            }
        }

        private fun failDeletionLocked(
            action: DeletionAction,
            reason: SessionDeletionUiState.FailureReason,
        ) {
            val recoverPreparation = action.phase == DeletionPhase.PREPARING
            if (reason == SessionDeletionUiState.FailureReason.MISSING) action.detailReadRecoveryPending = false
            action.phase = DeletionPhase.FAILED
            action.job = null
            deletionPublished.value = SessionDeletionUiState.Failed(action.nonce, action.session, reason)
            if (recoverPreparation && takeDetailReadRecoveryLocked(action)) deletionDetailReads.value++
        }

        private fun takeDetailReadRecoveryLocked(action: DeletionAction): Boolean {
            if (!action.detailReadRecoveryPending) return false
            action.detailReadRecoveryPending = false
            return !action.sourceAbsent && action.phase != DeletionPhase.DELETING &&
                action.phase != DeletionPhase.DELETED && currentDeletionContextLocked(action)
        }

        private fun invalidateDeletionLocked() {
            val previous = deletion
            deletion = null
            deletionPublished.value = SessionDeletionUiState.Idle
            previous?.job?.cancel()
        }

        private fun preserveDeletionReceiptLocked(
            owner: Reader,
            query: Query,
            records: List<PracticeSession>,
        ): Boolean {
            val action = deletion ?: return false
            if (action.owner !== owner || action.query !== query || !currentDeletionOwnerLocked(action)) return false
            val source = records.singleOrNull { it.sessionId == action.session.sessionId }
            return when {
                action.phase == DeletionPhase.DELETING && source == null -> {
                    action.sourceAbsent = true
                    true
                }
                action.phase == DeletionPhase.DELETED && (source == null || source == action.session) -> true
                else -> false
            }
        }

        fun retry(): Boolean =
            synchronized(lock) {
                val owner = reader?.takeIf { it.job.isActive } ?: return@synchronized false
                if (!canRead()) return@synchronized false
                if (owner.entry is SessionHistoryEntry.FinishedReview && reviewUnavailable) return@synchronized false
                invalidateDeletionLocked()
                epoch++
                owner.query = null
                ended = null
                publishLocked(SessionHistoryUiState.Loading)
                requests.value = epoch
                true
            }

        fun retryEntry(
            expected: SessionHistoryUiState,
            canKeepOpen: () -> Boolean,
        ): Boolean =
            synchronized(lock) {
                val owner = reader ?: return@synchronized false
                if (owner.entry !is SessionHistoryEntry.FinishedReview || published.value !== expected ||
                    expected !is SessionHistoryUiState.Failed || !hostAllowed(owner) || !canKeepOpen()
                ) {
                    return@synchronized false
                }
                if (published.value !== expected || reader !== owner) return@synchronized false
                retry()
            }

        /** Explicitly accepts the latest received list after a selected record disappeared. */
        fun resetToLatest(expectedGeneration: Long): Boolean =
            synchronized(lock) {
                val owner = reader ?: return@synchronized false
                val query = owner.query ?: return@synchronized false
                if (owner.entry is SessionHistoryEntry.FinishedReview) return@synchronized false
                if (!currentQuery(owner, query) || !canRead()) return@synchronized false
                val changed = published.value as? SessionHistoryUiState.SelectionChanged ?: return@synchronized false
                if (changed.generation != expectedGeneration) return@synchronized false
                val records = ended ?: return@synchronized false
                invalidateDeletionLocked()
                selectionLost = false
                selectedId = records.firstOrNull()?.sessionId
                publishSelectionLocked(records)
                true
            }

        fun older(
            expectedSessionId: String,
            generation: Long,
        ): Boolean = move(expectedSessionId, generation, 1)

        fun newer(
            expectedSessionId: String,
            generation: Long,
        ): Boolean = move(expectedSessionId, generation, -1)

        private fun move(
            expectedSessionId: String,
            expectedGeneration: Long,
            offset: Int,
        ): Boolean =
            synchronized(lock) {
                val owner = reader ?: return@synchronized false
                val query = owner.query ?: return@synchronized false
                if (owner.entry is SessionHistoryEntry.FinishedReview) return@synchronized false
                if (!currentQuery(owner, query) || !canRead()) return@synchronized false
                val current = published.value as? SessionHistoryUiState.Ready ?: return@synchronized false
                if (
                    current.session.sessionId != expectedSessionId ||
                    current.generation != expectedGeneration ||
                    selectedId != expectedSessionId
                ) {
                    return@synchronized false
                }
                val records = ended ?: return@synchronized false
                val index = current.index + offset
                if (index !in records.indices) return@synchronized false
                invalidateDeletionLocked()
                selectedId = records[index].sessionId
                publishSelectionLocked(records)
                true
            }

        private fun watchAccess(
            owner: Reader,
            requestEpoch: Long,
        ): Flow<Unit> =
            flow {
                emitAll(
                    combine(vehicle.state, restrictions.restrictions) { currentVehicle, currentRestrictions ->
                        if (allowsPracticeInteraction(currentVehicle, currentRestrictions) && canRead()) Access.Allowed else Access.Locked
                    },
                )
            }.distinctUntilChanged()
                .map<_, Access> { it }
                .onCompletion { cause ->
                    if (cause == null) emit(Access.Failed(SessionHistoryUiState.FailureReason.ACCESS_STREAM_COMPLETED))
                }.catch {
                    currentCoroutineContext().ensureActive()
                    emit(Access.Failed(SessionHistoryUiState.FailureReason.ACCESS_READ))
                }.onEach { publishAccess(owner, requestEpoch, it) }
                .flatMapLatest { access ->
                    if (access == Access.Allowed) watchSessions(owner, requestEpoch) else flowOf(Unit)
                }.buffer(0)

        private fun watchSessions(
            owner: Reader,
            requestEpoch: Long,
        ): Flow<Unit> =
            flow {
                val query = Query(requestEpoch, checkNotNull(currentCoroutineContext()[Job]))
                if (!beginQuery(owner, query)) return@flow
                try {
                    emitAll(
                        flow { emitAll(sessions.observeSessions()) }
                            .map { records ->
                                receive(owner, query, records)
                            }.onCompletion { cause ->
                                if (cause == null) {
                                    publishFailure(owner, query, SessionHistoryUiState.FailureReason.SESSION_STREAM_COMPLETED)
                                }
                            }.catch { failure ->
                                currentCoroutineContext().ensureActive()
                                publishFailure(
                                    owner,
                                    query,
                                    if (failure is InvalidSessions) {
                                        SessionHistoryUiState.FailureReason.INVALID_SESSIONS
                                    } else {
                                        SessionHistoryUiState.FailureReason.SESSION_READ
                                    },
                                )
                                emit(Unit)
                            },
                    )
                } finally {
                    synchronized(lock) {
                        if (reader === owner && owner.query === query) {
                            if (owner.entry is SessionHistoryEntry.FinishedReview && !query.job.isActive &&
                                owner.job.isActive && hostAllowed(owner) && canRead()
                            ) {
                                publishLocked(SessionHistoryUiState.Failed(SessionHistoryUiState.FailureReason.SESSION_READ))
                            }
                            owner.query = null
                        }
                    }
                }
            }

        private fun publishAccess(
            owner: Reader,
            requestEpoch: Long,
            access: Access,
        ) = synchronized(lock) {
            if (!currentReader(owner, requestEpoch)) return@synchronized
            invalidateDeletionLocked()
            owner.query = null
            ended = null
            val state =
                when {
                    !canRead() || access == Access.Locked -> SessionHistoryUiState.Locked
                    access is Access.Failed -> SessionHistoryUiState.Failed(access.reason)
                    else -> SessionHistoryUiState.Loading
                }
            publishLocked(state)
        }

        private fun beginQuery(
            owner: Reader,
            query: Query,
        ): Boolean =
            synchronized(lock) {
                if (!currentReader(owner, query.epoch) || !query.job.isActive) return@synchronized false
                if (!canRead()) {
                    restartDeniedQueryLocked(owner, query)
                    return@synchronized false
                }
                invalidateDeletionLocked()
                owner.query = query
                ended = null
                publishLocked(SessionHistoryUiState.Loading)
                true
            }

        /** The source receipt and cursor publication share one lock, before any downstream channel. */
        private fun receive(
            owner: Reader,
            query: Query,
            records: List<PracticeSession>,
        ) = synchronized(lock) {
            if (!currentQuery(owner, query)) return@synchronized
            if (!canRead()) {
                restartDeniedQueryLocked(owner, query)
                return@synchronized
            }
            publishLocked(SessionHistoryUiState.Loading)
            val snapshot = records.toList()
            validate(snapshot)
            receivedHasActiveSession = snapshot.any { it.isActive }
            if (!preserveDeletionReceiptLocked(owner, query, snapshot)) invalidateDeletionLocked()
            val finished = snapshot.filterNot { it.isActive }
            ended = finished
            val exact = owner.entry as? SessionHistoryEntry.FinishedReview
            if (exact != null) {
                val expected = exact.expectedSource
                if (reviewUnavailable || expected == null || snapshot.singleOrNull { it.sessionId == expected.sessionId } != expected) {
                    reviewUnavailable = true
                    publishLocked(SessionHistoryUiState.Unavailable)
                    return@synchronized
                }
                selectedId = expected.sessionId
                selectionLost = false
            }
            if (selectedId != null && finished.none { it.sessionId == selectedId }) selectionLost = true
            if (selectedId == null && !selectionLost) selectedId = finished.firstOrNull()?.sessionId
            publishSelectionLocked(finished)
        }

        /** Caller holds lock and has already observed a denied live value. */
        private fun restartDeniedQueryLocked(
            owner: Reader,
            query: Query,
        ) {
            if (!currentReader(owner, query.epoch) || !query.job.isActive ||
                (owner.query != null && owner.query !== query)
            ) {
                return
            }
            invalidateDeletionLocked()
            owner.query = null
            ended = null
            publishLocked(SessionHistoryUiState.Locked)
            // A new access subscription sees the current value even when a short D/P transition
            // was conflated. A sustained denied value settles at Locked without a database query.
            epoch++
            requests.value = epoch
            query.job.cancel()
        }

        private fun publishFailure(
            owner: Reader,
            query: Query,
            reason: SessionHistoryUiState.FailureReason,
        ) = synchronized(lock) {
            if (!currentQuery(owner, query)) return@synchronized
            if (!canRead()) {
                restartDeniedQueryLocked(owner, query)
                return@synchronized
            }
            invalidateDeletionLocked()
            ended = null
            publishLocked(SessionHistoryUiState.Failed(reason))
        }

        private fun publishSelectionLocked(records: List<PracticeSession>) {
            storedObservations.invalidate()
            storedMemos.invalidate()
            storedRecordingRuns.invalidate()
            courseCompletion.invalidate()
            val index = records.indexOfFirst { it.sessionId == selectedId }
            val nextGeneration = nextGenerationLocked()
            published.value =
                when {
                    selectionLost -> SessionHistoryUiState.SelectionChanged(nextGeneration)
                    records.isEmpty() -> SessionHistoryUiState.Empty
                    index < 0 -> SessionHistoryUiState.SelectionChanged(nextGeneration)
                    else -> {
                        val session = records[index]
                        val summary = SessionCourseSummary.from(session, records) ?: throw InvalidSessions()
                        SessionHistoryUiState.Ready(session, index, records.size, nextGeneration, summary)
                    }
                }
        }

        private fun publishLocked(state: SessionHistoryUiState) {
            storedObservations.invalidate(state == SessionHistoryUiState.Locked)
            storedMemos.invalidate(state == SessionHistoryUiState.Locked)
            storedRecordingRuns.invalidate(state == SessionHistoryUiState.Locked)
            courseCompletion.invalidate(state == SessionHistoryUiState.Locked)
            receivedHasActiveSession = null
            nextGenerationLocked()
            published.value = state
        }

        private fun nextGenerationLocked(): Long = ++generation

        private fun currentReader(
            owner: Reader,
            requestEpoch: Long,
        ): Boolean = reader === owner && owner.job.isActive && requestEpoch == epoch && hostAllowed(owner)

        private fun currentQuery(
            owner: Reader,
            query: Query,
        ): Boolean = currentReader(owner, query.epoch) && owner.query === query && query.job.isActive

        private fun validate(records: List<PracticeSession>) {
            if (records.map { it.sessionId }.distinct().size != records.size) throw InvalidSessions()
            for (record in records) {
                val selection = record.selection
                val endedAt = record.endedAtEpochMillis
                if (record.sessionId.isBlank() || selection.goalId.isBlank() ||
                    selection.courseId.isBlank() || selection.taskId.isBlank() ||
                    selection.courseVersion <= 0 || selection.briefingVersion <= 0 || record.goalTitle.isBlank() ||
                    record.courseTitle.isBlank() || record.taskTitle.isBlank() || record.startedAtEpochMillis <= 0 ||
                    (endedAt != null && endedAt < record.startedAtEpochMillis)
                ) {
                    throw InvalidSessions()
                }
            }
        }

        private class DeletionAction(
            val nonce: Long,
            val owner: Reader,
            val query: Query,
            val session: PracticeSession,
            val generation: Long,
            val canKeepOpen: () -> Boolean,
        ) {
            var phase = DeletionPhase.PREPARING
            var target: SessionDeletionTarget? = null
            var job: Job? = null
            var sourceAbsent = false
            var detailReadRecoveryPending = true
        }

        private enum class DeletionPhase { PREPARING, CONFIRMING, DELETING, FAILED, DELETED }

        private class Reader(
            val job: Job,
            val entry: SessionHistoryEntry,
            val canKeepOpen: () -> Boolean,
        ) {
            var query: Query? = null
        }

        private class Query(
            val epoch: Long,
            val job: Job,
        )

        private sealed interface Access {
            data object Allowed : Access

            data object Locked : Access

            data class Failed(
                val reason: SessionHistoryUiState.FailureReason,
            ) : Access
        }

        private class InvalidSessions : IllegalStateException()
    }
