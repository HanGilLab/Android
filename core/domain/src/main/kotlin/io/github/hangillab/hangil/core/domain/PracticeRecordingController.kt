package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock

/**
 * Serializes the ordinary STARTED recorder and one explicitly requested service run.
 * Native service promotion is a separate acknowledgement, never inferred from a start request.
 * A failed background run stays failed until an explicit fresh request or opt-out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PracticeRecordingController(
    private val vehicleGateway: VehicleGateway,
    private val sessionRepository: PracticeSessionRepository,
    observationRepository: VehicleObservationRepository,
    private val restrictionsProvider: DrivingRestrictionsProvider,
    private val runRepository: RecordingRunRepository,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val nextRunId: () -> String,
    private val processId: String,
    private val startService: (runId: String, sessionId: String) -> Unit,
    private val stopService: (runId: String) -> Unit,
    private val serviceStartTimeoutMillis: Long = 10_000,
) : RecordingController {
    private val recorder = VehicleObservationRecorder(vehicleGateway, observationRepository, clock::millis)
    private val host = Mutex()
    private val work = Mutex()
    private val guard = Any()
    private val issuedRunIds = mutableSetOf<String>()
    private val retries = MutableStateFlow(0L)
    private val owner = MutableStateFlow<Request?>(null)
    private val markerSource = MutableStateFlow<MarkerSource?>(null)
    private val currentState = MutableStateFlow<VehicleRecordingState>(VehicleRecordingState.Paused)
    private val currentBackground = MutableStateFlow<BackgroundRecordingState>(BackgroundRecordingState.Disabled)
    override val state = currentState.asStateFlow()
    override val background = currentBackground.asStateFlow()

    init {
        require(processId.isNotBlank())
        require(serviceStartTimeoutMillis > 0)
    }

    private val recovered =
        scope.async {
            try {
                runRepository.recoverInterrupted(processId)
                true
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                recoveryFailed(null)
                false
            } catch (_: Exception) {
                recoveryFailed(null)
                false
            }
        }

    init {
        // A result belongs to the same live source reader, not merely a reused session ID.
        scope.launch {
            if (!recovered.await()) return@launch
            markerSource.collectLatest { source ->
                if (source == null) return@collectLatest
                try {
                    runRepository
                        .observe(source.session.sessionId)
                        .onCompletion { cause -> if (cause == null) error("Run observation completed") }
                        .collect { runs ->
                            currentCoroutineContext().ensureActive()
                            synchronized(guard) {
                                if (markerSource.value != source || owner.value != null) return@synchronized
                                currentBackground.value =
                                    when {
                                        !validRecoveredRows(source.session.sessionId, runs) ->
                                            BackgroundRecordingState.Failed(
                                                null,
                                                source.session.sessionId,
                                                BackgroundRecordingState.Reason.RECOVERY,
                                            )
                                        runs.any { it.status == RecordingRunStatus.INTERRUPTED } ->
                                            BackgroundRecordingState.Interrupted(source.session.sessionId)
                                        runs.lastOrNull()?.status == RecordingRunStatus.FAILED ->
                                            BackgroundRecordingState.Failed(
                                                null,
                                                source.session.sessionId,
                                                BackgroundRecordingState.Reason.PREVIOUS_RUN,
                                            )
                                        else -> BackgroundRecordingState.Disabled
                                    }
                            }
                        }
                } catch (cancelled: CancellationException) {
                    if (currentCoroutineContext().isActive) recoveryFailed(source)
                    throw cancelled
                } catch (_: Exception) {
                    recoveryFailed(source)
                }
            }
        }
    }

    override suspend fun recordWhileStarted() =
        host.withLock {
            val sourceOwner = Any()
            try {
                recovered.await() // Recovery failure does not disable ordinary foreground recording.
                owner.collectLatest { request ->
                    if (request == null) {
                        work.withLock { recordForeground(sourceOwner) }
                    } else {
                        awaitCancellation()
                    }
                }
            } finally {
                synchronized(guard) {
                    if (markerSource.value?.owner === sourceOwner) setMarkerSource(null)
                    if (owner.value == null) currentState.value = VehicleRecordingState.Paused
                }
            }
        }

    override fun retry(): Boolean =
        synchronized(guard) {
            if (owner.value != null || currentState.value !is VehicleRecordingState.Failed || !allowsStart()) return@synchronized false
            retries.value += 1
            true
        }

    override fun requestBackground(
        expected: PracticeSession,
        canStart: () -> Boolean,
    ): Boolean =
        synchronized(guard) {
            val previous = owner.value
            if ((previous != null && (!previous.completed.isCompleted || previous.source.sessionId != expected.sessionId)) ||
                !expected.isActive || !allowsStart(canStart)
            ) {
                return@synchronized false
            }
            if (recovered.isCompleted && !runCatching { recovered.getCompleted() }.getOrDefault(false)) {
                currentBackground.value =
                    BackgroundRecordingState.Failed(
                        null,
                        expected.sessionId,
                        BackgroundRecordingState.Reason.RECOVERY,
                    )
                return@synchronized false
            }
            val runId =
                try {
                    nextRunId().also { check(it.isNotBlank() && issuedRunIds.add(it)) }
                } catch (_: Exception) {
                    currentBackground.value =
                        BackgroundRecordingState.Failed(
                            previous?.runId,
                            previous?.source?.sessionId ?: expected.sessionId,
                            BackgroundRecordingState.Reason.OPEN,
                        )
                    return@synchronized false
                }
            val request = Request(runId, expected, canStart)
            owner.value = request
            currentBackground.value = BackgroundRecordingState.Starting(runId, expected.sessionId)
            request.preparing = scope.launch(start = CoroutineStart.LAZY) { prepare(request) }
            request.preparing?.start()
            true
        }

    override fun stopBackground(expectedRunId: String): Boolean =
        synchronized(guard) {
            val request = owner.value?.takeIf { it.runId == expectedRunId } ?: return@synchronized false
            request.optOutRequested = true
            if (request.completed.isCompleted) {
                currentBackground.value = BackgroundRecordingState.Disabled
                currentState.value = VehicleRecordingState.Paused
                // Release last: an immediate foreground collector can publish its state inline.
                owner.value = null
            } else {
                requestEnd(request, End(RecordingRunStatus.STOPPED, null, knownEnd = true, optOut = true))
            }
            true
        }

    override fun startFailed(runId: String) {
        synchronized(guard) {
            val request = owner.value?.takeIf { it.runId == runId && !it.completed.isCompleted } ?: return
            requestEnd(request, End(RecordingRunStatus.FAILED, BackgroundRecordingState.Reason.SERVICE_START, knownEnd = false))
        }
    }

    override suspend fun recordInService(
        runId: String,
        sessionId: String,
    ) {
        val request =
            synchronized(guard) {
                val candidate = owner.value
                if (candidate == null || candidate.runId != runId || candidate.source.sessionId != sessionId ||
                    !candidate.opened || !candidate.dispatched || candidate.ending != null || candidate.recording != null
                ) {
                    return
                }
                if (!allowsStart(candidate.canStart)) {
                    requestEnd(candidate, End(RecordingRunStatus.FAILED, BackgroundRecordingState.Reason.BLOCKED, knownEnd = true))
                    return
                }
                candidate.timeout?.cancel()
                candidate.canStart = null
                currentBackground.value = BackgroundRecordingState.Active(runId, sessionId)
                candidate.recording = scope.launch(start = CoroutineStart.LAZY) { recordBackground(candidate) }
                candidate.recording?.start()
                candidate
            }
        try {
            request.completed.await()
        } finally {
            val cleanup =
                synchronized(guard) {
                    if (!request.completed.isCompleted && request.ending == null) {
                        requestEnd(request, End(RecordingRunStatus.FAILED, BackgroundRecordingState.Reason.OBSERVATION, knownEnd = false))
                    }
                    request.cleanup
                }
            // The service lifetime cannot return while its recorder or pending append still runs.
            withContext(NonCancellable) { cleanup?.join() }
        }
    }

    private suspend fun prepare(request: Request) {
        try {
            if (!recovered.await()) {
                requestEnd(
                    request,
                    End(
                        RecordingRunStatus.FAILED,
                        BackgroundRecordingState.Reason.RECOVERY,
                        knownEnd = false,
                        releaseForeground = true,
                    ),
                )
                return
            }
            work.withLock {
                if (!isStarting(request)) return@withLock
                currentState.value = VehicleRecordingState.Loading
                if (!allowsStart(request.canStart)) {
                    requestEnd(request, End(RecordingRunStatus.FAILED, BackgroundRecordingState.Reason.BLOCKED, knownEnd = true))
                    return@withLock
                }
                val prior = runRepository.observe(request.source.sessionId).first()
                if (!validRecoveredRows(request.source.sessionId, prior, allowOwnOpen = true)) {
                    requestEnd(request, End(RecordingRunStatus.FAILED, BackgroundRecordingState.Reason.RECOVERY, knownEnd = false))
                    return@withLock
                }
                val preparingContext = currentCoroutineContext()
                val result =
                    withContext(NonCancellable) {
                        preparingContext.ensureActive()
                        runRepository
                            .open(request.source, request.runId, processId, clock.millis()) {
                                preparingContext.ensureActive()
                                isStarting(request) && allowsStart(request.canStart)
                            }.also { opened ->
                                // Cleanup joins preparation, so a committed row cannot lose its receipt at cancellation.
                                if (opened is RecordingRunOpenResult.Opened) {
                                    synchronized(guard) { request.opened = true }
                                }
                            }
                    }
                when (result) {
                    is RecordingRunOpenResult.Opened -> {
                        if (result.run.runId != request.runId || result.run.sessionId != request.source.sessionId ||
                            result.run.processId != processId || result.run.status != RecordingRunStatus.OPEN
                        ) {
                            requestEnd(
                                request,
                                End(
                                    RecordingRunStatus.FAILED,
                                    BackgroundRecordingState.Reason.PERSISTENCE,
                                    knownEnd = false,
                                ),
                            )
                            return@withLock
                        }
                    }
                    RecordingRunOpenResult.AlreadyOpen -> {
                        requestEnd(request, End(RecordingRunStatus.FAILED, BackgroundRecordingState.Reason.ALREADY_OPEN, knownEnd = false))
                        return@withLock
                    }
                    RecordingRunOpenResult.SourceChanged -> {
                        requestEnd(request, End(RecordingRunStatus.FAILED, BackgroundRecordingState.Reason.SOURCE_CHANGED, knownEnd = true))
                        return@withLock
                    }
                    RecordingRunOpenResult.Blocked -> {
                        requestEnd(request, End(RecordingRunStatus.FAILED, BackgroundRecordingState.Reason.BLOCKED, knownEnd = true))
                        return@withLock
                    }
                    RecordingRunOpenResult.InvalidTime -> {
                        requestEnd(request, End(RecordingRunStatus.FAILED, BackgroundRecordingState.Reason.INVALID_TIME, knownEnd = false))
                        return@withLock
                    }
                }
                preparingContext.ensureActive()
                if (!isStarting(request) || !allowsStart(request.canStart)) {
                    requestEnd(request, End(RecordingRunStatus.FAILED, BackgroundRecordingState.Reason.BLOCKED, knownEnd = true))
                    return@withLock
                }
                synchronized(guard) {
                    if (!isStarting(request) || !allowsStart(request.canStart)) {
                        requestEnd(request, End(RecordingRunStatus.FAILED, BackgroundRecordingState.Reason.BLOCKED, knownEnd = true))
                        return@synchronized
                    }
                    request.dispatched = true
                    startService(request.runId, request.source.sessionId)
                    if (request.ending == null && request.recording == null) {
                        request.timeout =
                            scope.launch {
                                delay(serviceStartTimeoutMillis)
                                synchronized(guard) {
                                    if (request.recording == null && isStarting(request)) {
                                        requestEnd(
                                            request,
                                            End(
                                                RecordingRunStatus.FAILED,
                                                BackgroundRecordingState.Reason.SERVICE_START,
                                                knownEnd = false,
                                            ),
                                        )
                                    }
                                }
                            }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            if (request.ending == null) {
                requestEnd(request, End(RecordingRunStatus.FAILED, BackgroundRecordingState.Reason.OPEN, knownEnd = false))
            }
            throw cancelled
        } catch (_: Exception) {
            val reason = if (request.dispatched) BackgroundRecordingState.Reason.SERVICE_START else BackgroundRecordingState.Reason.OPEN
            requestEnd(request, End(RecordingRunStatus.FAILED, reason, knownEnd = false))
        }
    }

    private suspend fun recordBackground(request: Request) {
        try {
            work.withLock {
                if (!isLive(request)) return@withLock
                currentState.value = VehicleRecordingState.Loading
                sessionRepository
                    .observeSessions()
                    .onCompletion { cause -> if (cause == null) error("Session observation completed") }
                    .map { sessions ->
                        val active = activeSession(sessions)
                        updateSource(request, active)
                        when {
                            active == request.source -> active.sessionId
                            active == null && sessions.any { it.sessionId == request.source.sessionId && !it.isActive } ->
                                throw SessionEnded()
                            else -> throw SourceChanged()
                        }
                    }.distinctUntilChanged()
                    .collectLatest { sessionId ->
                        currentState.value = VehicleRecordingState.Recording
                        try {
                            val result = recorder.record(sessionId)
                            if (result is VehicleObservationRecordingResult.FinishedSession) {
                                requestEnd(request, End(RecordingRunStatus.STOPPED, null, knownEnd = true, optOut = true))
                            } else {
                                val reason = recordingReason(result)
                                requestEnd(
                                    request,
                                    End(
                                        RecordingRunStatus.FAILED,
                                        backgroundReason(reason),
                                        knownEnd = true,
                                        recordingReason = reason,
                                    ),
                                )
                            }
                        } catch (cancelled: CancellationException) {
                            if (currentCoroutineContext().isActive) {
                                requestEnd(
                                    request,
                                    End(
                                        RecordingRunStatus.FAILED,
                                        BackgroundRecordingState.Reason.OBSERVATION,
                                        knownEnd = false,
                                    ),
                                )
                            }
                            throw cancelled
                        } catch (_: Exception) {
                            requestEnd(
                                request,
                                End(
                                    RecordingRunStatus.FAILED,
                                    BackgroundRecordingState.Reason.OBSERVATION,
                                    knownEnd = false,
                                ),
                            )
                        }
                        awaitCancellation()
                    }
            }
        } catch (_: SessionEnded) {
            requestEnd(request, End(RecordingRunStatus.STOPPED, null, knownEnd = true, optOut = true))
        } catch (_: SourceChanged) {
            requestEnd(request, End(RecordingRunStatus.FAILED, BackgroundRecordingState.Reason.SOURCE_CHANGED, knownEnd = true))
        } catch (cancelled: CancellationException) {
            if (request.ending == null) {
                requestEnd(request, End(RecordingRunStatus.FAILED, BackgroundRecordingState.Reason.SESSION_READ, knownEnd = false))
            }
            throw cancelled
        } catch (_: Exception) {
            requestEnd(request, End(RecordingRunStatus.FAILED, BackgroundRecordingState.Reason.SESSION_READ, knownEnd = false))
        }
    }

    private fun requestEnd(
        request: Request,
        end: End,
    ): Job? =
        synchronized(guard) {
            if (owner.value !== request || request.completed.isCompleted || request.ending != null) return@synchronized request.cleanup
            request.ending = end
            request.canStart = null
            currentBackground.value =
                if (end.reason == null) {
                    BackgroundRecordingState.Stopping(request.runId, request.source.sessionId)
                } else {
                    BackgroundRecordingState.Failed(request.runId, request.source.sessionId, end.reason)
                }
            if (end.reason != null) currentState.value = VehicleRecordingState.Failed(end.recordingReason)
            request.cleanup = scope.launch(start = CoroutineStart.LAZY) { finish(request, end) }
            request.cleanup?.start()
            request.cleanup
        }

    private suspend fun finish(
        request: Request,
        end: End,
    ) {
        var failure = end.reason
        var cleanupFailed = false
        try {
            request.preparing?.cancelAndJoin()
            request.recording?.cancelAndJoin()
            request.timeout?.cancelAndJoin()
            if (request.opened) {
                val endedAt = if (end.knownEnd) runCatching { clock.millis() }.getOrNull() else null
                val status = if (end.knownEnd && endedAt == null) RecordingRunStatus.FAILED else end.status
                var saved =
                    try {
                        runRepository.finish(request.runId, processId, status, endedAt)
                    } catch (_: Exception) {
                        false
                    }
                if (!saved && endedAt != null) {
                    saved =
                        try {
                            runRepository.finish(request.runId, processId, RecordingRunStatus.FAILED, null)
                        } catch (_: Exception) {
                            false
                        }
                    failure = BackgroundRecordingState.Reason.PERSISTENCE
                    cleanupFailed = true
                }
                if (!saved || (end.knownEnd && endedAt == null)) {
                    failure = BackgroundRecordingState.Reason.PERSISTENCE
                    cleanupFailed = true
                }
            }
            if (request.dispatched) {
                try {
                    stopService(request.runId)
                } catch (_: Exception) {
                    failure = BackgroundRecordingState.Reason.SERVICE_STOP
                    cleanupFailed = true
                }
            }
        } finally {
            synchronized(guard) {
                if (owner.value === request) {
                    if (markerSource.value?.owner === request) setMarkerSource(null)
                    // Every release must follow these writes so reentrant foreground state survives.
                    if (request.optOutRequested) {
                        currentBackground.value =
                            if (cleanupFailed) {
                                BackgroundRecordingState.Failed(null, request.source.sessionId, checkNotNull(failure))
                            } else {
                                BackgroundRecordingState.Disabled
                            }
                        currentState.value = VehicleRecordingState.Paused
                        owner.value = null
                    } else if (end.releaseForeground && !request.opened) {
                        currentBackground.value =
                            BackgroundRecordingState.Failed(
                                null,
                                request.source.sessionId,
                                failure ?: BackgroundRecordingState.Reason.RECOVERY,
                            )
                        currentState.value = VehicleRecordingState.Paused
                        owner.value = null
                    } else if (end.optOut && failure == null) {
                        currentBackground.value = BackgroundRecordingState.Disabled
                        currentState.value = VehicleRecordingState.Paused
                        owner.value = null
                    } else {
                        currentBackground.value =
                            BackgroundRecordingState.Failed(
                                request.runId,
                                request.source.sessionId,
                                failure ?: BackgroundRecordingState.Reason.PERSISTENCE,
                            )
                        currentState.value = VehicleRecordingState.Failed(end.recordingReason)
                    }
                }
                request.completed.complete(Unit)
            }
        }
    }

    private suspend fun recordForeground(sourceOwner: Any) {
        retries.collectLatest {
            currentState.value = VehicleRecordingState.Loading
            try {
                sessionRepository
                    .observeSessions()
                    .onCompletion { cause -> if (cause == null) error("Session observation completed") }
                    .map { sessions ->
                        val active = activeSession(sessions)
                        updateSource(sourceOwner, active)
                        active?.sessionId
                    }.distinctUntilChanged()
                    .collectLatest { sessionId ->
                        if (sessionId == null) {
                            currentState.value = VehicleRecordingState.Idle
                        } else {
                            recordForegroundSession(sessionId)
                        }
                    }
                currentState.value = VehicleRecordingState.Failed(VehicleRecordingState.Reason.SESSION_READ)
            } catch (cancelled: CancellationException) {
                if (currentCoroutineContext().isActive) {
                    clearSource(sourceOwner)
                    currentState.value = VehicleRecordingState.Failed(VehicleRecordingState.Reason.SESSION_READ)
                }
                throw cancelled
            } catch (_: Exception) {
                clearSource(sourceOwner)
                currentState.value = VehicleRecordingState.Failed(VehicleRecordingState.Reason.SESSION_READ)
            }
            awaitCancellation()
        }
    }

    private suspend fun recordForegroundSession(sessionId: String) {
        currentState.value = VehicleRecordingState.Recording
        val result =
            try {
                recorder.record(sessionId)
            } catch (cancelled: CancellationException) {
                if (currentCoroutineContext().isActive) {
                    currentState.value = VehicleRecordingState.Failed(VehicleRecordingState.Reason.OBSERVATION)
                }
                throw cancelled
            } catch (_: Exception) {
                currentState.value = VehicleRecordingState.Failed(VehicleRecordingState.Reason.OBSERVATION)
                return
            }
        currentState.value = VehicleRecordingState.Failed(recordingReason(result))
    }

    private fun updateSource(
        sourceOwner: Any,
        session: PracticeSession?,
    ) {
        synchronized(guard) { setMarkerSource(session?.let { MarkerSource(sourceOwner, it) }) }
    }

    /** Called under guard so a historical warning leaves with its actual source receipt. */
    private fun setMarkerSource(source: MarkerSource?) {
        val previousSource = markerSource.value
        markerSource.value = source
        if (owner.value != null) return
        val previousSession =
            when (val previous = currentBackground.value) {
                is BackgroundRecordingState.Interrupted -> previous.sessionId
                is BackgroundRecordingState.Failed ->
                    previous.sessionId.takeIf { previous.runId == null && previous.reason == BackgroundRecordingState.Reason.PREVIOUS_RUN }
                else -> null
            }
        if (previousSession != null && (previousSource != source || previousSession != source?.session?.sessionId)) {
            currentBackground.value = BackgroundRecordingState.Disabled
        }
    }

    private fun clearSource(sourceOwner: Any) {
        synchronized(guard) {
            if (markerSource.value?.owner === sourceOwner) setMarkerSource(null)
        }
    }

    private fun recoveryFailed(source: MarkerSource?) {
        synchronized(guard) {
            if (owner.value == null && (source == null || markerSource.value == source)) {
                currentBackground.value =
                    BackgroundRecordingState.Failed(
                        null,
                        source?.session?.sessionId,
                        BackgroundRecordingState.Reason.RECOVERY,
                    )
            }
        }
    }

    private fun validRecoveredRows(
        sessionId: String,
        runs: List<VehicleRecordingRun>,
        allowOwnOpen: Boolean = false,
    ): Boolean =
        runs.all {
            it.sessionId == sessionId &&
                !(it.status == RecordingRunStatus.OPEN && (!allowOwnOpen || it.processId != processId))
        }

    private fun isStarting(request: Request): Boolean = synchronized(guard) { owner.value === request && request.ending == null }

    private fun isLive(request: Request): Boolean = synchronized(guard) { owner.value === request && request.ending == null }

    private fun allowsStart(canStart: (() -> Boolean)? = { true }): Boolean =
        try {
            canStart?.invoke() == true && allowsPracticeInteraction(vehicleGateway.state.value, restrictionsProvider.restrictions.value)
        } catch (_: Exception) {
            false
        }

    private fun activeSession(sessions: List<PracticeSession>): PracticeSession? {
        val active = sessions.filter { it.isActive }
        check(active.size <= 1)
        return active.singleOrNull()
    }

    private fun recordingReason(result: VehicleObservationRecordingResult): VehicleRecordingState.Reason =
        when (result) {
            VehicleObservationRecordingResult.AlreadyRunning -> VehicleRecordingState.Reason.ALREADY_RUNNING
            is VehicleObservationRecordingResult.MissingSession -> VehicleRecordingState.Reason.SESSION_MISSING
            is VehicleObservationRecordingResult.FinishedSession -> VehicleRecordingState.Reason.SESSION_FINISHED
            is VehicleObservationRecordingResult.InvalidTime -> VehicleRecordingState.Reason.INVALID_TIME
            is VehicleObservationRecordingResult.WriteFailed -> VehicleRecordingState.Reason.WRITE
        }

    private fun backgroundReason(reason: VehicleRecordingState.Reason): BackgroundRecordingState.Reason =
        when (reason) {
            VehicleRecordingState.Reason.ALREADY_RUNNING -> BackgroundRecordingState.Reason.ALREADY_OPEN
            VehicleRecordingState.Reason.SESSION_READ,
            VehicleRecordingState.Reason.SESSION_MISSING,
            VehicleRecordingState.Reason.SESSION_FINISHED,
            ->
                BackgroundRecordingState.Reason.SESSION_READ
            VehicleRecordingState.Reason.INVALID_TIME -> BackgroundRecordingState.Reason.INVALID_TIME
            VehicleRecordingState.Reason.WRITE, VehicleRecordingState.Reason.OBSERVATION -> BackgroundRecordingState.Reason.OBSERVATION
        }

    private class Request(
        val runId: String,
        val source: PracticeSession,
        var canStart: (() -> Boolean)?,
    ) {
        var preparing: Job? = null
        var recording: Job? = null
        var timeout: Job? = null
        var cleanup: Job? = null
        var opened = false
        var optOutRequested = false
        var dispatched = false
        var ending: End? = null
        val completed = CompletableDeferred<Unit>()
    }

    private data class MarkerSource(
        val owner: Any,
        val session: PracticeSession,
    )

    private data class End(
        val status: RecordingRunStatus,
        val reason: BackgroundRecordingState.Reason?,
        val knownEnd: Boolean,
        val optOut: Boolean = false,
        val releaseForeground: Boolean = false,
        val recordingReason: VehicleRecordingState.Reason = VehicleRecordingState.Reason.OBSERVATION,
    )

    private class SessionEnded : RuntimeException()

    private class SourceChanged : RuntimeException()
}
