package io.github.hangillab.hangil.core.database

import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.os.Bundle
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.domain.NextPracticeSaveResult
import io.github.hangillab.hangil.core.domain.NextPracticeSeed
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Actual Android Room with UUID synthetic fixtures in an isolated, in-memory library-test DB. */
@RunWith(AndroidJUnit4::class)
class NextPracticeStorageTest {
    private val sourceSelection = PracticeSelection("commute", "neighborhood", 1, 1, "sequence")
    private val target = sourceSelection.copy(taskId = "arrival")
    private val original = PracticeSelection("shopping", "intersections", 1, 1, "review")

    @Test fun validLatestSourceChangesOnlyPreparationAndOlderSourceStaysRejected() =
        runBlocking {
            withTimeout(20_000) {
                withOwnedDatabase { database, fixture ->
                    val before = snapshot(database, includePreparation = false)
                    val result = fixture.repository.saveSelection(fixture.seed, target) { true }
                    assertTrue("NPST_VALID_RESULT", result == NextPracticeSaveResult.Saved)
                    assertTrue("NPST_VALID_SELECTION", database.preparationDao().current()?.selection() == target)
                    assertTrue("NPST_VALID_OTHER_ROWS", before == snapshot(database, includePreparation = false))
                    val newerId = "native-next-newer-${UUID.randomUUID()}"
                    insertCopy(database.openHelper.writableDatabase, fixture.source.sessionId, newerId, "500", "750", "NULL")
                    val afterNewer = snapshot(database)
                    assertTrue(
                        "NPST_OLDER_REJECTED",
                        fixture.repository.saveSelection(fixture.seed, sourceSelection) { true } == NextPracticeSaveResult.SourceChanged,
                    )
                    assertTrue("NPST_OLDER_UNCHANGED", afterNewer == snapshot(database))
                    val latest = checkNotNull(database.practiceSessionDao().find(newerId)).session()
                    val latestSeed = checkNotNull(NextPracticeSeed.resolve(fixture.catalog, latest))
                    val beforeLatestSave = snapshot(database, includePreparation = false)
                    assertTrue(
                        "NPST_LATEST_RESULT",
                        fixture.repository.saveSelection(latestSeed, sourceSelection) { true } == NextPracticeSaveResult.Saved,
                    )
                    assertTrue("NPST_LATEST_SELECTION", database.preparationDao().current()?.selection() == sourceSelection)
                    assertTrue("NPST_LATEST_OTHER_ROWS", beforeLatestSave == snapshot(database, includePreparation = false))
                }
            }
            phase("LATEST_ONLY_PASSED", 1)
        }

    @Test fun physicalSourceCorruptionsPropagateBeforePreparationWrite() =
        runBlocking {
            withTimeout(20_000) {
                for (corruption in SourceCorruption.entries) {
                    withOwnedDatabase { database, fixture ->
                        corruptSource(database.openHelper.writableDatabase, fixture.source.sessionId, corruption)
                        val before = snapshot(database)
                        var checks = 0
                        expectMalformedSource {
                            fixture.repository.saveSelection(fixture.seed, target) {
                                checks++
                                true
                            }
                        }
                        assertTrue("NPST_SOURCE_PRE_GATE", checks == 1)
                        assertTrue("NPST_SOURCE_PRE_UNCHANGED", before == snapshot(database))
                    }
                }
            }
            phase("SOURCE_PREWRITE_PASSED", SourceCorruption.entries.size)
        }

    @Test fun physicalSourceCorruptionsInSecondGateRollBackEveryTableAndSequence() =
        runBlocking {
            withTimeout(20_000) {
                for (hasPreparation in listOf(false, true)) {
                    for (corruption in SourceCorruption.entries) {
                        withOwnedDatabase(hasPreparation) { database, fixture ->
                            val before = snapshot(database)
                            var checks = 0
                            expectMalformedSource {
                                fixture.repository.saveSelection(fixture.seed, target) {
                                    if (++checks == 2) {
                                        assertTargetWritten(database.openHelper.writableDatabase)
                                        corruptSource(database.openHelper.writableDatabase, fixture.source.sessionId, corruption)
                                    }
                                    true
                                }
                            }
                            assertTrue("NPST_SOURCE_POST_GATE", checks == 2)
                            assertTrue("NPST_SOURCE_POST_ROLLBACK", before == snapshot(database))
                        }
                    }
                }
            }
            phase("SOURCE_POSTWRITE_ROLLBACK_PASSED", SourceCorruption.entries.size * 2)
        }

    @Test fun unfinishedRowWithoutActiveSlotRejectsBeforePreparationWrite() =
        runBlocking {
            withTimeout(20_000) {
                withOwnedDatabase { database, fixture ->
                    insertUnfinished(database.openHelper.writableDatabase, fixture.source.sessionId)
                    val before = snapshot(database)
                    var checks = 0
                    val result =
                        fixture.repository.saveSelection(fixture.seed, target) {
                            checks++
                            true
                        }
                    assertTrue("NPST_UNFINISHED_PRE_RESULT", result == NextPracticeSaveResult.SourceChanged)
                    assertTrue("NPST_UNFINISHED_PRE_GATE", checks == 1)
                    assertTrue("NPST_UNFINISHED_PRE_UNCHANGED", before == snapshot(database))
                }
            }
            phase("UNFINISHED_PREWRITE_PASSED", 1)
        }

    @Test fun unfinishedRowWithoutActiveSlotInSecondGateRollsBackEveryTableAndSequence() =
        runBlocking {
            withTimeout(20_000) {
                for (hasPreparation in listOf(false, true)) {
                    withOwnedDatabase(hasPreparation) { database, fixture ->
                        val before = snapshot(database)
                        var checks = 0
                        val result =
                            fixture.repository.saveSelection(fixture.seed, target) {
                                if (++checks == 2) {
                                    assertTargetWritten(database.openHelper.writableDatabase)
                                    insertUnfinished(database.openHelper.writableDatabase, fixture.source.sessionId)
                                }
                                true
                            }
                        assertTrue("NPST_UNFINISHED_POST_RESULT", result == NextPracticeSaveResult.SourceChanged)
                        assertTrue("NPST_UNFINISHED_POST_GATE", checks == 2)
                        assertTrue("NPST_UNFINISHED_POST_ROLLBACK", before == snapshot(database))
                    }
                }
            }
            phase("UNFINISHED_POSTWRITE_ROLLBACK_PASSED", 2)
        }

    @Test fun newerBlobSessionIdCannotBorrowAnOlderValidTextSource() =
        runBlocking {
            withTimeout(20_000) {
                withOwnedDatabase { database, fixture ->
                    insertBlobAlias(database.openHelper.writableDatabase, fixture.source.sessionId)
                    assertTrue(
                        "NPST_ALIAS_VALID_TEXT_CONTROL",
                        database.practiceSessionDao().countValidDeletionStorage(fixture.source.sessionId) == 1,
                    )
                    val latestRow =
                        database.openHelper.readableDatabase
                            .query("SELECT rowId FROM practice_session ORDER BY rowId DESC LIMIT 1")
                            .use { cursor ->
                                assertTrue("NPST_ALIAS_PHYSICAL_ROW", cursor.moveToFirst())
                                cursor.getLong(0)
                            }
                    val originalRow = checkNotNull(database.practiceSessionDao().find(fixture.source.sessionId)).rowId
                    assertTrue(
                        "NPST_ALIAS_VALID_ROW_CONTROL",
                        database.practiceSessionDao().countValidNextPracticeSourceStorage(originalRow, fixture.source.sessionId) == 1,
                    )
                    assertTrue(
                        "NPST_ALIAS_EXACT_ROW_REJECTED",
                        database.practiceSessionDao().countValidNextPracticeSourceStorage(latestRow, fixture.source.sessionId) == 0,
                    )
                    val before = snapshot(database)
                    var checks = 0
                    expectMalformedSource {
                        fixture.repository.saveSelection(fixture.seed, target) {
                            checks++
                            true
                        }
                    }
                    assertTrue("NPST_ALIAS_PRE_GATE", checks == 1)
                    assertTrue("NPST_ALIAS_PRE_UNCHANGED", before == snapshot(database))
                }
            }
            phase("BLOB_ALIAS_PREWRITE_PASSED", 1)
        }

    @Test fun newerBlobSessionIdInSecondGateRollsBackEveryTableAndSequence() =
        runBlocking {
            withTimeout(20_000) {
                for (hasPreparation in listOf(false, true)) {
                    withOwnedDatabase(hasPreparation) { database, fixture ->
                        val before = snapshot(database)
                        var checks = 0
                        expectMalformedSource {
                            fixture.repository.saveSelection(fixture.seed, target) {
                                if (++checks == 2) {
                                    assertTargetWritten(database.openHelper.writableDatabase)
                                    insertBlobAlias(database.openHelper.writableDatabase, fixture.source.sessionId)
                                }
                                true
                            }
                        }
                        assertTrue("NPST_ALIAS_POST_GATE", checks == 2)
                        assertTrue("NPST_ALIAS_POST_ROLLBACK", before == snapshot(database))
                    }
                }
            }
            phase("BLOB_ALIAS_POSTWRITE_ROLLBACK_PASSED", 2)
        }

    private suspend fun withOwnedDatabase(
        hasPreparation: Boolean = true,
        block: suspend (PreparationDatabase, Fixture) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("NPST_ISOLATED_PACKAGE", context.packageName == "io.github.hangillab.hangil.core.database.test")
        val database = Room.inMemoryDatabaseBuilder(context, PreparationDatabase::class.java).build()
        try {
            val catalog = LocalPracticeCatalog()
            val practice = (catalog.resolve(sourceSelection) as Preparation.Ready).practice
            val id = "native-next-source-${UUID.randomUUID()}"
            database.practiceSessionDao().insert(
                PracticeSessionEntity(
                    sessionId = id,
                    goalId = sourceSelection.goalId,
                    courseId = sourceSelection.courseId,
                    courseVersion = sourceSelection.courseVersion,
                    briefingVersion = sourceSelection.briefingVersion,
                    taskId = sourceSelection.taskId,
                    goalTitle = practice.goal.title,
                    courseTitle = practice.course.title,
                    taskTitle = practice.task.title,
                    courseReviewStatus = practice.course.reviewStatus.name,
                    startedAtEpochMillis = 1_000,
                    endedAtEpochMillis = 2_000,
                    activeSlot = null,
                ),
            )
            database.vehicleObservationDao().insert(VehicleObservationEntity.from(id, VehicleObservation(1_500, VehicleState())))
            database.sessionMemoDao().insert(SessionMemoEntity(id, "Synthetic native fixture", UUID.randomUUID().toString()))
            if (hasPreparation) {
                assertTrue("NPST_FIXTURE_PREPARATION", RoomPracticeRepository(database).saveSelection(original) { true })
            }
            val source = checkNotNull(database.practiceSessionDao().find(id)).session()
            val seed = checkNotNull(NextPracticeSeed.resolve(catalog, source))
            block(database, Fixture(catalog, source, seed, RoomNextPracticeRepository(database, catalog)))
            database.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
                assertTrue("NPST_FOREIGN_KEYS", cursor.count == 0)
            }
            assertTrue("NPST_SCHEMA_VERSION", database.openHelper.readableDatabase.version == 7)
        } finally {
            database.close()
        }
    }

    private suspend fun expectMalformedSource(action: suspend () -> NextPracticeSaveResult) {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue(
            "NPST_RAW_SOURCE_EXCEPTION",
            failure?.javaClass == IllegalStateException::class.java || failure?.javaClass == SQLiteException::class.java,
        )
    }

    private fun corruptSource(
        database: SupportSQLiteDatabase,
        sourceId: String,
        corruption: SourceCorruption,
    ) {
        database.execSQL(
            "UPDATE practice_session SET ${corruption.column} = ${corruption.expression} WHERE sessionId = ?",
            arrayOf(sourceId),
        )
        database
            .query(
                "SELECT typeof(${corruption.column}), ${corruption.column} FROM practice_session WHERE sessionId = ?",
                arrayOf(sourceId),
            ).use { cursor ->
                assertTrue("NPST_CORRUPTION_ROW", cursor.moveToFirst())
                assertTrue("NPST_CORRUPTION_TYPE", cursor.getString(0) == corruption.storageType)
                if (corruption.expectedValue == null) {
                    assertTrue("NPST_CORRUPTION_BLOB", cursor.getBlob(1).isNotEmpty())
                } else {
                    assertTrue("NPST_CORRUPTION_VALUE", cursor.getDouble(1) == corruption.expectedValue)
                }
                assertTrue("NPST_CORRUPTION_SINGLE", !cursor.moveToNext())
            }
    }

    private fun insertUnfinished(
        database: SupportSQLiteDatabase,
        sourceId: String,
    ) {
        insertCopy(database, sourceId, "native-next-unfinished-${UUID.randomUUID()}", "3000", "NULL", "NULL")
        database.query("SELECT COUNT(*) FROM practice_session WHERE endedAtEpochMillis IS NULL AND activeSlot IS NULL").use { cursor ->
            assertTrue("NPST_UNFINISHED_ROW", cursor.moveToFirst() && cursor.getLong(0) == 1L)
        }
    }

    private fun insertBlobAlias(
        database: SupportSQLiteDatabase,
        sourceId: String,
    ) {
        database.execSQL(
            "INSERT INTO practice_session(sessionId,goalId,courseId,courseVersion,briefingVersion,taskId," +
                "goalTitle,courseTitle,taskTitle,courseReviewStatus,startedAtEpochMillis,endedAtEpochMillis,activeSlot) " +
                "SELECT CAST(sessionId AS BLOB),goalId,courseId,courseVersion,briefingVersion,taskId," +
                "goalTitle,courseTitle,taskTitle,courseReviewStatus,startedAtEpochMillis,endedAtEpochMillis,activeSlot " +
                "FROM practice_session WHERE sessionId = ? AND typeof(sessionId) = 'text'",
            arrayOf(sourceId),
        )
        database
            .query(
                "SELECT rowId,typeof(sessionId),rowId > " +
                    "(SELECT rowId FROM practice_session WHERE sessionId = ? AND typeof(sessionId) = 'text')," +
                    "sessionId = CAST(? AS BLOB) FROM practice_session ORDER BY rowId DESC LIMIT 1",
                arrayOf(sourceId, sourceId),
            ).use { cursor ->
                assertTrue("NPST_ALIAS_ROW", cursor.moveToFirst() && cursor.getLong(0) > 0L)
                assertTrue("NPST_ALIAS_TYPE", cursor.getString(1) == "blob")
                assertTrue("NPST_ALIAS_NEWER", cursor.getLong(2) == 1L)
                assertTrue("NPST_ALIAS_BYTES", cursor.getLong(3) == 1L)
            }
        database
            .query("SELECT COUNT(*) FROM practice_session WHERE sessionId = ? AND typeof(sessionId) = 'text'", arrayOf(sourceId))
            .use { cursor -> assertTrue("NPST_ALIAS_ORIGINAL_TEXT", cursor.moveToFirst() && cursor.getLong(0) == 1L) }
    }

    private fun insertCopy(
        database: SupportSQLiteDatabase,
        sourceId: String,
        copyId: String,
        started: String,
        ended: String,
        activeSlot: String,
    ) {
        database.execSQL(
            "INSERT INTO practice_session(sessionId,goalId,courseId,courseVersion,briefingVersion,taskId," +
                "goalTitle,courseTitle,taskTitle,courseReviewStatus,startedAtEpochMillis,endedAtEpochMillis,activeSlot) " +
                "SELECT ?,goalId,courseId,courseVersion,briefingVersion,taskId,goalTitle,courseTitle,taskTitle," +
                "courseReviewStatus,$started,$ended,$activeSlot FROM practice_session WHERE sessionId = ?",
            arrayOf(copyId, sourceId),
        )
    }

    private fun assertTargetWritten(database: SupportSQLiteDatabase) {
        database.query("SELECT goalId,courseId,courseVersion,briefingVersion,taskId FROM preparation WHERE slot = 1").use { cursor ->
            assertTrue("NPST_POSTWRITE_ROW", cursor.moveToFirst())
            assertTrue(
                "NPST_POSTWRITE_SELECTION",
                cursor.getString(0) == target.goalId && cursor.getString(1) == target.courseId &&
                    cursor.getInt(2) == target.courseVersion && cursor.getInt(3) == target.briefingVersion &&
                    cursor.getString(4) == target.taskId,
            )
            assertTrue("NPST_POSTWRITE_SINGLE", !cursor.moveToNext())
        }
    }

    /** Compare physical SQLite types, row order, schema and every table including sqlite_sequence in memory. */
    private fun snapshot(
        database: PreparationDatabase,
        includePreparation: Boolean = true,
    ): Map<String, List<List<Pair<Int, Any?>>>> {
        val raw = database.openHelper.readableDatabase
        val names =
            raw.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        assertTrue("NPST_TABLE_BOUND", size < 32)
                        add(cursor.getString(0))
                    }
                }
            }
        assertTrue("NPST_SEQUENCE_PRESENT", "sqlite_sequence" in names)
        return buildMap {
            put(
                "schema",
                raw.query("SELECT type,name,tbl_name,rootpage,sql FROM sqlite_master ORDER BY type,name,tbl_name").use(::typedRows),
            )
            put("version", raw.query("PRAGMA user_version").use(::typedRows))
            for (name in names) {
                if (name == "preparation" && !includePreparation) continue
                val quoted = "\"" + name.replace("\"", "\"\"") + "\""
                put("table-info-$name", raw.query("PRAGMA table_info($quoted)").use(::typedRows))
                put("table-rows-$name", raw.query("SELECT rowid,* FROM $quoted ORDER BY rowid").use(::typedRows))
            }
        }
    }

    private fun typedRows(cursor: Cursor): List<List<Pair<Int, Any?>>> =
        buildList {
            assertTrue("NPST_COLUMN_BOUND", cursor.columnCount <= 64)
            while (cursor.moveToNext()) {
                assertTrue("NPST_ROW_BOUND", size < 256)
                add(
                    (0 until cursor.columnCount).map { column ->
                        val type = cursor.getType(column)
                        val value: Any? =
                            when (type) {
                                Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                                Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                                Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
                                else -> error("NPST_CELL_TYPE")
                            }
                        type to value
                    },
                )
            }
        }

    private fun phase(
        code: String,
        cases: Int,
    ) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("stream", "NEXT_PRACTICE_STORAGE_$code|cases=$cases\n") },
        )
    }

    private data class Fixture(
        val catalog: LocalPracticeCatalog,
        val source: PracticeSession,
        val seed: NextPracticeSeed,
        val repository: RoomNextPracticeRepository,
    )

    private enum class SourceCorruption(
        val column: String,
        val expression: String,
        val storageType: String,
        val expectedValue: Double?,
    ) {
        COURSE_VERSION("courseVersion", "1.5", "real", 1.5),
        BRIEFING_VERSION("briefingVersion", "4294967297", "integer", 4_294_967_297.0),
        START_TIME("startedAtEpochMillis", "1000.5", "real", 1_000.5),
        END_TIME("endedAtEpochMillis", "2000.5", "real", 2_000.5),
        TASK_TITLE_BLOB("taskTitle", "CAST(taskTitle AS BLOB)", "blob", null),
    }
}
