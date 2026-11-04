package io.github.hangillab.hangil.core.database

import android.database.sqlite.SQLiteException
import androidx.room.Room
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.SessionCourseSummary
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionStorageIntegrityTest {
    private val normalId = "normal-session-integrity-fixture"
    private val targetId = "target-session-integrity-fixture"
    private val catalog = LocalPracticeCatalog()
    private val selection = PracticeSelection("commute", "neighborhood", 1, 1, "sequence")
    private val practice = (catalog.resolve(selection) as Preparation.Ready).practice

    @Test fun `REAL course version must not enter a normal historical summary`() =
        rejectedRead(
            "courseVersion",
            1.75,
            "real",
            summary = true,
        )

    @Test fun `REAL briefing version must not enter a normal historical summary`() =
        rejectedRead(
            "briefingVersion",
            1.75,
            "real",
            summary = true,
        )

    @Test fun `overflow course version must not merge with current version history`() =
        rejectedRead("courseVersion", 4_294_967_297L, "integer", summary = true)

    @Test fun `overflow briefing version must not merge with current version history`() =
        rejectedRead("briefingVersion", 4_294_967_297L, "integer", summary = true)

    @Test fun `negative overflow course version must not merge with current version history`() =
        rejectedRead("courseVersion", -4_294_967_295L, "integer", summary = true)

    @Test fun `negative overflow briefing version must not merge with current version history`() =
        rejectedRead("briefingVersion", -4_294_967_295L, "integer", summary = true)

    @Test fun `REAL start time must not become a normal session timestamp`() = rejectedRead("startedAtEpochMillis", 1_000.75, "real")

    @Test fun `REAL end time must not become a normal session timestamp`() = rejectedRead("endedAtEpochMillis", 2_000.75, "real")

    @Test fun `REAL active slot must not become a normal active session`() = rejectedRead("activeSlot", 1.75, "real", active = true)

    @Test fun `coerced active source must not be acknowledged as AlreadyActive`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seed(database, targetActive = true)
                corrupt(database, "courseVersion", 4_294_967_297L, "integer")
                val before = physicalTables(database)
                val attempt = runCatching { RoomPracticeSessionRepository(database, catalog).start(practice, 3_000) { true } }
                assertStorageFailure(attempt, "Invalid active source must not be acknowledged: ${attempt.getOrNull()}")
                assertEquals(before, physicalTables(database))
            }
        }

    @Test fun `noncanonical active slot cannot be skipped to insert a second logical owner`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seed(database, targetActive = true)
                corrupt(database, "activeSlot", 1.75, "real")
                assertEquals(null, database.practiceSessionDao().active())
                assertEquals(1L, database.practiceSessionDao().unfinishedCount())
                val before = physicalTables(database)
                val attempt = runCatching { RoomPracticeSessionRepository(database, catalog).start(practice, 3_000) { true } }
                val count = database.practiceSessionDao().unfinishedCount()
                assertStorageFailure(
                    attempt,
                    "Skipped owner must block start: result=${attempt.getOrNull()}, " +
                        "unfinished=$count, rawUnchanged=${before == physicalTables(database)}",
                )
                assertEquals(before, physicalTables(database))
            }
        }

    @Test fun `valid active row cannot hide another unfinished source`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seed(database, targetActive = true)
                database.practiceSessionDao().insert(entity("other-unfinished-fixture").copy(endedAtEpochMillis = null, activeSlot = 2))
                assertEquals(2L, database.practiceSessionDao().unfinishedCount())
                val before = physicalTables(database)
                val attempt = runCatching { RoomPracticeSessionRepository(database, catalog).start(practice, 3_000) { true } }
                assertStorageFailure(attempt, "Ambiguous logical owners must not become AlreadyActive: ${attempt.getOrNull()}")
                assertEquals(before, physicalTables(database))
            }
        }

    @Test fun `normal history keeps insertion order historical versions and exact course context`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seed(database)
                val repository = RoomPracticeSessionRepository(database, catalog)
                val before = physicalTables(database)
                val first = repository.observeSessions().first()
                assertEquals(listOf(targetId, normalId), first.map { it.sessionId })
                assertEquals(2, checkNotNull(SessionCourseSummary.from(first.last(), first)).sessionCount)
                assertEquals(before, physicalTables(database))
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE practice_session SET courseVersion = 2, startedAtEpochMillis = 100, endedAtEpochMillis = 200 WHERE sessionId = ?",
                    arrayOf(targetId),
                )
                val changed = physicalTables(database)
                val records = repository.observeSessions().first()
                assertEquals(listOf(targetId, normalId), records.map { it.sessionId })
                assertEquals(2, records.first().selection.courseVersion)
                assertEquals(1, checkNotNull(SessionCourseSummary.from(records.last(), records)).sessionCount)
                assertEquals(changed, physicalTables(database))
                val started = repository.start(practice, 3_000) { true } as SessionStartResult.Started
                assertEquals(SessionStartResult.AlreadyActive(started.session), repository.start(practice, 4_000) { true })
                assertEquals(1L, database.practiceSessionDao().unfinishedCount())
            }
        }

    @Test fun `blocked start precedes malformed owner reads and preserves all raw storage`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seed(database, targetActive = true)
                corrupt(database, "activeSlot", 1.75, "real")
                val before = physicalTables(database)
                assertEquals(SessionStartResult.Blocked, RoomPracticeSessionRepository(database, catalog).start(practice, 3_000) { false })
                assertEquals(before, physicalTables(database))
            }
        }

    private fun rejectedRead(
        column: String,
        value: Number,
        storageType: String,
        active: Boolean = false,
        summary: Boolean = false,
    ) = runBlocking {
        memoryDatabase().useDatabase { database ->
            seed(database, active)
            corrupt(database, column, value, storageType)
            val before = physicalTables(database)
            val attempt = runCatching { RoomPracticeSessionRepository(database, catalog).observeSessions().first() }
            val records = attempt.getOrNull()
            val count =
                if (summary &&
                    records != null
                ) {
                    SessionCourseSummary.from(records.single { it.sessionId == normalId }, records)?.sessionCount
                } else {
                    null
                }
            assertStorageFailure(
                attempt,
                "Corrupt row must fail the whole stream: records=${records?.size}, summary=$count, result=${attempt.getOrNull()}",
            )
            assertEquals(
                "An invalid read must retain every raw fixture field, type, row, schema and sequence",
                before,
                physicalTables(database),
            )
            database.openHelper.readableDatabase
                .query("PRAGMA foreign_key_check")
                .use { assertEquals(0, it.count) }
        }
    }

    private suspend fun seed(
        database: PreparationDatabase,
        targetActive: Boolean = false,
    ) {
        database.practiceSessionDao().insert(entity(normalId))
        assertTrue(
            RoomSessionMemoRepository(database).save(normalId, "retained fixture memo", null) { true } is SessionMemoWriteResult.Saved,
        )
        database.practiceSessionDao().insert(
            entity(targetId).copy(endedAtEpochMillis = if (targetActive) null else 2_000, activeSlot = if (targetActive) 1 else null),
        )
        assertTrue(RoomPracticeRepository(database).saveSelection(selection) { true })
    }

    private fun entity(id: String) =
        PracticeSessionEntity(
            sessionId = id,
            goalId = selection.goalId,
            courseId = selection.courseId,
            courseVersion = 1,
            briefingVersion = 1,
            taskId = selection.taskId,
            goalTitle = "Fixture goal",
            courseTitle = "Fixture course",
            taskTitle = "Fixture task",
            courseReviewStatus = CourseReviewStatus.DEMO.name,
            startedAtEpochMillis = 1_000,
            endedAtEpochMillis = 2_000,
            activeSlot = null,
        )

    private fun corrupt(
        database: PreparationDatabase,
        column: String,
        value: Number,
        storageType: String,
    ) {
        check(column in setOf("courseVersion", "briefingVersion", "startedAtEpochMillis", "endedAtEpochMillis", "activeSlot"))
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL("UPDATE practice_session SET $column = ? WHERE sessionId = ?", arrayOf(value, targetId))
        sqlite.query("SELECT typeof($column), $column FROM practice_session WHERE sessionId = ?", arrayOf(targetId)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(storageType, cursor.getString(0))
            val raw = if (storageType == "real") cursor.getDouble(1) else cursor.getLong(1)
            assertEquals("Fixture must retain intended physical value before product reads", value, raw)
            assertTrue(!cursor.moveToNext())
        }
    }

    private fun assertStorageFailure(
        result: Result<*>,
        message: String,
    ) {
        val error = result.exceptionOrNull()
        if (error is CancellationException) throw error
        if (error != null && error !is IllegalStateException && error !is SQLiteException) throw error
        assertTrue(message, error is IllegalStateException || error is SQLiteException)
    }

    private fun memoryDatabase() =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PreparationDatabase::class.java).build()

    private fun physicalTables(database: PreparationDatabase): Map<String, List<List<String?>>> {
        val sqlite = database.openHelper.readableDatabase

        fun rows(sql: String): List<List<String?>> =
            sqlite.query(sql).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            (0 until cursor.columnCount).map { column ->
                                when (cursor.getType(column)) {
                                    0 -> null
                                    1 -> "integer:" + cursor.getLong(column)
                                    2 -> "real:" + java.lang.Double.toHexString(cursor.getDouble(column))
                                    3 -> "text:" + cursor.getString(column)
                                    4 -> "blob:" + cursor.getBlob(column).joinToString("") { "%02x".format(it) }
                                    else -> error("Unknown fixture storage type")
                                }
                            },
                        )
                    }
                }
            }
        val tables =
            sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        return tables.associateWith { table ->
            check(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
            rows("SELECT rowid, * FROM `$table` ORDER BY rowid")
        } + mapOf("schema" to rows("SELECT type, name, tbl_name, rootpage, sql FROM sqlite_master ORDER BY type, name"))
    }

    private inline fun <T> PreparationDatabase.useDatabase(block: (PreparationDatabase) -> T): T {
        try {
            return block(this)
        } finally {
            close()
        }
    }
}
