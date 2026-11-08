package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
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
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
        val canCommit: () -> Boolean,
    )

    private data class ClearRequest(
        val source: PracticeSession,
        val expectedCurrent: SessionReviewRoad,
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
        var saveAction: suspend (SaveRequest) -> SessionReviewRoadWriteResult = { request ->
            when {
                !request.canCommit() -> SessionReviewRoadWriteResult.Blocked
                value.value != request.expectedCurrent -> SessionReviewRoadWriteResult.Conflict
                else -> {
                    val review = SessionReviewRoad(request.source, request.road, "version-${saved.size}")
                    value.value = review
                    SessionReviewRoadWriteResult.Saved(review)
                }
            }
        }
        var clearAction: suspend (ClearRequest) -> SessionReviewRoadWriteResult = { request ->
            when {
                !request.canCommit() -> SessionReviewRoadWriteResult.Blocked
                value.value != request.expectedCurrent -> SessionReviewRoadWriteResult.Conflict
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
            canCommit: () -> Boolean,
        ): SessionReviewRoadWriteResult {
            val request = SaveRequest(expectedSource, road, expectedCurrent, canCommit)
            saved += request
            return saveAction(request)
        }

        override suspend fun clear(
            expectedSource: PracticeSession,
            expectedCurrent: SessionReviewRoad,
            canCommit: () -> Boolean,
        ): SessionReviewRoadWriteResult {
            val request = ClearRequest(expectedSource, expectedCurrent, canCommit)
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
