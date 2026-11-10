package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.LocationRecordingEvent
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeCourse
import io.github.hangillab.hangil.core.domain.PracticeGoal
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.PracticeTask
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionReviewRoad
import io.github.hangillab.hangil.core.domain.SessionReviewRoadRepository
import io.github.hangillab.hangil.core.domain.SessionReviewRoadWriteResult
import io.github.hangillab.hangil.core.domain.SessionReviewSample
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** Production ViewModel with controlled domain inputs; no SDK, Room or memo writer is involved. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionReviewRoadViewModelTest {
    @Before fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun resetMain() {
        Dispatchers.resetMain()
    }

    @Test fun coldReadsRequireTheWholeEndedSourceAndMatchingCatalogCandidates() =
        runTest {
            val mismatch = Fixture()
            mismatch.vm.observe(mismatch.source, mismatch::hostAllowed)
            runCurrent()
            assertEquals(0, mismatch.sessions.reads)
            assertTrue(mismatch.reviews.requested.isEmpty())
            mismatch.sessions.values.value = listOf(mismatch.source.copy(taskTitle = "Changed task"))
            val unavailable = watch(mismatch)
            runCurrent()
            assertTrue(unavailable.last() is SessionReviewRoadUiState.Unavailable)
            assertTrue(mismatch.reviews.requested.isEmpty())
            unavailable.job.cancelAndJoin()

            val exact = Fixture()
            val current = watch(exact)
            runCurrent()
            assertEquals(exact.source, current.ready().source)
            assertEquals(listOf(firstRoad(), secondRoad()), current.ready().candidates)
            assertEquals(listOf(exact.source.sessionId), exact.reviews.requested)
            assertTrue(exact.reviews.saved.isEmpty())

            val changedCatalog = Fixture()
            changedCatalog.catalog.courses = changedCatalog.catalog.courses.map { it.copy(version = 2) }
            val outdated = watch(changedCatalog)
            runCurrent()
            assertTrue(outdated.ready().candidates.isEmpty())
            assertFalse(changedCatalog.vm.select(outdated.ready().token, firstRoad().id))
            assertTrue(changedCatalog.reviews.saved.isEmpty())
        }

    @Test fun selectingAndCancellingStayLocalAndSaveUsesTheDisplayedWholeCasOnce() =
        runTest {
            val f = Fixture()
            val trace = watch(f)
            runCurrent()
            assertTrue(f.vm.select(trace.ready().token, firstRoad().id))
            runCurrent()
            assertEquals(firstRoad().id, trace.ready().draftRoadId)
            assertTrue(f.reviews.saved.isEmpty())
            assertTrue(f.vm.cancel(trace.ready().token))
            runCurrent()
            assertNull(trace.ready().draftRoadId)
            assertTrue(f.reviews.saved.isEmpty())

            assertTrue(f.vm.select(trace.ready().token, secondRoad().id))
            runCurrent()
            val firstSave = trace.ready().token
            assertTrue(f.vm.save(firstSave))
            assertFalse(f.vm.save(firstSave))
            runCurrent()
            val request = f.reviews.saved.single()
            assertEquals(f.source, request.source)
            assertEquals(secondRoad(), request.road)
            assertNull(request.expectedCurrent)
            val stored = checkNotNull(trace.ready().stored)
            assertEquals(secondRoad(), stored.road)
            assertEquals("USER_SELECTION", stored.origin)
            assertEquals(SessionReviewRoadUiState.Feedback.SAVED, trace.ready().feedback)

            assertTrue(f.vm.select(trace.ready().token, firstRoad().id))
            runCurrent()
            assertTrue(f.vm.save(trace.ready().token))
            runCurrent()
            assertEquals(
                stored,
                f.reviews.saved
                    .last()
                    .expectedCurrent,
            )
            assertEquals(firstRoad(), trace.ready().stored?.road)
            assertEquals(2, f.reviews.saved.size)
        }

    @Test fun savedSnapshotRemainsReadableWithoutCatalogAndClearSurvivesReentry() =
        runTest {
            val original = SessionReviewRoad(ended(), firstRoad().copy(title = "Retired public road snapshot"), "original-version")
            val f = Fixture(original)
            f.catalog.courses = emptyList()
            val trace = watch(f)
            runCurrent()
            assertEquals(original, trace.ready().stored)
            assertTrue(trace.ready().candidates.isEmpty())
            assertEquals("USER_SELECTION", trace.ready().stored?.origin)
            val clear = trace.ready().token
            assertTrue(f.vm.clear(clear))
            assertFalse(f.vm.clear(clear))
            runCurrent()
            assertEquals(
                original,
                f.reviews.cleared
                    .single()
                    .expectedCurrent,
            )
            assertEquals(
                f.source,
                f.reviews.cleared
                    .single()
                    .source,
            )
            assertNull(trace.ready().stored)
            assertEquals(SessionReviewRoadUiState.Feedback.CLEARED, trace.ready().feedback)
            trace.job.cancelAndJoin()

            val reopened = watch(f)
            runCurrent()
            assertNull(reopened.ready().stored)
            assertTrue(reopened.ready().candidates.isEmpty())
            assertTrue(f.reviews.saved.isEmpty())
            assertEquals(1, f.reviews.cleared.size)
        }

    @Test fun changedSourceAndDisposedHostNeverReuseAnOldSelection() =
        runTest {
            val f = Fixture()
            val trace = watch(f)
            runCurrent()
            assertTrue(f.vm.select(trace.ready().token, firstRoad().id))
            runCurrent()
            val old = trace.ready().token
            f.sessions.values.value = listOf(f.source.copy(taskTitle = "Replacement source"))
            runCurrent()
            assertFalse(f.vm.canRead(old))
            assertFalse(f.vm.save(old))
            assertFalse(f.vm.clear(old))
            f.sessions.values.value = listOf(f.source)
            runCurrent()
            assertFalse(f.vm.select(old, firstRoad().id))
            assertFalse(f.vm.save(old))
            trace.job.cancelAndJoin()

            val reopened = watch(f)
            runCurrent()
            assertTrue(f.vm.select(reopened.ready().token, secondRoad().id))
            runCurrent()
            val disposed = reopened.ready().token
            reopened.job.cancelAndJoin()
            assertFalse(f.vm.canRead(disposed))
            assertFalse(f.vm.save(disposed))
            assertTrue(f.reviews.saved.isEmpty())
            assertTrue(f.reviews.cleared.isEmpty())
        }

    @Test fun observedDrivingOrUnknownUxCannotBorrowANewParkedContext() =
        runTest {
            for (denyUx in listOf(false, true)) {
                val f = Fixture()
                val trace = watch(f)
                runCurrent()
                assertTrue(f.vm.select(trace.ready().token, firstRoad().id))
                runCurrent()
                val old = trace.ready().token
                if (denyUx) {
                    f.restrictions.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNKNOWN)
                } else {
                    f.vehicle.state.value = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))
                }
                // A direct action observes denial before scheduled flow delivery can restore P.
                assertFalse(f.vm.save(old))
                f.vehicle.state.value = parked()
                f.restrictions.restrictions.value = unrestricted()
                runCurrent()
                assertFalse(f.vm.canRead(old))
                assertFalse(f.vm.select(old, secondRoad().id))
                assertFalse(f.vm.cancel(old))
                assertFalse(f.vm.save(old))
                assertTrue(f.reviews.saved.isEmpty())
                trace.job.cancelAndJoin()
            }
        }

    @Test fun anotherActiveSessionAllowsSavedReadingButRetiresPendingWrites() =
        runTest {
            val original = SessionReviewRoad(ended(), firstRoad(), "v1")
            val f = Fixture(original)
            val trace = watch(f)
            runCurrent()
            assertTrue(f.vm.select(trace.ready().token, secondRoad().id))
            runCurrent()
            val old = trace.ready().token
            f.sessions.values.value = listOf(ended("active").copy(endedAtEpochMillis = null), f.source)
            runCurrent()
            assertEquals(original, trace.ready().stored)
            assertFalse(trace.ready().canWrite)
            assertFalse(f.vm.save(old))
            assertFalse(f.vm.select(trace.ready().token, secondRoad().id))
            assertFalse(f.vm.clear(trace.ready().token))
            f.sessions.values.value = listOf(f.source)
            runCurrent()
            assertFalse(f.vm.save(old))
            assertEquals(original, trace.ready().stored)
            assertTrue(f.reviews.saved.isEmpty())
            assertTrue(f.reviews.cleared.isEmpty())
        }

    @Test fun retiredHostClosesItsReadWhileTheOuterCollectorRemainsAlive() =
        runTest {
            val f = Fixture()
            val trace = watch(f)
            runCurrent()
            assertEquals(1, f.reviews.active)
            val old = trace.ready().token
            f.host = false
            assertFalse(f.vm.canRead(old))
            runCurrent()
            assertTrue(trace.job.isActive)
            assertEquals(0, f.reviews.active)

            f.host = true
            val reopened = watch(f)
            runCurrent()
            assertEquals(1, f.reviews.active)
            assertTrue(f.vm.canRead(reopened.ready().token))
            assertFalse(f.vm.canRead(old))
            reopened.job.cancelAndJoin()
            runCurrent()
            assertEquals(0, f.reviews.active)
            trace.job.cancelAndJoin()
            assertEquals(0, f.reviews.active)
        }

    @Test fun uncertainCommittedWriteRequiresExplicitFreshReadWithoutAutomaticResave() =
        runTest {
            val f = Fixture()
            f.reviews.saveAction = { request ->
                check(request.canCommit())
                f.reviews.value.value = SessionReviewRoad(request.source, request.road, "committed-before-delivery-failure")
                throw IOException("Controlled result delivery failure")
            }
            val trace = watch(f)
            runCurrent()
            assertTrue(f.vm.select(trace.ready().token, firstRoad().id))
            runCurrent()
            assertTrue(f.vm.save(trace.ready().token))
            runCurrent()
            val failed = trace.last() as SessionReviewRoadUiState.Failed
            assertTrue(failed.writeUnconfirmed)
            assertEquals(1, f.reviews.saved.size)
            assertFalse(f.vm.save(failed.token))
            val readsBeforeRetry = f.reviews.requested.size
            assertTrue(f.vm.retry(failed.token))
            runCurrent()
            assertTrue(f.reviews.requested.size > readsBeforeRetry)
            assertEquals(f.reviews.value.value, trace.ready().stored)
            assertEquals("committed-before-delivery-failure", trace.ready().stored?.version)
            assertFalse(f.vm.retry(failed.token))
            assertEquals(1, f.reviews.saved.size)
            assertTrue(f.reviews.cleared.isEmpty())
        }

    @Test fun memoEditingRequiresStoredRoadAndRetiresOldTypingCallbacks() =
        runTest {
            val absent = Fixture()
            val empty = watch(absent)
            runCurrent()
            assertFalse(absent.vm.beginMemo(empty.ready().token))
            assertTrue(absent.reviews.saved.isEmpty())

            val original = SessionReviewRoad(ended(), firstRoad(), "original", "Original road note")
            val f = Fixture(original)
            val trace = watch(f)
            runCurrent()
            assertTrue(f.vm.select(trace.ready().token, secondRoad().id))
            runCurrent()
            assertFalse(f.vm.beginMemo(trace.ready().token))
            assertTrue(f.vm.cancel(trace.ready().token))
            runCurrent()
            assertTrue(f.vm.beginMemo(trace.ready().token))
            runCurrent()
            val receipt = trace.ready().token
            val edit = checkNotNull(trace.ready().edit)
            assertEquals(original.memoText, edit.text)
            val exact = "  Merge check\nSecond line 🚗  "
            assertTrue(f.vm.editMemo(receipt, edit.nonce, exact))
            runCurrent()
            val typed = trace.ready().token
            assertFalse(receipt == typed)
            assertEquals(edit.nonce, trace.ready().edit?.nonce)
            assertEquals(exact, trace.ready().edit?.text)
            assertFalse(f.vm.saveMemo(receipt, edit.nonce))
            assertFalse(f.vm.editMemo(receipt, edit.nonce, "Old typing callback"))
            assertEquals(exact, trace.ready().edit?.text)
            assertFalse(f.vm.select(typed, secondRoad().id))
            assertFalse(f.vm.save(typed))
            assertFalse(f.vm.clear(typed))
            assertTrue(f.reviews.saved.isEmpty())
            assertTrue(f.vm.cancelMemo(typed, edit.nonce))
            runCurrent()
            assertNull(trace.ready().edit)
            assertEquals(original, trace.ready().stored)
            assertFalse(f.vm.editMemo(typed, edit.nonce, "Retired draft"))
            assertFalse(f.vm.saveMemo(typed, edit.nonce))
            assertTrue(f.reviews.saved.isEmpty())

            assertTrue(f.vm.beginMemo(trace.ready().token))
            runCurrent()
            val next = checkNotNull(trace.ready().edit)
            val current = trace.ready().token
            assertFalse(next.nonce == edit.nonce)
            assertFalse(f.vm.editMemo(current, edit.nonce, "Wrong edit nonce"))
            assertTrue(f.vm.editMemo(current, next.nonce, exact))
            runCurrent()
            val latest = trace.ready().token
            assertFalse(current == latest)
            assertEquals(next.nonce, trace.ready().edit?.nonce)
            assertFalse(f.vm.saveMemo(current, next.nonce))
            assertFalse(f.vm.editMemo(current, next.nonce, "Superseded content"))
            assertTrue(f.reviews.saved.isEmpty())
            assertTrue(f.vm.saveMemo(latest, next.nonce))
            assertFalse(f.vm.saveMemo(latest, next.nonce))
            runCurrent()
            val request = f.reviews.saved.single()
            assertEquals(f.source, request.source)
            assertEquals(original.road, request.road)
            assertEquals(original, request.expectedCurrent)
            assertEquals(exact, request.memoText)
            assertFalse(request.discardMemo)
            assertEquals(exact, trace.ready().stored?.memoText)
            assertNull(trace.ready().edit)
            assertFalse(f.vm.editMemo(latest, next.nonce, "After save"))
        }

    @Test fun replacingOrClearingANotedRoadRequiresCurrentDiscardConfirmationExactlyOnce() =
        runTest {
            val original = SessionReviewRoad(ended(), firstRoad(), "noted-road", "Keep until confirmed")
            val f = Fixture(original)
            val trace = watch(f)
            runCurrent()
            assertTrue(f.vm.select(trace.ready().token, secondRoad().id))
            runCurrent()
            assertEquals(original, trace.ready().stored)
            assertTrue(f.reviews.saved.isEmpty())
            assertTrue(f.vm.save(trace.ready().token))
            runCurrent()
            val firstToken = trace.ready().token
            val first = checkNotNull(trace.ready().confirmation)
            assertEquals(secondRoad(), first.target)
            assertEquals(original, trace.ready().stored)
            assertTrue(f.reviews.saved.isEmpty())
            assertTrue(f.reviews.cleared.isEmpty())
            assertTrue(f.vm.cancelDiscard(firstToken, first.nonce))
            runCurrent()
            assertNull(trace.ready().confirmation)
            assertEquals(original, trace.ready().stored)
            assertFalse(f.vm.confirmDiscard(firstToken, first.nonce))
            assertTrue(f.reviews.saved.isEmpty())

            if (trace.ready().draft != secondRoad()) {
                assertTrue(f.vm.select(trace.ready().token, secondRoad().id))
                runCurrent()
            }
            assertTrue(f.vm.save(trace.ready().token))
            runCurrent()
            val secondToken = trace.ready().token
            val second = checkNotNull(trace.ready().confirmation)
            assertTrue(f.vm.confirmDiscard(secondToken, second.nonce))
            assertFalse(f.vm.confirmDiscard(secondToken, second.nonce))
            runCurrent()
            val replacement = f.reviews.saved.single()
            assertEquals(original, replacement.expectedCurrent)
            assertEquals(secondRoad(), replacement.road)
            assertEquals("", replacement.memoText)
            assertTrue(replacement.discardMemo)
            assertEquals(secondRoad(), trace.ready().stored?.road)
            assertEquals("", trace.ready().stored?.memoText)

            val clearFixture = Fixture(original)
            val clearing = watch(clearFixture)
            runCurrent()
            assertTrue(clearFixture.vm.clear(clearing.ready().token))
            runCurrent()
            val cancelToken = clearing.ready().token
            val cancel = checkNotNull(clearing.ready().confirmation)
            assertNull(cancel.target)
            assertTrue(clearFixture.reviews.cleared.isEmpty())
            assertTrue(clearFixture.vm.cancelDiscard(cancelToken, cancel.nonce))
            runCurrent()
            assertEquals(original, clearing.ready().stored)
            assertTrue(clearFixture.reviews.cleared.isEmpty())
            assertTrue(clearFixture.vm.clear(clearing.ready().token))
            runCurrent()
            val clearToken = clearing.ready().token
            val clear = checkNotNull(clearing.ready().confirmation)
            assertFalse(clearFixture.vm.confirmDiscard(cancelToken, cancel.nonce))
            assertTrue(clearFixture.vm.confirmDiscard(clearToken, clear.nonce))
            assertFalse(clearFixture.vm.confirmDiscard(clearToken, clear.nonce))
            runCurrent()
            assertEquals(
                original,
                clearFixture.reviews.cleared
                    .single()
                    .expectedCurrent,
            )
            assertTrue(
                clearFixture.reviews.cleared
                    .single()
                    .discardMemo,
            )
            assertNull(clearing.ready().stored)
        }

    @Test fun memoCanBeEditedOnTheExactStoredSnapshotEvenWhenTheCatalogHasGone() =
        runTest {
            val archived = firstRoad().copy(title = "Archived exact snapshot", osmWayVersion = 1)
            val original = SessionReviewRoad(ended(), archived, "archived", "Old road note")
            val f = Fixture(original)
            f.catalog.courses = emptyList()
            val trace = watch(f)
            runCurrent()
            assertTrue(trace.ready().candidates.isEmpty())
            assertEquals(archived, trace.ready().stored?.road)
            assertTrue(f.vm.beginMemo(trace.ready().token))
            runCurrent()
            val current = trace.ready().token
            val edit = checkNotNull(trace.ready().edit)
            assertTrue(f.vm.editMemo(current, edit.nonce, "Updated road note"))
            runCurrent()
            assertFalse(f.vm.saveMemo(current, edit.nonce))
            assertTrue(f.vm.saveMemo(trace.ready().token, edit.nonce))
            runCurrent()
            val request = f.reviews.saved.single()
            assertEquals(archived, request.road)
            assertEquals(original, request.expectedCurrent)
            assertEquals("Updated road note", request.memoText)
            assertFalse(request.discardMemo)
            assertEquals(archived, trace.ready().stored?.road)
            assertEquals("Updated road note", trace.ready().stored?.memoText)
        }

    @Test fun failedMemoWriteRestoresDraftAfterFreshReadAndWaitsForAnExplicitNewSave() =
        runTest {
            val original = SessionReviewRoad(ended(), firstRoad(), "original", "Saved note A")
            val draft = "Unsaved note B 🚗"
            val f = Fixture(original)
            val successfulSave = f.reviews.saveAction
            f.reviews.saveAction = { request ->
                if (f.reviews.saved.size == 1) {
                    check(request.canCommit())
                    throw IOException("Controlled failure before write")
                }
                successfulSave(request)
            }
            val trace = watch(f)
            runCurrent()
            assertTrue(f.vm.beginMemo(trace.ready().token))
            runCurrent()
            val beforeTyping = trace.ready().token
            val edit = checkNotNull(trace.ready().edit)
            assertTrue(f.vm.editMemo(beforeTyping, edit.nonce, draft))
            runCurrent()
            val submitted = trace.ready().token
            assertFalse(f.vm.saveMemo(beforeTyping, edit.nonce))
            assertTrue(f.vm.saveMemo(submitted, edit.nonce))
            runCurrent()
            val failed = trace.last() as SessionReviewRoadUiState.Failed
            assertTrue(failed.writeUnconfirmed)
            assertEquals(draft, failed.retainedMemo)
            assertEquals(original, f.reviews.value.value)
            assertEquals(1, f.reviews.saved.size)

            val readsBeforeRetry = f.reviews.requested.size
            assertTrue(f.vm.retry(failed.token))
            runCurrent()
            assertTrue(f.reviews.requested.size > readsBeforeRetry)
            val restored = checkNotNull(trace.ready().edit)
            val current = trace.ready().token
            assertEquals(draft, restored.text)
            assertFalse(edit.nonce == restored.nonce)
            assertEquals(original, trace.ready().stored)
            assertEquals(1, f.reviews.saved.size)
            assertFalse(f.vm.saveMemo(submitted, edit.nonce))
            assertFalse(f.vm.editMemo(submitted, edit.nonce, "Retired failed input"))
            assertFalse(f.vm.saveMemo(current, edit.nonce))
            assertEquals(1, f.reviews.saved.size)

            assertTrue(f.vm.saveMemo(current, restored.nonce))
            assertFalse(f.vm.saveMemo(current, restored.nonce))
            runCurrent()
            assertEquals(2, f.reviews.saved.size)
            val retried = f.reviews.saved.last()
            assertEquals(original, retried.expectedCurrent)
            assertEquals(original.source, retried.source)
            assertEquals(original.road, retried.road)
            assertEquals(draft, retried.memoText)
            assertFalse(retried.discardMemo)
            assertEquals(draft, trace.ready().stored?.memoText)
            assertFalse(original.version == trace.ready().stored?.version)
            assertNull(trace.ready().edit)
            assertTrue(f.reviews.cleared.isEmpty())
        }

    @Test fun uncertainMemoCommitNeedsFreshReadAndPreservesItsExactRoadAndNote() =
        runTest {
            val original = SessionReviewRoad(ended(), firstRoad(), "original", "Original note")
            val f = Fixture(original)
            f.reviews.saveAction = { request ->
                check(request.canCommit())
                f.reviews.value.value = SessionReviewRoad(request.source, request.road, "committed-note", request.memoText)
                throw IOException("Controlled note result delivery failure")
            }
            val trace = watch(f)
            runCurrent()
            assertTrue(f.vm.beginMemo(trace.ready().token))
            runCurrent()
            val current = trace.ready().token
            val edit = checkNotNull(trace.ready().edit)
            assertTrue(f.vm.editMemo(current, edit.nonce, "Committed note 🚗"))
            runCurrent()
            val latest = trace.ready().token
            assertFalse(f.vm.saveMemo(current, edit.nonce))
            assertTrue(f.vm.saveMemo(latest, edit.nonce))
            runCurrent()
            val failed = trace.last() as SessionReviewRoadUiState.Failed
            assertTrue(failed.writeUnconfirmed)
            assertFalse(f.vm.saveMemo(latest, edit.nonce))
            assertFalse(f.vm.editMemo(latest, edit.nonce, "Retired note"))
            assertEquals(1, f.reviews.saved.size)
            val previousReads = f.reviews.requested.size
            assertTrue(f.vm.retry(failed.token))
            runCurrent()
            assertTrue(f.reviews.requested.size > previousReads)
            assertEquals(f.reviews.value.value, trace.ready().stored)
            assertEquals(original.source, trace.ready().stored?.source)
            assertEquals(original.road, trace.ready().stored?.road)
            assertEquals("Committed note 🚗", trace.ready().stored?.memoText)
            assertNull(trace.ready().edit)
            assertEquals(1, f.reviews.saved.size)
            assertTrue(f.reviews.cleared.isEmpty())
        }

    @Test fun sourceReplacementOrObservedLockRetiresTheOldMemoTokenAndNonce() =
        runTest {
            for (replaceSource in listOf(false, true)) {
                val original = SessionReviewRoad(ended(), firstRoad(), "original", "Original note")
                val f = Fixture(original)
                val trace = watch(f)
                runCurrent()
                assertTrue(f.vm.beginMemo(trace.ready().token))
                runCurrent()
                val beforeTyping = trace.ready().token
                val edit = checkNotNull(trace.ready().edit)
                assertTrue(f.vm.editMemo(beforeTyping, edit.nonce, "Unsaved note"))
                runCurrent()
                val old = trace.ready().token
                assertFalse(beforeTyping == old)
                if (replaceSource) {
                    f.sessions.values.value = listOf(f.source.copy(taskTitle = "New source"))
                    runCurrent()
                } else {
                    f.vehicle.state.value = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))
                    assertFalse(f.vm.saveMemo(old, edit.nonce))
                }
                f.vehicle.state.value = parked()
                f.sessions.values.value = listOf(f.source)
                runCurrent()
                assertFalse(f.vm.canRead(old))
                assertFalse(f.vm.editMemo(old, edit.nonce, "Old callback"))
                assertFalse(f.vm.saveMemo(old, edit.nonce))
                assertFalse(f.vm.cancelMemo(old, edit.nonce))
                assertEquals(original, f.reviews.value.value)
                assertTrue(f.reviews.saved.isEmpty())
                assertTrue(f.reviews.cleared.isEmpty())
                trace.job.cancelAndJoin()
            }
        }

    @Test fun explicitSampleLinkKeepsTheWholeRawOwnerAndMemoAndUnlinkLeavesTheRoad() =
        runTest {
            val original = SessionReviewRoad(ended(), firstRoad(), "original", "Original road memo")
            val f = Fixture(original)
            val candidate = linkedSample()
            val trace = watch(f)
            runCurrent()
            val before = trace.ready().token
            assertFalse(f.vm.setSample(before, linkedSample("foreign")))
            assertTrue(f.vm.setSample(before, candidate))
            runCurrent()
            val linked = checkNotNull(trace.ready().stored)
            assertEquals(candidate, linked.sampleAnchor)
            assertEquals(original.road, linked.road)
            assertEquals(original.memoText, linked.memoText)
            assertEquals(
                original,
                f.reviews.sampled
                    .single()
                    .expectedCurrent,
            )
            assertEquals(
                candidate,
                f.reviews.sampled
                    .single()
                    .sample,
            )
            assertFalse(f.vm.setSample(before, null))
            assertFalse(f.vm.setSample(trace.ready().token, candidate))
            assertEquals(SessionReviewRoadUiState.Feedback.SAMPLE_SAVED, trace.ready().feedback)
            assertTrue(f.vm.beginMemo(trace.ready().token))
            runCurrent()
            val edit = checkNotNull(trace.ready().edit)
            assertTrue(f.vm.editMemo(trace.ready().token, edit.nonce, "Updated memo"))
            runCurrent()
            assertTrue(f.vm.saveMemo(trace.ready().token, edit.nonce))
            runCurrent()
            assertEquals(candidate, trace.ready().stored?.sampleAnchor)
            assertEquals("Updated memo", trace.ready().stored?.memoText)
            assertTrue(f.vm.setSample(trace.ready().token, null))
            runCurrent()
            assertNull(trace.ready().stored?.sampleAnchor)
            assertEquals(original.road, trace.ready().stored?.road)
            assertEquals("Updated memo", trace.ready().stored?.memoText)
            assertEquals(SessionReviewRoadUiState.Feedback.SAMPLE_CLEARED, trace.ready().feedback)
            assertEquals(listOf(f.source), f.sessions.values.value)
            assertTrue(f.reviews.cleared.isEmpty())
        }

    @Test fun sampleIntentNeverBorrowsAReturnedHostOrAnotherActiveSession() =
        runTest {
            val original = SessionReviewRoad(ended(), firstRoad(), "original")
            val f = Fixture(original)
            val candidate = linkedSample()
            val trace = watch(f)
            runCurrent()
            val old = trace.ready().token
            val release = CompletableDeferred<Unit>()
            val saveSample = f.reviews.sampleAction
            f.reviews.sampleAction = { request ->
                withContext(NonCancellable) { release.await() }
                saveSample(request)
            }
            assertTrue(f.vm.setSample(old, candidate))
            runCurrent()
            val busy = trace.ready().token
            assertTrue(trace.ready().busy)
            assertTrue(busy != old)
            assertTrue(f.vm.canRead(busy))
            f.host = false
            // The displayed busy receipt observes the withdrawal; an already stale receipt cannot revoke its successor.
            assertFalse(f.vm.canRead(busy))
            f.host = true
            release.complete(Unit)
            runCurrent()
            assertEquals(original, f.reviews.value.value)
            assertFalse(f.vm.setSample(old, candidate))
            val reopened = watch(f)
            runCurrent()
            f.sessions.values.value = listOf(ended("active").copy(endedAtEpochMillis = null), f.source)
            runCurrent()
            assertFalse(reopened.ready().canWrite)
            assertFalse(f.vm.setSample(reopened.ready().token, candidate))
            assertEquals(1, f.reviews.sampled.size)
            assertEquals(original, reopened.ready().stored)
        }

    @Test fun anchoredRoadNeedsCurrentDiscardAndAConcurrentMemoCannotReuseAnOldSampleOrConfirmation() =
        runTest {
            val anchor = linkedSample()
            val original = SessionReviewRoad(ended(), firstRoad(), "original", sampleAnchor = anchor)
            val f = Fixture(original)
            val trace = watch(f)
            runCurrent()
            val oldSampleIntent = trace.ready().token
            assertTrue(f.vm.select(trace.ready().token, secondRoad().id))
            runCurrent()
            assertTrue(f.vm.save(trace.ready().token))
            runCurrent()
            val pending = checkNotNull(trace.ready().confirmation)
            val oldConfirm = trace.ready().token
            assertTrue(f.reviews.saved.isEmpty())
            val modified = SessionReviewRoad(ended(), firstRoad(), "new-note-version", "Changed note", anchor)
            f.reviews.value.value = modified
            runCurrent()
            val failed = trace.last() as SessionReviewRoadUiState.Failed
            assertFalse(f.vm.confirmDiscard(oldConfirm, pending.nonce))
            assertFalse(f.vm.setSample(oldSampleIntent, null))
            assertTrue(f.vm.retry(failed.token))
            runCurrent()
            assertEquals(anchor, trace.ready().stored?.sampleAnchor)
            assertTrue(f.vm.select(trace.ready().token, secondRoad().id))
            runCurrent()
            assertTrue(f.vm.save(trace.ready().token))
            runCurrent()
            val confirmed = checkNotNull(trace.ready().confirmation)
            assertTrue(f.vm.confirmDiscard(trace.ready().token, confirmed.nonce))
            runCurrent()
            assertEquals(secondRoad(), trace.ready().stored?.road)
            assertNull(trace.ready().stored?.sampleAnchor)
            assertEquals("", trace.ready().stored?.memoText)
            assertTrue(
                f.reviews.saved
                    .single()
                    .discardSample,
            )
            assertTrue(
                f.reviews.saved
                    .single()
                    .discardMemo,
            )
        }

    @Test fun sampleFailureAndLostCommittedReplyRequireReadbackWithoutAutomaticResubmission() =
        runTest {
            val original = SessionReviewRoad(ended(), firstRoad(), "original")
            val f = Fixture(original)
            val trace = watch(f)
            runCurrent()
            val candidate = linkedSample()
            val saveSample = f.reviews.sampleAction
            f.reviews.sampleAction = { throw IOException("Controlled before-write failure") }
            assertTrue(f.vm.setSample(trace.ready().token, candidate))
            runCurrent()
            var failed = trace.last() as SessionReviewRoadUiState.Failed
            assertTrue(failed.writeUnconfirmed)
            assertEquals(original, f.reviews.value.value)
            assertTrue(f.vm.retry(failed.token))
            runCurrent()
            assertEquals(1, f.reviews.sampled.size)
            assertNull(trace.ready().stored?.sampleAnchor)
            f.reviews.sampleAction = { request ->
                saveSample(request)
                throw IOException("Controlled post-COMMIT reply failure")
            }
            assertTrue(f.vm.setSample(trace.ready().token, candidate))
            runCurrent()
            failed = trace.last() as SessionReviewRoadUiState.Failed
            assertTrue(failed.writeUnconfirmed)
            assertEquals(
                candidate,
                f.reviews.value.value
                    ?.sampleAnchor,
            )
            assertTrue(f.vm.retry(failed.token))
            runCurrent()
            assertEquals(candidate, trace.ready().stored?.sampleAnchor)
            assertEquals(2, f.reviews.sampled.size)
            assertFalse(f.vm.setSample(trace.ready().token, candidate))
        }

    private fun linkedSample(sessionId: String = "session") =
        SessionReviewSample(
            LocationRecordingEvent(
                1,
                sessionId,
                "run",
                1,
                150,
                LocationUpdateReading.Sample(LocationUpdateSample(37.0, 127.0, -2.0, 421f, -5f, -42, 20)),
            ),
            "controlled-process",
            120,
            10,
        )

    private fun TestScope.watch(f: Fixture): Trace {
        val states = mutableListOf<SessionReviewRoadUiState>()
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.vm.observe(f.source, f::hostAllowed).collect { states += it }
            }
        return Trace(states, job)
    }

    private data class Trace(
        val states: List<SessionReviewRoadUiState>,
        val job: Job,
    ) {
        fun last() = states.last()

        fun ready() = last() as SessionReviewRoadUiState.Ready
    }

    private class Fixture(
        initial: SessionReviewRoad? = null,
    ) {
        val source = ended()
        val vehicle = FakeVehicle()
        val restrictions = FakeRestrictions()
        val sessions = FakeSessions(source)
        val reviews = FakeReviews(initial)
        val catalog = FakeCatalog()
        var host = true
        val vm = SessionReviewRoadViewModel(vehicle, restrictions, sessions, reviews, catalog)

        fun hostAllowed() = host
    }

    private class FakeVehicle : VehicleGateway {
        override val state = MutableStateFlow(parked())
    }

    private class FakeRestrictions : DrivingRestrictionsProvider {
        override val restrictions = MutableStateFlow(unrestricted())
    }

    private class FakeSessions(
        source: PracticeSession,
    ) : PracticeSessionRepository {
        val values = MutableStateFlow(listOf(source))
        var reads = 0

        override fun observeSessions(): Flow<List<PracticeSession>> {
            reads++
            return values
        }

        override suspend fun start(
            practice: PreparedPractice,
            startedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionStartResult = error("Review road never starts a session")

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult = error("Review road never finishes a session")
    }

    private class FakeCatalog : PracticeCatalog {
        override val goals = listOf(PracticeGoal("goal", "Fixture goal", "Fixture goal description"))
        override var courses =
            listOf(
                PracticeCourse(
                    "course",
                    1,
                    1,
                    "Fixture course",
                    "Public road candidates",
                    emptyList(),
                    listOf(PracticeTask("task", "Fixture task", "Fixture task description")),
                    setOf("goal"),
                    roadSnapshots = listOf(firstRoad(), secondRoad()),
                ),
            )
    }

    private data class SaveRequest(
        val source: PracticeSession,
        val road: CourseRoadSnapshot,
        val expectedCurrent: SessionReviewRoad?,
        val memoText: String,
        val discardMemo: Boolean,
        val discardSample: Boolean,
        val initialSample: SessionReviewSample?,
        val canCommit: () -> Boolean,
    )

    private data class ClearRequest(
        val source: PracticeSession,
        val expectedCurrent: SessionReviewRoad,
        val discardMemo: Boolean,
        val discardSample: Boolean,
        val canCommit: () -> Boolean,
    )

    private data class SampleRequest(
        val source: PracticeSession,
        val expectedCurrent: SessionReviewRoad,
        val sample: SessionReviewSample?,
        val canCommit: () -> Boolean,
    )

    private class FakeReviews(
        initial: SessionReviewRoad?,
    ) : SessionReviewRoadRepository {
        val value = MutableStateFlow(initial)
        val requested = mutableListOf<String>()
        var active = 0
        val saved = mutableListOf<SaveRequest>()
        val cleared = mutableListOf<ClearRequest>()
        val sampled = mutableListOf<SampleRequest>()
        var sampleAction: suspend (SampleRequest) -> SessionReviewRoadWriteResult = { request ->
            val candidate = request.sample
            when {
                !request.canCommit() -> SessionReviewRoadWriteResult.Blocked
                value.value != request.expectedCurrent -> SessionReviewRoadWriteResult.Conflict
                candidate != null && candidate.event.sessionId != request.source.sessionId -> SessionReviewRoadWriteResult.SampleChanged
                else -> {
                    val current = request.expectedCurrent
                    val updated =
                        SessionReviewRoad(request.source, current.road, "sample-version-${sampled.size}", current.memoText, request.sample)
                    value.value = updated
                    SessionReviewRoadWriteResult.Saved(updated)
                }
            }
        }
        var saveAction: suspend (SaveRequest) -> SessionReviewRoadWriteResult = { request ->
            val current = request.expectedCurrent
            val initialSample = request.initialSample
            when {
                !request.canCommit() -> SessionReviewRoadWriteResult.Blocked
                value.value != current || (current != null && initialSample != null) -> SessionReviewRoadWriteResult.Conflict
                initialSample != null && initialSample.event.sessionId != request.source.sessionId -> {
                    SessionReviewRoadWriteResult.SampleChanged
                }
                current?.memoText?.isNotEmpty() == true &&
                    current.road != request.road && !request.discardMemo -> {
                    SessionReviewRoadWriteResult.MemoPresent
                }
                current?.sampleAnchor != null &&
                    current.road != request.road &&
                    !request.discardSample -> {
                    SessionReviewRoadWriteResult.SamplePresent
                }
                else -> {
                    val retainedSample = current?.takeIf { it.road == request.road }?.sampleAnchor ?: initialSample
                    val review = SessionReviewRoad(request.source, request.road, "version-${saved.size}", request.memoText, retainedSample)
                    value.value = review
                    SessionReviewRoadWriteResult.Saved(review)
                }
            }
        }
        var clearAction: suspend (ClearRequest) -> SessionReviewRoadWriteResult = { request ->
            when {
                !request.canCommit() -> SessionReviewRoadWriteResult.Blocked
                value.value != request.expectedCurrent -> SessionReviewRoadWriteResult.Conflict
                request.expectedCurrent.memoText.isNotEmpty() && !request.discardMemo -> SessionReviewRoadWriteResult.MemoPresent
                request.expectedCurrent.sampleAnchor != null && !request.discardSample -> {
                    SessionReviewRoadWriteResult.SamplePresent
                }
                else -> {
                    value.value = null
                    SessionReviewRoadWriteResult.Cleared
                }
            }
        }

        override fun observe(sessionId: String): Flow<SessionReviewRoad?> {
            requested += sessionId
            return flow {
                active++
                try {
                    emitAll(value)
                } finally {
                    active--
                }
            }
        }

        override suspend fun save(
            expectedSource: PracticeSession,
            road: CourseRoadSnapshot,
            expectedCurrent: SessionReviewRoad?,
            memoText: String,
            discardMemo: Boolean,
            discardSample: Boolean,
            initialSample: SessionReviewSample?,
            canCommit: () -> Boolean,
        ): SessionReviewRoadWriteResult {
            val request =
                SaveRequest(expectedSource, road, expectedCurrent, memoText, discardMemo, discardSample, initialSample, canCommit)
            saved += request
            return saveAction(request)
        }

        override suspend fun setSample(
            expectedSource: PracticeSession,
            expectedCurrent: SessionReviewRoad,
            sample: SessionReviewSample?,
            canCommit: () -> Boolean,
        ): SessionReviewRoadWriteResult {
            val request = SampleRequest(expectedSource, expectedCurrent, sample, canCommit)
            sampled += request
            return sampleAction(request)
        }

        override suspend fun clear(
            expectedSource: PracticeSession,
            expectedCurrent: SessionReviewRoad,
            discardMemo: Boolean,
            discardSample: Boolean,
            canCommit: () -> Boolean,
        ): SessionReviewRoadWriteResult {
            val request = ClearRequest(expectedSource, expectedCurrent, discardMemo, discardSample, canCommit)
            cleared += request
            return clearAction(request)
        }
    }

    private companion object {
        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        fun unrestricted() = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)

        fun ended(id: String = "session") =
            PracticeSession(
                id,
                PracticeSelection("goal", "course", 1, 1, "task"),
                "Fixture goal",
                "Fixture course",
                "Fixture task",
                CourseReviewStatus.DEMO,
                100,
                200,
            )

        fun firstRoad() =
            CourseRoadSnapshot(
                "road-a",
                "First public road",
                "residential",
                10,
                2,
                1_000,
                listOf(CourseRoadSnapshot.Node(1, 1, 37.0, 127.0), CourseRoadSnapshot.Node(2, 1, 37.1, 127.1)),
            )

        fun secondRoad() =
            CourseRoadSnapshot(
                "road-b",
                "Second public road",
                "residential",
                20,
                3,
                1_000,
                listOf(CourseRoadSnapshot.Node(3, 1, 37.2, 127.2), CourseRoadSnapshot.Node(4, 1, 37.3, 127.3)),
            )
    }
}
