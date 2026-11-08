package io.github.hangillab.hangil.core.database

import android.database.Cursor
import android.database.sqlite.SQLiteException
import androidx.room.Room
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionDeletionResult
import io.github.hangillab.hangil.core.domain.SessionReviewRoad
import io.github.hangillab.hangil.core.domain.SessionReviewRoadWriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/** Real Room with owned fixtures; no SDK, location matching or production store is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomSessionReviewRoadRepositoryTest {
    @Test fun `whole choices including absence use CAS and a matching same road preserves its token`() =
        runBlocking {
            withDatabase { db ->
                val catalog = MutableCatalog()
                val source = seed(db, catalog)
                seed(db, catalog)
                val repo = RoomSessionReviewRoadRepository(db, catalog)
                val roads = catalog.courses.first().roadSnapshots
                val original = snapshot(db, includeReview = false)
                assertNull(repo.observe(source.sessionId).first())
                val first = saved(repo.save(source, roads[0], null) { true })
                assertEquals(source, first.source)
                assertEquals(roads[0], first.road)
                assertEquals("USER_SELECTION", first.origin)
                assertEquals(SessionReviewRoadWriteResult.Conflict, repo.save(source, roads[0], null) { true })
                assertEquals(first, saved(repo.save(source, roads[0], first) { true }))
                val forged = SessionReviewRoad(source, first.road.copy(title = "changed fixture title"), first.version)
                assertEquals(SessionReviewRoadWriteResult.Conflict, repo.clear(source, forged) { true })
                val next = saved(repo.save(source, roads[1], first) { true })
                assertNotEquals(first.version, next.version)
                assertEquals(SessionReviewRoadWriteResult.Conflict, repo.save(source, roads[0], first) { true })
                assertEquals(SessionReviewRoadWriteResult.Conflict, repo.clear(source, first) { true })
                assertEquals(next, repo.observe(source.sessionId).first())
                assertEquals(original, snapshot(db, includeReview = false))
            }
        }

    @Test fun `an ordered closed snapshot survives reopen and can be cleared after its catalog disappears`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val name = "review-road-fixture-${UUID.randomUUID()}.db"
            val catalog = MutableCatalog()
            val originalRoad =
                catalog.courses
                    .first()
                    .roadSnapshots
                    .first()
            val closed = originalRoad.copy(nodes = originalRoad.nodes + originalRoad.nodes.first())
            catalog.courses =
                catalog.courses.mapIndexed { index, course -> if (index == 0) course.copy(roadSnapshots = listOf(closed)) else course }

            fun open() = Room.databaseBuilder(context, PreparationDatabase::class.java, name).build()
            try {
                val source: PracticeSession
                val review: SessionReviewRoad
                val original: Map<String, List<List<Pair<Int, Any?>>>>
                val first = open()
                try {
                    source = seed(first, catalog)
                    original = snapshot(first, includeReview = false)
                    review =
                        saved(
                            RoomSessionReviewRoadRepository(first, catalog)
                                .save(source, closed, null, memoText = "  원문\n메모 🙂  ") { true },
                        )
                } finally {
                    first.close()
                }
                val reopened = open()
                try {
                    catalog.courses = emptyList()
                    val repo = RoomSessionReviewRoadRepository(reopened, catalog)
                    assertEquals(review, repo.observe(source.sessionId).first())
                    assertEquals(
                        closed.nodes,
                        repo
                            .observe(source.sessionId)
                            .first()
                            ?.road
                            ?.nodes,
                    )
                    assertEquals("  원문\n메모 🙂  ", repo.observe(source.sessionId).first()?.memoText)
                    val edited = saved(repo.save(source, review.road, review, memoText = "카탈로그 없이 고친 메모") { true })
                    assertEquals(review.road, edited.road)
                    assertEquals(SessionReviewRoadWriteResult.Cleared, repo.clear(source, edited, discardMemo = true) { true })
                    assertNull(repo.observe(source.sessionId).first())
                    assertEquals(SessionReviewRoadWriteResult.Conflict, repo.clear(source, review) { true })
                    assertEquals(original, snapshot(reopened, includeReview = false))
                } finally {
                    reopened.close()
                }
            } finally {
                if (context.getDatabasePath(name).exists()) check(context.deleteDatabase(name))
            }
        }

    @Test fun `post write denial and cancellation roll back header and ordered nodes together`() =
        runBlocking {
            withDatabase { db ->
                val catalog = MutableCatalog()
                val source = seed(db, catalog)
                val repo = RoomSessionReviewRoadRepository(db, catalog)
                val roads = catalog.courses.first().roadSnapshots
                val absent = snapshot(db)
                var checks = 0
                assertEquals(SessionReviewRoadWriteResult.Blocked, repo.save(source, roads[0], null) { ++checks == 1 })
                assertEquals(2, checks)
                assertEquals(absent, snapshot(db))
                val original = saved(repo.save(source, roads[0], null) { true })
                val before = snapshot(db)
                checks = 0
                assertEquals(SessionReviewRoadWriteResult.Blocked, repo.save(source, roads[1], original) { ++checks == 1 })
                assertEquals(before, snapshot(db))
                checks = 0
                assertEquals(SessionReviewRoadWriteResult.Blocked, repo.clear(source, original) { ++checks == 1 })
                assertEquals(before, snapshot(db))
                checks = 0
                val pending =
                    async {
                        val owner = checkNotNull(currentCoroutineContext()[Job])
                        repo.save(source, roads[1], original) {
                            if (++checks == 2) owner.cancel()
                            true
                        }
                    }
                try {
                    pending.await()
                    fail("Cancelled review road write must not return a result")
                } catch (_: CancellationException) {
                }
                assertEquals(before, snapshot(db))
            }
        }

    @Test fun `memo edits preserve exact text and nodes and compare the whole memo before no op`() =
        runBlocking {
            withDatabase { db ->
                val catalog = MutableCatalog()
                val source = seed(db, catalog)
                val repo = RoomSessionReviewRoadRepository(db, catalog)
                val road =
                    catalog.courses
                        .first()
                        .roadSnapshots
                        .first()
                val first = saved(repo.save(source, road, null) { true })
                val originalNodes = snapshot(db)["session_review_road_node"]
                val text = "  줄바꿈\n사용자 원문 🙂\n  "
                val noted = saved(repo.save(source, road, first, memoText = text) { true })
                assertEquals(text, noted.memoText)
                assertNotEquals(first.version, noted.version)
                assertEquals(originalNodes, snapshot(db)["session_review_road_node"])
                assertEquals(noted, saved(repo.save(source, road, noted) { true }))
                val forged = SessionReviewRoad(source, road, noted.version, "다른 원문")
                assertEquals(SessionReviewRoadWriteResult.Conflict, repo.save(source, road, forged, memoText = "덮지 않음") { true })
                assertEquals(SessionReviewRoadWriteResult.InvalidMemo, repo.save(source, road, noted, memoText = " \n ") { true })
                assertEquals(noted, repo.observe(source.sessionId).first())
                var checks = 0
                val before = snapshot(db)
                assertEquals(
                    SessionReviewRoadWriteResult.Blocked,
                    repo.save(source, road, noted, memoText = "차단된 메모") { ++checks == 1 },
                )
                assertEquals(before, snapshot(db))
                checks = 0
                val pending =
                    async {
                        val owner = checkNotNull(currentCoroutineContext()[Job])
                        repo.save(source, road, noted, memoText = "취소된 메모") {
                            if (++checks == 2) owner.cancel()
                            true
                        }
                    }
                try {
                    pending.await()
                    fail("Cancelled memo write must not return a result")
                } catch (_: CancellationException) {
                }
                assertEquals(before, snapshot(db))
                val empty = saved(repo.save(source, road, noted, memoText = "") { true })
                assertEquals("", empty.memoText)
                assertEquals(originalNodes, snapshot(db)["session_review_road_node"])
            }
        }

    @Test fun `road replacement and clear require explicit memo discard and never copy it to the new road`() =
        runBlocking {
            withDatabase { db ->
                val catalog = MutableCatalog()
                val source = seed(db, catalog)
                val repo = RoomSessionReviewRoadRepository(db, catalog)
                val roads = catalog.courses.first().roadSnapshots
                val first = saved(repo.save(source, roads[0], null, memoText = "첫 도로 메모") { true })
                val before = snapshot(db)
                assertEquals(SessionReviewRoadWriteResult.MemoPresent, repo.save(source, roads[1], first) { true })
                assertEquals(SessionReviewRoadWriteResult.MemoPresent, repo.clear(source, first) { true })
                assertEquals(SessionReviewRoadWriteResult.InvalidMemo, repo.save(source, roads[1], first, discardMemo = true) { true })
                assertEquals(before, snapshot(db))
                val replaced = saved(repo.save(source, roads[1], first, memoText = "", discardMemo = true) { true })
                assertEquals("", replaced.memoText)
                assertEquals(roads[1], replaced.road)
                assertEquals(SessionReviewRoadWriteResult.Conflict, repo.clear(source, first, discardMemo = true) { true })
                val noted = saved(repo.save(source, replaced.road, replaced, memoText = "두 번째 도로 메모") { true })
                var checks = 0
                val notedBefore = snapshot(db)
                assertEquals(SessionReviewRoadWriteResult.Blocked, repo.clear(source, noted, discardMemo = true) { ++checks == 1 })
                assertEquals(notedBefore, snapshot(db))
                assertEquals(SessionReviewRoadWriteResult.Cleared, repo.clear(source, noted, discardMemo = true) { true })
                assertNull(repo.observe(source.sessionId).first())
            }
        }

    @Test fun `source active state and complete catalog provenance are rechecked without writes`() =
        runBlocking {
            withDatabase { db ->
                val catalog = MutableCatalog()
                val source = seed(db, catalog)
                val repo = RoomSessionReviewRoadRepository(db, catalog)
                val road =
                    catalog.courses
                        .first()
                        .roadSnapshots
                        .first()
                val before = snapshot(db)
                assertEquals(
                    SessionReviewRoadWriteResult.MissingSession,
                    repo.save(source.copy(sessionId = "missing-fixture"), road, null) { true },
                )
                assertEquals(
                    SessionReviewRoadWriteResult.SourceChanged,
                    repo.save(source.copy(taskTitle = "changed fixture"), road, null) { true },
                )
                val changedRoad = road.copy(nodes = listOf(road.nodes.first().copy(osmNodeVersion = 99)) + road.nodes.drop(1))
                assertEquals(SessionReviewRoadWriteResult.UnavailableRoad, repo.save(source, changedRoad, null) { true })
                var checks = 0
                assertEquals(
                    SessionReviewRoadWriteResult.UnavailableRoad,
                    repo.save(source, road, null) {
                        if (++checks == 2) catalog.courses = catalog.courses.map { it.copy(summary = "changed catalog fixture") }
                        true
                    },
                )
                assertEquals(before, snapshot(db))
                val active = seed(db, catalog, active = true)
                val withActive = snapshot(db)
                assertEquals(SessionReviewRoadWriteResult.SessionActive, repo.save(source, road, null) { true })
                assertEquals(SessionReviewRoadWriteResult.SessionActive, repo.save(active, road, null) { true })
                assertEquals(withActive, snapshot(db))
            }
        }

    @Test fun `malformed header node types and missing nodes are read errors never absence`() =
        runBlocking {
            val mutations =
                listOf(
                    "UPDATE session_review_road SET origin = 'UNKNOWN'",
                    "UPDATE session_review_road SET roadTitle = CAST(roadTitle AS BLOB)",
                    "UPDATE session_review_road SET memoText = CAST('fixture' AS BLOB)",
                    "UPDATE session_review_road SET memoText = '   '",
                    "UPDATE session_review_road_node SET osmNodeVersion = 4294967297 WHERE ordinal = 0",
                    "UPDATE session_review_road_node SET latitude = 'not-a-coordinate' WHERE ordinal = 0",
                    "DELETE FROM session_review_road_node WHERE ordinal = 0",
                )
            for (mutation in mutations) {
                withDatabase { db ->
                    val catalog = MutableCatalog()
                    val source = seed(db, catalog)
                    val repo = RoomSessionReviewRoadRepository(db, catalog)
                    saved(
                        repo.save(
                            source,
                            catalog.courses
                                .first()
                                .roadSnapshots
                                .first(),
                            null,
                        ) { true },
                    )
                    db.openHelper.writableDatabase.execSQL(mutation)
                    expectInvalid { repo.observe(source.sessionId).first() }
                    expectInvalid {
                        repo.save(
                            source,
                            catalog.courses
                                .first()
                                .roadSnapshots
                                .first(),
                            null,
                        ) { true }
                    }
                }
            }
        }

    @Test fun `deletion confirmation includes full road presence and cascading nodes without touching another session`() =
        runBlocking {
            withDatabase { db ->
                val catalog = MutableCatalog()
                val source = seed(db, catalog)
                val retained = seed(db, catalog)
                val repo = RoomSessionReviewRoadRepository(db, catalog)
                val road =
                    catalog.courses
                        .first()
                        .roadSnapshots
                        .first()
                val deletions = RoomSessionDeletionRepository(db)
                val absent = (deletions.prepare(source) { true } as SessionDeletionPreparation.Ready).target
                val review = saved(repo.save(source, road, null) { true })
                val retainedReview = saved(repo.save(retained, road, null) { true })
                assertEquals(SessionDeletionResult.Changed, deletions.delete(absent) { true })
                val present = (deletions.prepare(source) { true } as SessionDeletionPreparation.Ready).target
                assertEquals(review, present.reviewRoad)
                assertEquals(SessionReviewRoadWriteResult.Cleared, repo.clear(source, review) { true })
                assertEquals(SessionDeletionResult.Changed, deletions.delete(present) { true })
                val restored = saved(repo.save(source, road, null) { true })
                val beforeMemo = (deletions.prepare(source) { true } as SessionDeletionPreparation.Ready).target
                saved(repo.save(source, road, restored, memoText = "구간에 남긴 메모") { true })
                assertEquals(SessionDeletionResult.Changed, deletions.delete(beforeMemo) { true })
                val current = (deletions.prepare(source) { true } as SessionDeletionPreparation.Ready).target
                val before = snapshot(db)
                var checks = 0
                assertEquals(SessionDeletionResult.Blocked, deletions.delete(current) { ++checks == 1 })
                assertEquals(before, snapshot(db))
                assertEquals(SessionDeletionResult.Deleted, deletions.delete(current) { true })
                assertNull(repo.observe(source.sessionId).first())
                assertEquals(retainedReview, repo.observe(retained.sessionId).first())
                db.openHelper.readableDatabase
                    .query(
                        "SELECT COUNT(*) FROM session_review_road_node WHERE sessionId = ?",
                        arrayOf(source.sessionId),
                    ).use {
                        check(it.moveToFirst())
                        assertEquals(0L, it.getLong(0))
                    }
                db.openHelper.readableDatabase
                    .query("PRAGMA foreign_key_check")
                    .use { assertEquals(0, it.count) }
            }
        }

    private class MutableCatalog : PracticeCatalog {
        private val shipped = LocalPracticeCatalog()
        override val goals = shipped.goals
        override var courses = shipped.courses
    }

    private suspend fun seed(
        db: PreparationDatabase,
        catalog: PracticeCatalog,
        active: Boolean = false,
    ): PracticeSession {
        val goal = catalog.goals.first()
        val course = catalog.courses.first()
        val task = course.tasks.first()
        val entity =
            PracticeSessionEntity(
                sessionId = UUID.randomUUID().toString(),
                goalId = goal.id,
                courseId = course.id,
                courseVersion = course.version,
                briefingVersion = course.briefingVersion,
                taskId = task.id,
                goalTitle = goal.title,
                courseTitle = course.title,
                taskTitle = task.title,
                courseReviewStatus = course.reviewStatus.name,
                startedAtEpochMillis = 1_000,
                endedAtEpochMillis = if (active) null else 2_000,
                activeSlot = if (active) 1 else null,
            )
        db.practiceSessionDao().insert(entity)
        return entity.session()
    }

    private fun saved(result: SessionReviewRoadWriteResult) = (result as SessionReviewRoadWriteResult.Saved).review

    private suspend fun withDatabase(block: suspend (PreparationDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PreparationDatabase::class.java).build()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private fun snapshot(
        db: PreparationDatabase,
        includeReview: Boolean = true,
    ): Map<String, List<List<Pair<Int, Any?>>>> {
        val tables =
            listOf(
                "preparation",
                "practice_session",
                "vehicle_observation",
                "session_memo",
                "vehicle_recording_run",
                "course_completion_declaration",
                "location_recording_run",
                "location_recording_event",
                "sqlite_sequence",
            ) +
                if (includeReview) listOf("session_review_road", "session_review_road_node") else emptyList()
        return tables.associateWith { table ->
            db.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            (0 until cursor.columnCount).map { index ->
                                val value: Any? =
                                    when (cursor.getType(index)) {
                                        Cursor.FIELD_TYPE_NULL -> null
                                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                                        Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).toList()
                                        else -> error("Invalid fixture storage")
                                    }
                                cursor.getType(index) to value
                            },
                        )
                    }
                }
            }
        }
    }

    private suspend fun expectInvalid(action: suspend () -> Any?) {
        try {
            action()
            fail("Invalid stored review road must fail closed")
        } catch (
            _: IllegalStateException,
        ) {
        } catch (_: IllegalArgumentException) {
        } catch (_: SQLiteException) {
        }
    }
}
