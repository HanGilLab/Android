package io.github.hangillab.hangil

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.database.OwnedPracticeTestStore
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclaration
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclarationResult
import io.github.hangillab.hangil.core.domain.CourseCompletionOrigin
import io.github.hangillab.hangil.core.domain.CourseCompletionRepository
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import io.github.hangillab.hangil.feature.home.R as HomeR

/**
 * Actual UUID-owned Room, production repositories and real History Route with controlled P/UX.
 * All source/observation/memo values are synthetic and never copied from production. This proves
 * product persistence boundaries, not actual SDK gates or an internal Room dispatcher failure.
 * No Main, application graph, production declaration, service or performance sample is created.
 */
@RunWith(AndroidJUnit4::class)
class ManualCourseCompletionRoomFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()

    @Test fun explicitSaveRestoresAcrossFreshHandleAndDuplicateKeepsTheFirstOriginal() =
        fixture { f ->
            var ui = f.mount()
            val before = f.snapshot()
            ui.open()
            ui.touch("history_course_completion_request")
            ui.confirmation(f.latest)
            ui.touch("history_course_completion_cancel")
            ui.absent("history_course_completion_dialog")
            assertEquals("confirmation cancellation writes nothing", before, f.snapshot())
            ui.touch("history_course_completion_request")
            val sameConsent = ui.action("history_course_completion_confirm")
            ui.invoke(sameConsent, sameConsent)
            ui.waitTag("history_course_completion_ready")
            val stored = f.declaration(f.latest)
            assertEquals(f.latest, stored.source)
            assertEquals(CourseCompletionOrigin.USER_DECLARATION, stored.origin)
            assertTrue(stored.version.isNotBlank() && stored.declaredAtEpochMillis > 0)
            assertEquals(before.copy(declarations = before.declarations + (f.latest.sessionId to stored)), f.snapshot())
            val duplicate =
                runBlocking {
                    f.store.courseCompletions.declare(f.latest, 1) { true }
                }
            assertEquals(
                "a repeated declaration keeps source/time/version",
                CourseCompletionDeclarationResult.AlreadyDeclared(stored),
                duplicate,
            )
            ui.invoke(sameConsent)
            assertEquals(stored, f.declaration(f.latest))
            ui.dispose()
            compose.activityRule.scenario.recreate()
            f.store.reopen()
            ui = f.mount()
            ui.ready(f.latest)
            ui.touch("history_course_completion")
            ui.waitTag("history_course_completion_ready")
            ui.assertText("history_course_completion_source", ui.text(HomeR.string.course_completion_source))
            assertEquals("new handle and VM read the durable original", stored, f.declaration(f.latest))
            val shown = ui.nodes("history_course_completion_content").flatMap(ui::texts).joinToString(" ")
            assertFalse(shown.contains(stored.version) || shown.contains(stored.source.sessionId))
            assertEquals(before.copy(declarations = before.declarations + (f.latest.sessionId to stored)), f.snapshot())
        }

    @Test fun realTransactionRejectionsAndCancelledAdapterLeaveTheOwnedSourceUndeclared() =
        fixture { f ->
            val before = f.snapshot()
            val repository = f.store.courseCompletions
            runBlocking {
                assertEquals(
                    CourseCompletionDeclarationResult.SourceChanged,
                    repository.declare(f.latest.copy(taskTitle = "격리 변경 source"), TIME) { true },
                )
                assertEquals(
                    CourseCompletionDeclarationResult.MissingSession,
                    repository.declare(f.latest.copy(sessionId = "owned-missing-source"), TIME) { true },
                )
                assertEquals(CourseCompletionDeclarationResult.InvalidTime, repository.declare(f.latest, 0) { true })
                val checks = AtomicInteger(0)
                assertEquals(
                    CourseCompletionDeclarationResult.Blocked,
                    repository.declare(f.latest, TIME) { checks.incrementAndGet() == 1 },
                )
                assertTrue("real transaction must recheck permission", checks.get() >= 2)
            }
            assertEquals("rejected writes preserve every owned field and sequence", before, f.snapshot())
            val entered = CompletableDeferred<Unit>()
            val cancelled = AtomicBoolean(false)
            val held =
                object : CourseCompletionRepository by repository {
                    override suspend fun declare(
                        expectedSource: PracticeSession,
                        declaredAtEpochMillis: Long,
                        canCommit: () -> Boolean,
                    ): CourseCompletionDeclarationResult {
                        entered.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.set(true)
                        }
                    }
                }
            var ui = f.mount(held)
            ui.open()
            ui.touch("history_course_completion_request")
            ui.touch("history_course_completion_confirm")
            runBlocking { withTimeout(20_000) { entered.await() } }
            ui.move(Lifecycle.State.CREATED)
            compose.waitUntil(20_000) { cancelled.get() }
            ui.dispose()
            assertEquals("cancel before repository invocation cannot invent a save", before, f.snapshot())
            val failedRead = AtomicBoolean(true)
            val fallible =
                object : CourseCompletionRepository by repository {
                    override fun observe(sessionId: String): Flow<CourseCompletionDeclaration?> =
                        flow {
                            check(!failedRead.get()) { "controlled adapter read failure before real Room read" }
                            emitAll(repository.observe(sessionId))
                        }
                }
            ui = f.mount(fallible)
            ui.ready(f.latest)
            ui.touch("history_course_completion")
            ui.waitTag("history_course_completion_failed")
            assertTrue(ui.nodes("history_course_completion_absent").isEmpty())
            failedRead.set(false)
            ui.touch("history_course_completion_retry")
            ui.waitTag("history_course_completion_absent")
            assertEquals(before, f.snapshot())
            ui.dispose()
            val practice = (f.store.catalog.resolve(before.preparation) as Preparation.Ready).practice
            val active = runBlocking { f.store.sessions.start(practice, TIME + 9_000) { true } }
            assertTrue(active is SessionStartResult.Started)
            runBlocking {
                assertEquals(CourseCompletionDeclarationResult.ActiveSession, repository.declare(f.latest, TIME) { true })
            }
            ui = f.mount()
            ui.ready(f.latest)
            ui.open()
            ui.waitTag("history_course_completion_active")
            assertTrue(ui.nodes("history_course_completion_ready").isEmpty())
            ui.dispose()
            val finished =
                runBlocking {
                    f.store.sessions.finish((active as SessionStartResult.Started).session.sessionId, TIME + 10_000) { true }
                }
            assertTrue(finished is SessionFinishResult.Finished)
            assertTrue(
                "separate owned active fixture must never declare old records",
                f
                    .snapshot()
                    .declarations.values
                    .all { it == null },
            )
        }

    @Test fun committedResultDeliveryCancellationReopensTheSameStoredOriginalWithoutAnotherWrite() =
        fixture { f ->
            val delegate = f.store.courseCompletions
            val committed = CompletableDeferred<CourseCompletionDeclaration>()
            val deliveryCancelled = AtomicBoolean(false)
            val calls = AtomicInteger(0)
            val heldAfterCommit =
                object : CourseCompletionRepository by delegate {
                    // Hold later read delivery too, so a valid Flow-before-ACK does not cancel this
                    // explicitly controlled result-delivery fixture before its committed receipt exists.
                    override fun observe(sessionId: String): Flow<CourseCompletionDeclaration?> =
                        flow {
                            emit(delegate.observe(sessionId).first())
                            awaitCancellation()
                        }

                    override suspend fun declare(
                        expectedSource: PracticeSession,
                        declaredAtEpochMillis: Long,
                        canCommit: () -> Boolean,
                    ): CourseCompletionDeclarationResult {
                        calls.incrementAndGet()
                        val result = delegate.declare(expectedSource, declaredAtEpochMillis, canCommit)
                        check(result is CourseCompletionDeclarationResult.Saved)
                        committed.complete(result.declaration)
                        try {
                            awaitCancellation()
                        } finally {
                            deliveryCancelled.set(true)
                        }
                    }
                }
            var ui = f.mount(heldAfterCommit)
            ui.open()
            ui.touch("history_course_completion_request")
            ui.touch("history_course_completion_confirm")
            val stored = runBlocking { withTimeout(20_000) { committed.await() } }
            assertEquals("actual Room commit precedes controlled response cancellation", stored, f.declaration(f.latest))
            ui.move(Lifecycle.State.CREATED)
            compose.waitUntil(20_000) { deliveryCancelled.get() }
            ui.dispose()
            f.store.reopen()
            val fresh = f.store.courseCompletions
            val noReplay =
                object : CourseCompletionRepository by fresh {
                    override suspend fun declare(
                        expectedSource: PracticeSession,
                        declaredAtEpochMillis: Long,
                        canCommit: () -> Boolean,
                    ): CourseCompletionDeclarationResult {
                        calls.incrementAndGet()
                        error("fresh reconciliation must only read the committed row")
                    }
                }
            ui = f.mount(noReplay)
            ui.ready(f.latest)
            ui.touch("history_course_completion")
            ui.waitTag("history_course_completion_ready")
            assertEquals(stored, f.declaration(f.latest))
            assertEquals("reconciliation reads the committed row instead of writing again", 1, calls.get())
            assertTrue(ui.nodes("history_course_completion_request").isEmpty())
        }

    private fun fixture(block: (Fixture) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("manualCourseCompletionRoomFlow") == "true")
        val f = Fixture(OwnedPracticeTestStore.create(InstrumentationRegistry.getInstrumentation().targetContext))
        var primary: Throwable? = null
        try {
            f.seed()
            block(f)
        } catch (failure: Throwable) {
            primary = failure
            f.ui?.diagnose(failure)
            throw failure
        } finally {
            var first: Throwable? = null

            fun attempt(action: () -> Unit) {
                try {
                    action()
                } catch (cleanup: Throwable) {
                    if (first == null) {
                        first = cleanup
                    } else if (first !== cleanup) {
                        first!!.addSuppressed(cleanup)
                    }
                    primary?.let { if (it !== cleanup) it.addSuppressed(cleanup) }
                }
            }
            attempt { f.ui?.dispose() }
            attempt {
                assertTrue("owned Room remains schema6/FK clean", f.store.schemaVersion() == 6 && f.store.foreignKeyViolationCount() == 0)
            }
            // Source additions in the explicit active-session test are owned fixtures. All originals survive.
            attempt { f.assertSeedPreserved() }
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
        lateinit var latest: PracticeSession
        private lateinit var initial: Data
        var ui: ManualCourseCompletionBoundaryTest.Driver? = null

        fun seed() =
            runBlocking {
                check(
                    store.sessions
                        .observeSessions()
                        .first()
                        .isEmpty() && store.preparation.observeSelection().first() == null,
                )
                val course = store.catalog.courses.first()
                val goal = store.catalog.goals.first { it.id in course.goalIds }
                repeat(2) { index ->
                    val selection = PracticeSelection(goal.id, course.id, course.version, course.briefingVersion, course.tasks[index].id)
                    check(store.preparation.saveSelection(selection) { true })
                    val practice = (store.catalog.resolve(selection) as Preparation.Ready).practice
                    val started = store.sessions.start(practice, TIME + index * 2_000) { true }
                    check(started is SessionStartResult.Started)
                    val source = started.session
                    check(
                        store.observations.append(
                            source.sessionId,
                            VehicleObservation(
                                TIME + index * 2_000 + 100,
                                ManualCourseCompletionBoundaryTest.parked(),
                            ),
                        ) is VehicleObservationAppendResult.Appended,
                    )
                    val finished = store.sessions.finish(source.sessionId, TIME + index * 2_000 + 1_000) { true }
                    check(finished is SessionFinishResult.Finished)
                    latest = finished.session
                    check(store.memos.save(latest.sessionId, "격리 fixture 메모", null) { true } is SessionMemoWriteResult.Saved)
                }
                initial = snapshot()
                check(initial.declarations.values.all { it == null })
            }

        fun mount(repo: CourseCompletionRepository = store.courseCompletions): ManualCourseCompletionBoundaryTest.Driver {
            ui?.dispose()
            return ManualCourseCompletionBoundaryTest
                .Driver(
                    compose,
                    store.sessions,
                    repo,
                    store.deletions,
                    store.observations,
                    store.memos,
                ).also {
                    ui = it
                    it.mount()
                    it.ready(latest)
                }
        }

        fun declaration(source: PracticeSession): CourseCompletionDeclaration =
            runBlocking {
                withTimeout(20_000) { checkNotNull(store.courseCompletions.observe(source.sessionId).first()) }
            }

        fun snapshot(): Data =
            runBlocking {
                withTimeout(20_000) {
                    val sessions = store.sessions.observeSessions().first()
                    Data(
                        store.preparation.observeSelection().first(),
                        sessions,
                        sessions.associate { it.sessionId to store.observations.observe(it.sessionId).first() },
                        sessions.associate { it.sessionId to store.memos.observe(it.sessionId).first() },
                        sessions.associate { it.sessionId to store.courseCompletions.observe(it.sessionId).first() },
                        store.sequenceSnapshot(),
                    )
                }
            }

        fun assertSeedPreserved() {
            if (!::initial.isInitialized) return
            val after = snapshot()
            assertEquals(initial.preparation, after.preparation)
            assertEquals(initial.sessions, after.sessions.filter { row -> initial.sessions.any { it.sessionId == row.sessionId } })
            assertEquals(initial.observations, after.observations.filterKeys { it in initial.observations })
            assertEquals(initial.memos, after.memos.filterKeys { it in initial.memos })
            initial.sequence.forEach { (table, value) ->
                assertTrue("owned sequences never decrease", checkNotNull(after.sequence[table]) >= value)
            }
        }
    }

    private data class Data(
        val preparation: PracticeSelection?,
        val sessions: List<PracticeSession>,
        val observations: Map<String, List<SessionVehicleObservation>>,
        val memos: Map<String, SessionMemo?>,
        val declarations: Map<String, CourseCompletionDeclaration?>,
        val sequence: Map<String, Long>,
    )

    private companion object {
        const val TIME = 1_700_000_000_000L
    }
}
