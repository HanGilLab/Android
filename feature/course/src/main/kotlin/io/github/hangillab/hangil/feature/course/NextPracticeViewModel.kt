package io.github.hangillab.hangil.feature.course

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.NextPracticeRepository
import io.github.hangillab.hangil.core.domain.NextPracticeSaveResult
import io.github.hangillab.hangil.core.domain.NextPracticeSeed
import io.github.hangillab.hangil.core.domain.PlannedPracticeRoad
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.SessionCourseSummary
import io.github.hangillab.hangil.core.domain.SessionReviewRoad
import io.github.hangillab.hangil.core.domain.SessionReviewRoadRepository
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import io.github.hangillab.hangil.feature.course.NextPracticeUiState.FailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
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
import javax.inject.Inject

/** One fixed ended source, manually chosen task, and an owner-bound acknowledgement after commit. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NextPracticeViewModel
    @Inject
    constructor(
        private val vehicle: VehicleGateway,
        private val restrictions: DrivingRestrictionsProvider,
        private val sessions: PracticeSessionRepository,
        private val catalog: PracticeCatalog,
        private val nextRepository: NextPracticeRepository,
        private val savedState: SavedStateHandle,
        private val reviews: SessionReviewRoadRepository,
    ) : ViewModel() {
        private val lock = Any()
        private val sourceId = (savedState.get<Any?>("nextPracticeSourceId") as? String)?.takeIf { it.isNotBlank() }
        private val historyArgumentPresent = savedState.contains("historyNextToken")
        private val historyToken = (savedState.get<Any?>("historyNextToken") as? String)?.takeIf { it.isNotBlank() }
        private var pinnedEntry: EntryKey? = null
        private val published = MutableStateFlow<NextPracticeUiState>(NextPracticeUiState.Loading)
        private val requests = MutableStateFlow(0L)
        private val savedEvents = MutableSharedFlow<Long>(replay = 0)
        val saved: Flow<Long> = savedEvents.asSharedFlow()
        private var reader: Reader? = null
        private var epoch = 0L
        private var generation = 0L
        private var actionSerial = 0L
        private var completionSerial = 0L
        private var closed = false
        private var expired: ExpiredKind? = null
        private var pinned: NextPracticeSeed? = null
        private var received: NextPracticeSeed? = null
        private var taskId: String? = null
        private var reviewRoad: NextPracticeUiState.ReviewRoad = NextPracticeUiState.ReviewRoad.Loading
        private var targetReview: SessionReviewRoad? = null
        private var targetChoiceRequired = pendingTargetChoice()
        private var activeAction: Action? = null
        private var completion: Completion? = null

        internal val uiState =
            observe(Entry.Latest)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), NextPracticeUiState.Loading)

        /** The caller supplies a fresh, irrevocable host receipt for each resumed history entry. */
        internal fun observeHistory(
            expectedSource: PracticeSession?,
            isEntryCurrent: () -> Boolean,
            canKeepOpen: () -> Boolean,
        ): Flow<NextPracticeUiState> =
            flow {
                val token = historyToken
                if (token == null || expectedSource == null || expectedSource.isActive ||
                    expectedSource.sessionId != sourceId || !safeGuard(isEntryCurrent) || !safeGuard(canKeepOpen)
                ) {
                    // A malformed request must neither query latest nor replace another live owner.
                    emit(NextPracticeUiState.SourceChanged(-1))
                    return@flow
                }
                emitAll(observe(Entry.History(token, expectedSource, isEntryCurrent, canKeepOpen)))
            }

        private fun observe(entry: Entry): Flow<NextPracticeUiState> =
            flow {
                coroutineScope {
                    val owner = Reader(checkNotNull(currentCoroutineContext()[Job]), entry)
                    val accepted =
                        synchronized(lock) {
                            if (!entryCurrent(owner) || (entry == Entry.Latest && historyArgumentPresent)) return@synchronized false
                            val previous = reader
                            reader = owner
                            invalidateActionLocked()
                            previous?.query?.job?.cancel()
                            val key = entry.key()
                            if (pinnedEntry != key) {
                                pinnedEntry = key
                                pinned = null
                                taskId = null
                                targetReview = null
                                targetChoiceRequired = pendingTargetChoice()
                                expired = null
                            }
                            received = null
                            resetReviewLocked()
                            requests.value = ++epoch
                            publishLocked(if (closed) NextPracticeUiState.Locked else NextPracticeUiState.Loading)
                            true
                        }
                    if (!accepted) {
                        emit(NextPracticeUiState.SourceChanged(-1))
                        return@coroutineScope
                    }
                    val queryJob = launch { requests.flatMapLatest { watchAccess(owner, it) }.collect() }
                    try {
                        emitAll(published)
                    } finally {
                        synchronized(lock) {
                            if (reader === owner) {
                                owner.query = null
                                reader = null
                                received = null
                                invalidateActionLocked()
                                publishLocked(NextPracticeUiState.Loading)
                            }
                        }
                        queryJob.cancel()
                    }
                }
            }

        fun canInteract(): Boolean =
            synchronized(lock) { !closed && reader?.let { it.job.isActive && entryCurrent(it) } == true && permitted() }

        /** A detail callback remains bound to its displayed source and manually selected generation. */
        fun canRevealSource(
            expectedSourceId: String,
            expectedGeneration: Long,
        ): Boolean = synchronized(lock) { currentReady(expectedSourceId, expectedGeneration) != null }

        fun selectTask(
            taskId: String,
            expectedSourceId: String,
            generation: Long,
        ): Boolean =
            synchronized(lock) {
                val current = currentReady(expectedSourceId, generation) ?: return@synchronized false
                if (current.saving || current.taskId == taskId) return@synchronized false
                val target =
                    current.seed.practice.course.tasks
                        .singleOrNull { it.id == taskId } ?: return@synchronized false
                if (target.id.isBlank() || target.title.isBlank()) return@synchronized false
                invalidateActionLocked()
                this.taskId = taskId
                savedState["nextPracticeTaskId"] = taskId
                savedState["nextPracticeSeedFields"] = binding(current.seed)
                published.value = current.copy(taskId = taskId, generation = ++this.generation, saveFailed = false)
                true
            }

        fun useReviewRoad(
            expectedSourceId: String,
            generation: Long,
        ): Boolean =
            synchronized(lock) {
                val current = currentReady(expectedSourceId, generation) ?: return@synchronized false
                val available = current.reviewRoad as? NextPracticeUiState.ReviewRoad.Available ?: return@synchronized false
                if (current.saving || (current.targetReview == available.review && !current.targetChoiceRequired)) return@synchronized false
                invalidateActionLocked()
                targetReview = available.review
                targetChoiceRequired = false
                savedState["nextPracticeTargetChoicePending"] = true
                published.value =
                    current.copy(
                        targetReview = targetReview,
                        targetChoiceRequired = false,
                        generation = ++this.generation,
                        saveFailed = false,
                    )
                true
            }

        fun unlinkReviewRoad(
            expectedSourceId: String,
            generation: Long,
        ): Boolean =
            synchronized(lock) {
                val current = currentReady(expectedSourceId, generation) ?: return@synchronized false
                if (current.saving || (current.targetReview == null && !current.targetChoiceRequired)) return@synchronized false
                invalidateActionLocked()
                targetReview = null
                targetChoiceRequired = false
                savedState["nextPracticeTargetChoicePending"] = false
                published.value =
                    current.copy(targetReview = null, targetChoiceRequired = false, generation = ++this.generation, saveFailed = false)
                true
            }

        fun retryReviewRoad(
            expectedSourceId: String,
            generation: Long,
        ): Boolean =
            synchronized(lock) {
                val current = currentReady(expectedSourceId, generation) ?: return@synchronized false
                if (current.saving || current.reviewRoad != NextPracticeUiState.ReviewRoad.Failed) return@synchronized false
                retry()
            }

        fun save(
            expectedSourceId: String,
            generation: Long,
        ): Boolean {
            val job =
                synchronized(lock) {
                    val current = currentReady(expectedSourceId, generation) ?: return@synchronized null
                    if (!current.canSave || activeAction != null) return@synchronized null
                    val owner = reader ?: return@synchronized null
                    val query = owner.query ?: return@synchronized null
                    val task = current.taskId ?: return@synchronized null
                    val practice = current.seed.practice
                    val selection =
                        PracticeSelection(
                            goalId = practice.goal.id,
                            courseId = practice.course.id,
                            courseVersion = practice.course.version,
                            briefingVersion = practice.course.briefingVersion,
                            taskId = task,
                            plannedRoad = current.targetReview?.let { PlannedPracticeRoad.from(it) },
                        )
                    val action = Action(++actionSerial, owner, query, current.seed, selection, current.targetReview)
                    completion = null
                    val work = viewModelScope.launch(start = CoroutineStart.LAZY) { performSave(action) }
                    action.job = work
                    activeAction = action
                    published.value = current.copy(saving = true, saveFailed = false)
                    work
                } ?: return false
            job.start()
            return true
        }

        fun consumeSaved(token: Long): Boolean =
            synchronized(lock) {
                val done = completion ?: return@synchronized false
                if (!permitted()) {
                    completion = null
                    return@synchronized false
                }
                val current = currentReady(done.seed.source.sessionId) ?: return@synchronized false
                if (closed || done.token != token || done.action != actionSerial || reader !== done.owner ||
                    done.owner.query !== done.query || current.seed != done.seed || current.taskId != done.taskId || current.saving
                ) {
                    return@synchronized false
                }
                completion = null
                leaveLocked()
                true
            }

        fun retry(): Boolean =
            synchronized(lock) {
                val owner = reader ?: return@synchronized false
                if (closed || expired != null || !owner.job.isActive || !entryCurrent(owner) || !permitted() ||
                    activeAction != null
                ) {
                    return@synchronized false
                }
                val oldQuery = owner.query
                owner.query = null
                received = null
                invalidateActionLocked()
                resetReviewLocked()
                publishLocked(NextPracticeUiState.Loading)
                requests.value = ++epoch
                oldQuery?.job?.cancel()
                true
            }

        fun leaveForNormalPreparation(expectedGeneration: Long): Boolean =
            synchronized(lock) {
                val owner = reader ?: return@synchronized false
                if (closed || !owner.job.isActive || !entryCurrent(owner) || !permitted()) return@synchronized false
                val currentGeneration =
                    when (val state = published.value) {
                        is NextPracticeUiState.SourceChanged -> state.generation
                        is NextPracticeUiState.Unavailable -> state.generation
                        is NextPracticeUiState.Failed -> state.generation
                        else -> return@synchronized false
                    }
                if (currentGeneration != expectedGeneration) return@synchronized false
                leaveLocked()
                true
            }

        fun leave() = synchronized(lock) { leaveLocked() }

        private fun leaveLocked() {
            closed = true
            epoch++
            val query = reader?.query
            reader?.query = null
            received = null
            invalidateActionLocked()
            publishLocked(NextPracticeUiState.Locked)
            query?.job?.cancel()
        }

        private suspend fun performSave(action: Action) {
            try {
                if (!canCommit(action)) return
                val result =
                    when (action.owner.entry) {
                        Entry.Latest ->
                            nextRepository.saveSelection(
                                action.seed,
                                action.selection,
                                action.expectedReview,
                            ) { canCommit(action) }
                        is Entry.History ->
                            nextRepository.saveHistorySelection(
                                action.seed,
                                action.selection,
                                action.expectedReview,
                            ) { canCommit(action) }
                    }
                currentCoroutineContext().ensureActive()
                val token =
                    synchronized(lock) {
                        if (!currentAction(action)) return@synchronized null
                        when (result) {
                            NextPracticeSaveResult.Saved -> {
                                val current = published.value as NextPracticeUiState.Ready
                                activeAction = null
                                published.value = current.copy(saving = false, saveFailed = false)
                                val done =
                                    Completion(
                                        token = ++completionSerial,
                                        action = action.id,
                                        owner = action.owner,
                                        query = action.query,
                                        seed = action.seed,
                                        taskId = action.selection.taskId,
                                    )
                                completion = done
                                done.token
                            }
                            NextPracticeSaveResult.Blocked -> {
                                saveFailedLocked(action)
                                null
                            }
                            NextPracticeSaveResult.SourceChanged -> {
                                expireLocked(action.owner, action.query, ExpiredKind.SOURCE_CHANGED)
                                null
                            }
                            NextPracticeSaveResult.Unavailable -> {
                                expireLocked(action.owner, action.query, ExpiredKind.UNAVAILABLE)
                                null
                            }
                        }
                    }
                if (token != null) savedEvents.emit(token)
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                synchronized(lock) { saveFailedLocked(action) }
                throw cancelled
            } catch (_: Exception) {
                synchronized(lock) { saveFailedLocked(action) }
            } finally {
                synchronized(lock) {
                    if (activeAction === action) {
                        activeAction = null
                        val current = published.value as? NextPracticeUiState.Ready
                        if (current != null && current.seed == action.seed &&
                            current.taskId == action.selection.taskId
                        ) {
                            published.value = current.copy(saving = false)
                        }
                    }
                }
            }
        }

        private fun canCommit(action: Action): Boolean = synchronized(lock) { currentAction(action) }

        private fun currentAction(action: Action): Boolean {
            val current = currentReady(action.seed.source.sessionId) ?: return false
            return activeAction === action && action.id == actionSerial && action.job?.isActive == true &&
                reader === action.owner && action.owner.query === action.query && current.seed == action.seed &&
                pinned == action.seed && current.taskId == action.selection.taskId && current.saving &&
                !current.targetChoiceRequired && current.targetReview == action.expectedReview &&
                (
                    action.expectedReview == null ||
                        (current.reviewRoad as? NextPracticeUiState.ReviewRoad.Available)?.review == action.expectedReview
                )
        }

        private fun saveFailedLocked(action: Action) {
            if (!currentAction(action)) return
            val current = published.value as NextPracticeUiState.Ready
            published.value = current.copy(saving = false, saveFailed = true)
        }

        private fun currentReady(
            expectedSourceId: String,
            expectedGeneration: Long? = null,
        ): NextPracticeUiState.Ready? {
            val owner = reader ?: return null
            val query = owner.query ?: return null
            if (!currentQuery(owner, query) || owner.receiving || !permitted()) return null
            val current = published.value as? NextPracticeUiState.Ready ?: return null
            return current.takeIf {
                it.seed.source.sessionId == expectedSourceId && (expectedGeneration == null || it.generation == expectedGeneration) &&
                    received == it.seed
            }
        }

        private fun watchAccess(
            owner: Reader,
            requestEpoch: Long,
        ): Flow<Unit> =
            flow {
                emitAll(
                    combine(vehicle.state, restrictions.restrictions) { v, r ->
                        if (allowsPracticeInteraction(v, r)) Access.Allowed else Access.Locked
                    },
                )
            }.distinctUntilChanged()
                .map<_, Access> { it }
                .onCompletion { if (it == null) emit(Access.Failed(FailureReason.ACCESS_STREAM_COMPLETED)) }
                .catch {
                    currentCoroutineContext().ensureActive()
                    emit(Access.Failed(FailureReason.ACCESS_READ))
                }.onEach { publishAccess(owner, requestEpoch, it) }
                .flatMapLatest { if (it == Access.Allowed) watchSessions(owner, requestEpoch) else flowOf(Unit) }
                .buffer(0)

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
                            .map {
                                receive(owner, query, it)
                                synchronized(lock) {
                                    (published.value as? NextPracticeUiState.Ready)?.seed?.takeIf {
                                        currentQuery(owner, query) &&
                                            received == it
                                    }
                                }
                            }.onCompletion {
                                if (it == null) {
                                    publishFailure(
                                        owner,
                                        query,
                                        FailureReason.SESSION_STREAM_COMPLETED,
                                    )
                                }
                            }.catch {
                                currentCoroutineContext().ensureActive()
                                publishFailure(
                                    owner,
                                    query,
                                    if (it is InvalidSessions) {
                                        FailureReason.INVALID_SESSIONS
                                    } else {
                                        FailureReason.SESSION_READ
                                    },
                                )
                                emit(null)
                            }.distinctUntilChanged()
                            .flatMapLatest { seed ->
                                if (seed == null) flowOf(Unit) else watchReview(owner, query, seed)
                            },
                    )
                } finally {
                    synchronized(lock) { if (reader === owner && owner.query === query) owner.query = null }
                }
            }

        /** SavedState remembers unfinished intent only, never restores an unvalidated road payload. */
        private fun pendingTargetChoice(): Boolean =
            savedState.contains("nextPracticeTargetChoicePending") && savedState.get<Any?>("nextPracticeTargetChoicePending") != false

        private fun resetReviewLocked() {
            if (targetReview != null) targetChoiceRequired = true
            targetReview = null
            reviewRoad = NextPracticeUiState.ReviewRoad.Loading
        }

        private fun watchReview(
            owner: Reader,
            query: Query,
            seed: NextPracticeSeed,
        ): Flow<Unit> =
            flow {
                try {
                    emitAll(
                        reviews.observe(seed.source.sessionId).map { review ->
                            check(review == null || review.source == seed.source) { "Review road source changed" }
                            receiveReview(
                                owner,
                                query,
                                seed,
                                review?.let { NextPracticeUiState.ReviewRoad.Available(it) } ?: NextPracticeUiState.ReviewRoad.Absent,
                            )
                        },
                    )
                    receiveReview(owner, query, seed, NextPracticeUiState.ReviewRoad.Failed)
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    receiveReview(owner, query, seed, NextPracticeUiState.ReviewRoad.Failed)
                }
            }

        private fun receiveReview(
            owner: Reader,
            query: Query,
            seed: NextPracticeSeed,
            value: NextPracticeUiState.ReviewRoad,
        ) = synchronized(lock) {
            if (!currentQuery(owner, query) || !permitted() || received != seed) return@synchronized
            val current = published.value as? NextPracticeUiState.Ready ?: return@synchronized
            if (current.seed != seed || reviewRoad == value) return@synchronized
            // Refresh the displayed controls while keeping saves that do not depend on this road.
            val targetChanged = targetReview != null && (value as? NextPracticeUiState.ReviewRoad.Available)?.review != targetReview
            if (targetChanged) {
                targetReview = null
                targetChoiceRequired = true
                invalidateActionLocked()
            }
            reviewRoad = value
            published.value =
                current.copy(
                    reviewRoad = value,
                    targetReview = targetReview,
                    targetChoiceRequired = targetChoiceRequired,
                    generation = ++generation,
                    saving = current.saving && !targetChanged,
                    saveFailed = current.saveFailed && !targetChanged,
                )
        }

        private fun publishAccess(
            owner: Reader,
            requestEpoch: Long,
            access: Access,
        ) = synchronized(lock) {
            if (!currentReader(owner, requestEpoch)) return@synchronized
            owner.query = null
            received = null
            invalidateActionLocked()
            when {
                !permitted() || access == Access.Locked -> publishLocked(NextPracticeUiState.Locked)
                expired != null -> publishExpiredLocked(checkNotNull(expired))
                access is Access.Failed -> publishFailedLocked(access.reason)
                else -> publishLocked(NextPracticeUiState.Loading)
            }
        }

        private fun beginQuery(
            owner: Reader,
            query: Query,
        ): Boolean =
            synchronized(lock) {
                if (!currentReader(owner, query.epoch) || !query.job.isActive) return@synchronized false
                if (!permitted()) {
                    restartDeniedLocked(owner, query)
                    return@synchronized false
                }
                if (sourceId == null || expired != null) {
                    expired = expired ?: ExpiredKind.SOURCE_CHANGED
                    publishExpiredLocked(checkNotNull(expired))
                    return@synchronized false
                }
                owner.query = query
                received = null
                resetReviewLocked()
                invalidateActionLocked()
                publishLocked(NextPracticeUiState.Loading)
                true
            }

        private fun receive(
            owner: Reader,
            query: Query,
            rows: List<PracticeSession>,
        ) = synchronized(lock) {
            if (!currentQuery(owner, query)) return@synchronized
            if (!permitted()) {
                restartDeniedLocked(owner, query)
                return@synchronized
            }
            owner.receiving = true
            try {
                val records = rows.toList()
                validate(records)
                val source =
                    when (val entry = owner.entry) {
                        Entry.Latest -> records.firstOrNull { !it.isActive }
                        is Entry.History ->
                            records
                                .singleOrNull { it.sessionId == entry.source.sessionId }
                                ?.takeIf { it == entry.source }
                    }
                if (records.any { it.isActive } || source == null || source.sessionId != sourceId) {
                    expireLocked(owner, query, ExpiredKind.SOURCE_CHANGED)
                    return@synchronized
                }
                val previous = pinned
                if (previous != null && previous.source != source) {
                    expireLocked(owner, query, ExpiredKind.SOURCE_CHANGED)
                    return@synchronized
                }
                val seed =
                    NextPracticeSeed.resolve(catalog, source) ?: run {
                        expireLocked(owner, query, ExpiredKind.UNAVAILABLE)
                        return@synchronized
                    }
                if (previous != null && previous != seed) {
                    expireLocked(owner, query, ExpiredKind.UNAVAILABLE)
                    return@synchronized
                }
                if (!currentQuery(owner, query)) return@synchronized
                if (!permitted()) {
                    restartDeniedLocked(owner, query)
                    return@synchronized
                }
                if (previous == null) {
                    pinned = seed
                    restoreTaskLocked(seed)
                }
                val summary = SessionCourseSummary.from(source, records) ?: throw InvalidSessions()
                received = seed
                val current = published.value as? NextPracticeUiState.Ready
                if (current == null || current.seed != seed || current.taskId != taskId) {
                    published.value =
                        NextPracticeUiState.Ready(
                            seed,
                            taskId,
                            ++generation,
                            summary,
                            reviewRoad = reviewRoad,
                            targetReview = targetReview,
                            targetChoiceRequired = targetChoiceRequired,
                        )
                } else if (current.summary != summary) {
                    published.value = current.copy(summary = summary)
                }
            } finally {
                owner.receiving = false
            }
        }

        private fun publishFailure(
            owner: Reader,
            query: Query,
            reason: FailureReason,
        ) = synchronized(lock) {
            if (!currentQuery(owner, query)) return@synchronized
            if (!permitted()) {
                restartDeniedLocked(owner, query)
                return@synchronized
            }
            received = null
            invalidateActionLocked()
            publishFailedLocked(reason)
        }

        private fun restartDeniedLocked(
            owner: Reader,
            query: Query,
        ) {
            if (!currentReader(owner, query.epoch) || !query.job.isActive || (owner.query != null && owner.query !== query)) return
            owner.query = null
            received = null
            invalidateActionLocked()
            publishLocked(NextPracticeUiState.Locked)
            requests.value = ++epoch
            query.job.cancel()
        }

        private fun expireLocked(
            owner: Reader,
            query: Query,
            state: ExpiredKind,
        ) {
            if (!currentQuery(owner, query)) return
            expired = state
            owner.query = null
            received = null
            invalidateActionLocked()
            publishExpiredLocked(state)
            query.job.cancel()
        }

        private fun invalidateActionLocked() {
            actionSerial++
            completion = null
            val action = activeAction
            activeAction = null
            action?.job?.cancel()
        }

        private fun publishLocked(state: NextPracticeUiState) {
            generation++
            published.value = state
        }

        private fun publishExpiredLocked(state: ExpiredKind) {
            val nextGeneration = ++generation
            published.value =
                when (state) {
                    ExpiredKind.SOURCE_CHANGED -> NextPracticeUiState.SourceChanged(nextGeneration)
                    ExpiredKind.UNAVAILABLE -> NextPracticeUiState.Unavailable(nextGeneration)
                }
        }

        private fun publishFailedLocked(reason: FailureReason) {
            published.value = NextPracticeUiState.Failed(reason, ++generation)
        }

        private fun permitted(): Boolean = allowsPracticeInteraction(vehicle.state.value, restrictions.restrictions.value)

        private fun currentReader(
            owner: Reader,
            requestEpoch: Long,
        ): Boolean =
            !closed && reader === owner &&
                owner.job.isActive && epoch == requestEpoch && entryCurrent(owner)

        private fun currentQuery(
            owner: Reader,
            query: Query,
        ): Boolean =
            currentReader(owner, query.epoch) &&
                owner.query === query && query.job.isActive

        private fun restoreTaskLocked(seed: NextPracticeSeed) {
            val fields = binding(seed)
            val stored = savedState.get<Any?>("nextPracticeSeedFields")
            val candidate = savedState.get<Any?>("nextPracticeTaskId") as? String
            val target =
                candidate?.let { id ->
                    seed.practice.course.tasks
                        .singleOrNull { it.id == id }
                }
            val sameSeed = stored is ArrayList<*> && stored == fields
            val validTarget = target != null && target.id.isNotBlank() && target.title.isNotBlank()
            taskId = if (sameSeed && validTarget) candidate else null
            savedState["nextPracticeSeedFields"] = fields
            savedState["nextPracticeTaskId"] = taskId
        }

        /** Bundle-safe field boundaries/counts preserve complete equality without toString/hashCode. */
        private fun binding(seed: NextPracticeSeed): ArrayList<String> {
            val fields = arrayListOf<String>()
            val entry = reader?.entry
            if (entry is Entry.History) {
                fields.add("history-entry-v1")
                fields.add(entry.token)
            }
            val source = seed.source
            val selection = source.selection
            fields.addAll(selection.savedFields())
            fields.add(source.sessionId)
            fields.add(selection.goalId)
            fields.add(selection.courseId)
            fields.add(selection.courseVersion.toString())
            fields.add(selection.briefingVersion.toString())
            fields.add(selection.taskId)
            fields.add(source.goalTitle)
            fields.add(source.courseTitle)
            fields.add(source.taskTitle)
            fields.add(source.courseReviewStatus.name)
            fields.add(source.startedAtEpochMillis.toString())
            fields.add(source.endedAtEpochMillis?.toString() ?: "null")
            val practice = seed.practice
            fields.add(practice.goal.id)
            fields.add(practice.goal.title)
            fields.add(practice.goal.description)
            val course = practice.course
            fields.add(course.id)
            fields.add(course.version.toString())
            fields.add(course.briefingVersion.toString())
            fields.add(course.title)
            fields.add(course.summary)
            fields.add(course.reviewStatus.name)
            fields.add(course.sections.size.toString())
            fields.addAll(course.sections)
            fields.add(course.tasks.size.toString())
            course.tasks.forEach { task ->
                fields.add(task.id)
                fields.add(task.title)
                fields.add(task.description)
            }
            fields.add(course.goalIds.size.toString())
            fields.addAll(course.goalIds.sorted())
            val preview = course.mapPreview
            fields.add(if (preview == null) "no-preview" else "preview")
            if (preview != null) {
                fields.add(preview.id)
                fields.add(preview.version.toString())
                fields.add(preview.title)
                fields.add(preview.source.name)
                fields.add(preview.points.size.toString())
                preview.points.forEach { point ->
                    fields.add(point.id)
                    fields.add(point.title)
                    fields.add(point.latitude.toString())
                    fields.add(point.longitude.toString())
                    fields.add(point.osmWayId.toString())
                    fields.add(point.osmVersion.toString())
                }
            }
            fields.add(course.roadSnapshots.size.toString())
            course.roadSnapshots.forEach { road ->
                fields.addAll(
                    listOf(
                        road.id,
                        road.title,
                        road.highway,
                        road.osmWayId.toString(),
                        road.osmWayVersion.toString(),
                        road.capturedAtEpochMillis.toString(),
                    ),
                )
                fields.add(road.nodes.size.toString())
                road.nodes.forEach { node ->
                    fields.addAll(
                        listOf(
                            node.osmNodeId.toString(),
                            node.osmNodeVersion.toString(),
                            node.latitude.toString(),
                            node.longitude.toString(),
                        ),
                    )
                }
            }
            fields.add(practice.task.id)
            fields.add(practice.task.title)
            fields.add(practice.task.description)
            return fields
        }

        private fun validate(rows: List<PracticeSession>) {
            if (rows.map { it.sessionId }.distinct().size != rows.size) throw InvalidSessions()
            for (row in rows) {
                val selection = row.selection
                val end = row.endedAtEpochMillis
                val validIds =
                    row.sessionId.isNotBlank() && selection.goalId.isNotBlank() &&
                        selection.courseId.isNotBlank() && selection.taskId.isNotBlank()
                val validVersions = selection.courseVersion > 0 && selection.briefingVersion > 0
                val validTitles = row.goalTitle.isNotBlank() && row.courseTitle.isNotBlank() && row.taskTitle.isNotBlank()
                val validTimes = row.startedAtEpochMillis > 0 && (end == null || end >= row.startedAtEpochMillis)
                if (!validIds || !validVersions || !validTitles || !validTimes) throw InvalidSessions()
            }
        }

        private fun entryCurrent(owner: Reader): Boolean {
            if (owner.revoked) return false
            val current =
                when (val entry = owner.entry) {
                    Entry.Latest -> true
                    is Entry.History -> safeGuard(entry.isEntryCurrent) && safeGuard(entry.canKeepOpen)
                }
            if (!current) owner.revoked = true
            return current
        }

        private fun safeGuard(guard: () -> Boolean): Boolean = runCatching(guard).getOrDefault(false)

        private sealed interface Entry {
            data object Latest : Entry

            data class History(
                val token: String,
                val source: PracticeSession,
                val isEntryCurrent: () -> Boolean,
                val canKeepOpen: () -> Boolean,
            ) : Entry

            fun key(): EntryKey =
                when (this) {
                    Latest -> EntryKey(null, null)
                    is History -> EntryKey(token, source)
                }
        }

        private data class EntryKey(
            val token: String?,
            val source: PracticeSession?,
        )

        private class Reader(
            val job: Job,
            val entry: Entry,
        ) {
            var query: Query? = null
            var receiving = false
            var revoked = false
        }

        private class Query(
            val epoch: Long,
            val job: Job,
        )

        private class Action(
            val id: Long,
            val owner: Reader,
            val query: Query,
            val seed: NextPracticeSeed,
            val selection: PracticeSelection,
            val expectedReview: SessionReviewRoad?,
        ) {
            var job: Job? = null
        }

        private class Completion(
            val token: Long,
            val action: Long,
            val owner: Reader,
            val query: Query,
            val seed: NextPracticeSeed,
            val taskId: String,
        )

        private sealed interface Access {
            data object Allowed : Access

            data object Locked : Access

            data class Failed(
                val reason: FailureReason,
            ) : Access
        }

        private enum class ExpiredKind { SOURCE_CHANGED, UNAVAILABLE }

        private class InvalidSessions : IllegalStateException()
    }
