package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeCourse
import io.github.hangillab.hangil.core.domain.PracticeGoal
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.PracticeTask
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
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
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class SessionFinishReviewViewModelTest {
    @Before fun before() {
        Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher())
    }

    @After fun after() {
        Dispatchers.resetMain()
    }

    @Test fun `no session reads or finish actions exist before a visible reader`() =
        runTest {
            val f = Fixture()
            f.model.setFinishHostResumed(true)
            assertEquals(0, f.reads)
            assertNull(f.model.finishActions.value)
            assertNull(f.model.finishReceipt.value)
        }

    @Test fun `strict finish acknowledgement produces one explicit full source entry`() =
        runTest {
            val f = Fixture().also { it.attach(this) }
            val action = f.action()
            assertTrue(f.model.finish(action) { true })
            assertFalse(f.model.finish(action) { true })
            runCurrent()
            assertEquals(1, f.finishes)
            val receipt = checkNotNull(f.model.finishReceipt.value)
            assertEquals(f.ended, receipt.source)
            assertEquals(SessionFinishReceipt.Kind.FINISHED, receipt.kind)
            assertEquals(f.active, f.expected)
            assertEquals(f.ended, f.model.consumeFinishedReview(receipt) { true })
            assertNull(f.model.consumeFinishedReview(receipt) { true })
        }

    @Test fun `already finished returns the original end without claiming a new finish`() =
        runTest {
            val f = Fixture()
            val original = f.ended.copy(endedAtEpochMillis = 2_000)
            f.write = { source, _, _ ->
                f.values.value = listOf(original)
                assertEquals(f.active, source)
                SessionFinishResult.AlreadyFinished(original)
            }
            f.attach(this)
            f.model.finish(f.action()) { true }
            runCurrent()
            val receipt = checkNotNull(f.model.finishReceipt.value)
            assertEquals(original, receipt.source)
            assertEquals(SessionFinishReceipt.Kind.ALREADY_FINISHED, receipt.kind)
            assertEquals(SessionOperationState.Feedback(SessionFeedback.ALREADY_FINISHED), f.model.uiState.value.operation)
        }

    @Test fun `ack first waits for the exact ended source and does not infer it from active`() =
        runTest {
            val f = Fixture()
            f.write = { _, _, _ -> SessionFinishResult.Finished(f.ended) }
            f.attach(this)
            f.model.finish(f.action()) { true }
            runCurrent()
            assertNull(f.model.finishReceipt.value)
            f.values.value = listOf(f.ended)
            runCurrent()
            assertEquals(
                f.ended,
                f.model.finishReceipt.value
                    ?.source,
            )
        }

    @Test fun `own ended flow before acknowledgement keeps A even when B becomes newest`() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            f.write = { _, _, gate ->
                assertTrue(gate())
                f.values.value = listOf(f.ended)
                release.await()
                SessionFinishResult.Finished(f.ended)
            }
            f.attach(this)
            f.model.finish(f.action()) { true }
            runCurrent()
            assertNull(f.model.finishReceipt.value)
            f.values.value = listOf(f.ended.copy(sessionId = "newer"), f.ended)
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(f.ended, checkNotNull(f.model.finishReceipt.value).source)
        }

    @Test fun `unchanged active notification does not revoke a pending strict write`() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            f.write = { _, _, gate ->
                release.await()
                assertTrue(gate())
                f.values.value = listOf(f.ended)
                SessionFinishResult.Finished(f.ended)
            }
            f.attach(this)
            f.model.finish(f.action()) { true }
            runCurrent()
            f.values.value = listOf(f.active, f.ended.copy(sessionId = "other"))
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertEquals(
                f.ended,
                f.model.finishReceipt.value
                    ?.source,
            )
        }

    @Test fun `changed source while waiting rejects both commit guard and late acknowledgement`() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            f.write = { _, _, gate ->
                release.await()
                assertFalse(gate())
                SessionFinishResult.Finished(f.ended)
            }
            f.attach(this)
            f.model.finish(f.action()) { true }
            runCurrent()
            f.values.value = listOf(f.active.copy(taskTitle = "replacement"))
            runCurrent()
            release.complete(Unit)
            runCurrent()
            assertNull(f.model.finishReceipt.value)
        }

    @Test fun `host retirement does not lend a fresh resume to an old action`() =
        runTest {
            val f = Fixture().also { it.attach(this) }
            val old = f.action()
            f.model.setFinishHostResumed(false)
            f.model.setFinishHostResumed(true)
            val fresh = f.action()
            assertNotSame(old, fresh)
            assertFalse(f.model.finish(old) { true })
            assertTrue(f.model.finish(fresh) { true })
            runCurrent()
            assertEquals(1, f.finishes)
        }

    @Test fun `live denial before recomposition retires the old action across P recovery`() =
        runTest {
            val f = Fixture().also { it.attach(this) }
            val old = f.action()
            f.vehicleValues.value = VehicleState()
            assertFalse(f.model.finish(old) { true })
            f.vehicleValues.value = parked()
            runCurrent()
            assertFalse(f.model.finish(old) { true })
            assertEquals(0, f.finishes)
        }

    @Test fun `old host closure cannot consume a receipt after fresh resume`() =
        runTest {
            val f = Fixture().also { it.attach(this) }
            f.model.finish(f.action()) { true }
            runCurrent()
            val old = checkNotNull(f.model.finishReceipt.value)
            f.model.setFinishHostResumed(false)
            f.model.setFinishHostResumed(true)
            val fresh = checkNotNull(f.model.finishReceipt.value)
            assertNotSame(old, fresh)
            assertNull(f.model.consumeFinishedReview(old) { true })
            assertEquals(f.ended, f.model.consumeFinishedReview(fresh) { true })
        }

    @Test fun `new start mutation retires a previous review acknowledgement`() =
        runTest {
            val f = Fixture().also { it.attach(this) }
            f.model.finish(f.action()) { true }
            runCurrent()
            val old = checkNotNull(f.model.finishReceipt.value)
            val release = CompletableDeferred<Unit>()
            f.startWrite = { _, _, _ ->
                release.await()
                SessionStartResult.Blocked
            }
            f.model.start()
            runCurrent()
            assertNull(f.model.finishReceipt.value)
            assertFalse(f.model.canReviewFinished(old))
            assertNull(f.model.consumeFinishedReview(old) { true })
            release.complete(Unit)
            runCurrent()
        }

    @Test fun `new legacy finish mutation also retires a previous review acknowledgement`() =
        runTest {
            val f = Fixture().also { it.attach(this) }
            f.model.finish(f.action()) { true }
            runCurrent()
            val old = checkNotNull(f.model.finishReceipt.value)
            f.model.finish(f.active.sessionId)
            runCurrent()
            assertNull(f.model.finishReceipt.value)
            assertNull(f.model.consumeFinishedReview(old) { true })
        }

    @Test fun `source deletion then identical recreation cannot restore an observed old receipt`() =
        runTest {
            val f = Fixture().also { it.attach(this) }
            f.model.finish(f.action()) { true }
            runCurrent()
            val old = checkNotNull(f.model.finishReceipt.value)
            f.values.value = emptyList()
            runCurrent()
            f.values.value = listOf(f.ended)
            runCurrent()
            assertNull(f.model.finishReceipt.value)
            assertNull(f.model.consumeFinishedReview(old) { true })
        }

    @Test fun `invalid and unconfirmed results never manufacture review success`() =
        runTest {
            for (mode in 0..7) {
                val f = Fixture()
                f.write = { _, _, _ ->
                    when (mode) {
                        0 -> SessionFinishResult.Finished(f.active)
                        1 -> SessionFinishResult.Finished(f.ended.copy(taskTitle = "foreign"))
                        2 -> SessionFinishResult.Finished(f.ended.copy(endedAtEpochMillis = 1))
                        3 -> SessionFinishResult.SourceChanged
                        4 -> SessionFinishResult.Missing
                        5 -> SessionFinishResult.Blocked
                        6 -> throw CancellationException("controlled lost acknowledgement")
                        else -> throw IllegalStateException("controlled adapter failure")
                    }
                }
                val subscription = f.attach(this)
                f.model.finish(f.action()) { true }
                runCurrent()
                assertNull(f.model.finishReceipt.value)
                subscription.cancel()
                runCurrent()
            }
        }

    @Test fun `host getter reentry cannot consume or start using a retired receipt`() =
        runTest {
            val f = Fixture().also { it.attach(this) }
            val old = f.action()
            assertFalse(
                f.model.finish(old) {
                    f.model.setFinishHostResumed(false)
                    true
                },
            )
            assertEquals(0, f.finishes)
            f.model.setFinishHostResumed(true)
            f.model.finish(f.action()) { true }
            runCurrent()
            val receipt = checkNotNull(f.model.finishReceipt.value)
            assertNull(
                f.model.consumeFinishedReview(receipt) {
                    f.model.setFinishHostResumed(false)
                    true
                },
            )
        }

    private class Fixture {
        val active = source()
        val ended = active.copy(endedAtEpochMillis = 3_000)
        val values = MutableStateFlow(listOf(active))
        val vehicleValues = MutableStateFlow(parked())
        val vehicle =
            object : VehicleGateway {
                override val state = vehicleValues
            }
        private val restrictions =
            object : DrivingRestrictionsProvider {
                override val restrictions = MutableStateFlow(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))
            }
        var reads = 0
        var finishes = 0
        var expected: PracticeSession? = null
        var write: suspend (PracticeSession, Long, () -> Boolean) -> SessionFinishResult = { _, _, gate ->
            check(gate())
            values.value = listOf(ended)
            SessionFinishResult.Finished(ended)
        }
        var startWrite: suspend (PreparedPractice, Long, () -> Boolean) -> SessionStartResult = { _, _, _ -> error("unexpected start") }
        private val goal = PracticeGoal("goal", "목표", "설명")
        private val task = PracticeTask("task", "과제", "설명")
        private val course = PracticeCourse("course", 1, 1, "코스", "설명", emptyList(), listOf(task), setOf(goal.id))
        private val catalog =
            object : PracticeCatalog {
                override val goals = listOf(goal)
                override val courses = listOf(course)
            }
        private val preparations =
            object : PracticeRepository {
                override fun observeSelection(): Flow<PracticeSelection?> =
                    flow {
                        emit(active.selection)
                        awaitCancellation()
                    }

                override suspend fun saveSelection(
                    selection: PracticeSelection,
                    canCommit: () -> Boolean,
                ): Boolean = error("unexpected write")
            }
        private val repository =
            object : PracticeSessionRepository {
                override fun observeSessions(): Flow<List<PracticeSession>> =
                    flow {
                        reads++
                        emitAll(values)
                    }

                override suspend fun start(
                    practice: PreparedPractice,
                    startedAtEpochMillis: Long,
                    canCommit: () -> Boolean,
                ) = startWrite.invoke(practice, startedAtEpochMillis, canCommit)

                override suspend fun finish(
                    sessionId: String,
                    endedAtEpochMillis: Long,
                    canCommit: () -> Boolean,
                ): SessionFinishResult = SessionFinishResult.Blocked

                override suspend fun finish(
                    expectedSource: PracticeSession,
                    endedAtEpochMillis: Long,
                    canCommit: () -> Boolean,
                ): SessionFinishResult {
                    finishes++
                    expected = expectedSource
                    return write(expectedSource, endedAtEpochMillis, canCommit)
                }
            }
        val model =
            SessionViewModel(
                vehicle,
                restrictions,
                preparations,
                catalog,
                repository,
                Clock.fixed(Instant.ofEpochMilli(3_000), ZoneOffset.UTC),
            )

        fun action() = checkNotNull(model.finishActions.value)

        fun attach(scope: TestScope): Job {
            val job = scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { model.uiState.collect() }
            scope.runCurrent()
            model.setFinishHostResumed(true)
            scope.runCurrent()
            return job
        }
    }

    private companion object {
        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        fun source() =
            PracticeSession(
                "source",
                PracticeSelection("goal", "course", 1, 1, "task"),
                "목표",
                "코스",
                "과제",
                CourseReviewStatus.DEMO,
                1_000,
                null,
            )
    }
}
