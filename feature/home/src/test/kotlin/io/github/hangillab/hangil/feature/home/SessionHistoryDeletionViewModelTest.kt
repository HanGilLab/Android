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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Controller tests use explicit fakes; Room transaction and actual SDK evidence live separately. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionHistoryDeletionViewModelTest {
    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun enteringHistoryDoesNotPrepareOrDeleteAnyRecord() =
        runTest {
            val f = Fixture()
            assertFalse(f.model.requestDeletion("a", 0, f.keepOpen))
            subscribe(f)
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
            assertEquals(1, f.sessions.reads)
            assertEquals(1, f.sessions.active)
            assertEquals(0, f.deletions.prepareCalls)
            assertEquals(0, f.deletions.deleteCalls)
            assertEquals(0, f.sessions.writes)
        }

    @Test fun explicitRequestPreparesFixedCountsAndMemoEvenWithASeparateActiveSession() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val state = confirming(f)
            assertEquals(session("a"), state.target.session)
            assertEquals(2L, state.target.observationCount)
            assertEquals("memo-version-1", state.target.memoVersion)
            assertTrue(f.model.canPresentDeletion(state.nonce))
            assertEquals(1, f.deletions.prepareCalls)
            assertEquals(0, f.deletions.deleteCalls)
            assertTrue(
                f.sessions.values.value
                    .any { it.isActive },
            )
            assertEquals(1, f.sessions.maximumActive)
        }

    @Test fun staleSelectionGenerationAndExpiredHostCannotStartPreparation() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val current = ready(f)
            assertFalse(f.model.requestDeletion("b", current.generation, f.keepOpen))
            assertFalse(f.model.requestDeletion("a", current.generation - 1, f.keepOpen))
            f.host.set(false)
            assertFalse(f.model.requestDeletion("a", current.generation, f.keepOpen))
            assertEquals(0, f.deletions.prepareCalls)
            assertEquals(0, f.deletions.deleteCalls)
        }

    @Test fun liveDenialsBlockRequestBeforeTheDisplayedParkedStateChanges() =
        runTest {
            val denied =
                listOf(
                    parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE)) to unrestricted(),
                    parked().copy(connection = ConnectionState.DISCONNECTED) to unrestricted(),
                    parked().copy(connection = ConnectionState.PERMISSION_DENIED) to unrestricted(),
                    parked().copy(gear = VehicleReading.Unknown) to unrestricted(),
                    parked() to DrivingRestrictions(),
                    parked() to unrestricted().copy(uxRestrictionState = UxRestrictionState.RESTRICTED),
                )
            for ((vehicle, restrictions) in denied) {
                val f = Fixture()
                val reader = subscribe(f)
                val displayed = ready(f)
                f.vehicle.value = vehicle
                f.restrictions.value = restrictions
                assertEquals(displayed, f.model.uiState.value)
                assertFalse(f.model.requestDeletion(displayed.session.sessionId, displayed.generation, f.keepOpen))
                assertEquals(0, f.deletions.prepareCalls)
                assertEquals(0, f.deletions.deleteCalls)
                reader.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun latePreparationCannotOpenAConfirmationAfterTheSelectedRecordChanges() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            f.deletions.prepareAction = { expected, _ ->
                withContext(NonCancellable) { release.await() }
                SessionDeletionPreparation.Ready(target(expected))
            }
            subscribe(f)
            val original = ready(f)
            assertTrue(f.model.requestDeletion("a", original.generation, f.keepOpen))
            runCurrent()
            assertTrue(f.model.deletionState.value is SessionDeletionUiState.Preparing)
            assertTrue(f.model.older("a", original.generation))
            release.complete(Unit)
            runCurrent()
            assertEquals("b", ready(f).session.sessionId)
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
            assertEquals(0, f.deletions.deleteCalls)
        }

    @Test fun retryReplacesTheQueryAndRejectsItsLatePreparation() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            f.deletions.prepareAction = { expected, _ ->
                withContext(NonCancellable) { release.await() }
                SessionDeletionPreparation.Ready(target(expected))
            }
            subscribe(f)
            val current = ready(f)
            assertTrue(f.model.requestDeletion("a", current.generation, f.keepOpen))
            runCurrent()
            assertTrue(f.model.retry())
            release.complete(Unit)
            runCurrent()
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
            assertNotEquals(current.generation, ready(f).generation)
            assertEquals(2, f.sessions.reads)
            assertEquals(1, f.sessions.maximumActive)
        }

    @Test fun sourceCompletionAndReadErrorRevokeAnOpenConfirmation() =
        runTest {
            for (fails in listOf(false, true)) {
                val f = Fixture()
                val stop = CompletableDeferred<Unit>()
                f.sessions.source = {
                    flow {
                        emit(f.sessions.values.value)
                        stop.await()
                        if (fails) throw IOException("fixture history read failure")
                    }
                }
                val reader = subscribe(f)
                val confirmation = confirming(f)
                stop.complete(Unit)
                runCurrent()
                assertTrue(f.model.uiState.value is SessionHistoryUiState.Failed)
                assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
                assertFalse(f.model.confirmDeletion(confirmation.nonce))
                assertEquals(0, f.deletions.deleteCalls)
                reader.cancelAndJoin()
            }
        }

    @Test fun dismissCancelsLatePreparationAndCannotRestoreItsDialog() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            f.deletions.prepareAction = { expected, _ ->
                withContext(NonCancellable) { release.await() }
                SessionDeletionPreparation.Ready(target(expected))
            }
            subscribe(f)
            val current = ready(f)
            assertTrue(f.model.requestDeletion("a", current.generation, f.keepOpen))
            runCurrent()
            val preparing = f.model.deletionState.value as SessionDeletionUiState.Preparing
            assertTrue(f.model.dismissDeletion(preparing.nonce))
            release.complete(Unit)
            runCurrent()
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
            assertFalse(f.model.confirmDeletion(preparing.nonce))
            assertEquals(0, f.deletions.deleteCalls)
        }

    @Test fun aChangedFullSnapshotInvalidatesConfirmationEvenWhenTheSessionIdIsUnchanged() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val confirmation = confirming(f)
            f.sessions.values.value =
                f.sessions.values.value.map {
                    if (it.sessionId == "a") it.copy(taskTitle = "Changed task snapshot") else it
                }
            runCurrent()
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
            assertFalse(f.model.confirmDeletion(confirmation.nonce))
            assertEquals("Changed task snapshot", ready(f).session.taskTitle)
            assertEquals(0, f.deletions.deleteCalls)
        }

    @Test fun stoppingTheReaderDiscardsTheNonceAndReentryNeedsANewRequest() =
        runTest {
            val f = Fixture()
            val reader = subscribe(f)
            val confirmation = confirming(f)
            reader.cancelAndJoin()
            runCurrent()
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
            assertFalse(f.model.confirmDeletion(confirmation.nonce))
            assertEquals(0, f.sessions.active)
            subscribe(f)
            assertFalse(f.model.confirmDeletion(confirmation.nonce))
            val fresh = confirming(f)
            assertNotEquals(confirmation.nonce, fresh.nonce)
            assertTrue(f.model.canPresentDeletion(fresh.nonce))
            assertEquals(1, f.sessions.maximumActive)
        }

    @Test fun typedPreparationRejectionsNeverBecomeConfirmationsOrSuccess() =
        runTest {
            val cases =
                listOf(
                    SessionDeletionPreparation.Missing to SessionDeletionUiState.FailureReason.MISSING,
                    SessionDeletionPreparation.Active to SessionDeletionUiState.FailureReason.ACTIVE,
                    SessionDeletionPreparation.Changed to SessionDeletionUiState.FailureReason.CHANGED,
                    SessionDeletionPreparation.Blocked to SessionDeletionUiState.FailureReason.BLOCKED,
                )
            for ((result, reason) in cases) {
                val f = Fixture()
                val reader = subscribe(f)
                f.deletions.prepareAction = { _, _ -> result }
                val current = ready(f)
                assertTrue(f.model.requestDeletion("a", current.generation, f.keepOpen))
                runCurrent()
                val failure = f.model.deletionState.value as SessionDeletionUiState.Failed
                assertEquals(reason, failure.reason)
                assertFalse(f.model.confirmDeletion(failure.nonce))
                assertEquals(0, f.deletions.deleteCalls)
                reader.cancelAndJoin()
            }
        }

    @Test fun aWrongTargetSnapshotOrNegativeObservationCountRequiresNewConfirmation() =
        runTest {
            for (wrong in listOf(target(session("b")), target(session("a")).copy(observationCount = -1))) {
                val f = Fixture()
                val reader = subscribe(f)
                f.deletions.prepareAction = { _, _ -> SessionDeletionPreparation.Ready(wrong) }
                val current = ready(f)
                f.model.requestDeletion("a", current.generation, f.keepOpen)
                runCurrent()
                assertEquals(
                    SessionDeletionUiState.FailureReason.CHANGED,
                    (f.model.deletionState.value as SessionDeletionUiState.Failed).reason,
                )
                assertEquals(0, f.deletions.deleteCalls)
                reader.cancelAndJoin()
            }
        }

    @Test fun storagePreparationFailureIsDistinctFromCancellationAndCanBeRequestedAgain() =
        runTest {
            val f = Fixture()
            subscribe(f)
            f.deletions.prepareAction = { _, _ -> throw IOException("fixture prepare failure") }
            val current = ready(f)
            f.model.requestDeletion("a", current.generation, f.keepOpen)
            runCurrent()
            val failure = f.model.deletionState.value as SessionDeletionUiState.Failed
            assertEquals(SessionDeletionUiState.FailureReason.PREPARE_FAILED, failure.reason)
            f.deletions.prepareAction = { expected, canRead ->
                if (canRead()) SessionDeletionPreparation.Ready(target(expected)) else SessionDeletionPreparation.Blocked
            }
            val next = confirming(f)
            assertNotEquals(failure.nonce, next.nonce)
            assertEquals(0, f.deletions.deleteCalls)
        }

    @Test fun independentPreparationCancellationDiscardsIntentWithoutReportingAStorageFailure() =
        runTest {
            val f = Fixture()
            subscribe(f)
            f.deletions.prepareAction = { _, _ -> throw CancellationException("fixture prepare cancellation") }
            val current = ready(f)
            f.model.requestDeletion("a", current.generation, f.keepOpen)
            runCurrent()
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
            assertEquals(current.session, ready(f).session)
            f.deletions.prepareAction = { expected, _ -> SessionDeletionPreparation.Ready(target(expected)) }
            assertTrue(f.model.requestDeletion("a", ready(f).generation, f.keepOpen))
            runCurrent()
            assertTrue(f.model.deletionState.value is SessionDeletionUiState.Confirming)
        }

    @Test fun deniedConfirmationDiscardsIntentEvenIfDAndPConflate() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val confirmation = confirming(f)
            val displayed = ready(f)
            f.vehicle.value = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))
            assertEquals(displayed, f.model.uiState.value)
            assertFalse(f.model.confirmDeletion(confirmation.nonce))
            f.vehicle.value = parked()
            runCurrent()
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
            assertFalse(f.model.confirmDeletion(confirmation.nonce))
            assertEquals(0, f.deletions.deleteCalls)
            assertNotEquals(confirmation.nonce, confirming(f).nonce)
        }

    @Test fun deniedOldDeleteRequestRevokesAnExistingConfirmationBeforeSilentPRecovery() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val confirmation = confirming(f)
            val displayed = ready(f)
            val oldDeleteRequest = {
                f.model.requestDeletion(displayed.session.sessionId, displayed.generation, f.keepOpen)
            }
            f.vehicle.value = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))
            assertEquals(displayed, f.model.uiState.value)
            assertFalse(oldDeleteRequest())
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
            f.vehicle.value = parked()
            assertEquals(displayed, f.model.uiState.value)
            assertFalse(f.model.confirmDeletion(confirmation.nonce))
            runCurrent()
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
            assertEquals(1, f.deletions.prepareCalls)
            assertEquals(0, f.deletions.deleteCalls)
            assertNotEquals(confirmation.nonce, confirming(f).nonce)
        }

    @Test fun aStaleRequestCannotRevokeAnotherValidSelectedConfirmation() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val original = ready(f)
            val old = confirming(f)
            f.model.dismissDeletion(old.nonce)
            assertTrue(f.model.older(original.session.sessionId, original.generation))
            runCurrent()
            val fresh = confirming(f)
            assertEquals("b", fresh.target.session.sessionId)
            assertFalse(f.model.requestDeletion(original.session.sessionId, original.generation, f.keepOpen))
            assertEquals(fresh, f.model.deletionState.value)
            assertTrue(f.model.canPresentDeletion(fresh.nonce))
            assertEquals(0, f.deletions.deleteCalls)
        }

    @Test fun observingAnExpiredConfirmationHostDiscardsItsNonceBeforeAnotherRequest() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val old = confirming(f)
            val displayed = ready(f)
            f.host.set(false)
            assertFalse(f.model.requestDeletion(displayed.session.sessionId, displayed.generation) { true })
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
            assertFalse(f.model.confirmDeletion(old.nonce))
            assertTrue(f.model.requestDeletion(displayed.session.sessionId, displayed.generation) { true })
            runCurrent()
            val fresh = f.model.deletionState.value as SessionDeletionUiState.Confirming
            assertNotEquals(old.nonce, fresh.nonce)
            assertEquals(0, f.deletions.deleteCalls)
        }

    @Test fun anOldDismissOrConfirmCannotBorrowTheNextNonce() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val old = confirming(f)
            assertTrue(f.model.dismissDeletion(old.nonce))
            val fresh = confirming(f)
            assertNotEquals(old.nonce, fresh.nonce)
            assertFalse(f.model.dismissDeletion(old.nonce))
            assertFalse(f.model.confirmDeletion(old.nonce))
            assertEquals(fresh, f.model.deletionState.value)
            assertTrue(f.model.canPresentDeletion(fresh.nonce))
            assertEquals(0, f.deletions.deleteCalls)
        }

    @Test fun duplicateConfirmationEntersTheDeletionRepositoryOnlyOnce() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            f.deletions.deleteAction = { _, canCommit ->
                assertTrue(canCommit())
                release.await()
                SessionDeletionResult.Blocked
            }
            subscribe(f)
            val confirmation = confirming(f)
            assertTrue(f.model.confirmDeletion(confirmation.nonce))
            assertFalse(f.model.confirmDeletion(confirmation.nonce))
            runCurrent()
            assertTrue(f.model.deletionState.value is SessionDeletionUiState.Deleting)
            assertFalse(f.model.confirmDeletion(confirmation.nonce))
            assertEquals(1, f.deletions.deleteCalls)
            release.complete(Unit)
            runCurrent()
            assertEquals(
                SessionDeletionUiState.FailureReason.BLOCKED,
                (f.model.deletionState.value as SessionDeletionUiState.Failed).reason,
            )
        }

    @Test fun commitPredicateReadsSilentLiveDAndEndsTheBusyStateWithoutInventingSuccess() =
        runTest {
            val f = Fixture()
            val rows = f.sessions.values.value
            f.deletions.deleteAction = { _, canCommit ->
                assertTrue(canCommit())
                f.vehicle.value = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))
                assertFalse(canCommit())
                SessionDeletionResult.Blocked
            }
            subscribe(f)
            val confirmation = confirming(f)
            f.model.confirmDeletion(confirmation.nonce)
            runCurrent()
            assertEquals(rows, f.sessions.values.value)
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
            assertEquals(0, f.deletions.committed.size)
            f.vehicle.value = parked()
            runCurrent()
            assertFalse(f.model.confirmDeletion(confirmation.nonce))
        }

    @Test fun aStoppedHostIsSeenByThePostwritePredicateAndDoesNotLeaveFalseDeleting() =
        runTest {
            val f = Fixture()
            val rows = f.sessions.values.value
            f.deletions.deleteAction = { _, canCommit ->
                assertTrue(canCommit())
                f.host.set(false)
                assertFalse(canCommit())
                SessionDeletionResult.Blocked
            }
            subscribe(f)
            val confirmation = confirming(f)
            f.model.confirmDeletion(confirmation.nonce)
            runCurrent()
            assertEquals(rows, f.sessions.values.value)
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
            assertFalse(f.model.canPresentDeletion(confirmation.nonce))
            assertEquals(0, f.deletions.committed.size)
        }

    @Test fun aNewReceiptDuringDeletionRevokesTheOldGenerationAndIgnoresALateResult() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            f.deletions.deleteAction = { _, canCommit ->
                assertTrue(canCommit())
                withContext(NonCancellable) { release.await() }
                assertFalse(canCommit())
                SessionDeletionResult.Deleted
            }
            subscribe(f)
            val confirmation = confirming(f)
            f.model.confirmDeletion(confirmation.nonce)
            runCurrent()
            f.sessions.values.value = listOf(session("new")) + f.sessions.values.value
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
            assertEquals("a", ready(f).session.sessionId)
            assertEquals(1, f.deletions.deleteCalls)
            assertEquals(0, f.deletions.committed.size)
        }

    @Test fun ownSourceDisappearanceBeforeTypedDeletedDoesNotCancelTheCommittedWorkerOrSelectAnotherRecord() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            var worker: Job? = null
            f.deletions.deleteAction = { value, canCommit ->
                assertTrue(canCommit())
                worker = currentCoroutineContext()[Job]
                f.sessions.values.value =
                    f.sessions.values.value
                        .filterNot { it.sessionId == value.session.sessionId }
                release.await()
                assertTrue(checkNotNull(worker).isActive)
                // Observed disappearance is not permission to perform another write.
                assertFalse(canCommit())
                SessionDeletionResult.Deleted
            }
            subscribe(f)
            val confirmation = confirming(f)
            f.model.confirmDeletion(confirmation.nonce)
            runCurrent()
            assertTrue(checkNotNull(worker).isActive)
            assertTrue(f.model.deletionState.value is SessionDeletionUiState.Deleting)
            assertTrue(f.model.canPresentDeletion(confirmation.nonce))
            assertTrue(f.model.uiState.value is SessionHistoryUiState.SelectionChanged)
            release.complete(Unit)
            runCurrent()
            assertEquals(SessionDeletionUiState.Deleted(confirmation.nonce, session("a")), f.model.deletionState.value)
            assertTrue(f.model.canPresentDeletion(confirmation.nonce))
            val changed = f.model.uiState.value as SessionHistoryUiState.SelectionChanged
            assertTrue(f.model.resetToLatest(changed.generation))
            runCurrent()
            assertEquals("b", ready(f).session.sessionId)
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
            assertFalse(f.model.canPresentDeletion(confirmation.nonce))
        }

    @Test fun disappearanceWithoutATypedDeletedResultIsNeverADeletionSuccess() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            f.deletions.deleteAction = { value, _ ->
                f.sessions.values.value =
                    f.sessions.values.value
                        .filterNot { it.sessionId == value.session.sessionId }
                release.await()
                SessionDeletionResult.Missing
            }
            subscribe(f)
            val confirmation = confirming(f)
            f.model.confirmDeletion(confirmation.nonce)
            runCurrent()
            assertTrue(f.model.deletionState.value is SessionDeletionUiState.Deleting)
            release.complete(Unit)
            runCurrent()
            assertEquals(
                SessionDeletionUiState.FailureReason.MISSING,
                (f.model.deletionState.value as SessionDeletionUiState.Failed).reason,
            )
            assertTrue(f.model.uiState.value is SessionHistoryUiState.SelectionChanged)
            assertFalse(f.model.deletionState.value is SessionDeletionUiState.Deleted)
        }

    @Test fun changedMemoTargetRequiresAnExplicitNewPreparationAndNewNonce() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val previous = confirming(f)
            f.deletions.deleteAction = { _, _ -> SessionDeletionResult.Changed }
            f.model.confirmDeletion(previous.nonce)
            runCurrent()
            assertEquals(
                SessionDeletionUiState.FailureReason.CHANGED,
                (f.model.deletionState.value as SessionDeletionUiState.Failed).reason,
            )
            assertEquals(1, f.deletions.prepareCalls)
            assertFalse(f.model.confirmDeletion(previous.nonce))
            f.deletions.prepareAction = { expected, _ ->
                SessionDeletionPreparation.Ready(target(expected).copy(memoVersion = "memo-version-2"))
            }
            val fresh = confirming(f)
            assertEquals("memo-version-2", fresh.target.memoVersion)
            assertNotEquals(previous.nonce, fresh.nonce)
            assertEquals(2, f.deletions.prepareCalls)
            assertEquals(1, f.deletions.deleteCalls)
        }

    @Test fun storageDeletionExceptionPreservesTheSourceAndReportsOnlyAStorageFailure() =
        runTest {
            val f = Fixture()
            val original = f.sessions.values.value
            subscribe(f)
            val confirmation = confirming(f)
            f.deletions.deleteAction = { _, _ -> throw IOException("fixture delete failure") }
            f.model.confirmDeletion(confirmation.nonce)
            runCurrent()
            assertEquals(
                SessionDeletionUiState.FailureReason.DELETE_FAILED,
                (f.model.deletionState.value as SessionDeletionUiState.Failed).reason,
            )
            assertEquals(original, f.sessions.values.value)
            assertTrue(f.model.canPresentDeletion(confirmation.nonce))
            assertEquals(0, f.deletions.committed.size)
        }

    @Test fun independentDeletionCancellationDiscardsTheOwnerWithoutStorageErrorFeedback() =
        runTest {
            val f = Fixture()
            val original = f.sessions.values.value
            subscribe(f)
            val confirmation = confirming(f)
            f.deletions.deleteAction = { _, _ -> throw CancellationException("fixture delete cancellation") }
            f.model.confirmDeletion(confirmation.nonce)
            runCurrent()
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
            assertEquals(original, f.sessions.values.value)
            assertFalse(f.model.confirmDeletion(confirmation.nonce))
            assertTrue(f.model.requestDeletion("a", ready(f).generation, f.keepOpen))
            runCurrent()
            assertTrue(f.model.deletionState.value is SessionDeletionUiState.Confirming)
        }

    @Test fun dismissDuringDeletionRevokesThePredicateAndLateCompletionCannotPublishSuccess() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            f.deletions.deleteAction = { _, canCommit ->
                assertTrue(canCommit())
                withContext(NonCancellable) { release.await() }
                assertFalse(canCommit())
                SessionDeletionResult.Blocked
            }
            subscribe(f)
            val confirmation = confirming(f)
            f.model.confirmDeletion(confirmation.nonce)
            runCurrent()
            assertTrue(f.model.dismissDeletion(confirmation.nonce))
            release.complete(Unit)
            runCurrent()
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
            assertEquals(0, f.deletions.committed.size)
            assertEquals("a", ready(f).session.sessionId)
        }

    @Test fun typedDeletedBeforeObserverDeliveryKeepsATombstoneUntilExplicitReset() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val confirmation = confirming(f)
            f.deletions.deleteAction = { _, canCommit ->
                assertTrue(canCommit())
                SessionDeletionResult.Deleted
            }
            f.model.confirmDeletion(confirmation.nonce)
            runCurrent()
            assertTrue(f.model.deletionState.value is SessionDeletionUiState.Deleted)
            assertTrue(f.model.uiState.value is SessionHistoryUiState.SelectionChanged)
            f.sessions.values.value = f.sessions.values.value + session("older")
            runCurrent()
            assertTrue(f.model.deletionState.value is SessionDeletionUiState.Deleted)
            assertTrue(f.model.uiState.value is SessionHistoryUiState.SelectionChanged)
            f.sessions.values.value =
                f.sessions.values.value
                    .filterNot { it.sessionId == "a" }
            runCurrent()
            assertTrue(f.model.deletionState.value is SessionDeletionUiState.Deleted)
            val changed = f.model.uiState.value as SessionHistoryUiState.SelectionChanged
            assertTrue(f.model.resetToLatest(changed.generation))
            runCurrent()
            assertEquals("b", ready(f).session.sessionId)
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
        }

    @Test fun expiredHostDuringPreparationFinishesIdleWithoutAStuckLoadingDialog() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            f.deletions.prepareAction = { expected, canRead ->
                release.await()
                assertFalse(canRead())
                SessionDeletionPreparation.Ready(target(expected))
            }
            subscribe(f)
            val current = ready(f)
            f.model.requestDeletion("a", current.generation, f.keepOpen)
            runCurrent()
            f.host.set(false)
            release.complete(Unit)
            runCurrent()
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
            assertEquals(0, f.deletions.deleteCalls)
        }

    private fun TestScope.subscribe(f: Fixture): Job {
        val reader = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.model.uiState.collect {} }
        runCurrent()
        return reader
    }

    private fun TestScope.ready(f: Fixture): SessionHistoryUiState.Ready {
        runCurrent()
        return f.model.uiState.value as SessionHistoryUiState.Ready
    }

    private fun TestScope.confirming(f: Fixture): SessionDeletionUiState.Confirming {
        val current = ready(f)
        assertTrue(f.model.requestDeletion(current.session.sessionId, current.generation, f.keepOpen))
        runCurrent()
        return f.model.deletionState.value as SessionDeletionUiState.Confirming
    }

    private class Fixture {
        val vehicle = MutableStateFlow(parked())
        val restrictions = MutableStateFlow(unrestricted())
        val host = AtomicBoolean(true)
        val keepOpen: () -> Boolean = { host.get() }
        val sessions = FakeSessions()
        val deletions = FakeDeletions()
        val observations =
            object : VehicleObservationRepository {
                override fun observe(sessionId: String): Flow<List<SessionVehicleObservation>> =
                    error("Stored observation queries are outside this fixture")

                override suspend fun append(
                    sessionId: String,
                    observation: VehicleObservation,
                ): VehicleObservationAppendResult = error("Stored observation writes are outside this fixture")
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
            )

        init {
            deletions.prepareAction = { expected, canRead ->
                if (canRead()) SessionDeletionPreparation.Ready(target(expected)) else SessionDeletionPreparation.Blocked
            }
            deletions.deleteAction = { value, canCommit ->
                if (canCommit()) {
                    deletions.committed += value
                    sessions.values.value = sessions.values.value.filterNot { it.sessionId == value.session.sessionId }
                    SessionDeletionResult.Deleted
                } else {
                    SessionDeletionResult.Blocked
                }
            }
        }
    }

    private class FakeDeletions : SessionDeletionRepository {
        var prepareCalls = 0
        var deleteCalls = 0
        val committed = mutableListOf<SessionDeletionTarget>()
        var prepareAction: suspend (PracticeSession, () -> Boolean) -> SessionDeletionPreparation = { _, _ -> error("unconfigured") }
        var deleteAction: suspend (SessionDeletionTarget, () -> Boolean) -> SessionDeletionResult = { _, _ -> error("unconfigured") }

        override suspend fun prepare(
            expected: PracticeSession,
            canRead: () -> Boolean,
        ): SessionDeletionPreparation {
            prepareCalls++
            return prepareAction(expected, canRead)
        }

        override suspend fun delete(
            target: SessionDeletionTarget,
            canCommit: () -> Boolean,
        ): SessionDeletionResult {
            deleteCalls++
            return deleteAction(target, canCommit)
        }
    }

    private class FakeSessions : PracticeSessionRepository {
        val values = MutableStateFlow(listOf(session("active").copy(endedAtEpochMillis = null), session("a"), session("b")))
        var source: () -> Flow<List<PracticeSession>> = { values }
        var reads = 0
        var active = 0
        var maximumActive = 0
        var writes = 0

        override fun observeSessions(): Flow<List<PracticeSession>> {
            reads++
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
            error("History must not start sessions")
        }

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult {
            writes++
            error("History must not finish sessions")
        }
    }

    private companion object {
        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        fun unrestricted() = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)

        fun session(id: String) =
            PracticeSession(
                id,
                PracticeSelection("goal", "course", 1, 1, "task-" + id),
                "Fixture goal",
                "Fixture course",
                "Fixture task " + id,
                CourseReviewStatus.DEMO,
                1_000L,
                2_000L,
            )

        fun target(session: PracticeSession) = SessionDeletionTarget(session, 2L, "memo-version-1")
    }
}
