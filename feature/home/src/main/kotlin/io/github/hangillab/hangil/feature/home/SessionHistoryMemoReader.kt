package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.HistorySessionMemoWriter
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoPolicy
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import io.github.hangillab.hangil.feature.home.SessionHistoryMemoUiState.FailureReason
import io.github.hangillab.hangil.feature.home.SessionHistoryMemoUiState.Feedback
import io.github.hangillab.hangil.feature.home.SessionHistoryMemoUiState.Token
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import io.github.hangillab.hangil.feature.home.SessionHistoryMemoUiState as MemoState

/** Cold selected-source reads and explicit edit actions share the parent's receipt lock. */
internal class SessionHistoryMemoReader(
    private val lock: Any,
    private val repository: SessionMemoRepository,
    private val writer: HistorySessionMemoWriter,
    private val allowed: () -> Boolean,
) {
    private var current: Reader? = null
    private var readerSerial = 0L
    private var querySerial = 0L
    private var revision = 0L
    private var actionSerial = 0L

    class Binding(
        val isCurrent: () -> Boolean,
        val denyAccess: () -> Unit,
        val canWrite: () -> Boolean,
    )

    fun observe(
        source: PracticeSession,
        historyGeneration: Long,
        canKeepOpen: () -> Boolean,
        bind: () -> Binding?,
    ): Flow<MemoState> =
        flow {
            coroutineScope {
                val hostJob = checkNotNull(currentCoroutineContext()[Job])
                var initial: MemoState? = null
                val owner =
                    synchronized(lock) {
                        val binding = bind()
                        when {
                            binding == null || !safe(canKeepOpen) -> {
                                initial = MemoState.Unavailable(source, historyGeneration, Token(0, 0, 0))
                                null
                            }
                            !safe(allowed) -> {
                                binding.denyAccess()
                                initial = MemoState.Locked(source, historyGeneration, Token(0, 0, 0))
                                null
                            }
                            else -> {
                                invalidate()
                                Reader(++readerSerial, source, historyGeneration, binding, canKeepOpen, hostJob, this).also {
                                    current = it
                                    loading(it)
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

    fun invalidate(locked: Boolean = false) =
        synchronized(lock) {
            current?.let { revoke(it, locked) }
            Unit
        }

    fun canRead(token: Token): Boolean = synchronized(lock) { owner(token) != null }

    fun isEditing(): Boolean = synchronized(lock) { current?.let { ownsContext(it) && it.action != null } == true }

    fun beginEdit(token: Token): Boolean =
        synchronized(lock) {
            val owner = owner(token) ?: return@synchronized false
            if (owner.published.value !is MemoState.Ready || owner.action != null || !canWrite(owner)) return@synchronized false
            val memo = owner.stored ?: return@synchronized false
            val action = Action(++actionSerial, memo, memo.text)
            owner.action = action
            owner.published.value = MemoState.Editing(owner.source, owner.generation, next(owner), memo, action.draft, action.nonce)
            true
        }

    fun edit(
        token: Token,
        nonce: Long,
        text: String,
    ): Boolean =
        synchronized(lock) {
            val owner = owner(token) ?: return@synchronized false
            val action = editing(owner, nonce) ?: return@synchronized false
            if (!canWrite(owner)) {
                fail(owner, FailureReason.BLOCKED)
                return@synchronized false
            }
            action.draft = text
            // Local keystrokes keep one edit receipt; a stale save still must match the displayed draft.
            owner.published.value = MemoState.Editing(owner.source, owner.generation, token, action.memo, text, nonce)
            true
        }

    fun cancelEdit(
        token: Token,
        nonce: Long,
    ): Boolean =
        synchronized(lock) {
            val owner = owner(token) ?: return@synchronized false
            editing(owner, nonce) ?: return@synchronized false
            if (!canWrite(owner)) {
                fail(owner, FailureReason.BLOCKED)
                return@synchronized false
            }
            retireAction(owner)
            publishReceipt(owner)
            true
        }

    fun save(
        token: Token,
        nonce: Long,
        expectedText: String,
    ): Boolean {
        val job =
            synchronized(lock) {
                val owner = owner(token) ?: return@synchronized null
                val action = editing(owner, nonce) ?: return@synchronized null
                if (action.draft != expectedText) return@synchronized null
                if (!canWrite(owner)) {
                    fail(owner, FailureReason.BLOCKED)
                    return@synchronized null
                }
                if (!SessionMemoPolicy.isValidText(expectedText)) return@synchronized null
                action.saving = true
                val work = owner.scope.launch(start = CoroutineStart.LAZY) { write(owner, action) }
                action.job = work
                owner.published.value = MemoState.Saving(owner.source, owner.generation, next(owner), action.memo, action.draft, nonce)
                work
            } ?: return false
        job.start()
        return true
    }

    /** Only an explicit retry discards a retired draft and receives a fresh stored original. */
    fun retry(token: Token): Boolean =
        synchronized(lock) {
            val owner = owner(token) ?: return@synchronized false
            if (owner.published.value !is MemoState.Failed) return@synchronized false
            retireAction(owner)
            owner.expectedAck = null
            owner.pendingDraft = null
            refresh(owner)
            true
        }

    private fun editing(
        owner: Reader,
        nonce: Long,
    ): Action? {
        val action = owner.action ?: return null
        val state = owner.published.value as? MemoState.Editing ?: return null
        return action.takeIf { it.nonce == nonce && state.nonce == nonce && !it.saving && it.memo == owner.stored }
    }

    private fun owner(token: Token): Reader? {
        val owner = current ?: return null
        if (token.readerId <= 0 || owner.published.value.token != token || !ownsContext(owner)) return null
        return owner
    }

    private fun safe(check: () -> Boolean): Boolean =
        try {
            check()
        } catch (_: Exception) {
            false
        }

    private fun ownsContext(owner: Reader): Boolean {
        if (current !== owner || owner.revoked || !owner.job.isActive) return false
        if (!safe(owner.binding.isCurrent) || !safe(owner.canKeepOpen)) {
            revoke(owner, false)
            return false
        }
        if (!safe(allowed)) {
            owner.binding.denyAccess()
            revoke(owner, true)
            return false
        }
        return true
    }

    private fun canWrite(owner: Reader): Boolean =
        ownsContext(owner) && owner.received && owner.query?.let { currentQuery(owner, it) } == true && safe(owner.binding.canWrite)

    private fun currentQuery(
        owner: Reader,
        query: Query,
    ): Boolean = current === owner && !owner.revoked && owner.job.isActive && owner.query === query && query.job.isActive && !query.terminal

    private suspend fun read(owner: Reader) {
        val job = checkNotNull(currentCoroutineContext()[Job])
        val query =
            synchronized(lock) {
                if (!ownsContext(owner)) return
                Query(++querySerial, job).also {
                    owner.query = it
                    owner.queryNonce = it.nonce
                    loading(owner)
                }
            }
        try {
            try {
                repository.observe(owner.source.sessionId).collect { memo ->
                    synchronized(lock) { if (currentQuery(owner, query) && ownsContext(owner)) receive(owner, memo) }
                }
                readFailed(owner, query, FailureReason.MEMO_STREAM_COMPLETED)
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                readFailed(owner, query, if (failure is InvalidMemo) FailureReason.INVALID_MEMO else FailureReason.MEMO_READ)
            }
            awaitCancellation()
        } finally {
            synchronized(lock) {
                if (owner.query === query) {
                    owner.query = null
                    if (current === owner && !owner.revoked && owner.job.isActive) fail(owner, FailureReason.MEMO_READ)
                }
            }
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
        val previous = owner.stored
        val received = owner.received
        owner.stored = memo
        owner.received = true
        if (owner.failed) return
        val action = owner.action
        if (action != null) {
            if (memo == action.memo) return
            val draft = action.draft
            val saving = action.saving
            retireAction(owner)
            if (saving && memo != null && memo.text == draft) {
                publishReceipt(owner, Feedback.RELOADED)
            } else {
                fail(owner, FailureReason.CONFLICT, draft)
            }
            return
        }
        val ack = owner.expectedAck
        if (ack != null) {
            val draft = owner.pendingDraft
            owner.expectedAck = null
            owner.pendingDraft = null
            if (memo == ack) {
                publishReceipt(owner, Feedback.SAVED)
            } else {
                fail(owner, FailureReason.WRITE_UNCONFIRMED, draft)
            }
        } else if (!received || memo != previous || owner.published.value is MemoState.Loading) {
            publishReceipt(owner)
        }
    }

    private suspend fun write(
        owner: Reader,
        action: Action,
    ) {
        val job = checkNotNull(currentCoroutineContext()[Job])
        val canCommit = {
            synchronized(lock) { job.isActive && currentAction(owner, action, job) && canWrite(owner) && owner.stored == action.memo }
        }
        try {
            if (!canCommit()) {
                synchronized(lock) { if (currentAction(owner, action, job)) fail(owner, FailureReason.BLOCKED) }
                return
            }
            val result = writer.update(owner.source, action.memo, action.draft, canCommit)
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (!currentAction(owner, action, job) || !ownsContext(owner)) return@synchronized
                when (result) {
                    is SessionMemoWriteResult.Saved -> {
                        val memo = result.memo
                        if (memo.sessionId != owner.source.sessionId || memo.text != action.draft ||
                            memo.version.isBlank() || memo.version == action.memo.version
                        ) {
                            fail(owner, FailureReason.INVALID_SAVE_RESULT)
                        } else {
                            retireAction(owner, cancel = false)
                            owner.expectedAck = memo
                            owner.pendingDraft = action.draft
                            refresh(owner)
                        }
                    }
                    SessionMemoWriteResult.Blocked -> fail(owner, FailureReason.BLOCKED)
                    SessionMemoWriteResult.Conflict -> fail(owner, FailureReason.CONFLICT)
                    SessionMemoWriteResult.InvalidText -> fail(owner, FailureReason.INVALID_TEXT)
                    SessionMemoWriteResult.SessionChanged -> fail(owner, FailureReason.SESSION_CHANGED)
                    SessionMemoWriteResult.SessionActive -> fail(owner, FailureReason.SESSION_ACTIVE)
                    SessionMemoWriteResult.MissingSession -> fail(owner, FailureReason.MISSING_SESSION)
                    SessionMemoWriteResult.Deleted -> fail(owner, FailureReason.INVALID_SAVE_RESULT)
                }
            }
        } catch (cancelled: CancellationException) {
            synchronized(lock) { if (currentAction(owner, action, job) && ownsContext(owner)) fail(owner, FailureReason.WRITE_UNCONFIRMED) }
            throw cancelled
        } catch (_: Exception) {
            synchronized(lock) { if (currentAction(owner, action, job) && ownsContext(owner)) fail(owner, FailureReason.WRITE_UNCONFIRMED) }
        } finally {
            synchronized(lock) { if (currentAction(owner, action, job) && ownsContext(owner)) fail(owner, FailureReason.WRITE_UNCONFIRMED) }
        }
    }

    private fun currentAction(
        owner: Reader,
        action: Action,
        job: Job,
    ): Boolean = current === owner && !owner.revoked && owner.job.isActive && owner.action === action && action.job === job

    private fun readFailed(
        owner: Reader,
        query: Query,
        reason: FailureReason,
    ) = synchronized(lock) {
        if (currentQuery(owner, query) && ownsContext(owner)) {
            query.terminal = true
            fail(owner, reason)
        }
    }

    private fun fail(
        owner: Reader,
        reason: FailureReason,
        draft: String? = owner.action?.draft ?: owner.pendingDraft,
    ) {
        // A current denial still revokes the owner before a terminal failure is preserved.
        if (!ownsContext(owner) || owner.failed) return
        retireAction(owner)
        owner.failed = true
        owner.expectedAck = null
        owner.pendingDraft = null
        owner.published.value = MemoState.Failed(owner.source, owner.generation, next(owner), reason, draft)
    }

    private fun retireAction(
        owner: Reader,
        cancel: Boolean = true,
    ) {
        val action = owner.action
        owner.action = null
        val job = action?.job
        if (action != null) action.job = null
        if (cancel) job?.cancel()
    }

    private fun refresh(owner: Reader) {
        val previous = owner.query
        owner.query = null
        previous?.job?.cancel()
        owner.received = false
        owner.failed = false
        loading(owner)
        owner.requests.value++
    }

    private fun publishReceipt(
        owner: Reader,
        feedback: Feedback? = null,
    ) {
        val canEdit = safe(owner.binding.canWrite)
        if (!ownsContext(owner)) return
        val memo = owner.stored
        owner.published.value =
            if (memo == null) {
                MemoState.Empty(owner.source, owner.generation, next(owner))
            } else {
                MemoState.Ready(owner.source, owner.generation, next(owner), memo, canEdit, feedback)
            }
    }

    private fun revoke(
        owner: Reader,
        locked: Boolean,
    ) {
        if (owner.revoked) return
        owner.revoked = true
        retireAction(owner)
        owner.query?.job?.cancel()
        owner.query = null
        owner.stored = null
        owner.expectedAck = null
        owner.pendingDraft = null
        owner.received = false
        owner.published.value =
            if (locked) {
                MemoState.Locked(owner.source, owner.generation, next(owner))
            } else {
                MemoState.Unavailable(owner.source, owner.generation, next(owner))
            }
    }

    private fun loading(owner: Reader) {
        owner.published.value = MemoState.Loading(owner.source, owner.generation, next(owner))
    }

    private fun next(owner: Reader): Token = Token(owner.id, owner.queryNonce, ++revision)

    private class Reader(
        val id: Long,
        val source: PracticeSession,
        val generation: Long,
        val binding: Binding,
        val canKeepOpen: () -> Boolean,
        val job: Job,
        val scope: CoroutineScope,
    ) {
        val requests = MutableStateFlow(0L)
        val published = MutableStateFlow<MemoState>(MemoState.Loading(source, generation, Token(0, 0, 0)))
        var query: Query? = null
        var queryNonce = 0L
        var received = false
        var stored: SessionMemo? = null
        var expectedAck: SessionMemo? = null
        var pendingDraft: String? = null
        var action: Action? = null
        var failed = false
        var revoked = false
    }

    private class Action(
        val nonce: Long,
        val memo: SessionMemo,
        var draft: String,
    ) {
        var saving = false
        var job: Job? = null
    }

    private class Query(
        val nonce: Long,
        val job: Job,
    ) {
        var terminal = false
    }

    private class InvalidMemo : RuntimeException()
}
