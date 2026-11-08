package io.github.hangillab.hangil.feature.home

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.NextPracticeSeed
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.SessionMemoPolicy
import io.github.hangillab.hangil.core.domain.SessionReviewRoad
import io.github.hangillab.hangil.core.domain.SessionReviewRoadRepository
import io.github.hangillab.hangil.core.domain.SessionReviewRoadWriteResult
import io.github.hangillab.hangil.core.domain.SessionReviewSample
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject
import io.github.hangillab.hangil.feature.home.SessionReviewRoadUiState as RoadState

/** A visible exact ended source owns one draft and its explicit compare-and-set write. */
@HiltViewModel
class SessionReviewRoadViewModel
    @Inject
    constructor(
        private val vehicle: VehicleGateway,
        private val restrictions: DrivingRestrictionsProvider,
        private val sessions: PracticeSessionRepository,
        private val reviews: SessionReviewRoadRepository,
        private val catalog: PracticeCatalog,
    ) : ViewModel() {
        private val lock = Any()
        private var serial = 0L
        private var revision = 0L
        private var actionSerial = 0L
        private var current: Reader? = null
        private val signals = MutableStateFlow(0L)
        val inputChanges: Flow<Unit> =
            merge(vehicle.state.map { Unit }, restrictions.restrictions.map { Unit }, signals.map { Unit })

        fun observe(
            source: PracticeSession,
            canKeepOpen: () -> Boolean,
        ): Flow<RoadState> =
            flow {
                coroutineScope {
                    val hostJob = checkNotNull(currentCoroutineContext()[Job])
                    val owner =
                        synchronized(lock) {
                            current?.let { revoke(it, false) }
                            Reader(++serial, source, canKeepOpen, hostJob, this).also {
                                current = it
                                loading(it)
                            }
                        }
                    val queries = launch { owner.requests.collectLatest { read(owner) } }
                    owner.watch = launch { watch(owner) }
                    try {
                        emitAll(owner.published)
                    } finally {
                        synchronized(lock) {
                            if (current === owner) {
                                revoke(owner, false)
                                current = null
                            }
                        }
                        queries.cancel()
                        owner.watch?.cancel()
                    }
                }
            }.buffer(0)

        fun canRead(token: RoadState.Token): Boolean = synchronized(lock) { owner(token) != null }

        fun select(
            token: RoadState.Token,
            roadId: String,
        ): Boolean =
            synchronized(lock) {
                val owner = owner(token) ?: return@synchronized false
                if (!editable(owner) || owner.edit != null || owner.confirmation != null || owner.retainedMemo != null) {
                    return@synchronized false
                }
                val road = owner.candidates.singleOrNull { it.id == roadId } ?: return@synchronized false
                owner.draft = road
                owner.feedback = null
                publish(owner)
                true
            }

        fun cancel(token: RoadState.Token): Boolean =
            synchronized(lock) {
                val owner = owner(token) ?: return@synchronized false
                if (!owner.received || owner.action != null || owner.edit != null || owner.confirmation != null ||
                    owner.retainedMemo != null
                ) {
                    return@synchronized false
                }
                owner.draft = owner.stored?.road
                owner.feedback = null
                publish(owner)
                true
            }

        fun save(token: RoadState.Token): Boolean = write(token, clear = false)

        fun clear(token: RoadState.Token): Boolean = write(token, clear = true)

        fun beginMemo(token: RoadState.Token): Boolean =
            synchronized(lock) {
                val owner = owner(token) ?: return@synchronized false
                val stored = owner.stored ?: return@synchronized false
                if (!editable(owner) || owner.edit != null || owner.confirmation != null || owner.retainedMemo != null ||
                    owner.draft != stored.road
                ) {
                    return@synchronized false
                }
                owner.edit = RoadState.Edit(++actionSerial, stored.memoText, stored)
                owner.feedback = null
                publish(owner)
                true
            }

        fun resumeMemo(token: RoadState.Token): Boolean =
            synchronized(lock) {
                val owner = owner(token) ?: return@synchronized false
                val retained = owner.retainedMemo ?: return@synchronized false
                val stored = owner.stored ?: return@synchronized false
                if (!editable(owner) || owner.edit != null || owner.confirmation != null || stored.road != retained.expectedCurrent.road) {
                    return@synchronized false
                }
                // This explicit new intent accepts the freshly read full current value, never an old Save.
                owner.edit = RoadState.Edit(++actionSerial, retained.text, stored)
                owner.retainedMemo = null
                owner.draft = stored.road
                owner.feedback = null
                publish(owner)
                true
            }

        fun discardMemoDraft(token: RoadState.Token): Boolean =
            synchronized(lock) {
                val owner = owner(token) ?: return@synchronized false
                if (!editable(owner) || owner.edit != null || owner.confirmation != null || owner.retainedMemo == null) {
                    return@synchronized false
                }
                owner.retainedMemo = null
                publish(owner)
                true
            }

        fun editMemo(
            token: RoadState.Token,
            editNonce: Long,
            text: String,
        ): Boolean =
            synchronized(lock) {
                val owner = owner(token) ?: return@synchronized false
                val edit = owner.edit ?: return@synchronized false
                if (!editable(owner) || edit.nonce != editNonce || edit.expectedCurrent != owner.stored) return@synchronized false
                owner.edit = edit.copy(text = text)
                // Keep the editor key stable, but retire saved callbacks for the previously displayed text.
                publish(owner)
                true
            }

        fun cancelMemo(
            token: RoadState.Token,
            editNonce: Long,
        ): Boolean =
            synchronized(lock) {
                val owner = owner(token) ?: return@synchronized false
                val edit = owner.edit ?: return@synchronized false
                if (!editable(owner) || edit.nonce != editNonce || edit.expectedCurrent != owner.stored) return@synchronized false
                owner.edit = null
                owner.draft = owner.stored?.road
                publish(owner)
                true
            }

        fun saveMemo(
            token: RoadState.Token,
            editNonce: Long,
        ): Boolean {
            val job =
                synchronized(lock) {
                    val owner = owner(token) ?: return@synchronized null
                    val edit = owner.edit ?: return@synchronized null
                    if (!editable(owner) || edit.nonce != editNonce || edit.expectedCurrent != owner.stored ||
                        (edit.text.isNotEmpty() && !SessionMemoPolicy.isValidText(edit.text))
                    ) {
                        return@synchronized null
                    }
                    schedule(owner, edit.expectedCurrent.road, edit.text, discardMemo = false, memoEdit = true)
                } ?: return false
            job.start()
            return true
        }

        /** The caller's immutable comparison host binds this intent to its currently selected event. */
        fun setSample(
            token: RoadState.Token,
            sample: SessionReviewSample?,
        ): Boolean {
            val job =
                synchronized(lock) {
                    val owner = owner(token) ?: return@synchronized null
                    val stored = owner.stored ?: return@synchronized null
                    if (!editable(owner) || owner.edit != null || owner.confirmation != null || owner.retainedMemo != null ||
                        (sample != null && sample.event.sessionId != owner.source.sessionId) || stored.sampleAnchor == sample
                    ) {
                        return@synchronized null
                    }
                    schedule(owner, stored.road, stored.memoText, discardMemo = false, memoEdit = false, sampleEdit = true, sample = sample)
                } ?: return false
            job.start()
            return true
        }

        fun cancelDiscard(
            token: RoadState.Token,
            confirmationNonce: Long,
        ): Boolean =
            synchronized(lock) {
                val owner = owner(token) ?: return@synchronized false
                val pending = owner.confirmation ?: return@synchronized false
                if (!editable(owner) || pending.nonce != confirmationNonce || owner.stored != pending.expectedCurrent) {
                    return@synchronized false
                }
                owner.confirmation = null
                publish(owner)
                true
            }

        fun confirmDiscard(
            token: RoadState.Token,
            confirmationNonce: Long,
        ): Boolean {
            val job =
                synchronized(lock) {
                    val owner = owner(token) ?: return@synchronized null
                    val pending = owner.confirmation ?: return@synchronized null
                    if (!editable(owner) || owner.edit != null || pending.nonce != confirmationNonce ||
                        owner.stored != pending.expectedCurrent ||
                        (pending.expectedCurrent.memoText.isEmpty() && pending.expectedCurrent.sampleAnchor == null)
                    ) {
                        return@synchronized null
                    }
                    if (pending.target != null && owner.candidates.none { it == pending.target }) return@synchronized null
                    owner.confirmation = null
                    schedule(
                        owner,
                        pending.target,
                        "",
                        discardMemo = true,
                        memoEdit = false,
                        discardSample =
                            pending.expectedCurrent.sampleAnchor != null,
                    )
                } ?: return false
            job.start()
            return true
        }

        fun retry(token: RoadState.Token): Boolean =
            synchronized(lock) {
                val owner = owner(token) ?: return@synchronized false
                if (owner.published.value !is RoadState.Failed || owner.action != null) return@synchronized false
                owner.outcome = null
                reload(owner)
                true
            }

        private fun safe(check: () -> Boolean): Boolean =
            try {
                check()
            } catch (_: Exception) {
                false
            }

        private fun context(owner: Reader): Boolean {
            if (current !== owner || owner.revoked || !owner.job.isActive) return false
            if (!safe(owner.canKeepOpen)) {
                revoke(owner, false)
                return false
            }
            if (!allowsPracticeInteraction(vehicle.state.value, restrictions.restrictions.value)) {
                revoke(owner, true)
                return false
            }
            return owner.sourceReady
        }

        private fun owner(token: RoadState.Token): Reader? {
            val owner = current ?: return null
            return owner.takeIf { token.readerId > 0 && it.published.value.token == token && context(it) }
        }

        private fun editable(owner: Reader): Boolean = context(owner) && owner.received && !owner.hasActive && owner.action == null

        private suspend fun watch(owner: Reader) {
            try {
                combine(vehicle.state, restrictions.restrictions, sessions.observeSessions()) { _, _, records -> records }
                    .collect { records ->
                        synchronized(lock) {
                            if (current !== owner || owner.revoked) return@synchronized
                            if (records.map { it.sessionId }.distinct().size != records.size ||
                                owner.source.isActive || records.singleOrNull { it.sessionId == owner.source.sessionId } != owner.source
                            ) {
                                revoke(owner, false)
                                return@synchronized
                            }
                            val first = !owner.sourceReady
                            val active = records.any { it.isActive }
                            val activeChanged = owner.hasActive != active
                            owner.sourceReady = true
                            owner.hasActive = active
                            signals.value += 1
                            if (!context(owner)) return@synchronized
                            if (active && (owner.action != null || owner.edit != null || owner.confirmation != null)) {
                                failed(owner, owner.action != null)
                            } else if (first) {
                                owner.candidates =
                                    NextPracticeSeed
                                        .resolve(catalog, owner.source)
                                        ?.practice
                                        ?.course
                                        ?.roadSnapshots
                                        .orEmpty()
                                check(
                                    owner.candidates
                                        .map { it.id }
                                        .distinct()
                                        .size == owner.candidates.size,
                                )
                                reload(owner)
                            } else if (activeChanged && owner.received) {
                                publish(owner)
                            }
                        }
                    }
                synchronized(lock) { if (current === owner) revoke(owner, false) }
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                synchronized(lock) { if (current === owner) revoke(owner, false) }
            }
        }

        private suspend fun read(owner: Reader) {
            val readJob = checkNotNull(currentCoroutineContext()[Job])
            val query =
                synchronized(lock) {
                    if (!context(owner)) return
                    owner.readJob = readJob
                    owner.queryNonce
                }
            try {
                reviews.observe(owner.source.sessionId).collect { stored ->
                    synchronized(lock) {
                        if (query != owner.queryNonce || !context(owner)) return@synchronized
                        check(stored == null || valid(owner, stored))
                        val outcome = owner.outcome
                        if (outcome != null && stored != outcome.review) {
                            owner.outcome = null
                            failed(owner, true)
                            return@synchronized
                        }
                        val changed = !owner.received || owner.stored != stored
                        if (owner.received && changed && owner.action == null &&
                            (owner.edit != null || owner.confirmation != null)
                        ) {
                            failed(owner, false)
                            return@synchronized
                        }
                        owner.received = true
                        owner.stored = stored
                        reconcileMemoDraft(owner)
                        if (changed && owner.action == null) owner.draft = stored?.road
                        if (outcome != null) {
                            owner.feedback = outcome.feedback
                            owner.outcome = null
                        }
                        publish(owner, keepToken = owner.edit != null && !changed)
                    }
                }
                synchronized(lock) {
                    if (query == owner.queryNonce && context(owner)) failed(owner, false)
                }
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (query == owner.queryNonce && context(owner)) failed(owner, false)
                }
            } finally {
                synchronized(lock) { if (owner.readJob === readJob) owner.readJob = null }
            }
        }

        private fun reconcileMemoDraft(owner: Reader) {
            val retained = owner.retainedMemo ?: return
            val stored = owner.stored
            if (stored != null && stored.road == retained.expectedCurrent.road && stored.memoText == retained.text) {
                // A fresh read confirmed the exact submitted text; it is now the saved value.
                owner.retainedMemo = null
            } else if (stored == retained.expectedCurrent && !owner.hasActive) {
                owner.edit = retained.copy(nonce = ++actionSerial)
                owner.retainedMemo = null
            }
        }

        private fun valid(
            owner: Reader,
            stored: SessionReviewRoad,
        ): Boolean = stored.source == owner.source && stored.version.isNotBlank() && stored.origin == "USER_SELECTION"

        private fun write(
            token: RoadState.Token,
            clear: Boolean,
        ): Boolean {
            var accepted = false
            val job =
                synchronized(lock) {
                    val owner = owner(token) ?: return@synchronized null
                    if (!editable(owner) || owner.edit != null || owner.confirmation != null || owner.retainedMemo != null) {
                        return@synchronized null
                    }
                    val expected = owner.stored
                    if (clear && expected == null) return@synchronized null
                    val road = if (clear) null else (owner.draft ?: return@synchronized null)
                    if (road != null && road != expected?.road && owner.candidates.none { it == road }) return@synchronized null
                    accepted = true
                    if (expected != null && (expected.memoText.isNotEmpty() || expected.sampleAnchor != null) && road != expected.road) {
                        owner.confirmation = RoadState.Confirmation(++actionSerial, road, expected)
                        publish(owner)
                        null
                    } else {
                        val memo = if (road == expected?.road) expected?.memoText.orEmpty() else ""
                        schedule(owner, road, memo, discardMemo = false, memoEdit = false)
                    }
                }
            job?.start()
            return accepted
        }

        private fun schedule(
            owner: Reader,
            road: CourseRoadSnapshot?,
            memoText: String,
            discardMemo: Boolean,
            memoEdit: Boolean,
            discardSample: Boolean = false,
            sampleEdit: Boolean = false,
            sample: SessionReviewSample? = null,
        ): Job {
            val expectedSample = if (sampleEdit) sample else owner.stored?.takeIf { it.road == road }?.sampleAnchor
            val action = Action(owner.stored, road, memoText, discardMemo, memoEdit, discardSample, sampleEdit, expectedSample)
            owner.action = action
            val work = owner.scope.launch(start = CoroutineStart.LAZY) { write(owner, action) }
            action.job = work
            publish(owner)
            return work
        }

        private fun commit(
            owner: Reader,
            action: Action,
            job: Job,
        ): Boolean =
            synchronized(lock) {
                owner.action === action && action.job === job && job.isActive && context(owner) &&
                    !owner.hasActive && owner.received && owner.stored == action.expected
            }

        private suspend fun write(
            owner: Reader,
            action: Action,
        ) {
            val job = checkNotNull(currentCoroutineContext()[Job])
            try {
                if (!commit(owner, action, job)) return
                val result =
                    if (action.sampleEdit) {
                        reviews.setSample(owner.source, checkNotNull(action.expected), action.sample) { commit(owner, action, job) }
                    } else if (action.road == null) {
                        reviews.clear(
                            owner.source,
                            checkNotNull(action.expected),
                            action.discardMemo,
                            action.discardSample,
                        ) { commit(owner, action, job) }
                    } else {
                        reviews.save(
                            owner.source,
                            action.road,
                            action.expected,
                            action.memoText,
                            action.discardMemo,
                            action.discardSample,
                        ) {
                            commit(owner, action, job)
                        }
                    }
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (owner.action !== action || !context(owner)) return@synchronized
                    owner.action = null
                    when (result) {
                        is SessionReviewRoadWriteResult.Saved -> {
                            if (action.road == null || !valid(owner, result.review) || result.review.road != action.road ||
                                result.review.memoText != action.memoText || result.review.sampleAnchor != action.sample
                            ) {
                                failed(owner, true)
                            } else {
                                owner.edit = null
                                val feedback =
                                    if (action.sampleEdit) {
                                        if (action.sample == null) RoadState.Feedback.SAMPLE_CLEARED else RoadState.Feedback.SAMPLE_SAVED
                                    } else if (action.memoEdit) {
                                        if (action.memoText.isEmpty()) RoadState.Feedback.MEMO_CLEARED else RoadState.Feedback.MEMO_SAVED
                                    } else {
                                        RoadState.Feedback.SAVED
                                    }
                                owner.outcome = Outcome(result.review, feedback)
                                reload(owner)
                            }
                        }
                        SessionReviewRoadWriteResult.Cleared -> {
                            if (action.road != null) {
                                failed(owner, true)
                            } else {
                                owner.outcome = Outcome(null, RoadState.Feedback.CLEARED)
                                reload(owner)
                            }
                        }
                        else -> failed(owner, false)
                    }
                }
            } catch (failure: CancellationException) {
                synchronized(lock) {
                    if (owner.action === action && context(owner)) failed(owner, true)
                }
                throw failure
            } catch (_: Exception) {
                synchronized(lock) {
                    if (owner.action === action && context(owner)) failed(owner, true)
                }
            } finally {
                synchronized(lock) {
                    if (owner.action === action) {
                        owner.action = null
                        if (context(owner)) failed(owner, true)
                    }
                }
            }
        }

        private fun failed(
            owner: Reader,
            unconfirmed: Boolean,
        ) {
            val action = owner.action
            owner.retainedMemo = owner.edit ?: owner.retainedMemo
            owner.edit = null
            owner.confirmation = null
            owner.action = null
            action?.job?.cancel()
            owner.received = false
            // Retire in-flight emissions until an explicit fresh read; never overwrite uncertainty.
            owner.queryNonce += 1
            owner.readJob?.cancel()
            owner.readJob = null
            owner.published.value = RoadState.Failed(owner.source, next(owner), unconfirmed, owner.retainedMemo?.text)
            signals.value += 1
        }

        private fun reload(owner: Reader) {
            owner.readJob?.cancel()
            owner.readJob = null
            owner.received = false
            owner.queryNonce += 1
            loading(owner)
            owner.requests.value += 1
        }

        private fun publish(
            owner: Reader,
            keepToken: Boolean = false,
        ) {
            if (!context(owner)) return
            owner.published.value =
                RoadState.Ready(
                    owner.source,
                    if (keepToken) owner.published.value.token else next(owner),
                    owner.stored,
                    owner.candidates,
                    owner.draft,
                    owner.action != null,
                    !owner.hasActive && owner.action == null,
                    owner.feedback,
                    owner.edit,
                    owner.confirmation,
                    owner.retainedMemo,
                )
            signals.value += 1
        }

        private fun loading(owner: Reader) {
            owner.published.value = RoadState.Loading(owner.source, next(owner))
            signals.value += 1
        }

        private fun revoke(
            owner: Reader,
            locked: Boolean,
        ) {
            if (owner.revoked) return
            owner.revoked = true
            owner.retainedMemo = null
            owner.edit = null
            owner.confirmation = null
            owner.action?.job?.cancel()
            owner.action = null
            owner.readJob?.cancel()
            owner.readJob = null
            owner.watch?.cancel()
            owner.published.value =
                if (locked) {
                    RoadState.Locked(owner.source, next(owner))
                } else {
                    RoadState.Unavailable(owner.source, next(owner))
                }
            signals.value += 1
        }

        private fun next(owner: Reader): RoadState.Token = RoadState.Token(owner.id, owner.queryNonce, ++revision)

        private class Reader(
            val id: Long,
            val source: PracticeSession,
            val canKeepOpen: () -> Boolean,
            val job: Job,
            val scope: CoroutineScope,
        ) {
            val published = MutableStateFlow<RoadState>(RoadState.Loading(source, RoadState.Token(id, 0, 0)))
            val requests = MutableStateFlow(0L)
            var watch: Job? = null
            var readJob: Job? = null
            var queryNonce = 0L
            var revoked = false
            var sourceReady = false
            var hasActive = true
            var candidates = emptyList<CourseRoadSnapshot>()
            var stored: SessionReviewRoad? = null
            var draft: CourseRoadSnapshot? = null
            var edit: RoadState.Edit? = null
            var confirmation: RoadState.Confirmation? = null
            var retainedMemo: RoadState.Edit? = null
            var received = false
            var action: Action? = null
            var feedback: RoadState.Feedback? = null
            var outcome: Outcome? = null
        }

        private class Action(
            val expected: SessionReviewRoad?,
            val road: CourseRoadSnapshot?,
            val memoText: String,
            val discardMemo: Boolean,
            val memoEdit: Boolean,
            val discardSample: Boolean,
            val sampleEdit: Boolean,
            val sample: SessionReviewSample?,
        ) {
            var job: Job? = null
        }

        private class Outcome(
            val review: SessionReviewRoad?,
            val feedback: RoadState.Feedback,
        )
    }
