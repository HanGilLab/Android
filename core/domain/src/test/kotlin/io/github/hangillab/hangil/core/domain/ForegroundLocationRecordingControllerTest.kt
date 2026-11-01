package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Controlled domain streams and transactional receipts, never a real SDK/Room success claim. */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundLocationRecordingControllerTest {
    @Test fun `no host or permission or live parking receipt cannot initialize or begin`() =
        runTest {
            val f = Fixture(backgroundScope)
            val c = f.controller()
            assertFalse(c.start(source()) { true })
            val host = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            f.permission = false
            assertFalse(c.start(source()) { true })
            assertEquals(LocationRecordingState.Reason.PERMISSION_DENIED, (c.state.value as LocationRecordingState.Failed).reason)
            f.permission = true
            f.vehicle.state.value = driving
            assertFalse(c.start(source()) { true })
            f.vehicle.state.value = parked
            assertFalse(c.start(source()) { false })
            assertEquals(0, f.beginCalls)
            assertEquals(0, f.gateway.collections)
            assertTrue(f.runs.isEmpty())
            host.cancel()
            runCurrent()
        }

    @Test fun `D passive samples ignore retired start UI predicate and preserve raw callback order`() =
        runTest {
            val f = Fixture(backgroundScope)
            val c = f.controller()
            var uiAllowed = true
            val host = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertTrue(c.start(source()) { uiAllowed })
            runCurrent()
            uiAllowed = false
            f.vehicle.state.value = driving
            f.wall.value = 150 // A positive wall-clock regression is not a new owner or sort key.
            f.gateway.send(sample(sdkTime = -7))
            f.gateway.send(LocationUpdateReading.Empty(1000))
            runCurrent()
            assertEquals(2, f.events.size)
            assertEquals(listOf(1, 2), f.events.map { it.ordinal })
            assertEquals(-7L, ((f.events.first().reading as LocationUpdateReading.Sample).sample.sdkTimestampMillis))
            assertTrue(c.state.value is LocationRecordingState.Recording)
            assertTrue(c.stop("run-1")) // Privacy stop is allowed in D and without the old UI receipt.
            runCurrent()
            assertEquals(LocationRecordingStopReason.USER_STOP, f.runs.single().stopReason)
            assertEquals(0, f.gateway.active)
            host.cancel()
            runCurrent()
        }

    @Test fun `OPEN remains Starting and pre-ACK sample cannot append until local registration handoff`() =
        runTest {
            val f = Fixture(backgroundScope)
            val ackGate = CompletableDeferred<Unit>()
            f.gateway.beforeRegistration = { ackGate.await() }
            f.gateway.beforeAckReading = sample()
            val c = f.controller()
            val host = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertTrue(c.start(source()) { true })
            runCurrent()
            assertTrue(c.state.value is LocationRecordingState.Starting)
            assertEquals(LocationRecordingRunStatus.OPEN, f.runs.single().status)
            assertTrue(f.events.isEmpty())
            assertEquals(0, f.gateway.localRegistrations)
            ackGate.complete(Unit)
            runCurrent()
            assertTrue(c.state.value is LocationRecordingState.Recording)
            assertEquals(1, f.gateway.localRegistrations)
            assertEquals(listOf(sample()), f.events.map { it.reading })
            assertTrue(c.stop("run-1"))
            runCurrent()
            host.cancel()
            runCurrent()
        }

    @Test fun `pre-registration start receipt denial is permanent and old ACK cannot replace a fresh owner`() =
        runTest {
            val f = Fixture(backgroundScope)
            val ackGate = CompletableDeferred<Unit>()
            var allowed = true
            f.gateway.beforeRegistration = { ackGate.await() }
            f.gateway.beforeAckReading = sample()
            val c = f.controller()
            val host = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertTrue(c.start(source()) { allowed })
            runCurrent()
            val oldGuard = f.gateway.guards.single()
            val oldAck = f.gateway.acknowledgements.single()
            allowed = false
            assertFalse(oldGuard())
            runCurrent()
            allowed = true
            assertFalse(oldGuard())
            assertEquals(LocationRecordingState.Reason.BLOCKED, (c.state.value as LocationRecordingState.Failed).reason)
            assertTrue(f.events.isEmpty())
            assertEquals(0, f.gateway.localRegistrations)
            f.gateway.beforeRegistration = {}
            f.gateway.beforeAckReading = null
            assertTrue(c.start(source()) { allowed })
            runCurrent()
            oldAck()
            runCurrent()
            assertEquals("run-2", (c.state.value as LocationRecordingState.Recording).run.runId)
            assertEquals(1, f.gateway.localRegistrations)
            assertTrue(c.stop("run-2"))
            runCurrent()
            host.cancel()
            runCurrent()
        }

    @Test fun `synchronous SDK error with no registration ACK remains terminal metadata not a sample`() =
        runTest {
            val f = Fixture(backgroundScope)
            val reading = LocationUpdateReading.Error(LocationUpdateErrorOrigin.SDK_CALLBACK, "sample.SyncFailure", 1000)
            f.gateway.beforeAckReading = reading
            val c = f.controller()
            val host = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertTrue(c.start(source()) { true })
            runCurrent()
            assertEquals(0, f.gateway.localRegistrations)
            assertEquals(listOf(reading), f.events.map { it.reading })
            assertEquals(LocationRecordingStopReason.SDK_ERROR, f.runs.single().stopReason)
            assertEquals(LocationRecordingState.Reason.SDK_ERROR, (c.state.value as LocationRecordingState.Failed).reason)
            assertEquals(0, f.gateway.active)
            host.cancel()
            runCurrent()
        }

    @Test fun `explicit cleanup callback survives cancellation and prevents a false normal STOPPED marker`() =
        runTest {
            val f = Fixture(backgroundScope)
            val first = IOException("controlled unregister cleanup")
            val second = IOException("controlled binding cleanup")
            first.addSuppressed(second)
            f.gateway.cleanupFailure = first
            val c = f.controller()
            val host = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertTrue(c.start(source()) { true })
            runCurrent()
            assertTrue(c.stop("run-1"))
            runCurrent()
            assertEquals(LocationRecordingRunStatus.FAILED, f.runs.single().status)
            assertEquals(LocationRecordingStopReason.ADAPTER_ERROR, f.runs.single().stopReason)
            assertEquals(LocationRecordingState.Reason.ADAPTER_ERROR, (c.state.value as LocationRecordingState.Failed).reason)
            assertFalse((c.state.value as LocationRecordingState.Failed).storageUnknown)
            assertSame(first, c.failureCause())
            assertSame(second, c.failureCause()!!.suppressed.single())
            host.cancel()
            runCurrent()
        }

    @Test fun `SDK error remains semantic primary when cleanup callback is delivered before terminal metadata`() =
        runTest {
            val f = Fixture(backgroundScope)
            val cleanup = IOException("controlled cleanup after original SDK error")
            f.gateway.cleanupFailure = cleanup
            f.gateway.beforeAckReading =
                LocationUpdateReading.Error(LocationUpdateErrorOrigin.SDK_CALLBACK, "sample.OriginalSdkFailure", 1000)
            val c = f.controller()
            val host = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertTrue(c.start(source()) { true })
            runCurrent()
            val failed = c.state.value as LocationRecordingState.Failed
            assertEquals(LocationRecordingState.Reason.SDK_ERROR, failed.reason)
            assertEquals("sample.OriginalSdkFailure", failed.exceptionClassName)
            assertEquals(LocationRecordingStopReason.SDK_ERROR, f.runs.single().stopReason)
            assertSame(cleanup, c.failureCause())
            assertEquals(0, f.gateway.active)
            host.cancel()
            runCurrent()
        }

    @Test fun `late cleanup callback from an old owner cannot replace a fresh run state`() =
        runTest {
            val f = Fixture(backgroundScope)
            val c = f.controller()
            val host = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertTrue(c.start(source()) { true })
            runCurrent()
            val oldCleanup = f.gateway.cleanupCallbacks.single()
            assertTrue(c.stop("run-1"))
            runCurrent()
            assertTrue(c.start(source()) { true })
            runCurrent()
            oldCleanup(IOException("controlled late old cleanup"))
            assertEquals("run-2", (c.state.value as LocationRecordingState.Recording).run.runId)
            assertTrue(c.stop("run-2"))
            runCurrent()
            assertEquals(LocationRecordingStopReason.USER_STOP, f.runs.last().stopReason)
            host.cancel()
            runCurrent()
        }

    @Test fun `host STOP joins the single stream and does not automatically resume on a new host`() =
        runTest {
            val f = Fixture(backgroundScope)
            val c = f.controller()
            var host = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertTrue(c.start(source()) { true })
            runCurrent()
            val oldGuard = f.gateway.guards.single()
            host.cancel()
            runCurrent()
            assertFalse(oldGuard())
            assertEquals(LocationRecordingStopReason.HOST_STOPPED, f.runs.single().stopReason)
            host = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertEquals(1, f.gateway.collections)
            assertTrue(c.start(source()) { true })
            runCurrent()
            assertFalse(oldGuard())
            assertEquals(2, f.gateway.collections)
            assertFalse(c.stop("run-1"))
            assertTrue(c.stop("run-2"))
            runCurrent()
            assertEquals(0, f.gateway.active)
            host.cancel()
            runCurrent()
        }

    @Test fun `replacement STARTED host waits for old cleanup and still accepts a fresh explicit action`() =
        runTest {
            val f = Fixture(backgroundScope)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.beforeEnd = {
                entered.complete(Unit)
                release.await()
            }
            val c = f.controller()
            val firstHost = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertTrue(c.start(source()) { true })
            runCurrent()
            firstHost.cancel()
            runCurrent()
            assertTrue(entered.isCompleted)
            val replacement = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertFalse(c.start(source()) { true })
            release.complete(Unit)
            runCurrent()
            assertTrue(c.start(source()) { true })
            runCurrent()
            assertEquals(2, f.gateway.collections)
            f.beforeEnd = {}
            assertTrue(c.stop("run-2"))
            runCurrent()
            replacement.cancel()
            runCurrent()
        }

    @Test fun `same source FinishedSession append before session Flow notice closes STOPPED normally`() =
        runTest {
            val f = Fixture(backgroundScope)
            val c = f.controller()
            val host = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertTrue(c.start(source()) { true })
            runCurrent()
            f.appendOverride = LocationRecordingAppendResult.FinishedSession
            f.gateway.send(sample())
            runCurrent()
            assertEquals(LocationRecordingRunStatus.STOPPED, f.runs.single().status)
            assertEquals(LocationRecordingStopReason.SESSION_FINISHED, f.runs.single().stopReason)
            assertTrue(c.state.value is LocationRecordingState.Stopped)
            assertEquals(0, f.gateway.active)
            assertTrue(f.events.isEmpty())
            host.cancel()
            runCurrent()
        }

    @Test fun `same ID metadata replacement is source change not normal finish or automatic restart`() =
        runTest {
            val f = Fixture(backgroundScope)
            val c = f.controller()
            val host = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertTrue(c.start(source()) { true })
            runCurrent()
            val replacement = source().copy(goalTitle = "different source")
            f.sessionState.value = listOf(replacement)
            runCurrent()
            assertEquals(LocationRecordingRunStatus.FAILED, f.runs.single().status)
            assertEquals(LocationRecordingStopReason.SOURCE_CHANGED, f.runs.single().stopReason)
            assertEquals(1, f.gateway.collections)
            assertFalse(c.start(source()) { true })
            assertTrue(c.start(replacement) { true })
            runCurrent()
            assertTrue(c.stop("run-2"))
            runCurrent()
            host.cancel()
            runCurrent()
        }

    @Test fun `begin COMMIT result cancellation captures ACK before exact owner cleanup`() =
        runTest {
            val f = Fixture(backgroundScope)
            val c = f.controller()
            val committed = CompletableDeferred<Unit>()
            val deliver = CompletableDeferred<Unit>()
            val dispatcher = StandardTestDispatcher(testScheduler, "begin-ACK-delivery")
            f.beginDelivery = { result ->
                withContext(dispatcher) {
                    committed.complete(Unit)
                    deliver.await()
                    assertTrue(c.stop("run-1"))
                    result
                }
            }
            val host = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertTrue(c.start(source()) { true })
            runCurrent()
            assertTrue(committed.isCompleted)
            assertEquals(LocationRecordingRunStatus.OPEN, f.runs.single().status)
            deliver.complete(Unit)
            runCurrent()
            assertEquals(LocationRecordingRunStatus.STOPPED, f.runs.single().status)
            assertEquals(0, f.gateway.collections)
            assertEquals(1, f.beginCalls)
            assertFalse((c.state.value as? LocationRecordingState.Failed)?.storageUnknown == true)
            host.cancel()
            runCurrent()
        }

    @Test fun `begin protected context still rolls back original cancellation before commit`() =
        runTest {
            val f = Fixture(backgroundScope)
            val gate = CompletableDeferred<Unit>()
            f.beforeBeginCommit = { gate.await() }
            val c = f.controller()
            val host = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertTrue(c.start(source()) { true })
            runCurrent()
            assertTrue(c.stop("run-1"))
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertTrue(f.runs.isEmpty())
            assertEquals(0, f.gateway.collections)
            assertTrue(c.start(source()) { true })
            runCurrent()
            assertTrue(c.stop("run-2"))
            runCurrent()
            host.cancel()
            runCurrent()
        }

    @Test fun `sample commit rechecks actual permission and stores denial metadata without coordinates`() =
        runTest {
            val f = Fixture(backgroundScope)
            val c = f.controller()
            val host = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertTrue(c.start(source()) { true })
            runCurrent()
            f.permissionChecks = 0
            f.beforeAppendCommit = { reading -> if (reading is LocationUpdateReading.Sample) f.permission = false }
            f.gateway.send(sample())
            runCurrent()
            assertTrue(f.permissionChecks >= 2)
            assertEquals(1, f.events.size)
            assertTrue(f.events.single().reading is LocationUpdateReading.PermissionDenied)
            assertEquals(LocationRecordingStopReason.PERMISSION_DENIED, f.runs.single().stopReason)
            assertEquals(LocationRecordingRunStatus.FAILED, f.runs.single().status)
            host.cancel()
            runCurrent()
        }

    @Test fun `permission read exception is adapter failure metadata not permission denied`() =
        runTest {
            val f = Fixture(backgroundScope)
            val failure = IOException("controlled permission read")
            val c = f.controller()
            val host = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertTrue(c.start(source()) { true })
            runCurrent()
            f.beforeAppendCommit = { reading -> if (reading is LocationUpdateReading.Sample) f.permissionFailure = failure }
            f.gateway.send(sample())
            runCurrent()
            val event = f.events.single().reading as LocationUpdateReading.Error
            assertEquals(LocationUpdateErrorOrigin.ADAPTER, event.origin)
            assertEquals(failure.javaClass.name, event.exceptionClassName)
            assertEquals(LocationRecordingStopReason.ADAPTER_ERROR, f.runs.single().stopReason)
            assertSame(failure, c.failureCause())
            host.cancel()
            runCurrent()
        }

    @Test fun `permission read reentry retires the host before a cached true can authorize append`() =
        runTest {
            val f = Fixture(backgroundScope)
            val c = f.controller()
            val host = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertTrue(c.start(source()) { true })
            runCurrent()
            f.onPermissionCheck = { host.cancel() }
            f.gateway.send(sample())
            runCurrent()
            assertTrue(f.events.isEmpty())
            assertEquals(LocationRecordingStopReason.HOST_STOPPED, f.runs.single().stopReason)
            assertEquals(0, f.gateway.active)
        }

    @Test fun `NoUpdate and SDK Error terminal metadata have distinct failure reasons`() =
        runTest {
            for (reading in listOf(
                LocationUpdateReading.NoUpdate(1000),
                LocationUpdateReading.Error(LocationUpdateErrorOrigin.SDK_CALLBACK, "sample.Failure", 1000),
            )) {
                val f = Fixture(backgroundScope)
                val c = f.controller()
                val host = backgroundScope.launch { c.recordWhileStarted() }
                runCurrent()
                assertTrue(c.start(source()) { true })
                runCurrent()
                f.gateway.send(reading)
                runCurrent()
                assertEquals(listOf(reading), f.events.map { it.reading })
                val reason =
                    when (reading) {
                        is LocationUpdateReading.NoUpdate -> LocationRecordingStopReason.NO_UPDATE
                        else -> LocationRecordingStopReason.SDK_ERROR
                    }
                assertEquals(reason, f.runs.single().stopReason)
                assertEquals(LocationRecordingRunStatus.FAILED, f.runs.single().status)
                assertEquals(0, f.gateway.active)
                host.cancel()
                runCurrent()
            }
        }

    @Test fun `1024th receipt closes once without conflating repeated identical samples`() =
        runTest {
            val f = Fixture(backgroundScope)
            val c = f.controller()
            val host = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertTrue(c.start(source()) { true })
            runCurrent()
            repeat(LocationRecordingRepository.MAX_EVENTS_PER_RUN) { f.gateway.send(sample()) }
            runCurrent()
            assertEquals(1024, f.events.size)
            assertEquals((1..1024).toList(), f.events.map { it.ordinal })
            assertEquals(LocationRecordingStopReason.LIMIT_REACHED, f.runs.single().stopReason)
            assertEquals(0, f.gateway.active)
            assertEquals(1, f.endCalls)
            host.cancel()
            runCurrent()
        }

    @Test fun `append COMMIT ACK loss is read back without replay and never called normal stop`() =
        runTest {
            val f = Fixture(backgroundScope)
            val committed = CompletableDeferred<Unit>()
            f.appendDelivery = {
                committed.complete(Unit)
                awaitCancellation()
            }
            val c = f.controller()
            val host = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertTrue(c.start(source()) { true })
            runCurrent()
            f.gateway.send(sample())
            runCurrent()
            assertTrue(committed.isCompleted)
            assertTrue(c.stop("run-1"))
            runCurrent()
            assertEquals(1, f.events.size)
            assertEquals(1, f.appendCalls)
            assertEquals(LocationRecordingRunStatus.FAILED, f.runs.single().status)
            assertEquals(LocationRecordingStopReason.WRITE_ERROR, f.runs.single().stopReason)
            assertEquals(LocationRecordingState.Reason.WRITE, (c.state.value as LocationRecordingState.Failed).reason)
            assertFalse((c.state.value as LocationRecordingState.Failed).storageUnknown)
            host.cancel()
            runCurrent()
        }

    @Test fun `failed end holds exact ownership and explicit cleanup can resolve it without new SDK collection`() =
        runTest {
            val f = Fixture(backgroundScope)
            val failure = IOException("controlled end ACK failure")
            f.beforeEnd = { throw failure }
            val c = f.controller()
            val host = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertTrue(c.start(source()) { true })
            runCurrent()
            assertTrue(c.stop("run-1"))
            runCurrent()
            assertTrue((c.state.value as LocationRecordingState.Failed).storageUnknown)
            assertFalse(c.start(source()) { true })
            f.beforeEnd = {}
            assertTrue(c.stop("run-1"))
            runCurrent()
            assertFalse((c.state.value as LocationRecordingState.Failed).storageUnknown)
            assertSame(failure, c.failureCause())
            assertEquals(1, f.gateway.collections)
            assertTrue(c.start(source()) { true })
            runCurrent()
            assertTrue(c.stop("run-2"))
            runCurrent()
            host.cancel()
            runCurrent()
        }

    @Test fun `recovery failure blocks new location begin and retains the original cause`() =
        runTest {
            val f = Fixture(backgroundScope)
            val failure = IOException("controlled recovery failure")
            f.recover = { throw failure }
            val c = f.controller()
            val host = backgroundScope.launch { c.recordWhileStarted() }
            runCurrent()
            assertFalse(c.start(source()) { true })
            assertEquals(LocationRecordingState.Reason.RECOVERY, (c.state.value as LocationRecordingState.Failed).reason)
            assertSame(failure, c.failureCause())
            assertEquals(0, f.beginCalls)
            assertEquals(0, f.gateway.collections)
            host.cancel()
            runCurrent()
        }

    private class Fixture(
        private val scope: CoroutineScope,
    ) {
        val wall = MutableClock(200)
        var elapsed = 1000L
        var permission = true
        var permissionChecks = 0
        var permissionFailure: Exception? = null
        var onPermissionCheck: () -> Unit = {}
        val gateway =
            FakeGateway {
                permissionChecks++
                onPermissionCheck()
                permissionFailure?.let { throw it }
                permission
            }
        val vehicle = FakeVehicle()
        val restrictions = FakeRestrictions()
        val sessionState = MutableStateFlow(listOf(source()))
        val runs = mutableListOf<LocationRecordingRun>()
        val events = mutableListOf<LocationRecordingEvent>()
        var beginCalls = 0
        var appendCalls = 0
        var endCalls = 0
        var activeWrites = 0
        var sequence = 0
        var recover: suspend () -> Unit = {}
        var beforeBeginCommit: suspend () -> Unit = {}
        var beginDelivery: suspend (LocationRecordingBeginResult.Begun) -> LocationRecordingBeginResult = { it }
        var beforeAppendCommit: suspend (LocationUpdateReading) -> Unit = {}
        var appendDelivery: suspend (LocationRecordingAppendResult.Appended) -> LocationRecordingAppendResult = { it }
        var appendOverride: LocationRecordingAppendResult? = null
        var beforeEnd: suspend () -> Unit = {}
        private val sessions =
            object : PracticeSessionRepository {
                override fun observeSessions(): Flow<List<PracticeSession>> = sessionState

                override suspend fun start(
                    practice: PreparedPractice,
                    startedAtEpochMillis: Long,
                    canCommit: () -> Boolean,
                ): SessionStartResult = error("Unexpected session write")

                override suspend fun finish(
                    sessionId: String,
                    endedAtEpochMillis: Long,
                    canCommit: () -> Boolean,
                ): SessionFinishResult = error("Unexpected session write")
            }
        private val repository =
            object : LocationRecordingRepository {
                override fun observe(sessionId: String): Flow<LocationRecordingSnapshot> =
                    flow {
                        emit(
                            LocationRecordingSnapshot(
                                runs.filter { it.source.sessionId == sessionId },
                                events.filter {
                                    it.sessionId ==
                                        sessionId
                                },
                            ),
                        )
                    }

                override suspend fun begin(
                    expectedSource: PracticeSession,
                    runId: String,
                    processId: String,
                    startedAtEpochMillis: Long,
                    startedAtElapsedRealtimeMillis: Long,
                    canBegin: () -> Boolean,
                ): LocationRecordingBeginResult {
                    beginCalls++
                    if (!canBegin()) return LocationRecordingBeginResult.Blocked
                    if (sessionState.value.singleOrNull { it.isActive } != expectedSource) return LocationRecordingBeginResult.SourceChanged
                    if (runs.any { it.status == LocationRecordingRunStatus.OPEN }) return LocationRecordingBeginResult.AlreadyOpen
                    beforeBeginCommit()
                    if (!canBegin()) return LocationRecordingBeginResult.Blocked
                    val row =
                        LocationRecordingRun(
                            runId,
                            processId,
                            expectedSource,
                            startedAtEpochMillis,
                            startedAtElapsedRealtimeMillis,
                            null,
                            null,
                            LocationRecordingRunStatus.OPEN,
                            null,
                        )
                    runs.add(row)
                    return beginDelivery(LocationRecordingBeginResult.Begun(row))
                }

                override suspend fun append(
                    expectedSource: PracticeSession,
                    expectedRun: LocationRecordingRun,
                    reading: LocationUpdateReading,
                    observedAtEpochMillis: Long,
                    canAppend: () -> Boolean,
                ): LocationRecordingAppendResult {
                    appendCalls++
                    activeWrites++
                    try {
                        if (!canAppend()) return LocationRecordingAppendResult.Blocked
                        appendOverride?.let { return it }
                        val actual =
                            sessionState.value.singleOrNull { it.sessionId == expectedSource.sessionId }
                                ?: return LocationRecordingAppendResult.MissingSession
                        if (actual.endedAtEpochMillis != null &&
                            actual.copy(endedAtEpochMillis = null) == expectedSource
                        ) {
                            return LocationRecordingAppendResult.FinishedSession
                        }
                        if (actual != expectedSource) return LocationRecordingAppendResult.SourceChanged
                        val stored = runs.single { it.runId == expectedRun.runId }
                        if (stored.status != LocationRecordingRunStatus.OPEN) return LocationRecordingAppendResult.RunClosed
                        val prior = events.filter { it.runId == expectedRun.runId }
                        if (prior.size == 1024) return LocationRecordingAppendResult.LimitReached
                        if (reading.receivedAtElapsedRealtimeMillis <
                            (prior.lastOrNull()?.reading?.receivedAtElapsedRealtimeMillis ?: stored.startedAtElapsedRealtimeMillis)
                        ) {
                            return LocationRecordingAppendResult.InvalidTime
                        }
                        beforeAppendCommit(reading)
                        if (!canAppend()) return LocationRecordingAppendResult.Blocked
                        val event =
                            LocationRecordingEvent(
                                events.size + 1L,
                                expectedSource.sessionId,
                                expectedRun.runId,
                                prior.size + 1,
                                observedAtEpochMillis,
                                reading,
                            )
                        events.add(event)
                        return appendDelivery(LocationRecordingAppendResult.Appended(event, prior.size + 1 == 1024))
                    } finally {
                        activeWrites--
                    }
                }

                override suspend fun end(
                    expectedRun: LocationRecordingRun,
                    status: LocationRecordingRunStatus,
                    stopReason: LocationRecordingStopReason,
                    endedAtEpochMillis: Long?,
                    endedAtElapsedRealtimeMillis: Long?,
                ): LocationRecordingEndResult {
                    endCalls++
                    check(activeWrites == 0 && gateway.active == 0) { "End must follow canceled/joined append and SDK collection" }
                    beforeEnd()
                    val index = runs.indexOfFirst { it.runId == expectedRun.runId }
                    if (index < 0) return LocationRecordingEndResult.MissingRun
                    val actual = runs[index]
                    if (actual.status != LocationRecordingRunStatus.OPEN) return LocationRecordingEndResult.AlreadyEnded(actual)
                    val ended =
                        actual.copy(
                            status = status,
                            stopReason = stopReason,
                            endedAtEpochMillis = endedAtEpochMillis,
                            endedAtElapsedRealtimeMillis = endedAtElapsedRealtimeMillis,
                        )
                    runs[index] = ended
                    return LocationRecordingEndResult.Ended(ended)
                }

                override suspend fun recoverInterrupted(currentProcessId: String) {
                    recover()
                }
            }

        fun controller() =
            ForegroundLocationRecordingController(
                gateway,
                sessions,
                repository,
                vehicle,
                restrictions,
                wall,
                { elapsed },
                scope,
                { "run-${++sequence}" },
                "controlled-process",
            )
    }

    private class FakeGateway(
        private val permission: () -> Boolean,
    ) : LocationUpdateGateway {
        var collections = 0
        var localRegistrations = 0
        var active = 0
        var beforeRegistration: suspend () -> Unit = {}
        var beforeAckReading: LocationUpdateReading? = null
        var cleanupFailure: Throwable? = null
        val guards = mutableListOf<() -> Boolean>()
        val acknowledgements = mutableListOf<() -> Unit>()
        val cleanupCallbacks = mutableListOf<(Throwable) -> Unit>()
        private val channels = mutableListOf<Channel<LocationUpdateReading>>()

        override fun hasPermission(): Boolean = permission()

        override fun updates(
            canContinue: () -> Boolean,
            onRegistered: () -> Unit,
            onCleanupFailure: (Throwable) -> Unit,
        ): Flow<LocationUpdateReading> =
            channelFlow {
                val output = this
                collections++
                active++
                guards.add(canContinue)
                acknowledgements.add(onRegistered)
                cleanupCallbacks.add(onCleanupFailure)
                val channel = Channel<LocationUpdateReading>(Channel.UNLIMITED)
                channels.add(channel)
                var cleaned = false
                try {
                    val early = beforeAckReading
                    if (early != null && early !is LocationUpdateReading.Sample && early !is LocationUpdateReading.Empty) {
                        active--
                        cleaned = true
                        cleanupFailure?.let(onCleanupFailure)
                        output.send(early)
                        return@channelFlow
                    }
                    early?.let { output.send(it) }
                    beforeRegistration()
                    if (!canContinue()) return@channelFlow
                    localRegistrations++
                    onRegistered()
                    for (reading in channel) {
                        if (!canContinue()) break
                        if (reading !is LocationUpdateReading.Sample && reading !is LocationUpdateReading.Empty) {
                            active--
                            cleaned = true
                            cleanupFailure?.let(onCleanupFailure)
                            output.send(reading)
                            return@channelFlow
                        }
                        output.send(reading)
                    }
                } finally {
                    channel.close()
                    if (!cleaned) {
                        active--
                        cleanupFailure?.let(onCleanupFailure)
                    }
                }
            }

        fun send(reading: LocationUpdateReading) {
            check(channels.last().trySend(reading).isSuccess)
        }
    }

    private class FakeVehicle : VehicleGateway {
        override val state = MutableStateFlow(parked)
    }

    private class FakeRestrictions : DrivingRestrictionsProvider {
        override val restrictions = MutableStateFlow(unrestricted)
    }

    private class MutableClock(
        var value: Long,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(value)
    }

    private companion object {
        val parked = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))
        val driving = parked.copy(gear = VehicleReading.Available(GearPosition.DRIVE))
        val unrestricted = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)

        fun source() =
            PracticeSession(
                "controlled-session",
                PracticeSelection("goal", "course", 1, 1, "task"),
                "goal",
                "course",
                "task",
                CourseReviewStatus.DEMO,
                100,
                null,
            )

        fun sample(sdkTime: Long = -1) = LocationUpdateReading.Sample(LocationUpdateSample(12.0, 13.0, -3.0, -2f, -1f, sdkTime, 1000))
    }
}
