package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoPolicy
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
import io.github.hangillab.hangil.feature.home.SessionHistoryMemoUiState.FailureReason
import io.github.hangillab.hangil.feature.home.SessionHistoryMemoUiState.Token
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

/** Shares the parent's receipt lock, but owns only a cold, expanded-detail memo query. */
internal class SessionHistoryMemoReader(
    private val lock: Any,
    private val repository: SessionMemoRepository,
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
    ): Flow<SessionHistoryMemoUiState> =
        flow {
            coroutineScope {
                val hostJob = checkNotNull(currentCoroutineContext()[Job])
                var initial: SessionHistoryMemoUiState? = null
                val owner =
                    synchronized(lock) {
                        val binding = bind()
                        when {
                            binding == null || !keepsOpen(canKeepOpen) -> {
                                initial = SessionHistoryMemoUiState.Unavailable(source, historyGeneration, Token(0, 0, 0))
                                null
                            }
                            !allowed() -> {
                                binding.denyAccess()
                                initial = SessionHistoryMemoUiState.Locked(source, historyGeneration, Token(0, 0, 0))
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

    fun retry(token: Token): Boolean =
        synchronized(lock) {
            val owner = currentOwner(token) ?: return@synchronized false
            if (owner.published.value !is SessionHistoryMemoUiState.Failed) return@synchronized false
            owner.query?.job?.cancel()
            owner.query = null
            publishLoading(owner)
            owner.requests.value += 1
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
                repository.observe(owner.source.sessionId).collect { memo ->
                    synchronized(lock) {
                        if (currentQuery(owner, query) && ownsContext(owner)) receive(owner, memo)
                    }
                }
                fail(owner, query, FailureReason.MEMO_STREAM_COMPLETED)
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                fail(owner, query, if (failure is InvalidMemo) FailureReason.INVALID_MEMO else FailureReason.MEMO_READ)
            }
            // A finite/failed source stays terminal until explicit retry, never auto-recollects.
            awaitCancellation()
        } finally {
            synchronized(lock) { if (owner.query === query) owner.query = null }
        }
    }

    private fun receive(
        owner: Reader,
        memo: SessionMemo?,
    ) {
        if (memo != null &&
            (memo.sessionId != owner.source.sessionId || memo.version.isBlank() || !SessionMemoPolicy.isValidText(memo.text))
        ) {
            throw InvalidMemo()
        }
        owner.published.value =
            if (memo == null) {
                SessionHistoryMemoUiState.Empty(owner.source, owner.historyGeneration, nextToken(owner))
            } else {
                SessionHistoryMemoUiState.Ready(owner.source, owner.historyGeneration, nextToken(owner), memo)
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
                SessionHistoryMemoUiState.Failed(owner.source, owner.historyGeneration, nextToken(owner), reason)
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
                SessionHistoryMemoUiState.Locked(owner.source, owner.historyGeneration, next)
            } else {
                SessionHistoryMemoUiState.Unavailable(owner.source, owner.historyGeneration, next)
            }
    }

    private fun publishLoading(owner: Reader) {
        owner.published.value = SessionHistoryMemoUiState.Loading(owner.source, owner.historyGeneration, nextToken(owner))
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
            MutableStateFlow<SessionHistoryMemoUiState>(
                SessionHistoryMemoUiState.Loading(source, historyGeneration, Token(0, 0, 0)),
            )
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

    private class InvalidMemo : IllegalStateException()
}
