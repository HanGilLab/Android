package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.LocationRecordingAppendResult
import io.github.hangillab.hangil.core.domain.LocationRecordingBeginResult
import io.github.hangillab.hangil.core.domain.LocationRecordingEndResult
import io.github.hangillab.hangil.core.domain.LocationRecordingEvent
import io.github.hangillab.hangil.core.domain.LocationRecordingRepository
import io.github.hangillab.hangil.core.domain.LocationRecordingRun
import io.github.hangillab.hangil.core.domain.LocationRecordingRunStatus
import io.github.hangillab.hangil.core.domain.LocationRecordingSnapshot
import io.github.hangillab.hangil.core.domain.LocationRecordingStopReason
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.feature.home.LocationHistoryUiState.FailureReason
import io.github.hangillab.hangil.feature.home.LocationHistoryUiState.Token
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Real read-only VM with controlled sources/policy/storage. No SDK, permission request or database write. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationHistoryViewModelTest {
    @Test fun `uncollected route does not query or write`() =
        runTest {
            val f = Fixture()
            f.model.observe(source(), f.host::get)
            runCurrent()
            assertEquals(0, f.sessions.calls)
            assertEquals(0, f.recordings.calls)
            assertEquals(0, f.recordings.writes)
            assertFalse(f.model.canRead(Token(0, 0, 0)))
        }

    @Test fun `missing and active sources are unavailable rather than empty`() =
        runTest {
            val missing = Fixture().apply { sessions.values.value = emptyList() }
            val m = open(missing)
            assertTrue(m.value is LocationHistoryUiState.Unavailable)
            assertEquals(0, missing.recordings.calls)
            val active = Fixture()
            val a = open(active, source().copy(endedAtEpochMillis = null))
            assertTrue(a.value is LocationHistoryUiState.Unavailable)
            assertEquals(0, active.recordings.calls)
        }

    @Test fun `unknown UX and D fail closed before reading stored coordinates`() =
        runTest {
            val driving = Fixture().apply { vehicle.value = vehicleState(GearPosition.DRIVE) }
            assertTrue(open(driving).value is LocationHistoryUiState.Locked)
            assertEquals(0, driving.recordings.calls)
            val unknown = Fixture().apply { restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNKNOWN) }
            assertTrue(open(unknown).value is LocationHistoryUiState.Locked)
            assertEquals(0, unknown.recordings.calls)
        }

    @Test fun `actual empty snapshot and a run with no events remain different`() =
        runTest {
            val empty = Fixture().apply { recordings.values.value = LocationRecordingSnapshot(emptyList(), emptyList()) }
            assertTrue(open(empty).value is LocationHistoryUiState.Empty)
            val noEvents = Fixture().apply { recordings.values.value = snapshot(events = emptyList()) }
            val ready = open(noEvents).ready()
            assertEquals(null, ready.event)
            assertEquals(1, ready.snapshot.runs.size)
            assertEquals(0, noEvents.recordings.writes)
        }

    @Test fun `latest creation and append order do not depend on raw wall clocks`() =
        runTest {
            val f = Fixture()
            val first = run("first", wall = 2_000)
            val last = run("last", wall = 500)
            f.recordings.values.value = LocationRecordingSnapshot(listOf(first, last), listOf(event(1, first.runId), event(2, last.runId)))
            val ready = open(f).ready()
            assertEquals("last", ready.run.runId)
            assertEquals(2L, ready.event?.id)
            assertEquals(0, f.recordings.writes)
        }

    @Test fun `old run and event clicks cannot borrow a newer selection receipt`() =
        runTest {
            val f = Fixture()
            f.recordings.values.value = snapshot(events = listOf(event(1), event(2, ordinal = 2, elapsed = 102)))
            val display = open(f)
            val old = display.ready()
            assertTrue(f.model.previousEvent(old.token, old.run.runId, old.event?.id))
            runCurrent()
            val current = display.ready()
            assertEquals(1L, current.event?.id)
            assertNotEquals(old.token, current.token)
            assertFalse(f.model.nextEvent(old.token, old.run.runId, old.event?.id))
            assertFalse(f.model.nextEvent(current.token, current.run.runId, old.event?.id))
            assertTrue(f.model.nextEvent(current.token, current.run.runId, current.event?.id))
        }

    @Test fun `selecting another run retires the old token and chooses only its events`() =
        runTest {
            val f = Fixture()
            val first = run("first")
            val second = run("second")
            f.recordings.values.value =
                LocationRecordingSnapshot(listOf(first, second), listOf(event(1, first.runId), event(2, second.runId)))
            val display = open(f)
            val old = display.ready()
            assertTrue(f.model.previousRun(old.token, old.run.runId))
            runCurrent()
            val current = display.ready()
            assertEquals("first", current.run.runId)
            assertEquals(1L, current.event?.id)
            assertFalse(f.model.nextRun(old.token, old.run.runId))
            assertFalse(f.model.nextRun(current.token, old.run.runId))
        }

    @Test fun `silent host denial permanently retires old clicks across host return`() =
        runTest {
            val f = Fixture()
            val display = open(f)
            val old = display.ready()
            f.host.set(false)
            assertFalse(f.model.canRead(old.token))
            runCurrent()
            assertTrue(display.value is LocationHistoryUiState.Unavailable)
            f.host.set(true)
            assertFalse(f.model.canRead(old.token))
            assertFalse(f.model.previousRun(old.token, old.run.runId))
            assertEquals(1, f.recordings.calls)
        }

    @Test fun `observed D then P never revives a retired reader`() =
        runTest {
            val f = Fixture()
            val display = open(f)
            val old = display.ready()
            f.vehicle.value = vehicleState(GearPosition.DRIVE)
            runCurrent()
            assertTrue(display.value is LocationHistoryUiState.Locked)
            f.vehicle.value = vehicleState(GearPosition.PARK)
            runCurrent()
            assertFalse(f.model.canRead(old.token))
            assertTrue(display.value is LocationHistoryUiState.Locked)
            assertEquals(1, f.recordings.calls)
        }

    @Test fun `same ID with changed full source cannot reveal an old snapshot`() =
        runTest {
            val f = Fixture()
            val display = open(f)
            val old = display.ready()
            f.sessions.values.value = listOf(source().copy(taskTitle = "Changed task"))
            runCurrent()
            assertTrue(display.value is LocationHistoryUiState.Unavailable)
            assertFalse(f.model.canRead(old.token))
            f.sessions.values.value = listOf(source())
            runCurrent()
            assertTrue(display.value is LocationHistoryUiState.Unavailable)
            assertEquals(1, f.recordings.calls)
        }

    @Test fun `recording errors and finite completion never look like empty data`() =
        runTest {
            val failed = Fixture().apply { recordings.factory = { flow { throw IOException("controlled read") } } }
            assertEquals(FailureReason.RECORDING_READ, (open(failed).value as LocationHistoryUiState.Failed).reason)
            val finite = Fixture().apply { recordings.factory = { flowOf(snapshot()) } }
            assertEquals(FailureReason.RECORDING_STREAM_COMPLETED, (open(finite).value as LocationHistoryUiState.Failed).reason)
        }

    @Test fun `session lookup errors cannot query location data`() =
        runTest {
            val f = Fixture().apply { sessions.factory = { flow { throw IOException("controlled lookup") } } }
            assertEquals(FailureReason.SOURCE_READ, (open(f).value as LocationHistoryUiState.Failed).reason)
            assertEquals(0, f.recordings.calls)
        }

    @Test fun `a failed read retries only explicitly with a new query receipt`() =
        runTest {
            val f = Fixture().apply { recordings.factory = { flow { throw IOException("controlled read") } } }
            val display = open(f)
            val old = display.value as LocationHistoryUiState.Failed
            f.recordings.factory = { f.recordings.values }
            runCurrent()
            assertEquals(1, f.recordings.calls)
            assertTrue(f.model.retry(old.token))
            runCurrent()
            val current = display.ready()
            assertNotEquals(old.token, current.token)
            assertEquals(2, f.recordings.calls)
            assertFalse(f.model.retry(old.token))
        }

    @Test fun `foreign source duplicate identities and event gaps fail validation`() =
        runTest {
            val cases =
                listOf(
                    snapshot().copy(runs = listOf(run().copy(source = source().copy(endedAtEpochMillis = null, courseTitle = "Foreign")))),
                    snapshot().copy(runs = listOf(run(), run())),
                    snapshot(events = listOf(event(1, ordinal = 2))),
                    snapshot(events = listOf(event(1).copy(sessionId = "foreign"))),
                    snapshot(events = listOf(event(1, elapsed = 50))),
                )
            for (invalid in cases) {
                val f = Fixture().apply { recordings.values.value = invalid }
                assertEquals(FailureReason.INVALID_SNAPSHOT, (open(f).value as LocationHistoryUiState.Failed).reason)
            }
        }

    @Test fun `stored row removal is regression rather than a fresh empty success`() =
        runTest {
            val f = Fixture()
            val display = open(f)
            val old = display.ready()
            f.recordings.values.value = snapshot(events = emptyList())
            runCurrent()
            assertEquals(FailureReason.SNAPSHOT_REGRESSED, (display.value as LocationHistoryUiState.Failed).reason)
            assertFalse(f.model.canRead(old.token))
            f.recordings.values.value = snapshot()
            runCurrent()
            assertTrue(display.value is LocationHistoryUiState.Failed)
            assertEquals(1, f.recordings.calls)
        }

    @Test fun `an old interruption persists when a newer stopped run is selected`() =
        runTest {
            val f = Fixture()
            val old =
                run(
                    "old",
                ).copy(
                    status = LocationRecordingRunStatus.INTERRUPTED,
                    stopReason = LocationRecordingStopReason.PROCESS_INTERRUPTED,
                    endedAtEpochMillis = null,
                    endedAtElapsedRealtimeMillis = null,
                )
            val latest = run("latest")
            f.recordings.values.value = LocationRecordingSnapshot(listOf(old, latest), listOf(event(1, latest.runId)))
            val ready = open(f).ready()
            assertEquals("latest", ready.run.runId)
            assertTrue(ready.hasInterrupted)
        }

    @Test fun `query job self cancellation hides the last receipt until explicit retry`() =
        runTest {
            val f =
                Fixture().apply {
                    recordings.factory =
                        {
                            flow {
                                emit(snapshot())
                                currentCoroutineContext().cancel()
                                awaitCancellation()
                            }
                        }
                }
            val display = open(f)
            assertTrue(display.value is LocationHistoryUiState.Failed)
            assertEquals(1, f.recordings.calls)
            assertEquals(0, f.recordings.writes)
        }

    @Test fun `collector cancellation releases its queries and a new route gets a different token`() =
        runTest {
            val f = Fixture()
            val first = open(f)
            val old = first.ready()
            first.job.cancelAndJoin()
            runCurrent()
            assertEquals(0, f.recordings.active)
            assertFalse(f.model.canRead(old.token))
            val current = open(f).ready()
            assertNotEquals(old.token.readerId, current.token.readerId)
            assertEquals(2, f.recordings.calls)
        }

    @Test fun `identical storage receipts preserve the displayed token and selection`() =
        runTest {
            val receipts = MutableSharedFlow<LocationRecordingSnapshot>(replay = 1)
            receipts.tryEmit(snapshot())
            val f = Fixture().apply { recordings.factory = { receipts } }
            val display = open(f)
            val old = display.ready()
            receipts.emit(snapshot())
            runCurrent()
            assertEquals(old, display.ready())
            assertTrue(f.model.canRead(old.token))
            assertEquals(1, f.recordings.calls)
        }

    @Test fun `cold unknown gate subscribes without querying until its first fresh parked receipt`() =
        runTest {
            val f = Fixture()
            f.vehicle.value = VehicleState()
            f.restrictions.value = DrivingRestrictions()
            val display = open(f)
            assertTrue(display.value is LocationHistoryUiState.Locked)
            assertEquals(0, f.sessions.calls)
            assertEquals(0, f.recordings.calls)
            f.vehicle.value = vehicleState(GearPosition.PARK)
            f.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
            runCurrent()
            assertTrue(display.value is LocationHistoryUiState.Ready)
            assertEquals(1, f.sessions.calls)
            assertEquals(1, f.recordings.calls)
        }

    @Test fun `initial known drive cannot borrow a later parked receipt`() =
        runTest {
            val f = Fixture().apply { vehicle.value = vehicleState(GearPosition.DRIVE) }
            val display = open(f)
            assertTrue(display.value is LocationHistoryUiState.Locked)
            f.vehicle.value = vehicleState(GearPosition.PARK)
            runCurrent()
            assertTrue(display.value is LocationHistoryUiState.Locked)
            assertEquals(0, f.recordings.calls)
        }

    @Test fun `a previously terminal run cannot acquire later events without a failure`() =
        runTest {
            val f = Fixture()
            val display = open(f)
            display.ready()
            f.recordings.values.value = snapshot(events = listOf(event(1), event(2, ordinal = 2, elapsed = 102)))
            runCurrent()
            assertEquals(FailureReason.SNAPSHOT_REGRESSED, (display.value as LocationHistoryUiState.Failed).reason)
        }

    private fun TestScope.open(
        f: Fixture,
        expected: PracticeSession = source(),
    ): Display {
        val states = MutableStateFlow<LocationHistoryUiState>(LocationHistoryUiState.Loading(expected, Token(0, 0, 0)))
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model.observe(expected, f.host::get).collect {
                    states.value =
                        it
                }
            }
        runCurrent()
        return Display(states, job)
    }

    private class Display(
        val states: MutableStateFlow<LocationHistoryUiState>,
        val job: Job,
    ) {
        val value get() = states.value

        fun ready() = value as LocationHistoryUiState.Ready
    }

    private class Fixture {
        val sessions = Sessions()
        val recordings = Recordings()
        val host = AtomicBoolean(true)
        val vehicle = MutableStateFlow(vehicleState(GearPosition.PARK))
        val restrictions = MutableStateFlow(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))
        val model =
            LocationHistoryViewModel(
                sessions,
                recordings,
                object : VehicleGateway {
                    override val state = vehicle
                },
                object : DrivingRestrictionsProvider {
                    override val restrictions = this@Fixture.restrictions
                },
            )
    }

    private class Sessions : PracticeSessionRepository {
        val values = MutableStateFlow(listOf(source()))
        var factory: () -> Flow<List<PracticeSession>> = { values }
        var calls = 0

        override fun observeSessions(): Flow<List<PracticeSession>> =
            flow {
                calls++
                emitAll(factory())
            }

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

    private class Recordings : LocationRecordingRepository {
        val values = MutableStateFlow(snapshot())
        var factory: () -> Flow<LocationRecordingSnapshot> = { values }
        var calls = 0
        var active = 0
        var writes = 0

        override fun observe(sessionId: String): Flow<LocationRecordingSnapshot> =
            flow {
                calls++
                active++
                try {
                    emitAll(factory())
                } finally {
                    active--
                }
            }

        override suspend fun begin(
            expectedSource: PracticeSession,
            runId: String,
            processId: String,
            startedAtEpochMillis: Long,
            startedAtElapsedRealtimeMillis: Long,
            canBegin: () -> Boolean,
        ): LocationRecordingBeginResult {
            writes++
            error("Unexpected location write")
        }

        override suspend fun append(
            expectedSource: PracticeSession,
            expectedRun: LocationRecordingRun,
            reading: LocationUpdateReading,
            observedAtEpochMillis: Long,
            canAppend: () -> Boolean,
        ): LocationRecordingAppendResult {
            writes++
            error("Unexpected location write")
        }

        override suspend fun end(
            expectedRun: LocationRecordingRun,
            status: LocationRecordingRunStatus,
            stopReason: LocationRecordingStopReason,
            endedAtEpochMillis: Long?,
            endedAtElapsedRealtimeMillis: Long?,
        ): LocationRecordingEndResult {
            writes++
            error("Unexpected location write")
        }

        override suspend fun recoverInterrupted(currentProcessId: String) {
            writes++
            error("Unexpected location recovery")
        }
    }

    companion object {
        private fun source() =
            PracticeSession(
                "source",
                PracticeSelection("goal", "course", 1, 1, "task"),
                "Goal",
                "Course",
                "Task",
                CourseReviewStatus.DEMO,
                1_000,
                3_000,
            )

        private fun run(
            id: String = "run",
            wall: Long = 2_000,
        ) = LocationRecordingRun(
            id,
            "process",
            source().copy(endedAtEpochMillis = null),
            wall,
            100,
            2_500,
            200,
            LocationRecordingRunStatus.STOPPED,
            LocationRecordingStopReason.USER_STOP,
        )

        private fun event(
            id: Long,
            runId: String = "run",
            ordinal: Int = 1,
            elapsed: Long = 101,
        ) = LocationRecordingEvent(
            id,
            "source",
            runId,
            ordinal,
            2_100,
            LocationUpdateReading.Sample(LocationUpdateSample(12.5, 34.5, -2.0, 421f, -5f, -42, elapsed)),
        )

        private fun snapshot(events: List<LocationRecordingEvent> = listOf(event(1))) = LocationRecordingSnapshot(listOf(run()), events)

        private fun vehicleState(gear: GearPosition) = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(gear))
    }
}
