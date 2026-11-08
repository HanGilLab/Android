package io.github.hangillab.hangil.core.database

import android.database.sqlite.SQLiteException
import androidx.room.Room
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclarationResult
import io.github.hangillab.hangil.core.domain.LocationRecordingBeginResult
import io.github.hangillab.hangil.core.domain.LocationRecordingRunStatus
import io.github.hangillab.hangil.core.domain.LocationRecordingStopReason
import io.github.hangillab.hangil.core.domain.NextPracticeSaveResult
import io.github.hangillab.hangil.core.domain.NextPracticeSeed
import io.github.hangillab.hangil.core.domain.PlannedPracticeRoad
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionDeletionResult
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionReviewRoad
import io.github.hangillab.hangil.core.domain.SessionReviewRoadWriteResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real Room with owned catalog fixtures; no SDK, production store or inferred road matching. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlannedPracticeRoadStorageTest {
    private val catalog = LocalPracticeCatalog()
    private val selection = PracticeSelection("commute", "neighborhood", 1, 1, "sequence")

    @Test fun `explicit copy survives original deletion and every new session source snapshot keeps its target`() =
        runBlocking {
            withDatabase { db ->
                val review = seed(db)
                val target = selection.copy(taskId = "arrival", plannedRoad = PlannedPracticeRoad.from(review))
                val preparation = RoomPracticeRepository(db)
                assertFalse(preparation.saveSelection(target) { true })
                assertEquals(NextPracticeSaveResult.Saved, next(db).saveHistorySelection(seed(review), target, review) { true })
                assertEquals(target, preparation.observeSelection().first())
                val json = checkNotNull(db.preparationDao().current()?.plannedRoadJson)
                assertFalse(json.contains("DO_NOT_COPY_REVIEW_MEMO"))
                val deletion = RoomSessionDeletionRepository(db)
                val old = deletion.prepare(review.source) { true } as SessionDeletionPreparation.Ready
                assertEquals(SessionDeletionResult.Deleted, deletion.delete(old.target) { true })
                assertEquals(target, preparation.observeSelection().first())

                val sessions = RoomPracticeSessionRepository(db, catalog)
                val practice = (catalog.resolve(target) as Preparation.Ready).practice
                val active = (sessions.start(practice, 3_000) { true } as SessionStartResult.Started).session
                assertEquals(target, active.selection)
                val locations = RoomLocationRecordingRepository(db)
                val begun =
                    locations.begin(
                        active,
                        "planned-run",
                        "owned-process",
                        3_100,
                        100,
                    ) { true } as LocationRecordingBeginResult.Begun
                val run = begun.run
                assertEquals(
                    active,
                    locations
                        .observe(active.sessionId)
                        .first()
                        .runs
                        .single()
                        .source,
                )
                locations.end(run, LocationRecordingRunStatus.STOPPED, LocationRecordingStopReason.USER_STOP, 3_200, 101)
                val ended = (sessions.finish(active, 4_000) { true } as SessionFinishResult.Finished).session
                assertEquals(target, ended.selection)
                assertNull(RoomSessionReviewRoadRepository(db, catalog).observe(ended.sessionId).first())
                val completion =
                    RoomCourseCompletionRepository(db).declare(ended, 4_100) { true } as CourseCompletionDeclarationResult.Saved
                assertEquals(ended, completion.declaration.source)
                val ownReview = RoomSessionReviewRoadRepository(db, catalog)
                val saved = ownReview.save(ended, review.road, null) { true } as SessionReviewRoadWriteResult.Saved
                assertEquals(ended, ownReview.observe(ended.sessionId).first()?.source)
                assertEquals(ended, saved.review.source)
                assertEquals("", saved.review.memoText)
                val tables =
                    listOf(
                        "preparation",
                        "practice_session",
                        "location_recording_run",
                        "course_completion_declaration",
                        "session_review_road",
                    )
                for (table in tables) {
                    suspend fun readChecked(): Any? =
                        when (table) {
                            "preparation" -> preparation.observeSelection().first()
                            "practice_session" -> sessions.observeSessions().first()
                            "location_recording_run" -> locations.observe(ended.sessionId).first()
                            "course_completion_declaration" -> RoomCourseCompletionRepository(db).observe(ended.sessionId).first()
                            else -> ownReview.observe(ended.sessionId).first()
                        }
                    val normal = readChecked()
                    assertTrue("The fixture must be readable before its storage type changes: table=$table", normal != null)
                    db.openHelper.writableDatabase.execSQL("UPDATE `$table` SET plannedRoadJson = CAST(plannedRoadJson AS BLOB)")
                    db.openHelper.readableDatabase
                        .query(
                            "SELECT COUNT(*) FROM `$table` WHERE typeof(plannedRoadJson) = 'blob'",
                        ).use { rows ->
                            check(rows.moveToFirst())
                            assertTrue("The controlled mutation must really store BLOB: table=$table", rows.getLong(0) > 0)
                        }
                    val failure = runCatching { readChecked() }.exceptionOrNull()
                    // Room's generated getText may reject BLOB before the explicit physical-type verdict is decoded.
                    val blobConversionRejected =
                        failure is SQLiteException &&
                            failure.message?.contains("BLOB", ignoreCase = true) == true &&
                            failure.message?.contains("convert", ignoreCase = true) == true
                    assertTrue(
                        "Unexpected BLOB read: table=$table, failure=${failure?.javaClass?.name ?: "NONE"}",
                        failure is IllegalStateException || blobConversionRejected,
                    )
                    db.openHelper.writableDatabase.execSQL("UPDATE `$table` SET plannedRoadJson = CAST(plannedRoadJson AS TEXT)")
                    assertEquals("Restoring only the storage type restores the same checked snapshot: table=$table", normal, readChecked())
                }
                val confirmation = deletion.prepare(ended) { true } as SessionDeletionPreparation.Ready
                assertEquals(target, confirmation.target.session.selection)
                assertEquals(SessionDeletionResult.Deleted, deletion.delete(confirmation.target) { true })
                assertEquals(target, preparation.observeSelection().first())
                db.openHelper.readableDatabase
                    .query("PRAGMA foreign_key_check")
                    .use { assertEquals(0, it.count) }
            }
        }

    @Test fun `full review version memo source and target are pinned through the next save transaction`() =
        runBlocking {
            withDatabase { db ->
                val review = seed(db)
                val target = selection.copy(taskId = "arrival", plannedRoad = PlannedPracticeRoad.from(review))
                val reviews = RoomSessionReviewRoadRepository(db, catalog)
                val update = reviews.save(review.source, review.road, review, memoText = "changed memo") { true }
                val updated = (update as SessionReviewRoadWriteResult.Saved).review
                assertEquals(NextPracticeSaveResult.SourceChanged, next(db).saveHistorySelection(seed(review), target, review) { true })
                assertEquals(selection, RoomPracticeRepository(db).observeSelection().first())
                val freshTarget = target.copy(plannedRoad = PlannedPracticeRoad.from(updated))
                val forged =
                    SessionReviewRoad(
                        updated.source.copy(taskTitle = "changed source"),
                        updated.road,
                        updated.version,
                        updated.memoText,
                    )
                assertEquals(
                    NextPracticeSaveResult.SourceChanged,
                    next(db).saveHistorySelection(seed(review), freshTarget, forged) { true },
                )
                var checks = 0
                assertEquals(
                    NextPracticeSaveResult.SourceChanged,
                    next(db).saveHistorySelection(seed(review), freshTarget, updated) {
                        if (++checks == 2) {
                            db.openHelper.writableDatabase.execSQL("UPDATE session_review_road SET memoText = 'competing memo'")
                        }
                        true
                    },
                )
                assertEquals(updated, reviews.observe(review.source.sessionId).first())
                assertEquals(selection, RoomPracticeRepository(db).observeSelection().first())
                val pending =
                    async {
                        val owner = checkNotNull(currentCoroutineContext()[Job])
                        var calls = 0
                        next(db).saveHistorySelection(seed(review), freshTarget, updated) {
                            if (++calls == 2) owner.cancel(CancellationException("Owned cancellation before COMMIT"))
                            true
                        }
                    }
                try {
                    pending.await()
                } catch (_: CancellationException) {
                }
                assertTrue(pending.isCancelled)
                assertEquals(selection, RoomPracticeRepository(db).observeSelection().first())
                assertEquals(updated, reviews.observe(review.source.sessionId).first())
            }
        }

    @Test fun `exact ordinary edits preserve a task only target and clear it for a different context`() =
        runBlocking {
            withDatabase { db ->
                val review = seed(db)
                val target = selection.copy(plannedRoad = PlannedPracticeRoad.from(review))
                assertEquals(NextPracticeSaveResult.Saved, next(db).saveHistorySelection(seed(review), target, review) { true })
                val repository = RoomPracticeRepository(db)
                val taskEdit = target.copy(taskId = "arrival")
                assertFalse(repository.saveSelection(taskEdit) { true })
                assertFalse(repository.replaceSelection(selection, taskEdit) { true })
                assertTrue(repository.replaceSelection(target, taskEdit) { true })
                assertFalse(repository.replaceSelection(taskEdit, taskEdit.copy(plannedRoad = null)) { true })
                val forged = taskEdit.copy(plannedRoad = PlannedPracticeRoad(review.source.sessionId, "forged-version", review.road))
                assertFalse(repository.replaceSelection(taskEdit, forged) { true })
                val otherContext = taskEdit.copy(courseId = "another-course", plannedRoad = null)
                var checks = 0
                assertFalse(repository.replaceSelection(taskEdit, otherContext) { ++checks == 1 })
                assertEquals(taskEdit, repository.observeSelection().first())
                assertTrue(repository.replaceSelection(taskEdit, otherContext) { true })
                assertEquals(otherContext, repository.observeSelection().first())
            }
        }

    @Test fun `start checks the displayed target and post write preparation rather than only task metadata`() =
        runBlocking {
            withDatabase { db ->
                val review = seed(db)
                val target = selection.copy(plannedRoad = PlannedPracticeRoad.from(review))
                assertEquals(NextPracticeSaveResult.Saved, next(db).saveHistorySelection(seed(review), target, review) { true })
                val sessions = RoomPracticeSessionRepository(db, catalog)
                val expected = (catalog.resolve(target) as Preparation.Ready).practice
                var checks = 0
                assertEquals(
                    SessionStartResult.PreparationChanged,
                    sessions.start(expected, 3_000) {
                        if (++checks == 2) db.openHelper.writableDatabase.execSQL("UPDATE preparation SET plannedRoadJson = NULL")
                        true
                    },
                )
                assertEquals(target, RoomPracticeRepository(db).observeSelection().first())
                assertEquals(listOf(review.source), sessions.observeSessions().first())
                val edit =
                    RoomSessionReviewRoadRepository(db, catalog).save(
                        review.source,
                        review.road,
                        review,
                        memoText = "new version",
                    ) { true }
                val edited =
                    (
                        edit
                            as SessionReviewRoadWriteResult.Saved
                    ).review
                val fresh = target.copy(plannedRoad = PlannedPracticeRoad.from(edited))
                assertEquals(NextPracticeSaveResult.Saved, next(db).saveHistorySelection(seed(review), fresh, edited) { true })
                assertEquals(SessionStartResult.PreparationChanged, sessions.start(expected, 3_000) { true })
                assertEquals(listOf(review.source), sessions.observeSessions().first())
            }
        }

    @Test fun `a copied target is independently decoded after database close and reopen`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val name = "planned-target-${java.util.UUID.randomUUID()}.db"
            check(!context.getDatabasePath(name).exists())

            fun open() = Room.databaseBuilder(context, PreparationDatabase::class.java, name).build()
            try {
                val target: PracticeSelection
                val ended: PracticeSession
                val first = open()
                try {
                    val review = seed(first)
                    target = selection.copy(plannedRoad = PlannedPracticeRoad.from(review))
                    assertEquals(NextPracticeSaveResult.Saved, next(first).saveHistorySelection(seed(review), target, review) { true })
                    val sessions = RoomPracticeSessionRepository(first, catalog)
                    val practice = (catalog.resolve(target) as Preparation.Ready).practice
                    val active = (sessions.start(practice, 3_000) { true } as SessionStartResult.Started).session
                    ended = (sessions.finish(active, 4_000) { true } as SessionFinishResult.Finished).session
                } finally {
                    first.close()
                }
                val reopened = open()
                try {
                    assertEquals(target, RoomPracticeRepository(reopened).observeSelection().first())
                    assertEquals(ended, RoomPracticeSessionRepository(reopened, catalog).observeSessions().first().first())
                } finally {
                    reopened.close()
                }
            } finally {
                if (context.getDatabasePath(name).exists()) check(context.deleteDatabase(name))
            }
        }

    private fun next(db: PreparationDatabase) = RoomNextPracticeRepository(db, catalog)

    private fun seed(review: SessionReviewRoad) = checkNotNull(NextPracticeSeed.resolve(catalog, review.source))

    private suspend fun seed(db: PreparationDatabase): SessionReviewRoad {
        check(RoomPracticeRepository(db).saveSelection(selection) { true })
        val sessions = RoomPracticeSessionRepository(db, catalog)
        val practice = (catalog.resolve(selection) as Preparation.Ready).practice
        val active = (sessions.start(practice, 1_000) { true } as SessionStartResult.Started).session
        val ended = (sessions.finish(active, 2_000) { true } as SessionFinishResult.Finished).session
        val road =
            checkNotNull(NextPracticeSeed.resolve(catalog, ended))
                .practice.course.roadSnapshots
                .first()
        return (
            RoomSessionReviewRoadRepository(db, catalog).save(ended, road, null, memoText = "DO_NOT_COPY_REVIEW_MEMO") { true }
                as SessionReviewRoadWriteResult.Saved
        ).review
    }

    private suspend fun withDatabase(block: suspend (PreparationDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PreparationDatabase::class.java).build()
        try {
            block(db)
        } finally {
            db.close()
        }
    }
}
