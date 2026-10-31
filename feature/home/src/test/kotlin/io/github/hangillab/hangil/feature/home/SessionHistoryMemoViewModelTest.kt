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
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
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
import io.github.hangillab.hangil.feature.home.SessionHistoryMemoUiState.FailureReason
import io.github.hangillab.hangil.feature.home.SessionHistoryMemoUiState.Token
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
class SessionHistoryMemoViewModelTest {
    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `uncollected detail does not query either repository`() =
        runTest {
            val f = Fixture()
            f.model.observeStoredMemo(session("a"), 1, f.host::get)
            runCurrent()
            assertEquals(0, f.sessions.calls)
            assertEquals(0, f.memos.calls)
            assertFalse(f.model.canReadStoredMemo(Token(0, 0, 0)))
        }

    @Test fun `missing parent never reads a memo`() =
        runTest {
            val f = Fixture()
            val d = open(f, session("a"), 1)
            assertTrue(d.value is SessionHistoryMemoUiState.Unavailable)
            assertEquals(0, f.memos.calls)
        }

    @Test fun `full source rather than just ID owns the memo`() =
        runTest {
            val f = Fixture()
            start(f)
            val source = parent(f)
            val d = open(f, source.session.copy(taskTitle = "different source"), source.generation)
            assertTrue(d.value is SessionHistoryMemoUiState.Unavailable)
            assertEquals(0, f.memos.calls)
        }

    @Test fun `expired history generation cannot start the same memo query`() =
        runTest {
            val f = Fixture()
            start(f)
            val old = parent(f)
            assertTrue(f.model.retry())
            runCurrent()
            val d = open(f, old.session, old.generation)
            assertTrue(d.value is SessionHistoryMemoUiState.Unavailable)
            assertEquals(0, f.memos.calls)
        }

    @Test fun `collapsed history has no extra memo or sessions subscription`() =
        runTest {
            val f = Fixture()
            start(f)
            assertEquals(1, f.sessions.calls)
            assertEquals(0, f.memos.calls)
            assertEquals(0, f.memos.active)
        }

    @Test fun `expanded query preserves exact multiline Unicode text and user version`() =
        runTest {
            val f = Fixture()
            start(f)
            val original = memo("a", "  첫 줄🌱\n둘째 줄  ", " version with spaces ")
            f.memos.values.value = original
            val d = open(f)
            assertEquals(original, ready(d).memo)
            assertEquals(listOf("a"), f.memos.requested)
            assertEquals(1, f.sessions.calls)
            assertEquals(1, f.memos.active)
            assertTrue(f.model.canReadStoredMemo(ready(d).token))
            assertFalse(f.model.retryStoredMemo(ready(d).token))
        }

    @Test fun `only a null receipt means no saved memo`() =
        runTest {
            val f = Fixture()
            start(f)
            f.memos.values.value = null
            val d = open(f)
            assertTrue(d.value is SessionHistoryMemoUiState.Empty)
            assertFalse(f.model.retryStoredMemo(checkNotNull(d.value).token))
            assertEquals(1, f.memos.calls)
        }

    @Test fun `valid replacement rotates the displayed token without mutating the text`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            val replacement = memo("a", "다른 원문\n끝 ", "v2")
            f.memos.values.value = replacement
            runCurrent()
            assertEquals(replacement, ready(d).memo)
            assertNotEquals(old.token, ready(d).token)
            assertFalse(f.model.canReadStoredMemo(old.token))
            assertEquals(1, f.memos.calls)
        }

    @Test fun `actual deletion receipt becomes empty without inventing a note`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            f.memos.values.value = null
            runCurrent()
            assertTrue(d.value is SessionHistoryMemoUiState.Empty)
            assertFalse(f.model.canReadStoredMemo(old.token))
        }

    @Test fun `wrong memo session fails instead of leaking another note`() =
        runTest {
            invalidReceipt(memo("b"))
        }

    @Test fun `blank version fails instead of authorizing an unknown note`() =
        runTest {
            invalidReceipt(memo("a", version = " "))
        }

    @Test fun `blank saved text is corruption not absence`() =
        runTest {
            invalidReceipt(memo("a", text = "\n "))
        }

    @Test fun `NUL text is rejected without replacement or truncation`() =
        runTest {
            invalidReceipt(memo("a", text = "before\u0000after"))
        }

    @Test fun `isolated surrogate text is rejected`() =
        runTest {
            invalidReceipt(memo("a", text = "bad\uD800"))
        }

    @Test fun `text above the code point limit is rejected`() =
        runTest {
            invalidReceipt(memo("a", text = "가".repeat(281)))
        }

    @Test fun `code point limit accepts astral characters without trimming`() =
        runTest {
            val f = Fixture()
            start(f)
            val note = memo("a", "🌱".repeat(280))
            f.memos.values.value = note
            assertEquals(note, ready(open(f)).memo)
        }

    @Test fun `query factory failure is terminal and explicit retry owns a new query`() =
        runTest {
            val f = Fixture()
            start(f)
            f.memos.factoryFailure = IOException("controlled")
            val d = open(f)
            val old = failed(d)
            assertEquals(FailureReason.MEMO_READ, old.reason)
            f.memos.factoryFailure = null
            f.memos.values.value = memo("a", "new receipt")
            runCurrent()
            assertEquals(old, d.value)
            assertEquals(1, f.memos.calls)
            assertTrue(f.model.retryStoredMemo(old.token))
            runCurrent()
            assertEquals("new receipt", ready(d).memo.text)
            assertEquals(2, f.memos.calls)
            assertFalse(f.model.retryStoredMemo(old.token))
        }

    @Test fun `independent cancellation exception is a read failure not empty success`() =
        runTest {
            val f = Fixture()
            start(f)
            f.memos.querySource = { flow { throw CancellationException("independent source failure") } }
            val d = open(f)
            assertEquals(FailureReason.MEMO_READ, failed(d).reason)
            assertEquals(0, f.memos.active)
            assertTrue(f.model.canReadStoredMemo(failed(d).token))
        }

    @Test fun `normal memo stream completion is terminal instead of keeping a stale note`() =
        runTest {
            val f = Fixture()
            start(f)
            f.memos.querySource = { flowOf(memo("a")) }
            val d = open(f)
            assertEquals(FailureReason.MEMO_STREAM_COMPLETED, failed(d).reason)
            assertEquals(1, f.memos.calls)
            runCurrent()
            assertEquals(1, f.memos.calls)
        }

    @Test fun `invalid receipt remains failed until an explicit retry`() =
        runTest {
            val f = Fixture()
            start(f)
            f.memos.values.value = memo("b")
            val d = open(f)
            val old = failed(d)
            f.memos.values.value = memo("a")
            runCurrent()
            assertEquals(old, d.value)
            assertTrue(f.model.retryStoredMemo(old.token))
            runCurrent()
            assertEquals(memo("a"), ready(d).memo)
        }

    @Test fun `closing a detail cancels its database subscription`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            d.job.cancelAndJoin()
            runCurrent()
            assertEquals(0, f.memos.active)
            assertFalse(f.model.canReadStoredMemo(old.token))
        }

    @Test fun `another detail reader makes every old token unusable`() =
        runTest {
            val f = Fixture()
            start(f)
            val a = open(f)
            val old = ready(a)
            val b = open(f)
            assertTrue(a.value is SessionHistoryMemoUiState.Unavailable)
            assertEquals(1, f.memos.active)
            assertFalse(f.model.retryStoredMemo(old.token))
            assertTrue(f.model.canReadStoredMemo(ready(b).token))
        }

    @Test fun `parent selection change cancels the previous memo before opening another`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            val source = parent(f)
            assertTrue(f.model.older(source.session.sessionId, source.generation))
            runCurrent()
            assertEquals(0, f.memos.active)
            assertTrue(d.value is SessionHistoryMemoUiState.Unavailable)
            assertFalse(f.model.canReadStoredMemo(old.token))
            f.memos.values.value = memo("b", "B original")
            assertEquals("B original", ready(open(f)).memo.text)
            assertEquals(listOf("a", "b"), f.memos.requested)
        }

    @Test fun `same ID metadata change revokes the old source`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            f.sessions.values.value = listOf(session("a").copy(taskTitle = "revised source"), session("b"))
            runCurrent()
            assertTrue(d.value is SessionHistoryMemoUiState.Unavailable)
            assertEquals(0, f.memos.active)
            assertFalse(f.model.canReadStoredMemo(old.token))
            assertEquals("revised source", ready(open(f)).source.taskTitle)
        }

    @Test fun `new latest session does not redirect the selected historical memo`() =
        runTest {
            val f = Fixture()
            start(f)
            open(f)
            f.sessions.values.value = listOf(session("new"), session("a"), session("b"))
            runCurrent()
            assertEquals("a", parent(f).session.sessionId)
            assertEquals("a", ready(open(f)).memo.sessionId)
            assertEquals(listOf("a", "a"), f.memos.requested)
        }

    @Test fun `selected session disappearance revokes note and requires explicit parent recovery`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            f.sessions.values.value = listOf(session("b"))
            runCurrent()
            assertTrue(f.model.uiState.value is SessionHistoryUiState.SelectionChanged)
            assertTrue(d.value is SessionHistoryMemoUiState.Unavailable)
            assertEquals(0, f.memos.active)
            assertFalse(f.model.retryStoredMemo(old.token))
        }

    @Test fun `silent live D read rejects and retires the original parent generation`() =
        runTest {
            val f = Fixture()
            start(f)
            val source = parent(f)
            val d = open(f)
            val old = ready(d)
            f.vehicle.setWithoutEmission(driving())
            assertFalse(f.model.canReadStoredMemo(old.token))
            f.vehicle.setWithoutEmission(parked())
            assertFalse(f.model.canRevealStoredMemo(source.session.sessionId, source.generation))
            assertFalse(f.model.retryStoredMemo(old.token))
            runCurrent()
            assertEquals(0, f.memos.active)
        }

    @Test fun `silent D expansion denial is not remembered as a new P intent`() =
        runTest {
            val f = Fixture()
            start(f)
            val source = parent(f)
            f.vehicle.setWithoutEmission(driving())
            assertFalse(f.model.canRevealStoredMemo(source.session.sessionId, source.generation))
            f.vehicle.setWithoutEmission(parked())
            assertFalse(f.model.canRevealStoredMemo(source.session.sessionId, source.generation))
            runCurrent()
            assertEquals(0, f.memos.calls)
        }

    @Test fun `silent UX denial also revokes old ready and failed retry contexts`() =
        runTest {
            val f = Fixture()
            start(f)
            f.memos.factoryFailure = IOException("controlled")
            val d = open(f)
            val token = failed(d).token
            val source = parent(f)
            f.restrictions.setWithoutEmission(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.RESTRICTED))
            assertFalse(f.model.retryStoredMemo(token))
            f.restrictions.setWithoutEmission(unrestricted())
            assertFalse(f.model.retryStoredMemo(token))
            assertFalse(f.model.canRevealStoredMemo(source.session.sessionId, source.generation))
            assertEquals(1, f.memos.calls)
        }

    @Test fun `parked UX alone does not allow a disconnected or unknown gear`() =
        runTest {
            val deniedStates =
                listOf(
                    parked().copy(connection = ConnectionState.DISCONNECTED),
                    parked().copy(gear = VehicleReading.Unknown),
                    driving(),
                )
            for (state in deniedStates) {
                val f = Fixture()
                f.vehicle.emit(state)
                start(f)
                val d = open(f, session("a"), 1)
                assertTrue(d.value is SessionHistoryMemoUiState.Unavailable)
                assertEquals(0, f.memos.calls)
            }
        }

    @Test fun `host expiry rejects a current retry and ends its subscription`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            f.host.set(false)
            assertFalse(f.model.canReadStoredMemo(old.token))
            runCurrent()
            assertTrue(d.value is SessionHistoryMemoUiState.Unavailable)
            assertEquals(0, f.memos.active)
        }

    @Test fun `host guard exception fails closed without exposing or querying a memo`() =
        runTest {
            val f = Fixture()
            start(f)
            var value: SessionHistoryMemoUiState? = null
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model
                    .observeStoredMemo(
                        parent(f).session,
                        parent(f).generation,
                    ) { error("controlled host failure") }
                    .collect { value = it }
            }
            runCurrent()
            assertTrue(value is SessionHistoryMemoUiState.Unavailable)
            assertEquals(0, f.memos.calls)
        }

    @Test fun `parent retry revokes the old memo reader even for the same session`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = ready(d)
            assertTrue(f.model.retry())
            runCurrent()
            assertEquals(0, f.memos.active)
            assertTrue(d.value is SessionHistoryMemoUiState.Unavailable)
            assertFalse(f.model.canReadStoredMemo(old.token))
            assertEquals(memo("a"), ready(open(f)).memo)
        }

    @Test fun `STOP of the parent cannot leave the old memo query alive`() =
        runTest {
            val f = Fixture()
            val history = start(f)
            val d = open(f)
            val old = ready(d)
            history.cancelAndJoin()
            runCurrent()
            assertEquals(0, f.memos.active)
            assertFalse(f.model.canReadStoredMemo(old.token))
            start(f)
            assertFalse(f.model.retryStoredMemo(old.token))
            assertEquals(memo("a"), ready(open(f)).memo)
        }

    @Test fun `late noncancellable old memo cannot replace a newly selected note`() =
        runTest {
            val f = Fixture()
            start(f)
            f.memos.querySource = { lateMemo() }
            val d = open(f)
            val source = parent(f)
            assertTrue(f.model.older(source.session.sessionId, source.generation))
            runCurrent()
            f.memos.querySource = { f.memos.values }
            f.memos.values.value = memo("b", "B current")
            val b = open(f)
            runCurrent()
            assertTrue(d.value is SessionHistoryMemoUiState.Unavailable)
            assertEquals("B current", ready(b).memo.text)
            assertEquals(1, f.memos.active)
        }

    private fun TestScope.invalidReceipt(value: SessionMemo) {
        val f = Fixture()
        start(f)
        f.memos.values.value = value
        val d = open(f)
        assertEquals(FailureReason.INVALID_MEMO, failed(d).reason)
        assertEquals(0, f.memos.active)
    }

    private fun TestScope.start(f: Fixture): Job {
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.model.uiState.collect {} }
        runCurrent()
        return job
    }

    private fun parent(f: Fixture) = f.model.uiState.value as SessionHistoryUiState.Ready

    private fun ready(d: Detail) = d.value as SessionHistoryMemoUiState.Ready

    private fun failed(d: Detail) = d.value as SessionHistoryMemoUiState.Failed

    private fun TestScope.open(
        f: Fixture,
        source: PracticeSession = parent(f).session,
        generation: Long = parent(f).generation,
    ): Detail {
        val d = Detail()
        d.job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model.observeStoredMemo(source, generation, f.host::get).collect { d.value = it }
            }
        runCurrent()
        return d
    }

    private class Detail {
        lateinit var job: Job
        var value: SessionHistoryMemoUiState? = null
    }

    private class Fixture {
        val vehicle = SilentLiveState(parked())
        val restrictions = SilentLiveState(unrestricted())
        val sessions = Sessions()
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
                    ): SessionDeletionPreparation = error("No deletion in read-only memo history")

                    override suspend fun delete(
                        target: SessionDeletionTarget,
                        canCommit: () -> Boolean,
                    ): SessionDeletionResult = error("No deletion in read-only memo history")
                },
                object : VehicleObservationRepository {
                    override fun observe(sessionId: String): Flow<List<SessionVehicleObservation>> =
                        error("No observation query in memo detail")

                    override suspend fun append(
                        sessionId: String,
                        observation: VehicleObservation,
                    ): VehicleObservationAppendResult = error("No observation append in memo history")
                },
                memos,
                UnexpectedHistoryRecordingRunRepository,
            )
    }

    private class Sessions : PracticeSessionRepository {
        val values = MutableStateFlow(listOf(session("a"), session("b")))
        var calls = 0

        override fun observeSessions(): Flow<List<PracticeSession>> {
            calls++
            return values
        }

        override suspend fun start(
            practice: PreparedPractice,
            startedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionStartResult = error("Read-only")

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult = error("Read-only")
    }

    private class Memos : SessionMemoRepository {
        val values = MutableStateFlow<SessionMemo?>(memo("a"))
        val requested = mutableListOf<String>()
        var calls = 0
        var active = 0
        var factoryFailure: Throwable? = null
        var querySource: () -> Flow<SessionMemo?> = { values }

        override fun observe(sessionId: String): Flow<SessionMemo?> {
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

        override suspend fun save(
            sessionId: String,
            text: String,
            expectedVersion: String?,
            canCommit: () -> Boolean,
        ): SessionMemoWriteResult = error("Read-only detail must not save")

        override suspend fun delete(
            sessionId: String,
            expectedVersion: String,
            canCommit: () -> Boolean,
        ): SessionMemoWriteResult = error("Read-only detail must not delete")
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
    private fun lateMemo(): Flow<SessionMemo?> =
        object : Flow<SessionMemo?> {
            override suspend fun collect(collector: FlowCollector<SessionMemo?>) {
                collector.emit(memo("a"))
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { collector.emit(memo("a", "late old note")) }
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

        fun memo(
            id: String,
            text: String = "original note",
            version: String = "v1",
        ) = SessionMemo(id, text, version)

        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        fun driving() = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))

        fun unrestricted() = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
    }
}
