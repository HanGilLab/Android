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
import io.github.hangillab.hangil.core.domain.TurnSignalState
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import io.github.hangillab.hangil.core.domain.VehicleObservationRepository
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.feature.home.SessionHistoryObservationUiState.FailureReason
import io.github.hangillab.hangil.feature.home.SessionHistoryObservationUiState.Token
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class SessionHistoryObservationViewModelTest {
    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `creating an uncollected detail flow does not query either repository`() =
        runTest {
            val f = Fixture()
            f.model.observeStoredObservations(session("a"), 1L, f.host::get)
            runCurrent()
            assertEquals(0, f.sessions.calls)
            assertEquals(0, f.observations.calls)
            assertFalse(f.model.canReadStoredObservations(Token(0, 0, 0)))
        }

    @Test fun `a detail without a parent reader is unavailable and never queries observations`() =
        runTest {
            val f = Fixture()
            val d = open(f, session("a"), 1)
            assertTrue(d.value is SessionHistoryObservationUiState.Unavailable)
            assertEquals(0, f.observations.calls)
        }

    @Test fun `a live D parent and detail cannot start a database query`() =
        runTest {
            val f = Fixture()
            f.vehicle.emit(driving())
            start(f)
            val d = open(f, session("a"), 1)
            assertEquals(SessionHistoryUiState.Locked, f.model.uiState.value)
            assertTrue(d.value is SessionHistoryObservationUiState.Unavailable)
            assertEquals(0, f.sessions.calls)
            assertEquals(0, f.observations.calls)
        }

    @Test fun `unknown UX cannot start the stored detail query`() =
        runTest {
            val f = Fixture()
            f.restrictions.emit(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNKNOWN))
            start(f)
            open(f, session("a"), 1)
            assertEquals(0, f.observations.calls)
        }

    @Test fun `the full expected source must match not merely its ID`() =
        runTest {
            val f = Fixture()
            start(f)
            val parent = parent(f)
            val d = open(f, parent.session.copy(taskTitle = "different snapshot"), parent.generation)
            assertTrue(d.value is SessionHistoryObservationUiState.Unavailable)
            assertEquals(0, f.observations.calls)
        }

    @Test fun `an expired parent generation cannot open the same source`() =
        runTest {
            val f = Fixture()
            start(f)
            val old = parent(f)
            assertTrue(f.model.retry())
            runCurrent()
            val d = open(f, old.session, old.generation)
            assertTrue(d.value is SessionHistoryObservationUiState.Unavailable)
            assertEquals(0, f.observations.calls)
        }

    @Test fun `a selected older ended record reads only its own rows without a second sessions subscription`() =
        runTest {
            val f = Fixture()
            start(f)
            val newest = parent(f)
            assertTrue(f.model.older(newest.session.sessionId, newest.generation))
            runCurrent()
            val older = parent(f)
            f.observations.values.value = listOf(row(9, "b"), row(10, "b"))
            val d = open(f, older.session, older.generation)
            assertEquals("b", ready(d).source.sessionId)
            assertEquals(10L, ready(d).row.id)
            assertEquals(listOf("b"), f.observations.requested)
            assertEquals(1, f.sessions.calls)
            assertEquals(1, f.observations.active)
        }

    @Test fun `initial selection uses the last stored ID and preserves regressing observation clocks`() =
        runTest {
            val f = Fixture()
            start(f)
            f.observations.values.value = listOf(row(1, time = 900), row(7, time = 100))
            val d = open(f)
            assertEquals(listOf(1L, 7L), ready(d).observations.map { it.id })
            assertEquals(7L, ready(d).row.id)
            assertEquals(100L, ready(d).row.observation.observedAtEpochMillis)
        }

    @Test fun `paging validates the displayed row and rotates revision without another query`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val original = ready(d)
            assertFalse(f.model.previousStoredObservation(original.token, 99))
            assertTrue(f.model.previousStoredObservation(original.token, original.row.id))
            runCurrent()
            val previous = ready(d)
            assertEquals(2L, previous.row.id)
            assertNotEquals(original.token, previous.token)
            assertFalse(f.model.previousStoredObservation(original.token, original.row.id))
            assertFalse(f.model.nextStoredObservation(previous.token, 99))
            assertTrue(f.model.nextStoredObservation(previous.token, previous.row.id))
            runCurrent()
            assertEquals(3L, ready(d).row.id)
            assertEquals(1, f.observations.calls)
        }

    @Test fun `paging boundaries do not mutate the token or cursor`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val last = ready(d)
            assertFalse(f.model.nextStoredObservation(last.token, last.row.id))
            assertEquals(last, ready(d))
            repeat(2) {
                val current = ready(d)
                assertTrue(f.model.previousStoredObservation(current.token, current.row.id))
                runCurrent()
            }
            val first = ready(d)
            assertFalse(f.model.previousStoredObservation(first.token, first.row.id))
            assertFalse(first.canPrevious)
            assertTrue(first.canNext)
            assertEquals(1, f.observations.calls)
        }

    @Test fun `an initially empty live stream is Empty rather than an invented row`() =
        runTest {
            val f = Fixture()
            start(f)
            f.observations.values.value = emptyList()
            val d = open(f)
            assertTrue(d.value is SessionHistoryObservationUiState.Empty)
            assertTrue(f.model.canReadStoredObservations(checkNotNull(d.value).token))
            assertEquals(1, f.observations.active)
        }

    @Test fun `wrong session rows are rejected instead of filtered`() =
        runTest {
            assertInvalid(listOf(row(1), row(2, "b")))
        }

    @Test fun `duplicate row IDs are invalid`() = runTest { assertInvalid(listOf(row(1), row(1))) }

    @Test fun `decreasing row IDs are invalid`() = runTest { assertInvalid(listOf(row(2), row(1))) }

    @Test fun `nonpositive row IDs are invalid`() = runTest { assertInvalid(listOf(row(0))) }

    @Test fun `nonpositive observation times are invalid`() = runTest { assertInvalid(listOf(row(1, time = 0))) }

    @Test fun `returned rows cannot be externally mutated into another cursor`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val current = ready(d)
            var refused = false
            try {
                (current.observations as MutableList<SessionVehicleObservation>).clear()
            } catch (
                _: UnsupportedOperationException,
            ) {
                refused = true
            }
            assertTrue(refused)
            assertEquals(3L, ready(d).row.id)
        }

    @Test fun `a removed selected row is not silently replaced and explicit retry accepts the new list`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            f.observations.values.value = listOf(row(1), row(2))
            runCurrent()
            val failed = failed(d)
            assertEquals(FailureReason.SELECTED_ROW_MISSING, failed.reason)
            assertEquals(3L, failed.selectedRowId)
            assertTrue(f.model.retryStoredObservations(failed.token, failed.selectedRowId))
            runCurrent()
            assertEquals(2L, ready(d).row.id)
        }

    @Test fun `row receipt keeps the selected ID but rejects actions with an old revision`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            f.observations.values.value = listOf(row(1), row(2), row(3), row(4))
            runCurrent()
            assertEquals(3L, ready(d).row.id)
            assertNotEquals(old.token, ready(d).token)
            assertFalse(f.model.previousStoredObservation(old.token, old.row.id))
        }

    @Test fun `a completed observation stream is terminal failure even after an empty emission`() =
        runTest {
            val f = Fixture()
            start(f)
            f.observations.querySource = { flow { emit(emptyList()) } }
            val d = open(f)
            assertEquals(FailureReason.OBSERVATION_STREAM_COMPLETED, failed(d).reason)
            assertEquals(0, f.observations.active)
            assertEquals(1, f.observations.calls)
        }

    @Test fun `repository factory errors remain failures without retrying on vehicle events`() =
        runTest {
            val f = Fixture()
            start(f)
            f.observations.factoryFailure = IOException("controlled factory failure")
            val d = open(f)
            assertEquals(FailureReason.OBSERVATION_READ, failed(d).reason)
            f.vehicle.emit(parked().copy(turnSignal = VehicleReading.Available(TurnSignalState.LEFT)))
            runCurrent()
            assertTrue(d.value is SessionHistoryObservationUiState.Failed)
            assertEquals(1, f.observations.calls)
        }

    @Test fun `an independently cancelled observation source fails without cancelling the parent`() =
        runTest {
            val f = Fixture()
            val parentJob = start(f)
            f.observations.querySource = { flow { throw CancellationException("independent read") } }
            val d = open(f)
            assertEquals(FailureReason.OBSERVATION_READ, failed(d).reason)
            assertTrue(parentJob.isActive)
            assertTrue(f.model.uiState.value is SessionHistoryUiState.Ready)
        }

    @Test fun `retry checks the exact failure row and token before creating a fresh query`() =
        runTest {
            val f = Fixture()
            start(f)
            f.observations.querySource = {
                flow {
                    emit(listOf(row(1), row(2)))
                    throw IOException("controlled read")
                }
            }
            val d = open(f)
            val old = failed(d)
            assertFalse(f.model.retryStoredObservations(old.token, 99))
            f.observations.querySource = { f.observations.values }
            assertTrue(f.model.retryStoredObservations(old.token, old.selectedRowId))
            runCurrent()
            val current = ready(d)
            assertNotEquals(old.token.queryNonce, current.token.queryNonce)
            assertFalse(f.model.retryStoredObservations(old.token, old.selectedRowId))
            assertEquals(2, f.observations.calls)
            assertEquals(1, f.observations.active)
        }

    @Test fun `history selection movement cancels the detail and old callbacks cannot affect the next record`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            val source = parent(f)
            assertTrue(f.model.older(source.session.sessionId, source.generation))
            runCurrent()
            assertTrue(d.value is SessionHistoryObservationUiState.Unavailable)
            assertEquals(0, f.observations.active)
            f.observations.values.value = listOf(row(10, "b"), row(11, "b"))
            val newer = open(f)
            val current = ready(newer)
            assertFalse(f.model.previousStoredObservation(old.token, old.row.id))
            assertFalse(f.model.canReadStoredObservations(old.token))
            assertEquals(current, ready(newer))
            assertTrue(f.model.canReadStoredObservations(current.token))
        }

    @Test fun `same ID full source changes invalidate the captured detail before old paging`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            f.sessions.values.value = listOf(session("a").copy(taskTitle = "new stored title"), session("b"))
            runCurrent()
            assertTrue(d.value is SessionHistoryObservationUiState.Unavailable)
            assertFalse(f.model.previousStoredObservation(old.token, old.row.id))
            assertEquals(0, f.observations.active)
        }

    @Test fun `receiving the same full source again still expires the old History generation`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            f.sessions.values.value = listOf(session("a"), session("b"), session("c"))
            runCurrent()
            assertEquals(old.source, parent(f).session)
            assertTrue(d.value is SessionHistoryObservationUiState.Unavailable)
            assertFalse(f.model.canReadStoredObservations(old.token))
            assertEquals(0, f.observations.active)
        }

    @Test fun `a deleted selected session invalidates detail rather than displaying empty rows as success`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            f.sessions.values.value = listOf(session("b"))
            runCurrent()
            assertTrue(f.model.uiState.value is SessionHistoryUiState.SelectionChanged)
            assertTrue(d.value is SessionHistoryObservationUiState.Unavailable)
            assertEquals(0, f.observations.active)
        }

    @Test fun `parent retry invalidates a stored detail even when the same source returns`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            assertTrue(f.model.retry())
            runCurrent()
            assertTrue(d.value is SessionHistoryObservationUiState.Unavailable)
            assertFalse(f.model.canReadStoredObservations(old.token))
            val fresh = open(f)
            assertNotEquals(old.token.readerId, ready(fresh).token.readerId)
            assertEquals(1, f.observations.active)
        }

    @Test fun `a closed host is revoked immediately without another row emission`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            f.host.set(false)
            assertFalse(f.model.canReadStoredObservations(old.token))
            runCurrent()
            assertTrue(d.value is SessionHistoryObservationUiState.Unavailable)
            assertEquals(0, f.observations.active)
            f.host.set(true)
            assertFalse(f.model.canReadStoredObservations(old.token))
        }

    @Test fun `cancelling the detail cannot retain its query or its old action token`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            d.job.cancel()
            assertFalse(f.model.canReadStoredObservations(old.token))
            runCurrent()
            assertEquals(0, f.observations.active)
            val fresh = open(f)
            assertNotEquals(old.token.readerId, ready(fresh).token.readerId)
            assertFalse(f.model.previousStoredObservation(old.token, old.row.id))
        }

    @Test fun `cancelling the parent cancels the expanded query even if its collector remains alive`() =
        runTest {
            val f = Fixture()
            val parentJob = start(f)
            val d = open(f)
            val old = ready(d)
            parentJob.cancelAndJoin()
            runCurrent()
            assertFalse(f.model.canReadStoredObservations(old.token))
            assertTrue(d.value is SessionHistoryObservationUiState.Unavailable)
            assertEquals(0, f.observations.active)
        }

    @Test fun `a finite parent source cancels detail and preserves the parent read failure`() =
        runTest {
            val f = Fixture()
            val finish = CompletableDeferred<Unit>()
            f.sessions.querySource = {
                flow {
                    emit(listOf(session("a"), session("b")))
                    finish.await()
                }
            }
            start(f)
            val d = open(f)
            finish.complete(Unit)
            runCurrent()
            assertTrue(f.model.uiState.value is SessionHistoryUiState.Failed)
            assertTrue(d.value is SessionHistoryObservationUiState.Unavailable)
            assertEquals(0, f.observations.active)
        }

    @Test fun `a current page action observing silent D revokes parent and detail and never revives`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            f.vehicle.setWithoutEmission(driving())
            assertFalse(f.model.previousStoredObservation(old.token, old.row.id))
            // The receipt/action fence is synchronous; uiState's stateIn display copy is scheduled.
            assertFalse(f.model.canRevealStoredObservations(old.source.sessionId, old.historyGeneration))
            assertFalse(f.model.canReadStoredObservations(old.token))
            assertFalse(f.model.nextStoredObservation(old.token, old.row.id))
            runCurrent()
            assertEquals(SessionHistoryUiState.Locked, f.model.uiState.value)
            assertTrue(d.value is SessionHistoryObservationUiState.Locked)
            assertEquals(0, f.observations.active)
            f.vehicle.setWithoutEmission(parked())
            assertFalse(f.model.canRevealStoredObservations(old.source.sessionId, old.historyGeneration))
            assertFalse(f.model.canReadStoredObservations(old.token))
            assertFalse(f.model.previousStoredObservation(old.token, old.row.id))
            assertTrue(f.model.retry())
            runCurrent()
            val fresh = open(f)
            assertTrue(f.model.canReadStoredObservations(ready(fresh).token))
            assertFalse(f.model.canReadStoredObservations(old.token))
        }

    @Test fun `canRead itself observing silent denied UX permanently invalidates that token`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            f.restrictions.setWithoutEmission(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.RESTRICTED))
            assertFalse(f.model.canReadStoredObservations(old.token))
            runCurrent()
            assertTrue(d.value is SessionHistoryObservationUiState.Locked)
            assertEquals(0, f.observations.active)
            f.restrictions.setWithoutEmission(unrestricted())
            assertFalse(f.model.canReadStoredObservations(old.token))
        }

    @Test fun `a retry observing silent D cannot revive a failed old reader after P`() =
        runTest {
            val f = Fixture()
            start(f)
            f.observations.factoryFailure = IOException("controlled failure")
            val d = open(f)
            val old = failed(d)
            f.vehicle.setWithoutEmission(driving())
            assertFalse(f.model.retryStoredObservations(old.token, old.selectedRowId))
            runCurrent()
            assertTrue(d.value is SessionHistoryObservationUiState.Locked)
            f.vehicle.setWithoutEmission(parked())
            assertFalse(f.model.retryStoredObservations(old.token, old.selectedRowId))
            assertEquals(1, f.observations.calls)
        }

    @Test fun `an old opening guard that observes D cannot borrow P or a fresh History generation`() =
        runTest {
            val f = Fixture()
            start(f)
            val old = parent(f)
            f.vehicle.setWithoutEmission(driving())
            assertFalse(f.model.canRevealStoredObservations(old.session.sessionId, old.generation))
            runCurrent()
            assertEquals(SessionHistoryUiState.Locked, f.model.uiState.value)
            f.vehicle.setWithoutEmission(parked())
            assertFalse(f.model.canRevealStoredObservations(old.session.sessionId, old.generation))
            assertTrue(f.model.retry())
            runCurrent()
            val current = parent(f)
            assertNotEquals(old.generation, current.generation)
            assertTrue(f.model.canRevealStoredObservations(current.session.sessionId, current.generation))
            assertEquals(0, f.observations.calls)
        }

    @Test fun `row receipt during silent D locks without a hot requery loop`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            f.vehicle.setWithoutEmission(driving())
            f.observations.values.value = listOf(row(1), row(2), row(3), row(4))
            runCurrent()
            assertEquals(SessionHistoryUiState.Locked, f.model.uiState.value)
            assertTrue(d.value is SessionHistoryObservationUiState.Locked)
            assertEquals(1, f.observations.calls)
            assertEquals(0, f.observations.active)
        }

    @Test fun `late rows from a cancelled reader cannot overwrite a replacement reader`() =
        runTest {
            val f = Fixture()
            start(f)
            f.observations.querySource = { lateRowsOnCancellation() }
            val oldDetail = open(f)
            val old = ready(oldDetail)
            f.observations.querySource = { f.observations.values }
            val fresh = open(f)
            assertEquals(3L, ready(fresh).row.id)
            assertFalse(f.model.canReadStoredObservations(old.token))
            assertTrue(oldDetail.value is SessionHistoryObservationUiState.Unavailable)
            assertEquals(1, f.observations.active)
        }

    @Test fun `a throwing host guard cannot open an observation query`() =
        runTest {
            val f = Fixture()
            start(f)
            val parent = parent(f)
            val states = mutableListOf<SessionHistoryObservationUiState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model
                    .observeStoredObservations(parent.session, parent.generation) { throw IOException("closed host") }
                    .collect { states.add(it) }
            }
            runCurrent()
            assertTrue(states.last() is SessionHistoryObservationUiState.Unavailable)
            assertEquals(0, f.observations.calls)
        }

    @Test fun `an old failure retry cannot cancel a replacement source reader`() =
        runTest {
            val f = Fixture()
            start(f)
            f.observations.factoryFailure = IOException("old failure")
            val d = open(f)
            val old = failed(d)
            val parent = parent(f)
            assertTrue(f.model.older(parent.session.sessionId, parent.generation))
            runCurrent()
            f.observations.factoryFailure = null
            f.observations.values.value = listOf(row(10, "b"), row(11, "b"))
            val fresh = open(f)
            val current = ready(fresh)
            val calls = f.observations.calls
            assertFalse(f.model.retryStoredObservations(old.token, old.selectedRowId))
            assertEquals(current, ready(fresh))
            assertTrue(f.model.canReadStoredObservations(current.token))
            assertEquals(calls, f.observations.calls)
            assertEquals(1, f.observations.active)
        }

    private fun TestScope.assertInvalid(rows: List<SessionVehicleObservation>) {
        val f = Fixture()
        start(f)
        f.observations.values.value = rows
        val d = open(f)
        assertEquals(FailureReason.INVALID_OBSERVATIONS, failed(d).reason)
        assertEquals(0, f.observations.active)
    }

    private fun TestScope.start(f: Fixture): Job {
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.model.uiState.collect {} }
        runCurrent()
        return job
    }

    private fun parent(f: Fixture) = f.model.uiState.value as SessionHistoryUiState.Ready

    private fun ready(d: Detail) = d.value as SessionHistoryObservationUiState.Ready

    private fun failed(d: Detail) = d.value as SessionHistoryObservationUiState.Failed

    private fun TestScope.open(
        f: Fixture,
        source: PracticeSession = parent(f).session,
        generation: Long = parent(f).generation,
    ): Detail {
        val d = Detail()
        d.job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model.observeStoredObservations(source, generation, f.host::get).collect { d.value = it }
            }
        runCurrent()
        return d
    }

    private class Detail {
        lateinit var job: Job
        var value: SessionHistoryObservationUiState? = null
    }

    private class Fixture {
        val vehicle = SilentLiveState(parked())
        val restrictions = SilentLiveState(unrestricted())
        val sessions = Sessions()
        val observations = Observations()
        val host = AtomicBoolean(true)
        val deletions =
            object : SessionDeletionRepository {
                override suspend fun prepare(
                    expected: PracticeSession,
                    canRead: () -> Boolean,
                ): SessionDeletionPreparation = error("Deletion is outside this read-only fixture")

                override suspend fun delete(
                    target: SessionDeletionTarget,
                    canCommit: () -> Boolean,
                ): SessionDeletionResult = error("Deletion is outside this read-only fixture")
            }
        val model =
            SessionHistoryViewModel(
                object : VehicleGateway {
                    override val state = vehicle
                },
                object : DrivingRestrictionsProvider {
                    override val restrictions = this@Fixture.restrictions
                },
                sessions,
                deletions,
                observations,
                UnexpectedHistoryMemoRepository,
                UnexpectedHistoryRecordingRunRepository,
                UnexpectedHistoryCourseCompletionRepository,
                UnexpectedHistorySessionMemoWriter,
            )
    }

    private class Sessions : PracticeSessionRepository {
        val values = MutableStateFlow(listOf(session("a"), session("b")))
        var calls = 0
        var querySource: () -> Flow<List<PracticeSession>> = { values }

        override fun observeSessions(): Flow<List<PracticeSession>> {
            calls++
            return flow { emitAll(querySource()) }
        }

        override suspend fun start(
            practice: PreparedPractice,
            startedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionStartResult = error("Read-only history must not start sessions")

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult = error("Read-only history must not finish sessions")
    }

    private class Observations : VehicleObservationRepository {
        val values = MutableStateFlow(listOf(row(1), row(2), row(3)))
        val requested = mutableListOf<String>()
        var calls = 0
        var active = 0
        var factoryFailure: Throwable? = null
        var querySource: () -> Flow<List<SessionVehicleObservation>> = { values }

        override fun observe(sessionId: String): Flow<List<SessionVehicleObservation>> {
            calls++
            requested.add(sessionId)
            factoryFailure?.let { throw it }
            return flow {
                active++
                try {
                    emitAll(querySource())
                } finally {
                    active--
                }
            }
        }

        override suspend fun append(
            sessionId: String,
            observation: VehicleObservation,
        ): VehicleObservationAppendResult = error("Read-only detail must not append observations")
    }

    @OptIn(InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
    private class SilentLiveState<T>(
        initial: T,
        private val backing: MutableStateFlow<T> = MutableStateFlow(initial),
    ) : StateFlow<T> by backing {
        @Volatile private var current = initial
        override val value: T get() = current

        fun setWithoutEmission(value: T) {
            current = value
        }

        fun emit(value: T) {
            current = value
            backing.value = value
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun lateRowsOnCancellation(): Flow<List<SessionVehicleObservation>> =
        object : Flow<List<SessionVehicleObservation>> {
            override suspend fun collect(collector: FlowCollector<List<SessionVehicleObservation>>) {
                collector.emit(listOf(row(1), row(2), row(3)))
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { collector.emit(listOf(row(99))) }
                }
            }
        }

    private companion object {
        fun session(id: String) =
            PracticeSession(
                id,
                PracticeSelection("goal", "course", 1, 1, "task"),
                "stored goal",
                "stored course",
                "stored task",
                CourseReviewStatus.DEMO,
                100,
                200,
            )

        fun row(
            id: Long,
            sessionId: String = "a",
            time: Long = 100,
        ) = SessionVehicleObservation(id, sessionId, VehicleObservation(time, parked()))

        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        fun driving() = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))

        fun unrestricted() = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
    }
}
