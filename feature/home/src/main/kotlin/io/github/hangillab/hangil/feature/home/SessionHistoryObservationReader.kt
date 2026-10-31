package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationRepository
import io.github.hangillab.hangil.feature.home.SessionHistoryObservationUiState.FailureReason
import io.github.hangillab.hangil.feature.home.SessionHistoryObservationUiState.Token
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** Shares the parent's receipt lock, but owns only a cold, expanded-detail observation query. */
internal class SessionHistoryObservationReader(
    private val lock: Any,
    private val repository: VehicleObservationRepository,
    private val allowed: () -> Boolean,
) {
    private var current: Reader? = null
    private var readerSerial = 0L
    private var querySerial = 0L
    private var revision = 0L

    class Binding(
        val isCurrent: () -> Boolean,
        val denyAccess: () -> Unit,
    )

    fun observe(
        source: PracticeSession,
        historyGeneration: Long,
        canKeepOpen: () -> Boolean,
        bind: () -> Binding?,
    ): Flow<SessionHistoryObservationUiState> =
        flow {
            coroutineScope {
                val hostJob = checkNotNull(currentCoroutineContext()[Job])
                var initial: SessionHistoryObservationUiState? = null
                val owner =
                    synchronized(lock) {
                        val binding = bind()
                        when {
                            binding == null || !keepsOpen(canKeepOpen) -> {
                                initial = SessionHistoryObservationUiState.Unavailable(source, historyGeneration, Token(0, 0, 0))
                                null
                            }
                            !allowed() -> {
                                binding.denyAccess()
                                initial = SessionHistoryObservationUiState.Locked(source, historyGeneration, Token(0, 0, 0))
                                null
                            }
                            else -> {
                                invalidate()
                                Reader(
                                    ++readerSerial,
                                    source,
                                    historyGeneration,
                                    binding,
                                    canKeepOpen,
                                    hostJob,
                                ).also {
                                    current = it
                                    publishLoading(it)
                                }
                            }
                        }
                    }
                if (owner == null) {
                    emit(checkNotNull(initial))
                    awaitCancellation()
                }
                val worker = launch { owner.requests.collectLatest { read(owner) } }
                try {
                    emitAll(owner.published)
                } finally {
                    synchronized(lock) {
                        if (current === owner) {
                            revoke(owner, false)
                            current = null
                        }
                    }
                    worker.cancel()
                }
            }
        }.buffer(0)

    /** Parent publishes a changed/failed/locked context before any downstream UI delivery. */
    fun invalidate(locked: Boolean = false) =
        synchronized(lock) {
            current?.let { revoke(it, locked) }
            Unit
        }

    fun canRead(token: Token): Boolean = synchronized(lock) { currentOwner(token) != null }

    fun previous(
        token: Token,
        rowId: Long,
    ): Boolean = move(token, rowId, -1)

    fun next(
        token: Token,
        rowId: Long,
    ): Boolean = move(token, rowId, 1)

    fun retry(
        token: Token,
        rowId: Long?,
    ): Boolean =
        synchronized(lock) {
            val owner = currentOwner(token) ?: return@synchronized false
            val failed = owner.published.value as? SessionHistoryObservationUiState.Failed ?: return@synchronized false
            if (failed.selectedRowId != rowId) return@synchronized false
            owner.query?.job?.cancel()
            owner.query = null
            owner.selectedId = null
            publishLoading(owner)
            owner.requests.value += 1
            true
        }

    private fun move(
        token: Token,
        rowId: Long,
        offset: Int,
    ): Boolean =
        synchronized(lock) {
            val owner = currentOwner(token) ?: return@synchronized false
            val query = owner.query ?: return@synchronized false
            if (!query.job.isActive || query.terminal) return@synchronized false
            val ready = owner.published.value as? SessionHistoryObservationUiState.Ready ?: return@synchronized false
            if (ready.row.id != rowId) return@synchronized false
            val index = ready.index + offset
            if (index !in ready.observations.indices) return@synchronized false
            owner.selectedId = ready.observations[index].id
            owner.published.value = ready.copy(token = nextToken(owner), index = index)
            true
        }

    private fun currentOwner(expected: Token): Reader? {
        val owner = current ?: return null
        if (expected.readerId <= 0 || owner.published.value.token != expected || owner.revoked || !owner.job.isActive) return null
        if (!owner.binding.isCurrent() || !keepsOpen(owner.canKeepOpen)) {
            revoke(owner, false)
            return null
        }
        if (!allowed()) {
            // The exact current token observed denial. Old tokens cannot revoke a newer context.
            owner.binding.denyAccess()
            revoke(owner, true)
            return null
        }
        return owner
    }

    private fun keepsOpen(check: () -> Boolean): Boolean =
        try {
            check()
        } catch (_: Exception) {
            false
        }

    private fun currentQuery(
        owner: Reader,
        query: Query,
    ): Boolean = current === owner && !owner.revoked && owner.job.isActive && owner.query === query && query.job.isActive && !query.terminal

    private fun ownsContext(owner: Reader): Boolean {
        if (current !== owner || owner.revoked || !owner.job.isActive) return false
        if (!owner.binding.isCurrent() || !keepsOpen(owner.canKeepOpen)) {
            revoke(owner, false)
            return false
        }
        if (!allowed()) {
            owner.binding.denyAccess()
            revoke(owner, true)
            return false
        }
        return true
    }

    private suspend fun read(owner: Reader) {
        val queryJob = checkNotNull(currentCoroutineContext()[Job])
        val query =
            synchronized(lock) {
                if (!ownsContext(owner)) return
                Query(++querySerial, queryJob).also {
                    owner.query = it
                    owner.queryNonce = it.nonce
                    publishLoading(owner)
                }
            }
        try {
            try {
                repository.observe(owner.source.sessionId).collect { rows ->
                    synchronized(lock) {
                        if (currentQuery(owner, query) && ownsContext(owner)) receive(owner, rows)
                    }
                }
                fail(owner, query, FailureReason.OBSERVATION_STREAM_COMPLETED)
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                fail(owner, query, (failure as? InvalidRows)?.reason ?: FailureReason.OBSERVATION_READ)
            }
            // A finite/failed source stays terminal until explicit retry, never auto-recollects.
            awaitCancellation()
        } finally {
            synchronized(lock) { if (owner.query === query) owner.query = null }
        }
    }

    private fun receive(
        owner: Reader,
        rows: List<SessionVehicleObservation>,
    ) {
        var previousId = 0L
        for (row in rows) {
            if (row.sessionId != owner.source.sessionId || row.id <= previousId || row.observation.observedAtEpochMillis <= 0) {
                throw InvalidRows(FailureReason.INVALID_OBSERVATIONS)
            }
            previousId = row.id
        }
        val snapshot = buildList { addAll(rows) }
        val selected = owner.selectedId
        if (selected != null && snapshot.none { it.id == selected }) throw InvalidRows(FailureReason.SELECTED_ROW_MISSING)
        if (snapshot.isEmpty()) {
            owner.published.value = SessionHistoryObservationUiState.Empty(owner.source, owner.historyGeneration, nextToken(owner))
        } else {
            val index = if (selected == null) snapshot.lastIndex else snapshot.indexOfFirst { it.id == selected }
            owner.selectedId = snapshot[index].id
            owner.published.value =
                SessionHistoryObservationUiState.Ready(owner.source, owner.historyGeneration, nextToken(owner), snapshot, index)
        }
    }

    private fun fail(
        owner: Reader,
        query: Query,
        reason: FailureReason,
    ) = synchronized(lock) {
        if (currentQuery(owner, query) && ownsContext(owner)) {
            query.terminal = true
            owner.published.value =
                SessionHistoryObservationUiState.Failed(owner.source, owner.historyGeneration, nextToken(owner), reason, owner.selectedId)
        }
    }

    private fun revoke(
        owner: Reader,
        locked: Boolean,
    ) {
        if (owner.revoked) return
        owner.revoked = true
        owner.query?.job?.cancel()
        owner.query = null
        val next = nextToken(owner)
        owner.published.value =
            if (locked) {
                SessionHistoryObservationUiState.Locked(owner.source, owner.historyGeneration, next)
            } else {
                SessionHistoryObservationUiState.Unavailable(owner.source, owner.historyGeneration, next)
            }
    }

    private fun publishLoading(owner: Reader) {
        owner.published.value = SessionHistoryObservationUiState.Loading(owner.source, owner.historyGeneration, nextToken(owner))
    }

    private fun nextToken(owner: Reader): Token = Token(owner.id, owner.queryNonce, ++revision)

    private class Reader(
        val id: Long,
        val source: PracticeSession,
        val historyGeneration: Long,
        val binding: Binding,
        val canKeepOpen: () -> Boolean,
        val job: Job,
    ) {
        val requests = MutableStateFlow(0L)
        val published =
            MutableStateFlow<SessionHistoryObservationUiState>(
                SessionHistoryObservationUiState.Loading(source, historyGeneration, Token(0, 0, 0)),
            )
        var selectedId: Long? = null
        var query: Query? = null
        var queryNonce = 0L
        var revoked = false
    }

    private class Query(
        val nonce: Long,
        val job: Job,
    ) {
        var terminal = false
    }

    private class InvalidRows(
        val reason: FailureReason,
    ) : IllegalStateException()
}
