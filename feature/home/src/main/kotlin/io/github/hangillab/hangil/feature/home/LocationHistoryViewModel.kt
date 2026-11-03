package io.github.hangillab.hangil.feature.home

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.LocationRecordingRepository
import io.github.hangillab.hangil.core.domain.LocationRecordingRun
import io.github.hangillab.hangil.core.domain.LocationRecordingRunStatus
import io.github.hangillab.hangil.core.domain.LocationRecordingSnapshot
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import io.github.hangillab.hangil.feature.home.LocationHistoryUiState.FailureReason
import io.github.hangillab.hangil.feature.home.LocationHistoryUiState.Token
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Read-only reader for one captured ended source. Location runtime permission is not a read gate. */
@HiltViewModel
class LocationHistoryViewModel
    @Inject
    constructor(
        private val sessions: PracticeSessionRepository,
        private val locationRepo: LocationRecordingRepository,
        private val vehicle: VehicleGateway,
        private val restrictions: DrivingRestrictionsProvider,
    ) : ViewModel() {
        private val lock = Any()
        private var current: Reader? = null
        private var serial = 0L
        private var nonce = 0L
        private var revision = 0L
        private var cleared = false

        fun observe(
            expectedSource: PracticeSession,
            canKeepOpen: () -> Boolean,
        ): Flow<LocationHistoryUiState> =
            flow {
                coroutineScope {
                    val hostJob = checkNotNull(currentCoroutineContext()[Job])
                    val hostScope = this
                    val owner =
                        synchronized(lock) {
                            current?.let { revoke(it, false) }
                            Reader(++serial, expectedSource, canKeepOpen, hostJob, hostScope).also {
                                current = it
                                if (cleared || !validSource(expectedSource) || !hostAlive(it)) {
                                    revoke(it, false)
                                } else if (allowed()) {
                                    it.authorized = true
                                } else if (!mayAwaitFirstGate()) {
                                    revoke(it, true)
                                } else {
                                    it.states.value = LocationHistoryUiState.Locked(it.source, nextToken(it))
                                }
                            }
                        }
                    val policy =
                        launch(start = CoroutineStart.LAZY) {
                            try {
                                combine(vehicle.state, restrictions.restrictions, ::allowsPracticeInteraction).collect { permits ->
                                    synchronized(lock) {
                                        if (current !== owner || owner.revoked) return@synchronized
                                        if (!hostAlive(owner)) {
                                            revoke(owner, false)
                                        } else if (permits) {
                                            if (!owner.authorized) {
                                                owner.authorized = true
                                                if (owner.sourceJob == null && context(owner, requireSource = false)) startSource(owner)
                                            }
                                        } else if (owner.authorized || !mayAwaitFirstGate()) {
                                            revoke(owner, true)
                                        } else if (owner.states.value !is LocationHistoryUiState.Locked) {
                                            owner.states.value = LocationHistoryUiState.Locked(owner.source, nextToken(owner))
                                        }
                                    }
                                }
                            } catch (
                                cancelled: CancellationException,
                            ) {
                                throw cancelled
                            } catch (_: Exception) {
                                synchronized(lock) { if (current === owner) revoke(owner, true) }
                            }
                        }
                    synchronized(lock) {
                        if (!owner.revoked && current === owner) {
                            owner.policyJob = policy
                            policy.start()
                        } else {
                            policy.cancel()
                        }
                    }
                    synchronized(lock) {
                        if (owner.authorized && owner.sourceJob == null &&
                            context(owner, requireSource = false)
                        ) {
                            startSource(owner)
                        }
                    }
                    try {
                        emitAll(owner.states)
                    } finally {
                        synchronized(lock) {
                            if (current === owner) {
                                revoke(owner, false)
                                current = null
                            }
                        }
                        policy.cancel()
                    }
                }
            }.buffer(0)

        fun canRead(token: Token): Boolean =
            synchronized(lock) {
                val owner = owner(token) ?: return@synchronized false
                owner.states.value is LocationHistoryUiState.Ready && owner.queryJob?.isActive == true
            }

        fun previousRun(
            token: Token,
            expectedRunId: String,
        ): Boolean = moveRun(token, expectedRunId, -1)

        fun nextRun(
            token: Token,
            expectedRunId: String,
        ): Boolean = moveRun(token, expectedRunId, 1)

        fun previousEvent(
            token: Token,
            expectedRunId: String,
            expectedEventId: Long?,
        ): Boolean = moveEvent(token, expectedRunId, expectedEventId, -1)

        fun nextEvent(
            token: Token,
            expectedRunId: String,
            expectedEventId: Long?,
        ): Boolean = moveEvent(token, expectedRunId, expectedEventId, 1)

        fun retry(token: Token): Boolean =
            synchronized(lock) {
                val owner = current ?: return@synchronized false
                if (owner.states.value.token != token || owner.states.value !is LocationHistoryUiState.Failed ||
                    !context(owner, requireSource = false)
                ) {
                    return@synchronized false
                }
                val sourceJob = owner.sourceJob
                owner.sourceJob = null
                detachQuery(owner)
                sourceJob?.cancel()
                owner.failed = false
                owner.actualSource = null
                publish(owner, LocationHistoryUiState.Loading(owner.source, nextToken(owner)))
                startSource(owner)
                true
            }

        private fun moveRun(
            token: Token,
            id: String,
            offset: Int,
        ): Boolean =
            synchronized(lock) {
                val owner = owner(token) ?: return@synchronized false
                val ready = owner.states.value as? LocationHistoryUiState.Ready ?: return@synchronized false
                if (owner.queryJob?.isActive != true || ready.run.runId != id) return@synchronized false
                val target = ready.runIndex + offset
                if (target !in ready.snapshot.runs.indices) return@synchronized false
                owner.selectedRun = ready.snapshot.runs[target].runId
                owner.selectedEvent = null
                publishReady(owner, ready.snapshot)
                true
            }

        private fun moveEvent(
            token: Token,
            runId: String,
            eventId: Long?,
            offset: Int,
        ): Boolean =
            synchronized(lock) {
                val owner = owner(token) ?: return@synchronized false
                val ready = owner.states.value as? LocationHistoryUiState.Ready ?: return@synchronized false
                if (owner.queryJob?.isActive != true || ready.run.runId != runId || ready.event?.id != eventId) return@synchronized false
                val target = ready.eventIndex + offset
                if (target !in ready.events.indices) return@synchronized false
                owner.selectedEvent = ready.events[target].id
                publish(owner, ready.copy(token = nextToken(owner), eventIndex = target))
                true
            }

        private fun startSource(owner: Reader) {
            if (!context(owner, requireSource = false)) return
            val job =
                owner.scope.launch(start = CoroutineStart.LAZY) {
                    val self = currentCoroutineContext()[Job]
                    try {
                        if (!synchronized(lock) { owner.sourceJob === self && context(owner, requireSource = false) }) return@launch
                        sessions.observeSessions().collect { rows ->
                            synchronized(lock) {
                                if (owner.sourceJob !== self || !context(owner, requireSource = false)) return@synchronized
                                val matches = rows.filter { it.sessionId == owner.source.sessionId }
                                if (matches.size != 1 || matches.single() != owner.source) {
                                    revoke(owner, false)
                                } else {
                                    owner.actualSource = matches.single()
                                    if (!owner.failed && owner.queryJob == null) startQuery(owner)
                                }
                            }
                        }
                        synchronized(lock) { if (owner.sourceJob === self) fail(owner, FailureReason.SOURCE_STREAM_COMPLETED) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        synchronized(lock) { if (owner.sourceJob === self) fail(owner, FailureReason.SOURCE_READ) }
                    } finally {
                        synchronized(lock) {
                            if (owner.sourceJob === self && current === owner && !owner.revoked && !owner.failed && owner.job.isActive) {
                                fail(owner, FailureReason.SOURCE_READ)
                            }
                        }
                    }
                }
            owner.sourceJob = job
            job.start()
        }

        private fun startQuery(owner: Reader) {
            if (!context(owner)) return
            owner.queryNonce = ++nonce
            val queryNonce = owner.queryNonce
            publish(owner, LocationHistoryUiState.Loading(owner.source, nextToken(owner)))
            val job =
                owner.scope.launch(start = CoroutineStart.LAZY) {
                    val self = currentCoroutineContext()[Job]
                    try {
                        if (!synchronized(
                                lock,
                            ) { owner.queryJob === self && owner.queryNonce == queryNonce && !owner.failed && context(owner) }
                        ) {
                            return@launch
                        }
                        locationRepo.observe(owner.source.sessionId).collect { received ->
                            synchronized(lock) {
                                if (owner.queryJob !== self || owner.queryNonce != queryNonce || owner.failed ||
                                    !context(owner)
                                ) {
                                    return@synchronized
                                }
                                val snapshot = LocationRecordingSnapshot(received.runs.toList(), received.events.toList())
                                val reason =
                                    when {
                                        !valid(owner.source, snapshot) -> FailureReason.INVALID_SNAPSHOT
                                        owner.lastSnapshot?.let { !progresses(it, snapshot) } == true -> FailureReason.SNAPSHOT_REGRESSED
                                        owner.selectedRun != null &&
                                            snapshot.runs.none {
                                                it.runId == owner.selectedRun
                                            }
                                        -> FailureReason.SELECTED_MISSING
                                        owner.selectedEvent != null &&
                                            snapshot.events.none {
                                                it.id == owner.selectedEvent
                                            }
                                        -> FailureReason.SELECTED_MISSING
                                        else -> null
                                    }
                                if (reason != null) {
                                    fail(owner, reason)
                                } else {
                                    if (owner.lastSnapshot == snapshot &&
                                        (
                                            owner.states.value is LocationHistoryUiState.Ready ||
                                                owner.states.value is LocationHistoryUiState.Empty
                                        )
                                    ) {
                                        return@synchronized
                                    }
                                    owner.lastSnapshot = snapshot
                                    if (snapshot.runs.isEmpty()) {
                                        publish(owner, LocationHistoryUiState.Empty(owner.source, nextToken(owner)))
                                    } else {
                                        publishReady(owner, snapshot)
                                    }
                                }
                            }
                        }
                        synchronized(lock) { if (owner.queryJob === self) fail(owner, FailureReason.RECORDING_STREAM_COMPLETED) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        synchronized(lock) { if (owner.queryJob === self) fail(owner, FailureReason.RECORDING_READ) }
                    } finally {
                        synchronized(lock) {
                            if (owner.queryJob === self && current === owner && !owner.revoked && !owner.failed && owner.job.isActive) {
                                fail(owner, FailureReason.RECORDING_READ)
                            }
                        }
                    }
                }
            owner.queryJob = job
            job.start()
        }

        private fun publishReady(
            owner: Reader,
            snapshot: LocationRecordingSnapshot,
        ) {
            if (!context(owner) || owner.failed) return
            val runIndex =
                if (owner.selectedRun ==
                    null
                ) {
                    snapshot.runs.lastIndex
                } else {
                    snapshot.runs.indexOfFirst { it.runId == owner.selectedRun }
                }
            if (runIndex < 0) {
                fail(owner, FailureReason.SELECTED_MISSING)
                return
            }
            val rows = snapshot.events.filter { it.runId == snapshot.runs[runIndex].runId }
            val eventIndex = if (owner.selectedEvent == null) rows.lastIndex else rows.indexOfFirst { it.id == owner.selectedEvent }
            if (owner.selectedEvent != null && eventIndex < 0) {
                fail(owner, FailureReason.SELECTED_MISSING)
                return
            }
            owner.selectedRun = snapshot.runs[runIndex].runId
            owner.selectedEvent = rows.getOrNull(eventIndex)?.id
            publish(owner, LocationHistoryUiState.Ready(owner.source, nextToken(owner), snapshot, runIndex, eventIndex))
        }

        private fun owner(token: Token): Reader? {
            val owner = current ?: return null
            if (token.readerId <= 0 || owner.states.value.token != token || !context(owner)) return null
            return owner
        }

        private fun context(
            owner: Reader,
            requireSource: Boolean = true,
        ): Boolean {
            if (cleared || current !== owner || owner.revoked || !owner.job.isActive) return false
            if (!hostAlive(owner)) {
                revoke(owner, false)
                return false
            }
            if (!allowed()) {
                revoke(owner, true)
                return false
            }
            if (current !== owner || owner.revoked || !owner.job.isActive) return false
            return !requireSource || (owner.actualSource == owner.source && owner.sourceJob?.isActive == true)
        }

        /** Cold WhileSubscribed SDK state may initially be unknown. No source/data query runs until first fresh P+UX. */
        private fun mayAwaitFirstGate(): Boolean =
            try {
                val state = vehicle.state.value
                val policy = restrictions.restrictions.value
                state.connection !in listOf(ConnectionState.PERMISSION_DENIED, ConnectionState.UNAVAILABLE, ConnectionState.ERROR) &&
                    (state.gear as? VehicleReading.Available)?.value.let { it == null || it == GearPosition.PARK } &&
                    policy.parkingState != ParkingState.NOT_PARKED && policy.uxRestrictionState != UxRestrictionState.RESTRICTED
            } catch (_: Exception) {
                false
            }

        private fun hostAlive(owner: Reader): Boolean =
            try {
                owner.canKeepOpen()
            } catch (_: Exception) {
                false
            }

        private fun allowed(): Boolean =
            try {
                allowsPracticeInteraction(vehicle.state.value, restrictions.restrictions.value)
            } catch (_: Exception) {
                false
            }

        private fun fail(
            owner: Reader,
            reason: FailureReason,
        ) {
            if (!context(owner, requireSource = false) || owner.failed) return
            owner.failed = true
            detachQuery(owner)
            publish(owner, LocationHistoryUiState.Failed(owner.source, nextToken(owner), reason))
        }

        private fun revoke(
            owner: Reader,
            locked: Boolean,
        ) {
            if (owner.revoked) return
            owner.revoked = true
            owner.lastSnapshot = null
            owner.selectedRun = null
            owner.selectedEvent = null
            val policy = owner.policyJob
            owner.policyJob = null
            policy?.cancel()
            detachQuery(owner)
            val source = owner.sourceJob
            owner.sourceJob = null
            source?.cancel()
            owner.states.value =
                if (locked) {
                    LocationHistoryUiState.Locked(owner.source, nextToken(owner))
                } else {
                    LocationHistoryUiState.Unavailable(owner.source, nextToken(owner))
                }
        }

        private fun detachQuery(owner: Reader) {
            val job = owner.queryJob
            owner.queryJob = null
            job?.cancel()
        }

        private fun nextToken(owner: Reader) = Token(owner.id, owner.queryNonce, ++revision)

        private fun publish(
            owner: Reader,
            state: LocationHistoryUiState,
        ) {
            if (current === owner && !owner.revoked && owner.job.isActive) owner.states.value = state
        }

        private fun validSource(source: PracticeSession): Boolean {
            val ended = source.endedAtEpochMillis ?: return false
            val selection = source.selection
            return source.sessionId.isNotBlank() && source.startedAtEpochMillis > 0 && ended >= source.startedAtEpochMillis &&
                selection.goalId.isNotBlank() && selection.courseId.isNotBlank() && selection.taskId.isNotBlank() &&
                selection.courseVersion > 0 && selection.briefingVersion > 0 && source.goalTitle.isNotBlank() &&
                source.courseTitle.isNotBlank() && source.taskTitle.isNotBlank()
        }

        private fun valid(
            source: PracticeSession,
            snapshot: LocationRecordingSnapshot,
        ): Boolean {
            if (snapshot.runs
                    .map { it.runId }
                    .distinct()
                    .size != snapshot.runs.size ||
                snapshot.runs.count { it.status == LocationRecordingRunStatus.OPEN } > 1
            ) {
                return false
            }
            if (snapshot.runs.any { it.source != source.copy(endedAtEpochMillis = null) }) return false
            if (snapshot.events
                    .map { it.id }
                    .distinct()
                    .size != snapshot.events.size ||
                snapshot.events.zipWithNext().any { (a, b) -> a.id >= b.id }
            ) {
                return false
            }
            val runs = snapshot.runs.associateBy { it.runId }
            if (snapshot.events.any { it.sessionId != source.sessionId || it.runId !in runs }) return false
            for ((runId, rows) in snapshot.events.groupBy { it.runId }) {
                val run = checkNotNull(runs[runId])
                if (rows.size > LocationRecordingRepository.MAX_EVENTS_PER_RUN) return false
                var elapsed = run.startedAtElapsedRealtimeMillis
                for ((index, event) in rows.withIndex()) {
                    if (event.ordinal != index + 1 || event.reading.receivedAtElapsedRealtimeMillis < elapsed) return false
                    if (index < rows.lastIndex && event.reading !is LocationUpdateReading.Sample &&
                        event.reading !is LocationUpdateReading.Empty
                    ) {
                        return false
                    }
                    elapsed = event.reading.receivedAtElapsedRealtimeMillis
                }
                val endedElapsed = run.endedAtElapsedRealtimeMillis
                if (endedElapsed != null && endedElapsed < elapsed) return false
            }
            return true
        }

        private fun progresses(
            before: LocationRecordingSnapshot,
            after: LocationRecordingSnapshot,
        ): Boolean {
            if (after.events.take(before.events.size) != before.events || after.runs.size < before.runs.size) return false
            val terminalOwners =
                before.runs
                    .filter { it.status != LocationRecordingRunStatus.OPEN }
                    .map { it.runId }
                    .toSet()
            if (after.events.drop(before.events.size).any { it.runId in terminalOwners }) return false
            return before.runs.indices.all { index ->
                val old = before.runs[index]
                val next = after.runs[index]
                old == next || (old.status == LocationRecordingRunStatus.OPEN && sameOwner(old, next))
            }
        }

        private fun sameOwner(
            a: LocationRecordingRun,
            b: LocationRecordingRun,
        ): Boolean =
            a ==
                b.copy(
                    status = LocationRecordingRunStatus.OPEN,
                    stopReason = null,
                    endedAtEpochMillis = null,
                    endedAtElapsedRealtimeMillis = null,
                )

        override fun onCleared() {
            val hostJob =
                synchronized(lock) {
                    cleared = true
                    val job = current?.job
                    current?.let { revoke(it, false) }
                    current = null
                    job
                }
            hostJob?.cancel()
            super.onCleared()
        }

        private class Reader(
            val id: Long,
            val source: PracticeSession,
            val canKeepOpen: () -> Boolean,
            val job: Job,
            val scope: CoroutineScope,
        ) {
            var queryNonce = 0L
            var revoked = false
            var failed = false
            var authorized = false
            var actualSource: PracticeSession? = null
            var sourceJob: Job? = null
            var policyJob: Job? = null
            var queryJob: Job? = null
            var selectedRun: String? = null
            var selectedEvent: Long? = null
            var lastSnapshot: LocationRecordingSnapshot? = null
            val states = MutableStateFlow<LocationHistoryUiState>(LocationHistoryUiState.Loading(source, Token(id, 0, 0)))
        }
    }
