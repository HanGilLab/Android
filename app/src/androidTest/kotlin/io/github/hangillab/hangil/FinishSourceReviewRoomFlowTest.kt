package io.github.hangillab.hangil

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.database.OwnedPracticeTestStore
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclaration
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.RecordingRunOpenResult
import io.github.hangillab.hangil.core.domain.RecordingRunRepository
import io.github.hangillab.hangil.core.domain.RecordingRunStatus
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import io.github.hangillab.hangil.core.domain.VehicleRecordingRun
import io.github.hangillab.hangil.feature.home.SessionFinishReceipt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Real production routes and strict Room repository in a UUID-owned schema6 store; no SDK or user database. */
@RunWith(AndroidJUnit4::class)
class FinishSourceReviewRoomFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()

    @Test fun committedFlowBeforeAcknowledgementReviewsTheFinishedSourceEvenAfterANewerRecordExists() {
        assumeManual()
        fixture { f ->
            val original = f.snapshot()
            runBlocking {
                check(
                    f.store.sessions.finish(f.active.copy(taskTitle = "changed owned source"), TIME + 90_000) { true } ==
                        SessionFinishResult.SourceChanged,
                )
                check(f.store.sessions.finish(f.active, TIME + 90_000) { false } == SessionFinishResult.Blocked)
                check(f.store.sessions.finish(f.active, TIME) { true } == SessionFinishResult.InvalidTime)
            }
            check(f.snapshot() == original) { "Rejected strict writes changed owned data" }
            val committed = CompletableDeferred<SessionFinishResult>()
            val acknowledge = CompletableDeferred<Unit>()
            val proxy = Sessions(f.store.sessions, listOf(f.active))
            proxy.onFinish = { expected, time, allowed ->
                val result = f.store.sessions.finish(expected, time, allowed)
                committed.complete(result)
                acknowledge.await()
                result
            }
            val ui = f.mount(proxy)
            ui.touch("session_finish")
            val result = await(committed)
            check(result is SessionFinishResult.Finished)
            val ended = result.session
            f.assertOnlyEnded(original, ended)
            check(ui.session.finishReceipt.value == null && ui.navigation.isEmpty())
            runBlocking {
                check(f.store.memos.save(ended.sessionId, "Owned finish review memo", null) { true } is SessionMemoWriteResult.Saved)
            }
            val newer = f.addNewerEnded()
            val beforeReview = f.snapshot()
            acknowledge.complete(Unit)
            ui.waitReceipt()
            val receipt = checkNotNull(ui.session.finishReceipt.value)
            check(receipt.source == ended && receipt.kind == SessionFinishReceipt.Kind.FINISHED)
            val oldReview = ui.action("session_finish_review")
            ui.touch("session_finish_review")
            ui.ready(ended)
            ui.invoke(oldReview)
            check(ui.navigation == listOf(ended))
            check(ui.nodes("history_newer").isEmpty() && ui.nodes("history_older").isEmpty())
            check(f.memoQueries.isEmpty()) { "Exact entry must keep a closed memo cold" }
            ui.touch("history_memo")
            ui.waitTag("history_memo_text")
            check(ui.text("history_memo_text") == "Owned finish review memo")
            check(f.memoQueries.toList() == listOf(ended.sessionId))
            check(f.memoQueries.none { it == newer.sessionId })
            check(f.snapshot() == beforeReview) { "Reviewing an exact source changed owned data" }
            check(proxy.strictCalls.get() == 1 && proxy.legacyCalls.get() == 0)
        }
    }

    @Test fun alreadyFinishedAcknowledgementWaitsForTheExactStoredFlowAndPreservesTheFirstEnd() {
        assumeManual()
        fixture { f ->
            val original = f.snapshot()
            val acknowledgeReturned = CompletableDeferred<Unit>()
            val proxy = Sessions(f.store.sessions, listOf(f.active))
            proxy.deliver.value = false
            var committed: PracticeSession? = null
            proxy.onFinish = { expected, time, allowed ->
                val first = f.store.sessions.finish(expected, time - 1_000, allowed)
                check(first is SessionFinishResult.Finished)
                committed = first.session
                val duplicate = f.store.sessions.finish(expected, time, allowed)
                check(duplicate is SessionFinishResult.AlreadyFinished && duplicate.session == first.session)
                acknowledgeReturned.complete(Unit)
                duplicate
            }
            val ui = f.mount(proxy)
            ui.touch("session_finish")
            await(acknowledgeReturned)
            compose.waitUntil(TIMEOUT) { proxy.completed.get() == 1 }
            compose.waitForIdle()
            check(ui.session.finishReceipt.value == null && ui.nodes("session_finish_review").isEmpty()) {
                "An ACK must not substitute for an unreceived exact stored source"
            }
            val ended = checkNotNull(committed)
            f.assertOnlyEnded(original, ended)
            proxy.deliver.value = true
            ui.waitReceipt()
            val receipt = checkNotNull(ui.session.finishReceipt.value)
            check(receipt.kind == SessionFinishReceipt.Kind.ALREADY_FINISHED && receipt.source == ended)
            val beforeReview = f.snapshot()
            ui.touch("session_finish_review")
            ui.ready(ended)
            check(ui.navigation == listOf(ended) && f.snapshot() == beforeReview)
            check(proxy.strictCalls.get() == 1 && proxy.legacyCalls.get() == 0)
        }
    }

    @Test fun committedButCancelledReplyCreatesNoReceiptAndFreshRoomHandleReadsOnlyTheCommittedSource() {
        assumeManual()
        fixture { f ->
            val original = f.snapshot()
            val committed = CompletableDeferred<PracticeSession>()
            val proxy = Sessions(f.store.sessions, listOf(f.active))
            proxy.onFinish = { expected, time, allowed ->
                val result = f.store.sessions.finish(expected, time, allowed)
                check(result is SessionFinishResult.Finished)
                committed.complete(result.session)
                // This delays adapter result delivery after a real COMMIT; it is not an internal Room-dispatcher claim.
                awaitCancellation()
            }
            val ui = f.mount(proxy)
            ui.touch("session_finish")
            val ended = await(committed)
            check(ui.session.finishReceipt.value == null && ui.navigation.isEmpty())
            ui.dispose()
            check(ui.isStopped && proxy.completed.get() == 1 && proxy.strictCalls.get() == 1 && proxy.legacyCalls.get() == 0)
            f.assertOnlyEnded(original, ended)
            val committedData = f.snapshot()
            f.store.reopen()
            check(f.snapshot() == committedData) { "Reopening a real Room handle changed the committed result" }
            val next = f.mount(Sessions(f.store.sessions, listOf(ended)))
            check(next.session.finishReceipt.value == null && next.nodes("session_finish_review").isEmpty()) {
                "A new ViewModel must not invent an acknowledgement receipt or navigation"
            }
            next.openExact(ended)
            next.ready(ended)
            check(next.navigation.isEmpty() && f.snapshot() == committedData)
        }
    }

    private fun fixture(body: (Fixture) -> Unit) {
        val f = Fixture(OwnedPracticeTestStore.create(InstrumentationRegistry.getInstrumentation().targetContext))
        var primary: Throwable? = null
        try {
            f.seed()
            body(f)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null

            fun attempt(action: () -> Unit) {
                try {
                    action()
                } catch (failure: Throwable) {
                    if (cleanupFailure ==
                        null
                    ) {
                        cleanupFailure = failure
                    } else if (cleanupFailure !== failure) {
                        cleanupFailure!!.addSuppressed(failure)
                    }
                    primary?.let { if (it !== failure) it.addSuppressed(failure) }
                }
            }
            attempt { f.ui?.dispose() }
            attempt { check(f.store.schemaVersion() == 6 && f.store.foreignKeyViolationCount() == 0) }
            attempt {
                check(f.ui == null || f.ui!!.isStopped)
                f.store.close()
            }
            if (primary == null) cleanupFailure?.let { throw it }
        }
    }

    private inner class Fixture(
        val store: OwnedPracticeTestStore,
    ) {
        lateinit var active: PracticeSession
        var ui: FinishSourceReviewBoundaryTest.Driver? = null
        val memoQueries = ConcurrentLinkedQueue<String>()

        fun seed() =
            runBlocking {
                check(
                    store.schemaVersion() == 6 &&
                        store.sessions
                            .observeSessions()
                            .first()
                            .isEmpty(),
                )
                check(store.preparation.observeSelection().first() == null)
                val practice = prepared(0)
                val start = store.sessions.start(practice, TIME + 1_000) { true }
                check(start is SessionStartResult.Started)
                active = start.session
                check(
                    store.observations.append(
                        active.sessionId,
                        VehicleObservation(TIME + 1_100, FinishSourceReviewBoundaryTest.parked()),
                    ) is VehicleObservationAppendResult.Appended,
                )
                check(store.courseCompletions.observe(active.sessionId).first() == null)
            }

        private suspend fun prepared(taskIndex: Int): io.github.hangillab.hangil.core.domain.PreparedPractice {
            val course = store.catalog.courses.first()
            val goal = store.catalog.goals.first { it.id in course.goalIds }
            check(course.tasks.size > taskIndex)
            val selection = PracticeSelection(goal.id, course.id, course.version, course.briefingVersion, course.tasks[taskIndex].id)
            check(store.preparation.saveSelection(selection) { true })
            return (store.catalog.resolve(selection) as Preparation.Ready).practice
        }

        fun addNewerEnded(): PracticeSession =
            runBlocking {
                val start = store.sessions.start(prepared(1), TIME + 100_000) { true }
                check(start is SessionStartResult.Started)
                val result = store.sessions.finish(start.session, TIME + 120_000) { true }
                check(result is SessionFinishResult.Finished)
                result.session
            }

        fun mount(sessions: PracticeSessionRepository): FinishSourceReviewBoundaryTest.Driver {
            ui?.dispose()
            val memos =
                object : SessionMemoRepository by store.memos {
                    override fun observe(sessionId: String): Flow<SessionMemo?> {
                        memoQueries.add(sessionId)
                        return store.memos.observe(sessionId)
                    }
                }
            // This owned fixture has no runs. Check their real deletion snapshots; a write here must fail.
            val runs =
                object : RecordingRunRepository {
                    override fun observe(sessionId: String): Flow<List<VehicleRecordingRun>> =
                        throw AssertionError("A closed recording-run detail must not start a read")

                    override suspend fun open(
                        source: PracticeSession,
                        runId: String,
                        processId: String,
                        startedAtEpochMillis: Long,
                        canCommit: () -> Boolean,
                    ): RecordingRunOpenResult = throw AssertionError("Unexpected run creation in finish-review fixture")

                    override suspend fun finish(
                        runId: String,
                        processId: String,
                        status: RecordingRunStatus,
                        endedAtEpochMillis: Long?,
                    ): Boolean = throw AssertionError("Unexpected run mutation in finish-review fixture")

                    override suspend fun recoverInterrupted(processId: String): Unit = throw AssertionError("Unexpected run recovery")
                }
            return FinishSourceReviewBoundaryTest
                .Driver(
                    compose,
                    store.catalog,
                    store.preparation,
                    sessions,
                    memos,
                    store.observations,
                    runs,
                    store.courseCompletions,
                    store.deletions,
                    store.historyMemoWriter,
                ).also {
                    ui = it
                    it.mount()
                    it.waitTag("home_journey")
                }
        }

        fun snapshot(): Data =
            runBlocking {
                withTimeout(TIMEOUT) {
                    val sessions = store.sessions.observeSessions().first()
                    Data(
                        store.preparation.observeSelection().first(),
                        sessions,
                        sessions.associate { it.sessionId to store.observations.observe(it.sessionId).first() },
                        sessions.associate { it.sessionId to store.memos.observe(it.sessionId).first() },
                        sessions.associate { it.sessionId to store.courseCompletions.observe(it.sessionId).first() },
                        sessions.filterNot { it.isActive }.associate { source ->
                            val prepared = store.deletions.prepare(source) { true }
                            check(prepared is SessionDeletionPreparation.Ready)
                            check(prepared.target.recordingRuns.isEmpty())
                            source.sessionId to prepared.target.recordingRuns
                        },
                        store.sequenceSnapshot(),
                    )
                }
            }

        fun assertOnlyEnded(
            before: Data,
            ended: PracticeSession,
        ) {
            check(ended.copy(endedAtEpochMillis = null) == active && ended.endedAtEpochMillis != null)
            val after = snapshot()
            check(after.sessions == before.sessions.map { if (it == active) ended else it })
            check(
                after.preparation == before.preparation && after.observations == before.observations && after.memos == before.memos &&
                    after.declarations == before.declarations && after.sequence == before.sequence,
            )
            check(after.runs.values.all { it.isEmpty() })
        }
    }

    private class Sessions(
        private val real: PracticeSessionRepository,
        private val before: List<PracticeSession>,
    ) : PracticeSessionRepository by real {
        val strictCalls = AtomicInteger(0)
        val legacyCalls = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val deliver = MutableStateFlow(true)
        var onFinish: suspend (
            PracticeSession,
            Long,
            () -> Boolean,
        ) -> SessionFinishResult = { _, _, _ -> error("Unconfigured real Room boundary") }

        override fun observeSessions(): Flow<List<PracticeSession>> =
            combine(real.observeSessions(), deliver) { rows, allowed -> if (allowed) rows else before }

        override suspend fun finish(
            expectedSource: PracticeSession,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult {
            strictCalls.incrementAndGet()
            try {
                return onFinish(expectedSource, endedAtEpochMillis, canCommit)
            } finally {
                completed.incrementAndGet()
            }
        }

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult {
            legacyCalls.incrementAndGet()
            throw AssertionError("The real flow must use only the strict full-source writer")
        }
    }

    private data class Data(
        val preparation: PracticeSelection?,
        val sessions: List<PracticeSession>,
        val observations: Map<String, List<SessionVehicleObservation>>,
        val memos: Map<String, SessionMemo?>,
        val declarations: Map<String, CourseCompletionDeclaration?>,
        val runs: Map<String, List<VehicleRecordingRun>>,
        val sequence: Map<String, Long>,
    )

    private fun <T> await(value: CompletableDeferred<T>): T = runBlocking { withTimeout(TIMEOUT) { value.await() } }

    private companion object {
        const val TIME = FinishSourceReviewBoundaryTest.TIME
        const val TIMEOUT = FinishSourceReviewBoundaryTest.TIMEOUT

        fun assumeManual() = assumeTrue(InstrumentationRegistry.getArguments().getString("manualFinishSourceReviewRoom") == "true")
    }
}
