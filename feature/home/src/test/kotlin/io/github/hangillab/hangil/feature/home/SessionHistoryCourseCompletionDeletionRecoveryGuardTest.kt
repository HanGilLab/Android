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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Production VM integration with controlled stores; no Room, SDK or actual deletion is exercised. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionHistoryCourseCompletionDeletionRecoveryGuardTest {
    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `dismissing failed deletion feedback does not retire a new declaration confirmation`() =
        runTest {
            val f = Fixture()
            f.deletions.prepareAction = { _, _ -> throw IOException("controlled preparation failure") }
            f.completions.declareAction = { _, _, canCommit ->
                assertTrue(canCommit())
                CourseCompletionDeclarationResult.Blocked
            }
            start(f)
            val originalParent = parent(f)
            val detail = open(f)
            val original = absent(detail)
            assertTrue(f.model.requestDeletion(originalParent.session.sessionId, originalParent.generation, f.host::get))
            runCurrent()
            val failure = f.model.deletionState.value as SessionDeletionUiState.Failed
            assertEquals(SessionDeletionUiState.FailureReason.PREPARE_FAILED, failure.reason)
            val recovered = absent(detail)
            assertNotEquals(original.token, recovered.token)
            assertEquals(2, f.completions.reads)
            assertTrue(f.model.requestCourseCompletion(recovered.token))
            runCurrent()
            val fresh = detail.value as SessionHistoryCourseCompletionUiState.Confirming
            assertTrue(f.model.dismissDeletion(failure.nonce))
            runCurrent()
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
            assertEquals(fresh, detail.value)
            assertEquals(2, f.completions.reads)
            assertEquals(originalParent, parent(f))
            assertFalse(f.model.dismissDeletion(failure.nonce))
            assertFalse(f.model.confirmDeletion(failure.nonce))
            runCurrent()
            assertEquals(fresh, detail.value)
            assertEquals(2, f.completions.reads)
            assertTrue(f.model.confirmCourseCompletion(fresh.token, fresh.nonce))
            runCurrent()
            assertEquals(
                SessionHistoryCourseCompletionUiState.FailureReason.BLOCKED,
                (detail.value as SessionHistoryCourseCompletionUiState.Failed).reason,
            )
            assertEquals(1, f.completions.writes)
            assertEquals(0, f.deletions.deleteCalls)
        }

    @Test fun `old deletion nonces cannot dismiss a newer deletion or trigger a second recovery`() =
        runTest {
            val f = Fixture()
            start(f)
            val originalParent = parent(f)
            val detail = open(f)
            val first = deletionConfirmation(f)
            assertTrue(f.model.dismissDeletion(first.nonce))
            runCurrent()
            val firstRecovery = absent(detail)
            assertTrue(f.model.requestCourseCompletion(firstRecovery.token))
            runCurrent()
            val oldConsent = detail.value as SessionHistoryCourseCompletionUiState.Confirming
            val second = deletionConfirmation(f)
            assertNotEquals(first.nonce, second.nonce)
            assertFalse(f.model.dismissDeletion(first.nonce))
            assertFalse(f.model.confirmDeletion(first.nonce))
            runCurrent()
            assertEquals(second, f.model.deletionState.value)
            assertEquals(2, f.completions.reads)
            assertFalse(f.model.canReadCourseCompletion(oldConsent.token))
            assertTrue(f.model.dismissDeletion(second.nonce))
            runCurrent()
            val fresh = absent(detail)
            assertNotEquals(firstRecovery.token, fresh.token)
            assertFalse(f.model.confirmCourseCompletion(oldConsent.token, oldConsent.nonce))
            assertFalse(f.model.dismissDeletion(first.nonce))
            assertFalse(f.model.dismissDeletion(second.nonce))
            assertFalse(f.model.confirmDeletion(first.nonce))
            runCurrent()
            assertEquals(fresh, detail.value)
            assertEquals(3, f.completions.reads)
            assertEquals(1, f.completions.active)
            assertEquals(originalParent, parent(f))
            assertEquals(1, f.sessions.reads)
            assertEquals(0, f.completions.writes)
            assertEquals(0, f.deletions.deleteCalls)
        }

    @Test fun `expired host or changed full source cannot recover an old expanded reader after preparation`() =
        runTest {
            for (expireHost in listOf(false, true)) {
                val f = Fixture()
                val release = CompletableDeferred<Unit>()
                f.deletions.prepareAction = { _, _ ->
                    release.await()
                    throw IOException("controlled late preparation failure")
                }
                start(f)
                val originalParent = parent(f)
                val detail = open(f)
                assertTrue(f.model.requestCourseCompletion(absent(detail).token))
                runCurrent()
                val oldConsent = detail.value as SessionHistoryCourseCompletionUiState.Confirming
                assertTrue(f.model.requestDeletion(originalParent.session.sessionId, originalParent.generation, f.host::get))
                runCurrent()
                assertTrue(f.model.deletionState.value is SessionDeletionUiState.Preparing)
                if (expireHost) {
                    f.host.set(false)
                } else {
                    f.sessions.values.value = listOf(session("a").copy(taskTitle = "changed full snapshot"), session("b"))
                    runCurrent()
                }
                release.complete(Unit)
                runCurrent()
                f.host.set(true)
                if (!expireHost) {
                    f.sessions.values.value = listOf(session("a"), session("b"))
                }
                runCurrent()
                assertTrue(detail.value is SessionHistoryCourseCompletionUiState.Unavailable)
                assertFalse(f.model.canReadCourseCompletion(oldConsent.token))
                assertFalse(f.model.confirmCourseCompletion(oldConsent.token, oldConsent.nonce))
                assertEquals(1, f.completions.reads)
                assertEquals(0, f.completions.active)
                assertEquals(0, f.completions.writes)
                val explicit = open(f)
                assertTrue(absent(explicit).canDeclare)
                assertEquals(2, f.completions.reads)
                assertEquals(0, f.deletions.deleteCalls)
            }
        }

    @Test fun `observed D then P does not automatically revive preparation recovery or old consent`() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            f.deletions.prepareAction = { _, _ ->
                release.await()
                throw IOException("controlled preparation failure after gate transition")
            }
            start(f)
            val originalParent = parent(f)
            val detail = open(f)
            assertTrue(f.model.requestCourseCompletion(absent(detail).token))
            runCurrent()
            val oldConsent = detail.value as SessionHistoryCourseCompletionUiState.Confirming
            assertTrue(f.model.requestDeletion(originalParent.session.sessionId, originalParent.generation, f.host::get))
            runCurrent()
            val preparing = f.model.deletionState.value as SessionDeletionUiState.Preparing
            f.vehicle.value = driving()
            runCurrent()
            assertEquals(SessionHistoryUiState.Locked, f.model.uiState.value)
            assertFalse(f.model.confirmDeletion(preparing.nonce))
            release.complete(Unit)
            f.vehicle.value = parked()
            runCurrent()
            assertTrue(f.model.uiState.value is SessionHistoryUiState.Ready)
            assertFalse(detail.value is SessionHistoryCourseCompletionUiState.Absent)
            assertFalse(detail.value is SessionHistoryCourseCompletionUiState.Confirming)
            assertFalse(detail.value is SessionHistoryCourseCompletionUiState.Ready)
            assertFalse(f.model.confirmCourseCompletion(oldConsent.token, oldConsent.nonce))
            assertFalse(f.model.confirmDeletion(preparing.nonce))
            assertEquals(1, f.completions.reads)
            assertEquals(0, f.completions.active)
            assertEquals(0, f.completions.writes)
            val explicit = open(f)
            assertTrue(absent(explicit).canDeclare)
            assertEquals(2, f.completions.reads)
            assertEquals(0, f.deletions.deleteCalls)
        }

    @Test fun `terminal failures recover explicitly while busy cancellation only retires its owner`() =
        runTest {
            val results: List<SessionDeletionResult?> =
                listOf(
                    SessionDeletionResult.Blocked,
                    SessionDeletionResult.Changed,
                    SessionDeletionResult.Active,
                    null,
                )
            for (result in results) {
                val f = Fixture()
                val release = CompletableDeferred<Unit>()
                f.deletions.deleteAction = { _, canCommit ->
                    release.await()
                    assertTrue(canCommit())
                    result ?: throw IOException("controlled deletion failure")
                }
                start(f)
                val originalParent = parent(f)
                val detail = open(f)
                val original = absent(detail)
                val confirming = deletionConfirmation(f)
                assertTrue(f.model.confirmDeletion(confirming.nonce))
                runCurrent()
                val deleting = f.model.deletionState.value as SessionDeletionUiState.Deleting
                assertEquals(deleting, f.model.deletionState.value)
                assertEquals(1, f.deletions.deleteCalls)
                assertEquals(1, f.completions.reads)
                assertEquals(0, f.completions.active)
                release.complete(Unit)
                runCurrent()
                assertTrue(f.model.deletionState.value is SessionDeletionUiState.Failed)
                assertEquals(1, f.completions.reads)
                assertFalse(f.model.canReadCourseCompletion(original.token))
                assertTrue(f.model.dismissDeletion(deleting.nonce))
                runCurrent()
                val restored = absent(detail)
                assertTrue(restored.canDeclare)
                assertNotEquals(original.token, restored.token)
                assertFalse(f.model.requestCourseCompletion(original.token))
                assertEquals(2, f.completions.reads)
                assertEquals(1, f.completions.active)
                assertEquals(originalParent, parent(f))
                assertEquals(1, f.sessions.reads)
                assertEquals(0, f.completions.writes)
                assertFalse(f.model.dismissDeletion(deleting.nonce))
                runCurrent()
                assertEquals(restored, detail.value)
                assertEquals(2, f.completions.reads)
            }

            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            f.deletions.deleteAction = { _, canCommit ->
                assertTrue(canCommit())
                withContext(NonCancellable) { release.await() }
                assertFalse(canCommit())
                SessionDeletionResult.Blocked
            }
            start(f)
            val originalParent = parent(f)
            val detail = open(f)
            val original = absent(detail)
            val confirming = deletionConfirmation(f)
            assertTrue(f.model.confirmDeletion(confirming.nonce))
            runCurrent()
            assertTrue(f.model.deletionState.value is SessionDeletionUiState.Deleting)
            assertTrue(f.model.dismissDeletion(confirming.nonce))
            runCurrent()
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
            assertEquals(1, f.completions.reads)
            assertEquals(0, f.completions.active)
            assertFalse(f.model.canReadCourseCompletion(original.token))
            release.complete(Unit)
            runCurrent()
            assertEquals(SessionDeletionUiState.Idle, f.model.deletionState.value)
            assertEquals(1, f.completions.reads)
            assertEquals(originalParent, parent(f))
            assertEquals(1, f.deletions.deleteCalls)
            assertEquals(0, f.completions.writes)
            val explicit = open(f)
            assertTrue(absent(explicit).canDeclare)
            assertEquals(2, f.completions.reads)
            assertFalse(f.model.requestCourseCompletion(original.token))
        }

    @Test fun `repository missing never reopens a stale parent source after preparation or delete`() =
        runTest {
            for (duringPreparation in listOf(false, true)) {
                val f = Fixture()
                if (duringPreparation) {
                    f.deletions.prepareAction = { _, _ -> SessionDeletionPreparation.Missing }
                } else {
                    f.deletions.deleteAction = { _, _ -> SessionDeletionResult.Missing }
                }
                start(f)
                val originalParent = parent(f)
                val detail = open(f)
                val original = absent(detail)
                assertTrue(f.model.requestDeletion(originalParent.session.sessionId, originalParent.generation, f.host::get))
                runCurrent()
                if (!duringPreparation) {
                    val confirming = f.model.deletionState.value as SessionDeletionUiState.Confirming
                    assertTrue(f.model.confirmDeletion(confirming.nonce))
                    runCurrent()
                }
                val failed = f.model.deletionState.value as SessionDeletionUiState.Failed
                assertEquals(SessionDeletionUiState.FailureReason.MISSING, failed.reason)
                assertTrue(f.model.dismissDeletion(failed.nonce))
                runCurrent()
                assertEquals(1, f.completions.reads)
                assertEquals(0, f.completions.active)
                assertFalse(f.model.canReadCourseCompletion(original.token))
                assertFalse(f.model.requestCourseCompletion(original.token))
                assertEquals(originalParent, parent(f))
                assertEquals(0, f.completions.writes)
                assertEquals(if (duringPreparation) 0 else 1, f.deletions.deleteCalls)
            }
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
        val vehicle = MutableStateFlow(parked())
        val restrictions = MutableStateFlow(unrestricted())
        val sessions = Sessions()
        val completions = Completions()
        val deletions = Deletions()
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
                UnexpectedHistorySessionMemoWriter,
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

        fun driving() = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))

        fun unrestricted() = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
    }
}
