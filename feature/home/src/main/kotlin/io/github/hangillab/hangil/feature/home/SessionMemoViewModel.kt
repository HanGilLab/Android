package io.github.hangillab.hangil.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoPolicy
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/** Only a visible, started editor owns database subscriptions; drafts remain in the VM. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionMemoViewModel
    @Inject
    constructor(
        private val vehicle: VehicleGateway,
        private val restrictions: DrivingRestrictionsProvider,
        private val sessions: PracticeSessionRepository,
        private val memos: SessionMemoRepository,
    ) : ViewModel() {
        private val entries = mutableMapOf<String, Entry>()
        private val target = MutableStateFlow<Reader?>(null)
        private val mutation = Mutex()
        private val actionNonce = AtomicLong()

        fun observe(sessionId: String): Flow<SessionMemoUiState> =
            channelFlow {
                val entry = entry(sessionId)
                val reader = Reader(sessionId, checkNotNull(currentCoroutineContext()[Job]))
                entry.state.update { it.copy(load = SessionMemoUiState.Load.Loading, failure = null) }
                target.value = reader
                val publisher = launch { entry.state.collect { send(it) } }
                try {
                    target
                        .flatMapLatest { current ->
                            if (current === reader) {
                                entry.retry.flatMapLatest { epoch ->
                                    observeAttempt(sessionId).map { epoch to it }
                                }
                            } else {
                                flowOf(entry.retry.value to ReadState.Changed)
                            }
                        }.buffer(0)
                        .collect { (epoch, read) ->
                            if (target.value === reader && epoch == entry.readEpoch.get()) applyRead(entry, reader, read)
                        }
                } finally {
                    reader.active.set(false)
                    reader.revision.incrementAndGet()
                    target.compareAndSet(reader, null)
                    publisher.cancelAndJoin()
                }
            }.buffer(0)

        fun canRead(): Boolean = allowsPracticeInteraction(vehicle.state.value, restrictions.restrictions.value)

        fun beginEdit(sessionId: String): Boolean {
            val entry = currentEntry(sessionId) ?: return false
            val state = entry.state.value
            if (!state.canEdit || state.editing || state.needsReload || state.memo == null) return false
            entry.state.value = state.copy(editing = true, draft = state.memo.text, expectedVersion = state.memo.version, feedback = null)
            return true
        }

        fun edit(
            sessionId: String,
            text: String,
        ): Boolean {
            val entry = currentEntry(sessionId) ?: return false
            val state = entry.state.value
            if (!state.canEdit || !state.editing) return false
            entry.state.value = state.copy(draft = text, feedback = if (state.needsReload) state.feedback else null)
            return true
        }

        fun cancelEdit(sessionId: String): Boolean {
            val entry = currentEntry(sessionId) ?: return false
            val state = entry.state.value
            val memo = state.memo ?: return false
            if (!state.canEdit || !state.editing || state.needsReload) return false
            entry.state.value =
                state.copy(
                    draft = memo.text,
                    expectedVersion = memo.version,
                    editing = false,
                    needsReload = false,
                    feedback = null,
                )
            return true
        }

        fun save(sessionId: String): Boolean {
            val entry = currentEntry(sessionId) ?: return false
            val state = entry.state.value
            if (!state.canEdit || !state.editing || state.needsReload) return false
            if (!SessionMemoPolicy.isValidText(state.draft)) {
                entry.state.value = state.copy(feedback = SessionMemoUiState.Feedback.InvalidText)
                return false
            }
            return mutate(entry, SessionMemoUiState.Operation.Saving) { canCommit ->
                memos.save(sessionId, state.draft, state.expectedVersion, canCommit)
            }
        }

        /** The confirmation belongs to this immutable version, never a subsequently displayed note. */
        fun delete(
            sessionId: String,
            confirmedVersion: String,
        ): Boolean {
            val entry = currentEntry(sessionId) ?: return false
            val state = entry.state.value
            if (!state.canDelete) return false
            if (confirmedVersion != state.memo?.version || confirmedVersion != state.expectedVersion) {
                entry.state.value = state.copy(needsReload = true, feedback = SessionMemoUiState.Feedback.Conflict)
                return false
            }
            return mutate(entry, SessionMemoUiState.Operation.Deleting) { canCommit ->
                memos.delete(sessionId, confirmedVersion, canCommit)
            }
        }

        /** Retains the draft and its optimistic token, including after a read error. */
        fun retry(sessionId: String): Boolean = refresh(sessionId, discardDraft = false)

        /** Explicitly discards the draft only after a fresh successful read. */
        fun reload(sessionId: String): Boolean = refresh(sessionId, discardDraft = true)

        private fun refresh(
            sessionId: String,
            discardDraft: Boolean,
        ): Boolean {
            val reader = target.value?.takeIf { it.sessionId == sessionId && it.isAlive() } ?: return false
            val entry = entry(sessionId)
            if (!canRead() || mutation.isLocked || entry.state.value.operation != SessionMemoUiState.Operation.Idle) return false
            if (discardDraft) entry.resetOnRead.set(true)
            restart(entry, reader)
            return true
        }

        private fun restart(
            entry: Entry,
            reader: Reader,
        ) {
            val epoch = entry.readEpoch.incrementAndGet()
            reader.revision.incrementAndGet()
            entry.state.update { it.copy(load = SessionMemoUiState.Load.Loading, failure = null) }
            entry.retry.value = epoch
        }

        private fun currentEntry(sessionId: String): Entry? {
            val reader = target.value ?: return null
            if (!reader.isAlive() || reader.sessionId != sessionId || !canRead()) return null
            return entry(sessionId).takeIf { it.state.value.load == SessionMemoUiState.Load.Ready }
        }

        private fun mutate(
            entry: Entry,
            operation: SessionMemoUiState.Operation,
            action: suspend (() -> Boolean) -> SessionMemoWriteResult,
        ): Boolean {
            val reader = target.value ?: return false
            val sessionId = entry.state.value.sessionId
            if (currentEntry(sessionId) !== entry || !mutation.tryLock()) return false
            val revision = reader.revision.get()
            val nonce = actionNonce.incrementAndGet()
            entry.state.update { it.copy(operation = operation, feedback = null) }
            val canCommit = {
                target.value === reader && reader.isAlive() && reader.revision.get() == revision &&
                    actionNonce.get() == nonce && canRead() && entry.state.value.load == SessionMemoUiState.Load.Ready
            }
            viewModelScope.launch {
                try {
                    if (!canCommit()) {
                        entry.state.update { it.copy(feedback = SessionMemoUiState.Feedback.Blocked) }
                    } else {
                        val result = action(canCommit)
                        if (canCommit()) {
                            applyResult(entry, reader, operation, result)
                        } else {
                            entry.state.update { it.copy(feedback = SessionMemoUiState.Feedback.Blocked) }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    if (currentCoroutineContext().isActive) writeFailed(entry, operation)
                    throw cancelled
                } catch (_: Exception) {
                    writeFailed(entry, operation)
                } finally {
                    entry.state.update { it.copy(operation = SessionMemoUiState.Operation.Idle) }
                    mutation.unlock()
                }
            }
            return true
        }

        private fun applyResult(
            entry: Entry,
            reader: Reader,
            operation: SessionMemoUiState.Operation,
            result: SessionMemoWriteResult,
        ) {
            when (result) {
                is SessionMemoWriteResult.Saved -> {
                    val saved = result.memo
                    if (
                        operation != SessionMemoUiState.Operation.Saving ||
                        !validMemo(reader.sessionId, saved) || saved.text != entry.state.value.draft
                    ) {
                        writeFailed(entry, operation)
                        return
                    }
                    // Reject in-flight emissions from the old query before publishing the authoritative result.
                    val epoch = entry.readEpoch.incrementAndGet()
                    reader.revision.incrementAndGet()
                    entry.state.update {
                        it.copy(
                            memo = saved,
                            draft = saved.text,
                            expectedVersion = saved.version,
                            editing = false,
                            needsReload = false,
                            feedback = SessionMemoUiState.Feedback.Saved,
                            load = SessionMemoUiState.Load.Loading,
                        )
                    }
                    entry.retry.value = epoch
                }
                SessionMemoWriteResult.Deleted -> {
                    if (operation != SessionMemoUiState.Operation.Deleting) {
                        writeFailed(entry, operation)
                        return
                    }
                    val epoch = entry.readEpoch.incrementAndGet()
                    reader.revision.incrementAndGet()
                    entry.state.update {
                        it.copy(
                            memo = null,
                            draft = "",
                            expectedVersion = null,
                            editing = true,
                            needsReload = false,
                            feedback = SessionMemoUiState.Feedback.Deleted,
                            load = SessionMemoUiState.Load.Loading,
                        )
                    }
                    entry.retry.value = epoch
                }
                SessionMemoWriteResult.Conflict ->
                    entry.state.update {
                        it.copy(needsReload = true, feedback = SessionMemoUiState.Feedback.Conflict)
                    }
                SessionMemoWriteResult.Blocked -> entry.state.update { it.copy(feedback = SessionMemoUiState.Feedback.Blocked) }
                SessionMemoWriteResult.InvalidText -> entry.state.update { it.copy(feedback = SessionMemoUiState.Feedback.InvalidText) }
                SessionMemoWriteResult.SessionChanged -> targetChanged(entry, SessionMemoUiState.Feedback.SessionChanged)
                SessionMemoWriteResult.MissingSession -> targetChanged(entry, SessionMemoUiState.Feedback.MissingSession)
                SessionMemoWriteResult.SessionActive -> targetChanged(entry, SessionMemoUiState.Feedback.SessionActive)
            }
        }

        private fun targetChanged(
            entry: Entry,
            feedback: SessionMemoUiState.Feedback,
        ) {
            target.value?.revision?.incrementAndGet()
            entry.state.update { it.copy(load = SessionMemoUiState.Load.SessionChanged, feedback = feedback) }
        }

        private fun writeFailed(
            entry: Entry,
            operation: SessionMemoUiState.Operation,
        ) {
            entry.state.update {
                it.copy(
                    feedback =
                        if (operation == SessionMemoUiState.Operation.Deleting) {
                            SessionMemoUiState.Feedback.DeleteFailed
                        } else {
                            SessionMemoUiState.Feedback.SaveFailed
                        },
                )
            }
        }

        private fun applyRead(
            entry: Entry,
            reader: Reader,
            read: ReadState,
        ) {
            when (read) {
                is ReadState.Ready -> {
                    val reset = entry.resetOnRead.getAndSet(false)
                    val initialize = !entry.initialized.getAndSet(true) || reset
                    entry.state.update { state ->
                        if (initialize || !state.editing) {
                            state.copy(
                                load = SessionMemoUiState.Load.Ready,
                                memo = read.memo,
                                draft = read.memo?.text.orEmpty(),
                                editing = read.memo == null,
                                expectedVersion = read.memo?.version,
                                needsReload = false,
                                feedback = if (reset) null else state.feedback,
                                failure = null,
                            )
                        } else {
                            val conflict =
                                state.operation == SessionMemoUiState.Operation.Idle &&
                                    read.memo?.version != state.expectedVersion
                            state.copy(
                                load = SessionMemoUiState.Load.Ready,
                                memo = read.memo,
                                needsReload = state.needsReload || conflict,
                                feedback = if (conflict) SessionMemoUiState.Feedback.Conflict else state.feedback,
                                failure = null,
                            )
                        }
                    }
                }
                else -> {
                    reader.revision.incrementAndGet()
                    val load =
                        when (read) {
                            ReadState.Loading -> SessionMemoUiState.Load.Loading
                            ReadState.Locked -> SessionMemoUiState.Load.Locked
                            ReadState.Changed -> SessionMemoUiState.Load.SessionChanged
                            is ReadState.Failed -> SessionMemoUiState.Load.Failed
                            is ReadState.Ready -> error("handled above")
                        }
                    entry.state.update { it.copy(load = load, failure = (read as? ReadState.Failed)?.reason) }
                }
            }
        }

        private fun observeAttempt(sessionId: String): Flow<ReadState> =
            flow {
                emitAll(
                    combine(vehicle.state, restrictions.restrictions) { currentVehicle, currentRestrictions ->
                        if (allowsPracticeInteraction(currentVehicle, currentRestrictions)) ReadState.Loading else ReadState.Locked
                    },
                )
            }.map<_, ReadState> { it }
                .distinctUntilChanged()
                .onCompletion { cause ->
                    if (cause == null) emit(ReadState.Failed(SessionMemoUiState.FailureReason.AccessStreamCompleted))
                }.catch {
                    currentCoroutineContext().ensureActive()
                    emit(ReadState.Failed(SessionMemoUiState.FailureReason.AccessRead))
                }.flatMapLatest { access ->
                    if (access == ReadState.Loading) observeSession(sessionId) else flowOf(access)
                }.buffer(0)

        private fun observeSession(sessionId: String): Flow<ReadState> =
            flow { emitAll(sessions.observeSessions()) }
                .map { records -> records.firstOrNull { !it.isActive }?.sessionId == sessionId }
                .distinctUntilChanged()
                .map<Boolean, ReadState> { if (it) ReadState.Loading else ReadState.Changed }
                .onCompletion { cause ->
                    if (cause == null) emit(ReadState.Failed(SessionMemoUiState.FailureReason.SessionStreamCompleted))
                }.catch {
                    currentCoroutineContext().ensureActive()
                    emit(ReadState.Failed(SessionMemoUiState.FailureReason.SessionRead))
                }.flatMapLatest { state -> if (state == ReadState.Loading) observeMemo(sessionId) else flowOf(state) }
                .onStart { emit(ReadState.Loading) }
                .buffer(0)

        private fun observeMemo(sessionId: String): Flow<ReadState> =
            flow { emitAll(memos.observe(sessionId)) }
                .map<_, ReadState> { memo ->
                    if (memo != null && !validMemo(sessionId, memo)) throw InvalidMemo()
                    ReadState.Ready(memo)
                }.onStart { emit(ReadState.Loading) }
                .onCompletion { cause -> if (cause == null) emit(ReadState.Failed(SessionMemoUiState.FailureReason.MemoStreamCompleted)) }
                .catch { failure ->
                    currentCoroutineContext().ensureActive()
                    emit(
                        ReadState.Failed(
                            if (failure is InvalidMemo) {
                                SessionMemoUiState.FailureReason.InvalidMemo
                            } else {
                                SessionMemoUiState.FailureReason.MemoRead
                            },
                        ),
                    )
                }

        private fun validMemo(
            sessionId: String,
            memo: SessionMemo,
        ): Boolean = memo.sessionId == sessionId && memo.version.isNotBlank() && SessionMemoPolicy.isValidText(memo.text)

        private fun entry(sessionId: String): Entry = synchronized(entries) { entries.getOrPut(sessionId) { Entry(sessionId) } }

        private class Entry(
            sessionId: String,
        ) {
            val state = MutableStateFlow(SessionMemoUiState(sessionId))
            val retry = MutableStateFlow(0L)
            val readEpoch = AtomicLong()
            val initialized = AtomicBoolean(false)
            val resetOnRead = AtomicBoolean(false)
        }

        private class Reader(
            val sessionId: String,
            private val job: Job,
        ) {
            val active = AtomicBoolean(true)
            val revision = AtomicLong()

            fun isAlive(): Boolean = active.get() && job.isActive
        }

        private sealed interface ReadState {
            data object Loading : ReadState

            data object Locked : ReadState

            data object Changed : ReadState

            data class Ready(
                val memo: SessionMemo?,
            ) : ReadState

            data class Failed(
                val reason: SessionMemoUiState.FailureReason,
            ) : ReadState
        }

        private class InvalidMemo : IllegalStateException()
    }
