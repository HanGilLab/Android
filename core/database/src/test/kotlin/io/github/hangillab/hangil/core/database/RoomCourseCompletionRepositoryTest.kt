package io.github.hangillab.hangil.core.database

import android.database.Cursor
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.withTransaction
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclaration
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclarationResult
import io.github.hangillab.hangil.core.domain.CourseCompletionOrigin
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionDeletionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomCourseCompletionRepositoryTest {
    @Test fun `saving preserves the full historical source and duplicate calls preserve the first declaration`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                seed(db, "newer-fixture", started = 500, ended = 900)
                val repository = RoomCourseCompletionRepository(db)
                val before = originals(db)
                assertEquals(null, repository.observe(source.sessionId).first())
                val first = saved(repository.declare(source, 100) { true })
                assertEquals(source, first.source)
                assertEquals(100L, first.declaredAtEpochMillis)
                assertEquals(CourseCompletionOrigin.USER_DECLARATION, first.origin)
                assertTrue(first.version.isNotBlank())
                assertEquals(CourseCompletionDeclarationResult.AlreadyDeclared(first), repository.declare(source, 9_000) { true })
                assertEquals(first, repository.observe(source.sessionId).first())
                assertEquals(before, originals(db))
                assertEquals(1L, count(db))
            }
        }

    @Test fun `every source snapshot field is compared before declaring`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val selection = source.selection
                val changed =
                    listOf(
                        source.copy(selection = selection.copy(goalId = "other")),
                        source.copy(selection = selection.copy(courseId = "other")),
                        source.copy(selection = selection.copy(taskId = "other")),
                        source.copy(selection = selection.copy(courseVersion = 2)),
                        source.copy(selection = selection.copy(briefingVersion = 2)),
                        source.copy(goalTitle = "other"),
                        source.copy(courseTitle = "other"),
                        source.copy(taskTitle = "other"),
                        source.copy(startedAtEpochMillis = 999),
                        source.copy(endedAtEpochMillis = 2_001),
                    )
                val repository = RoomCourseCompletionRepository(db)
                for (candidate in changed) {
                    assertEquals(CourseCompletionDeclarationResult.SourceChanged, repository.declare(candidate, 3_000) { true })
                }
                assertEquals(0L, count(db))
                assertEquals(source, db.practiceSessionDao().find(source.sessionId)?.session())
            }
        }

    @Test fun `missing source active source and another active session are distinct from invalid time`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val active = seed(db, "active-fixture", ended = null)
                val repository = RoomCourseCompletionRepository(db)
                assertEquals(
                    CourseCompletionDeclarationResult.MissingSession,
                    repository.declare(
                        source.copy(sessionId = "missing-fixture"),
                        3_000,
                    ) {
                        true
                    },
                )
                assertEquals(CourseCompletionDeclarationResult.ActiveSession, repository.declare(active, 3_000) { true })
                assertEquals(CourseCompletionDeclarationResult.ActiveSession, repository.declare(source, 3_000) { true })
                db.practiceSessionDao().finish(active.sessionId, 3_000)
                for (time in listOf(0L, -1L, Long.MIN_VALUE)) {
                    assertEquals(CourseCompletionDeclarationResult.InvalidTime, repository.declare(source, time) { true })
                }
                assertEquals(0L, count(db))
            }
        }

    @Test fun `permission denial and cancellation after insert roll back the declaration`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repository = RoomCourseCompletionRepository(db)
                val before = originals(db)
                assertEquals(CourseCompletionDeclarationResult.Blocked, repository.declare(source, 3_000) { false })
                var checks = 0
                assertEquals(
                    CourseCompletionDeclarationResult.Blocked,
                    repository.declare(source, 3_000) { ++checks == 1 },
                )
                assertEquals(2, checks)
                val worker =
                    launch(Dispatchers.Default) {
                        val owner = currentCoroutineContext()[Job]
                        var permits = 0
                        repository.declare(source, 3_000) {
                            if (++permits == 2) owner?.cancel()
                            true
                        }
                    }
                worker.join()
                assertTrue(worker.isCancelled)
                assertEquals(0L, count(db))
                assertEquals(before, originals(db))
            }
        }

    @Test fun `a throwing permission gate propagates and rolls back instead of reporting success`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                var checks = 0
                val failure =
                    runCatching {
                        RoomCourseCompletionRepository(db).declare(source, 3_000) {
                            if (++checks == 2) throw IOException("Controlled permission failure")
                            true
                        }
                    }.exceptionOrNull()
                assertTrue(failure is IOException)
                assertEquals(0L, count(db))
            }
        }

    @Test fun `cancelling an enclosing transaction rolls back an acknowledged declaration`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val acknowledged = CompletableDeferred<Unit>()
                val worker =
                    launch(Dispatchers.Default) {
                        db.withTransaction {
                            saved(RoomCourseCompletionRepository(db).declare(source, 3_000) { true })
                            acknowledged.complete(Unit)
                            awaitCancellation()
                        }
                    }
                withTimeout(10_000) { acknowledged.await() }
                worker.cancelAndJoin()
                assertEquals(null, RoomCourseCompletionRepository(db).observe(source.sessionId).first())
            }
        }

    @Test fun `lost result delivery after commit is resolved by rereading the original immutable row`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val committed = CompletableDeferred<CourseCompletionDeclaration>()
                val worker =
                    launch(Dispatchers.Default) {
                        val result = saved(RoomCourseCompletionRepository(db).declare(source, 3_000) { true })
                        committed.complete(result)
                        awaitCancellation()
                    }
                val first = withTimeout(10_000) { committed.await() }
                worker.cancelAndJoin()
                val repository = RoomCourseCompletionRepository(db)
                assertEquals(first, repository.observe(source.sessionId).first())
                assertEquals(CourseCompletionDeclarationResult.AlreadyDeclared(first), repository.declare(source, 4_000) { true })
                assertEquals(1L, count(db))
            }
        }

    @Test fun `concurrent declarations have one saved result and one unchanged idempotent result`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val start = CompletableDeferred<Unit>()
                val workers =
                    (1..2).map { index ->
                        async(Dispatchers.Default) {
                            start.await()
                            RoomCourseCompletionRepository(db).declare(source, 3_000L + index) { true }
                        }
                    }
                start.complete(Unit)
                val results = workers.map { it.await() }
                val first =
                    (results.single { it is CourseCompletionDeclarationResult.Saved } as CourseCompletionDeclarationResult.Saved)
                        .declaration
                assertEquals(
                    CourseCompletionDeclarationResult.AlreadyDeclared(first),
                    results.single {
                        it is CourseCompletionDeclarationResult.AlreadyDeclared
                    },
                )
                assertEquals(1L, count(db))
            }
        }

    @Test fun `duplicate declaration still checks current source active session and both permission gates`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repository = RoomCourseCompletionRepository(db)
                val first = saved(repository.declare(source, 3_000) { true })
                var checks = 0
                assertEquals(CourseCompletionDeclarationResult.Blocked, repository.declare(source, 4_000) { ++checks == 1 })
                val active = seed(db, "active-fixture", ended = null)
                assertEquals(CourseCompletionDeclarationResult.ActiveSession, repository.declare(source, 4_000) { true })
                db.practiceSessionDao().finish(active.sessionId, 3_000)
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE practice_session SET taskTitle = ? WHERE sessionId = ?",
                    arrayOf("changed", source.sessionId),
                )
                val actual = checkNotNull(db.practiceSessionDao().find(source.sessionId)).session()
                assertEquals(CourseCompletionDeclarationResult.SourceChanged, repository.declare(source, 4_000) { true })
                assertEquals(CourseCompletionDeclarationResult.SourceChanged, repository.declare(actual, 4_000) { true })
                assertEquals(first, repository.observe(source.sessionId).first())
            }
        }

    @Test fun `null to declaration change invalidates an earlier deletion confirmation and cascade is exact`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val retained = seed(db, "retained-fixture")
                val deletions = RoomSessionDeletionRepository(db)
                val before = (deletions.prepare(source) { true } as SessionDeletionPreparation.Ready).target
                assertEquals(null, before.completionDeclaration)
                val repository = RoomCourseCompletionRepository(db)
                val first = saved(repository.declare(source, 3_000) { true })
                val other = saved(repository.declare(retained, 3_001) { true })
                assertEquals(SessionDeletionResult.Changed, deletions.delete(before) { true })
                val target = (deletions.prepare(source) { true } as SessionDeletionPreparation.Ready).target
                assertEquals(first, target.completionDeclaration)
                assertEquals(
                    SessionDeletionResult.Changed,
                    deletions.delete(
                        target.copy(completionDeclaration = first.copy(version = "different")),
                    ) {
                        true
                    },
                )
                assertEquals(
                    SessionDeletionResult.Changed,
                    deletions.delete(
                        target.copy(completionDeclaration = first.copy(declaredAtEpochMillis = 1)),
                    ) {
                        true
                    },
                )
                assertEquals(SessionDeletionResult.Deleted, deletions.delete(target) { true })
                assertEquals(null, repository.observe(source.sessionId).first())
                assertEquals(other, repository.observe(retained.sessionId).first())
                assertEquals(CourseCompletionDeclarationResult.MissingSession, repository.declare(source, 4_000) { true })
                assertEquals(1L, count(db))
                db.openHelper.writableDatabase
                    .query("PRAGMA foreign_key_check")
                    .use { assertEquals(0, it.count) }
            }
        }

    @Test fun `permission loss after cascade restores the exact stored declaration`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repository = RoomCourseCompletionRepository(db)
                val declaration = saved(repository.declare(source, 3_000) { true })
                val deletions = RoomSessionDeletionRepository(db)
                val target = (deletions.prepare(source) { true } as SessionDeletionPreparation.Ready).target
                val before = originals(db)
                var checks = 0
                assertEquals(
                    SessionDeletionResult.Blocked,
                    deletions.delete(target) {
                        if (++checks == 2) {
                            assertEquals(0L, count(db))
                            false
                        } else {
                            true
                        }
                    },
                )
                assertEquals(declaration, repository.observe(source.sessionId).first())
                assertEquals(before, originals(db))
            }
        }

    @Test fun `stored declaration physical types source values and unknown origin fail decoding`() =
        runBlocking {
            val corruptions =
                listOf(
                    "declaredAtEpochMillis = 3000.25",
                    "startedAtEpochMillis = 1000.25",
                    "endedAtEpochMillis = 2000.25",
                    "courseVersion = 1.25",
                    "briefingVersion = 4294967297",
                    "goalTitle = CAST('fixture' AS BLOB)",
                    "version = CAST('fixture' AS BLOB)",
                    "origin = CAST('USER_DECLARATION' AS BLOB)",
                    "origin = 'UNKNOWN'",
                    "courseReviewStatus = 'UNKNOWN'",
                    "version = ''",
                    "taskId = ''",
                    "declaredAtEpochMillis = 0",
                    "endedAtEpochMillis = 1",
                )
            for (mutation in corruptions) {
                withDatabase { db ->
                    val source = seed(db)
                    val repository = RoomCourseCompletionRepository(db)
                    saved(repository.declare(source, 3_000) { true })
                    val target = (RoomSessionDeletionRepository(db).prepare(source) { true } as SessionDeletionPreparation.Ready).target
                    db.openHelper.writableDatabase.execSQL("UPDATE course_completion_declaration SET $mutation")
                    expectInvalid { repository.observe(source.sessionId).first() }
                    expectInvalid { repository.declare(source, 4_000) { true } }
                    expectInvalid { RoomSessionDeletionRepository(db).delete(target) { true } }
                    assertEquals(source, db.practiceSessionDao().find(source.sessionId)?.session())
                    assertEquals(1L, count(db))
                }
            }
        }

    @Test fun `source physical corruption cannot become an ordinary matching declaration source`() =
        runBlocking {
            for (mutation in listOf(
                "courseVersion = 4294967297",
                "goalTitle = CAST('Fixture goal' AS BLOB)",
                "endedAtEpochMillis = 2000.25",
            )) {
                withDatabase { db ->
                    val source = seed(db)
                    db.openHelper.writableDatabase.execSQL("UPDATE practice_session SET $mutation")
                    expectInvalid { RoomCourseCompletionRepository(db).declare(source, 3_000) { true } }
                    assertEquals(0L, count(db))
                }
            }
        }

    @Test fun `primary and foreign keys reject duplicate or orphan declarations`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val declaration = saved(RoomCourseCompletionRepository(db).declare(source, 3_000) { true })
                val entity = CourseCompletionDeclarationEntity.from(declaration)
                expectInvalid { db.courseCompletionDeclarationDao().insert(entity) }
                expectInvalid { db.courseCompletionDeclarationDao().insert(entity.copy(sessionId = "missing-fixture")) }
                assertEquals(1L, count(db))
            }
        }

    @Test fun `saved declaration survives closing and reopening the owned database`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val name = "completion-fixture-${UUID.randomUUID()}.db"

            fun open() = Room.databaseBuilder(context, PreparationDatabase::class.java, name).build()
            try {
                val first = open()
                val source: PracticeSession
                val declaration: CourseCompletionDeclaration
                try {
                    source = seed(first)
                    declaration = saved(RoomCourseCompletionRepository(first).declare(source, 3_000) { true })
                } finally {
                    first.close()
                }
                val reopened = open()
                try {
                    val repository = RoomCourseCompletionRepository(reopened)
                    assertEquals(declaration, repository.observe(source.sessionId).first())
                    assertEquals(CourseCompletionDeclarationResult.AlreadyDeclared(declaration), repository.declare(source, 4_000) { true })
                } finally {
                    reopened.close()
                }
            } finally {
                context.deleteDatabase(name)
            }
        }

    private suspend fun seed(
        db: PreparationDatabase,
        id: String = "source-fixture",
        started: Long = 1_000,
        ended: Long? = 2_000,
    ): PracticeSession {
        db.practiceSessionDao().insert(
            PracticeSessionEntity(
                sessionId = id,
                goalId = "commute",
                courseId = "neighborhood",
                courseVersion = 1,
                briefingVersion = 1,
                taskId = "sequence",
                goalTitle = "Fixture goal",
                courseTitle = "Fixture course",
                taskTitle = "Fixture task",
                courseReviewStatus = "DEMO",
                startedAtEpochMillis = started,
                endedAtEpochMillis = ended,
                activeSlot = if (ended == null) 1 else null,
            ),
        )
        return checkNotNull(db.practiceSessionDao().find(id)).session()
    }

    private fun saved(result: CourseCompletionDeclarationResult) = (result as CourseCompletionDeclarationResult.Saved).declaration

    private suspend fun withDatabase(block: suspend (PreparationDatabase) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PreparationDatabase::class.java).build()
        try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun count(db: PreparationDatabase): Long =
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM course_completion_declaration").use {
            check(it.moveToFirst())
            it.getLong(0)
        }

    private fun originals(db: PreparationDatabase): Map<String, List<List<Pair<Int, Any?>>>> =
        listOf(
            "preparation",
            "practice_session",
            "vehicle_observation",
            "session_memo",
            "vehicle_recording_run",
            "sqlite_sequence",
        ).associateWith { table ->
            db.openHelper.writableDatabase.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
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
                                        else -> error("Invalid fixture type")
                                    }
                                cursor.getType(index) to value
                            },
                        )
                    }
                }
            }
        }

    private suspend fun expectInvalid(action: suspend () -> Any?) {
        try {
            action()
            fail("Malformed completion data or constraints must fail")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            // Invalid persisted snapshot or unknown enum, never absence.
        } catch (_: IllegalStateException) {
            // Original SQLite storage types must not be coerced into valid declarations.
        } catch (_: SQLiteException) {
            // SQLite can reject a constraint before a row is decoded.
        }
    }
}
