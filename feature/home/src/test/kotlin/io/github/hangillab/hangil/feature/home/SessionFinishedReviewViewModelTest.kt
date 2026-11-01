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
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionDeletionRepository
import io.github.hangillab.hangil.core.domain.SessionDeletionResult
import io.github.hangillab.hangil.core.domain.SessionDeletionTarget
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import io.github.hangillab.hangil.core.domain.VehicleObservationRepository
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionFinishedReviewViewModelTest {
    @Before fun before() {
        Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher())
    }

    @After fun after() {
        Dispatchers.resetMain()
    }

    @Test fun `exact entry is cold and selects its source before any newest record`() =
        runTest {
            val f = Fixture()
            val flow = f.model.observeEntry(SessionHistoryEntry.FinishedReview(f.a)) { true }
            assertEquals(0, f.reads)
            val states = mutableListOf<SessionHistoryUiState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { states += it } }
            runCurrent()
            val ready = states.filterIsInstance<SessionHistoryUiState.Ready>()
            assertTrue(ready.isNotEmpty())
            assertTrue(ready.all { it.session == f.a })
            assertEquals(0, f.observationReads)
            assertEquals(0, f.writes)
        }

    @Test fun `lost exact source is unavailable without starting a database query`() =
        runTest {
            val f = Fixture()
            f.attach(this, null)
            assertEquals(SessionHistoryUiState.Unavailable, f.state)
            assertEquals(0, f.reads)
            assertEquals(0, f.writes)
        }

    @Test fun `active source cannot masquerade as an ended review entry`() =
        runTest {
            val f = Fixture()
            f.attach(this, f.a.copy(endedAtEpochMillis = null))
            assertEquals(SessionHistoryUiState.Unavailable, f.state)
            assertEquals(0, f.reads)
        }

    @Test fun `missing exact source never falls back to the newest available record`() =
        runTest {
            val f = Fixture()
            f.values.value = listOf(f.b)
            f.attach(this)
            assertEquals(SessionHistoryUiState.Unavailable, f.state)
            assertFalse(f.model.resetToLatest(1))
            assertEquals(0, f.writes)
        }

    @Test fun `same id metadata replacement expires the exact entry permanently`() =
        runTest {
            val f = Fixture()
            f.attach(this)
            val old = f.ready()
            f.values.value = listOf(f.b, f.a.copy(taskTitle = "replacement"))
            runCurrent()
            assertEquals(SessionHistoryUiState.Unavailable, f.state)
            assertFalse(f.model.canRevealSummary(old.session.sessionId, old.generation))
            f.values.value = listOf(f.b, f.a)
            runCurrent()
            assertEquals(SessionHistoryUiState.Unavailable, f.state)
            assertFalse(f.model.retry())
        }

    @Test fun `observed deletion and identical recreation do not revive an old entry`() =
        runTest {
            val f = Fixture()
            val subscription = f.attach(this)
            f.values.value = listOf(f.b)
            runCurrent()
            assertEquals(SessionHistoryUiState.Unavailable, f.state)
            subscription.cancel()
            runCurrent()
            f.values.value = listOf(f.b, f.a)
            f.attach(this)
            assertEquals(SessionHistoryUiState.Unavailable, f.state)
        }

    @Test fun `exact entry refuses all browse and reset actions`() =
        runTest {
            val f = Fixture()
            f.attach(this)
            val ready = f.ready()
            assertFalse(f.model.older(ready.session.sessionId, ready.generation))
            assertFalse(f.model.newer(ready.session.sessionId, ready.generation))
            assertFalse(f.model.resetToLatest(ready.generation))
            assertEquals(f.a, f.ready().session)
        }

    @Test fun `general history still defaults to newest and explicitly browses older`() =
        runTest {
            val f = Fixture()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.model.uiState.collect { f.state = it } }
            runCurrent()
            val newest = f.ready()
            assertEquals(f.b, newest.session)
            assertTrue(f.model.older(newest.session.sessionId, newest.generation))
            runCurrent()
            assertEquals(f.a, f.ready().session)
        }

    @Test fun `an expired host rejects details and a new host receives a fresh generation`() =
        runTest {
            val f = Fixture()
            var live = true
            val subscription = f.attach(this, host = { live })
            val old = f.ready()
            live = false
            assertFalse(f.model.canRevealStoredObservations(old.session.sessionId, old.generation))
            assertFalse(f.model.requestDeletion(old.session.sessionId, old.generation) { true })
            subscription.cancel()
            runCurrent()
            f.attach(this)
            val fresh = f.ready()
            assertTrue(fresh.generation > old.generation)
            assertFalse(f.model.canRevealStoredObservations(old.session.sessionId, old.generation))
            assertTrue(f.model.canRevealStoredObservations(fresh.session.sessionId, fresh.generation))
        }

    @Test fun `live unknown denies old details before a UI recomposition and does not borrow P`() =
        runTest {
            val f = Fixture()
            f.attach(this)
            val old = f.ready()
            f.vehicleValues.value = VehicleState()
            assertFalse(f.model.canRevealStoredObservations(old.session.sessionId, old.generation))
            f.vehicleValues.value = parked()
            runCurrent()
            assertFalse(f.model.canRevealStoredObservations(old.session.sessionId, old.generation))
            assertEquals(f.a, f.ready().session)
        }

    @Test fun `failed and finite session streams stay failure until an explicit current retry`() =
        runTest {
            for (finite in listOf(false, true)) {
                val f = Fixture()
                f.source = { if (finite) flowOf(listOf(f.b, f.a)) else flow { throw IllegalStateException("controlled read") } }
                f.attach(this)
                val failed = f.state
                assertTrue(failed is SessionHistoryUiState.Failed)
                val reads = f.reads
                f.source = { f.values }
                runCurrent()
                assertEquals(reads, f.reads)
                assertTrue(f.model.retryEntry(failed) { true })
                runCurrent()
                assertEquals(f.a, f.ready().session)
                assertFalse(f.model.retryEntry(failed) { true })
            }
        }

    @Test fun `query self cancellation becomes failure rather than leaving a ready or empty snapshot`() =
        runTest {
            val f = Fixture()
            f.source = {
                flow {
                    emit(listOf(f.b, f.a))
                    currentCoroutineContext().cancel()
                    awaitCancellation()
                }
            }
            f.attach(this)
            assertTrue(f.state is SessionHistoryUiState.Failed)
            assertFalse(f.state is SessionHistoryUiState.Empty)
            assertEquals(0, f.writes)
        }

    @Test fun `stored observation empty is actual empty and query belongs only to the exact source`() =
        runTest {
            val f = Fixture()
            f.attach(this)
            val current = f.ready()
            val states = mutableListOf<SessionHistoryObservationUiState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model.observeStoredObservations(current.session, current.generation) { true }.collect { states += it }
            }
            runCurrent()
            assertEquals(listOf(f.a.sessionId), f.observedIds)
            assertTrue(states.last() is SessionHistoryObservationUiState.Empty)
            assertEquals(0, f.writes)
        }

    @Test fun `denied exact collector emits local lock without displacing a valid reader`() =
        runTest {
            val f = Fixture()
            f.attach(this)
            val original = f.ready()
            val reads = f.reads
            val deniedStates = mutableListOf<SessionHistoryUiState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model.observeEntry(SessionHistoryEntry.FinishedReview(f.a)) { false }.collect { deniedStates += it }
            }
            runCurrent()
            assertEquals(SessionHistoryUiState.Locked, deniedStates.last())
            assertEquals(original, f.state)
            assertEquals(reads, f.reads)
            assertTrue(f.model.canRevealStoredObservations(original.session.sessionId, original.generation))
        }

    @Test fun `cancelling a denied collector cannot clear a different live exact owner`() =
        runTest {
            val f = Fixture()
            f.attach(this)
            val original = f.ready()
            val reads = f.reads
            val denied =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    f.model.observeEntry(SessionHistoryEntry.FinishedReview(f.a)) { false }.collect()
                }
            runCurrent()
            denied.cancel()
            runCurrent()
            assertEquals(original, f.state)
            assertEquals(reads, f.reads)
            assertTrue(f.model.canRevealStoredObservations(original.session.sessionId, original.generation))
        }

    @Test fun `already cancelled exact collection cannot install or clear a shared reader`() =
        runTest {
            val f = Fixture()
            f.attach(this)
            val original = f.ready()
            val reads = f.reads
            val rejected = mutableListOf<SessionHistoryUiState>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                // Enter the cold flow with its own cancelled Job. No SDK or external lifecycle is involved.
                currentCoroutineContext().cancel()
                f.model.observeEntry(SessionHistoryEntry.FinishedReview(f.a)) { true }.collect { rejected += it }
            }
            runCurrent()
            assertTrue(rejected.isEmpty())
            assertEquals(original, f.state)
            assertEquals(reads, f.reads)
            assertTrue(f.model.canRevealStoredObservations(original.session.sessionId, original.generation))
        }

    private class Fixture {
        val a = session("a")
        val b = session("b")
        val values = MutableStateFlow(listOf(b, a))
        var source: () -> Flow<List<PracticeSession>> = { values }
        var state: SessionHistoryUiState = SessionHistoryUiState.Loading
        var reads = 0
        var observationReads = 0
        var writes = 0
        val observedIds = mutableListOf<String>()
        val vehicleValues = MutableStateFlow(parked())
        val vehicle =
            object : VehicleGateway {
                override val state = vehicleValues
            }
        private val restrictions =
            object : DrivingRestrictionsProvider {
                override val restrictions = MutableStateFlow(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))
            }
        private val sessions =
            object : PracticeSessionRepository {
                override fun observeSessions(): Flow<List<PracticeSession>> =
                    flow {
                        reads++
                        emitAll(source())
                    }

                override suspend fun start(
                    practice: PreparedPractice,
                    startedAtEpochMillis: Long,
                    canCommit: () -> Boolean,
                ): SessionStartResult {
                    writes++
                    error("unexpected session start")
                }

                override suspend fun finish(
                    sessionId: String,
                    endedAtEpochMillis: Long,
                    canCommit: () -> Boolean,
                ): SessionFinishResult {
                    writes++
                    error("unexpected session finish")
                }
            }
        private val deletions =
            object : SessionDeletionRepository {
                override suspend fun prepare(
                    expected: PracticeSession,
                    canRead: () -> Boolean,
                ): SessionDeletionPreparation = error("unexpected deletion preparation")

                override suspend fun delete(
                    target: SessionDeletionTarget,
                    canCommit: () -> Boolean,
                ): SessionDeletionResult = error("unexpected deletion")
            }
        private val observations =
            object : VehicleObservationRepository {
                override fun observe(sessionId: String): Flow<List<SessionVehicleObservation>> =
                    flow {
                        observationReads++
                        observedIds += sessionId
                        emit(emptyList())
                        awaitCancellation()
                    }

                override suspend fun append(
                    sessionId: String,
                    observation: VehicleObservation,
                ): VehicleObservationAppendResult {
                    writes++
                    error("unexpected observation append")
                }
            }
        val model =
            SessionHistoryViewModel(
                vehicle,
                restrictions,
                sessions,
                deletions,
                observations,
                UnexpectedHistoryMemoRepository,
                UnexpectedHistoryRecordingRunRepository,
                UnexpectedHistoryCourseCompletionRepository,
                UnexpectedHistorySessionMemoWriter,
            )

        fun attach(
            scope: TestScope,
            expected: PracticeSession? = a,
            host: () -> Boolean = { true },
        ): Job {
            val job =
                scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) {
                    model.observeEntry(SessionHistoryEntry.FinishedReview(expected), host).collect { state = it }
                }
            scope.runCurrent()
            return job
        }

        fun ready() = state as SessionHistoryUiState.Ready
    }

    private companion object {
        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        fun session(id: String) =
            PracticeSession(
                id,
                PracticeSelection("goal-$id", "course-$id", 1, 1, "task-$id"),
                "목표 $id",
                "코스 $id",
                "과제 $id",
                CourseReviewStatus.DEMO,
                1_000,
                2_000,
            )
    }
}
