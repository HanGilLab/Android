package io.github.hangillab.hangil.feature.course

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.NextPracticeRepository
import io.github.hangillab.hangil.core.domain.NextPracticeSaveResult
import io.github.hangillab.hangil.core.domain.NextPracticeSeed
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
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
    ) : ViewModel() {
        private val lock = Any()
        private val sourceId = (savedState.get<Any?>("nextPracticeSourceId") as? String)?.takeIf { it.isNotBlank() }
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
        private var activeAction: Action? = null
        private var completion: Completion? = null

        internal val uiState =
            flow {
                coroutineScope {
                    val owner = Reader(checkNotNull(currentCoroutineContext()[Job]))
                    synchronized(lock) {
                        reader = owner
                        invalidateActionLocked()
                        received = null
                        publishLocked(if (closed) NextPracticeUiState.Locked else NextPracticeUiState.Loading)
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
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), NextPracticeUiState.Loading)

        fun canInteract(): Boolean = synchronized(lock) { !closed && reader?.job?.isActive == true && permitted() }

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
                        )
                    val action = Action(++actionSerial, owner, query, current.seed, selection, generation)
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
                val current = currentReady(done.seed.source.sessionId, done.generation) ?: return@synchronized false
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
                if (closed || expired != null || !owner.job.isActive || !permitted() || activeAction != null) return@synchronized false
                val oldQuery = owner.query
                owner.query = null
                received = null
                invalidateActionLocked()
                publishLocked(NextPracticeUiState.Loading)
                requests.value = ++epoch
                oldQuery?.job?.cancel()
                true
            }

        fun leaveForNormalPreparation(expectedGeneration: Long): Boolean =
            synchronized(lock) {
                val owner = reader ?: return@synchronized false
                if (closed || !owner.job.isActive || !permitted()) return@synchronized false
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
                val result = nextRepository.saveSelection(action.seed, action.selection) { canCommit(action) }
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
                                        generation = action.generation,
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
                            current.generation == action.generation
                        ) {
                            published.value = current.copy(saving = false)
                        }
                    }
                }
            }
        }

        private fun canCommit(action: Action): Boolean = synchronized(lock) { currentAction(action) }

        private fun currentAction(action: Action): Boolean {
            val current = currentReady(action.seed.source.sessionId, action.generation) ?: return false
            return activeAction === action && action.id == actionSerial && action.job?.isActive == true &&
                reader === action.owner && action.owner.query === action.query && current.seed == action.seed &&
                pinned == action.seed && current.taskId == action.selection.taskId && current.saving
        }

        private fun saveFailedLocked(action: Action) {
            if (!currentAction(action)) return
            val current = published.value as NextPracticeUiState.Ready
            published.value = current.copy(saving = false, saveFailed = true)
        }

        private fun currentReady(
            expectedSourceId: String,
            expectedGeneration: Long,
        ): NextPracticeUiState.Ready? {
            val owner = reader ?: return null
            val query = owner.query ?: return null
            if (!currentQuery(owner, query) || owner.receiving || !permitted()) return null
            val current = published.value as? NextPracticeUiState.Ready ?: return null
            return current.takeIf {
                it.seed.source.sessionId == expectedSourceId && it.generation == expectedGeneration &&
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
                            .map { receive(owner, query, it) }
                            .onCompletion {
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
                                emit(Unit)
                            },
                    )
                } finally {
                    synchronized(lock) { if (reader === owner && owner.query === query) owner.query = null }
                }
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
                val source = records.firstOrNull { !it.isActive }
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
                if (previous == null) {
                    pinned = seed
                    restoreTaskLocked(seed)
                }
                received = seed
                val current = published.value as? NextPracticeUiState.Ready
                if (current == null || current.seed != seed || current.taskId != taskId) {
                    published.value =
                        NextPracticeUiState.Ready(seed, taskId, ++generation)
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
                owner.job.isActive && epoch == requestEpoch

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
            val source = seed.source
            val selection = source.selection
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

        private class Reader(
            val job: Job,
        ) {
            var query: Query? = null
            var receiving = false
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
            val generation: Long,
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
            val generation: Long,
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
