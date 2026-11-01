package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import kotlin.coroutines.CoroutineContext

/** One explicit foreground-only location stream. Vehicle recording and its FGS are independent. */
class ForegroundLocationRecordingController(
    private val gateway: LocationUpdateGateway,
    private val sessions: PracticeSessionRepository,
    private val locationRepository: LocationRecordingRepository,
    private val vehicle: VehicleGateway,
    private val restrictions: DrivingRestrictionsProvider,
    private val clock: Clock,
    private val elapsedRealtimeMillis: () -> Long,
    private val scope: CoroutineScope,
    private val nextRunId: () -> String,
    private val processId: String,
) : LocationRecordingController {
    private val guard = Any()
    private val hosts = Mutex()
    private val ids = mutableSetOf<String>()
    private val current = MutableStateFlow<LocationRecordingState>(LocationRecordingState.Paused)
    override val state = current.asStateFlow()
    private var host: Host? = null
    private var owner: Request? = null
    private var lastFailure: Throwable? = null
    private var recoverySucceeded = false

    init {
        require(processId.isNotBlank())
    }

    private val recovered =
        scope.async {
            try {
                locationRepository.recoverInterrupted(processId)
                synchronized(guard) { recoverySucceeded = true }
                true
            } catch (failure: Throwable) {
                synchronized(guard) {
                    lastFailure = failure
                    current.value =
                        LocationRecordingState.Failed(null, null, LocationRecordingState.Reason.RECOVERY, true, failure.javaClass.name)
                }
                false
            }
        }

    override suspend fun recordWhileStarted() = hosts.withLock { recordHost() }

    private suspend fun recordHost() {
        val receipt = Host(currentCoroutineContext())
        synchronized(guard) {
            check(host == null)
            host = receipt
        }
        try {
            if (!recovered.await()) awaitCancellation()
            synchronized(guard) {
                if (host === receipt && owner == null &&
                    current.value == LocationRecordingState.Paused
                ) {
                    current.value = LocationRecordingState.Idle
                }
            }
            try {
                sessions.observeSessions().collect { rows ->
                    currentCoroutineContext().ensureActive()
                    synchronized(guard) {
                        if (host !== receipt || !receipt.live) return@synchronized
                        check(rows.map { it.sessionId }.distinct().size == rows.size)
                        val active = rows.filter { it.isActive }
                        check(active.size <= 1)
                        receipt.source = active.singleOrNull()
                        receipt.known = true
                        val request = owner?.takeIf { it.host === receipt && it.ending == null }
                        if (request != null && receipt.source != request.source) {
                            val finished = rows.any { sameFinishedSource(request.source, it) }
                            requestEnd(
                                request,
                                if (finished) {
                                    End(LocationRecordingStopReason.SESSION_FINISHED)
                                } else {
                                    End(LocationRecordingStopReason.SOURCE_CHANGED, LocationRecordingState.Reason.SOURCE_CHANGED)
                                },
                            )
                        }
                    }
                }
                throw SourceStreamEnded()
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                sourceFailed(receipt, cancelled)
                awaitCancellation()
            } catch (failure: Throwable) {
                sourceFailed(receipt, failure)
                awaitCancellation()
            }
        } finally {
            val cleanup =
                synchronized(guard) {
                    receipt.live = false
                    receipt.known = false
                    receipt.source = null
                    val request = owner?.takeIf { it.host === receipt }
                    if (request != null && request.ending == null) requestEnd(request, End(LocationRecordingStopReason.HOST_STOPPED))
                    request?.cleanup
                }
            withContext(NonCancellable) { cleanup?.join() }
            synchronized(guard) {
                if (host === receipt) host = null
                if (owner == null && current.value == LocationRecordingState.Idle) current.value = LocationRecordingState.Paused
            }
        }
    }

    override fun start(
        expected: PracticeSession,
        canStart: () -> Boolean,
    ): Boolean =
        synchronized(guard) {
            val activeHost = host ?: return@synchronized false
            if (owner != null || !expected.isActive || !startSource(activeHost, expected) || !startGate(canStart)) return@synchronized false
            val permitted =
                try {
                    gateway.hasPermission()
                } catch (failure: Throwable) {
                    if (owner == null && startSource(activeHost, expected)) {
                        lastFailure = failure
                        current.value =
                            LocationRecordingState.Failed(
                                expected,
                                null,
                                LocationRecordingState.Reason.PERMISSION_CHECK,
                                false,
                                failure.javaClass.name,
                            )
                    }
                    return@synchronized false
                }
            if (!permitted) {
                if (owner == null && startSource(activeHost, expected)) {
                    lastFailure = null
                    current.value = LocationRecordingState.Failed(expected, null, LocationRecordingState.Reason.PERMISSION_DENIED)
                }
                return@synchronized false
            }
            // Permission reads may synchronously re-enter the host/source or request boundary.
            if (owner != null || !startSource(activeHost, expected) || !startGate(canStart) ||
                owner != null || !startSource(activeHost, expected)
            ) {
                return@synchronized false
            }
            if (!recoverySucceeded) return@synchronized false
            val id =
                try {
                    nextRunId().also { require(it.isNotBlank() && ids.add(it)) }
                } catch (failure: Throwable) {
                    lastFailure = failure
                    current.value =
                        LocationRecordingState.Failed(expected, null, LocationRecordingState.Reason.OPEN, false, failure.javaClass.name)
                    return@synchronized false
                }
            val request = Request(id, expected, activeHost, canStart)
            request.preparing = scope.launch(start = CoroutineStart.LAZY) { prepare(request) }
            lastFailure = null
            owner = request
            current.value = LocationRecordingState.Starting(id, expected)
            request.preparing?.invokeOnCompletion { failure ->
                if (failure != null && request.ending == null) {
                    remember(request, failure, LocationRecordingState.Reason.OPEN)
                    requestEnd(request, End(LocationRecordingStopReason.WRITE_ERROR, LocationRecordingState.Reason.OPEN, false))
                }
            }
            request.preparing?.start()
            true
        }

    override fun stop(expectedRunId: String): Boolean =
        synchronized(guard) {
            val request = owner?.takeIf { it.id == expectedRunId } ?: return@synchronized false
            if (request.cleanupDone) {
                if (!request.storageUnknown) return@synchronized false
                request.cleanupDone = false
                request.cleanup = scope.launch(NonCancellable, start = CoroutineStart.LAZY) { finish(request) }
                current.value =
                    LocationRecordingState.Stopping(request.id, request.source, request.run, checkNotNull(request.ending).reason)
                request.cleanup?.start()
                return@synchronized true
            }
            if (request.ending != null) return@synchronized false
            requestEnd(request, End(LocationRecordingStopReason.USER_STOP))
            true
        }

    private suspend fun prepare(request: Request) {
        try {
            if (!recovered.await() || !starting(request)) return
            val times =
                try {
                    val wall = clock.millis()
                    val elapsed = elapsedRealtimeMillis()
                    require(wall > 0 && elapsed >= 0)
                    wall to elapsed
                } catch (failure: Throwable) {
                    remember(request, failure, LocationRecordingState.Reason.INVALID_TIME)
                    requestEnd(request, End(LocationRecordingStopReason.WRITE_ERROR, LocationRecordingState.Reason.INVALID_TIME, false))
                    return
                }
            val (wall, elapsed) = times
            request.startedWall = wall
            request.startedElapsed = elapsed
            val preparing = currentCoroutineContext()
            val result =
                withContext(NonCancellable) {
                    preparing.ensureActive()
                    request.beginAttempted = true
                    locationRepository
                        .begin(request.source, request.id, processId, wall, elapsed) {
                            preparing.ensureActive()
                            val allowed = starting(request) && startGate(request.canStart) && freshPermission(request)
                            preparing.ensureActive()
                            allowed && startGate(request.canStart) && starting(request)
                        }.also { acknowledged ->
                            if (acknowledged is LocationRecordingBeginResult.Begun) {
                                check(sameOwner(request, acknowledged.run) && acknowledged.run.status == LocationRecordingRunStatus.OPEN)
                                synchronized(guard) { request.run = acknowledged.run }
                            } else {
                                request.noRunConfirmed = true
                            }
                        }
                }
            preparing.ensureActive()
            when (result) {
                is LocationRecordingBeginResult.Begun ->
                    synchronized(guard) {
                        if (!starting(request) || !startGate(request.canStart) || !freshPermission(request) ||
                            !startGate(request.canStart) || !starting(request)
                        ) {
                            if (request.ending == null) requestEnd(request, permissionEnd(request))
                        } else {
                            // Keep the accepted start receipt through the SDK's local register handoff.
                            request.recording = scope.launch(start = CoroutineStart.LAZY) { record(request) }
                            request.recording?.invokeOnCompletion { failure ->
                                if (failure != null && request.ending == null) {
                                    remember(request, failure, LocationRecordingState.Reason.ADAPTER_ERROR)
                                    requestEnd(
                                        request,
                                        End(LocationRecordingStopReason.ADAPTER_ERROR, LocationRecordingState.Reason.ADAPTER_ERROR, false),
                                    )
                                }
                            }
                            request.recording?.start()
                        }
                    }
                LocationRecordingBeginResult.FinishedSession ->
                    requestEnd(
                        request,
                        End(LocationRecordingStopReason.SESSION_FINISHED, LocationRecordingState.Reason.SESSION_FINISHED),
                    )
                LocationRecordingBeginResult.SourceChanged, LocationRecordingBeginResult.MissingSession ->
                    requestEnd(
                        request,
                        End(LocationRecordingStopReason.SOURCE_CHANGED, LocationRecordingState.Reason.SOURCE_CHANGED),
                    )
                LocationRecordingBeginResult.AlreadyOpen ->
                    requestEnd(
                        request,
                        End(LocationRecordingStopReason.WRITE_ERROR, LocationRecordingState.Reason.ALREADY_OPEN, false),
                    )
                LocationRecordingBeginResult.InvalidTime ->
                    requestEnd(
                        request,
                        End(LocationRecordingStopReason.WRITE_ERROR, LocationRecordingState.Reason.INVALID_TIME, false),
                    )
                LocationRecordingBeginResult.Blocked -> requestEnd(request, permissionEnd(request))
            }
        } catch (failure: Throwable) {
            if (request.ending == null) {
                remember(request, failure, LocationRecordingState.Reason.OPEN)
                requestEnd(request, End(LocationRecordingStopReason.WRITE_ERROR, LocationRecordingState.Reason.OPEN, false))
            }
        }
    }

    private suspend fun record(request: Request) {
        try {
            gateway.updates({ streamAllowed(request) }, { registered(request) }, { cleanupFailed(request, it) }).collect { reading ->
                currentCoroutineContext().ensureActive()
                if (reading is LocationUpdateReading.Error && reading.origin == LocationUpdateErrorOrigin.SDK_CALLBACK) {
                    synchronized(guard) {
                        if (live(request) && !request.startRetired) {
                            // The SDK error occurred before cleanup even if its terminal metadata
                            // is intentionally delivered after the cleanup callback.
                            if (request.failureReason == null || request.cleanupWasPrimary) {
                                request.failureReason = LocationRecordingState.Reason.SDK_ERROR
                            }
                            request.cleanupWasPrimary = false
                            request.errorClassName = reading.exceptionClassName
                        }
                    }
                }
                // A synchronous real callback may arrive during register. Its receipt is buffered
                // until the local registration handoff. A terminal failure carries no coordinates
                // and may legitimately report a synchronous SDK failure without an ACK.
                if (reading is LocationUpdateReading.Sample || reading is LocationUpdateReading.Empty) {
                    request.registeredReceipt.await()
                }
                append(request, reading)
            }
            if (live(request)) {
                if (request.startRetired) {
                    requestEnd(request, blockedStart())
                } else {
                    terminalAdapterFailure(request, SourceStreamEnded())
                }
            }
        } catch (cancelled: CancellationException) {
            if (request.ending == null && currentCoroutineContext().isActive) {
                if (request.startRetired) {
                    requestEnd(request, blockedStart())
                } else {
                    terminalAdapterFailure(request, cancelled)
                }
            } else if (request.ending == null) {
                remember(request, cancelled, LocationRecordingState.Reason.ADAPTER_ERROR)
                requestEnd(request, End(LocationRecordingStopReason.ADAPTER_ERROR, LocationRecordingState.Reason.ADAPTER_ERROR, false))
            } else if (cancelled.suppressed.isNotEmpty()) {
                remember(request, cancelled, LocationRecordingState.Reason.ADAPTER_ERROR)
            }
        } catch (failure: Throwable) {
            if (request.ending == null) {
                terminalAdapterFailure(request, failure)
            } else {
                remember(request, failure, LocationRecordingState.Reason.ADAPTER_ERROR)
            }
        }
    }

    private fun streamAllowed(request: Request): Boolean =
        synchronized(guard) {
            if (!live(request)) return@synchronized false
            if (request.registered) return@synchronized true
            val allowed = !request.startRetired && startGate(request.canStart) && live(request)
            if (!allowed && live(request)) {
                request.startRetired = true
                request.registeredReceipt.cancel(CancellationException("Original location start receipt retired"))
            }
            allowed
        }

    private fun registered(request: Request) =
        synchronized(guard) {
            if (!live(request) || request.registered) return@synchronized
            if (request.startRetired || !startGate(request.canStart) || !live(request)) {
                request.startRetired = true
                requestEnd(request, blockedStart())
                return@synchronized
            }
            request.registered = true
            request.canStart = null
            current.value = LocationRecordingState.Recording(checkNotNull(request.run))
            request.registeredReceipt.complete(Unit)
        }

    private fun blockedStart() = End(LocationRecordingStopReason.ADAPTER_ERROR, LocationRecordingState.Reason.BLOCKED)

    private suspend fun append(
        request: Request,
        reading: LocationUpdateReading,
    ) {
        val observedAt =
            try {
                clock.millis().also { require(it > 0) }
            } catch (failure: Throwable) {
                remember(request, failure, LocationRecordingState.Reason.INVALID_TIME)
                requestEnd(request, End(LocationRecordingStopReason.WRITE_ERROR, LocationRecordingState.Reason.INVALID_TIME, false))
                return
            }
        val phase = Any()
        val accepted =
            synchronized(guard) {
                if (!live(request) || request.startRetired) {
                    false
                } else {
                    request.phase = phase
                    request.receiptFloor = maxOf(request.receiptFloor, reading.receivedAtElapsedRealtimeMillis)
                    true
                }
            }
        if (!accepted) return
        val result =
            try {
                val context = currentCoroutineContext()
                locationRepository
                    .append(request.source, checkNotNull(request.run), reading, observedAt) {
                        context.ensureActive()
                        val allowed = phaseLive(request, phase) && (reading !is LocationUpdateReading.Sample || freshPermission(request))
                        context.ensureActive()
                        allowed && phaseLive(request, phase)
                    }.also { acknowledged ->
                        if (acknowledged is LocationRecordingAppendResult.Appended) {
                            check(
                                acknowledged.event.sessionId == request.source.sessionId && acknowledged.event.runId == request.id &&
                                    acknowledged.event.reading == reading && acknowledged.event.observedAtEpochMillis == observedAt,
                            )
                        }
                    }
            } catch (failure: Throwable) {
                synchronized(guard) { request.appendUnconfirmed = true }
                remember(request, failure, LocationRecordingState.Reason.WRITE)
                requestEnd(request, End(LocationRecordingStopReason.WRITE_ERROR, LocationRecordingState.Reason.WRITE, false))
                throw failure
            }
        synchronized(guard) { if (request.phase === phase) request.phase = null }
        if (!live(request)) return
        when (result) {
            is LocationRecordingAppendResult.Appended -> {
                when {
                    reading is LocationUpdateReading.PermissionDenied ->
                        requestEnd(
                            request,
                            End(LocationRecordingStopReason.PERMISSION_DENIED, LocationRecordingState.Reason.PERMISSION_DENIED),
                        )
                    reading is LocationUpdateReading.Unsupported ->
                        requestEnd(
                            request,
                            End(LocationRecordingStopReason.UNSUPPORTED, LocationRecordingState.Reason.UNSUPPORTED),
                        )
                    reading is LocationUpdateReading.NoUpdate ->
                        requestEnd(
                            request,
                            End(LocationRecordingStopReason.NO_UPDATE, LocationRecordingState.Reason.NO_UPDATE),
                        )
                    reading is LocationUpdateReading.Error -> {
                        request.errorClassName = reading.exceptionClassName
                        val sdk = reading.origin == LocationUpdateErrorOrigin.SDK_CALLBACK
                        requestEnd(
                            request,
                            End(
                                if (sdk) LocationRecordingStopReason.SDK_ERROR else LocationRecordingStopReason.ADAPTER_ERROR,
                                if (sdk) LocationRecordingState.Reason.SDK_ERROR else LocationRecordingState.Reason.ADAPTER_ERROR,
                            ),
                        )
                    }
                    result.reachedLimit -> requestEnd(request, End(LocationRecordingStopReason.LIMIT_REACHED))
                    else -> Unit
                }
            }
            LocationRecordingAppendResult.FinishedSession -> requestEnd(request, End(LocationRecordingStopReason.SESSION_FINISHED))
            LocationRecordingAppendResult.SourceChanged, LocationRecordingAppendResult.MissingSession ->
                requestEnd(
                    request,
                    End(LocationRecordingStopReason.SOURCE_CHANGED, LocationRecordingState.Reason.SOURCE_CHANGED),
                )
            LocationRecordingAppendResult.LimitReached -> requestEnd(request, End(LocationRecordingStopReason.LIMIT_REACHED))
            LocationRecordingAppendResult.Blocked -> {
                if (reading is LocationUpdateReading.Sample && (request.permissionDenied || request.permissionFailure != null)) {
                    val receipt = elapsedRealtimeMillis()
                    val metadata =
                        if (request.permissionDenied) {
                            LocationUpdateReading.PermissionDenied(receipt)
                        } else {
                            LocationUpdateReading.Error(
                                LocationUpdateErrorOrigin.ADAPTER,
                                checkNotNull(request.permissionFailure).javaClass.name,
                                receipt,
                            )
                        }
                    append(request, metadata)
                } else {
                    requestEnd(request, End(LocationRecordingStopReason.WRITE_ERROR, LocationRecordingState.Reason.WRITE, false))
                }
            }
            LocationRecordingAppendResult.RunClosed, LocationRecordingAppendResult.MissingRun, LocationRecordingAppendResult.OwnerChanged,
            LocationRecordingAppendResult.InvalidTime,
            ->
                requestEnd(
                    request,
                    End(LocationRecordingStopReason.WRITE_ERROR, LocationRecordingState.Reason.WRITE, false),
                )
        }
    }

    private suspend fun terminalAdapterFailure(
        request: Request,
        failure: Throwable,
    ) {
        remember(request, failure, LocationRecordingState.Reason.ADAPTER_ERROR)
        if (!live(request)) return
        try {
            append(request, LocationUpdateReading.Error(LocationUpdateErrorOrigin.ADAPTER, failure.javaClass.name, elapsedRealtimeMillis()))
        } catch (cleanup: Throwable) {
            remember(request, cleanup, LocationRecordingState.Reason.WRITE)
        } finally {
            requestEnd(request, End(LocationRecordingStopReason.ADAPTER_ERROR, LocationRecordingState.Reason.ADAPTER_ERROR, false))
        }
    }

    private fun requestEnd(
        request: Request,
        end: End,
    ) = synchronized(guard) {
        if (owner !== request || request.ending != null) return@synchronized
        request.ending = end
        request.canStart = null
        request.phase = null // Revoke the sink/append phase before cancellation or any cleanup call.
        if (end.uiReason != null && request.failureReason == null) request.failureReason = end.uiReason
        request.cleanup = scope.launch(NonCancellable, start = CoroutineStart.LAZY) { finish(request) }
        current.value = LocationRecordingState.Stopping(request.id, request.source, request.run, end.reason)
        request.cleanup?.start()
    }

    private suspend fun finish(request: Request) {
        try {
            try {
                request.preparing?.cancelAndJoin()
            } catch (
                failure: Throwable,
            ) {
                remember(request, failure, LocationRecordingState.Reason.OPEN)
            }
            try {
                request.recording?.cancelAndJoin()
            } catch (
                failure: Throwable,
            ) {
                remember(request, failure, LocationRecordingState.Reason.ADAPTER_ERROR)
            }
            if ((request.beginAttempted && request.run == null && !request.noRunConfirmed) || request.appendUnconfirmed) {
                try {
                    val stored = locationRepository.observe(request.source.sessionId).first()
                    val matches = stored.runs.filter { it.runId == request.id }
                    check(matches.size <= 1)
                    val actual = matches.singleOrNull()
                    if (actual != null) {
                        check(sameOwner(request, actual))
                        request.run = actual
                        request.receiptFloor =
                            maxOf(
                                request.receiptFloor,
                                stored.events.filter { it.runId == request.id }.maxOfOrNull { it.reading.receivedAtElapsedRealtimeMillis }
                                    ?: 0,
                            )
                    } else {
                        request.run = null
                        request.noRunConfirmed = true
                    }
                    request.appendUnconfirmed = false
                    request.storageUnknown = false
                } catch (failure: Throwable) {
                    request.storageUnknown = true
                    remember(request, failure, LocationRecordingState.Reason.END)
                }
            }
            val opened = request.run
            if (opened != null) {
                val end = checkNotNull(request.ending)
                val normalFailure = request.failureReason != null && end.uiReason == null
                val reason = if (normalFailure) failureStopReason(checkNotNull(request.failureReason)) else end.reason
                var wall: Long? = null
                var elapsed: Long? = null
                if (end.knownTime && !normalFailure) {
                    try {
                        val nowWall = clock.millis()
                        val nowElapsed = elapsedRealtimeMillis()
                        require(nowWall > 0 && nowElapsed >= maxOf(opened.startedAtElapsedRealtimeMillis, request.receiptFloor))
                        wall = nowWall
                        elapsed = nowElapsed
                    } catch (failure: Throwable) {
                        remember(request, failure, LocationRecordingState.Reason.INVALID_TIME)
                    }
                }
                try {
                    val ended = locationRepository.end(opened, reason.status, reason, wall, elapsed)
                    when (ended) {
                        is LocationRecordingEndResult.Ended -> {
                            check(sameOwner(request, ended.run) && ended.run.status != LocationRecordingRunStatus.OPEN)
                            request.run = ended.run
                            request.storageUnknown =
                                request.appendUnconfirmed
                        }
                        is LocationRecordingEndResult.AlreadyEnded -> {
                            check(sameOwner(request, ended.run) && ended.run.status != LocationRecordingRunStatus.OPEN)
                            request.run = ended.run
                            request.storageUnknown =
                                request.appendUnconfirmed
                        }
                        LocationRecordingEndResult.MissingRun -> {
                            request.run = null
                            request.noRunConfirmed = true
                            request.storageUnknown =
                                false
                            request.appendUnconfirmed = false
                        }
                        else -> request.storageUnknown = true
                    }
                } catch (failure: Throwable) {
                    request.storageUnknown = true
                    remember(request, failure, LocationRecordingState.Reason.END)
                }
            }
        } finally {
            synchronized(guard) {
                request.cleanupDone = true
                if (owner === request) {
                    val actual = request.run
                    current.value =
                        when {
                            request.storageUnknown -> failureState(request, true)
                            request.failureReason != null -> failureState(request, false)
                            actual != null && actual.status == LocationRecordingRunStatus.STOPPED ->
                                LocationRecordingState.Stopped(
                                    actual,
                                    checkNotNull(actual.stopReason),
                                )
                            actual != null ->
                                LocationRecordingState.Failed(
                                    request.source,
                                    request.id,
                                    uiReason(checkNotNull(actual.stopReason)),
                                )
                            else -> if (host?.live == true) LocationRecordingState.Idle else LocationRecordingState.Paused
                        }
                    lastFailure = request.failure
                    // Release last so an immediately re-entered new request cannot be overwritten.
                    if (!request.storageUnknown) owner = null
                }
            }
        }
    }

    private fun sourceFailed(
        receipt: Host,
        failure: Throwable,
    ) = synchronized(guard) {
        if (host !== receipt || !receipt.live) return@synchronized
        receipt.known = false
        receipt.source = null
        val request = owner?.takeIf { it.host === receipt }
        if (request != null) {
            remember(request, failure, LocationRecordingState.Reason.SOURCE_READ)
            if (request.ending ==
                null
            ) {
                requestEnd(request, End(LocationRecordingStopReason.ADAPTER_ERROR, LocationRecordingState.Reason.SOURCE_READ, false))
            }
        } else if (owner == null) {
            lastFailure = failure
            current.value =
                LocationRecordingState.Failed(null, null, LocationRecordingState.Reason.SOURCE_READ, false, failure.javaClass.name)
        }
    }

    private fun startSource(
        receipt: Host,
        source: PracticeSession,
    ): Boolean =
        host === receipt && receipt.live && receipt.context.isActive && scope.coroutineContext.isActive && receipt.known &&
            receipt.source == source

    private fun starting(request: Request): Boolean =
        synchronized(guard) {
            owner === request && request.ending == null &&
                startSource(request.host, request.source)
        }

    private fun live(request: Request): Boolean =
        synchronized(guard) {
            owner === request && request.ending == null &&
                startSource(request.host, request.source)
        }

    private fun phaseLive(
        request: Request,
        phase: Any,
    ): Boolean = synchronized(guard) { live(request) && request.phase === phase }

    private fun startGate(predicate: (() -> Boolean)?): Boolean =
        try {
            predicate?.invoke() == true && allowsPracticeInteraction(vehicle.state.value, restrictions.restrictions.value)
        } catch (_: Throwable) {
            false
        }

    private fun freshPermission(request: Request): Boolean {
        val permitted =
            try {
                gateway.hasPermission()
            } catch (failure: Throwable) {
                synchronized(guard) { request.permissionFailure = failure }
                remember(request, failure, LocationRecordingState.Reason.PERMISSION_CHECK)
                false
            }
        if (!permitted) synchronized(guard) { request.permissionDenied = request.permissionFailure == null }
        return permitted && live(request)
    }

    private fun permissionEnd(request: Request): End =
        when {
            request.permissionFailure != null ->
                End(
                    LocationRecordingStopReason.ADAPTER_ERROR,
                    LocationRecordingState.Reason.PERMISSION_CHECK,
                    false,
                )
            request.permissionDenied -> End(LocationRecordingStopReason.PERMISSION_DENIED, LocationRecordingState.Reason.PERMISSION_DENIED)
            else -> End(LocationRecordingStopReason.ADAPTER_ERROR, LocationRecordingState.Reason.BLOCKED)
        }

    private fun cleanupFailed(
        request: Request,
        failure: Throwable,
    ) = synchronized(guard) {
        val firstReason = request.failureReason
        remember(request, failure, LocationRecordingState.Reason.ADAPTER_ERROR)
        if (firstReason == null) request.cleanupWasPrimary = true
    }

    private fun remember(
        request: Request,
        failure: Throwable,
        reason: LocationRecordingState.Reason,
    ) = synchronized(guard) {
        val first = request.failure
        if (first ==
            null
        ) {
            request.failure = failure
        } else if (first !== failure &&
            first.suppressed.none { it === failure }
        ) {
            first.addSuppressed(failure)
        }
        if (request.failureReason == null) request.failureReason = reason
    }

    internal fun failureCause(): Throwable? = synchronized(guard) { lastFailure ?: owner?.failure }

    private fun failureState(
        request: Request,
        unknown: Boolean,
    ) = LocationRecordingState.Failed(
        request.source,
        if (request.run != null || unknown) request.id else null,
        request.failureReason ?: LocationRecordingState.Reason.END,
        unknown,
        request.errorClassName ?: request.failure?.javaClass?.name,
    )

    private fun sameOwner(
        request: Request,
        run: LocationRecordingRun,
    ): Boolean =
        run.runId == request.id && run.processId == processId &&
            run.source == request.source && run.startedAtEpochMillis == request.startedWall &&
            run.startedAtElapsedRealtimeMillis == request.startedElapsed

    private fun sameFinishedSource(
        source: PracticeSession,
        actual: PracticeSession,
    ): Boolean =
        actual.endedAtEpochMillis != null &&
            actual.copy(endedAtEpochMillis = null) == source

    private fun uiReason(reason: LocationRecordingStopReason): LocationRecordingState.Reason =
        when (reason) {
            LocationRecordingStopReason.PERMISSION_DENIED -> LocationRecordingState.Reason.PERMISSION_DENIED
            LocationRecordingStopReason.UNSUPPORTED -> LocationRecordingState.Reason.UNSUPPORTED
            LocationRecordingStopReason.NO_UPDATE -> LocationRecordingState.Reason.NO_UPDATE
            LocationRecordingStopReason.SDK_ERROR -> LocationRecordingState.Reason.SDK_ERROR
            LocationRecordingStopReason.SOURCE_CHANGED -> LocationRecordingState.Reason.SOURCE_CHANGED
            LocationRecordingStopReason.WRITE_ERROR -> LocationRecordingState.Reason.WRITE
            else -> LocationRecordingState.Reason.ADAPTER_ERROR
        }

    private fun failureStopReason(reason: LocationRecordingState.Reason): LocationRecordingStopReason =
        when (reason) {
            LocationRecordingState.Reason.PERMISSION_DENIED -> LocationRecordingStopReason.PERMISSION_DENIED
            LocationRecordingState.Reason.SDK_ERROR -> LocationRecordingStopReason.SDK_ERROR
            LocationRecordingState.Reason.UNSUPPORTED -> LocationRecordingStopReason.UNSUPPORTED
            LocationRecordingState.Reason.NO_UPDATE -> LocationRecordingStopReason.NO_UPDATE
            LocationRecordingState.Reason.OPEN, LocationRecordingState.Reason.WRITE, LocationRecordingState.Reason.END,
            LocationRecordingState.Reason.INVALID_TIME,
            -> LocationRecordingStopReason.WRITE_ERROR
            else -> LocationRecordingStopReason.ADAPTER_ERROR
        }

    private class Host(
        val context: CoroutineContext,
    ) {
        var live = true
        var known = false
        var source: PracticeSession? = null
    }

    private class Request(
        val id: String,
        val source: PracticeSession,
        val host: Host,
        var canStart: (() -> Boolean)?,
    ) {
        val registeredReceipt = CompletableDeferred<Unit>()
        var registered = false
        var startRetired = false
        var preparing: Job? = null
        var recording: Job? = null
        var cleanup: Job? = null
        var cleanupDone = false
        var beginAttempted = false
        var noRunConfirmed = false
        var startedWall: Long? = null
        var startedElapsed: Long? = null
        var run: LocationRecordingRun? = null
        var ending: End? = null
        var phase: Any? = null
        var appendUnconfirmed = false
        var storageUnknown = false
        var permissionDenied = false
        var permissionFailure: Throwable? = null
        var receiptFloor = 0L
        var failure: Throwable? = null
        var errorClassName: String? = null
        var failureReason: LocationRecordingState.Reason? = null
        var cleanupWasPrimary = false
    }

    private data class End(
        val reason: LocationRecordingStopReason,
        val uiReason: LocationRecordingState.Reason? = null,
        val knownTime: Boolean = true,
    )

    private class SourceStreamEnded : IllegalStateException("Location source stream ended without an owner decision")
}
