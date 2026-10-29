package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleObservationRecorderTest {
    @Test
    fun `initial and changed snapshots preserve every reading state and connection without normalization`() =
        runTest {
            val gears =
                listOf(
                    VehicleReading.Available(GearPosition.PARK),
                    VehicleReading.Unknown,
                    VehicleReading.Unsupported,
                    VehicleReading.PermissionDenied,
                    VehicleReading.Error,
                )
            val signals =
                listOf(
                    VehicleReading.Available(TurnSignalState.OFF),
                    VehicleReading.Unknown,
                    VehicleReading.Unsupported,
                    VehicleReading.PermissionDenied,
                    VehicleReading.Error,
                )
            val brakes =
                listOf(
                    VehicleReading.Available(false),
                    VehicleReading.Unknown,
                    VehicleReading.Unsupported,
                    VehicleReading.PermissionDenied,
                    VehicleReading.Error,
                )
            val expected =
                ConnectionState.entries.flatMap { connection ->
                    gears.indices.map { index ->
                        VehicleState(connection, gears[index], signals[(index + 1) % 5], brakes[(index + 2) % 5])
                    }
                }
            val gateway = FakeGateway(expected.first())
            val repository = FakeRepository()
            var clock = 100L
            val recorder = VehicleObservationRecorder(gateway, repository) { clock++ }
            val recording = backgroundScope.async { recorder.record("fixed-session") }
            runCurrent()
            expected.drop(1).forEach {
                gateway.state.value = it
                runCurrent()
            }
            assertEquals(30, repository.saved.size)
            assertEquals(expected, repository.saved.map { it.observation.state })
            assertEquals((100L..129L).toList(), repository.saved.map { it.observation.observedAtEpochMillis })
            assertTrue(repository.saved.all { it.sessionId == "fixed-session" })
            assertTrue(repository.saved.all { it.observation.source == VehicleObservationSource.SDK_STATE_OBSERVATION })
            recording.cancelAndJoin()
            assertEquals(0, gateway.state.subscriptionCount.value)
        }

    @Test
    fun `positive clock regressions retain append order rather than sorting or correcting time`() =
        runTest {
            val gateway = FakeGateway(parked)
            val repository = FakeRepository()
            val times = ArrayDeque(listOf(300L, 100L, 200L))
            val recorder = VehicleObservationRecorder(gateway, repository) { times.removeFirst() }
            val recording = backgroundScope.async { recorder.record("fixed-session") }
            runCurrent()
            gateway.state.value = driving
            runCurrent()
            val braking = driving.copy(brakePressed = VehicleReading.Available(true))
            gateway.state.value = braking
            runCurrent()
            assertEquals(listOf(300L, 100L, 200L), repository.saved.map { it.observation.observedAtEpochMillis })
            assertEquals(listOf(parked, driving, braking), repository.saved.map { it.observation.state })
            recording.cancelAndJoin()
        }

    @Test
    fun `an equal state does not add an observation or sample the clock again`() =
        runTest {
            val gateway = FakeGateway(parked)
            val repository = FakeRepository()
            var clockReads = 0
            val recorder = VehicleObservationRecorder(gateway, repository) { ++clockReads * 100L }
            val recording = backgroundScope.async { recorder.record("fixed-session") }
            runCurrent()
            repeat(3) {
                gateway.state.value = parked.copy()
                runCurrent()
            }
            assertEquals(1, repository.saved.size)
            assertEquals(1, clockReads)
            assertEquals(1, gateway.state.subscriptionCount.value)
            recording.cancelAndJoin()
        }

    @Test
    fun `slow append finishes before latest conflated state and intermediate states are not promised`() =
        runTest {
            val gateway = FakeGateway(parked)
            val repository = FakeRepository()
            val releaseFirstWrite = CompletableDeferred<Unit>()
            var firstWriteFinished = false
            var firstWriteCancelled = false
            repository.respond = {
                if (repository.attempts.size == 1) {
                    try {
                        releaseFirstWrite.await()
                        firstWriteFinished = true
                    } catch (cancelled: CancellationException) {
                        firstWriteCancelled = true
                        throw cancelled
                    }
                }
                VehicleObservationAppendResult.Appended(repository.attempts.size.toLong())
            }
            val recorder = VehicleObservationRecorder(gateway, repository) { 100L }
            val recording = backgroundScope.async { recorder.record("fixed-session") }
            runCurrent()
            gateway.state.value = driving
            runCurrent()
            gateway.state.value = driving.copy(turnSignal = VehicleReading.Available(TurnSignalState.LEFT))
            runCurrent()
            val latest = driving.copy(brakePressed = VehicleReading.Available(true))
            gateway.state.value = latest
            runCurrent()
            assertEquals(1, repository.attempts.size)
            assertFalse(firstWriteFinished)
            assertFalse(firstWriteCancelled)
            releaseFirstWrite.complete(Unit)
            runCurrent()
            assertTrue(firstWriteFinished)
            assertFalse(firstWriteCancelled)
            assertEquals(listOf(parked, latest), repository.saved.map { it.observation.state })
            recording.cancelAndJoin()
        }

    @Test
    fun `missing session stops collection and reports only successful appends`() =
        assertRepositoryStop(VehicleObservationAppendResult.MissingSession, VehicleObservationRecordingResult.MissingSession(1L))

    @Test
    fun `finished session stops collection and reports only successful appends`() =
        assertRepositoryStop(VehicleObservationAppendResult.FinishedSession, VehicleObservationRecordingResult.FinishedSession(1L))

    @Test
    fun `invalid time rejection stops collection and reports only successful appends`() =
        assertRepositoryStop(VehicleObservationAppendResult.InvalidTime, VehicleObservationRecordingResult.InvalidTime(1L))

    @Test
    fun `nonpositive clocks reach the repository unchanged and rejection never counts as a saved observation`() =
        runTest {
            listOf(0L, -1L).forEach { invalidTime ->
                val gateway = FakeGateway(parked)
                val repository = FakeRepository()
                repository.respond = {
                    if (it.observation.observedAtEpochMillis <=
                        0L
                    ) {
                        VehicleObservationAppendResult.InvalidTime
                    } else {
                        VehicleObservationAppendResult.Appended(repository.attempts.size.toLong())
                    }
                }
                val times = ArrayDeque(listOf(100L, invalidTime))
                val recorder = VehicleObservationRecorder(gateway, repository) { times.removeFirst() }
                val recording = backgroundScope.async { recorder.record("fixed-session") }
                runCurrent()
                gateway.state.value = driving
                runCurrent()
                assertEquals(VehicleObservationRecordingResult.InvalidTime(1L), recording.await())
                assertEquals(
                    invalidTime,
                    repository.attempts
                        .last()
                        .observation.observedAtEpochMillis,
                )
                assertEquals(1, repository.saved.size)
                assertEquals(0, gateway.state.subscriptionCount.value)
            }
        }

    @Test
    fun `write failure preserves its cause and earlier count then stops instead of retrying future states`() =
        runTest {
            val gateway = FakeGateway(parked)
            val repository = FakeRepository()
            val failure = IOException("fixture write failed")
            repository.respond = {
                if (repository.attempts.size == 2) throw failure
                VehicleObservationAppendResult.Appended(repository.attempts.size.toLong())
            }
            val recorder = VehicleObservationRecorder(gateway, repository) { 100L }
            val recording = backgroundScope.async { recorder.record("fixed-session") }
            runCurrent()
            gateway.state.value = driving
            runCurrent()
            val result = recording.await() as VehicleObservationRecordingResult.WriteFailed
            assertEquals(1L, result.appendedCount)
            assertSame(failure, result.cause)
            assertEquals(1, repository.saved.size)
            assertEquals(0, gateway.state.subscriptionCount.value)
            gateway.state.value = parked
            runCurrent()
            assertEquals(2, repository.attempts.size)
        }

    @Test
    fun `repository cancellation propagates unchanged and releases the recorder for a new caller`() =
        runTest {
            val gateway = FakeGateway(parked)
            val repository = FakeRepository()
            val cancellation = CancellationException("fixture cancellation")
            repository.respond = { throw cancellation }
            val recorder = VehicleObservationRecorder(gateway, repository) { 100L }
            val first = backgroundScope.async { runCatching { recorder.record("first-session") } }
            runCurrent()
            assertSame(cancellation, first.await().exceptionOrNull())
            assertEquals(0, repository.saved.size)
            assertEquals(0, gateway.state.subscriptionCount.value)
            repository.respond = { VehicleObservationAppendResult.MissingSession }
            assertEquals(VehicleObservationRecordingResult.MissingSession(0L), recorder.record("new-session"))
            assertEquals(listOf("first-session", "new-session"), repository.attempts.map { it.sessionId })
        }

    @Test
    fun `caller cancellation cancels a pending write without detached work and permits restart`() =
        runTest {
            val gateway = FakeGateway(parked)
            val repository = FakeRepository()
            val releaseWrite = CompletableDeferred<Unit>()
            var writeCancelled = false
            repository.respond = {
                try {
                    releaseWrite.await()
                } catch (cancelled: CancellationException) {
                    writeCancelled = true
                    throw cancelled
                }
                VehicleObservationAppendResult.Appended(1L)
            }
            val recorder = VehicleObservationRecorder(gateway, repository) { 100L }
            val first = backgroundScope.async { recorder.record("first-session") }
            runCurrent()
            first.cancelAndJoin()
            releaseWrite.complete(Unit)
            gateway.state.value = driving
            runCurrent()
            assertTrue(writeCancelled)
            assertEquals(1, repository.attempts.size)
            assertEquals(0, repository.saved.size)
            assertEquals(0, gateway.state.subscriptionCount.value)
            repository.respond = { VehicleObservationAppendResult.FinishedSession }
            assertEquals(VehicleObservationRecordingResult.FinishedSession(0L), recorder.record("new-session"))
            assertEquals(listOf("first-session", "new-session"), repository.attempts.map { it.sessionId })
        }

    @Test
    fun `clock failure propagates unchanged without a write and releases the recorder`() =
        runTest {
            val gateway = FakeGateway(parked)
            val repository = FakeRepository()
            val failure = IllegalStateException("fixture clock unavailable")
            var failClock = false
            val recorder = VehicleObservationRecorder(gateway, repository) { if (failClock) throw failure else 100L }
            val first = backgroundScope.async { runCatching { recorder.record("first-session") } }
            runCurrent()
            failClock = true
            gateway.state.value = driving
            runCurrent()
            assertSame(failure, first.await().exceptionOrNull())
            assertEquals(1, repository.attempts.size)
            assertEquals(1, repository.saved.size)
            assertEquals(0, gateway.state.subscriptionCount.value)
            failClock = false
            repository.respond = { VehicleObservationAppendResult.MissingSession }
            assertEquals(VehicleObservationRecordingResult.MissingSession(0L), recorder.record("new-session"))
            assertEquals(listOf("first-session", "new-session"), repository.attempts.map { it.sessionId })
        }

    @Test
    fun `concurrent caller is refused without another subscription clock read or write and can retry after terminal result`() =
        runTest {
            val gateway = FakeGateway(parked)
            val repository = FakeRepository()
            val releaseSecondWrite = CompletableDeferred<Unit>()
            repository.respond = {
                if (repository.attempts.size == 1) {
                    VehicleObservationAppendResult.Appended(1L)
                } else {
                    releaseSecondWrite.await()
                    VehicleObservationAppendResult.FinishedSession
                }
            }
            var clockReads = 0
            val recorder = VehicleObservationRecorder(gateway, repository) { ++clockReads * 100L }
            val first = backgroundScope.async { recorder.record("first-session") }
            runCurrent()
            gateway.state.value = driving
            runCurrent()
            assertEquals(1, repository.saved.size)
            val refused = recorder.record("other-session")
            assertSame(VehicleObservationRecordingResult.AlreadyRunning, refused)
            assertEquals(0L, refused.appendedCount)
            assertEquals(1, gateway.state.subscriptionCount.value)
            assertEquals(2, clockReads)
            assertEquals(2, repository.attempts.size)
            assertTrue(repository.attempts.all { it.sessionId == "first-session" })
            releaseSecondWrite.complete(Unit)
            runCurrent()
            assertEquals(VehicleObservationRecordingResult.FinishedSession(1L), first.await())
            assertEquals(0, gateway.state.subscriptionCount.value)
            repository.respond = { VehicleObservationAppendResult.MissingSession }
            assertEquals(VehicleObservationRecordingResult.MissingSession(0L), recorder.record("other-session"))
            assertEquals("other-session", repository.attempts.last().sessionId)
        }

    private fun assertRepositoryStop(
        rejection: VehicleObservationAppendResult,
        expected: VehicleObservationRecordingResult,
    ) = runTest {
        val gateway = FakeGateway(parked)
        val repository = FakeRepository()
        repository.respond = {
            if (repository.attempts.size == 1) VehicleObservationAppendResult.Appended(1L) else rejection
        }
        val recorder = VehicleObservationRecorder(gateway, repository) { 100L }
        val recording = backgroundScope.async { recorder.record("fixed-session") }
        runCurrent()
        gateway.state.value = driving
        runCurrent()
        assertEquals(expected, recording.await())
        assertEquals(1, repository.saved.size)
        assertEquals(0, gateway.state.subscriptionCount.value)
        gateway.state.value = parked
        runCurrent()
        assertEquals(2, repository.attempts.size)
        assertTrue(repository.attempts.all { it.sessionId == "fixed-session" })
    }

    private class FakeGateway(
        initialState: VehicleState,
    ) : VehicleGateway {
        override val state = MutableStateFlow(initialState)
    }

    private class FakeRepository : VehicleObservationRepository {
        val attempts = mutableListOf<AppendCall>()
        val saved = mutableListOf<AppendCall>()
        var respond: suspend (AppendCall) -> VehicleObservationAppendResult = {
            VehicleObservationAppendResult.Appended(attempts.size.toLong())
        }

        override fun observe(sessionId: String): Flow<List<SessionVehicleObservation>> = flowOf(emptyList())

        override suspend fun append(
            sessionId: String,
            observation: VehicleObservation,
        ): VehicleObservationAppendResult {
            val call = AppendCall(sessionId, observation)
            attempts += call
            return respond(call).also { if (it is VehicleObservationAppendResult.Appended) saved += call }
        }
    }

    private data class AppendCall(
        val sessionId: String,
        val observation: VehicleObservation,
    )

    private val parked = VehicleState(connection = ConnectionState.CONNECTED, gear = VehicleReading.Available(GearPosition.PARK))
    private val driving = parked.copy(gear = VehicleReading.Available(GearPosition.DRIVE))
}
