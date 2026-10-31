package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.CourseCompletionDeclaration
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclarationResult
import io.github.hangillab.hangil.core.domain.CourseCompletionOrigin
import io.github.hangillab.hangil.core.domain.CourseCompletionRepository
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.feature.home.SessionHistoryCourseCompletionUiState.FailureReason
import io.github.hangillab.hangil.feature.home.SessionHistoryCourseCompletionUiState.Feedback
import io.github.hangillab.hangil.feature.home.SessionHistoryCourseCompletionUiState.Token
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
import io.github.hangillab.hangil.feature.home.SessionHistoryCourseCompletionUiState as CompletionState

/** One expanded history reader owns its read receipts and explicit, revocable confirmation. */
internal class SessionHistoryCourseCompletionReader(
    private val lock: Any,
    private val repository: CourseCompletionRepository,
    private val allowed: () -> Boolean,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private var current: Reader? = null
    private var readerSerial = 0L
    private var querySerial = 0L
    private var revision = 0L
    private var actionSerial = 0L

    class Binding(
        val isCurrent: () -> Boolean,
        val denyAccess: () -> Unit,
        val canDeclare: () -> Boolean,
    )

    fun observe(
        source: PracticeSession,
        historyGeneration: Long,
        canKeepOpen: () -> Boolean,
        bind: () -> Binding?,
    ): Flow<CompletionState> =
        flow {
            coroutineScope {
                val host = checkNotNull(currentCoroutineContext()[Job])
                var initial: CompletionState? = null
                val owner =
                    synchronized(lock) {
                        val binding = bind()
                        when {
                            binding == null || !safe(canKeepOpen) -> {
                                initial = CompletionState.Unavailable(source, historyGeneration, Token(0, 0, 0))
                                null
                            }
                            !safe(allowed) -> {
                                binding.denyAccess()
                                initial = CompletionState.Locked(source, historyGeneration, Token(0, 0, 0))
                                null
                            }
                            else -> {
                                invalidate()
                                Reader(++readerSerial, source, historyGeneration, binding, canKeepOpen, host, this).also {
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

    fun request(token: Token): Boolean =
        synchronized(lock) {
            val owner = owner(token) ?: return@synchronized false
            if (owner.published.value !is CompletionState.Absent ||
                !canDeclare(owner) || !owner.received || owner.stored != null || owner.action != null
            ) {
                return@synchronized false
            }
            val action = Action(++actionSerial)
            owner.action = action
            owner.published.value = CompletionState.Confirming(owner.source, owner.generation, next(owner), action.nonce)
            true
        }

    fun confirm(
        token: Token,
        nonce: Long,
    ): Boolean {
        val job =
            synchronized(lock) {
                val owner = owner(token) ?: return@synchronized null
                val state =
                    owner.published.value as? CompletionState.Confirming ?: return@synchronized null
                val action =
                    owner.action?.takeIf {
                        it.nonce == nonce && state.nonce == nonce && it.job == null
                    } ?: return@synchronized null
                if (!canDeclare(owner)) {
                    // A reentrant live check may already have published Locked/Unavailable.
                    if (current === owner && !owner.revoked && owner.job.isActive) fail(owner, FailureReason.BLOCKED)
                    return@synchronized null
                }
                val time =
                    try {
                        nowEpochMillis()
                    } catch (_: Exception) {
                        0L
                    }
                if (time <= 0) {
                    fail(owner, FailureReason.INVALID_TIME)
                    return@synchronized null
                }
                action.time = time
                action.saving = true
                val work = owner.scope.launch(start = CoroutineStart.LAZY) { save(owner, action) }
                action.job = work
                owner.published.value = CompletionState.Saving(owner.source, owner.generation, next(owner), nonce)
                work
            } ?: return false
        job.start()
        return true
    }

    fun dismiss(
        token: Token,
        nonce: Long,
    ): Boolean =
        synchronized(lock) {
            val owner = owner(token) ?: return@synchronized false
            val action = owner.action?.takeIf { it.nonce == nonce && !it.saving } ?: return@synchronized false
            val state = owner.published.value as? CompletionState.Confirming ?: return@synchronized false
            if (state.nonce != nonce) return@synchronized false
            owner.action = null
            action.job?.cancel()
            publishReceipt(owner)
            true
        }

    fun retry(token: Token): Boolean =
        synchronized(lock) {
            val owner = owner(token) ?: return@synchronized false
            if (owner.published.value !is CompletionState.Failed) return@synchronized false
            cancelAction(owner)
            owner.query?.job?.cancel()
            owner.query = null
            owner.received = false
            loading(owner)
            owner.requests.value += 1
            true
        }

    private fun owner(token: Token): Reader? {
        val owner = current ?: return null
        if (token.readerId <= 0 || owner.published.value.token != token || !context(owner)) return null
        return owner
    }

    private fun safe(check: () -> Boolean): Boolean =
        try {
            check()
        } catch (_: Exception) {
            false
        }

    private fun context(owner: Reader): Boolean {
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

    private fun canDeclare(owner: Reader): Boolean = context(owner) && safe(owner.binding.canDeclare)

    private fun ownsQuery(
        owner: Reader,
        query: Query,
    ): Boolean = current === owner && !owner.revoked && owner.query === query && query.job.isActive && !query.terminal

    private suspend fun read(owner: Reader) {
        val queryJob = checkNotNull(currentCoroutineContext()[Job])
        val query =
            synchronized(lock) {
                if (!context(owner)) return
                Query(++querySerial, queryJob).also {
                    owner.query = it
                    owner.queryNonce = it.nonce
                    owner.received = false
                    loading(owner)
                }
            }
        try {
            try {
                repository.observe(owner.source.sessionId).collect { declaration ->
                    synchronized(lock) {
                        if (ownsQuery(owner, query) && context(owner)) receive(owner, declaration)
                    }
                }
                readFailed(owner, query, FailureReason.DECLARATION_STREAM_COMPLETED)
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                readFailed(owner, query, (failure as? InvalidDeclaration)?.reason ?: FailureReason.DECLARATION_READ)
            }
            awaitCancellation()
        } finally {
            synchronized(lock) { if (owner.query === query) owner.query = null }
        }
    }

    private fun valid(
        owner: Reader,
        declaration: CourseCompletionDeclaration,
    ): Boolean =
        declaration.source == owner.source && !declaration.source.isActive && declaration.version.isNotBlank() &&
            declaration.declaredAtEpochMillis > 0 && declaration.origin == CourseCompletionOrigin.USER_DECLARATION

    private fun receive(
        owner: Reader,
        declaration: CourseCompletionDeclaration?,
    ) {
        if (declaration != null && !valid(owner, declaration)) throw InvalidDeclaration(FailureReason.INVALID_DECLARATION)
        if (owner.known != null && declaration != owner.known) throw InvalidDeclaration(FailureReason.DECLARATION_CHANGED)
        if (owner.received && owner.stored == declaration) return
        cancelAction(owner)
        owner.received = true
        owner.stored = declaration
        if (declaration != null) owner.known = declaration
        publishReceipt(owner)
    }

    private fun publishReceipt(owner: Reader) {
        val stored = owner.stored
        owner.published.value =
            if (stored == null) {
                val allowed = canDeclare(owner)
                if (current !== owner || owner.revoked || !owner.job.isActive) return
                CompletionState.Absent(owner.source, owner.generation, next(owner), allowed)
            } else {
                CompletionState.Ready(owner.source, owner.generation, next(owner), stored, owner.feedback)
            }
    }

    private fun commit(
        owner: Reader,
        action: Action,
        job: Job,
    ): Boolean =
        synchronized(lock) {
            // Saving's UI acknowledgement changes only presentation, never the captured consent owner.
            owner.action === action && action.saving && action.job === job && job.isActive &&
                owner.received && owner.stored == null && canDeclare(owner)
        }

    private suspend fun save(
        owner: Reader,
        action: Action,
    ) {
        val job = checkNotNull(currentCoroutineContext()[Job])
        try {
            if (!commit(owner, action, job)) return
            val result = repository.declare(owner.source, action.time) { commit(owner, action, job) }
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (!commit(owner, action, job)) return@synchronized
                when (result) {
                    is CourseCompletionDeclarationResult.Saved -> accept(owner, action, result.declaration, Feedback.SAVED)
                    is CourseCompletionDeclarationResult.AlreadyDeclared ->
                        accept(owner, action, result.declaration, Feedback.ALREADY_DECLARED)
                    CourseCompletionDeclarationResult.MissingSession -> fail(owner, FailureReason.MISSING_SESSION)
                    CourseCompletionDeclarationResult.SourceChanged -> fail(owner, FailureReason.SOURCE_CHANGED)
                    CourseCompletionDeclarationResult.ActiveSession -> fail(owner, FailureReason.ACTIVE_SESSION)
                    CourseCompletionDeclarationResult.Blocked -> fail(owner, FailureReason.BLOCKED)
                    CourseCompletionDeclarationResult.InvalidTime -> fail(owner, FailureReason.INVALID_TIME)
                }
            }
        } catch (cancelled: CancellationException) {
            synchronized(lock) {
                if (owner.action === action && context(owner)) fail(owner, FailureReason.WRITE_UNCONFIRMED)
            }
            throw cancelled
        } catch (_: Exception) {
            synchronized(lock) {
                if (owner.action === action && context(owner)) fail(owner, FailureReason.WRITE_UNCONFIRMED)
            }
        } finally {
            synchronized(lock) {
                if (owner.action === action) {
                    owner.action = null
                    if (context(owner) && owner.published.value is CompletionState.Saving) {
                        fail(owner, FailureReason.WRITE_UNCONFIRMED)
                    }
                }
            }
        }
    }

    private fun accept(
        owner: Reader,
        action: Action,
        declaration: CourseCompletionDeclaration,
        feedback: Feedback,
    ) {
        if (!valid(owner, declaration) || (feedback == Feedback.SAVED && declaration.declaredAtEpochMillis != action.time)) {
            fail(owner, FailureReason.INVALID_SAVE_RESULT)
            return
        }
        owner.action = null
        owner.known = declaration
        owner.stored = declaration
        owner.received = true
        owner.feedback = feedback
        // An old in-flight null must not overwrite this durable acknowledgement.
        owner.query?.job?.cancel()
        owner.query = null
        publishReceipt(owner)
        owner.requests.value += 1
    }

    private fun readFailed(
        owner: Reader,
        query: Query,
        reason: FailureReason,
    ) = synchronized(lock) {
        if (ownsQuery(owner, query) && context(owner)) {
            query.terminal = true
            fail(owner, reason)
        }
    }

    private fun fail(
        owner: Reader,
        reason: FailureReason,
    ) {
        cancelAction(owner)
        owner.published.value = CompletionState.Failed(owner.source, owner.generation, next(owner), reason)
    }

    private fun cancelAction(owner: Reader) {
        val action = owner.action
        owner.action = null
        action?.job?.cancel()
    }

    private fun revoke(
        owner: Reader,
        locked: Boolean,
    ) {
        if (owner.revoked) return
        owner.revoked = true
        cancelAction(owner)
        owner.query?.job?.cancel()
        owner.query = null
        owner.published.value =
            if (locked) {
                CompletionState.Locked(owner.source, owner.generation, next(owner))
            } else {
                CompletionState.Unavailable(owner.source, owner.generation, next(owner))
            }
    }

    private fun loading(owner: Reader) {
        owner.published.value = CompletionState.Loading(owner.source, owner.generation, next(owner))
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
        val published =
            MutableStateFlow<CompletionState>(
                CompletionState.Loading(source, generation, Token(id, 0, 0)),
            )
        val requests = MutableStateFlow(0L)
        var query: Query? = null
        var queryNonce = 0L
        var revoked = false
        var received = false
        var stored: CourseCompletionDeclaration? = null
        var known: CourseCompletionDeclaration? = null
        var feedback: Feedback? = null
        var action: Action? = null
    }

    private class Query(
        val nonce: Long,
        val job: Job,
    ) {
        var terminal = false
    }

    private class Action(
        val nonce: Long,
    ) {
        var saving = false
        var time = 0L
        var job: Job? = null
    }

    private class InvalidDeclaration(
        val reason: FailureReason,
    ) : IllegalStateException()
}
