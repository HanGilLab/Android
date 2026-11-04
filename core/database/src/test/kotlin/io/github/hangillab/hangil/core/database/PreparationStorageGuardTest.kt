package io.github.hangillab.hangil.core.database

import android.database.sqlite.SQLiteException
import androidx.room.Room
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.Preparation
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
class PreparationStorageGuardTest {
    private val catalog = LocalPracticeCatalog()
    private val selection = PracticeSelection("commute", "neighborhood", 1, 1, "sequence")
    private val practice = (catalog.resolve(selection) as Preparation.Ready).practice

    @Test fun `REAL course version never becomes a ready selection or a started session`() = numericRejected("courseVersion", 1.75, "real")

    @Test fun `REAL briefing version never becomes a ready selection or a started session`() =
        numericRejected(
            "briefingVersion",
            1.75,
            "real",
        )

    @Test fun `overflow course version cannot wrap into the current version`() = numericRejected("courseVersion", 4_294_967_297L, "integer")

    @Test fun `overflow briefing version cannot wrap into the current version`() =
        numericRejected(
            "briefingVersion",
            4_294_967_297L,
            "integer",
        )

    @Test fun `negative overflow course version cannot wrap into the current version`() =
        numericRejected("courseVersion", -4_294_967_295L, "integer")

    @Test fun `negative overflow briefing version cannot wrap into the current version`() =
        numericRejected("briefingVersion", -4_294_967_295L, "integer")

    @Test fun `BLOB goal identity never becomes a normal string selection`() = blobRejected("goalId", selection.goalId)

    @Test fun `BLOB course identity never becomes a normal string selection`() = blobRejected("courseId", selection.courseId)

    @Test fun `BLOB task identity never becomes a normal string selection`() = blobRejected("taskId", selection.taskId)

    @Test fun `blocked start takes precedence over corrupt preparation and preserves every row`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seed(database)
                database.openHelper.writableDatabase.execSQL("UPDATE preparation SET courseVersion = 1.75 WHERE slot = 1")
                val before = physicalTables(database)
                assertEquals(SessionStartResult.Blocked, RoomPracticeSessionRepository(database, catalog).start(practice, 3_000) { false })
                assertEquals(before, physicalTables(database))
            }
        }

    @Test fun `valid signed Int versions remain Outdated and never turn into storage errors`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val repository = RoomPracticeRepository(database)
                val sessions = RoomPracticeSessionRepository(database, catalog)
                for (version in listOf(Int.MIN_VALUE, -1, 0, 2, Int.MAX_VALUE)) {
                    for (next in listOf(selection.copy(courseVersion = version), selection.copy(briefingVersion = version))) {
                        assertTrue(repository.saveSelection(next) { true })
                        val before = physicalTables(database)
                        assertEquals(next, repository.observeSelection().first())
                        assertEquals(Preparation.Outdated, catalog.resolve(repository.observeSelection().first()))
                        assertEquals(SessionStartResult.PreparationChanged, sessions.start(practice, 3_000) { true })
                        assertEquals(before, physicalTables(database))
                    }
                }
            }
        }

    @Test fun `missing and valid preparation keep Empty Started and AlreadyActive outcomes`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val repository = RoomPracticeRepository(database)
                val sessions = RoomPracticeSessionRepository(database, catalog)
                assertEquals(null, repository.observeSelection().first())
                assertEquals(Preparation.Empty, catalog.resolve(repository.observeSelection().first()))
                assertEquals(SessionStartResult.PreparationChanged, sessions.start(practice, 3_000) { true })
                assertTrue(repository.saveSelection(selection) { true })
                assertEquals(Preparation.Ready(practice), catalog.resolve(repository.observeSelection().first()))
                val started = sessions.start(practice, 3_000) { true } as SessionStartResult.Started
                assertEquals(SessionStartResult.AlreadyActive(started.session), sessions.start(practice, 4_000) { true })
            }
        }

    @Test fun `existing active session wins over unrelated corrupt numeric preparation`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seed(database)
                val sessions = RoomPracticeSessionRepository(database, catalog)
                val started = sessions.start(practice, 3_000) { true } as SessionStartResult.Started
                database.openHelper.writableDatabase.execSQL("UPDATE preparation SET courseVersion = 1.75 WHERE slot = 1")
                val before = physicalTables(database)
                assertEquals(SessionStartResult.AlreadyActive(started.session), sessions.start(practice, 4_000) { true })
                assertEquals(before, physicalTables(database))
            }
        }

    private fun numericRejected(
        column: String,
        value: Number,
        expectedType: String,
    ) = runBlocking {
        check(column in setOf("courseVersion", "briefingVersion"))
        rejectsCorruptPreparation { database ->
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL("UPDATE preparation SET $column = ? WHERE slot = 1", arrayOf(value))
            sqlite.query("SELECT typeof($column), $column FROM preparation WHERE slot = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(expectedType, cursor.getString(0))
                val raw = if (expectedType == "real") cursor.getDouble(1) else cursor.getLong(1)
                assertEquals("Fixture physical value must be exact before observing or starting", value, raw)
                assertTrue(!cursor.moveToNext())
            }
        }
    }

    private fun blobRejected(
        column: String,
        value: String,
    ) = runBlocking {
        check(column in setOf("goalId", "courseId", "taskId"))
        rejectsCorruptPreparation { database ->
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL("UPDATE preparation SET $column = CAST(? AS BLOB) WHERE slot = 1", arrayOf(value))
            sqlite.query("SELECT typeof($column), $column FROM preparation WHERE slot = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("blob", cursor.getString(0))
                assertTrue(value.toByteArray().contentEquals(cursor.getBlob(1)))
            }
        }
    }

    private suspend fun rejectsCorruptPreparation(corrupt: (PreparationDatabase) -> Unit) {
        memoryDatabase().useDatabase { database ->
            seed(database)
            corrupt(database)
            val before = physicalTables(database)
            val observed = runCatching { RoomPracticeRepository(database).observeSelection().first() }
            val observedError = observed.exceptionOrNull()
            if (observedError is CancellationException) throw observedError
            if (observedError != null && observedError !is IllegalStateException && observedError !is SQLiteException) throw observedError
            val attempted = runCatching { RoomPracticeSessionRepository(database, catalog).start(practice, 3_000) { true } }
            val writeError = attempted.exceptionOrNull()
            if (writeError is CancellationException) throw writeError
            if (writeError != null && writeError !is IllegalStateException && writeError !is SQLiteException) throw writeError
            val after = physicalTables(database)
            assertTrue(
                "Corrupt preparation must fail before becoming Ready/Started: observed=${catalog.resolve(observed.getOrNull())}, " +
                    "start=${attempted.getOrNull()}, rawUnchanged=${before == after}",
                observedError != null && writeError != null,
            )
            assertEquals("Rejected observation/start must preserve raw values, types, row order, schema and sequence", before, after)
            database.openHelper.readableDatabase
                .query("PRAGMA foreign_key_check")
                .use { assertEquals(0, it.count) }
        }
    }

    private suspend fun seed(database: PreparationDatabase) {
        database.practiceSessionDao().insert(
            PracticeSessionEntity(
                sessionId = "retained-preparation-guard-fixture",
                goalId = selection.goalId,
                courseId = selection.courseId,
                courseVersion = 1,
                briefingVersion = 1,
                taskId = selection.taskId,
                goalTitle = "Fixture goal",
                courseTitle = "Fixture course",
                taskTitle = "Fixture task",
                courseReviewStatus = "DEMO",
                startedAtEpochMillis = 1_000,
                endedAtEpochMillis = 2_000,
                activeSlot = null,
            ),
        )
        assertTrue(
            RoomSessionMemoRepository(database).save("retained-preparation-guard-fixture", "retained fixture memo", null) { true }
                is SessionMemoWriteResult.Saved,
        )
        assertTrue(RoomPracticeRepository(database).saveSelection(selection) { true })
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
