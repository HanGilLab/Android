package io.github.hangillab.hangil.core.database

import androidx.room.Room
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import kotlinx.coroutines.CancellationException
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
class RoomSessionMemoSourceGuardTest {
    private val sessionId = "latest-memo-source-fixture"
    private val retainedId = "retained-memo-source-fixture"

    @Test fun `REAL course version rejects all memo mutations`() = assertNumericRejected("courseVersion", 1.75, "real")

    @Test fun `REAL briefing version rejects all memo mutations`() = assertNumericRejected("briefingVersion", 1.75, "real")

    @Test fun `REAL start time rejects all memo mutations`() = assertNumericRejected("startedAtEpochMillis", 1_000.75, "real")

    @Test fun `REAL end time rejects all memo mutations`() = assertNumericRejected("endedAtEpochMillis", 2_000.75, "real")

    @Test fun `overflow course version rejects all memo mutations`() = assertNumericRejected("courseVersion", 4_294_967_297L, "integer")

    @Test fun `overflow briefing version rejects all memo mutations`() = assertNumericRejected("briefingVersion", 4_294_967_297L, "integer")

    @Test fun `blank goal identity rejects all memo mutations`() = assertBlankRejected("goalId")

    @Test fun `blank course identity rejects all memo mutations`() = assertBlankRejected("courseId")

    @Test fun `blank task identity rejects all memo mutations`() = assertBlankRejected("taskId")

    @Test fun `blank goal title rejects all memo mutations`() = assertBlankRejected("goalTitle")

    @Test fun `blank course title rejects all memo mutations`() = assertBlankRejected("courseTitle")

    @Test fun `blank task title rejects all memo mutations`() = assertBlankRejected("taskTitle")

    @Test fun `blocked calls take precedence over corrupt source and preserve all storage`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val repository = seed(database)
                val original = repository.save(sessionId, "fixture memo", null) { true } as SessionMemoWriteResult.Saved
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE practice_session SET startedAtEpochMillis = ? WHERE sessionId = ?",
                    arrayOf(1_000.75, sessionId),
                )
                val before = physicalTables(database)
                assertEquals(SessionMemoWriteResult.Blocked, repository.save(sessionId, "new text", null) { false })
                assertEquals(SessionMemoWriteResult.Blocked, repository.save(sessionId, "new text", original.memo.version) { false })
                assertEquals(SessionMemoWriteResult.Blocked, repository.delete(sessionId, original.memo.version) { false })
                assertEquals(before, physicalTables(database))
            }
        }

    private fun assertBlankRejected(column: String) =
        runBlocking {
            check(column in setOf("goalId", "courseId", "taskId", "goalTitle", "courseTitle", "taskTitle"))
            assertRejectedMutations { database ->
                database.openHelper.writableDatabase.execSQL(
                    "UPDATE practice_session SET $column = ? WHERE sessionId = ?",
                    arrayOf(" \t\n", sessionId),
                )
            }
        }

    private fun assertNumericRejected(
        column: String,
        value: Number,
        storageType: String,
    ) = runBlocking {
        check(column in setOf("courseVersion", "briefingVersion", "startedAtEpochMillis", "endedAtEpochMillis"))
        assertRejectedMutations { database ->
            val sqlite = database.openHelper.writableDatabase
            sqlite.execSQL("UPDATE practice_session SET $column = ? WHERE sessionId = ?", arrayOf(value, sessionId))
            sqlite.query("SELECT typeof($column), $column FROM practice_session WHERE sessionId = ?", arrayOf(sessionId)).use { cursor ->
                assertTrue("Fixture must be present before the mutation attempt", cursor.moveToFirst())
                assertEquals("SQLite affinity must retain the intended physical type", storageType, cursor.getString(0))
                val actual = if (storageType == "real") cursor.getDouble(1) else cursor.getLong(1)
                assertEquals("Physical source value must remain exact", value, actual)
                assertTrue(!cursor.moveToNext())
            }
        }
    }

    private suspend fun assertRejectedMutations(corrupt: (PreparationDatabase) -> Unit) {
        val violations = mutableListOf<String>()
        for (operation in listOf("create", "update", "delete")) {
            memoryDatabase().useDatabase { database ->
                val repository = seed(database)
                val original =
                    if (operation == "create") {
                        null
                    } else {
                        (repository.save(sessionId, "target fixture memo", null) { true } as SessionMemoWriteResult.Saved).memo
                    }
                corrupt(database)
                val before = physicalTables(database)
                val attempted =
                    runCatching {
                        when (operation) {
                            "create" -> repository.save(sessionId, "new fixture memo", null) { true }
                            "update" -> repository.save(sessionId, "changed fixture memo", checkNotNull(original).version) { true }
                            else -> repository.delete(sessionId, checkNotNull(original).version) { true }
                        }
                    }
                val error = attempted.exceptionOrNull()
                if (error is CancellationException) throw error
                if (error != null && error !is IllegalStateException) throw error
                if (error !is IllegalStateException) violations += "$operation returned ${attempted.getOrNull()}"
                if (before != physicalTables(database)) violations += "$operation changed raw fixture storage"
                database.openHelper.readableDatabase
                    .query("PRAGMA foreign_key_check")
                    .use { assertEquals(0, it.count) }
            }
        }
        assertTrue("Corrupt source must reject all mutations and retain raw storage: $violations", violations.isEmpty())
    }

    private fun memoryDatabase() =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PreparationDatabase::class.java).build()

    private suspend fun seed(database: PreparationDatabase): RoomSessionMemoRepository {
        val repository = RoomSessionMemoRepository(database)
        seedSession(database, retainedId)
        assertTrue(repository.save(retainedId, "retained fixture memo", null) { true } is SessionMemoWriteResult.Saved)
        seedSession(database, sessionId)
        return repository
    }

    private suspend fun seedSession(
        database: PreparationDatabase,
        id: String,
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
                startedAtEpochMillis = 1_000,
                endedAtEpochMillis = 2_000,
                activeSlot = null,
            ),
        )
    }

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
