package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclaration
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclarationResult
import io.github.hangillab.hangil.core.domain.CourseCompletionOrigin
import io.github.hangillab.hangil.core.domain.CourseCompletionRepository
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Production VM integration with controlled stores; no Room, SDK or actual deletion is exercised. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionHistoryCourseCompletionDeletionBoundaryTest {
    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `dismissing deletion restores absent on the same collector with a fresh receipt`() =
        runTest {
            val f = Fixture()
            start(f)
            val originalParent = parent(f)
            val detail = open(f)
            val original = absent(detail)
            val collector = detail.job
            val deletion = deletionConfirmation(f)
            assertFalse(f.model.canReadCourseCompletion(original.token))
            assertEquals(0, f.completions.active)
            assertTrue(f.model.dismissDeletion(deletion.nonce))
            runCurrent()
            val restored = absent(detail)
            assertTrue(restored.canDeclare)
            assertNotEquals(original.token, restored.token)
            assertTrue(f.model.canReadCourseCompletion(restored.token))
            assertFalse(f.model.requestCourseCompletion(original.token))
            assertTrue(collector === detail.job && collector.isActive)
            assertEquals(originalParent, parent(f))
            assertEquals(listOf("a", "a"), f.completions.requested)
            assertEquals(2, f.completions.reads)
            assertEquals(1, f.completions.active)
            assertEquals(1, f.sessions.reads)
            assertEquals(0, f.completions.writes)
            assertEquals(0, f.deletions.deleteCalls)
        }

    @Test fun `dismissing deletion restores the exact saved declaration on the existing collector`() =
        runTest {
            val f = Fixture()
            val stored = declaration()
            f.completions.values.value = stored
            start(f)
            val originalParent = parent(f)
            val detail = open(f)
            val original = ready(detail)
            val deletion = deletionConfirmation(f)
            assertEquals(stored, deletion.target.completionDeclaration)
            assertFalse(f.model.canReadCourseCompletion(original.token))
            assertTrue(f.model.dismissDeletion(deletion.nonce))
            runCurrent()
            val restored = ready(detail)
            assertEquals(stored, restored.declaration)
            assertNotEquals(original.token, restored.token)
            assertTrue(f.model.canReadCourseCompletion(restored.token))
            assertFalse(f.model.requestCourseCompletion(restored.token))
            assertTrue(detail.job.isActive)
            assertEquals(originalParent, parent(f))
            assertEquals(2, f.completions.reads)
            assertEquals(1, f.sessions.reads)
            assertEquals(0, f.completions.writes)
        }

    @Test fun `old declaration consent stays retired after deletion dismissal and only fresh consent declares once`() =
        runTest {
            val f = Fixture()
            f.completions.declareAction = { _, _, guard ->
                assertTrue(guard())
                CourseCompletionDeclarationResult.Blocked
            }
            start(f)
            val detail = open(f)
            assertTrue(f.model.requestCourseCompletion(absent(detail).token))
            runCurrent()
            val old = detail.value as SessionHistoryCourseCompletionUiState.Confirming
            val deletion = deletionConfirmation(f)
            assertTrue(f.model.dismissDeletion(deletion.nonce))
            runCurrent()
            val restored = absent(detail)
            assertFalse(f.model.confirmCourseCompletion(old.token, old.nonce))
            assertFalse(f.model.dismissCourseCompletion(old.token, old.nonce))
            assertFalse(f.model.requestCourseCompletion(old.token))
            assertTrue(f.model.requestCourseCompletion(restored.token))
            runCurrent()
            val fresh = detail.value as SessionHistoryCourseCompletionUiState.Confirming
            assertNotEquals(old.token, fresh.token)
            assertNotEquals(old.nonce, fresh.nonce)
            assertFalse(f.model.confirmCourseCompletion(fresh.token, old.nonce))
            assertEquals(0, f.completions.writes)
            assertTrue(f.model.confirmCourseCompletion(fresh.token, fresh.nonce))
            runCurrent()
            val failed = detail.value as SessionHistoryCourseCompletionUiState.Failed
            assertEquals(SessionHistoryCourseCompletionUiState.FailureReason.BLOCKED, failed.reason)
            assertFalse(f.model.confirmCourseCompletion(fresh.token, fresh.nonce))
            assertFalse(f.model.confirmCourseCompletion(old.token, old.nonce))
            assertEquals(1, f.completions.writes)
            assertEquals(0, f.deletions.deleteCalls)
            assertEquals(1, f.sessions.reads)
        }

    @Test fun `preparation failure restores a fresh declaration read without resubscribing the parent`() =
        runTest {
            for (preparationThrows in listOf(false, true)) {
                val f = Fixture()
                f.deletions.prepareAction = { _, _ ->
                    if (preparationThrows) throw IOException("controlled deletion preparation failure")
                    SessionDeletionPreparation.Blocked
                }
                start(f)
                val originalParent = parent(f)
                val detail = open(f)
                val original = absent(detail)
                assertTrue(f.model.requestDeletion(originalParent.session.sessionId, originalParent.generation, f.host::get))
                runCurrent()
                val failure = f.model.deletionState.value as SessionDeletionUiState.Failed
                assertEquals(
                    if (preparationThrows) {
                        SessionDeletionUiState.FailureReason.PREPARE_FAILED
                    } else {
                        SessionDeletionUiState.FailureReason.BLOCKED
                    },
                    failure.reason,
                )
                val restored = absent(detail)
                assertNotEquals(original.token, restored.token)
                assertTrue(restored.canDeclare)
                assertTrue(f.model.canReadCourseCompletion(restored.token))
                assertFalse(f.model.requestCourseCompletion(original.token))
                assertEquals(originalParent, parent(f))
                assertTrue(detail.job.isActive)
                assertEquals(2, f.completions.reads)
                assertEquals(1, f.sessions.reads)
                assertEquals(0, f.completions.writes)
                assertEquals(0, f.deletions.deleteCalls)
            }
        }

    @Test fun `successful deletion never reopens the missing source or declares for another source`() =
        runTest {
            val f = Fixture()
            f.completions.values.value = declaration()
            f.deletions.deleteAction = { target, canCommit ->
                assertTrue(canCommit())
                f.sessions.values.value =
                    f.sessions.values.value
                        .filterNot { it.sessionId == target.session.sessionId }
                f.completions.values.value = null
                SessionDeletionResult.Deleted
            }
            start(f)
            val originalParent = parent(f)
            val detail = open(f)
            val original = ready(detail)
            val deletion = deletionConfirmation(f)
            assertTrue(f.model.confirmDeletion(deletion.nonce))
            runCurrent()
            assertTrue(f.model.deletionState.value is SessionDeletionUiState.Deleted)
            assertTrue(f.model.uiState.value is SessionHistoryUiState.SelectionChanged)
            assertTrue(detail.value is SessionHistoryCourseCompletionUiState.Unavailable)
            assertFalse(f.model.canReadCourseCompletion(original.token))
            assertFalse(f.model.requestCourseCompletion(original.token))
            val missing = open(f, originalParent.session, originalParent.generation)
            assertTrue(missing.value is SessionHistoryCourseCompletionUiState.Unavailable)
            assertEquals(listOf("a"), f.completions.requested)
            assertEquals(1, f.completions.reads)
            assertEquals(0, f.completions.active)
            assertEquals(0, f.completions.writes)
            assertEquals(1, f.deletions.deleteCalls)
            assertEquals(listOf(session("b")), f.sessions.values.value)
        }

    private fun TestScope.start(f: Fixture) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.model.uiState.collect {} }
        runCurrent()
    }

    private fun parent(f: Fixture) = f.model.uiState.value as SessionHistoryUiState.Ready

    private fun absent(detail: Detail): SessionHistoryCourseCompletionUiState.Absent {
        assertTrue(
            "The same expanded collector must receive a fresh Absent receipt",
            detail.value is SessionHistoryCourseCompletionUiState.Absent,
        )
        return detail.value as SessionHistoryCourseCompletionUiState.Absent
    }

    private fun ready(detail: Detail): SessionHistoryCourseCompletionUiState.Ready {
        assertTrue(
            "The same expanded collector must receive the stored declaration",
            detail.value is SessionHistoryCourseCompletionUiState.Ready,
        )
        return detail.value as SessionHistoryCourseCompletionUiState.Ready
    }

    private fun TestScope.open(
        f: Fixture,
        source: PracticeSession = parent(f).session,
        generation: Long = parent(f).generation,
    ): Detail {
        val detail = Detail()
        detail.job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model.observeCourseCompletion(source, generation, f.host::get).collect { detail.value = it }
            }
        runCurrent()
        return detail
    }

    private fun TestScope.deletionConfirmation(f: Fixture): SessionDeletionUiState.Confirming {
        val source = parent(f)
        assertTrue(f.model.requestDeletion(source.session.sessionId, source.generation, f.host::get))
        runCurrent()
        return f.model.deletionState.value as SessionDeletionUiState.Confirming
    }

    private class Detail {
        lateinit var job: Job
        var value: SessionHistoryCourseCompletionUiState? = null
    }

    private class Fixture {
        val host = AtomicBoolean(true)
        val sessions = Sessions()
        val completions = Completions()
        val deletions = Deletions()
        val model =
            SessionHistoryViewModel(
                object : VehicleGateway {
                    override val state = MutableStateFlow(parked())
                },
                object : DrivingRestrictionsProvider {
                    override val restrictions = MutableStateFlow(unrestricted())
                },
                sessions,
                deletions,
                object : VehicleObservationRepository {
                    override fun observe(sessionId: String): Flow<List<SessionVehicleObservation>> = error("Unexpected observation read")

                    override suspend fun append(
                        sessionId: String,
                        observation: VehicleObservation,
                    ): VehicleObservationAppendResult = error("Unexpected observation write")
                },
                UnexpectedHistoryMemoRepository,
                UnexpectedHistoryRecordingRunRepository,
                completions,
            )

        init {
            deletions.prepareAction = { source, canRead ->
                if (canRead()) {
                    SessionDeletionPreparation.Ready(
                        SessionDeletionTarget(source, 0, null, completionDeclaration = completions.values.value),
                    )
                } else {
                    SessionDeletionPreparation.Blocked
                }
            }
        }
    }

    private class Sessions : PracticeSessionRepository {
        val values = MutableStateFlow(listOf(session("a"), session("b")))
        var reads = 0

        override fun observeSessions(): Flow<List<PracticeSession>> {
            reads++
            return values
        }

        override suspend fun start(
            practice: PreparedPractice,
            startedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionStartResult = error("Unexpected session start")

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult = error("Unexpected session finish")
    }

    private class Deletions : SessionDeletionRepository {
        var prepareCalls = 0
        var deleteCalls = 0
        var prepareAction: suspend (
            PracticeSession,
            () -> Boolean,
        ) -> SessionDeletionPreparation = { _, _ -> error("Unconfigured deletion preparation") }
        var deleteAction: suspend (SessionDeletionTarget, () -> Boolean) -> SessionDeletionResult = { _, _ -> error("Unexpected deletion") }

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

    private class Completions : CourseCompletionRepository {
        val values = MutableStateFlow<CourseCompletionDeclaration?>(null)
        val requested = mutableListOf<String>()
        var reads = 0
        var active = 0
        var writes = 0
        var declareAction: suspend (
            PracticeSession,
            Long,
            () -> Boolean,
        ) -> CourseCompletionDeclarationResult = { _, _, _ -> error("Unexpected declaration") }

        override fun observe(sessionId: String): Flow<CourseCompletionDeclaration?> {
            reads++
            requested += sessionId
            return flow {
                active++
                try {
                    emitAll(values)
                } finally {
                    active--
                }
            }
        }

        override suspend fun declare(
            expectedSource: PracticeSession,
            declaredAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): CourseCompletionDeclarationResult {
            writes++
            return declareAction(expectedSource, declaredAtEpochMillis, canCommit)
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

        fun declaration() = CourseCompletionDeclaration(session("a"), "original-version", 300, CourseCompletionOrigin.USER_DECLARATION)

        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        fun unrestricted() = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
    }
}
