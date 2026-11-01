package io.github.hangillab.hangil

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.database.OwnedPracticeTestStore
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclaration
import io.github.hangillab.hangil.core.domain.HistorySessionMemoWriter
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionDeletionResult
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoPolicy
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Actual UUID-owned Room/production writer/Route with synthetic P+UX. Never production data or actual SDK evidence. */
@RunWith(AndroidJUnit4::class)
class HistoryMemoEditRoomFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()

    @Test fun selectedOlderMemoCancelSaveAndFreshHandlePreserveNewestAndEveryOtherOwnedField() =
        fixture { f ->
            val before = f.snapshot()
            val original = checkNotNull(before.memos[f.older.sessionId])
            val prepared = runBlocking { f.store.deletions.prepare(f.older) { true } }
            check(prepared is SessionDeletionPreparation.Ready)
            val calls = AtomicInteger(0)
            val actual = f.store.historyMemoWriter
            var ui =
                f.mount(
                    object : HistorySessionMemoWriter {
                        override suspend fun update(
                            expectedSource: PracticeSession,
                            expectedMemo: SessionMemo,
                            text: String,
                            canCommit: () -> Boolean,
                        ): SessionMemoWriteResult {
                            calls.incrementAndGet()
                            return actual.update(expectedSource, expectedMemo, text, canCommit)
                        }
                    },
                )
            ui.assertExpectedTheme()
            ui.beginEdit()
            ui.replace("cancelled owned draft")
            ui.showIme()
            ui.assertVisibleText("history_memo_edit_context")
            ui.assertVisibleText("history_memo_save")
            ui.assertVisibleText("history_memo_cancel_edit")
            ui.capture("owned-cancel-ime", "owned-Room-production-writer-controlled-SDK")
            ui.touch("history_memo_cancel_edit")
            ui.awaitIme(false)
            ui.assertText("history_memo_text", original.text)
            assertEquals(0, calls.get())
            assertEquals(before, f.snapshot())
            ui.beginEdit()
            val exact = "  owned historical edit 😀\nsecond line  "
            ui.replace(exact)
            ui.showIme()
            ui.assertVisibleText("history_memo_textfield")
            ui.assertVisibleText("history_memo_save")
            ui.assertVisibleText("history_memo_cancel_edit")
            ui.capture("owned-save-ime", "owned-Room-production-writer-controlled-SDK")
            ui.touch("history_memo_save")
            ui.awaitIme(false)
            ui.assertText("history_memo_text", exact)
            val saved = f.memo()
            assertTrue(saved.version.isNotBlank() && saved.version != original.version)
            assertEquals(exact, saved.text)
            assertEquals(1, calls.get())
            f.assertOnlyMemoChanged(before, saved)
            val staleDeletion = runBlocking { f.store.deletions.delete(prepared.target) { true } }
            assertEquals("updated memo version invalidates the old deletion receipt", SessionDeletionResult.Changed, staleDeletion)
            f.assertOnlyMemoChanged(before, saved)
            val stale = runBlocking { f.store.historyMemoWriter.update(f.older, original, "stale old version") { true } }
            assertEquals(SessionMemoWriteResult.Conflict, stale)
            f.assertOnlyMemoChanged(before, saved)
            ui.assertVisibleText("history_memo_text")
            ui.capture("owned-saved", "owned-Room-production-writer-controlled-SDK")
            ui.dispose()
            compose.activityRule.scenario.recreate()
            f.store.reopen()
            ui =
                f.mount(
                    object : HistorySessionMemoWriter {
                        override suspend fun update(
                            expectedSource: PracticeSession,
                            expectedMemo: SessionMemo,
                            text: String,
                            canCommit: () -> Boolean,
                        ): SessionMemoWriteResult = throw AssertionError("fresh reopen must only read")
                    },
                )
            ui.assertText("history_memo_text", exact)
            assertEquals(saved, f.memo())
            f.assertOnlyMemoChanged(before, saved)
            ui.assertVisibleText("history_memo_text")
            ui.capture("owned-fresh-handle", "owned-Room-production-writer-controlled-SDK")
        }

    @Test fun invalidSourceVersionUnicodeAndLiveDenialNeverChangeTheOriginalOwnedMemo() =
        fixture { f ->
            val before = f.snapshot()
            val original = f.memo()
            val actual = f.store.historyMemoWriter
            runBlocking {
                assertEquals(
                    SessionMemoWriteResult.SessionChanged,
                    actual.update(f.older.copy(taskTitle = "changed source"), original, "draft") { true },
                )
                assertEquals(
                    SessionMemoWriteResult.Conflict,
                    actual.update(f.older, original.copy(version = "stale"), "draft") { true },
                )
                for (text in listOf("", "\u0000", "\uD800", "x".repeat(SessionMemoPolicy.MAX_CODE_POINTS + 1))) {
                    assertEquals(SessionMemoWriteResult.InvalidText, actual.update(f.older, original, text) { true })
                }
                val checks = AtomicInteger(0)
                assertEquals(
                    SessionMemoWriteResult.Blocked,
                    actual.update(f.older, original, "must roll back") {
                        checks.incrementAndGet() == 1
                    },
                )
                assertTrue(checks.get() >= 2)
            }
            assertEquals(before, f.snapshot())
            val calls = AtomicInteger(0)
            val ui =
                f.mount(
                    object : HistorySessionMemoWriter {
                        override suspend fun update(
                            expectedSource: PracticeSession,
                            expectedMemo: SessionMemo,
                            text: String,
                            canCommit: () -> Boolean,
                        ): SessionMemoWriteResult {
                            calls.incrementAndGet()
                            return actual.update(expectedSource, expectedMemo, text, canCommit)
                        }
                    },
                )
            ui.beginEdit()
            ui.replace("denied old draft")
            val oldSave = ui.action("history_memo_save")
            compose.runOnIdle {
                ui.live.silent(HistoryMemoEditBoundaryTest.driving())
                oldSave()
                ui.live.silent(HistoryMemoEditBoundaryTest.parked())
            }
            ui.invoke(oldSave)
            assertEquals(0, calls.get())
            assertEquals(before, f.snapshot())
            ui.publishParked()
            ui.ready(f.older)
            ui.openMemo(original.text)
            ui.beginEdit()
            ui.replace("fresh explicit edit")
            ui.touch("history_memo_save")
            ui.assertText("history_memo_text", "fresh explicit edit")
            assertEquals(1, calls.get())
            f.assertOnlyMemoChanged(before, f.memo())
        }

    @Test fun committedReplyCancellationReopensStoredVersionWithoutAnotherWrite() =
        fixture { f ->
            val before = f.snapshot()
            val actual = f.store.historyMemoWriter
            val actualMemos = f.store.memos
            val committed = CompletableDeferred<SessionMemo>()
            val cancelled = AtomicBoolean(false)
            val calls = AtomicInteger(0)
            val heldRead =
                object : SessionMemoRepository by actualMemos {
                    override fun observe(sessionId: String): Flow<SessionMemo?> =
                        flow {
                            emit(actualMemos.observe(sessionId).first())
                            awaitCancellation()
                        }
                }
            var ui =
                f.mount(
                    object : HistorySessionMemoWriter {
                        override suspend fun update(
                            expectedSource: PracticeSession,
                            expectedMemo: SessionMemo,
                            text: String,
                            canCommit: () -> Boolean,
                        ): SessionMemoWriteResult {
                            calls.incrementAndGet()
                            val result = actual.update(expectedSource, expectedMemo, text, canCommit)
                            check(result is SessionMemoWriteResult.Saved)
                            committed.complete(result.memo)
                            try {
                                awaitCancellation()
                            } finally {
                                cancelled.set(true)
                            }
                        }
                    },
                    heldRead,
                )
            ui.beginEdit()
            ui.replace("committed before controlled response cancellation")
            ui.touch("history_memo_save")
            val saved = runBlocking { withTimeout(20_000) { committed.await() } }
            assertEquals(saved, f.memo())
            ui.move(Lifecycle.State.CREATED)
            compose.waitUntil(20_000) { cancelled.get() }
            ui.dispose()
            f.store.reopen()
            ui =
                f.mount(
                    object : HistorySessionMemoWriter {
                        override suspend fun update(
                            expectedSource: PracticeSession,
                            expectedMemo: SessionMemo,
                            text: String,
                            canCommit: () -> Boolean,
                        ): SessionMemoWriteResult {
                            calls.incrementAndGet()
                            throw AssertionError("reconciliation must never write")
                        }
                    },
                )
            ui.assertText("history_memo_text", saved.text)
            assertEquals(1, calls.get())
            f.assertOnlyMemoChanged(before, saved)
        }

    private fun fixture(block: (Fixture) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("manualHistoryMemoEditRoomFlow") == "true")
        val f = Fixture(OwnedPracticeTestStore.create(InstrumentationRegistry.getInstrumentation().targetContext))
        var primary: Throwable? = null
        try {
            f.seed()
            block(f)
        } catch (failure: Throwable) {
            primary = failure
            f.ui?.diagnose(failure)
            try {
                f.ui?.capture("owned-failure", "owned-Room-production-writer-controlled-SDK", true)
            } catch (
                capture: Throwable,
            ) {
                if (capture !== failure) failure.addSuppressed(capture)
            }
            throw failure
        } finally {
            var first: Throwable? = null

            fun attempt(action: () -> Unit) {
                try {
                    action()
                } catch (failure: Throwable) {
                    if (first == null) {
                        first = failure
                    } else if (first !== failure) {
                        first!!.addSuppressed(failure)
                    }
                    primary?.let { if (it !== failure) it.addSuppressed(failure) }
                }
            }
            attempt { f.ui?.dispose() }
            attempt { f.assertOtherFieldsPreserved() }
            attempt { check(f.store.schemaVersion() == 6 && f.store.foreignKeyViolationCount() == 0) }
            attempt {
                check(f.ui == null || f.ui!!.isStopped)
                f.store.close()
            }
            if (primary == null) first?.let { throw it }
        }
    }

    private inner class Fixture(
        val store: OwnedPracticeTestStore,
    ) {
        lateinit var older: PracticeSession
        lateinit var latest: PracticeSession
        private lateinit var initial: Data
        var ui: HistoryMemoEditBoundaryTest.Driver? = null

        fun seed() =
            runBlocking {
                check(
                    store.sessions
                        .observeSessions()
                        .first()
                        .isEmpty(),
                )
                val course = store.catalog.courses.first()
                val goal = store.catalog.goals.first { it.id in course.goalIds }
                repeat(2) { index ->
                    val selected = PracticeSelection(goal.id, course.id, course.version, course.briefingVersion, course.tasks[index].id)
                    check(store.preparation.saveSelection(selected) { true })
                    val practice = (store.catalog.resolve(selected) as Preparation.Ready).practice
                    val start = store.sessions.start(practice, TIME + index * 2_000) { true }
                    check(start is SessionStartResult.Started)
                    check(
                        store.observations.append(
                            start.session.sessionId,
                            VehicleObservation(
                                TIME + index * 2_000 + 100,
                                HistoryMemoEditBoundaryTest.parked(),
                            ),
                        ) is VehicleObservationAppendResult.Appended,
                    )
                    val end = store.sessions.finish(start.session.sessionId, TIME + index * 2_000 + 1_000) { true }
                    check(end is SessionFinishResult.Finished)
                    val text = if (index == 0) "  owned original 😀\nsecond line  " else "newest memo unchanged"
                    check(store.memos.save(end.session.sessionId, text, null) { true } is SessionMemoWriteResult.Saved)
                    if (index == 0) older = end.session else latest = end.session
                }
                initial = snapshot()
                check(initial.sessions.first() == latest && older != latest)
            }

        fun mount(
            writer: HistorySessionMemoWriter = store.historyMemoWriter,
            memos: SessionMemoRepository = store.memos,
        ): HistoryMemoEditBoundaryTest.Driver {
            ui?.dispose()
            return HistoryMemoEditBoundaryTest.Driver(compose, store.sessions, memos, writer, store.deletions).also {
                ui = it
                it.mount()
                it.ready(latest)
                it.touch("history_older")
                it.ready(older)
                it.openMemo(memo().text)
            }
        }

        fun memo(): SessionMemo = runBlocking { withTimeout(20_000) { checkNotNull(store.memos.observe(older.sessionId).first()) } }

        fun snapshot(): Data =
            runBlocking {
                withTimeout(20_000) {
                    val sessions = store.sessions.observeSessions().first()
                    Data(
                        store.preparation.observeSelection().first(),
                        sessions,
                        sessions.associate { it.sessionId to store.memos.observe(it.sessionId).first() },
                        sessions.associate { it.sessionId to store.observations.observe(it.sessionId).first() },
                        sessions.associate { it.sessionId to store.courseCompletions.observe(it.sessionId).first() },
                        store.sequenceSnapshot(),
                    )
                }
            }

        fun assertOnlyMemoChanged(
            before: Data,
            memo: SessionMemo,
        ) = assertEquals(before.copy(memos = before.memos + (older.sessionId to memo)), snapshot())

        fun assertOtherFieldsPreserved() {
            if (!::initial.isInitialized) return
            val after = snapshot()
            assertEquals(initial.copy(memos = after.memos), after)
            assertEquals(initial.memos.getValue(latest.sessionId), after.memos.getValue(latest.sessionId))
        }
    }

    private data class Data(
        val preparation: PracticeSelection?,
        val sessions: List<PracticeSession>,
        val memos: Map<String, SessionMemo?>,
        val observations: Map<String, List<SessionVehicleObservation>>,
        val declarations: Map<String, CourseCompletionDeclaration?>,
        val sequence: Map<String, Long>,
    )

    private companion object {
        const val TIME = 1_700_000_000_000L
    }
}
