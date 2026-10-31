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
import io.github.hangillab.hangil.core.domain.RecordingRunOpenResult
import io.github.hangillab.hangil.core.domain.RecordingRunRepository
import io.github.hangillab.hangil.core.domain.RecordingRunStatus
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionDeletionRepository
import io.github.hangillab.hangil.core.domain.SessionDeletionResult
import io.github.hangillab.hangil.core.domain.SessionDeletionTarget
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import io.github.hangillab.hangil.core.domain.VehicleObservationRepository
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleRecordingRun
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.feature.home.SessionHistoryRecordingRunUiState.FailureReason
import io.github.hangillab.hangil.feature.home.SessionHistoryRecordingRunUiState.Token
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

/** Controlled repositories exercise the real history receipt boundary; no SDK or service is started. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionHistoryRecordingRunViewModelTest {
    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `an uncollected run detail does not query sessions or runs`() =
        runTest {
            val f = Fixture()
            f.model.observeStoredRecordingRuns(session("a"), 1, f.host::get)
            runCurrent()
            assertEquals(0, f.sessions.calls)
            assertEquals(0, f.runs.calls)
            assertFalse(f.model.canReadStoredRecordingRuns(Token(0, 0, 0)))
            assertEquals(0, f.runs.writes)
        }

    @Test fun `a run detail without a parent reader cannot query`() =
        runTest {
            val f = Fixture()
            val d = open(f, session("a"), 1)
            assertTrue(d.value is SessionHistoryRecordingRunUiState.Unavailable)
            assertEquals(0, f.runs.calls)
        }

    @Test fun `D and unknown UX never authorize a run query`() =
        runTest {
            val denied = Fixture()
            denied.vehicle.emit(driving())
            start(denied)
            open(denied, session("a"), 1)
            assertEquals(SessionHistoryUiState.Locked, denied.model.uiState.value)
            assertEquals(0, denied.runs.calls)
            val unknown = Fixture()
            unknown.restrictions.emit(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNKNOWN))
            start(unknown)
            open(unknown, session("a"), 1)
            assertEquals(0, unknown.runs.calls)
        }

    @Test fun `the full source and current history generation are required`() =
        runTest {
            val f = Fixture()
            start(f)
            val original = parent(f)
            assertTrue(
                open(
                    f,
                    original.session.copy(taskTitle = "other snapshot"),
                    original.generation,
                ).value is SessionHistoryRecordingRunUiState.Unavailable,
            )
            assertTrue(f.model.retry())
            runCurrent()
            assertTrue(open(f, original.session, original.generation).value is SessionHistoryRecordingRunUiState.Unavailable)
            assertEquals(0, f.runs.calls)
        }

    @Test fun `selected older source reads only its runs without another sessions subscription`() =
        runTest {
            val f = Fixture()
            start(f)
            val newest = parent(f)
            assertTrue(f.model.older(newest.session.sessionId, newest.generation))
            runCurrent()
            f.runs.values.value = listOf(run("older-first", sessionId = "b"), run("older-last", sessionId = "b"))
            val d = open(f)
            assertEquals("b", ready(d).source.sessionId)
            assertEquals("older-last", ready(d).run.runId)
            assertEquals(listOf("b"), f.runs.requested)
            assertEquals(1, f.sessions.calls)
            assertEquals(1, f.runs.active)
        }

    @Test fun `creation order is preserved despite IDs and wall clocks moving backwards`() =
        runTest {
            val f = Fixture()
            start(f)
            val rows = listOf(run("z", start = 900, end = 950), run("a", start = 110, end = 115))
            f.runs.values.value = rows
            val d = open(f)
            assertEquals(rows, ready(d).runs)
            assertEquals("a", ready(d).run.runId)
            assertEquals(110L, ready(d).run.startedAtEpochMillis)
        }

    @Test fun `all stored statuses and unknown terminal times remain unchanged`() =
        runTest {
            val f = Fixture()
            start(f)
            val rows =
                listOf(
                    run("stopped-unknown", end = null),
                    run("failed-unknown", status = RecordingRunStatus.FAILED, end = null),
                    run("interrupted", status = RecordingRunStatus.INTERRUPTED),
                    run("open", status = RecordingRunStatus.OPEN),
                )
            f.runs.values.value = rows
            val d = open(f)
            assertEquals(rows, ready(d).runs)
            assertTrue(ready(d).hasInterrupted)
            assertTrue(ready(d).runs.all { it.endedAtEpochMillis == null })
            assertEquals(0, f.runs.writes)
        }

    @Test fun `an older interruption remains visible when a newer run stopped`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            assertEquals(RecordingRunStatus.STOPPED, ready(d).run.status)
            assertTrue(ready(d).hasInterrupted)
            val current = ready(d)
            assertTrue(f.model.previousStoredRecordingRun(current.token, current.run.runId))
            runCurrent()
            assertEquals(RecordingRunStatus.INTERRUPTED, ready(d).run.status)
            assertTrue(ready(d).hasInterrupted)
        }

    @Test fun `legacy empty run data and controlled stored observations have independent states`() =
        runTest {
            val f = Fixture()
            start(f)
            f.runs.values.value = emptyList()
            val d = open(f)
            var observation: SessionHistoryObservationUiState? = null
            val source = parent(f)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model.observeStoredObservations(source.session, source.generation, f.host::get).collect { observation = it }
            }
            runCurrent()
            assertTrue(d.value is SessionHistoryRecordingRunUiState.Empty)
            assertTrue(observation is SessionHistoryObservationUiState.Ready)
            assertEquals(1, (observation as SessionHistoryObservationUiState.Ready).observations.size)
            assertEquals(0, f.runs.writes)
        }

    @Test fun `existing summary observation and memo reads never start the run reader`() =
        runTest {
            val f = Fixture()
            start(f)
            val source = parent(f)
            assertTrue(f.model.canRevealSummary(source.session.sessionId, source.generation))
            var observations: SessionHistoryObservationUiState? = null
            var memo: SessionHistoryMemoUiState? = null
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model.observeStoredObservations(source.session, source.generation, f.host::get).collect { observations = it }
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model.observeStoredMemo(source.session, source.generation, f.host::get).collect { memo = it }
            }
            runCurrent()
            assertTrue(observations is SessionHistoryObservationUiState.Ready)
            assertTrue(memo is SessionHistoryMemoUiState.Empty)
            assertEquals(0, f.runs.calls)
            assertEquals(0, f.runs.writes)
        }

    @Test fun `run failure cannot clear the current SDK observations memo or history source`() =
        runTest {
            val f = Fixture()
            start(f)
            val source = parent(f)
            var observations: SessionHistoryObservationUiState? = null
            var memo: SessionHistoryMemoUiState? = null
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model.observeStoredObservations(source.session, source.generation, f.host::get).collect { observations = it }
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model.observeStoredMemo(source.session, source.generation, f.host::get).collect { memo = it }
            }
            runCurrent()
            val stored = observations
            f.runs.factoryFailure = IOException("controlled run read failure")
            val d = open(f)
            assertEquals(FailureReason.RUN_READ, failed(d).reason)
            assertEquals(stored, observations)
            assertTrue(memo is SessionHistoryMemoUiState.Empty)
            assertEquals(source, parent(f))
        }

    @Test fun `paging validates the displayed run and rotates receipt without a query`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val original = ready(d)
            assertFalse(f.model.previousStoredRecordingRun(original.token, "not displayed"))
            assertTrue(f.model.previousStoredRecordingRun(original.token, original.run.runId))
            runCurrent()
            val previous = ready(d)
            assertEquals("interrupted", previous.run.runId)
            assertNotEquals(original.token, previous.token)
            assertFalse(f.model.previousStoredRecordingRun(original.token, original.run.runId))
            assertFalse(f.model.nextStoredRecordingRun(previous.token, "not displayed"))
            assertTrue(f.model.nextStoredRecordingRun(previous.token, previous.run.runId))
            runCurrent()
            assertEquals("last", ready(d).run.runId)
            assertEquals(1, f.runs.calls)
        }

    @Test fun `paging bounds do not mutate the receipt or selected run`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val last = ready(d)
            assertFalse(f.model.nextStoredRecordingRun(last.token, last.run.runId))
            assertEquals(last, ready(d))
            repeat(2) {
                val current = ready(d)
                assertTrue(f.model.previousStoredRecordingRun(current.token, current.run.runId))
                runCurrent()
            }
            val first = ready(d)
            assertFalse(first.canPrevious)
            assertTrue(first.canNext)
            assertFalse(f.model.previousStoredRecordingRun(first.token, first.run.runId))
            assertEquals(first, ready(d))
        }

    @Test fun `new run receipt preserves selection but rejects actions captured before it`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val original = ready(d)
            f.runs.values.value = f.runs.values.value + run("newest")
            runCurrent()
            val current = ready(d)
            assertEquals(original.run.runId, current.run.runId)
            assertNotEquals(original.token, current.token)
            assertFalse(f.model.nextStoredRecordingRun(original.token, original.run.runId))
            assertTrue(f.model.nextStoredRecordingRun(current.token, current.run.runId))
            runCurrent()
            assertEquals("newest", ready(d).run.runId)
        }

    @Test fun `a copied run receipt is not mutated by changes to the repository list`() =
        runTest {
            val f = Fixture()
            start(f)
            val original = mutableListOf(run("first"), run("last"))
            f.runs.values.value = original
            val d = open(f)
            original.clear()
            assertEquals(listOf("first", "last"), ready(d).runs.map { it.runId })
        }

    @Test fun `a removed selected run fails until exact explicit retry accepts the new list`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            f.runs.values.value = listOf(run("replacement"))
            runCurrent()
            val missing = failed(d)
            assertEquals(FailureReason.SELECTED_RUN_MISSING, missing.reason)
            assertEquals("last", missing.selectedRunId)
            assertEquals(0, f.runs.active)
            assertFalse(f.model.retryStoredRecordingRuns(missing.token, "other"))
            assertTrue(f.model.retryStoredRecordingRuns(missing.token, missing.selectedRunId))
            runCurrent()
            assertEquals("replacement", ready(d).run.runId)
            assertFalse(f.model.retryStoredRecordingRuns(missing.token, missing.selectedRunId))
            assertEquals(2, f.runs.calls)
        }

    @Test fun `wrong sessions duplicate or blank identities fail without filtering`() =
        runTest {
            listOf(
                listOf(run("valid"), run("foreign", sessionId = "b")),
                listOf(run("duplicate"), run("duplicate")),
                listOf(run("")),
                listOf(run("blank-process", processId = " ")),
            ).forEach { assertInvalid(it) }
        }

    @Test fun `invalid time and status combinations fail rather than normalizing values`() =
        runTest {
            listOf(
                listOf(run("zero", start = 0)),
                listOf(run("backwards", start = 150, end = 149)),
                listOf(run("open-end", status = RecordingRunStatus.OPEN, end = 120)),
                listOf(run("interrupted-end", status = RecordingRunStatus.INTERRUPTED, end = 120)),
                listOf(run("open-one", status = RecordingRunStatus.OPEN), run("open-two", status = RecordingRunStatus.OPEN)),
            ).forEach { assertInvalid(it) }
        }

    @Test fun `a completed run stream fails even after a valid empty emission`() =
        runTest {
            val f = Fixture()
            f.runs.querySource = { flow { emit(emptyList()) } }
            start(f)
            val d = open(f)
            assertEquals(FailureReason.RUN_STREAM_COMPLETED, failed(d).reason)
            assertEquals(0, f.runs.active)
            f.vehicle.emit(parked().copy(brakePressed = VehicleReading.Available(true)))
            runCurrent()
            assertEquals(1, f.runs.calls)
            assertEquals(FailureReason.RUN_STREAM_COMPLETED, failed(d).reason)
        }

    @Test fun `factory failure and independent source cancellation remain explicit failures`() =
        runTest {
            val f = Fixture()
            f.runs.factoryFailure = IOException("controlled factory failure")
            start(f)
            val d = open(f)
            assertEquals(FailureReason.RUN_READ, failed(d).reason)
            assertTrue(f.model.uiState.value is SessionHistoryUiState.Ready)
            f.runs.factoryFailure = null
            f.runs.querySource = { flow { throw CancellationException("independent run source cancellation") } }
            val failure = failed(d)
            assertTrue(f.model.retryStoredRecordingRuns(failure.token, failure.selectedRunId))
            runCurrent()
            assertEquals(FailureReason.RUN_READ, failed(d).reason)
            assertTrue(f.model.uiState.value is SessionHistoryUiState.Ready)
            assertEquals(0, f.runs.active)
        }

    @Test fun `selection movement closes old runs and old paging cannot affect another source`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            val source = parent(f)
            assertTrue(f.model.older(source.session.sessionId, source.generation))
            runCurrent()
            assertTrue(d.value is SessionHistoryRecordingRunUiState.Unavailable)
            assertEquals(0, f.runs.active)
            assertFalse(f.model.previousStoredRecordingRun(old.token, old.run.runId))
            f.runs.values.value = listOf(run("other", sessionId = "b"))
            val fresh = open(f)
            assertEquals("b", ready(fresh).source.sessionId)
            assertEquals("other", ready(fresh).run.runId)
        }

    @Test fun `same ID with changed full source invalidates old run actions`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            f.sessions.values.value = listOf(session("a").copy(taskTitle = "replaced source"), session("b"))
            runCurrent()
            assertTrue(d.value is SessionHistoryRecordingRunUiState.Unavailable)
            assertFalse(f.model.nextStoredRecordingRun(old.token, old.run.runId))
            val fresh = open(f)
            assertEquals("replaced source", ready(fresh).source.taskTitle)
            assertNotEquals(old.token, ready(fresh).token)
        }

    @Test fun `receiving the same full session snapshot still retires the old generation`() =
        runTest {
            val f = Fixture()
            val again = CompletableDeferred<Unit>()
            f.sessions.querySource = {
                flow {
                    emit(listOf(session("a"), session("b")))
                    again.await()
                    emit(listOf(session("a"), session("b")))
                    awaitCancellation()
                }
            }
            start(f)
            val d = open(f)
            val old = ready(d)
            again.complete(Unit)
            runCurrent()
            assertNotEquals(old.historyGeneration, parent(f).generation)
            assertFalse(f.model.canReadStoredRecordingRuns(old.token))
            assertTrue(d.value is SessionHistoryRecordingRunUiState.Unavailable)
        }

    @Test fun `deleting the selected source cannot turn old runs into a successful empty result`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            f.sessions.values.value = listOf(session("b"))
            runCurrent()
            assertTrue(f.model.uiState.value is SessionHistoryUiState.SelectionChanged)
            assertTrue(d.value is SessionHistoryRecordingRunUiState.Unavailable)
            assertFalse(f.model.canReadStoredRecordingRuns(old.token))
        }

    @Test fun `parent cancellation releases runs even when the detail collector remains`() =
        runTest {
            val f = Fixture()
            val parentJob = start(f)
            val d = open(f)
            val old = ready(d)
            parentJob.cancelAndJoin()
            runCurrent()
            assertEquals(0, f.runs.active)
            assertTrue(d.value is SessionHistoryRecordingRunUiState.Unavailable)
            assertFalse(f.model.canReadStoredRecordingRuns(old.token))
        }

    @Test fun `a failed parent source cancels runs without converting failure to Empty`() =
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
            val old = ready(d)
            finish.complete(Unit)
            runCurrent()
            assertTrue(f.model.uiState.value is SessionHistoryUiState.Failed)
            assertTrue(d.value is SessionHistoryRecordingRunUiState.Unavailable)
            assertFalse(f.model.canReadStoredRecordingRuns(old.token))
            assertEquals(0, f.runs.active)
        }

    @Test fun `a throwing host guard denies without querying or inventing an empty list`() =
        runTest {
            val f = Fixture()
            start(f)
            val source = parent(f)
            var detail: SessionHistoryRecordingRunUiState? = null
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model
                    .observeStoredRecordingRuns(source.session, source.generation) {
                        error("controlled host guard failure")
                    }.collect { detail = it }
            }
            runCurrent()
            assertTrue(detail is SessionHistoryRecordingRunUiState.Unavailable)
            assertEquals(0, f.runs.calls)
            assertEquals(0, f.runs.writes)
        }

    @Test fun `closing the host retires the token before a new row is emitted`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            f.host.set(false)
            assertFalse(f.model.canReadStoredRecordingRuns(old.token))
            f.host.set(true)
            assertFalse(f.model.previousStoredRecordingRun(old.token, old.run.runId))
            runCurrent()
            assertEquals(0, f.runs.active)
            assertTrue(d.value is SessionHistoryRecordingRunUiState.Unavailable)
            assertNotEquals(old.token, ready(open(f)).token)
        }

    @Test fun `cancelling the detail closes its query and old token`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            d.job.cancelAndJoin()
            runCurrent()
            assertEquals(0, f.runs.active)
            assertFalse(f.model.canReadStoredRecordingRuns(old.token))
        }

    @Test fun `silent D denial cannot be undone by silently restoring P`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            f.vehicle.setWithoutEmission(driving())
            assertFalse(f.model.previousStoredRecordingRun(old.token, old.run.runId))
            f.vehicle.setWithoutEmission(parked())
            assertFalse(f.model.canReadStoredRecordingRuns(old.token))
            assertFalse(f.model.nextStoredRecordingRun(old.token, old.run.runId))
            runCurrent()
            assertEquals(0, f.runs.active)
            assertTrue(d.value is SessionHistoryRecordingRunUiState.Locked)
            assertEquals(1, f.runs.calls)
        }

    @Test fun `a current canRead receipt observing denied UX stays retired after recovery`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            f.restrictions.setWithoutEmission(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.RESTRICTED))
            assertFalse(f.model.canReadStoredRecordingRuns(old.token))
            f.restrictions.setWithoutEmission(unrestricted())
            assertFalse(f.model.canReadStoredRecordingRuns(old.token))
            runCurrent()
            assertEquals(0, f.runs.active)
            assertEquals(1, f.runs.calls)
        }

    @Test fun `a failed retry observing silent denial cannot borrow a fresh P context`() =
        runTest {
            val f = Fixture()
            f.runs.factoryFailure = IOException("controlled failure")
            start(f)
            val d = open(f)
            val old = failed(d)
            f.vehicle.setWithoutEmission(driving())
            assertFalse(f.model.retryStoredRecordingRuns(old.token, old.selectedRunId))
            f.vehicle.setWithoutEmission(parked())
            assertFalse(f.model.retryStoredRecordingRuns(old.token, old.selectedRunId))
            runCurrent()
            assertEquals(1, f.runs.calls)
            assertEquals(0, f.runs.active)
        }

    @Test fun `an old opening guard cannot borrow a recovered parent receipt`() =
        runTest {
            val f = Fixture()
            start(f)
            val old = parent(f)
            f.vehicle.setWithoutEmission(driving())
            assertFalse(f.model.canRevealStoredRecordingRuns(old.session.sessionId, old.generation))
            f.vehicle.setWithoutEmission(parked())
            assertFalse(f.model.canRevealStoredRecordingRuns(old.session.sessionId, old.generation))
            runCurrent()
            assertEquals(0, f.runs.calls)
        }

    @Test fun `run receipt arriving during silent D locks without an automatic requery`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            f.vehicle.setWithoutEmission(driving())
            f.runs.values.value = f.runs.values.value + run("newest")
            runCurrent()
            assertEquals(SessionHistoryUiState.Locked, f.model.uiState.value)
            assertTrue(d.value is SessionHistoryRecordingRunUiState.Locked)
            assertEquals(1, f.runs.calls)
            assertEquals(0, f.runs.active)
        }

    @Test fun `late rows from a cancelled query cannot overwrite the replacement source`() =
        runTest {
            val f = Fixture()
            f.runs.querySource = { lateRunsOnCancellation() }
            start(f)
            val old = open(f)
            val receipt = ready(old)
            val source = parent(f)
            f.runs.querySource = { f.runs.values }
            f.runs.values.value = listOf(run("fresh", sessionId = "b"))
            assertTrue(f.model.older(source.session.sessionId, source.generation))
            runCurrent()
            val fresh = open(f)
            assertEquals("fresh", ready(fresh).run.runId)
            assertEquals("b", ready(fresh).source.sessionId)
            assertFalse(f.model.nextStoredRecordingRun(receipt.token, receipt.run.runId))
            assertEquals(1, f.runs.active)
        }

    @Test fun `an old failure retry cannot cancel a replacement reader`() =
        runTest {
            val f = Fixture()
            f.runs.factoryFailure = IOException("controlled failure")
            start(f)
            val old = failed(open(f))
            val source = parent(f)
            assertTrue(f.model.older(source.session.sessionId, source.generation))
            runCurrent()
            f.runs.factoryFailure = null
            f.runs.values.value = listOf(run("fresh", sessionId = "b"))
            val fresh = open(f)
            val current = ready(fresh)
            assertFalse(f.model.retryStoredRecordingRuns(old.token, old.selectedRunId))
            assertEquals(current, ready(fresh))
            assertEquals(1, f.runs.active)
        }

    private fun TestScope.assertInvalid(rows: List<VehicleRecordingRun>) {
        val f = Fixture()
        start(f)
        f.runs.values.value = rows
        val d = open(f)
        assertEquals(FailureReason.INVALID_RUNS, failed(d).reason)
        assertEquals(0, f.runs.active)
        assertEquals(0, f.runs.writes)
    }

    private fun TestScope.start(f: Fixture): Job {
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.model.uiState.collect {} }
        runCurrent()
        return job
    }

    private fun parent(f: Fixture) = f.model.uiState.value as SessionHistoryUiState.Ready

    private fun ready(detail: Detail) = detail.value as SessionHistoryRecordingRunUiState.Ready

    private fun failed(detail: Detail) = detail.value as SessionHistoryRecordingRunUiState.Failed

    private fun TestScope.open(
        f: Fixture,
        source: PracticeSession = parent(f).session,
        generation: Long = parent(f).generation,
    ): Detail {
        val detail = Detail()
        detail.job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model.observeStoredRecordingRuns(source, generation, f.host::get).collect { detail.value = it }
            }
        runCurrent()
        return detail
    }

    private class Detail {
        lateinit var job: Job
        var value: SessionHistoryRecordingRunUiState? = null
    }

    private class Fixture {
        val vehicle = SilentLiveState(parked())
        val restrictions = SilentLiveState(unrestricted())
        val sessions = Sessions()
        val runs = Runs()
        val observations = Observations()
        val memos = Memos()
        val host = AtomicBoolean(true)
        val model =
            SessionHistoryViewModel(
                object : VehicleGateway {
                    override val state = vehicle
                },
                object : DrivingRestrictionsProvider {
                    override val restrictions = this@Fixture.restrictions
                },
                sessions,
                object : SessionDeletionRepository {
                    override suspend fun prepare(
                        expected: PracticeSession,
                        canRead: () -> Boolean,
                    ): SessionDeletionPreparation = error("Read-only run history must not prepare deletion")

                    override suspend fun delete(
                        target: SessionDeletionTarget,
                        canCommit: () -> Boolean,
                    ): SessionDeletionResult = error("Read-only run history must not delete sessions")
                },
                observations,
                memos,
                runs,
                UnexpectedHistoryCourseCompletionRepository,
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
        ): SessionStartResult = error("History must not start sessions")

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult = error("History must not finish sessions")
    }

    private class Runs : RecordingRunRepository {
        val values =
            MutableStateFlow(
                listOf(run("first"), run("interrupted", status = RecordingRunStatus.INTERRUPTED), run("last")),
            )
        val requested = mutableListOf<String>()
        var calls = 0
        var active = 0
        var writes = 0
        var factoryFailure: Throwable? = null
        var querySource: () -> Flow<List<VehicleRecordingRun>> = { values }

        override fun observe(sessionId: String): Flow<List<VehicleRecordingRun>> {
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

        override suspend fun open(
            source: PracticeSession,
            runId: String,
            processId: String,
            startedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): RecordingRunOpenResult {
            writes++
            error("History must not open recording runs")
        }

        override suspend fun finish(
            runId: String,
            processId: String,
            status: RecordingRunStatus,
            endedAtEpochMillis: Long?,
        ): Boolean {
            writes++
            error("History must not finish recording runs")
        }

        override suspend fun recoverInterrupted(processId: String) {
            writes++
            error("History must not recover recording runs")
        }
    }

    private class Observations : VehicleObservationRepository {
        val values = MutableStateFlow(listOf(SessionVehicleObservation(1, "a", VehicleObservation(110, parked()))))

        override fun observe(sessionId: String): Flow<List<SessionVehicleObservation>> = values

        override suspend fun append(
            sessionId: String,
            observation: VehicleObservation,
        ): VehicleObservationAppendResult = error("History must not append observations")
    }

    private class Memos : SessionMemoRepository {
        val values = MutableStateFlow<SessionMemo?>(null)

        override fun observe(sessionId: String): Flow<SessionMemo?> = values

        override suspend fun save(
            sessionId: String,
            text: String,
            expectedVersion: String?,
            canCommit: () -> Boolean,
        ): SessionMemoWriteResult = error("History must not save memos")

        override suspend fun delete(
            sessionId: String,
            expectedVersion: String,
            canCommit: () -> Boolean,
        ): SessionMemoWriteResult = error("History must not delete memos")
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
    private fun lateRunsOnCancellation(): Flow<List<VehicleRecordingRun>> =
        object : Flow<List<VehicleRecordingRun>> {
            override suspend fun collect(collector: FlowCollector<List<VehicleRecordingRun>>) {
                collector.emit(listOf(run("first"), run("last")))
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { collector.emit(listOf(run("late"))) }
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

        fun run(
            id: String,
            sessionId: String = "a",
            start: Long = 110,
            status: RecordingRunStatus = RecordingRunStatus.STOPPED,
            end: Long? = if (status in listOf(RecordingRunStatus.OPEN, RecordingRunStatus.INTERRUPTED)) null else start + 5,
            processId: String = "stored-process",
        ) = VehicleRecordingRun(id, sessionId, processId, start, end, status)

        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        fun driving() = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))

        fun unrestricted() = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
    }
}
