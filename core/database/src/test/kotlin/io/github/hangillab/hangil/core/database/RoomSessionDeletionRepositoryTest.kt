package io.github.hangillab.hangil.core.database

import android.database.Cursor
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionDeletionResult
import io.github.hangillab.hangil.core.domain.SessionDeletionTarget
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
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
class RoomSessionDeletionRepositoryTest {
    @Test fun `prepare reads the exact confirmation snapshot without changing any stored value`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database, memo = "fixture user note")
                val before = snapshot(database)
                var checks = 0
                val result = RoomSessionDeletionRepository(database).prepare(source) { ++checks > 0 }
                val target = ready(result)
                assertEquals(source, target.session)
                assertEquals(2L, target.observationCount)
                assertEquals(
                    database
                        .sessionMemoDao()
                        .find(source.sessionId)
                        ?.memo()
                        ?.version,
                    target.memoVersion,
                )
                assertEquals(2, checks)
                assertEquals(database.vehicleRecordingRunDao().forSession(source.sessionId).map { it.run() }, target.recordingRuns)
                assertEquals(before, snapshot(database))
            }
        }

    @Test fun `a valid empty observation collection and absent memo remain explicit confirmation values`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database, observationCount = 0, recordingRun = false)
                val target = ready(RoomSessionDeletionRepository(database).prepare(source) { true })
                assertEquals(0L, target.observationCount)
                assertEquals(null, target.memoVersion)
                assertTrue(target.recordingRuns.isEmpty())
                assertEquals(SessionDeletionResult.Deleted, RoomSessionDeletionRepository(database).delete(target) { true })
                assertEquals(null, database.practiceSessionDao().find(source.sessionId))
            }
        }

    @Test fun `missing active and changed targets have distinct results and do not delete anything`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database)
                val active = seed(database, id = "fixture-active", ended = null)
                val repository = RoomSessionDeletionRepository(database)
                val missing = source.copy(sessionId = "fixture-missing")
                val changed = source.copy(taskTitle = "different received title")
                val before = snapshot(database)
                assertEquals(SessionDeletionPreparation.Missing, repository.prepare(missing) { true })
                assertEquals(SessionDeletionPreparation.Active, repository.prepare(active) { true })
                assertEquals(SessionDeletionPreparation.Changed, repository.prepare(changed) { true })
                assertEquals(SessionDeletionResult.Missing, repository.delete(SessionDeletionTarget(missing, 0, null)) { true })
                assertEquals(SessionDeletionResult.Active, repository.delete(SessionDeletionTarget(active, 2, null)) { true })
                assertEquals(SessionDeletionResult.Changed, repository.delete(SessionDeletionTarget(changed, 2, null)) { true })
                assertEquals(before, snapshot(database))
            }
        }

    @Test fun `every stored session snapshot field must match rather than merely its identity`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database)
                val selection = source.selection
                val alternatives =
                    listOf(
                        source.copy(selection = selection.copy(goalId = "other-goal")),
                        source.copy(selection = selection.copy(courseId = "other-course")),
                        source.copy(selection = selection.copy(courseVersion = 2)),
                        source.copy(selection = selection.copy(briefingVersion = 2)),
                        source.copy(selection = selection.copy(taskId = "other-task")),
                        source.copy(goalTitle = "other goal title"),
                        source.copy(courseTitle = "other course title"),
                        source.copy(taskTitle = "other task title"),
                        source.copy(startedAtEpochMillis = source.startedAtEpochMillis + 1),
                        source.copy(endedAtEpochMillis = checkNotNull(source.endedAtEpochMillis) + 1),
                    )
                val repository = RoomSessionDeletionRepository(database)
                val before = snapshot(database)
                for (candidate in alternatives) {
                    assertEquals(SessionDeletionPreparation.Changed, repository.prepare(candidate) { true })
                    assertEquals(SessionDeletionResult.Changed, repository.delete(SessionDeletionTarget(candidate, 2, null)) { true })
                }
                assertEquals(before, snapshot(database))
            }
        }

    @Test fun `an older finished target cascades only its children and preserves newer active preparation and sequences`() =
        runBlocking {
            withDatabase { database ->
                database.preparationDao().upsert(PreparationEntity(1, "commute", "neighborhood", 1, 1, "sequence"))
                val source = seed(database, memo = "target fixture memo")
                seed(database, id = "fixture-newer", started = 100, ended = 200, memo = "other fixture memo")
                seed(database, id = "fixture-active", started = 300, ended = null)
                val repository = RoomSessionDeletionRepository(database)
                val target = ready(repository.prepare(source) { true })
                val unrelated = snapshot(database, excluding = source.sessionId)
                val schema = schema(database.openHelper.writableDatabase)
                assertEquals(SessionDeletionResult.Deleted, repository.delete(target) { true })
                assertEquals(null, database.practiceSessionDao().find(source.sessionId))
                assertEquals(0L, database.vehicleObservationDao().count(source.sessionId))
                assertEquals(null, database.sessionMemoDao().find(source.sessionId))
                assertTrue(database.vehicleRecordingRunDao().forSession(source.sessionId).isEmpty())
                assertEquals(unrelated, snapshot(database, excluding = source.sessionId))
                assertEquals(schema, schema(database.openHelper.writableDatabase))
                assertEquals(5, database.openHelper.writableDatabase.version)
                database.openHelper.writableDatabase
                    .query("PRAGMA foreign_key_check")
                    .use { assertEquals(0, it.count) }
                assertEquals(SessionDeletionResult.Missing, repository.delete(target) { true })
            }
        }

    @Test fun `read permission is checked before and after confirmation and never returns a blocked target`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database, memo = "fixture memo")
                val repository = RoomSessionDeletionRepository(database)
                val before = snapshot(database)
                assertEquals(SessionDeletionPreparation.Blocked, repository.prepare(source) { false })
                var checks = 0
                assertEquals(SessionDeletionPreparation.Blocked, repository.prepare(source) { ++checks == 1 })
                assertEquals(2, checks)
                assertEquals(before, snapshot(database))
            }
        }

    @Test fun `blocked delete cannot remove an otherwise matching target`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database, memo = "fixture memo")
                val repository = RoomSessionDeletionRepository(database)
                val target = ready(repository.prepare(source) { true })
                val before = snapshot(database)
                var checks = 0
                assertEquals(
                    SessionDeletionResult.Blocked,
                    repository.delete(target) {
                        checks++
                        false
                    },
                )
                assertEquals(1, checks)
                assertEquals(before, snapshot(database))
            }
        }

    @Test fun `permission loss after actual cascade restores every physical row and sequence`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database, memo = "fixture memo")
                val repository = RoomSessionDeletionRepository(database)
                val target = ready(repository.prepare(source) { true })
                val before = snapshot(database)
                var checks = 0
                val result =
                    repository.delete(target) {
                        if (++checks == 1) {
                            true
                        } else {
                            assertEquals(0L, count(database.openHelper.writableDatabase, "practice_session"))
                            assertEquals(0L, count(database.openHelper.writableDatabase, "vehicle_observation"))
                            assertEquals(0L, count(database.openHelper.writableDatabase, "session_memo"))
                            false
                        }
                    }
                assertEquals(2, checks)
                assertEquals(SessionDeletionResult.Blocked, result)
                assertEquals(before, snapshot(database))
            }
        }

    @Test fun `guard exceptions propagate with their original cause and roll back an actual cascade`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database, memo = "fixture memo")
                val repository = RoomSessionDeletionRepository(database)
                val target = ready(repository.prepare(source) { true })
                val before = snapshot(database)
                val failure = IOException("fixture guard failure")
                var checks = 0
                try {
                    repository.delete(target) {
                        if (++checks == 2) throw failure
                        true
                    }
                    fail("A guard failure must not become a deletion result")
                } catch (actual: IOException) {
                    assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
                }
                assertEquals(before, snapshot(database))
            }
        }

    @Test fun `cancellation thrown by the post-delete guard propagates and restores the target and children`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database, memo = "fixture memo")
                val repository = RoomSessionDeletionRepository(database)
                val target = ready(repository.prepare(source) { true })
                val before = snapshot(database)
                val cancellation = CancellationException("fixture deletion cancellation")
                var checks = 0
                try {
                    repository.delete(target) {
                        if (++checks == 2) throw cancellation
                        true
                    }
                    fail("Cancellation must not become a normal deletion result")
                } catch (actual: CancellationException) {
                    assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === cancellation })
                }
                assertEquals(before, snapshot(database))
            }
        }

    @Test fun `a guard that cancels its coroutine while returning true still cannot commit`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database, memo = "fixture memo")
                val repository = RoomSessionDeletionRepository(database)
                val target = ready(repository.prepare(source) { true })
                val before = snapshot(database)
                val worker =
                    async(Dispatchers.Default) {
                        val ownJob = checkNotNull(currentCoroutineContext()[Job])
                        var checks = 0
                        repository.delete(target) {
                            if (++checks == 2) ownJob.cancel(CancellationException("fixture cancelled guard"))
                            true
                        }
                    }
                try {
                    worker.await()
                    fail("A cancelled worker must not return a committed deletion")
                } catch (_: CancellationException) {
                    assertTrue(worker.isCancelled)
                }
                assertEquals(before, snapshot(database))
            }
        }

    @Test fun `an enclosing transaction cancellation restores a deletion already acknowledged inside it`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database, memo = "fixture memo")
                val repository = RoomSessionDeletionRepository(database)
                val target = ready(repository.prepare(source) { true })
                val before = snapshot(database)
                val deleted = CompletableDeferred<Unit>()
                val worker =
                    launch(Dispatchers.Default) {
                        database.withTransaction {
                            assertEquals(SessionDeletionResult.Deleted, repository.delete(target) { true })
                            deleted.complete(Unit)
                            awaitCancellation()
                        }
                    }
                withTimeout(10_000) { deleted.await() }
                worker.cancelAndJoin()
                assertEquals(before, snapshot(database))
            }
        }

    @Test fun `session and child SQL failures propagate and roll back all deleted rows`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database, memo = "fixture memo")
                val repository = RoomSessionDeletionRepository(database)
                val target = ready(repository.prepare(source) { true })
                val before = snapshot(database)
                val sqlite = database.openHelper.writableDatabase
                for (table in listOf("practice_session", "vehicle_observation", "session_memo", "vehicle_recording_run")) {
                    sqlite.execSQL(
                        "CREATE TRIGGER reject_deletion_fixture AFTER DELETE ON $table " +
                            "BEGIN SELECT RAISE(ABORT, 'fixture delete failure'); END",
                    )
                    expectSqlFailure { repository.delete(target) { true } }
                    assertEquals(before, snapshot(database))
                    sqlite.execSQL("DROP TRIGGER reject_deletion_fixture")
                }
            }
        }

    @Test fun `a delete that affects no row is not reported as a successful deletion`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database)
                val repository = RoomSessionDeletionRepository(database)
                val target = ready(repository.prepare(source) { true })
                val before = snapshot(database)
                database.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER ignore_deletion_fixture BEFORE DELETE ON practice_session " +
                        "BEGIN SELECT RAISE(IGNORE); END",
                )
                try {
                    repository.delete(target) { true }
                    fail("An ignored DELETE must not return Deleted")
                } catch (_: IllegalStateException) {
                    assertEquals(before, snapshot(database))
                }
            }
        }

    @Test fun `a memo edited after confirmation even to the same text invalidates its old target`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database, memo = "same fixture text")
                val repository = RoomSessionDeletionRepository(database)
                val memos = RoomSessionMemoRepository(database)
                val target = ready(repository.prepare(source) { true })
                val updated = memos.save(source.sessionId, "same fixture text", target.memoVersion) { true }
                assertTrue(updated is SessionMemoWriteResult.Saved)
                assertNotEquals(target.memoVersion, (updated as SessionMemoWriteResult.Saved).memo.version)
                val before = snapshot(database)
                assertEquals(SessionDeletionResult.Changed, repository.delete(target) { true })
                assertEquals(before, snapshot(database))
            }
        }

    @Test fun `an absent memo is a compared state and cannot authorize deletion of a newly written note`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database)
                val repository = RoomSessionDeletionRepository(database)
                val target = ready(repository.prepare(source) { true })
                assertEquals(null, target.memoVersion)
                assertTrue(
                    RoomSessionMemoRepository(database).save(source.sessionId, "new fixture note", null) { true }
                        is SessionMemoWriteResult.Saved,
                )
                val before = snapshot(database)
                assertEquals(SessionDeletionResult.Changed, repository.delete(target) { true })
                assertEquals(before, snapshot(database))
            }
        }

    @Test fun `deleted and recreated memos cannot reuse a prior deletion confirmation`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database, memo = "fixture memo")
                val repository = RoomSessionDeletionRepository(database)
                val memos = RoomSessionMemoRepository(database)
                val target = ready(repository.prepare(source) { true })
                assertEquals(SessionMemoWriteResult.Deleted, memos.delete(source.sessionId, checkNotNull(target.memoVersion)) { true })
                assertEquals(SessionDeletionResult.Changed, repository.delete(target) { true })
                assertTrue(memos.save(source.sessionId, "fixture memo", null) { true } is SessionMemoWriteResult.Saved)
                val before = snapshot(database)
                assertEquals(SessionDeletionResult.Changed, repository.delete(target) { true })
                assertEquals(before, snapshot(database))
            }
        }

    @Test fun `an actual changed observation count or forged count or memo token never authorizes deletion`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database)
                val repository = RoomSessionDeletionRepository(database)
                val target = ready(repository.prepare(source) { true })
                assertEquals(SessionDeletionResult.Changed, repository.delete(target.copy(observationCount = -1)) { true })
                assertEquals(SessionDeletionResult.Changed, repository.delete(target.copy(memoVersion = "forged")) { true })
                // Simulate a separate storage writer, not a supported append to a finished session.
                database.vehicleObservationDao().insert(VehicleObservationEntity.from(source.sessionId, fixtureObservation(1_500)))
                val before = snapshot(database)
                assertEquals(SessionDeletionResult.Changed, repository.delete(target) { true })
                assertEquals(before, snapshot(database))
                assertEquals(3L, ready(repository.prepare(source) { true }).observationCount)
            }
        }

    @Test fun `actual stored snapshot changes are checked without waiting for an observation flow emission`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database)
                val repository = RoomSessionDeletionRepository(database)
                val target = ready(repository.prepare(source) { true })
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE practice_session SET taskTitle = ? WHERE sessionId = ?",
                    arrayOf("changed fixture task", source.sessionId),
                )
                val before = snapshot(database)
                assertEquals(SessionDeletionPreparation.Changed, repository.prepare(source) { true })
                assertEquals(SessionDeletionResult.Changed, repository.delete(target) { true })
                assertEquals(before, snapshot(database))
            }
        }

    @Test fun `invalid stored memo text token and blob fail confirmation and deletion instead of becoming absent`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database, memo = "fixture memo")
                val repository = RoomSessionDeletionRepository(database)
                val target = ready(repository.prepare(source) { true })
                val sqlite = database.openHelper.writableDatabase
                val version = checkNotNull(target.memoVersion)
                val updates =
                    listOf(
                        "UPDATE session_memo SET text = ''",
                        "UPDATE session_memo SET text = 'fixture', version = 'invalid'",
                        "UPDATE session_memo SET text = CAST('fixture' AS BLOB)",
                    )
                for (statement in updates) {
                    sqlite.execSQL("UPDATE session_memo SET text = ?, version = ?", arrayOf("fixture memo", version))
                    sqlite.execSQL(statement)
                    val before = snapshot(database)
                    expectInvalidStoredData { repository.prepare(source) { true } }
                    expectInvalidStoredData { repository.delete(target) { true } }
                    assertEquals(before, snapshot(database))
                }
            }
        }

    @Test fun `invalid stored session time or active slot fails rather than authorizing deletion`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database)
                val repository = RoomSessionDeletionRepository(database)
                val target = ready(repository.prepare(source) { true })
                val sqlite = database.openHelper.writableDatabase
                sqlite.execSQL("UPDATE practice_session SET endedAtEpochMillis = 1")
                expectInvalidStoredData { repository.prepare(source) { true } }
                expectInvalidStoredData { repository.delete(target) { true } }
                sqlite.execSQL("UPDATE practice_session SET endedAtEpochMillis = 2000, activeSlot = 1")
                val before = snapshot(database)
                expectInvalidStoredData { repository.prepare(source) { true } }
                expectInvalidStoredData { repository.delete(target) { true } }
                assertEquals(before, snapshot(database))
            }
        }

    @Test fun `fractional real button times never match an integer confirmation after Room long conversion`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database, memo = "fixture memo")
                val repository = RoomSessionDeletionRepository(database)
                val target = ready(repository.prepare(source) { true })
                val sqlite = database.openHelper.writableDatabase
                val values = mapOf("startedAtEpochMillis" to source.startedAtEpochMillis, "endedAtEpochMillis" to source.endedAtEpochMillis)
                for ((column, original) in values) {
                    val integer = checkNotNull(original)
                    sqlite.execSQL("UPDATE practice_session SET $column = ?", arrayOf(integer + 0.25))
                    sqlite.query("SELECT typeof($column) FROM practice_session").use {
                        check(it.moveToFirst())
                        assertEquals("real", it.getString(0))
                    }
                    val before = snapshot(database)
                    expectInvalidStoredData { repository.prepare(source) { true } }
                    expectInvalidStoredData { repository.delete(target) { true } }
                    assertEquals(before, snapshot(database))
                    sqlite.execSQL("UPDATE practice_session SET $column = ?", arrayOf(integer))
                }
            }
        }

    @Test fun `fractional real course versions never match an integer confirmation after Room int conversion`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database)
                val repository = RoomSessionDeletionRepository(database)
                val target = ready(repository.prepare(source) { true })
                val sqlite = database.openHelper.writableDatabase
                for (column in listOf("courseVersion", "briefingVersion")) {
                    sqlite.execSQL("UPDATE practice_session SET $column = 1.25")
                    sqlite.query("SELECT typeof($column) FROM practice_session").use {
                        check(it.moveToFirst())
                        assertEquals("real", it.getString(0))
                    }
                    val before = snapshot(database)
                    expectInvalidStoredData { repository.prepare(source) { true } }
                    expectInvalidStoredData { repository.delete(target) { true } }
                    assertEquals(before, snapshot(database))
                    sqlite.execSQL("UPDATE practice_session SET $column = 1")
                }
            }
        }

    @Test fun `integer versions outside Int range cannot wrap back to an otherwise matching target`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database)
                val repository = RoomSessionDeletionRepository(database)
                val target = ready(repository.prepare(source) { true })
                val sqlite = database.openHelper.writableDatabase
                for (column in listOf("courseVersion", "briefingVersion")) {
                    val wrappedVersion = 4_294_967_297L
                    sqlite.execSQL("UPDATE practice_session SET $column = ?", arrayOf(wrappedVersion))
                    sqlite.query("SELECT typeof($column), $column FROM practice_session").use {
                        check(it.moveToFirst())
                        assertEquals("integer", it.getString(0))
                        assertEquals(wrappedVersion, it.getLong(1))
                        assertEquals(1, it.getInt(1))
                    }
                    val before = snapshot(database)
                    expectInvalidStoredData { repository.prepare(source) { true } }
                    expectInvalidStoredData { repository.delete(target) { true } }
                    assertEquals(before, snapshot(database))
                    sqlite.execSQL("UPDATE practice_session SET $column = 1")
                }
            }
        }

    @Test fun `blob storage for any session text field cannot become an ordinary deletion snapshot`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database)
                val repository = RoomSessionDeletionRepository(database)
                val target = ready(repository.prepare(source) { true })
                val sqlite = database.openHelper.writableDatabase
                val values =
                    mapOf(
                        "goalId" to source.selection.goalId,
                        "courseId" to source.selection.courseId,
                        "taskId" to source.selection.taskId,
                        "goalTitle" to source.goalTitle,
                        "courseTitle" to source.courseTitle,
                        "taskTitle" to source.taskTitle,
                        "courseReviewStatus" to source.courseReviewStatus.name,
                    )
                for ((column, original) in values) {
                    sqlite.execSQL("UPDATE practice_session SET $column = CAST(? AS BLOB)", arrayOf(original))
                    sqlite.query("SELECT typeof($column) FROM practice_session").use {
                        check(it.moveToFirst())
                        assertEquals("blob", it.getString(0))
                    }
                    val before = snapshot(database)
                    expectInvalidStoredData { repository.prepare(source) { true } }
                    expectInvalidStoredData { repository.delete(target) { true } }
                    assertEquals(before, snapshot(database))
                    sqlite.execSQL("UPDATE practice_session SET $column = ?", arrayOf(original))
                }
            }
        }

    @Test fun `competing deletes have exactly one winner and the other observes missing rather than success`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database, memo = "fixture memo")
                val repository = RoomSessionDeletionRepository(database)
                val target = ready(repository.prepare(source) { true })
                val start = CompletableDeferred<Unit>()
                val workers =
                    (1..2).map {
                        async(Dispatchers.Default) {
                            start.await()
                            repository.delete(target) { true }
                        }
                    }
                start.complete(Unit)
                val results = workers.map { it.await() }
                assertEquals(1, results.count { it == SessionDeletionResult.Deleted })
                assertEquals(1, results.count { it == SessionDeletionResult.Missing })
                assertEquals(null, database.practiceSessionDao().find(source.sessionId))
                assertEquals(0L, database.vehicleObservationDao().count(source.sessionId))
                assertEquals(null, database.sessionMemoDao().find(source.sessionId))
            }
        }

    @Test fun `committed deletion persists across closing and reopening the owned database`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val name = "session-deletion-fixture-${UUID.randomUUID()}.db"

            fun open() = Room.databaseBuilder(context, PreparationDatabase::class.java, name).build()
            try {
                val retained =
                    open().useDatabase { database ->
                        val source = seed(database, memo = "target fixture")
                        seed(database, id = "fixture-retained", memo = "retained fixture")
                        val repository = RoomSessionDeletionRepository(database)
                        val target = ready(repository.prepare(source) { true })
                        assertEquals(SessionDeletionResult.Deleted, repository.delete(target) { true })
                        snapshot(database)
                    }
                open().useDatabase { database ->
                    assertEquals(retained, snapshot(database))
                    assertEquals(null, database.practiceSessionDao().find("fixture-target"))
                    assertFalse(checkNotNull(database.practiceSessionDao().find("fixture-retained")).session().isActive)
                }
            } finally {
                context.deleteDatabase(name)
            }
        }

    private suspend fun seed(
        database: PreparationDatabase,
        id: String = "fixture-target",
        started: Long = 1_000,
        ended: Long? = 2_000,
        observationCount: Int = 2,
        memo: String? = null,
        recordingRun: Boolean = true,
    ): PracticeSession {
        database.practiceSessionDao().insert(
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
                endedAtEpochMillis = null,
                activeSlot = 1,
            ),
        )
        repeat(observationCount) { index ->
            assertTrue(
                RoomVehicleObservationRepository(database).append(id, fixtureObservation(started + index + 1))
                    is VehicleObservationAppendResult.Appended,
            )
        }
        if (ended != null) {
            val finished = RoomPracticeSessionRepository(database, LocalPracticeCatalog()).finish(id, ended) { true }
            assertTrue(finished is SessionFinishResult.Finished)
        }
        if (memo != null) {
            assertTrue(RoomSessionMemoRepository(database).save(id, memo, null) { true } is SessionMemoWriteResult.Saved)
        }
        if (recordingRun) {
            database.vehicleRecordingRunDao().insert(
                VehicleRecordingRunEntity(
                    "$id-run",
                    id,
                    "process-fixture",
                    started,
                    ended,
                    if (ended == null) "OPEN" else "STOPPED",
                    if (ended == null) id else null,
                ),
            )
        }
        return checkNotNull(database.practiceSessionDao().find(id)).session()
    }

    private fun fixtureObservation(time: Long) =
        VehicleObservation(time, VehicleState(ConnectionState.ERROR, brakePressed = VehicleReading.Error))

    private fun ready(preparation: SessionDeletionPreparation) = (preparation as SessionDeletionPreparation.Ready).target

    private suspend fun <T> withDatabase(block: suspend (PreparationDatabase) -> T): T {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PreparationDatabase::class.java).build()
        return database.useDatabase(block)
    }

    private suspend fun <T> PreparationDatabase.useDatabase(block: suspend (PreparationDatabase) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }

    private fun snapshot(
        database: PreparationDatabase,
        excluding: String? = null,
    ): Map<String, Rows> {
        val sqlite = database.openHelper.writableDatabase
        return listOf(
            "preparation" to "slot",
            "practice_session" to "rowId",
            "vehicle_observation" to "id",
            "session_memo" to "sessionId",
            "vehicle_recording_run" to "runId",
            "sqlite_sequence" to "name",
        ).associate { (table, order) ->
            val excludesSession =
                excluding != null && table in listOf("practice_session", "vehicle_observation", "session_memo", "vehicle_recording_run")
            val where = if (excludesSession) " WHERE sessionId != ?" else ""
            val args: Array<Any?> = if (excludesSession) arrayOf(excluding) else emptyArray()
            table to sqlite.query("SELECT * FROM $table$where ORDER BY $order", args).use(::readRows)
        }
    }

    private fun readRows(cursor: Cursor): Rows =
        Rows(
            cursor.columnNames.toList(),
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
                                    else -> error("Unexpected fixture storage type")
                                }
                            cursor.getType(index) to value
                        },
                    )
                }
            },
        )

    private fun count(
        sqlite: SupportSQLiteDatabase,
        table: String,
    ): Long =
        sqlite.query("SELECT COUNT(*) FROM $table").use {
            check(it.moveToFirst())
            it.getLong(0)
        }

    private fun schema(sqlite: SupportSQLiteDatabase): Rows =
        sqlite.query("SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name").use(::readRows)

    private suspend fun expectSqlFailure(action: suspend () -> Any?) {
        try {
            action()
            fail("SQLite errors must propagate without a successful deletion")
        } catch (_: SQLiteException) {
            // Assert only the actual storage failure class, not arbitrary assertion/cancellation failures.
        }
    }

    private suspend fun expectInvalidStoredData(action: suspend () -> Any?) {
        try {
            action()
            fail("Invalid stored data must not become an absent or deletable target")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SQLiteException) {
            // SQLite may reject malformed physical types before Room builds the checked projection.
        } catch (_: IllegalStateException) {
            // Decodable invalid persisted values fail the existing checked model conversion.
        }
    }

    private data class Rows(
        val columns: List<String>,
        val values: List<List<Pair<Int, Any?>>>,
    )
}
