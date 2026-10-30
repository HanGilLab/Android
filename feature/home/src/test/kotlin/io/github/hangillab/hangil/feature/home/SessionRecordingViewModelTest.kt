package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import io.github.hangillab.hangil.core.domain.VehicleObservationRepository
import io.github.hangillab.hangil.core.domain.VehicleObservationSource
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRecordingViewModelTest {
    @Test
    fun `no active session is idle without subscribing to vehicle snapshots or needing UI observers`() =
        runTest {
            val f = Fixture()
            val model = f.model()
            assertEquals(VehicleRecordingState.Paused, model.state.value)
            val host = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            assertEquals(VehicleRecordingState.Idle, model.state.value)
            assertEquals(1, f.sessionReads)
            assertEquals(0, f.vehicle.state.subscriptionCount.value)
            assertTrue(f.attempts.isEmpty())
            assertFalse(model.retry())
            host.cancelAndJoin()
            assertEquals(VehicleRecordingState.Paused, model.state.value)
        }

    @Test
    fun `foreground active session records unknown and driving states independently of parking and UI collection`() =
        runTest {
            val f = Fixture()
            f.records.value = listOf(session())
            f.vehicle.state.value = VehicleState()
            f.restrictions.restrictions.value = DrivingRestrictions()
            val model = f.model()
            val host = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            assertEquals(VehicleRecordingState.Recording, model.state.value)
            assertEquals(
                VehicleState(),
                f.saved
                    .single()
                    .observation.state,
            )
            assertEquals(
                VehicleObservationSource.SDK_STATE_OBSERVATION,
                f.saved
                    .single()
                    .observation.source,
            )
            assertEquals(
                123L,
                f.saved
                    .single()
                    .observation.observedAtEpochMillis,
            )
            f.vehicle.state.value = driving
            runCurrent()
            assertEquals(listOf(VehicleState(), driving), f.saved.map { it.observation.state })
            assertTrue(f.saved.all { it.sessionId == "session-a" })
            host.cancelAndJoin()
        }

    @Test
    fun `same active id metadata changes do not restart or duplicate the initial snapshot`() =
        runTest {
            val f = Fixture()
            f.records.value = listOf(session())
            val model = f.model()
            val host = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            f.records.value = listOf(session().copy(courseTitle = "updated title"))
            runCurrent()
            assertEquals(1, f.attempts.size)
            assertEquals(1, f.vehicle.state.subscriptionCount.value)
            assertEquals(1, f.sessionReads)
            host.cancelAndJoin()
        }

    @Test
    fun `session replacement waits for cancelled pending write cleanup before recording the new id`() =
        runTest {
            val f = Fixture()
            f.records.value = listOf(session())
            val cleanup = CompletableDeferred<Unit>()
            var cancellationEntered = false
            f.append = { attempt ->
                if (attempt.sessionId == "session-a") {
                    try {
                        CompletableDeferred<Unit>().await()
                    } catch (cancelled: CancellationException) {
                        cancellationEntered = true
                        withContext(NonCancellable) { cleanup.await() }
                        throw cancelled
                    }
                }
                VehicleObservationAppendResult.Appended(1L)
            }
            val model = f.model()
            val host = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            f.records.value = listOf(session("session-b"), session().copy(endedAtEpochMillis = 150L))
            runCurrent()
            assertTrue(cancellationEntered)
            assertEquals(listOf("session-a"), f.attempts.map { it.sessionId })
            assertEquals(1, f.activeWrites)
            cleanup.complete(Unit)
            runCurrent()
            assertEquals(listOf("session-a", "session-b"), f.attempts.map { it.sessionId })
            assertEquals(listOf("session-b"), f.saved.map { it.sessionId })
            assertEquals(1, f.maxActiveWrites)
            assertEquals(VehicleRecordingState.Recording, model.state.value)
            host.cancelAndJoin()
        }

    @Test
    fun `finishing a session cancels collection even without another vehicle update`() =
        runTest {
            val f = Fixture()
            f.records.value = listOf(session())
            val model = f.model()
            val host = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            f.records.value = listOf(session().copy(endedAtEpochMillis = 150L))
            runCurrent()
            assertEquals(VehicleRecordingState.Idle, model.state.value)
            assertEquals(0, f.vehicle.state.subscriptionCount.value)
            f.vehicle.state.value = driving
            runCurrent()
            assertEquals(1, f.saved.size)
            host.cancelAndJoin()
        }

    @Test
    fun `STOP cancels collection and a new foreground lifetime records only its latest initial state`() =
        runTest {
            val f = Fixture()
            f.records.value = listOf(session())
            val model = f.model()
            val first = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            first.cancelAndJoin()
            assertEquals(VehicleRecordingState.Paused, model.state.value)
            assertEquals(0, f.vehicle.state.subscriptionCount.value)
            f.vehicle.state.value = driving
            runCurrent()
            assertEquals(1, f.saved.size)
            val second = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            assertEquals(2, f.sessionReads)
            assertEquals(listOf(parked, driving), f.saved.map { it.observation.state })
            assertEquals(VehicleRecordingState.Recording, model.state.value)
            second.cancelAndJoin()
        }

    @Test
    fun `overlapping lifecycle hosts are serialized and a waiting cancelled host cannot pause the owner`() =
        runTest {
            val f = Fixture()
            f.records.value = listOf(session())
            val model = f.model()
            val first = backgroundScope.launch { model.recordWhileStarted() }
            val waitingCancelled = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            waitingCancelled.cancelAndJoin()
            assertEquals(VehicleRecordingState.Recording, model.state.value)
            assertEquals(1, f.sessionReads)
            val next = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            assertEquals(1, f.attempts.size)
            assertEquals(1, f.vehicle.state.subscriptionCount.value)
            first.cancelAndJoin()
            runCurrent()
            assertEquals(2, f.sessionReads)
            assertEquals(2, f.attempts.size)
            assertEquals(1, f.vehicle.state.subscriptionCount.value)
            assertEquals(VehicleRecordingState.Recording, model.state.value)
            next.cancelAndJoin()
            assertEquals(VehicleRecordingState.Paused, model.state.value)
        }

    @Test
    fun `write failure remains failed across hot SDK updates and retries only after explicit parked retry`() =
        runTest {
            val f = Fixture()
            f.records.value = listOf(session())
            f.append = { throw IOException("private fixture detail") }
            val model = f.model()
            val host = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            assertEquals(VehicleRecordingState.Failed(VehicleRecordingState.Reason.WRITE), model.state.value)
            f.vehicle.state.value = driving
            f.records.value = listOf(session().copy(goalTitle = "updated title"))
            runCurrent()
            assertEquals(1, f.attempts.size)
            assertFalse(model.retry())
            f.vehicle.state.value = parked
            f.append = { VehicleObservationAppendResult.Appended(2L) }
            assertTrue(model.retry())
            runCurrent()
            assertEquals(VehicleRecordingState.Recording, model.state.value)
            assertEquals(2, f.attempts.size)
            assertEquals(1, f.saved.size)
            assertEquals(2, f.sessionReads)
            host.cancelAndJoin()
        }

    @Test
    fun `failed session read does not resubscribe until explicit retry and does not imply no active session`() =
        runTest {
            val f = Fixture()
            f.records.value = listOf(session())
            var failRead = true
            f.source = {
                flow {
                    if (failRead) throw IOException("private fixture detail")
                    emitAll(f.records)
                }
            }
            val model = f.model()
            val host = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            assertEquals(VehicleRecordingState.Failed(VehicleRecordingState.Reason.SESSION_READ), model.state.value)
            assertEquals(0, f.attempts.size)
            failRead = false
            runCurrent()
            assertEquals(1, f.sessionReads)
            assertTrue(model.retry())
            runCurrent()
            assertEquals(2, f.sessionReads)
            assertEquals(1, f.saved.size)
            assertEquals(VehicleRecordingState.Recording, model.state.value)
            host.cancelAndJoin()
        }

    @Test
    fun `session stream failure cancels an active vehicle subscription and is exposed as read failure`() =
        runTest {
            val f = Fixture()
            val fail = CompletableDeferred<Unit>()
            f.source = {
                flow {
                    emit(listOf(session()))
                    fail.await()
                    throw IOException("fixture read failed")
                }
            }
            val model = f.model()
            val host = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            assertEquals(1, f.saved.size)
            fail.complete(Unit)
            runCurrent()
            assertEquals(VehicleRecordingState.Failed(VehicleRecordingState.Reason.SESSION_READ), model.state.value)
            assertEquals(0, f.vehicle.state.subscriptionCount.value)
            f.vehicle.state.value = driving
            runCurrent()
            assertEquals(1, f.attempts.size)
            host.cancelAndJoin()
        }

    @Test
    fun `cancelling a pending append propagates STOP without showing failure or leaving detached writes`() =
        runTest {
            val f = Fixture()
            f.records.value = listOf(session())
            val release = CompletableDeferred<Unit>()
            var cancelled = false
            f.append = {
                try {
                    release.await()
                } catch (failure: CancellationException) {
                    cancelled = true
                    throw failure
                }
                VehicleObservationAppendResult.Appended(1L)
            }
            val model = f.model()
            val host = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            host.cancelAndJoin()
            release.complete(Unit)
            runCurrent()
            assertTrue(cancelled)
            assertTrue(host.isCancelled)
            assertEquals(0, f.activeWrites)
            assertEquals(0, f.saved.size)
            assertEquals(0, f.vehicle.state.subscriptionCount.value)
            assertEquals(VehicleRecordingState.Paused, model.state.value)
        }

    @Test
    fun `retry rejects eight unsafe live combinations before permitting confirmed park and UX clear`() =
        runTest {
            val f = Fixture()
            f.records.value = listOf(session())
            f.append = { throw IOException("fixture") }
            val model = f.model()
            val host = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            val blockedInputs =
                listOf(
                    parked to DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.RESTRICTED),
                    parked to DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNKNOWN),
                    parked to DrivingRestrictions(),
                    driving to unrestricted,
                    driving to DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.RESTRICTED),
                    driving to DrivingRestrictions(),
                    VehicleState() to unrestricted,
                    VehicleState() to DrivingRestrictions(),
                )
            blockedInputs.forEach { (vehicle, restrictions) ->
                f.vehicle.state.value = vehicle
                f.restrictions.restrictions.value = restrictions
                assertFalse(model.retry())
                runCurrent()
            }
            assertEquals(1, f.attempts.size)
            assertEquals(1, f.sessionReads)
            f.vehicle.state.value = parked
            f.restrictions.restrictions.value = unrestricted
            assertTrue(model.retry())
            runCurrent()
            assertEquals(2, f.attempts.size)
            host.cancelAndJoin()
        }

    @Test
    fun `repository terminal rejections are explicit failures without automatic vehicle driven retries`() =
        runTest {
            listOf(
                VehicleObservationAppendResult.MissingSession to VehicleRecordingState.Reason.SESSION_MISSING,
                VehicleObservationAppendResult.FinishedSession to VehicleRecordingState.Reason.SESSION_FINISHED,
                VehicleObservationAppendResult.InvalidTime to VehicleRecordingState.Reason.INVALID_TIME,
            ).forEach { (result, reason) ->
                val f = Fixture()
                f.records.value = listOf(session())
                f.append = { result }
                val model = f.model()
                val host = backgroundScope.launch { model.recordWhileStarted() }
                runCurrent()
                assertEquals(VehicleRecordingState.Failed(reason), model.state.value)
                f.vehicle.state.value = driving
                runCurrent()
                assertEquals(1, f.attempts.size)
                assertEquals(0, f.saved.size)
                assertEquals(0, f.vehicle.state.subscriptionCount.value)
                host.cancelAndJoin()
            }
        }

    @Test
    fun `a new foreground lifetime may retry a failed run even while detailed interaction stays locked`() =
        runTest {
            val f = Fixture()
            f.records.value = listOf(session())
            f.append = { throw IOException("fixture") }
            val model = f.model()
            val first = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            first.cancelAndJoin()
            f.vehicle.state.value = driving
            f.restrictions.restrictions.value = DrivingRestrictions(ParkingState.NOT_PARKED, UxRestrictionState.RESTRICTED)
            f.append = { VehicleObservationAppendResult.Appended(2L) }
            val second = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            assertEquals(VehicleRecordingState.Recording, model.state.value)
            assertEquals(
                driving,
                f.saved
                    .single()
                    .observation.state,
            )
            assertFalse(model.retry())
            second.cancelAndJoin()
        }

    @Test
    fun `clock failure is displayed without leaking the exception and does not append a fabricated time`() =
        runTest {
            val f = Fixture()
            f.records.value = listOf(session())
            val clock =
                object : Clock() {
                    override fun getZone(): ZoneId = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId): Clock = this

                    override fun instant(): Instant = error("private fixture detail")

                    override fun millis(): Long = error("private fixture detail")
                }
            val model = f.model(clock)
            val host = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            assertEquals(VehicleRecordingState.Failed(VehicleRecordingState.Reason.OBSERVATION), model.state.value)
            assertTrue(f.attempts.isEmpty())
            assertEquals(0, f.vehicle.state.subscriptionCount.value)
            host.cancelAndJoin()
        }

    @Test
    fun `multiple active sessions and completed read streams fail rather than choosing or pretending to monitor`() =
        runTest {
            listOf<() -> Flow<List<PracticeSession>>>(
                { flowOf(listOf(session(), session("session-b"))) },
                { flowOf(emptyList()) },
            ).forEach { source ->
                val f = Fixture()
                f.source = source
                val model = f.model()
                val host = backgroundScope.launch { model.recordWhileStarted() }
                runCurrent()
                assertEquals(VehicleRecordingState.Failed(VehicleRecordingState.Reason.SESSION_READ), model.state.value)
                assertTrue(f.attempts.isEmpty())
                host.cancelAndJoin()
            }
        }

    @Test
    fun `independent append cancellation leaves a live host failed and retryable instead of recording`() =
        runTest {
            val f = Fixture()
            f.records.value = listOf(session())
            f.append = { throw CancellationException("fixture internal append cancellation") }
            val model = f.model()
            val host = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            assertTrue(host.isActive)
            assertEquals(0, f.vehicle.state.subscriptionCount.value)
            assertEquals(VehicleRecordingState.Failed(VehicleRecordingState.Reason.OBSERVATION), model.state.value)
            f.vehicle.state.value = driving
            runCurrent()
            assertEquals(1, f.attempts.size)
            f.vehicle.state.value = parked
            f.append = { VehicleObservationAppendResult.Appended(2L) }
            assertTrue(model.retry())
            runCurrent()
            assertEquals(1, f.saved.size)
            assertEquals(VehicleRecordingState.Recording, model.state.value)
            host.cancelAndJoin()
        }

    @Test
    fun `append local timeout leaves a live host failed and retryable without an automatic restart`() =
        runTest {
            val f = Fixture()
            f.records.value = listOf(session())
            f.append = { withTimeout(1L) { awaitCancellation() } }
            val model = f.model()
            val host = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            advanceTimeBy(1L)
            runCurrent()
            assertTrue(host.isActive)
            assertEquals(0, f.vehicle.state.subscriptionCount.value)
            assertEquals(0, f.activeWrites)
            assertEquals(VehicleRecordingState.Failed(VehicleRecordingState.Reason.OBSERVATION), model.state.value)
            assertEquals(1, f.attempts.size)
            f.append = { VehicleObservationAppendResult.Appended(2L) }
            assertTrue(model.retry())
            runCurrent()
            assertEquals(1, f.saved.size)
            assertEquals(VehicleRecordingState.Recording, model.state.value)
            host.cancelAndJoin()
        }

    @Test
    fun `independent clock cancellation never leaves recording visible after the subscription has stopped`() =
        runTest {
            val f = Fixture()
            f.records.value = listOf(session())
            var failClock = true
            val clock =
                object : Clock() {
                    override fun getZone(): ZoneId = ZoneOffset.UTC

                    override fun withZone(zone: ZoneId): Clock = this

                    override fun instant(): Instant = Instant.ofEpochMilli(millis())

                    override fun millis(): Long =
                        if (failClock) throw CancellationException("fixture internal clock cancellation") else 123L
                }
            val model = f.model(clock)
            val host = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            assertTrue(host.isActive)
            assertEquals(0, f.vehicle.state.subscriptionCount.value)
            assertEquals(VehicleRecordingState.Failed(VehicleRecordingState.Reason.OBSERVATION), model.state.value)
            assertTrue(f.attempts.isEmpty())
            failClock = false
            assertTrue(model.retry())
            runCurrent()
            assertEquals(1, f.saved.size)
            assertEquals(VehicleRecordingState.Recording, model.state.value)
            host.cancelAndJoin()
        }

    @Test
    fun `independent session read cancellation cancels recording and leaves the live host retryable`() =
        runTest {
            val f = Fixture()
            val cancelRead = CompletableDeferred<Unit>()
            f.source = {
                flow {
                    emit(listOf(session()))
                    cancelRead.await()
                    throw CancellationException("fixture internal session read cancellation")
                }
            }
            val model = f.model()
            val host = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            assertEquals(1, f.saved.size)
            cancelRead.complete(Unit)
            runCurrent()
            assertTrue(host.isActive)
            assertEquals(0, f.vehicle.state.subscriptionCount.value)
            assertEquals(VehicleRecordingState.Failed(VehicleRecordingState.Reason.SESSION_READ), model.state.value)
            f.records.value = listOf(session())
            f.source = { f.records }
            assertTrue(model.retry())
            runCurrent()
            assertEquals(2, f.saved.size)
            assertEquals(VehicleRecordingState.Recording, model.state.value)
            host.cancelAndJoin()
        }

    @Test
    fun `finite active session flow stops the child recorder and reports failed monitoring without waiting forever`() =
        runTest {
            val f = Fixture()
            f.source = { flowOf(listOf(session())) }
            val model = f.model()
            val host = backgroundScope.launch { model.recordWhileStarted() }
            runCurrent()
            assertTrue(host.isActive)
            assertEquals(VehicleRecordingState.Failed(VehicleRecordingState.Reason.SESSION_READ), model.state.value)
            assertEquals(0, f.vehicle.state.subscriptionCount.value)
            assertEquals(1, f.saved.size)
            f.vehicle.state.value = driving
            runCurrent()
            assertEquals(1, f.attempts.size)
            assertEquals(1, f.sessionReads)
            f.records.value = listOf(session())
            f.source = { f.records }
            f.vehicle.state.value = parked
            assertTrue(model.retry())
            runCurrent()
            assertEquals(2, f.saved.size)
            assertEquals(VehicleRecordingState.Recording, model.state.value)
            host.cancelAndJoin()
        }

    private class Fixture {
        val vehicle = FakeVehicle()
        val restrictions = FakeRestrictions()
        val records = MutableStateFlow<List<PracticeSession>>(emptyList())
        var source: () -> Flow<List<PracticeSession>> = { records }
        var sessionReads = 0
        val attempts = mutableListOf<Attempt>()
        val saved = mutableListOf<Attempt>()
        var activeWrites = 0
        var maxActiveWrites = 0
        var append: suspend (
            Attempt,
        ) -> VehicleObservationAppendResult = { VehicleObservationAppendResult.Appended(attempts.size.toLong()) }
        private val sessions =
            object : PracticeSessionRepository {
                override fun observeSessions(): Flow<List<PracticeSession>> =
                    flow {
                        sessionReads++
                        emitAll(source())
                    }

                override suspend fun start(
                    practice: PreparedPractice,
                    startedAtEpochMillis: Long,
                    canCommit: () -> Boolean,
                ): SessionStartResult = error("not used")

                override suspend fun finish(
                    sessionId: String,
                    endedAtEpochMillis: Long,
                    canCommit: () -> Boolean,
                ): SessionFinishResult = error("not used")
            }
        private val observations =
            object : VehicleObservationRepository {
                override fun observe(sessionId: String): Flow<List<SessionVehicleObservation>> = error("not used")

                override suspend fun append(
                    sessionId: String,
                    observation: VehicleObservation,
                ): VehicleObservationAppendResult {
                    val attempt = Attempt(sessionId, observation)
                    attempts += attempt
                    activeWrites++
                    maxActiveWrites = maxOf(maxActiveWrites, activeWrites)
                    return try {
                        this@Fixture.append(attempt).also { if (it is VehicleObservationAppendResult.Appended) saved += attempt }
                    } finally {
                        activeWrites--
                    }
                }
            }

        fun model(clock: Clock = Clock.fixed(Instant.ofEpochMilli(123L), ZoneOffset.UTC)) =
            SessionRecordingViewModel(vehicle, sessions, observations, restrictions, clock)
    }

    private class FakeVehicle : VehicleGateway {
        override val state = MutableStateFlow(parked)
    }

    private class FakeRestrictions : DrivingRestrictionsProvider {
        override val restrictions = MutableStateFlow(unrestricted)
    }

    private data class Attempt(
        val sessionId: String,
        val observation: VehicleObservation,
    )

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
                100L,
                null,
            )
    }
}
