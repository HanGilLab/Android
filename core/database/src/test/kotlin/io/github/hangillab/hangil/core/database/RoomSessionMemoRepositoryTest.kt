package io.github.hangillab.hangil.core.database

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomSessionMemoRepositoryTest {
    private val sessionId = "memo-fixture-session"

    private fun memoryDatabase() =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PreparationDatabase::class.java).build()

    @Test fun `create update delete and recreate preserve exact user text and reject stale change tokens`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seedSession(database)
                val repository = RoomSessionMemoRepository(database)
                assertEquals(null, repository.observe(sessionId).first())
                val original = saved(repository.save(sessionId, " \n검증 메모 🙂\n ", null) { true })
                assertEquals(" \n검증 메모 🙂\n ", original.text)
                assertEquals(original, repository.observe(sessionId).first())
                assertEquals(original.version, UUID.fromString(original.version).toString())
                assertEquals(SessionMemoWriteResult.Conflict, repository.save(sessionId, "overwrite", null) { true })
                assertEquals(SessionMemoWriteResult.Conflict, repository.save(sessionId, "overwrite", "stale") { true })
                val updated = saved(repository.save(sessionId, "수정한 검증 메모", original.version) { true })
                assertNotEquals(original.version, updated.version)
                assertEquals(updated, repository.observe(sessionId).first())
                assertEquals(SessionMemoWriteResult.Conflict, repository.delete(sessionId, original.version) { true })
                assertEquals(SessionMemoWriteResult.Deleted, repository.delete(sessionId, updated.version) { true })
                assertEquals(null, repository.observe(sessionId).first())
                assertEquals(SessionMemoWriteResult.Conflict, repository.delete(sessionId, updated.version) { true })
                val recreated = saved(repository.save(sessionId, "다시 쓴 검증 메모", null) { true })
                assertNotEquals(updated.version, recreated.version)
                assertEquals(SessionMemoWriteResult.Conflict, repository.save(sessionId, "stale overwrite", updated.version) { true })
                assertEquals(recreated, repository.observe(sessionId).first())
            }
        }

    @Test fun `same text saves still issue a fresh token and never use a clock as the version`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seedSession(database)
                val repository = RoomSessionMemoRepository(database)
                val original = saved(repository.save(sessionId, "unchanged fixture", null) { true })
                val next = saved(repository.save(sessionId, original.text, original.version) { true })
                assertEquals(original.text, next.text)
                assertNotEquals(original.version, next.version)
                assertEquals(next.version, UUID.fromString(next.version).toString())
            }
        }

    @Test fun `missing active and superseded finished sessions have distinct results based on creation order`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seedSession(database, started = 4_000, ended = 5_000)
                val repository = RoomSessionMemoRepository(database)
                val previous = saved(repository.save(sessionId, "original fixture", null) { true })
                seedSession(database, id = "newer-finished", started = 100, ended = 200)
                seedSession(database, id = "current-active", ended = null)
                assertEquals(SessionMemoWriteResult.MissingSession, repository.save("missing", "fixture", null) { true })
                assertEquals(SessionMemoWriteResult.MissingSession, repository.delete("missing", "token") { true })
                assertEquals(SessionMemoWriteResult.SessionActive, repository.save("current-active", "fixture", null) { true })
                assertEquals(SessionMemoWriteResult.SessionActive, repository.delete("current-active", "token") { true })
                assertEquals(SessionMemoWriteResult.SessionChanged, repository.save(sessionId, "changed", previous.version) { true })
                assertEquals(SessionMemoWriteResult.SessionChanged, repository.delete(sessionId, previous.version) { true })
                assertEquals(previous, repository.observe(sessionId).first())
                assertTrue(repository.save("newer-finished", "latest by row order", null) { true } is SessionMemoWriteResult.Saved)
            }
        }

    @Test fun `blocked calls do not create update or delete notes even when tokens would otherwise match`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seedSession(database)
                val repository = RoomSessionMemoRepository(database)
                assertEquals(SessionMemoWriteResult.Blocked, repository.save(sessionId, "fixture", null) { false })
                assertEquals(null, repository.observe(sessionId).first())
                val original = saved(repository.save(sessionId, "original fixture", null) { true })
                assertEquals(SessionMemoWriteResult.Blocked, repository.save(sessionId, "changed", original.version) { false })
                assertEquals(SessionMemoWriteResult.Blocked, repository.delete(sessionId, original.version) { false })
                assertEquals(original, repository.observe(sessionId).first())
            }
        }

    @Test fun `permission loss after insert update or delete rolls back the entire write`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seedSession(database)
                val repository = RoomSessionMemoRepository(database)
                var checks = 0
                assertEquals(SessionMemoWriteResult.Blocked, repository.save(sessionId, "new fixture", null) { ++checks == 1 })
                assertEquals(2, checks)
                assertEquals(null, repository.observe(sessionId).first())
                val original = saved(repository.save(sessionId, "original fixture", null) { true })
                checks = 0
                assertEquals(
                    SessionMemoWriteResult.Blocked,
                    repository.save(sessionId, "changed fixture", original.version) { ++checks == 1 },
                )
                assertEquals(2, checks)
                assertEquals(original, repository.observe(sessionId).first())
                checks = 0
                assertEquals(SessionMemoWriteResult.Blocked, repository.delete(sessionId, original.version) { ++checks == 1 })
                assertEquals(2, checks)
                assertEquals(original, repository.observe(sessionId).first())
            }
        }

    @Test fun `two competing edits with one expected token cannot silently overwrite each other`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seedSession(database)
                val repository = RoomSessionMemoRepository(database)
                val original = saved(repository.save(sessionId, "original fixture", null) { true })
                val ready = CompletableDeferred<Unit>()
                val writers =
                    (1..2).map { number ->
                        async(Dispatchers.Default) {
                            ready.await()
                            repository.save(sessionId, "candidate fixture $number", original.version) { true }
                        }
                    }
                ready.complete(Unit)
                val results = writers.map { it.await() }
                assertEquals(1, results.count { it is SessionMemoWriteResult.Saved })
                assertEquals(1, results.count { it == SessionMemoWriteResult.Conflict })
                val winner = (results.single { it is SessionMemoWriteResult.Saved } as SessionMemoWriteResult.Saved).memo
                assertEquals(winner, repository.observe(sessionId).first())
            }
        }

    @Test fun `invalid input never repairs truncates clears or overwrites an existing memo`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seedSession(database)
                val repository = RoomSessionMemoRepository(database)
                val original = saved(repository.save(sessionId, "original fixture", null) { true })
                val invalid = listOf("", " \n\t", "x".repeat(281), "🙂".repeat(281), "memo\u0000value", "\uD800", "\uDC00")
                for (text in invalid) {
                    assertEquals(SessionMemoWriteResult.InvalidText, repository.save(sessionId, text, original.version) { true })
                    assertEquals(original, repository.observe(sessionId).first())
                }
                val atLimit = "🙂".repeat(280)
                val next = saved(repository.save(sessionId, atLimit, original.version) { true })
                assertEquals(atLimit, next.text)
                assertEquals(next, repository.observe(sessionId).first())
            }
        }

    @Test fun `note writes leave preparation session and SDK observation fields untouched`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                database.preparationDao().upsert(
                    PreparationEntity(
                        goalId = "commute",
                        courseId = "neighborhood",
                        courseVersion = 1,
                        briefingVersion = 1,
                        taskId = "sequence",
                    ),
                )
                seedSession(database, ended = null)
                RoomVehicleObservationRepository(database).append(
                    sessionId,
                    VehicleObservation(1_200, VehicleState(ConnectionState.ERROR, brakePressed = VehicleReading.Error)),
                )
                val sessions = RoomPracticeSessionRepository(database, LocalPracticeCatalog())
                assertTrue(sessions.finish(sessionId, 2_000) { true } is SessionFinishResult.Finished)
                val sqlite = database.openHelper.writableDatabase
                val before = otherTables(sqlite)
                val repository = RoomSessionMemoRepository(database)
                val created = saved(repository.save(sessionId, "source is user text", null) { true })
                val updated = saved(repository.save(sessionId, "edited user text", created.version) { true })
                assertEquals(SessionMemoWriteResult.Deleted, repository.delete(sessionId, updated.version) { true })
                assertEquals(before, otherTables(sqlite))
                sqlite.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
            }
        }

    @Test fun `SQL insert update and delete failures propagate and preserve the old note`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seedSession(database)
                val repository = RoomSessionMemoRepository(database)
                val sqlite = database.openHelper.writableDatabase
                rejectWrite(sqlite, "INSERT")
                expectSqlFailure { repository.save(sessionId, "insert fixture", null) { true } }
                assertEquals(null, repository.observe(sessionId).first())
                sqlite.execSQL("DROP TRIGGER reject_memo_fixture")
                val original = saved(repository.save(sessionId, "original fixture", null) { true })
                rejectWrite(sqlite, "UPDATE")
                expectSqlFailure { repository.save(sessionId, "changed fixture", original.version) { true } }
                assertEquals(original, repository.observe(sessionId).first())
                sqlite.execSQL("DROP TRIGGER reject_memo_fixture")
                rejectWrite(sqlite, "DELETE")
                expectSqlFailure { repository.delete(sessionId, original.version) { true } }
                assertEquals(original, repository.observe(sessionId).first())
            }
        }

    @Test fun `cancelling an enclosing transaction rolls back a newly acknowledged note`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seedSession(database)
                val repository = RoomSessionMemoRepository(database)
                val inserted = CompletableDeferred<Unit>()
                val writer =
                    launch(Dispatchers.Default) {
                        database.withTransaction {
                            assertTrue(repository.save(sessionId, "cancelled fixture", null) { true } is SessionMemoWriteResult.Saved)
                            inserted.complete(Unit)
                            awaitCancellation()
                        }
                    }
                withTimeout(10_000) { inserted.await() }
                writer.cancelAndJoin()
                assertEquals(null, repository.observe(sessionId).first())
            }
        }

    @Test fun `cancellation at the post-write guard preserves the original cause and rolls back the edit`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seedSession(database)
                val repository = RoomSessionMemoRepository(database)
                val original = saved(repository.save(sessionId, "original fixture", null) { true })
                val cancelled = CancellationException("fixture cancellation")
                var checks = 0
                try {
                    repository.save(sessionId, "cancelled edit", original.version) {
                        if (++checks == 2) throw cancelled
                        true
                    }
                    fail("Cancellation must not become a successful memo result")
                } catch (actual: CancellationException) {
                    // Coroutine stack recovery may copy the exception but must retain its cause.
                    assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === cancelled })
                }
                assertEquals(original, repository.observe(sessionId).first())
            }
        }

    @Test fun `malformed saved text or version fails reads and cannot be overwritten as if absent`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seedSession(database)
                val repository = RoomSessionMemoRepository(database)
                val validVersion = UUID.randomUUID().toString()
                val corrupt =
                    listOf(
                        SessionMemoEntity(sessionId, "", validVersion),
                        SessionMemoEntity(sessionId, " \n", validVersion),
                        SessionMemoEntity(sessionId, "x".repeat(281), validVersion),
                        SessionMemoEntity(sessionId, "memo\u0000value", validVersion),
                        SessionMemoEntity(sessionId, "fixture", ""),
                        SessionMemoEntity(sessionId, "fixture", "not-a-token"),
                        SessionMemoEntity(sessionId, "fixture", "1-1-1-1-1"),
                    )
                for (entity in corrupt) {
                    database.openHelper.writableDatabase.execSQL("DELETE FROM session_memo")
                    database.sessionMemoDao().insert(entity)
                    expectInvalidStoredMemo { repository.observe(sessionId).first() }
                    expectInvalidStoredMemo { repository.save(sessionId, "replacement fixture", null) { true } }
                    assertEquals(1, memoCount(database.openHelper.writableDatabase))
                }
            }
        }

    @Test fun `blob text or token storage never becomes an ordinary string memo`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seedSession(database)
                val repository = RoomSessionMemoRepository(database)
                val created = saved(repository.save(sessionId, "fixture", null) { true })
                val sqlite = database.openHelper.writableDatabase
                sqlite.execSQL("UPDATE session_memo SET text = CAST(? AS BLOB) WHERE sessionId = ?", arrayOf("fixture", sessionId))
                sqlite.query("SELECT typeof(text) FROM session_memo").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("blob", it.getString(0))
                }
                expectInvalidStoredMemo { repository.observe(sessionId).first() }
                sqlite.execSQL(
                    "UPDATE session_memo SET text = ?, version = CAST(? AS BLOB) WHERE sessionId = ?",
                    arrayOf("fixture", created.version, sessionId),
                )
                sqlite.query("SELECT typeof(version) FROM session_memo").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("blob", it.getString(0))
                }
                expectInvalidStoredMemo { repository.observe(sessionId).first() }
            }
        }

    @Test fun `memo text and token survive database close and reopen without changing other stored data`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val name = "session-memo-reopen-fixture.db"
            context.deleteDatabase(name)

            fun open() =
                Room
                    .databaseBuilder(context, PreparationDatabase::class.java, name)
                    .addMigrations(PreparationMigration1To2, PreparationMigration2To3, PreparationMigration3To4, PreparationMigration4To5)
                    .build()
            try {
                val expected =
                    open().useDatabase { database ->
                        seedSession(database)
                        saved(RoomSessionMemoRepository(database).save(sessionId, " \n기억할 점 🙂\n ", null) { true })
                    }
                open().useDatabase { database ->
                    assertEquals(expected, RoomSessionMemoRepository(database).observe(sessionId).first())
                    assertEquals(
                        1,
                        database
                            .practiceSessionDao()
                            .observe()
                            .first()
                            .size,
                    )
                    assertFalse(checkNotNull(database.practiceSessionDao().find(sessionId)).session().isActive)
                    assertTrue(
                        database
                            .vehicleObservationDao()
                            .observe(sessionId)
                            .first()
                            .isEmpty(),
                    )
                }
            } finally {
                context.deleteDatabase(name)
            }
        }

    private suspend fun seedSession(
        database: PreparationDatabase,
        id: String = sessionId,
        started: Long = 1_000,
        ended: Long? = 2_000,
    ) {
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
                endedAtEpochMillis = ended,
                activeSlot = if (ended == null) 1 else null,
            ),
        )
    }

    private fun saved(result: SessionMemoWriteResult): SessionMemo = (result as SessionMemoWriteResult.Saved).memo

    private fun rejectWrite(
        database: SupportSQLiteDatabase,
        operation: String,
    ) {
        database.execSQL(
            "CREATE TRIGGER reject_memo_fixture AFTER $operation ON session_memo " +
                "BEGIN SELECT RAISE(ABORT, 'fixture memo failure'); END",
        )
    }

    private suspend fun expectSqlFailure(action: suspend () -> Unit) {
        try {
            action()
            fail("SQLite failure must not become a successful memo result")
        } catch (_: SQLiteException) {
            // The real SQLite exception propagates rather than a successful or empty memo state.
        }
    }

    private suspend fun expectInvalidStoredMemo(action: suspend () -> Unit) {
        try {
            action()
            fail("Corrupt stored data must not become a normal or absent memo")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SQLiteException) {
            // Some SQLite readers reject BLOB-to-String conversion before the projection is built.
        } catch (_: IllegalStateException) {
            // Decodable corrupt values are rejected by the projection/domain validation instead.
        }
    }

    private fun memoCount(database: SupportSQLiteDatabase): Int =
        database.query("SELECT COUNT(*) FROM session_memo").use {
            check(it.moveToFirst())
            it.getInt(0)
        }

    private fun otherTables(database: SupportSQLiteDatabase): Map<String, List<List<String?>>> =
        listOf("preparation" to "slot", "practice_session" to "rowId", "vehicle_observation" to "id").associate { (table, key) ->
            table to
                database.query("SELECT * FROM $table ORDER BY $key").use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
                        }
                    }
                }
        }

    private inline fun <T> PreparationDatabase.useDatabase(block: (PreparationDatabase) -> T): T {
        try {
            return block(this)
        } finally {
            close()
        }
    }
}
