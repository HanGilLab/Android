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
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import io.github.hangillab.hangil.core.domain.SessionReviewSample
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.feature.home.LocationHistoryUiState.FailureReason
import io.github.hangillab.hangil.feature.home.LocationHistoryUiState.Token
import kotlinx.coroutines.CompletableDeferred
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
import io.github.hangillab.hangil.feature.home.SessionHistoryMemoUiState as MemoState

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
            assertFalse(f.model.openLinkedSample(old.token, SessionReviewSample.from(old.run, checkNotNull(old.event))))
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
            assertFalse(f.model.openLinkedSample(old.token, SessionReviewSample.from(old.run, checkNotNull(old.event))))
            f.vehicle.value = vehicleState(GearPosition.PARK)
            runCurrent()
            assertFalse(f.model.canRead(old.token))
            assertFalse(f.model.openLinkedSample(old.token, SessionReviewSample.from(old.run, checkNotNull(old.event))))
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
            assertFalse(f.model.openLinkedSample(old.token, SessionReviewSample.from(old.run, checkNotNull(old.event))))
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
            val anchor = SessionReviewSample.from(current.run, checkNotNull(current.event))
            assertFalse(f.model.openLinkedSample(old.token, anchor))
            assertTrue(f.model.openLinkedSample(current.token, anchor))
            assertEquals(2, f.recordings.calls)
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
            val anchor = SessionReviewSample.from(old.run, checkNotNull(old.event))
            assertFalse(f.model.openLinkedSample(old.token, anchor))
            assertTrue(f.model.openLinkedSample(current.token, anchor))
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

    @Test fun `session memo is lazy and its readonly receipt survives point and run selection`() =
        runTest {
            val f = Fixture()
            val first = run("first")
            val second = run("second")
            f.recordings.values.value =
                LocationRecordingSnapshot(
                    listOf(first, second),
                    listOf(event(1, first.runId), event(2, second.runId), event(3, second.runId, ordinal = 2, elapsed = 102)),
                )
            val parent = open(f)
            val selected = parent.ready()
            assertTrue(f.model.canRevealSessionMemo(selected.source, selected.token))
            f.model.observeSessionMemo(selected.source, selected.token, f.host::get)
            runCurrent()
            assertEquals(0, f.memos.calls)

            val memo = openMemo(f, selected)
            val ready = memo.value as MemoState.Ready
            assertEquals(selected.source, ready.source)
            assertEquals(selected.token.queryNonce, ready.historyGeneration)
            assertEquals(note(), ready.memo)
            assertFalse(ready.canEdit)
            assertTrue(f.model.canReadSessionMemo(ready.token))
            assertEquals(listOf(selected.source.sessionId), f.memos.requested)

            assertTrue(f.model.previousEvent(selected.token, selected.run.runId, selected.event?.id))
            runCurrent()
            val previousPoint = parent.ready()
            assertNotEquals(selected.token.revision, previousPoint.token.revision)
            assertTrue(f.model.canRevealSessionMemo(previousPoint.source, previousPoint.token))
            assertTrue(f.model.previousRun(previousPoint.token, previousPoint.run.runId))
            runCurrent()
            assertEquals("first", parent.ready().run.runId)
            assertEquals(ready, memo.value)
            assertTrue(f.model.canReadSessionMemo(ready.token))
            assertEquals(1, f.memos.calls)
            assertEquals(1, f.memos.active)
            assertEquals(0, f.memos.writes)
            assertEquals(0, f.recordings.writes)
        }

    @Test fun `memo absence read failure and explicit retry preserve their distinct meanings`() =
        runTest {
            val fail = CompletableDeferred<Unit>()
            val f =
                Fixture().apply {
                    memos.factory =
                        {
                            flow {
                                emit(null)
                                fail.await()
                                throw IOException("Controlled memo read")
                            }
                        }
                }
            val parent = open(f)
            val memo = openMemo(f, parent.ready())
            val empty = memo.value as MemoState.Empty
            assertFalse(empty.canCreate)
            assertEquals(1, f.memos.calls)

            fail.complete(Unit)
            runCurrent()
            val failed = memo.value as MemoState.Failed
            assertEquals(MemoState.FailureReason.MEMO_READ, failed.reason)
            f.memos.factory = { f.memos.values }
            runCurrent()
            assertEquals(failed, memo.value)
            assertEquals(1, f.memos.calls)
            assertTrue(f.model.retrySessionMemo(failed.token))
            runCurrent()
            val recovered = memo.value as MemoState.Ready
            assertEquals(note(), recovered.memo)
            assertFalse(recovered.canEdit)
            assertNotEquals(failed.token, recovered.token)
            assertFalse(f.model.retrySessionMemo(failed.token))
            assertEquals(2, f.memos.calls)

            f.memos.values.value = note().copy(sessionId = "another-session")
            runCurrent()
            assertEquals(MemoState.FailureReason.INVALID_MEMO, (memo.value as MemoState.Failed).reason)
            assertFalse(f.model.canReadSessionMemo(recovered.token))
            assertEquals(0, f.memos.writes)
        }

    @Test fun `parent query failure retires a retryable child before a fresh query can read again`() =
        runTest {
            val failParent = CompletableDeferred<Unit>()
            val f =
                Fixture().apply {
                    recordings.factory =
                        {
                            flow {
                                emit(snapshot())
                                failParent.await()
                                throw IOException("Controlled location read")
                            }
                        }
                    memos.factory = { flow { throw IOException("Controlled memo read") } }
                }
            val parent = open(f)
            val oldParent = parent.ready()
            val oldMemo = openMemo(f, oldParent)
            val failedMemo = oldMemo.value as MemoState.Failed
            assertTrue(f.model.canReadSessionMemo(failedMemo.token))

            failParent.complete(Unit)
            runCurrent()
            val failedParent = parent.value as LocationHistoryUiState.Failed
            assertFalse(f.model.canRevealSessionMemo(oldParent.source, oldParent.token))
            assertFalse(f.model.canReadSessionMemo(failedMemo.token))
            assertFalse(f.model.retrySessionMemo(failedMemo.token))
            assertEquals(1, f.memos.calls)

            f.recordings.factory = { f.recordings.values }
            f.memos.factory = { f.memos.values }
            assertTrue(f.model.retry(failedParent.token))
            runCurrent()
            val freshParent = parent.ready()
            assertNotEquals(oldParent.token.queryNonce, freshParent.token.queryNonce)
            assertFalse(f.model.canRevealSessionMemo(oldParent.source, oldParent.token))
            val freshMemo = openMemo(f, freshParent)
            val ready = freshMemo.value as MemoState.Ready
            assertEquals(note(), ready.memo)
            assertFalse(f.model.retrySessionMemo(failedMemo.token))
            assertTrue(f.model.canReadSessionMemo(ready.token))
            assertEquals(2, f.memos.calls)
            assertEquals(1, f.memos.active)
            assertEquals(0, f.memos.writes)
        }

    @Test fun `source replacement access loss and stopped collectors cancel the memo query`() =
        runTest {
            val retirements =
                listOf<(Fixture, Display) -> Unit>(
                    { f, _ -> f.sessions.values.value = listOf(source().copy(taskTitle = "Changed task")) },
                    { f, _ -> f.vehicle.value = vehicleState(GearPosition.DRIVE) },
                    { f, parent ->
                        f.host.set(false)
                        assertFalse(f.model.canRead(parent.ready().token))
                    },
                    { _, parent -> parent.job.cancel() },
                )
            for (retire in retirements) {
                val f = Fixture()
                val parent = open(f)
                val selected = parent.ready()
                val memo = openMemo(f, selected)
                val ready = memo.value as MemoState.Ready
                assertEquals(1, f.memos.active)
                retire(f, parent)
                runCurrent()
                assertFalse(f.model.canReadSessionMemo(ready.token))
                assertFalse(f.model.retrySessionMemo(ready.token))
                assertFalse(f.model.canRevealSessionMemo(selected.source, selected.token))
                assertEquals(0, f.memos.active)
                assertEquals(1, f.memos.calls)
                assertEquals(0, f.memos.writes)
                memo.job.cancelAndJoin()
                parent.job.cancelAndJoin()
            }
        }

    @Test fun `linked sample opens its exact older run and duplicate coordinate event without reads or writes`() =
        runTest {
            val f = Fixture()
            val first = run("first")
            val last = run("last")
            val events =
                listOf(
                    event(1, first.runId),
                    event(2, first.runId, ordinal = 2),
                    event(3, last.runId).copy(reading = LocationUpdateReading.Empty(101)),
                )
            val stored = LocationRecordingSnapshot(listOf(first, last), events)
            f.recordings.values.value = stored
            // Reading an ended record does not require ending a different active practice while parked.
            f.sessions.values.value = listOf(source().copy(sessionId = "other-active", endedAtEpochMillis = null), source())
            val display = open(f)
            val old = display.ready()
            assertEquals("last", old.run.runId)
            val linked =
                SessionReviewSample.from(
                    first.copy(
                        status = LocationRecordingRunStatus.OPEN,
                        stopReason = null,
                        endedAtEpochMillis = null,
                        endedAtElapsedRealtimeMillis = null,
                    ),
                    events[1],
                )
            assertEquals((events[0].reading as LocationUpdateReading.Sample).sample, linked.sample)
            assertTrue(f.model.openLinkedSample(old.token, linked))
            runCurrent()
            val current = display.ready()
            assertEquals("first", current.run.runId)
            assertEquals(2L, current.event?.id)
            assertNotEquals(old.token, current.token)
            assertFalse(f.model.openLinkedSample(old.token, linked))
            assertTrue(f.model.openLinkedSample(current.token, linked))
            runCurrent()
            assertEquals("An already selected exact sample is a read-only no-op", current, display.ready())
            assertEquals(stored, f.recordings.values.value)
            assertEquals(1, f.sessions.calls)
            assertEquals(1, f.recordings.calls)
            assertEquals(0, f.recordings.writes)
            assertEquals(0, f.memos.calls)
            assertEquals(0, f.memos.writes)
        }

    @Test fun `linked sample navigation rejects live UX denial before policy delivery`() =
        runTest {
            val f = Fixture()
            val display = open(f)
            val before = display.ready()
            val anchor = SessionReviewSample.from(before.run, checkNotNull(before.event))
            f.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.RESTRICTED)
            assertFalse(f.model.openLinkedSample(before.token, anchor))
            runCurrent()
            assertTrue(display.value is LocationHistoryUiState.Locked)
            f.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
            runCurrent()
            assertFalse(f.model.openLinkedSample(before.token, anchor))
            assertEquals(1, f.recordings.calls)
            assertEquals(0, f.recordings.writes)
        }

    @Test fun `missing or changed linked event and immutable run owners never select a substitute`() =
        runTest {
            val f = Fixture()
            val display = open(f)
            val before = display.ready()
            val anchor = SessionReviewSample.from(before.run, checkNotNull(before.event))
            val alteredPayload = anchor.event.copy(reading = LocationUpdateReading.Sample(anchor.sample.copy(latitude = 13.5)))
            val processId = anchor.processId
            val wall = anchor.startedAtEpochMillis
            val elapsed = anchor.startedAtElapsedRealtimeMillis
            val invalid =
                listOf(
                    SessionReviewSample(anchor.event.copy(id = 999), processId, wall, elapsed),
                    SessionReviewSample(anchor.event.copy(runId = "missing-run"), processId, wall, elapsed),
                    SessionReviewSample(anchor.event.copy(sessionId = "foreign"), processId, wall, elapsed),
                    SessionReviewSample(alteredPayload, processId, wall, elapsed),
                    SessionReviewSample(anchor.event, "another process", wall, elapsed),
                    SessionReviewSample(anchor.event, processId, wall + 1, elapsed),
                    SessionReviewSample(anchor.event, processId, wall, elapsed - 1),
                )
            invalid.forEach { candidate ->
                assertFalse(f.model.openLinkedSample(before.token, candidate))
                runCurrent()
                assertEquals(before, display.ready())
            }
            assertEquals(1, f.recordings.calls)
            assertEquals(0, f.recordings.writes)
            assertEquals(0, f.memos.calls)
        }

    private fun TestScope.openMemo(
        f: Fixture,
        parent: LocationHistoryUiState.Ready,
    ): MemoDisplay {
        val states =
            MutableStateFlow<MemoState>(
                MemoState.Loading(parent.source, parent.token.queryNonce, MemoState.Token(0, 0, 0)),
            )
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model.observeSessionMemo(parent.source, parent.token, f.host::get).collect { states.value = it }
            }
        runCurrent()
        return MemoDisplay(states, job)
    }

    private class MemoDisplay(
        private val states: MutableStateFlow<MemoState>,
        val job: Job,
    ) {
        val value get() = states.value
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
        val memos = Memos()
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
                memos,
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

    private class Memos : SessionMemoRepository {
        val values = MutableStateFlow<SessionMemo?>(note())
        var factory: () -> Flow<SessionMemo?> = { values }
        val requested = mutableListOf<String>()
        var calls = 0
        var active = 0
        var writes = 0

        override fun observe(sessionId: String): Flow<SessionMemo?> =
            flow {
                requested += sessionId
                calls++
                active++
                try {
                    emitAll(factory())
                } finally {
                    active--
                }
            }

        override suspend fun save(
            sessionId: String,
            text: String,
            expectedVersion: String?,
            canCommit: () -> Boolean,
        ): SessionMemoWriteResult {
            writes++
            error("Unexpected memo write")
        }

        override suspend fun delete(
            sessionId: String,
            expectedVersion: String,
            canCommit: () -> Boolean,
        ): SessionMemoWriteResult {
            writes++
            error("Unexpected memo deletion")
        }
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
        private fun note() = SessionMemo("source", "  Whole-session user note\nwith its original whitespace  ", "version")

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
