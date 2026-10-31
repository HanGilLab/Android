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
import io.github.hangillab.hangil.core.domain.SessionTaskCount
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import io.github.hangillab.hangil.core.domain.VehicleObservationRepository
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SessionHistoryViewModelTest {
    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `constructing history is cold and no cursor action works without a visible reader`() =
        runTest {
            val fixture = Fixture()
            assertTrue(fixture.model.canRead())
            assertEquals(SessionHistoryUiState.Loading, fixture.model.uiState.value)
            assertFalse(fixture.model.retry())
            assertFalse(fixture.model.resetToLatest(0L))
            assertFalse(fixture.model.older("a", 1))
            assertFalse(fixture.model.newer("a", 1))
            runCurrent()
            assertEquals(0, fixture.repository.calls)
            assertEquals(0, fixture.vehicleState.subscriptionCount.value)
            assertEquals(0, fixture.restrictionState.subscriptionCount.value)
        }

    @Test fun `ended snapshots follow repository creation order despite regressing wall clocks and an active row`() =
        runTest {
            val a = session("a", started = 100, ended = 200).copy(goalTitle = "저장 당시 목표", courseTitle = "과거 코스", taskTitle = "과거 과제")
            val b = session("b", started = 4_000, ended = 5_000)
            val fixture = Fixture(listOf(session("active", ended = null), a, b))
            subscribe(fixture)
            val first = ready(fixture)
            assertEquals(a, first.session)
            assertEquals(0, first.index)
            assertEquals(2, first.total)
            assertTrue(first.canOlder)
            assertFalse(first.canNewer)
            assertTrue(fixture.model.older(first.session.sessionId, first.generation))
            val second = ready(fixture)
            assertEquals(b, second.session)
            assertEquals(1, second.index)
            assertFalse(second.canOlder)
            assertTrue(second.canNewer)
            assertEquals(0, fixture.repository.writes)
        }

    @Test fun `initial empty and active only histories are known empty ended history`() =
        runTest {
            for (records in listOf(emptyList(), listOf(session("active", ended = null)))) {
                val fixture = Fixture(records)
                val job = subscribe(fixture)
                assertEquals(SessionHistoryUiState.Empty, fixture.model.uiState.value)
                assertFalse(fixture.model.resetToLatest(0L))
                assertEquals(0, fixture.repository.writes)
                job.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `an initial empty history selects a subsequently received first ended session`() =
        runTest {
            val fixture = Fixture(emptyList())
            subscribe(fixture)
            fixture.repository.values.value = listOf(session("first"))
            runCurrent()
            assertEquals("first", ready(fixture).session.sessionId)
        }

    @Test fun `unknown denied disconnected driving or restricted access never opens the repository`() =
        runTest {
            val blocked =
                listOf(
                    parked().copy(connection = ConnectionState.DISCONNECTED) to unrestricted(),
                    parked().copy(connection = ConnectionState.PERMISSION_DENIED) to unrestricted(),
                    parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE)) to unrestricted(),
                    parked().copy(gear = VehicleReading.Unknown) to unrestricted(),
                    parked().copy(gear = VehicleReading.PermissionDenied) to unrestricted(),
                    parked().copy(gear = VehicleReading.Unsupported) to unrestricted(),
                    parked().copy(gear = VehicleReading.Error) to unrestricted(),
                    parked() to unrestricted().copy(parkingState = ParkingState.UNKNOWN),
                    parked() to unrestricted().copy(uxRestrictionState = UxRestrictionState.UNKNOWN),
                    parked() to unrestricted().copy(uxRestrictionState = UxRestrictionState.RESTRICTED),
                )
            for ((vehicle, restrictions) in blocked) {
                val fixture = Fixture()
                fixture.vehicleState.value = vehicle
                fixture.restrictionState.value = restrictions
                val job = subscribe(fixture)
                assertFalse(fixture.model.canRead())
                assertEquals(SessionHistoryUiState.Locked, fixture.model.uiState.value)
                assertFalse(fixture.model.retry())
                assertFalse(fixture.model.resetToLatest(0L))
                assertEquals(0, fixture.repository.calls)
                job.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `live D fences every action before the displayed ready state recombines`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            val previous = ready(fixture)
            fixture.vehicleState.value = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))
            assertEquals(previous, fixture.model.uiState.value)
            assertFalse(fixture.model.older(previous.session.sessionId, previous.generation))
            assertFalse(fixture.model.newer(previous.session.sessionId, previous.generation))
            assertFalse(fixture.model.retry())
            assertFalse(fixture.model.resetToLatest(0L))
            runCurrent()
            assertEquals(SessionHistoryUiState.Locked, fixture.model.uiState.value)
            assertEquals(0, fixture.repository.active)
        }

    @Test fun `returning to parked retains the selected ID only after a fresh read and new generation`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            val a = ready(fixture)
            assertTrue(fixture.model.older(a.session.sessionId, a.generation))
            val b = ready(fixture)
            val delayed = CompletableDeferred<List<PracticeSession>>()
            fixture.repository.source = {
                flow {
                    emit(delayed.await())
                    awaitCancellation()
                }
            }
            fixture.vehicleState.value = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))
            runCurrent()
            fixture.vehicleState.value = parked()
            runCurrent()
            assertEquals(SessionHistoryUiState.Loading, fixture.model.uiState.value)
            assertFalse(fixture.model.newer(b.session.sessionId, b.generation))
            delayed.complete(fixture.repository.values.value)
            runCurrent()
            assertEquals("b", ready(fixture).session.sessionId)
            assertNotEquals(b.generation, ready(fixture).generation)
            assertEquals(1, fixture.repository.maximumActive)
        }

    @Test fun `parked gear alone cannot resume history while UX remains restricted`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            fixture.restrictionState.value = unrestricted().copy(uxRestrictionState = UxRestrictionState.RESTRICTED)
            runCurrent()
            fixture.vehicleState.value = parked()
            runCurrent()
            assertEquals(SessionHistoryUiState.Locked, fixture.model.uiState.value)
            assertEquals(0, fixture.repository.active)
            fixture.restrictionState.value = unrestricted()
            runCurrent()
            assertEquals("a", ready(fixture).session.sessionId)
            assertEquals(1, fixture.repository.active)
            assertEquals(1, fixture.repository.maximumActive)
        }

    @Test fun `a new insertion retains the selected ID and updates its created position`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            val a = ready(fixture)
            assertTrue(fixture.model.older(a.session.sessionId, a.generation))
            val previous = ready(fixture)
            fixture.repository.values.value = listOf(session("new")) + fixture.repository.values.value
            runCurrent()
            val current = ready(fixture)
            assertEquals("b", current.session.sessionId)
            assertEquals(2, current.index)
            assertEquals(4, current.total)
            assertNotEquals(previous.generation, current.generation)
            assertFalse(fixture.model.older(previous.session.sessionId, previous.generation))
            assertEquals(current, ready(fixture))
        }

    @Test fun `a received source update fences stale actions before the source continuation returns`() =
        runTest {
            val fixture = Fixture()
            val deliver = CompletableDeferred<Unit>()
            var stale: SessionHistoryUiState.Ready? = null
            var accepted: Boolean? = null
            fixture.repository.source = {
                flow {
                    emit(listOf(session("a"), session("b"), session("c")))
                    deliver.await()
                    emit(listOf(session("new"), session("a"), session("b"), session("c")))
                    val previous = checkNotNull(stale)
                    accepted = fixture.model.older(previous.session.sessionId, previous.generation)
                    awaitCancellation()
                }
            }
            subscribe(fixture)
            stale = ready(fixture)
            deliver.complete(Unit)
            runCurrent()
            assertEquals(false, accepted)
            assertEquals("a", ready(fixture).session.sessionId)
            assertEquals(1, ready(fixture).index)
        }

    @Test fun `received edits refresh the stored snapshot without catalog reinterpretation or stale cursor moves`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            val previous = ready(fixture)
            val changed = session("a").copy(courseTitle = "보존된 새 스냅샷", taskTitle = "실제 저장 과제")
            fixture.repository.values.value = listOf(changed, session("b"), session("c"))
            runCurrent()
            assertEquals(changed, ready(fixture).session)
            assertFalse(fixture.model.older(previous.session.sessionId, previous.generation))
        }

    @Test fun `a removed selection stays changed even if the same ID later reappears until explicit reset`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            val a = ready(fixture)
            fixture.model.older(a.session.sessionId, a.generation)
            val b = ready(fixture)
            fixture.repository.values.value = listOf(session("a"), session("c"))
            runCurrent()
            assertTrue(fixture.model.uiState.value is SessionHistoryUiState.SelectionChanged)
            assertFalse(fixture.model.newer(b.session.sessionId, b.generation))
            fixture.repository.values.value = listOf(session("new"), session("b"), session("a"), session("c"))
            runCurrent()
            assertTrue(fixture.model.uiState.value is SessionHistoryUiState.SelectionChanged)
            assertTrue(fixture.model.resetToLatest(changed(fixture).generation))
            assertEquals("new", ready(fixture).session.sessionId)
            assertEquals(0, ready(fixture).index)
        }

    @Test fun `explicitly resetting a removed last selection can acknowledge an empty ended history`() =
        runTest {
            val fixture = Fixture(listOf(session("a")))
            subscribe(fixture)
            fixture.repository.values.value = emptyList()
            runCurrent()
            assertTrue(fixture.model.uiState.value is SessionHistoryUiState.SelectionChanged)
            assertTrue(fixture.model.resetToLatest(changed(fixture).generation))
            runCurrent()
            assertEquals(SessionHistoryUiState.Empty, fixture.model.uiState.value)
            fixture.repository.values.value = listOf(session("fresh"))
            runCurrent()
            assertEquals("fresh", ready(fixture).session.sessionId)
        }

    @Test fun `a previous reset callback cannot overwrite a new ready cursor after reset and navigation`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            fixture.repository.values.value = listOf(session("b"), session("c"))
            runCurrent()
            assertTrue(fixture.model.uiState.value is SessionHistoryUiState.SelectionChanged)
            val firstGeneration = changed(fixture).generation
            assertTrue(fixture.model.resetToLatest(firstGeneration))
            val latest = ready(fixture)
            assertTrue(fixture.model.older(latest.session.sessionId, latest.generation))
            val current = ready(fixture)
            assertFalse(fixture.model.resetToLatest(firstGeneration))
            assertEquals(current, ready(fixture))
        }

    @Test fun `an old selection changed reset cannot accept a later different selection changed state`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            fixture.repository.values.value = listOf(session("b"), session("c"))
            runCurrent()
            assertTrue(fixture.model.uiState.value is SessionHistoryUiState.SelectionChanged)
            val firstGeneration = changed(fixture).generation
            val firstReset = { fixture.model.resetToLatest(firstGeneration) }
            assertTrue(firstReset())
            val b = ready(fixture)
            assertTrue(fixture.model.older(b.session.sessionId, b.generation))
            assertEquals("c", ready(fixture).session.sessionId)
            fixture.repository.values.value = listOf(session("b"))
            runCurrent()
            assertTrue(fixture.model.uiState.value is SessionHistoryUiState.SelectionChanged)
            assertFalse(firstReset())
        }

    @Test fun `retry gives a lost selection a fresh generation and rejects the previous reset`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            fixture.repository.values.value = listOf(session("b"), session("c"))
            val old = changed(fixture)
            val fresh = CompletableDeferred<List<PracticeSession>>()
            fixture.repository.source = {
                flow {
                    emit(fresh.await())
                    awaitCancellation()
                }
            }
            assertTrue(fixture.model.retry())
            assertFalse(fixture.model.resetToLatest(old.generation))
            runCurrent()
            assertEquals(SessionHistoryUiState.Loading, fixture.model.uiState.value)
            fresh.complete(fixture.repository.values.value)
            val current = changed(fixture)
            assertNotEquals(old.generation, current.generation)
            assertFalse(fixture.model.resetToLatest(old.generation))
            assertEquals(current, fixture.model.uiState.value)
            assertTrue(fixture.model.resetToLatest(current.generation))
            assertEquals("b", ready(fixture).session.sessionId)
        }

    @Test fun `D and fresh P reentry never accepts a reset from the previous lost selection generation`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            fixture.repository.values.value = listOf(session("b"), session("c"))
            val old = changed(fixture)
            val fresh = CompletableDeferred<List<PracticeSession>>()
            fixture.repository.source = {
                flow {
                    emit(fresh.await())
                    awaitCancellation()
                }
            }
            fixture.vehicleState.value = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))
            assertFalse(fixture.model.resetToLatest(old.generation))
            runCurrent()
            assertEquals(SessionHistoryUiState.Locked, fixture.model.uiState.value)
            fixture.vehicleState.value = parked()
            runCurrent()
            assertEquals(SessionHistoryUiState.Loading, fixture.model.uiState.value)
            assertFalse(fixture.model.resetToLatest(old.generation))
            fresh.complete(fixture.repository.values.value)
            val current = changed(fixture)
            assertNotEquals(old.generation, current.generation)
            assertFalse(fixture.model.resetToLatest(old.generation))
            assertTrue(fixture.model.resetToLatest(current.generation))
            assertEquals("b", ready(fixture).session.sessionId)
        }

    @Test fun `closing a lost selection fences its reset even after a new reader validates that loss again`() =
        runTest {
            val fixture = Fixture()
            val first = subscribe(fixture)
            fixture.repository.values.value = listOf(session("b"), session("c"))
            val old = changed(fixture)
            first.cancelAndJoin()
            runCurrent()
            assertFalse(fixture.model.resetToLatest(old.generation))
            assertEquals(0, fixture.repository.active)
            subscribe(fixture)
            val current = changed(fixture)
            assertNotEquals(old.generation, current.generation)
            assertFalse(fixture.model.resetToLatest(old.generation))
            assertTrue(fixture.model.resetToLatest(current.generation))
            assertEquals("b", ready(fixture).session.sessionId)
        }

    @Test fun `another received history list invalidates the reset captured from an earlier loss display`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            fixture.repository.values.value = listOf(session("b"), session("c"))
            val old = changed(fixture)
            fixture.repository.values.value = listOf(session("new"), session("b"), session("c"))
            val current = changed(fixture)
            assertNotEquals(old.generation, current.generation)
            assertFalse(fixture.model.resetToLatest(old.generation))
            assertTrue(fixture.model.resetToLatest(current.generation))
            assertEquals("new", ready(fixture).session.sessionId)
        }

    @Test fun `a live denied source receipt must not strand history after a conflated D to P return`() =
        runTest {
            val fixture = Fixture()
            val driveReceipt = CompletableDeferred<Unit>()
            val parkedReceipt = CompletableDeferred<Unit>()
            var sourceStarts = 0
            var attemptedDriveReceipt = false
            var restoredP = false
            val fresh =
                fixture.repository.values.value
                    .map { it.copy(courseTitle = "새로 검증한 저장 제목") }
            fixture.repository.source = {
                flow {
                    sourceStarts++
                    if (sourceStarts == 1) {
                        emit(fixture.repository.values.value)
                        driveReceipt.await()
                        fixture.repository.values.value = fresh
                        // This continuation does not yield between changing the live value and
                        // source receipt. The VM's map calls receive before any downstream channel.
                        fixture.vehicleState.value = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))
                        attemptedDriveReceipt = true
                        try {
                            emit(fresh)
                        } finally {
                            // The separate access collector may see only this latest P value.
                            fixture.vehicleState.value = parked()
                            restoredP = true
                        }
                        parkedReceipt.await()
                        emit(fresh)
                        awaitCancellation()
                    } else {
                        emitAll(fixture.repository.values)
                    }
                }
            }
            subscribe(fixture)
            val previous = ready(fixture)
            assertEquals("a", previous.session.sessionId)
            driveReceipt.complete(Unit)
            runCurrent()
            assertTrue("The existing reader must attempt a real source emission with the live D value", attemptedDriveReceipt)
            assertTrue("The same source continuation must restore P even if it is cancelled", restoredP)
            assertTrue(fixture.model.canRead())
            parkedReceipt.complete(Unit)
            runCurrent()
            assertTrue(
                "Live P with a subsequently received valid history must recover from the direct live-gate rejection",
                fixture.model.uiState.value is SessionHistoryUiState.Ready,
            )
            val current = ready(fixture)
            assertEquals(fresh.first(), current.session)
            assertNotEquals(previous.generation, current.generation)
            assertFalse(fixture.model.older(previous.session.sessionId, previous.generation))
            assertEquals(1, fixture.repository.active)
            assertEquals(1, fixture.repository.maximumActive)
        }

    @Test fun `direct denied receipts cancel queries and wait for fully permitted fresh validation without losing selection`() =
        runTest {
            val blocked =
                listOf(
                    parked() to unrestricted().copy(uxRestrictionState = UxRestrictionState.UNKNOWN),
                    parked() to unrestricted().copy(parkingState = ParkingState.UNKNOWN),
                    parked() to unrestricted().copy(uxRestrictionState = UxRestrictionState.RESTRICTED),
                    parked().copy(connection = ConnectionState.PERMISSION_DENIED) to unrestricted(),
                    parked().copy(gear = VehicleReading.PermissionDenied) to unrestricted(),
                )
            for ((deniedVehicle, deniedRestrictions) in blocked) {
                val fixture = Fixture()
                val deliver = CompletableDeferred<Unit>()
                var sourceStarts = 0
                var attemptedDeniedReceipt = false
                val fresh =
                    fixture.repository.values.value
                        .map { it.copy(taskTitle = "다시 확인한 저장 과제") }
                fixture.repository.source = {
                    flow {
                        sourceStarts++
                        if (sourceStarts == 1) {
                            emit(fixture.repository.values.value)
                            deliver.await()
                            fixture.repository.values.value = fresh
                            fixture.vehicleState.value = deniedVehicle
                            fixture.restrictionState.value = deniedRestrictions
                            attemptedDeniedReceipt = true
                            emit(fresh)
                            awaitCancellation()
                        } else {
                            emitAll(fixture.repository.values)
                        }
                    }
                }
                val job = subscribe(fixture)
                val latest = ready(fixture)
                assertTrue(fixture.model.older(latest.session.sessionId, latest.generation))
                val previous = ready(fixture)
                assertEquals("b", previous.session.sessionId)
                deliver.complete(Unit)
                runCurrent()
                assertTrue(attemptedDeniedReceipt)
                assertFalse(fixture.model.canRead())
                assertEquals(SessionHistoryUiState.Locked, fixture.model.uiState.value)
                assertEquals(0, fixture.repository.active)
                assertEquals(1, fixture.repository.calls)
                assertFalse(fixture.model.newer(previous.session.sessionId, previous.generation))
                assertFalse(fixture.model.retry())
                fixture.vehicleState.value = parked()
                fixture.restrictionState.value = unrestricted()
                val current = ready(fixture)
                assertEquals(fresh[1], current.session)
                assertEquals(1, current.index)
                assertNotEquals(previous.generation, current.generation)
                assertFalse(fixture.model.newer(previous.session.sessionId, previous.generation))
                assertEquals(1, fixture.repository.active)
                assertEquals(1, fixture.repository.maximumActive)
                assertEquals(0, fixture.repository.writes)
                job.cancelAndJoin()
                runCurrent()
                assertEquals(0, fixture.repository.active)
            }
        }

    @Test fun `a denied begin query gate must recover when its D to P transition is conflated`() =
        runTest {
            val live = MutableStateFlow(parked())
            var liveReads = 0
            var deniedBeforeQuery = false
            val access =
                object : StateFlow<VehicleState> by live {
                    override val value: VehicleState
                        get() {
                            liveReads++
                            if (liveReads == 2) {
                                // The first live read publishes access. The next is beginQuery's
                                // own gate, before opening any repository flow. Capture an actual
                                // denied backing value and restore P before its observer runs.
                                live.value = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))
                                val denied = live.value
                                deniedBeforeQuery = true
                                live.value = parked()
                                return denied
                            }
                            return live.value
                        }
                }
            val fixture = Fixture(accessSource = access)
            subscribe(fixture)
            assertTrue("The direct beginQuery gate must observe the controlled denied backing value", deniedBeforeQuery)
            assertTrue(fixture.model.canRead())
            assertTrue(
                "A current permitted value must not remain in Loading or Locked after the denied initial query gate",
                fixture.model.uiState.value is SessionHistoryUiState.Ready,
            )
            assertEquals("a", ready(fixture).session.sessionId)
            assertEquals(1, fixture.repository.calls)
            assertEquals(1, fixture.repository.active)
            assertEquals(1, fixture.repository.maximumActive)
        }

    @Test fun `a live denied exception publication recovers after its D to P transition is conflated`() =
        runTest { deniedFailureRecovery(FailureMode.Exception) }

    @Test fun `a live denied normal completion publication recovers after its D to P transition is conflated`() =
        runTest { deniedFailureRecovery(FailureMode.NormalCompletion) }

    @Test fun `a live denied internal cancellation publication recovers after its D to P transition is conflated`() =
        runTest { deniedFailureRecovery(FailureMode.InternalCancellation) }

    @Test fun `cursor boundaries do not change identity or generation`() =
        runTest {
            val fixture = Fixture(listOf(session("only")))
            subscribe(fixture)
            val current = ready(fixture)
            assertFalse(current.canOlder)
            assertFalse(current.canNewer)
            assertFalse(fixture.model.older(current.session.sessionId, current.generation))
            assertFalse(fixture.model.newer(current.session.sessionId, current.generation))
            assertEquals(current, ready(fixture))
        }

    @Test fun `wrong ID wrong generation and a repeated old click never move the new cursor`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            val previous = ready(fixture)
            assertFalse(fixture.model.older("different", previous.generation))
            assertFalse(fixture.model.older(previous.session.sessionId, previous.generation + 1))
            assertTrue(fixture.model.older(previous.session.sessionId, previous.generation))
            val current = ready(fixture)
            assertFalse(fixture.model.older(previous.session.sessionId, previous.generation))
            assertEquals(current, ready(fixture))
            assertTrue(fixture.model.newer(current.session.sessionId, current.generation))
            assertEquals("a", ready(fixture).session.sessionId)
        }

    @Test fun `closing releases the query and reopening the same ViewModel validates the retained selected ID`() =
        runTest {
            val fixture = Fixture()
            val first = subscribe(fixture)
            val a = ready(fixture)
            fixture.model.older(a.session.sessionId, a.generation)
            val b = ready(fixture)
            first.cancelAndJoin()
            runCurrent()
            assertEquals(0, fixture.repository.active)
            assertEquals(SessionHistoryUiState.Loading, fixture.model.uiState.value)
            assertFalse(fixture.model.older(b.session.sessionId, b.generation))
            assertFalse(fixture.model.retry())
            subscribe(fixture)
            assertEquals("b", ready(fixture).session.sessionId)
            assertNotEquals(b.generation, ready(fixture).generation)
            assertFalse(fixture.model.newer(b.session.sessionId, b.generation))
            assertEquals(1, fixture.repository.maximumActive)
        }

    @Test fun `a cancelled delayed reader cannot publish into a newly opened reader`() =
        runTest {
            val fixture = Fixture()
            val late = CompletableDeferred<List<PracticeSession>>()
            fixture.repository.source = {
                flow {
                    emit(late.await())
                    awaitCancellation()
                }
            }
            val old = subscribe(fixture)
            old.cancelAndJoin()
            runCurrent()
            assertEquals(0, fixture.repository.active)
            fixture.repository.source = { fixture.repository.values }
            subscribe(fixture)
            val current = ready(fixture)
            late.complete(listOf(session("late")))
            runCurrent()
            assertEquals(current, ready(fixture))
            assertEquals(1, fixture.repository.maximumActive)
        }

    @Test fun `retry invalidates the displayed generation immediately and validates the same selection afresh`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            val a = ready(fixture)
            fixture.model.older(a.session.sessionId, a.generation)
            val old = ready(fixture)
            val fresh = CompletableDeferred<List<PracticeSession>>()
            fixture.repository.source = {
                flow {
                    emit(fresh.await())
                    awaitCancellation()
                }
            }
            assertTrue(fixture.model.retry())
            assertFalse(fixture.model.newer(old.session.sessionId, old.generation))
            assertFalse(fixture.model.resetToLatest(0L))
            runCurrent()
            assertEquals(SessionHistoryUiState.Loading, fixture.model.uiState.value)
            fresh.complete(fixture.repository.values.value)
            runCurrent()
            assertEquals("b", ready(fixture).session.sessionId)
            assertNotEquals(old.generation, ready(fixture).generation)
            assertEquals(1, fixture.repository.maximumActive)
        }

    @Test fun `retry cancels a previous delayed query and its later completion cannot replace the fresh list`() =
        runTest {
            val fixture = Fixture()
            val late = CompletableDeferred<List<PracticeSession>>()
            fixture.repository.source = {
                flow {
                    emit(late.await())
                    awaitCancellation()
                }
            }
            subscribe(fixture)
            fixture.repository.source = { fixture.repository.values }
            assertTrue(fixture.model.retry())
            runCurrent()
            val current = ready(fixture)
            late.complete(listOf(session("late")))
            runCurrent()
            assertEquals(current, ready(fixture))
            assertEquals(1, fixture.repository.active)
            assertEquals(1, fixture.repository.maximumActive)
        }

    @Test fun `factory failures are explicit read failures and require a new parked retry`() =
        runTest {
            val fixture = Fixture()
            fixture.repository.factoryFailure = IOException("fixture factory failure")
            subscribe(fixture)
            assertEquals(
                SessionHistoryUiState.Failed(SessionHistoryUiState.FailureReason.SESSION_READ),
                fixture.model.uiState.value,
            )
            assertFalse(fixture.model.resetToLatest(0L))
            fixture.repository.factoryFailure = null
            runCurrent()
            assertTrue(fixture.model.uiState.value is SessionHistoryUiState.Failed)
            assertTrue(fixture.model.retry())
            runCurrent()
            assertEquals("a", ready(fixture).session.sessionId)
        }

    @Test fun `a stream failure removes the usable cursor but preserves its ID for explicit retry`() =
        runTest {
            val fixture = Fixture()
            val fail = CompletableDeferred<Unit>()
            fixture.repository.source = {
                flow {
                    emit(fixture.repository.values.value)
                    fail.await()
                    throw IOException("fixture stream failure")
                }
            }
            subscribe(fixture)
            val a = ready(fixture)
            fixture.model.older(a.session.sessionId, a.generation)
            val b = ready(fixture)
            fail.complete(Unit)
            runCurrent()
            assertEquals(
                SessionHistoryUiState.Failed(SessionHistoryUiState.FailureReason.SESSION_READ),
                fixture.model.uiState.value,
            )
            assertFalse(fixture.model.newer(b.session.sessionId, b.generation))
            fixture.repository.source = { fixture.repository.values }
            assertTrue(fixture.model.retry())
            runCurrent()
            assertEquals("b", ready(fixture).session.sessionId)
        }

    @Test fun `normal stream completion is a failure instead of an empty or permanently ready history`() =
        runTest {
            val fixture = Fixture()
            val complete = CompletableDeferred<Unit>()
            fixture.repository.source = {
                flow {
                    emit(fixture.repository.values.value)
                    complete.await()
                }
            }
            subscribe(fixture)
            complete.complete(Unit)
            runCurrent()
            assertEquals(
                SessionHistoryUiState.Failed(SessionHistoryUiState.FailureReason.SESSION_STREAM_COMPLETED),
                fixture.model.uiState.value,
            )
            assertEquals(0, fixture.repository.active)
            fixture.repository.source = { fixture.repository.values }
            assertTrue(fixture.model.retry())
            runCurrent()
            assertEquals("a", ready(fixture).session.sessionId)
        }

    @Test fun `internal cancellation becomes a read failure while reader cancellation remains cancellation`() =
        runTest {
            val fixture = Fixture()
            fixture.repository.source = { flow { throw CancellationException("fixture internal cancellation") } }
            val trace = mutableListOf<SessionHistoryUiState>()
            val job = subscribe(fixture, trace)
            assertTrue(job.isActive)
            assertEquals(
                SessionHistoryUiState.Failed(SessionHistoryUiState.FailureReason.SESSION_READ),
                fixture.model.uiState.value,
            )
            fixture.repository.source = { fixture.repository.values }
            fixture.model.retry()
            runCurrent()
            val failures = trace.count { it is SessionHistoryUiState.Failed }
            job.cancelAndJoin()
            runCurrent()
            assertEquals(failures, trace.count { it is SessionHistoryUiState.Failed })
            assertEquals(0, fixture.repository.active)
        }

    @Test fun `duplicate session IDs including an active duplicate are invalid rather than silently deduplicated`() =
        runTest {
            val fixture = Fixture(listOf(session("same"), session("same", ended = null)))
            subscribe(fixture)
            assertEquals(
                SessionHistoryUiState.Failed(SessionHistoryUiState.FailureReason.INVALID_SESSIONS),
                fixture.model.uiState.value,
            )
            assertEquals(0, fixture.repository.writes)
        }

    @Test fun `invalid saved identity titles versions and times fail without repairing the records`() =
        runTest {
            val valid = session("a")
            val invalid =
                listOf(
                    valid.copy(sessionId = " "),
                    valid.copy(selection = valid.selection.copy(goalId = "")),
                    valid.copy(selection = valid.selection.copy(courseId = "")),
                    valid.copy(selection = valid.selection.copy(taskId = "")),
                    valid.copy(selection = valid.selection.copy(courseVersion = 0)),
                    valid.copy(selection = valid.selection.copy(briefingVersion = -1)),
                    valid.copy(goalTitle = ""),
                    valid.copy(courseTitle = " "),
                    valid.copy(taskTitle = "\n"),
                    valid.copy(startedAtEpochMillis = 0),
                    valid.copy(endedAtEpochMillis = valid.startedAtEpochMillis - 1),
                    valid.copy(endedAtEpochMillis = -1),
                )
            for (record in invalid) {
                val fixture = Fixture(listOf(record))
                val job = subscribe(fixture)
                assertEquals(
                    SessionHistoryUiState.Failed(SessionHistoryUiState.FailureReason.INVALID_SESSIONS),
                    fixture.model.uiState.value,
                )
                assertEquals(0, fixture.repository.writes)
                job.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `equal start and finish times remain valid saved button times`() =
        runTest {
            val record = session("a", started = 500, ended = 500)
            val fixture = Fixture(listOf(record))
            subscribe(fixture)
            assertEquals(record, ready(fixture).session)
        }

    @Test fun `access stream errors are distinct from repository errors and never query or report empty history`() =
        runTest {
            val state = BrokenStateFlow(MutableStateFlow(parked()))
            val fixture = Fixture(accessSource = state)
            subscribe(fixture)
            assertEquals(
                SessionHistoryUiState.Failed(SessionHistoryUiState.FailureReason.ACCESS_READ),
                fixture.model.uiState.value,
            )
            assertEquals(0, fixture.repository.calls)
        }

    @Test fun `a refreshed equal delivered list also fences a cursor action captured from the previous generation`() =
        runTest {
            val fixture = Fixture()
            val delivered = MutableSharedFlow<List<PracticeSession>>(replay = 1)
            delivered.tryEmit(fixture.repository.values.value)
            fixture.repository.source = { delivered }
            subscribe(fixture)
            val old = ready(fixture)
            delivered.emit(
                fixture.repository.values.value
                    .toList(),
            )
            runCurrent()
            assertNotEquals(old.generation, ready(fixture).generation)
            assertFalse(fixture.model.older(old.session.sessionId, old.generation))
            assertEquals("a", ready(fixture).session.sessionId)
        }

    @Test fun `ready binds each selected historical snapshot and its course summary together`() =
        runTest {
            val a = courseSession("a")
            val b = courseSession("b", "other", "다른 저장 과제")
            val c = session("c")
            val fixture = Fixture(listOf(a, b, c))
            val trace = mutableListOf<SessionHistoryUiState>()
            subscribe(fixture, trace)
            val first = ready(fixture)
            assertEquals(2, first.summary.sessionCount)
            assertEquals(
                listOf(SessionTaskCount("task", "저장 과제", 1), SessionTaskCount("other", "다른 저장 과제", 1)),
                first.summary.tasks,
            )
            assertTrue(fixture.model.older(first.session.sessionId, first.generation))
            val second = ready(fixture)
            assertEquals(b, second.summary.source)
            assertEquals(first.summary.tasks, second.summary.tasks)
            assertTrue(fixture.model.older(second.session.sessionId, second.generation))
            val third = ready(fixture)
            assertEquals(c, third.summary.source)
            assertEquals(1, third.summary.sessionCount)
            for (state in trace.filterIsInstance<SessionHistoryUiState.Ready>()) {
                assertEquals(state.session, state.summary.source)
            }
            assertEquals(1, fixture.repository.calls)
            assertEquals(1, fixture.repository.maximumActive)
            assertEquals(0, fixture.repository.writes)
        }

    @Test fun `a received insertion updates summary order and counts while retaining selected identity`() =
        runTest {
            val a = courseSession("a")
            val b = courseSession("b")
            val fixture = Fixture(listOf(a, b))
            subscribe(fixture)
            val previous = ready(fixture)
            val inserted = courseSession("inserted", "new-task", "먼저 받은 새 과제")
            fixture.repository.values.value = listOf(inserted, a, b)
            runCurrent()
            val current = ready(fixture)
            assertEquals(a, current.session)
            assertEquals(1, current.index)
            assertEquals(3, current.summary.sessionCount)
            assertEquals(
                listOf(SessionTaskCount("new-task", "먼저 받은 새 과제", 1), SessionTaskCount("task", "저장 과제", 2)),
                current.summary.tasks,
            )
            assertNotEquals(previous.generation, current.generation)
            assertFalse(fixture.model.older(previous.session.sessionId, previous.generation))
            assertEquals(0, fixture.repository.writes)
        }

    @Test fun `a received source title change rebuilds the summary from its new historical context`() =
        runTest {
            val a = courseSession("a")
            val b = courseSession("b")
            val fixture = Fixture(listOf(a, b))
            subscribe(fixture)
            val previous = ready(fixture)
            val changed = a.copy(courseTitle = "별도로 저장된 코스 제목")
            fixture.repository.values.value = listOf(changed, b)
            runCurrent()
            val current = ready(fixture)
            assertEquals(changed, current.session)
            assertEquals(1, current.summary.sessionCount)
            assertFalse(fixture.model.older(previous.session.sessionId, previous.generation))
            assertTrue(fixture.model.older(current.session.sessionId, current.generation))
            val older = ready(fixture)
            assertEquals(b, older.session)
            assertEquals(1, older.summary.sessionCount)
            assertEquals(0, fixture.repository.writes)
        }

    @Test fun `a received historical task rename splits counts without changing the course group`() =
        runTest {
            val a = courseSession("a")
            val b = courseSession("b")
            val fixture = Fixture(listOf(a, b))
            subscribe(fixture)
            val previous = ready(fixture)
            assertEquals(listOf(SessionTaskCount("task", "저장 과제", 2)), previous.summary.tasks)
            fixture.repository.values.value = listOf(a, b.copy(taskTitle = "변경된 당시 과제"))
            runCurrent()
            val current = ready(fixture)
            assertEquals(2, current.summary.sessionCount)
            assertEquals(
                listOf(SessionTaskCount("task", "저장 과제", 1), SessionTaskCount("task", "변경된 당시 과제", 1)),
                current.summary.tasks,
            )
            assertNotEquals(previous.generation, current.generation)
        }

    @Test fun `driving hides summary until fresh parked validation includes received records`() =
        runTest {
            val a = courseSession("a")
            val b = courseSession("b")
            val fixture = Fixture(listOf(a, b))
            subscribe(fixture)
            val first = ready(fixture)
            assertTrue(fixture.model.older(first.session.sessionId, first.generation))
            val previous = ready(fixture)
            fixture.vehicleState.value = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))
            runCurrent()
            assertEquals(SessionHistoryUiState.Locked, fixture.model.uiState.value)
            assertEquals(0, fixture.repository.active)
            fixture.repository.values.value = listOf(courseSession("inserted"), a, b)
            runCurrent()
            assertEquals(SessionHistoryUiState.Locked, fixture.model.uiState.value)
            fixture.vehicleState.value = parked()
            runCurrent()
            val current = ready(fixture)
            assertEquals(b, current.session)
            assertEquals(3, current.summary.sessionCount)
            assertFalse(fixture.model.newer(previous.session.sessionId, previous.generation))
            assertEquals(1, fixture.repository.maximumActive)
            assertEquals(0, fixture.repository.writes)
        }

    @Test fun `closing clears summary and reopening validates new counts for the retained selection`() =
        runTest {
            val a = courseSession("a")
            val b = courseSession("b")
            val fixture = Fixture(listOf(a, b))
            val firstReader = subscribe(fixture)
            val first = ready(fixture)
            assertTrue(fixture.model.older(first.session.sessionId, first.generation))
            val previous = ready(fixture)
            firstReader.cancelAndJoin()
            runCurrent()
            assertEquals(SessionHistoryUiState.Loading, fixture.model.uiState.value)
            assertEquals(0, fixture.repository.active)
            fixture.repository.values.value = listOf(courseSession("inserted"), a, b)
            runCurrent()
            assertEquals(SessionHistoryUiState.Loading, fixture.model.uiState.value)
            subscribe(fixture)
            val current = ready(fixture)
            assertEquals(b, current.session)
            assertEquals(3, current.summary.sessionCount)
            assertFalse(fixture.model.newer(previous.session.sessionId, previous.generation))
            assertEquals(1, fixture.repository.maximumActive)
            assertEquals(0, fixture.repository.writes)
        }

    @Test fun `source read failure removes summary until explicit retry validates fresh counts`() =
        runTest {
            val a = courseSession("a")
            val b = courseSession("b")
            val fixture = Fixture(listOf(a, b))
            val fail = CompletableDeferred<Unit>()
            fixture.repository.source = {
                flow {
                    emit(fixture.repository.values.value)
                    fail.await()
                    throw IOException("fixture summary source failure")
                }
            }
            subscribe(fixture)
            val previous = ready(fixture)
            fail.complete(Unit)
            runCurrent()
            assertEquals(
                SessionHistoryUiState.Failed(SessionHistoryUiState.FailureReason.SESSION_READ),
                fixture.model.uiState.value,
            )
            fixture.repository.values.value = listOf(courseSession("inserted"), a, b)
            runCurrent()
            assertTrue(fixture.model.uiState.value is SessionHistoryUiState.Failed)
            fixture.repository.source = { fixture.repository.values }
            assertTrue(fixture.model.retry())
            runCurrent()
            val current = ready(fixture)
            assertEquals(a, current.session)
            assertEquals(3, current.summary.sessionCount)
            assertFalse(fixture.model.older(previous.session.sessionId, previous.generation))
            assertEquals(1, fixture.repository.maximumActive)
            assertEquals(0, fixture.repository.writes)
        }

    @Test fun `a removed selection has no replacement summary until its current loss is acknowledged`() =
        runTest {
            val a = courseSession("a")
            val b = courseSession("b")
            val fixture = Fixture(listOf(a, b))
            subscribe(fixture)
            val first = ready(fixture)
            assertTrue(fixture.model.older(first.session.sessionId, first.generation))
            fixture.repository.values.value = listOf(a)
            runCurrent()
            val oldLoss = changed(fixture)
            val other = session("other-course")
            fixture.repository.values.value = listOf(other, a)
            runCurrent()
            val currentLoss = changed(fixture)
            assertFalse(fixture.model.resetToLatest(oldLoss.generation))
            assertTrue(fixture.model.resetToLatest(currentLoss.generation))
            val current = ready(fixture)
            assertEquals(other, current.session)
            assertEquals(1, current.summary.sessionCount)
            assertEquals(0, fixture.repository.writes)
        }

    @Test fun `undelivered input list mutations do not alter a published or retained course summary`() =
        runTest {
            val a = courseSession("a")
            val b = courseSession("b")
            val records = mutableListOf(a, b)
            val fixture = Fixture(records)
            subscribe(fixture)
            val previous = ready(fixture)
            records.clear()
            records += session("undelivered")
            assertEquals(previous, fixture.model.uiState.value)
            assertEquals(2, previous.summary.sessionCount)
            assertTrue(fixture.model.older(previous.session.sessionId, previous.generation))
            val current = ready(fixture)
            assertEquals(b, current.session)
            assertEquals(2, current.summary.sessionCount)
            assertEquals(1, fixture.repository.calls)
            assertEquals(0, fixture.repository.writes)
        }

    @Test fun `summary reveal requires its current visible identity and generation without side effects`() =
        runTest {
            val fixture = Fixture()
            assertFalse(fixture.model.canRevealSummary("a", 1))
            assertEquals(0, fixture.repository.calls)
            subscribe(fixture)
            val current = ready(fixture)
            assertTrue(fixture.model.canRevealSummary(current.session.sessionId, current.generation))
            assertFalse(fixture.model.canRevealSummary("different", current.generation))
            assertFalse(fixture.model.canRevealSummary(current.session.sessionId, current.generation + 1))
            assertTrue(fixture.model.canRevealSummary(current.session.sessionId, current.generation))
            assertEquals(current, ready(fixture))
            assertEquals(1, fixture.repository.calls)
            assertEquals(1, fixture.repository.active)
            assertEquals(0, fixture.repository.writes)
        }

    @Test fun `summary callbacks reject silent live D and unconfirmed UX before Ready recombines`() =
        runTest {
            val blocked =
                listOf(
                    parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE)) to unrestricted(),
                    parked().copy(gear = VehicleReading.Unknown) to unrestricted(),
                    parked().copy(gear = VehicleReading.PermissionDenied) to unrestricted(),
                    parked().copy(connection = ConnectionState.DISCONNECTED) to unrestricted(),
                    parked() to unrestricted().copy(parkingState = ParkingState.UNKNOWN),
                    parked() to unrestricted().copy(uxRestrictionState = UxRestrictionState.UNKNOWN),
                    parked() to unrestricted().copy(uxRestrictionState = UxRestrictionState.RESTRICTED),
                )
            for ((vehicle, restrictions) in blocked) {
                val fixture = Fixture()
                val job = subscribe(fixture)
                val current = ready(fixture)
                val oldCallback = { fixture.model.canRevealSummary(current.session.sessionId, current.generation) }
                assertTrue(oldCallback())
                fixture.vehicleState.value = vehicle
                fixture.restrictionState.value = restrictions
                assertEquals(current, fixture.model.uiState.value)
                assertFalse(oldCallback())
                assertEquals(1, fixture.repository.calls)
                assertEquals(0, fixture.repository.writes)
                job.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `summary retry fences an old callback before the UI receives Loading`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            val previous = ready(fixture)
            val oldCallback = { fixture.model.canRevealSummary(previous.session.sessionId, previous.generation) }
            val fresh = CompletableDeferred<List<PracticeSession>>()
            fixture.repository.source = {
                flow {
                    emit(fresh.await())
                    awaitCancellation()
                }
            }
            assertTrue(oldCallback())
            assertTrue(fixture.model.retry())
            assertFalse(oldCallback())
            runCurrent()
            assertEquals(SessionHistoryUiState.Loading, fixture.model.uiState.value)
            fresh.complete(fixture.repository.values.value)
            runCurrent()
            val current = ready(fixture)
            assertFalse(oldCallback())
            assertTrue(fixture.model.canRevealSummary(current.session.sessionId, current.generation))
            assertEquals(1, fixture.repository.maximumActive)
            assertEquals(0, fixture.repository.writes)
        }

    @Test fun `each received history list fences the old summary callback before source continuation`() =
        runTest {
            for (editSource in listOf(false, true)) {
                val fixture = Fixture()
                val deliver = CompletableDeferred<Unit>()
                var previous: SessionHistoryUiState.Ready? = null
                var acceptedAfterReceipt: Boolean? = null
                fixture.repository.source = {
                    flow {
                        emit(fixture.repository.values.value)
                        deliver.await()
                        val records =
                            fixture.repository.values.value
                                .toList()
                        val delivered =
                            if (editSource) records.map { it.copy(courseTitle = "다시 받은 당시 코스") } else records
                        emit(delivered)
                        val old = checkNotNull(previous)
                        acceptedAfterReceipt = fixture.model.canRevealSummary(old.session.sessionId, old.generation)
                        awaitCancellation()
                    }
                }
                val job = subscribe(fixture)
                previous = ready(fixture)
                deliver.complete(Unit)
                runCurrent()
                val current = ready(fixture)
                assertEquals(false, acceptedAfterReceipt)
                assertNotEquals(checkNotNull(previous).generation, current.generation)
                assertTrue(fixture.model.canRevealSummary(current.session.sessionId, current.generation))
                assertEquals(1, fixture.repository.calls)
                assertEquals(0, fixture.repository.writes)
                job.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `summary callbacks reject cancelled queries before cleanup and after reader reentry`() =
        runTest {
            val fixture = Fixture()
            var previous: SessionHistoryUiState.Ready? = null
            var acceptedDuringCleanup: Boolean? = null
            fixture.repository.source = {
                flow {
                    try {
                        emitAll(fixture.repository.values)
                    } finally {
                        previous?.let { acceptedDuringCleanup = fixture.model.canRevealSummary(it.session.sessionId, it.generation) }
                    }
                }
            }
            val first = subscribe(fixture)
            previous = ready(fixture)
            val old = checkNotNull(previous)
            val oldCallback = { fixture.model.canRevealSummary(old.session.sessionId, old.generation) }
            first.cancelAndJoin()
            runCurrent()
            assertEquals(false, acceptedDuringCleanup)
            assertFalse(oldCallback())
            assertEquals(0, fixture.repository.active)
            subscribe(fixture)
            val current = ready(fixture)
            assertEquals(old.session, current.session)
            assertFalse(oldCallback())
            assertTrue(fixture.model.canRevealSummary(current.session.sessionId, current.generation))
            assertEquals(1, fixture.repository.maximumActive)
            assertEquals(0, fixture.repository.writes)
        }

    @Test fun `summary callbacks cannot follow a cursor away and back to the same source ID`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            val first = ready(fixture)
            val oldCallback = { fixture.model.canRevealSummary(first.session.sessionId, first.generation) }
            assertTrue(oldCallback())
            assertTrue(fixture.model.older(first.session.sessionId, first.generation))
            val older = ready(fixture)
            assertFalse(oldCallback())
            assertTrue(fixture.model.canRevealSummary(older.session.sessionId, older.generation))
            assertTrue(fixture.model.newer(older.session.sessionId, older.generation))
            val current = ready(fixture)
            assertEquals(first.session.sessionId, current.session.sessionId)
            assertFalse(oldCallback())
            assertTrue(fixture.model.canRevealSummary(current.session.sessionId, current.generation))
            assertEquals(1, fixture.repository.calls)
            assertEquals(0, fixture.repository.writes)
        }

    @Test fun `a removed summary source stays denied after explicit reset even when parking allows reading`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            val previous = ready(fixture)
            val oldCallback = { fixture.model.canRevealSummary(previous.session.sessionId, previous.generation) }
            fixture.repository.values.value = listOf(session("b"), session("c"))
            runCurrent()
            assertTrue(fixture.model.canRead())
            assertFalse(oldCallback())
            val loss = changed(fixture)
            assertTrue(fixture.model.resetToLatest(loss.generation))
            val current = ready(fixture)
            assertFalse(oldCallback())
            assertTrue(fixture.model.canRevealSummary(current.session.sessionId, current.generation))
            assertEquals(0, fixture.repository.writes)
        }

    @Test fun `summary read failures never leave a revealable cached Ready before explicit retry`() =
        runTest {
            val fixture = Fixture()
            val fail = CompletableDeferred<Unit>()
            fixture.repository.source = {
                flow {
                    emit(fixture.repository.values.value)
                    fail.await()
                    throw IOException("fixture disclosure failure")
                }
            }
            subscribe(fixture)
            val previous = ready(fixture)
            val oldCallback = { fixture.model.canRevealSummary(previous.session.sessionId, previous.generation) }
            fail.complete(Unit)
            runCurrent()
            assertTrue(fixture.model.canRead())
            assertTrue(fixture.model.uiState.value is SessionHistoryUiState.Failed)
            assertFalse(oldCallback())
            fixture.repository.source = { fixture.repository.values }
            assertTrue(fixture.model.retry())
            assertFalse(oldCallback())
            runCurrent()
            val current = ready(fixture)
            assertFalse(oldCallback())
            assertTrue(fixture.model.canRevealSummary(current.session.sessionId, current.generation))
            assertEquals(0, fixture.repository.writes)
        }

    private suspend fun TestScope.deniedFailureRecovery(mode: FailureMode) {
        val live = MutableStateFlow(parked())
        var armedFailureGate = false
        var readDeniedAtFailure = false
        var restoredP = false
        val access =
            object : StateFlow<VehicleState> by live {
                override val value: VehicleState
                    get() {
                        if (armedFailureGate) {
                            armedFailureGate = false
                            live.value = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))
                            val denied = live.value
                            readDeniedAtFailure = denied.gear == VehicleReading.Available(GearPosition.DRIVE)
                            // Restore only after capturing the actual denied live snapshot. Restoring
                            // in an upstream source finally would happen before the VM catch and
                            // would test ordinary permitted failure instead of this failure gate.
                            try {
                                return denied
                            } finally {
                                live.value = parked()
                                restoredP = true
                            }
                        }
                        return live.value
                    }
            }
        val fixture = Fixture(accessSource = access)
        val fail = CompletableDeferred<Unit>()
        var sourceStarts = 0
        val fresh =
            fixture.repository.values.value
                .map { it.copy(goalTitle = "실패 이후 다시 검증한 저장 목표") }
        fixture.repository.source = {
            flow {
                sourceStarts++
                if (sourceStarts == 1) {
                    emit(fixture.repository.values.value)
                    fail.await()
                    fixture.repository.values.value = fresh
                    armedFailureGate = true
                    when (mode) {
                        FailureMode.Exception -> throw IOException("fixture failure gate")
                        FailureMode.NormalCompletion -> Unit
                        FailureMode.InternalCancellation -> throw CancellationException("fixture internal failure gate")
                    }
                } else {
                    emitAll(fixture.repository.values)
                }
            }
        }
        val job = subscribe(fixture)
        val latest = ready(fixture)
        assertTrue(fixture.model.older(latest.session.sessionId, latest.generation))
        val previous = ready(fixture)
        assertEquals("b", previous.session.sessionId)
        fail.complete(Unit)
        runCurrent()
        assertTrue("The VM failure publication must read the controlled denied live snapshot", readDeniedAtFailure)
        assertTrue("The live source must restore P before its access observer resumes", restoredP)
        assertTrue(job.isActive)
        assertTrue(fixture.model.canRead())
        assertTrue(
            "A denied failure publication must revalidate access instead of leaving live P with Locked and no query",
            fixture.model.uiState.value is SessionHistoryUiState.Ready,
        )
        val current = ready(fixture)
        assertEquals(fresh[1], current.session)
        assertEquals(1, current.index)
        assertNotEquals(previous.generation, current.generation)
        assertFalse(fixture.model.newer(previous.session.sessionId, previous.generation))
        assertEquals(2, fixture.repository.calls)
        assertEquals(1, fixture.repository.active)
        assertEquals(1, fixture.repository.maximumActive)
        assertEquals(0, fixture.repository.writes)
    }

    private enum class FailureMode { Exception, NormalCompletion, InternalCancellation }

    private fun TestScope.subscribe(
        fixture: Fixture,
        trace: MutableList<SessionHistoryUiState> = mutableListOf(),
    ): Job {
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.model.uiState.collect { trace += it }
            }
        runCurrent()
        return job
    }

    private fun TestScope.changed(fixture: Fixture): SessionHistoryUiState.SelectionChanged {
        runCurrent()
        return fixture.model.uiState.value as SessionHistoryUiState.SelectionChanged
    }

    private fun TestScope.ready(fixture: Fixture): SessionHistoryUiState.Ready {
        runCurrent()
        val current = fixture.model.uiState.value as SessionHistoryUiState.Ready
        assertEquals(current.session, current.summary.source)
        return current
    }

    private class Fixture(
        records: List<PracticeSession> = listOf(session("a"), session("b"), session("c")),
        accessSource: StateFlow<VehicleState>? = null,
    ) {
        val vehicleState = MutableStateFlow(parked())
        val restrictionState = MutableStateFlow(unrestricted())
        val vehicle =
            object : VehicleGateway {
                override val state = accessSource ?: vehicleState
            }
        val restrictions =
            object : DrivingRestrictionsProvider {
                override val restrictions = restrictionState
            }
        val repository = FakeRepository(records)
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
        val observations =
            object : VehicleObservationRepository {
                override fun observe(sessionId: String): Flow<List<SessionVehicleObservation>> =
                    error("Stored observation queries are outside this fixture")

                override suspend fun append(
                    sessionId: String,
                    observation: VehicleObservation,
                ): VehicleObservationAppendResult = error("Stored observation writes are outside this fixture")
            }
        val model = SessionHistoryViewModel(vehicle, restrictions, repository, deletions, observations, UnexpectedHistoryMemoRepository)
    }

    private class FakeRepository(
        records: List<PracticeSession>,
    ) : PracticeSessionRepository {
        val values = MutableStateFlow(records)
        var source: () -> Flow<List<PracticeSession>> = { values }
        var factoryFailure: Exception? = null
        var calls = 0
        var active = 0
        var maximumActive = 0
        var writes = 0

        override fun observeSessions(): Flow<List<PracticeSession>> {
            calls++
            factoryFailure?.let { throw it }
            return flow {
                active++
                maximumActive = maxOf(maximumActive, active)
                try {
                    emitAll(source())
                } finally {
                    active--
                }
            }
        }

        override suspend fun start(
            practice: PreparedPractice,
            startedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionStartResult {
            writes++
            error("History must not start a session")
        }

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult {
            writes++
            error("History must not finish a session")
        }
    }

    @OptIn(InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
    private class BrokenStateFlow<T>(
        private val backing: StateFlow<T>,
    ) : StateFlow<T> by backing {
        override suspend fun collect(collector: FlowCollector<T>): Nothing = throw IOException("fixture access stream failure")
    }

    private companion object {
        fun courseSession(
            id: String,
            taskId: String = "task",
            taskTitle: String = "저장 과제",
        ) = session(id).copy(
            selection = PracticeSelection("summary-goal", "summary-course", 2, 3, taskId),
            goalTitle = "같은 당시 목표",
            courseTitle = "같은 당시 코스",
            taskTitle = taskTitle,
        )

        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        fun unrestricted() = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)

        fun session(
            id: String,
            started: Long = 1_000,
            ended: Long? = 2_000,
        ) = PracticeSession(
            id,
            PracticeSelection("goal-$id", "course-$id", 1, 1, "task-$id"),
            "저장 목표 $id",
            "저장 코스 $id",
            "저장 과제 $id",
            CourseReviewStatus.DEMO,
            started,
            ended,
        )
    }
}
