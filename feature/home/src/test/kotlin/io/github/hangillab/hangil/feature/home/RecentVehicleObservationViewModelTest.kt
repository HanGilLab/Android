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
import io.github.hangillab.hangil.core.domain.TurnSignalState
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import io.github.hangillab.hangil.core.domain.VehicleObservationRepository
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RecentVehicleObservationViewModelTest {
    @Test fun `constructing a detail flow and accepting retry do not subscribe until collected`() =
        runTest {
            val fixture = Fixture()
            fixture.viewModel.observe("session-a")
            assertTrue(fixture.viewModel.retry())
            runCurrent()
            assertEquals(0, fixture.sessions.factoryCalls)
            assertTrue(fixture.observations.requests.isEmpty())
            assertEquals(0, fixture.vehicle.state.subscriptionCount.value)
            assertEquals(0, fixture.restrictions.restrictions.subscriptionCount.value)
        }

    @Test fun `unconfirmed denied disconnected and stale parked restrictions never open database queries`() =
        runTest {
            val denied =
                listOf(
                    parkedVehicle().copy(gear = VehicleReading.Available(GearPosition.DRIVE)) to unrestricted(),
                    parkedVehicle().copy(connection = ConnectionState.DISCONNECTED) to unrestricted(),
                    parkedVehicle().copy(connection = ConnectionState.PERMISSION_DENIED) to unrestricted(),
                    parkedVehicle().copy(gear = VehicleReading.Unknown) to unrestricted(),
                    parkedVehicle().copy(gear = VehicleReading.Unsupported) to unrestricted(),
                    parkedVehicle() to unrestricted().copy(uxRestrictionState = UxRestrictionState.RESTRICTED),
                    parkedVehicle() to unrestricted().copy(uxRestrictionState = UxRestrictionState.UNKNOWN),
                )
            for ((vehicle, restrictions) in denied) {
                val fixture = Fixture()
                fixture.vehicle.state.value = vehicle
                fixture.restrictions.restrictions.value = restrictions
                val trace = watch(fixture)
                runCurrent()
                assertEquals(RecentVehicleObservationUiState.Locked("session-a"), trace.last())
                assertFalse(fixture.viewModel.canRead())
                assertFalse(fixture.viewModel.retry())
                assertEquals(0, fixture.sessions.factoryCalls)
                assertTrue(fixture.observations.requests.isEmpty())
                trace.job.cancelAndJoin()
            }
        }

    @Test fun `saved readings and all connection states remain snapshots without normalizing unavailable values`() =
        runTest {
            val savedStates =
                ConnectionState.entries.flatMap { connection ->
                    listOf(
                        VehicleState(connection, VehicleReading.Unknown, VehicleReading.Unknown, VehicleReading.Unknown),
                        VehicleState(connection, VehicleReading.Unsupported, VehicleReading.Unsupported, VehicleReading.Unsupported),
                        VehicleState(
                            connection,
                            VehicleReading.PermissionDenied,
                            VehicleReading.PermissionDenied,
                            VehicleReading.PermissionDenied,
                        ),
                        VehicleState(connection, VehicleReading.Error, VehicleReading.Error, VehicleReading.Error),
                        VehicleState(
                            connection,
                            VehicleReading.Available(GearPosition.DRIVE),
                            VehicleReading.Available(TurnSignalState.OFF),
                            VehicleReading.Available(false),
                        ),
                    )
                }
            val expected = savedStates.mapIndexed { index, state -> row(index + 1L, state = state) }
            val fixture = Fixture(expected)
            val trace = watch(fixture)
            runCurrent()
            assertEquals(expected, trace.ready().observations)
            assertEquals(0, fixture.observations.appendCalls)
        }

    @Test fun `positive clock regressions and repeated observation times preserve stored ID order`() =
        runTest {
            val expected = listOf(row(1, time = 300), row(2, time = 100), row(3, time = 100), row(4, time = 200))
            val fixture = Fixture(expected)
            val trace = watch(fixture)
            runCurrent()
            assertEquals(expected, trace.ready().observations)
        }

    @Test fun `known empty observations differ from a missing or active requested session`() =
        runTest {
            val fixture = Fixture(emptyList())
            val trace = watch(fixture)
            runCurrent()
            assertEquals(RecentVehicleObservationUiState.Empty("session-a"), trace.last())
            fixture.sessions.values.value = listOf(session("session-a", ended = null))
            runCurrent()
            assertEquals(RecentVehicleObservationUiState.SessionChanged("session-a"), trace.last())
            assertEquals(0, fixture.observations.active)
            fixture.sessions.values.value = emptyList()
            runCurrent()
            assertEquals(RecentVehicleObservationUiState.SessionChanged("session-a"), trace.last())
            assertEquals(listOf("session-a"), fixture.observations.requests)
        }

    @Test fun `latest finished session follows repository order rather than wall time and skips an active session`() =
        runTest {
            val fixture = Fixture()
            fixture.sessions.values.value =
                listOf(
                    session("active", ended = null),
                    session("session-a", started = 100, ended = 200),
                    session("session-b", started = 4_000, ended = 5_000),
                )
            val trace = watch(fixture)
            runCurrent()
            assertEquals("session-a", trace.ready().sessionId)
            assertEquals(listOf("session-a"), fixture.observations.requests)
        }

    @Test fun `a newer finished session cancels the previous query and rejects its late result`() =
        runTest {
            val fixture = Fixture()
            val delayed = CompletableDeferred<List<SessionVehicleObservation>>()
            fixture.observations.source = { flow { emit(delayed.await()) } }
            val trace = watch(fixture)
            runCurrent()
            assertEquals(1, fixture.observations.active)
            fixture.sessions.values.value = listOf(session("session-b"), session("session-a"))
            runCurrent()
            assertEquals(RecentVehicleObservationUiState.SessionChanged("session-a"), trace.last())
            assertEquals(0, fixture.observations.active)
            delayed.complete(listOf(row(1)))
            runCurrent()
            assertFalse(trace.states.any { it is RecentVehicleObservationUiState.Ready })
            assertEquals(RecentVehicleObservationUiState.SessionChanged("session-a"), trace.last())
        }

    @Test fun `each requested session keeps its own identity while only the actual latest session is queried`() =
        runTest {
            val fixture = Fixture()
            fixture.sessions.values.value = listOf(session("session-b"), session("session-a"))
            val expected = listOf(row(8, sessionId = "session-b"))
            fixture.observations.values("session-b").value = expected
            val previous = watch(fixture)
            val current = watch(fixture, "session-b")
            runCurrent()
            assertEquals(RecentVehicleObservationUiState.SessionChanged("session-a"), previous.last())
            assertTrue(previous.states.all { it.sessionId == "session-a" })
            assertTrue(current.states.all { it.sessionId == "session-b" })
            assertEquals(expected, current.ready().observations)
            assertEquals(listOf("session-b"), fixture.observations.requests)
            assertEquals(1, fixture.observations.maximumActive)
        }

    @Test fun `locking cancels both database subscriptions blocks retry and reopens only after both conditions recover`() =
        runTest {
            val fixture = Fixture()
            val trace = watch(fixture)
            runCurrent()
            assertTrue(trace.last() is RecentVehicleObservationUiState.Ready)
            fixture.vehicle.state.value = parkedVehicle().copy(gear = VehicleReading.Available(GearPosition.DRIVE))
            runCurrent()
            assertEquals(RecentVehicleObservationUiState.Locked("session-a"), trace.last())
            assertEquals(0, fixture.sessions.active)
            assertEquals(0, fixture.observations.active)
            assertFalse(fixture.viewModel.retry())
            fixture.restrictions.restrictions.value = unrestricted().copy(uxRestrictionState = UxRestrictionState.RESTRICTED)
            fixture.vehicle.state.value = parkedVehicle()
            runCurrent()
            assertEquals(RecentVehicleObservationUiState.Locked("session-a"), trace.last())
            assertEquals(1, fixture.sessions.factoryCalls)
            fixture.restrictions.restrictions.value = unrestricted()
            runCurrent()
            assertTrue(trace.last() is RecentVehicleObservationUiState.Ready)
            assertEquals(2, fixture.sessions.factoryCalls)
            assertEquals(1, fixture.observations.maximumActive)
        }

    @Test fun `unchanged latest ID does not restart observation queries when other session data changes`() =
        runTest {
            val fixture = Fixture()
            val trace = watch(fixture)
            runCurrent()
            fixture.sessions.values.value = listOf(session("session-a"), session("older"))
            runCurrent()
            fixture.sessions.values.value = listOf(session("active", ended = null), session("session-a"), session("older"))
            runCurrent()
            assertTrue(trace.last() is RecentVehicleObservationUiState.Ready)
            assertEquals(listOf("session-a"), fixture.observations.requests)
            assertEquals(1, fixture.observations.active)
        }

    @Test fun `explicit retry cancels the previous attempt before opening one replacement query`() =
        runTest {
            val fixture = Fixture()
            val trace = watch(fixture)
            runCurrent()
            repeat(3) {
                assertTrue(fixture.viewModel.retry())
                runCurrent()
                assertTrue(trace.last() is RecentVehicleObservationUiState.Ready)
            }
            assertEquals(4, fixture.observations.requests.size)
            assertEquals(1, fixture.observations.maximumActive)
            assertEquals(1, fixture.sessions.maximumActive)
        }

    @Test fun `observation factory failures are explicit and require retry rather than becoming empty`() =
        runTest {
            val fixture = Fixture()
            fixture.observations.factoryFailure = IOException("fixture read failure")
            val trace = watch(fixture)
            runCurrent()
            assertFailed(trace, RecentVehicleObservationUiState.FailureReason.OBSERVATION_READ)
            assertFalse(trace.states.any { it is RecentVehicleObservationUiState.Empty })
            fixture.observations.factoryFailure = null
            runCurrent()
            assertFailed(trace, RecentVehicleObservationUiState.FailureReason.OBSERVATION_READ)
            assertTrue(fixture.viewModel.retry())
            runCurrent()
            assertTrue(trace.last() is RecentVehicleObservationUiState.Ready)
        }

    @Test fun `session factory failures do not read observations and can be retried`() =
        runTest {
            val fixture = Fixture()
            fixture.sessions.factoryFailure = IOException("fixture session failure")
            val trace = watch(fixture)
            runCurrent()
            assertFailed(trace, RecentVehicleObservationUiState.FailureReason.SESSION_READ)
            assertTrue(fixture.observations.requests.isEmpty())
            fixture.sessions.factoryFailure = null
            assertTrue(fixture.viewModel.retry())
            runCurrent()
            assertTrue(trace.last() is RecentVehicleObservationUiState.Ready)
        }

    @Test fun `session stream failure clears ready data and cancels the observation query`() =
        runTest {
            val fixture = Fixture()
            val fail = CompletableDeferred<Unit>()
            fixture.sessions.source = {
                flow {
                    emit(listOf(session("session-a")))
                    fail.await()
                    throw IOException("fixture session stream failure")
                }
            }
            val trace = watch(fixture)
            runCurrent()
            assertTrue(trace.last() is RecentVehicleObservationUiState.Ready)
            fail.complete(Unit)
            runCurrent()
            assertFailed(trace, RecentVehicleObservationUiState.FailureReason.SESSION_READ)
            assertEquals(0, fixture.observations.active)
        }

    @Test fun `observation stream failure replaces ready data without retaining a stale successful snapshot`() =
        runTest {
            val fixture = Fixture()
            val fail = CompletableDeferred<Unit>()
            fixture.observations.source = {
                flow {
                    emit(listOf(row(1)))
                    fail.await()
                    throw IOException("fixture observation stream failure")
                }
            }
            val trace = watch(fixture)
            runCurrent()
            assertTrue(trace.last() is RecentVehicleObservationUiState.Ready)
            fail.complete(Unit)
            runCurrent()
            assertFailed(trace, RecentVehicleObservationUiState.FailureReason.OBSERVATION_READ)
            assertEquals(0, fixture.observations.active)
        }

    @Test fun `internally cancelled observation stream is a retryable failure while the caller is active`() =
        runTest {
            val fixture = Fixture()
            fixture.observations.source = { flow { throw CancellationException("fixture internal cancellation") } }
            val trace = watch(fixture)
            runCurrent()
            assertTrue(trace.job.isActive)
            assertFailed(trace, RecentVehicleObservationUiState.FailureReason.OBSERVATION_READ)
            fixture.observations.source = { fixture.observations.values(it) }
            assertTrue(fixture.viewModel.retry())
            runCurrent()
            assertTrue(trace.last() is RecentVehicleObservationUiState.Ready)
        }

    @Test fun `internally cancelled session stream is a retryable failure while the caller is active`() =
        runTest {
            val fixture = Fixture()
            fixture.sessions.source = { flow { throw CancellationException("fixture internal cancellation") } }
            val trace = watch(fixture)
            runCurrent()
            assertTrue(trace.job.isActive)
            assertFailed(trace, RecentVehicleObservationUiState.FailureReason.SESSION_READ)
            fixture.sessions.source = { fixture.sessions.values }
            assertTrue(fixture.viewModel.retry())
            runCurrent()
            assertTrue(trace.last() is RecentVehicleObservationUiState.Ready)
        }

    @Test fun `normal observation completion fails explicitly instead of pretending a live query remains healthy`() =
        runTest {
            val fixture = Fixture()
            val complete = CompletableDeferred<Unit>()
            fixture.observations.source = {
                flow {
                    emit(listOf(row(1)))
                    complete.await()
                }
            }
            val trace = watch(fixture)
            runCurrent()
            assertTrue(trace.last() is RecentVehicleObservationUiState.Ready)
            complete.complete(Unit)
            runCurrent()
            assertFailed(trace, RecentVehicleObservationUiState.FailureReason.OBSERVATION_STREAM_COMPLETED)
            assertEquals(0, fixture.observations.active)
            assertTrue(trace.job.isActive)
        }

    @Test fun `normal session completion cancels even a still live observation query and fails explicitly`() =
        runTest {
            val fixture = Fixture()
            val complete = CompletableDeferred<Unit>()
            fixture.sessions.source = {
                flow {
                    emit(listOf(session("session-a")))
                    complete.await()
                }
            }
            val trace = watch(fixture)
            runCurrent()
            assertTrue(trace.last() is RecentVehicleObservationUiState.Ready)
            complete.complete(Unit)
            runCurrent()
            assertFailed(trace, RecentVehicleObservationUiState.FailureReason.SESSION_STREAM_COMPLETED)
            assertEquals(0, fixture.observations.active)
            assertTrue(trace.job.isActive)
        }

    @Test fun `caller cancellation propagates its cause releases subscriptions and does not emit a failure`() =
        runTest {
            val fixture = Fixture()
            val trace = watch(fixture)
            runCurrent()
            var completedWith: Throwable? = null
            trace.job.invokeOnCompletion { completedWith = it }
            val cause = CancellationException("fixture route removed")
            trace.job.cancel(cause)
            trace.job.join()
            assertSame(cause, completedWith)
            assertEquals(0, fixture.sessions.active)
            assertEquals(0, fixture.observations.active)
            assertFalse(trace.states.any { it is RecentVehicleObservationUiState.Failed })
        }

    @Test fun `cross session nonpositive unordered duplicate and invalid time rows fail without repair or silent recovery`() =
        runTest {
            val invalid =
                listOf(
                    listOf(row(1, sessionId = "session-b")),
                    listOf(row(0)),
                    listOf(row(-1)),
                    listOf(row(1), row(1)),
                    listOf(row(2), row(1)),
                    listOf(row(1, time = 0)),
                    listOf(row(1, time = -1)),
                )
            for (rows in invalid) {
                val fixture = Fixture(rows)
                val trace = watch(fixture)
                runCurrent()
                assertFailed(trace, RecentVehicleObservationUiState.FailureReason.INVALID_OBSERVATIONS)
                assertEquals(0, fixture.observations.active)
                fixture.observations.values("session-a").value = listOf(row(1))
                runCurrent()
                assertFailed(trace, RecentVehicleObservationUiState.FailureReason.INVALID_OBSERVATIONS)
                assertTrue(fixture.viewModel.retry())
                runCurrent()
                assertTrue(trace.last() is RecentVehicleObservationUiState.Ready)
                trace.job.cancelAndJoin()
            }
        }

    @Test fun `ready owns a stable list snapshot rather than an adapters mutable container`() =
        runTest {
            val source = mutableListOf(row(1))
            val fixture = Fixture(source)
            val trace = watch(fixture)
            runCurrent()
            val published = trace.ready()
            source += row(2)
            assertEquals(listOf(row(1)), published.observations)
        }

    private fun TestScope.watch(
        fixture: Fixture,
        sessionId: String = "session-a",
    ): Trace {
        val states = mutableListOf<RecentVehicleObservationUiState>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.viewModel.observe(sessionId).collect { states += it }
            }
        return Trace(states, job)
    }

    private fun assertFailed(
        trace: Trace,
        reason: RecentVehicleObservationUiState.FailureReason,
    ) {
        assertEquals(RecentVehicleObservationUiState.Failed("session-a", reason), trace.last())
    }

    private data class Trace(
        val states: MutableList<RecentVehicleObservationUiState>,
        val job: Job,
    ) {
        fun last() = states.last()

        fun ready() = last() as RecentVehicleObservationUiState.Ready
    }

    private class Fixture(
        rows: List<SessionVehicleObservation> = listOf(row(1)),
    ) {
        val vehicle = FakeVehicle()
        val restrictions = FakeRestrictions()
        val sessions = FakeSessions()
        val observations = FakeObservations(rows)
        val viewModel = RecentVehicleObservationViewModel(vehicle, restrictions, sessions, observations)
    }

    private class FakeVehicle : VehicleGateway {
        override val state = MutableStateFlow(parkedVehicle())
    }

    private class FakeRestrictions : DrivingRestrictionsProvider {
        override val restrictions = MutableStateFlow(unrestricted())
    }

    private class FakeSessions : PracticeSessionRepository {
        val values = MutableStateFlow(listOf(session("session-a")))
        var source: () -> Flow<List<PracticeSession>> = { values }
        var factoryFailure: Exception? = null
        var factoryCalls = 0
        var active = 0
        var maximumActive = 0

        override fun observeSessions(): Flow<List<PracticeSession>> {
            factoryCalls++
            factoryFailure?.let { throw it }
            val upstream = source()
            return flow {
                active++
                maximumActive = maxOf(maximumActive, active)
                try {
                    emitAll(upstream)
                } finally {
                    active--
                }
            }
        }

        override suspend fun start(
            practice: PreparedPractice,
            startedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionStartResult = error("Read-only review must not start a session")

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult = error("Read-only review must not finish a session")
    }

    private class FakeObservations(
        initial: List<SessionVehicleObservation>,
    ) : VehicleObservationRepository {
        private val rows = mutableMapOf("session-a" to MutableStateFlow(initial))
        var source: (String) -> Flow<List<SessionVehicleObservation>> = { values(it) }
        var factoryFailure: Exception? = null
        val requests = mutableListOf<String>()
        var active = 0
        var maximumActive = 0
        var appendCalls = 0

        fun values(sessionId: String) = rows.getOrPut(sessionId) { MutableStateFlow(emptyList()) }

        override fun observe(sessionId: String): Flow<List<SessionVehicleObservation>> {
            requests += sessionId
            factoryFailure?.let { throw it }
            val upstream = source(sessionId)
            return flow {
                active++
                maximumActive = maxOf(maximumActive, active)
                try {
                    emitAll(upstream)
                } finally {
                    active--
                }
            }
        }

        override suspend fun append(
            sessionId: String,
            observation: VehicleObservation,
        ): VehicleObservationAppendResult {
            appendCalls++
            error("Read-only review must not append observations")
        }
    }

    private companion object {
        fun parkedVehicle() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        fun unrestricted() = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)

        fun session(
            id: String,
            started: Long = 1_000,
            ended: Long? = 2_000,
        ) = PracticeSession(
            sessionId = id,
            selection = PracticeSelection("goal", "course", 1, 1, "task"),
            goalTitle = "Fixture goal",
            courseTitle = "Fixture course",
            taskTitle = "Fixture task",
            courseReviewStatus = CourseReviewStatus.DEMO,
            startedAtEpochMillis = started,
            endedAtEpochMillis = ended,
        )

        fun row(
            id: Long,
            sessionId: String = "session-a",
            time: Long = 1_500,
            state: VehicleState = parkedVehicle(),
        ) = SessionVehicleObservation(id, sessionId, VehicleObservation(time, state))
    }
}
