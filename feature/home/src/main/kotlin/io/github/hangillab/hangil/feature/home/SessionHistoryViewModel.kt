package io.github.hangillab.hangil.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.SessionCourseSummary
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
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

/** Queries exist only while visible. Actions use the received source state, never a delayed UI copy. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionHistoryViewModel
    @Inject
    constructor(
        private val vehicle: VehicleGateway,
        private val restrictions: DrivingRestrictionsProvider,
        private val sessions: PracticeSessionRepository,
    ) : ViewModel() {
        private val lock = Any()
        private val published = MutableStateFlow<SessionHistoryUiState>(SessionHistoryUiState.Loading)
        private val requests = MutableStateFlow(0L)
        private var reader: Reader? = null
        private var epoch = 0L
        private var generation = 0L
        private var selectedId: String? = null
        private var selectionLost = false
        private var ended: List<PracticeSession>? = null

        val uiState =
            flow {
                coroutineScope {
                    val owner = Reader(checkNotNull(currentCoroutineContext()[Job]))
                    synchronized(lock) {
                        reader = owner
                        ended = null
                        publishLocked(SessionHistoryUiState.Loading)
                    }
                    val queryJob =
                        launch {
                            requests.flatMapLatest { watchAccess(owner, it) }.collect()
                        }
                    try {
                        emitAll(published)
                    } finally {
                        synchronized(lock) {
                            if (reader === owner) {
                                owner.query = null
                                reader = null
                                ended = null
                                publishLocked(SessionHistoryUiState.Loading)
                            }
                        }
                        queryJob.cancel()
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), SessionHistoryUiState.Loading)

        fun canRead(): Boolean = allowsPracticeInteraction(vehicle.state.value, restrictions.restrictions.value)

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

        fun retry(): Boolean =
            synchronized(lock) {
                val owner = reader?.takeIf { it.job.isActive } ?: return@synchronized false
                if (!canRead()) return@synchronized false
                epoch++
                owner.query = null
                ended = null
                publishLocked(SessionHistoryUiState.Loading)
                requests.value = epoch
                true
            }

        /** Explicitly accepts the latest received list after a selected record disappeared. */
        fun resetToLatest(expectedGeneration: Long): Boolean =
            synchronized(lock) {
                val owner = reader ?: return@synchronized false
                val query = owner.query ?: return@synchronized false
                if (!currentQuery(owner, query) || !canRead()) return@synchronized false
                val changed = published.value as? SessionHistoryUiState.SelectionChanged ?: return@synchronized false
                if (changed.generation != expectedGeneration) return@synchronized false
                val records = ended ?: return@synchronized false
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
                        if (allowsPracticeInteraction(currentVehicle, currentRestrictions)) Access.Allowed else Access.Locked
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
                        if (reader === owner && owner.query === query) owner.query = null
                    }
                }
            }

        private fun publishAccess(
            owner: Reader,
            requestEpoch: Long,
            access: Access,
        ) = synchronized(lock) {
            if (!currentReader(owner, requestEpoch)) return@synchronized
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
            val finished = snapshot.filterNot { it.isActive }
            ended = finished
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
            ended = null
            publishLocked(SessionHistoryUiState.Failed(reason))
        }

        private fun publishSelectionLocked(records: List<PracticeSession>) {
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
            nextGenerationLocked()
            published.value = state
        }

        private fun nextGenerationLocked(): Long = ++generation

        private fun currentReader(
            owner: Reader,
            requestEpoch: Long,
        ): Boolean = reader === owner && owner.job.isActive && requestEpoch == epoch

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

        private class Reader(
            val job: Job,
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
