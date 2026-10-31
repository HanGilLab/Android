package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class PracticeRecordingControllerTest {
    @Test
    fun `cancel at committed open result dispatch still closes the exact row and permits a fresh request`() =
        runTest {
            val f = Fixture(backgroundScope)
            val committed = CompletableDeferred<Unit>()
            val returnGate = CompletableDeferred<Unit>()
            val transaction = StandardTestDispatcher(testScheduler, "committed-open-return")
            val controller = f.controller()
            f.deliverOpened = { result ->
                withContext(transaction) {
                    committed.complete(Unit)
                    returnGate.await()
                    assertTrue(controller.stopBackground("run-1"))
                    result
                }
            }
            assertTrue(controller.requestBackground(session()) { true })
            runCurrent()
            assertTrue(committed.isCompleted)
            assertEquals(
                RecordingRunStatus.OPEN,
                f.rows.value
                    .single()
                    .status,
            )
            returnGate.complete(Unit)
            runCurrent()
            assertEquals(
                "A committed row must be closed even when cancellation discards the open result",
                RecordingRunStatus.STOPPED,
                f.rows.value
                    .single()
                    .status,
            )
            assertEquals(BackgroundRecordingState.Disabled, controller.background.value)
            assertTrue(f.starts.isEmpty())
            assertTrue(f.stops.isEmpty())
            assertTrue(f.attempts.isEmpty())
            f.deliverOpened = { it }
            assertTrue(controller.requestBackground(session()) { true })
            runCurrent()
            assertEquals(BackgroundRecordingState.Starting("run-2", "session-a"), controller.background.value)
            assertEquals(listOf("run-1", "run-2"), f.rows.value.map { it.runId })
            assertEquals(listOf("run-2" to "session-a"), f.starts)
            controller.startFailed("run-2")
            runCurrent()
        }

    @Test
    fun `original preparation cancellation before commit stays visible inside protected open`() =
        runTest {
            val f = Fixture(backgroundScope)
            val entered = CompletableDeferred<Unit>()
            val commitGate = CompletableDeferred<Unit>()
            f.beforeOpenCommit = {
                entered.complete(Unit)
                commitGate.await()
            }
            val controller = f.controller()
            assertTrue(controller.requestBackground(session()) { true })
            runCurrent()
            assertTrue(entered.isCompleted)
            assertTrue(controller.stopBackground("run-1"))
            runCurrent()
            commitGate.complete(Unit)
            runCurrent()
            assertTrue("Cancellation before commit must not leave an OPEN row", f.rows.value.isEmpty())
            assertTrue(f.starts.isEmpty())
            assertTrue(f.stops.isEmpty())
            assertTrue(f.attempts.isEmpty())
            assertEquals(BackgroundRecordingState.Disabled, controller.background.value)
            f.beforeOpenCommit = {}
            assertTrue(controller.requestBackground(session()) { true })
            runCurrent()
            assertEquals(BackgroundRecordingState.Starting("run-2", "session-a"), controller.background.value)
            controller.startFailed("run-2")
            runCurrent()
        }

    @Test
    fun `live start gate denial before commit creates no marker or service and allows an explicit retry`() =
        runTest {
            val f = Fixture(backgroundScope)
            val entered = CompletableDeferred<Unit>()
            val commitGate = CompletableDeferred<Unit>()
            var allowed = true
            f.beforeOpenCommit = {
                entered.complete(Unit)
                commitGate.await()
            }
            val controller = f.controller()
            assertTrue(controller.requestBackground(session()) { allowed })
            runCurrent()
            assertTrue(entered.isCompleted)
            allowed = false
            commitGate.complete(Unit)
            runCurrent()
            assertEquals(BackgroundRecordingState.Reason.BLOCKED, f.failure(controller))
            assertTrue(f.rows.value.isEmpty())
            assertTrue(f.starts.isEmpty())
            assertTrue(f.attempts.isEmpty())
            allowed = true
            f.beforeOpenCommit = {}
            assertTrue(controller.requestBackground(session()) { allowed })
            runCurrent()
            assertEquals(BackgroundRecordingState.Starting("run-2", "session-a"), controller.background.value)
            controller.startFailed("run-2")
            runCurrent()
        }

    @Test
    fun `open failure before commit is typed and never dispatches a service`() =
        runTest {
            val f = Fixture(backgroundScope)
            f.beforeOpenCommit = { throw IOException("controlled precommit failure") }
            val controller = f.controller()
            assertTrue(controller.requestBackground(session()) { true })
            runCurrent()
            assertEquals(BackgroundRecordingState.Reason.OPEN, f.failure(controller))
            assertTrue(f.rows.value.isEmpty())
            assertTrue(f.starts.isEmpty())
            assertTrue(f.stops.isEmpty())
            assertTrue(f.attempts.isEmpty())
            f.beforeOpenCommit = {}
            assertTrue(controller.requestBackground(session()) { true })
            runCurrent()
            assertEquals(BackgroundRecordingState.Starting("run-2", "session-a"), controller.background.value)
            controller.startFailed("run-2")
            runCurrent()
        }

    @Test
    fun `append finished receipt arriving before session notification stops normally without retaining an owner`() =
        runTest {
            val f = Fixture(backgroundScope)
            f.append = { VehicleObservationAppendResult.FinishedSession }
            val controller = f.controller()
            assertTrue(controller.requestBackground(session()) { true })
            runCurrent()
            val service = backgroundScope.launch { controller.recordInService("run-1", "session-a") }
            runCurrent()
            service.join()
            assertEquals(
                "FinishedSession is an authoritative end receipt even before observeSessions catches up",
                RecordingRunStatus.STOPPED,
                f.rows.value
                    .single()
                    .status,
            )
            assertEquals(BackgroundRecordingState.Disabled, controller.background.value)
            assertEquals(listOf("run-1"), f.stops)
            assertTrue(f.saved.isEmpty())
            f.sessions.value = listOf(session().copy(endedAtEpochMillis = 150))
            runCurrent()
            assertEquals(BackgroundRecordingState.Disabled, controller.background.value)
            f.sessions.value = listOf(session("session-b"), session().copy(endedAtEpochMillis = 150))
            f.append = { VehicleObservationAppendResult.Appended(f.attempts.size.toLong()) }
            assertTrue(controller.requestBackground(session("session-b")) { true })
            runCurrent()
            val next = backgroundScope.launch { controller.recordInService("run-2", "session-b") }
            runCurrent()
            assertEquals(BackgroundRecordingState.Active("run-2", "session-b"), controller.background.value)
            assertEquals(listOf("session-b"), f.saved.map { it.first })
            assertTrue(controller.stopBackground("run-2"))
            runCurrent()
            next.join()
        }

    @Test
    fun `missing session and invalid observation time remain failed background receipts`() =
        runTest {
            listOf(
                VehicleObservationAppendResult.MissingSession to BackgroundRecordingState.Reason.SESSION_READ,
                VehicleObservationAppendResult.InvalidTime to BackgroundRecordingState.Reason.INVALID_TIME,
            ).forEach { (result, expectedReason) ->
                val f = Fixture(backgroundScope)
                f.append = { result }
                val controller = f.controller()
                assertTrue(controller.requestBackground(session()) { true })
                runCurrent()
                val service = backgroundScope.launch { controller.recordInService("run-1", "session-a") }
                runCurrent()
                service.join()
                assertEquals(
                    RecordingRunStatus.FAILED,
                    f.rows.value
                        .single()
                        .status,
                )
                assertEquals(expectedReason, f.failure(controller))
                assertEquals("run-1", (controller.background.value as BackgroundRecordingState.Failed).runId)
                assertTrue(f.saved.isEmpty())
                assertTrue(controller.stopBackground("run-1"))
                runCurrent()
            }
        }

    @Test
    fun `immediate foreground host stays recording after explicit background stop releases ownership`() =
        runTest {
            val f = Fixture(backgroundScope)
            val controller = f.controller()
            val immediate = UnconfinedTestDispatcher(testScheduler)
            val host = backgroundScope.launch(immediate) { controller.recordWhileStarted() }
            runCurrent()
            assertEquals(VehicleRecordingState.Recording, controller.state.value)
            assertTrue(controller.requestBackground(session()) { true })
            runCurrent()
            val service = backgroundScope.launch { controller.recordInService("run-1", "session-a") }
            runCurrent()
            assertEquals(BackgroundRecordingState.Active("run-1", "session-a"), controller.background.value)
            assertTrue(controller.stopBackground("run-1"))
            runCurrent()
            service.join()
            assertEquals(
                RecordingRunStatus.STOPPED,
                f.rows.value
                    .single()
                    .status,
            )
            assertEquals(BackgroundRecordingState.Disabled, controller.background.value)
            assertEquals(
                listOf("session-a"),
                f.sessions.value
                    .filter { it.isActive }
                    .map { it.sessionId },
            )
            assertEquals(1, f.vehicle.state.subscriptionCount.value)
            val beforeDrive = f.saved.size
            f.vehicle.state.value = driving
            runCurrent()
            assertEquals(beforeDrive + 1, f.saved.size)
            assertEquals("session-a" to driving, f.saved.last().let { it.first to it.second.state })
            assertEquals(1, f.maxActiveWrites)
            assertEquals(
                "Native stop and a new live foreground subscription must not leave the controller Paused",
                VehicleRecordingState.Recording,
                controller.state.value,
            )
            host.cancelAndJoin()
        }

    @Test
    fun `immediate foreground host stays recording after opting out of a completed failed run`() =
        runTest {
            val f = Fixture(backgroundScope)
            val controller = f.controller()
            val host = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.recordWhileStarted() }
            runCurrent()
            assertTrue(controller.requestBackground(session()) { true })
            runCurrent()
            f.append = { throw IOException("controlled background append failure") }
            val service = backgroundScope.launch { controller.recordInService("run-1", "session-a") }
            runCurrent()
            service.join()
            assertEquals("run-1", (controller.background.value as BackgroundRecordingState.Failed).runId)
            assertEquals(
                RecordingRunStatus.FAILED,
                f.rows.value
                    .single()
                    .status,
            )
            f.append = { VehicleObservationAppendResult.Appended(f.attempts.size.toLong()) }
            assertTrue(controller.stopBackground("run-1"))
            runCurrent()
            assertEquals(1, f.vehicle.state.subscriptionCount.value)
            val beforeDrive = f.saved.size
            f.vehicle.state.value = driving
            runCurrent()
            assertEquals(beforeDrive + 1, f.saved.size)
            assertTrue(f.saved.all { it.first == "session-a" })
            assertEquals(listOf("run-1"), f.stops)
            assertEquals(VehicleRecordingState.Recording, controller.state.value)
            host.cancelAndJoin()
        }

    @Test
    fun `immediate foreground host stays recording after cleanup failure releases explicit opt out`() =
        runTest {
            val f = Fixture(backgroundScope)
            f.finishResult = { status, end -> status == RecordingRunStatus.FAILED && end == null }
            val controller = f.controller()
            val host = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.recordWhileStarted() }
            runCurrent()
            assertTrue(controller.requestBackground(session()) { true })
            runCurrent()
            val service = backgroundScope.launch { controller.recordInService("run-1", "session-a") }
            runCurrent()
            assertTrue(controller.stopBackground("run-1"))
            runCurrent()
            service.join()
            assertEquals(
                RecordingRunStatus.FAILED,
                f.rows.value
                    .single()
                    .status,
            )
            assertNull(
                f.rows.value
                    .single()
                    .endedAtEpochMillis,
            )
            val warning = controller.background.value as BackgroundRecordingState.Failed
            assertNull(warning.runId)
            assertEquals("session-a", warning.sessionId)
            assertEquals(1, f.vehicle.state.subscriptionCount.value)
            val beforeDrive = f.saved.size
            f.vehicle.state.value = driving
            runCurrent()
            assertEquals(beforeDrive + 1, f.saved.size)
            assertEquals("session-a" to driving, f.saved.last().let { it.first to it.second.state })
            assertEquals(listOf("run-1"), f.stops)
            assertEquals(VehicleRecordingState.Recording, controller.state.value)
            host.cancelAndJoin()
        }

    @Test
    fun `immediate foreground host remains recording when pending recovery failure releases unlaunched owner`() =
        runTest {
            val f = Fixture(backgroundScope)
            val recovery = CompletableDeferred<Unit>()
            f.recover = {
                recovery.await()
                throw IOException("controlled recovery failure")
            }
            val controller = f.controller()
            val host = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.recordWhileStarted() }
            assertTrue(controller.requestBackground(session()) { true })
            runCurrent()
            recovery.complete(Unit)
            runCurrent()
            assertEquals(BackgroundRecordingState.Reason.RECOVERY, f.failure(controller))
            assertNull((controller.background.value as BackgroundRecordingState.Failed).runId)
            assertTrue(f.rows.value.isEmpty())
            assertTrue(f.starts.isEmpty())
            assertTrue(f.stops.isEmpty())
            assertEquals(1, f.vehicle.state.subscriptionCount.value)
            val beforeDrive = f.saved.size
            f.vehicle.state.value = driving
            runCurrent()
            assertEquals(beforeDrive + 1, f.saved.size)
            assertTrue(f.saved.all { it.first == "session-a" })
            assertEquals(VehicleRecordingState.Recording, controller.state.value)
            host.cancelAndJoin()
        }

    @Test
    fun `immediate foreground host is idle after background session completion releases ownership`() =
        runTest {
            val f = Fixture(backgroundScope)
            val controller = f.controller()
            val host = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.recordWhileStarted() }
            runCurrent()
            assertTrue(controller.requestBackground(session()) { true })
            runCurrent()
            val service = backgroundScope.launch { controller.recordInService("run-1", "session-a") }
            runCurrent()
            f.sessions.value = listOf(session().copy(endedAtEpochMillis = 150))
            runCurrent()
            service.join()
            assertEquals(
                RecordingRunStatus.STOPPED,
                f.rows.value
                    .single()
                    .status,
            )
            assertEquals(BackgroundRecordingState.Disabled, controller.background.value)
            assertEquals(0, f.vehicle.state.subscriptionCount.value)
            val saved = f.saved.toList()
            f.vehicle.state.value = driving
            runCurrent()
            assertEquals(saved, f.saved)
            assertEquals(VehicleRecordingState.Idle, controller.state.value)
            host.cancelAndJoin()
        }

    @Test
    fun `request opens a marker but records only after native promotion acknowledgement`() =
        runTest {
            val f = Fixture(backgroundScope)
            val controller = f.controller()
            assertTrue(controller.requestBackground(session()) { true })
            runCurrent()
            assertEquals(BackgroundRecordingState.Starting("run-1", "session-a"), controller.background.value)
            assertEquals(listOf("run-1" to "session-a"), f.starts)
            assertEquals(
                RecordingRunStatus.OPEN,
                f.rows.value
                    .single()
                    .status,
            )
            assertTrue(f.attempts.isEmpty())
            val service = backgroundScope.launch { controller.recordInService("run-1", "session-a") }
            runCurrent()
            assertEquals(BackgroundRecordingState.Active("run-1", "session-a"), controller.background.value)
            assertEquals(VehicleRecordingState.Recording, controller.state.value)
            assertEquals(listOf(parked), f.saved.map { it.second.state })
            assertTrue(controller.stopBackground("run-1"))
            runCurrent()
            service.join()
            assertEquals(
                RecordingRunStatus.STOPPED,
                f.rows.value
                    .single()
                    .status,
            )
            assertEquals(listOf("run-1"), f.stops)
        }

    @Test
    fun `background handoff joins cancelled foreground append before service dispatch`() =
        runTest {
            val f = Fixture(backgroundScope)
            val cleanup = CompletableDeferred<Unit>()
            var cancelling = false
            f.append = {
                if (f.attempts.size == 1) {
                    try {
                        awaitCancellation()
                    } catch (cancelled: CancellationException) {
                        cancelling = true
                        withContext(NonCancellable) { cleanup.await() }
                        throw cancelled
                    }
                }
                VehicleObservationAppendResult.Appended(f.attempts.size.toLong())
            }
            val controller = f.controller()
            val host = backgroundScope.launch { controller.recordWhileStarted() }
            runCurrent()
            assertTrue(controller.requestBackground(session()) { true })
            runCurrent()
            assertTrue(cancelling)
            assertTrue(f.starts.isEmpty())
            assertTrue(f.rows.value.isEmpty())
            assertEquals(1, f.activeWrites)
            cleanup.complete(Unit)
            runCurrent()
            assertEquals(listOf("run-1" to "session-a"), f.starts)
            val service = backgroundScope.launch { controller.recordInService("run-1", "session-a") }
            runCurrent()
            assertEquals(1, f.maxActiveWrites)
            assertEquals(1, f.saved.size)
            host.cancelAndJoin()
            f.vehicle.state.value = driving
            runCurrent()
            assertEquals(listOf(parked, driving), f.saved.map { it.second.state })
            controller.stopBackground("run-1")
            runCurrent()
            service.join()
        }

    @Test
    fun `initial enable rejects unsafe live inputs and a dead UI owner without creating work`() =
        runTest {
            val f = Fixture(backgroundScope)
            val controller = f.controller()
            runCurrent()
            assertFalse(controller.requestBackground(session()) { false })
            assertFalse(controller.requestBackground(session().copy(endedAtEpochMillis = 110)) { true })
            f.vehicle.state.value = driving
            assertFalse(controller.requestBackground(session()) { true })
            f.vehicle.state.value = parked
            f.restrictions.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNKNOWN)
            assertFalse(controller.requestBackground(session()) { true })
            assertTrue(f.rows.value.isEmpty())
            assertTrue(f.starts.isEmpty())
            assertTrue(f.attempts.isEmpty())
        }

    @Test
    fun `source and UI permission are rechecked across marker open and service acknowledgement`() =
        runTest {
            val f = Fixture(backgroundScope)
            var allowed = true
            f.afterOpen = { allowed = false }
            val controller = f.controller()
            assertTrue(controller.requestBackground(session()) { allowed })
            runCurrent()
            assertEquals(BackgroundRecordingState.Reason.BLOCKED, f.failure(controller))
            assertEquals(
                RecordingRunStatus.FAILED,
                f.rows.value
                    .single()
                    .status,
            )
            assertTrue(f.starts.isEmpty())
            assertTrue(f.attempts.isEmpty())
            assertTrue(controller.stopBackground("run-1"))
            f.afterOpen = {}
            allowed = true
            assertTrue(controller.requestBackground(session()) { allowed })
            runCurrent()
            f.vehicle.state.value = driving
            controller.recordInService("run-2", "session-a")
            runCurrent()
            assertEquals(BackgroundRecordingState.Reason.BLOCKED, f.failure(controller))
            assertEquals(
                RecordingRunStatus.FAILED,
                f.rows.value
                    .last()
                    .status,
            )
            assertEquals(listOf("run-2"), f.stops)
            assertTrue(f.attempts.isEmpty())
        }

    @Test
    fun `same id changed source and existing open marker remain explicit startup rejections`() =
        runTest {
            val f = Fixture(backgroundScope)
            f.sessions.value = listOf(session().copy(courseTitle = "changed"))
            val controller = f.controller()
            assertTrue(controller.requestBackground(session()) { true })
            runCurrent()
            assertEquals(BackgroundRecordingState.Reason.SOURCE_CHANGED, f.failure(controller))
            assertTrue(f.rows.value.isEmpty())
            assertTrue(f.starts.isEmpty())
            controller.stopBackground("run-1")
            f.sessions.value = listOf(session())
            f.rows.value = listOf(run("other", process = "process"))
            assertTrue(controller.requestBackground(session()) { true })
            runCurrent()
            assertEquals(BackgroundRecordingState.Reason.ALREADY_OPEN, f.failure(controller))
            assertEquals(listOf("other"), f.rows.value.map { it.runId })
            assertTrue(f.starts.isEmpty())
        }

    @Test
    fun `active run ignores UI disappearance driving and unknown SDK values and rejects stale intents`() =
        runTest {
            val f = Fixture(backgroundScope)
            var alive = true
            val controller = f.controller()
            assertTrue(controller.requestBackground(session()) { alive })
            runCurrent()
            val service = backgroundScope.launch { controller.recordInService("run-1", "session-a") }
            runCurrent()
            alive = false
            f.vehicle.state.value = driving
            f.restrictions.restrictions.value = DrivingRestrictions()
            runCurrent()
            f.vehicle.state.value = VehicleState(connection = ConnectionState.ERROR)
            runCurrent()
            controller.recordInService("wrong-run", "session-a")
            controller.recordInService("run-1", "wrong-session")
            controller.recordInService("run-1", "session-a")
            controller.startFailed("wrong-run")
            assertFalse(controller.stopBackground("wrong-run"))
            runCurrent()
            assertEquals(3, f.saved.size)
            assertEquals(
                VehicleState(connection = ConnectionState.ERROR),
                f.saved
                    .last()
                    .second.state,
            )
            assertEquals(BackgroundRecordingState.Active("run-1", "session-a"), controller.background.value)
            assertEquals(1, f.vehicle.state.subscriptionCount.value)
            controller.stopBackground("run-1")
            runCurrent()
            service.join()
        }

    @Test
    fun `explicit stop joins pending append before finishing marker and stopping native service`() =
        runTest {
            val f = Fixture(backgroundScope)
            val cleanup = CompletableDeferred<Unit>()
            var cancelled = false
            f.append = {
                try {
                    awaitCancellation()
                } catch (failure: CancellationException) {
                    cancelled = true
                    withContext(NonCancellable) { cleanup.await() }
                    throw failure
                }
            }
            val controller = f.controller()
            controller.requestBackground(session()) { true }
            runCurrent()
            val service = backgroundScope.launch { controller.recordInService("run-1", "session-a") }
            runCurrent()
            assertTrue(controller.stopBackground("run-1"))
            runCurrent()
            assertTrue(cancelled)
            assertTrue(f.finishes.isEmpty())
            assertTrue(f.stops.isEmpty())
            assertEquals(
                RecordingRunStatus.OPEN,
                f.rows.value
                    .single()
                    .status,
            )
            cleanup.complete(Unit)
            runCurrent()
            service.join()
            assertEquals(0, f.activeWrites)
            assertTrue(f.saved.isEmpty())
            assertEquals(
                RecordingRunStatus.STOPPED,
                f.rows.value
                    .single()
                    .status,
            )
            assertEquals(listOf("finish:run-1", "stop:run-1"), f.events.takeLast(2))
        }

    @Test
    fun `session finish stops without another SDK event and new session is never adopted`() =
        runTest {
            val f = Fixture(backgroundScope)
            val controller = f.controller()
            controller.requestBackground(session()) { true }
            runCurrent()
            val service = backgroundScope.launch { controller.recordInService("run-1", "session-a") }
            runCurrent()
            f.sessions.value = listOf(session().copy(endedAtEpochMillis = 150))
            runCurrent()
            service.join()
            assertEquals(
                RecordingRunStatus.STOPPED,
                f.rows.value
                    .single()
                    .status,
            )
            assertEquals(0, f.vehicle.state.subscriptionCount.value)
            f.sessions.value = listOf(session("session-b"), session().copy(endedAtEpochMillis = 150))
            f.vehicle.state.value = driving
            runCurrent()
            assertEquals(listOf("session-a"), f.saved.map { it.first })
            assertEquals(BackgroundRecordingState.Disabled, controller.background.value)
        }

    @Test
    fun `active session replacement or metadata change fails instead of borrowing a fresh source`() =
        runTest {
            listOf(session("session-b"), session().copy(taskTitle = "changed")).forEach { changed ->
                val f = Fixture(backgroundScope)
                val controller = f.controller()
                controller.requestBackground(session()) { true }
                runCurrent()
                val service = backgroundScope.launch { controller.recordInService("run-1", "session-a") }
                runCurrent()
                f.sessions.value = listOf(changed)
                runCurrent()
                service.join()
                assertEquals(BackgroundRecordingState.Reason.SOURCE_CHANGED, f.failure(controller))
                assertEquals(
                    RecordingRunStatus.FAILED,
                    f.rows.value
                        .single()
                        .status,
                )
                assertEquals(listOf("session-a"), f.saved.map { it.first })
            }
        }

    @Test
    fun `write failure stays failed across hot inputs until explicit new run or opt out`() =
        runTest {
            val f = Fixture(backgroundScope)
            f.append = { throw IOException("private fixture data") }
            val controller = f.controller()
            val host = backgroundScope.launch { controller.recordWhileStarted() }
            runCurrent()
            controller.requestBackground(session()) { true }
            runCurrent()
            val service = backgroundScope.launch { controller.recordInService("run-1", "session-a") }
            runCurrent()
            service.join()
            val attempts = f.attempts.size
            f.vehicle.state.value = driving
            f.sessions.value = listOf(session().copy(goalTitle = "updated"))
            runCurrent()
            assertEquals(attempts, f.attempts.size)
            assertFalse(controller.retry())
            assertEquals(BackgroundRecordingState.Reason.OBSERVATION, f.failure(controller))
            f.vehicle.state.value = parked
            f.sessions.value = listOf(session())
            f.append = { VehicleObservationAppendResult.Appended(10) }
            assertTrue(controller.requestBackground(session()) { true })
            runCurrent()
            controller.startFailed("run-1")
            assertFalse(controller.stopBackground("run-1"))
            val nextService = backgroundScope.launch { controller.recordInService("run-2", "session-a") }
            runCurrent()
            assertEquals(BackgroundRecordingState.Active("run-2", "session-a"), controller.background.value)
            host.cancelAndJoin()
            controller.stopBackground("run-2")
            runCurrent()
            nextService.join()
        }

    @Test
    fun `finite failed and independently cancelled session monitoring all stop background collection`() =
        runTest {
            listOf<() -> Flow<List<PracticeSession>>>(
                { flowOf(listOf(session())) },
                {
                    flow {
                        emit(listOf(session()))
                        throw IOException("private fixture")
                    }
                },
                {
                    flow {
                        emit(listOf(session()))
                        throw CancellationException("independent fixture")
                    }
                },
            ).forEach { source ->
                val f = Fixture(backgroundScope)
                f.sessionSource = source
                val controller = f.controller()
                controller.requestBackground(session()) { true }
                runCurrent()
                val service = backgroundScope.launch { controller.recordInService("run-1", "session-a") }
                runCurrent()
                service.join()
                assertEquals(BackgroundRecordingState.Reason.SESSION_READ, f.failure(controller))
                assertEquals(
                    RecordingRunStatus.FAILED,
                    f.rows.value
                        .single()
                        .status,
                )
                assertEquals(0, f.vehicle.state.subscriptionCount.value)
                val count = f.attempts.size
                f.vehicle.state.value = driving
                runCurrent()
                assertEquals(count, f.attempts.size)
            }
        }

    @Test
    fun `independent append cancellation closes failed run instead of leaving recording visible`() =
        runTest {
            val f = Fixture(backgroundScope)
            f.append = { throw CancellationException("standalone cancellation") }
            val controller = f.controller()
            controller.requestBackground(session()) { true }
            runCurrent()
            val service = backgroundScope.launch { controller.recordInService("run-1", "session-a") }
            runCurrent()
            service.join()
            assertEquals(BackgroundRecordingState.Reason.OBSERVATION, f.failure(controller))
            assertEquals(
                RecordingRunStatus.FAILED,
                f.rows.value
                    .single()
                    .status,
            )
            assertNull(
                f.rows.value
                    .single()
                    .endedAtEpochMillis,
            )
            assertEquals(0, f.vehicle.state.subscriptionCount.value)
            assertTrue(f.saved.isEmpty())
        }

    @Test
    fun `service start timeout is virtual-time bounded and keeps the end unknown`() =
        runTest {
            val f = Fixture(backgroundScope)
            val controller = f.controller()
            controller.requestBackground(session()) { true }
            runCurrent()
            advanceTimeBy(9_999)
            runCurrent()
            assertEquals(BackgroundRecordingState.Starting("run-1", "session-a"), controller.background.value)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(BackgroundRecordingState.Reason.SERVICE_START, f.failure(controller))
            assertEquals(
                RecordingRunStatus.FAILED,
                f.rows.value
                    .single()
                    .status,
            )
            assertNull(
                f.rows.value
                    .single()
                    .endedAtEpochMillis,
            )
            assertEquals(listOf("run-1"), f.stops)
            assertTrue(f.attempts.isEmpty())
            controller.recordInService("run-1", "session-a")
            assertTrue(f.attempts.isEmpty())
        }

    @Test
    fun `native service lifetime cancellation joins recorder and marks unknown interruption`() =
        runTest {
            val f = Fixture(backgroundScope)
            val controller = f.controller()
            controller.requestBackground(session()) { true }
            runCurrent()
            val service = backgroundScope.launch { controller.recordInService("run-1", "session-a") }
            runCurrent()
            service.cancelAndJoin()
            assertEquals(
                RecordingRunStatus.FAILED,
                f.rows.value
                    .single()
                    .status,
            )
            assertNull(
                f.rows.value
                    .single()
                    .endedAtEpochMillis,
            )
            assertEquals(0, f.vehicle.state.subscriptionCount.value)
            assertEquals(listOf("run-1"), f.stops)
        }

    @Test
    fun `native startup failure and a throwing start hook close only the exact current run`() =
        runTest {
            val f = Fixture(backgroundScope)
            f.onStart = { _, _ -> throw IOException("private platform details") }
            val controller = f.controller()
            controller.requestBackground(session()) { true }
            runCurrent()
            assertEquals(BackgroundRecordingState.Reason.SERVICE_START, f.failure(controller))
            assertEquals(
                RecordingRunStatus.FAILED,
                f.rows.value
                    .single()
                    .status,
            )
            assertNull(
                f.rows.value
                    .single()
                    .endedAtEpochMillis,
            )
            assertTrue(f.attempts.isEmpty())
            controller.stopBackground("run-1")
            f.onStart = { _, _ -> }
            controller.requestBackground(session()) { true }
            runCurrent()
            controller.startFailed("run-1")
            assertEquals(BackgroundRecordingState.Starting("run-2", "session-a"), controller.background.value)
            controller.startFailed("run-2")
            runCurrent()
            assertEquals(BackgroundRecordingState.Reason.SERVICE_START, f.failure(controller))
            assertEquals(listOf("run-1", "run-2"), f.stops)
        }

    @Test
    fun `recovery is scoped once exposes only active interruption and leaves foreground usable`() =
        runTest {
            val f = Fixture(backgroundScope)
            f.rows.value =
                listOf(run("previous", process = "old-process"), run("ended", sessionId = "ended-session", process = "old-process"))
            val controller = f.controller()
            runCurrent()
            assertEquals(1, f.recoveries)
            assertTrue(f.starts.isEmpty())
            assertTrue(f.rows.value.all { it.status == RecordingRunStatus.INTERRUPTED && it.endedAtEpochMillis == null })
            val host = backgroundScope.launch { controller.recordWhileStarted() }
            runCurrent()
            assertEquals(BackgroundRecordingState.Interrupted("session-a"), controller.background.value)
            assertEquals(VehicleRecordingState.Recording, controller.state.value)
            f.sessions.value = listOf(session("session-b"), session().copy(endedAtEpochMillis = 150))
            runCurrent()
            assertEquals(BackgroundRecordingState.Disabled, controller.background.value)
            assertEquals(1, f.recoveries)
            assertTrue(f.starts.isEmpty())
            host.cancelAndJoin()
        }

    @Test
    fun `stored failed run restores an active source warning without guessing cause or restarting background`() =
        runTest {
            listOf<Long?>(null, 120L).forEach { endedAt ->
                val f = Fixture(backgroundScope)
                f.rows.value =
                    listOf(
                        run("previous", process = "old-process", status = RecordingRunStatus.FAILED)
                            .copy(endedAtEpochMillis = endedAt),
                    )
                val controller = f.controller()
                var host = backgroundScope.launch { controller.recordWhileStarted() }
                runCurrent()
                assertEquals(
                    BackgroundRecordingState.Failed(null, "session-a", BackgroundRecordingState.Reason.PREVIOUS_RUN),
                    controller.background.value,
                )
                assertEquals(VehicleRecordingState.Recording, controller.state.value)
                assertEquals(1, f.saved.size)
                assertTrue(f.starts.isEmpty())
                host.cancelAndJoin()
                assertEquals(BackgroundRecordingState.Disabled, controller.background.value)
                host = backgroundScope.launch { controller.recordWhileStarted() }
                runCurrent()
                assertEquals(BackgroundRecordingState.Reason.PREVIOUS_RUN, f.failure(controller))
                assertTrue(controller.requestBackground(session()) { true })
                runCurrent()
                val service = backgroundScope.launch { controller.recordInService("run-1", "session-a") }
                runCurrent()
                assertEquals(BackgroundRecordingState.Active("run-1", "session-a"), controller.background.value)
                assertEquals(listOf("previous", "run-1"), f.rows.value.map { it.runId })
                host.cancelAndJoin()
                controller.stopBackground("run-1")
                runCurrent()
                service.join()
            }
        }

    @Test
    fun `historical failed warning leaves with its active source and never borrows another ended session`() =
        runTest {
            val f = Fixture(backgroundScope)
            f.rows.value =
                listOf(
                    run("previous", process = "old-process", status = RecordingRunStatus.FAILED),
                    run("unrelated", sessionId = "ended-session", process = "old-process", status = RecordingRunStatus.FAILED),
                )
            val controller = f.controller()
            val host = backgroundScope.launch { controller.recordWhileStarted() }
            runCurrent()
            assertEquals(BackgroundRecordingState.Reason.PREVIOUS_RUN, f.failure(controller))
            f.sessions.value = listOf(session().copy(endedAtEpochMillis = 150))
            runCurrent()
            assertEquals(BackgroundRecordingState.Disabled, controller.background.value)
            assertEquals(VehicleRecordingState.Idle, controller.state.value)
            f.sessions.value = listOf(session("session-b"), session().copy(endedAtEpochMillis = 150))
            runCurrent()
            assertEquals(BackgroundRecordingState.Disabled, controller.background.value)
            assertEquals(VehicleRecordingState.Recording, controller.state.value)
            assertEquals("session-b", f.saved.last().first)
            assertTrue(f.starts.isEmpty())
            assertTrue(f.rows.value.all { it.status == RecordingRunStatus.FAILED })
            host.cancelAndJoin()
        }

    @Test
    fun `latest stored creation order controls failed warning even when wall clock goes backward`() =
        runTest {
            val failed =
                run("failed", process = "old-process", status = RecordingRunStatus.FAILED)
                    .copy(startedAtEpochMillis = 500, endedAtEpochMillis = 550)
            val stopped =
                run("stopped", process = "old-process", status = RecordingRunStatus.STOPPED)
                    .copy(startedAtEpochMillis = 200, endedAtEpochMillis = 250)
            val scenarios =
                listOf(
                    listOf(failed, stopped) to BackgroundRecordingState.Disabled,
                    listOf(stopped, failed) to
                        BackgroundRecordingState.Failed(null, "session-a", BackgroundRecordingState.Reason.PREVIOUS_RUN),
                )
            scenarios.forEach { (rows, expected) ->
                val f = Fixture(backgroundScope)
                f.rows.value = rows
                val controller = f.controller()
                val host = backgroundScope.launch { controller.recordWhileStarted() }
                runCurrent()
                assertEquals(expected, controller.background.value)
                assertEquals(VehicleRecordingState.Recording, controller.state.value)
                assertTrue(f.starts.isEmpty())
                host.cancelAndJoin()
            }
        }

    @Test
    fun `an older interrupted run keeps its warning when the latest run stopped or failed`() =
        runTest {
            listOf(RecordingRunStatus.STOPPED, RecordingRunStatus.FAILED).forEach { latestStatus ->
                val f = Fixture(backgroundScope)
                f.rows.value =
                    listOf(
                        run("interrupted", process = "old-process", status = RecordingRunStatus.INTERRUPTED),
                        run("latest", process = "old-process", status = latestStatus),
                    )
                val controller = f.controller()
                val host = backgroundScope.launch { controller.recordWhileStarted() }
                runCurrent()
                assertEquals(BackgroundRecordingState.Interrupted("session-a"), controller.background.value)
                assertEquals(VehicleRecordingState.Recording, controller.state.value)
                assertTrue(f.starts.isEmpty())
                host.cancelAndJoin()
            }
        }

    @Test
    fun `late failed run read cannot attach its warning to a newly active source`() =
        runTest {
            val f = Fixture(backgroundScope)
            val oldRead = CompletableDeferred<Unit>()
            f.runSource = { id ->
                if (id == "session-a") {
                    flow {
                        try {
                            oldRead.await()
                        } catch (_: CancellationException) {
                            withContext(NonCancellable) { oldRead.await() }
                        }
                        emit(listOf(run("old-failed", status = RecordingRunStatus.FAILED, process = "old-process")))
                        awaitCancellation()
                    }
                } else {
                    flow {
                        emit(emptyList())
                        awaitCancellation()
                    }
                }
            }
            val controller = f.controller()
            val host = backgroundScope.launch { controller.recordWhileStarted() }
            runCurrent()
            f.sessions.value = listOf(session("session-b"))
            runCurrent()
            oldRead.complete(Unit)
            runCurrent()
            assertEquals(BackgroundRecordingState.Disabled, controller.background.value)
            assertEquals("session-b", f.saved.last().first)
            host.cancelAndJoin()
        }

    @Test
    fun `late marker reader cannot attach old interruption to the newly active session`() =
        runTest {
            val f = Fixture(backgroundScope)
            val oldRead = CompletableDeferred<Unit>()
            f.runSource = { id ->
                if (id == "session-a") {
                    flow {
                        try {
                            oldRead.await()
                        } catch (_: CancellationException) {
                            withContext(NonCancellable) { oldRead.await() }
                        }
                        emit(listOf(run("old", status = RecordingRunStatus.INTERRUPTED, process = "old-process")))
                        awaitCancellation()
                    }
                } else {
                    flow {
                        emit(emptyList())
                        awaitCancellation()
                    }
                }
            }
            val controller = f.controller()
            val host = backgroundScope.launch { controller.recordWhileStarted() }
            runCurrent()
            f.sessions.value = listOf(session("session-b"))
            runCurrent()
            oldRead.complete(Unit)
            runCurrent()
            assertEquals(BackgroundRecordingState.Disabled, controller.background.value)
            assertEquals("session-b", f.saved.last().first)
            host.cancelAndJoin()
        }

    @Test
    fun `recovery failure blocks background startup while ordinary foreground and retry remain usable`() =
        runTest {
            val f = Fixture(backgroundScope)
            f.recover = { throw IOException("private storage detail") }
            val controller = f.controller()
            val host = backgroundScope.launch { controller.recordWhileStarted() }
            runCurrent()
            assertEquals(BackgroundRecordingState.Reason.RECOVERY, f.failure(controller))
            assertEquals(VehicleRecordingState.Recording, controller.state.value)
            assertEquals(1, f.saved.size)
            assertFalse(controller.requestBackground(session()) { true })
            runCurrent()
            assertEquals(BackgroundRecordingState.Reason.RECOVERY, f.failure(controller))
            assertTrue(f.starts.isEmpty())
            assertTrue(f.rows.value.isEmpty())
            assertEquals(VehicleRecordingState.Recording, controller.state.value)
            host.cancelAndJoin()
        }

    @Test
    fun `pending recovery failure releases unlaunched background ownership and foreground remains usable`() =
        runTest {
            val f = Fixture(backgroundScope)
            val release = CompletableDeferred<Unit>()
            f.recover = {
                release.await()
                throw IOException("private recovery failure")
            }
            val controller = f.controller()
            val host = backgroundScope.launch { controller.recordWhileStarted() }
            assertTrue(controller.requestBackground(session()) { true })
            runCurrent()
            assertTrue(f.starts.isEmpty())
            release.complete(Unit)
            runCurrent()
            assertEquals(BackgroundRecordingState.Reason.RECOVERY, f.failure(controller))
            assertNull((controller.background.value as BackgroundRecordingState.Failed).runId)
            assertEquals(VehicleRecordingState.Recording, controller.state.value)
            assertEquals(1, f.saved.size)
            assertFalse(controller.stopBackground("run-1"))
            host.cancelAndJoin()
        }

    @Test
    fun `old failed session requires exact opt out before enabling a different active source`() =
        runTest {
            val f = Fixture(backgroundScope)
            val controller = f.controller()
            controller.requestBackground(session()) { true }
            runCurrent()
            controller.startFailed("run-1")
            runCurrent()
            f.sessions.value = listOf(session("session-b"))
            assertFalse(controller.requestBackground(session("session-b")) { true })
            f.vehicle.state.value = driving
            f.restrictions.restrictions.value = DrivingRestrictions()
            assertTrue(controller.stopBackground("run-1"))
            assertFalse(controller.stopBackground("wrong"))
            f.vehicle.state.value = parked
            f.restrictions.restrictions.value = unrestricted
            assertTrue(controller.requestBackground(session("session-b")) { true })
            runCurrent()
            assertEquals(BackgroundRecordingState.Starting("run-2", "session-b"), controller.background.value)
            controller.startFailed("run-2")
            runCurrent()
        }

    @Test
    fun `wrong session rows and unrecovered foreign open markers fail closed`() =
        runTest {
            listOf(
                listOf(run("wrong", sessionId = "other-session", status = RecordingRunStatus.INTERRUPTED, process = "older")),
                listOf(run("unrecovered", process = "older")),
            ).forEach { rows ->
                val f = Fixture(backgroundScope)
                f.recover = {}
                f.runSource = { MutableStateFlow(rows) }
                val controller = f.controller()
                val host = backgroundScope.launch { controller.recordWhileStarted() }
                runCurrent()
                assertEquals(BackgroundRecordingState.Reason.RECOVERY, f.failure(controller))
                assertEquals(VehicleRecordingState.Recording, controller.state.value)
                controller.requestBackground(session()) { true }
                runCurrent()
                assertEquals(BackgroundRecordingState.Reason.RECOVERY, f.failure(controller))
                assertTrue(f.starts.isEmpty())
                assertTrue(f.rows.value.isEmpty())
                host.cancelAndJoin()
            }
        }

    @Test
    fun `marker finish rejection retries only failed unknown end and never reports a clean stop`() =
        runTest {
            val f = Fixture(backgroundScope)
            f.finishResult = { status, end -> status == RecordingRunStatus.FAILED && end == null }
            val controller = f.controller()
            controller.requestBackground(session()) { true }
            runCurrent()
            val service = backgroundScope.launch { controller.recordInService("run-1", "session-a") }
            runCurrent()
            controller.stopBackground("run-1")
            runCurrent()
            service.join()
            assertEquals(listOf(RecordingRunStatus.STOPPED to 123L, RecordingRunStatus.FAILED to null), f.finishes)
            assertEquals(
                RecordingRunStatus.FAILED,
                f.rows.value
                    .single()
                    .status,
            )
            assertEquals(BackgroundRecordingState.Reason.PERSISTENCE, f.failure(controller))
            assertEquals(listOf("run-1"), f.stops)
        }

    @Test
    fun `opt out during failed cleanup is retained until the recorder and marker are settled`() =
        runTest {
            val f = Fixture(backgroundScope)
            val finish = CompletableDeferred<Unit>()
            f.append = { throw IOException("private write failure") }
            f.beforeFinish = { finish.await() }
            val controller = f.controller()
            controller.requestBackground(session()) { true }
            runCurrent()
            val service = backgroundScope.launch { controller.recordInService("run-1", "session-a") }
            runCurrent()
            assertEquals(BackgroundRecordingState.Reason.OBSERVATION, f.failure(controller))
            assertTrue(controller.stopBackground("run-1"))
            assertTrue(f.stops.isEmpty())
            finish.complete(Unit)
            runCurrent()
            service.join()
            assertEquals(BackgroundRecordingState.Disabled, controller.background.value)
            assertEquals(
                RecordingRunStatus.FAILED,
                f.rows.value
                    .single()
                    .status,
            )
            assertEquals(listOf("run-1"), f.stops)
            assertFalse(controller.stopBackground("run-1"))
        }

    @Test
    fun `identifier factory failure preserves the current failed token for explicit opt out`() =
        runTest {
            val f = Fixture(backgroundScope)
            val controller = f.controller(nextId = { "same" })
            controller.requestBackground(session()) { true }
            runCurrent()
            controller.startFailed("same")
            runCurrent()
            assertFalse(controller.requestBackground(session()) { true })
            assertEquals("same", (controller.background.value as BackgroundRecordingState.Failed).runId)
            assertTrue(controller.stopBackground("same"))
            assertEquals(BackgroundRecordingState.Disabled, controller.background.value)
        }

    @Test
    fun `acknowledged service survives the cancelled startup timer and old timeout cannot stop a new run`() =
        runTest {
            val f = Fixture(backgroundScope)
            val controller = f.controller()
            controller.requestBackground(session()) { true }
            runCurrent()
            advanceTimeBy(9_999)
            val service = backgroundScope.launch { controller.recordInService("run-1", "session-a") }
            runCurrent()
            advanceTimeBy(1)
            runCurrent()
            assertEquals(BackgroundRecordingState.Active("run-1", "session-a"), controller.background.value)
            controller.stopBackground("run-1")
            runCurrent()
            service.join()
            controller.requestBackground(session()) { true }
            runCurrent()
            val next = backgroundScope.launch { controller.recordInService("run-2", "session-a") }
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(BackgroundRecordingState.Active("run-2", "session-a"), controller.background.value)
            assertEquals(listOf("run-1"), f.stops)
            controller.stopBackground("run-2")
            runCurrent()
            next.join()
        }

    @Test
    fun `reused run identifiers are refused so an old service intent cannot acquire fresh ownership`() =
        runTest {
            val f = Fixture(backgroundScope)
            val controller = f.controller(nextId = { "same" })
            controller.requestBackground(session()) { true }
            runCurrent()
            controller.startFailed("same")
            runCurrent()
            controller.stopBackground("same")
            assertFalse(controller.requestBackground(session()) { true })
            assertEquals(BackgroundRecordingState.Reason.OPEN, f.failure(controller))
            assertEquals(1, f.starts.size)
        }

    private class Fixture(
        private val scope: CoroutineScope,
    ) {
        val vehicle = FakeVehicle()
        val restrictions = FakeRestrictions()
        val sessions = MutableStateFlow(listOf(session()))
        val rows = MutableStateFlow<List<VehicleRecordingRun>>(emptyList())
        val starts = mutableListOf<Pair<String, String>>()
        val stops = mutableListOf<String>()
        val attempts = mutableListOf<Pair<String, VehicleObservation>>()
        val saved = mutableListOf<Pair<String, VehicleObservation>>()
        val finishes = mutableListOf<Pair<RecordingRunStatus, Long?>>()
        val events = mutableListOf<String>()
        var activeWrites = 0
        var maxActiveWrites = 0
        var recoveries = 0
        var sequence = 0
        var sessionSource: () -> Flow<List<PracticeSession>> = { sessions }
        var runSource: (String) -> Flow<List<VehicleRecordingRun>> = { id -> rows.mapRows(id) }
        var recover: suspend () -> Unit = {
            rows.value =
                rows.value.map {
                    if (it.status == RecordingRunStatus.OPEN &&
                        it.processId != "process"
                    ) {
                        it.copy(status = RecordingRunStatus.INTERRUPTED, endedAtEpochMillis = null)
                    } else {
                        it
                    }
                }
        }
        var append: suspend (
            VehicleObservation,
        ) -> VehicleObservationAppendResult = { VehicleObservationAppendResult.Appended(attempts.size.toLong()) }
        var beforeOpenCommit: suspend () -> Unit = {}
        var deliverOpened: suspend (RecordingRunOpenResult.Opened) -> RecordingRunOpenResult = { it }
        var afterOpen: () -> Unit = {}
        var onStart: (String, String) -> Unit = { _, _ -> }
        var beforeFinish: suspend () -> Unit = {}
        var finishResult: (RecordingRunStatus, Long?) -> Boolean = { _, _ -> true }
        private val sessionRepository =
            object : PracticeSessionRepository {
                override fun observeSessions(): Flow<List<PracticeSession>> = flow { emitAll(sessionSource()) }

                override suspend fun start(
                    practice: PreparedPractice,
                    startedAtEpochMillis: Long,
                    canCommit: () -> Boolean,
                ): SessionStartResult = error("unused")

                override suspend fun finish(
                    sessionId: String,
                    endedAtEpochMillis: Long,
                    canCommit: () -> Boolean,
                ): SessionFinishResult = error("unused")
            }
        private val observations =
            object : VehicleObservationRepository {
                override fun observe(sessionId: String): Flow<List<SessionVehicleObservation>> = error("unused")

                override suspend fun append(
                    sessionId: String,
                    observation: VehicleObservation,
                ): VehicleObservationAppendResult {
                    attempts += sessionId to observation
                    activeWrites++
                    maxActiveWrites = maxOf(maxActiveWrites, activeWrites)
                    return try {
                        this@Fixture.append(observation).also {
                            if (it is VehicleObservationAppendResult.Appended) {
                                saved +=
                                    sessionId to observation
                            }
                        }
                    } finally {
                        activeWrites--
                    }
                }
            }
        private val runRepository =
            object : RecordingRunRepository {
                override fun observe(sessionId: String): Flow<List<VehicleRecordingRun>> = runSource(sessionId)

                override suspend fun open(
                    source: PracticeSession,
                    runId: String,
                    processId: String,
                    startedAtEpochMillis: Long,
                    canCommit: () -> Boolean,
                ): RecordingRunOpenResult {
                    if (!canCommit()) return RecordingRunOpenResult.Blocked
                    if (startedAtEpochMillis <= 0) return RecordingRunOpenResult.InvalidTime
                    if (sessions.value.singleOrNull { it.isActive } != source) return RecordingRunOpenResult.SourceChanged
                    if (rows.value.any {
                            it.sessionId == source.sessionId && it.status == RecordingRunStatus.OPEN
                        }
                    ) {
                        return RecordingRunOpenResult.AlreadyOpen
                    }
                    val row = VehicleRecordingRun(runId, source.sessionId, processId, startedAtEpochMillis, null, RecordingRunStatus.OPEN)
                    beforeOpenCommit()
                    if (!canCommit()) return RecordingRunOpenResult.Blocked
                    rows.value += row
                    afterOpen()
                    return deliverOpened(RecordingRunOpenResult.Opened(row))
                }

                override suspend fun finish(
                    runId: String,
                    processId: String,
                    status: RecordingRunStatus,
                    endedAtEpochMillis: Long?,
                ): Boolean {
                    assertEquals(0, activeWrites)
                    beforeFinish()
                    events += "finish:$runId"
                    finishes += status to endedAtEpochMillis
                    if (!finishResult(status, endedAtEpochMillis)) return false
                    val actual =
                        rows.value.singleOrNull { it.runId == runId && it.processId == processId && it.status == RecordingRunStatus.OPEN }
                            ?: return false
                    rows.value =
                        rows.value.map { if (it == actual) it.copy(status = status, endedAtEpochMillis = endedAtEpochMillis) else it }
                    return true
                }

                override suspend fun recoverInterrupted(processId: String) {
                    recoveries++
                    recover()
                }
            }

        fun controller(nextId: () -> String = { "run-${++sequence}" }) =
            PracticeRecordingController(
                vehicle,
                sessionRepository,
                observations,
                restrictions,
                runRepository,
                Clock.fixed(Instant.ofEpochMilli(123), ZoneOffset.UTC),
                scope,
                nextId,
                "process",
                { runId, sessionId ->
                    starts += runId to sessionId
                    onStart(runId, sessionId)
                },
                { runId ->
                    assertEquals(0, activeWrites)
                    events += "stop:$runId"
                    stops += runId
                },
            )

        fun failure(controller: RecordingController): BackgroundRecordingState.Reason =
            (controller.background.value as BackgroundRecordingState.Failed).reason

        private fun MutableStateFlow<List<VehicleRecordingRun>>.mapRows(id: String): Flow<List<VehicleRecordingRun>> =
            flow { collect { emit(it.filter { row -> row.sessionId == id }) } }
    }

    private class FakeVehicle : VehicleGateway {
        override val state = MutableStateFlow(parked)
    }

    private class FakeRestrictions : DrivingRestrictionsProvider {
        override val restrictions = MutableStateFlow(unrestricted)
    }

    private companion object {
        val parked = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))
        val driving = parked.copy(gear = VehicleReading.Available(GearPosition.DRIVE))
        val unrestricted = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)

        fun session(id: String = "session-a") =
            PracticeSession(
                id,
                PracticeSelection("goal", "course", 1, 1, "task"),
                "goal",
                "course",
                "task",
                CourseReviewStatus.DEMO,
                100,
                null,
            )

        fun run(
            id: String,
            sessionId: String = "session-a",
            process: String = "process",
            status: RecordingRunStatus = RecordingRunStatus.OPEN,
        ) = VehicleRecordingRun(id, sessionId, process, 110, null, status)
    }
}
