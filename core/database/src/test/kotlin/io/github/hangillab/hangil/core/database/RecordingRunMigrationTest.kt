package io.github.hangillab.hangil.core.database

import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingRunMigrationTest {
    @Test fun `every supported legacy version reaches six without rewriting old values types order or sequences`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            for (version in 1..5) {
                val name = "recording-migration-fixture-v$version.db"
                context.deleteDatabase(name)
                try {
                    val before = createLegacy(name, version)
                    val database = open(name)
                    try {
                        val sqlite = database.openHelper.writableDatabase
                        assertEquals(6, sqlite.version)
                        for ((table, original) in before) assertEquals(original, readTable(sqlite, table))
                        assertTrue(RoomRecordingRunRepository(database).observe("active-fixture").first().isEmpty())
                        assertEquals(null, RoomCourseCompletionRepository(database).observe("finished-fixture").first())
                        sqlite.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
                    } finally {
                        database.close()
                    }
                } finally {
                    context.deleteDatabase(name)
                }
            }
        }

    @Test fun `migrated run table has unique open slots and cascades only the referenced parent`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val name = "recording-migration-constraints-fixture.db"
            context.deleteDatabase(name)
            try {
                createLegacy(name, 4)
                val database = open(name)
                try {
                    val sqlite = database.openHelper.writableDatabase
                    val before =
                        mapOf(
                            "preparation" to readTable(sqlite, "preparation"),
                            "sqlite_sequence" to readTable(sqlite, "sqlite_sequence"),
                        )
                    insertRun(sqlite, "first-fixture", "active-fixture", "active-fixture")
                    expectConstraint { insertRun(sqlite, "second-fixture", "active-fixture", "active-fixture") }
                    expectConstraint { insertRun(sqlite, "orphan-fixture", "missing-fixture", "missing-fixture") }
                    insertRun(sqlite, "retained-fixture", "finished-fixture", null)
                    sqlite.execSQL("DELETE FROM practice_session WHERE sessionId = ?", arrayOf("active-fixture"))
                    assertTrue(RoomRecordingRunRepository(database).observe("active-fixture").first().isEmpty())
                    assertEquals(1, RoomRecordingRunRepository(database).observe("finished-fixture").first().size)
                    for ((table, original) in before) assertEquals(original, readTable(sqlite, table))
                    sqlite.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
                } finally {
                    database.close()
                }
            } finally {
                context.deleteDatabase(name)
            }
        }

    private fun open(name: String) =
        Room
            .databaseBuilder(RuntimeEnvironment.getApplication(), PreparationDatabase::class.java, name)
            .addMigrations(
                PreparationMigration1To2,
                PreparationMigration2To3,
                PreparationMigration3To4,
                PreparationMigration4To5,
                PreparationMigration5To6,
            ).build()

    private fun createLegacy(
        name: String,
        version: Int,
    ): Map<String, TableRows> {
        val schema =
            checkNotNull(
                javaClass.classLoader?.getResourceAsStream("io.github.hangillab.hangil.core.database.PreparationDatabase/$version.json"),
            ).bufferedReader()
                .use { JSONObject(it.readText()).getJSONObject("database") }
        val callback =
            object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (index in 0 until entities.length()) {
                        val entity = entities.getJSONObject(index)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices")
                        if (indices != null) {
                            for (item in 0 until indices.length()) {
                                db.execSQL(indices.getJSONObject(item).getString("createSql").replace('$' + "{TABLE_NAME}", table))
                            }
                        }
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
                    db.execSQL("INSERT INTO preparation VALUES (1, 'commute', 'neighborhood', 1, 1, 'sequence')")
                    if (version >= 2) {
                        insertSession(db, 7, "finished-fixture", 3_000)
                        insertSession(db, 12, "active-fixture", null)
                        db.execSQL("UPDATE sqlite_sequence SET seq = 80 WHERE name = 'practice_session'")
                    }
                    if (version >= 3) {
                        db.execSQL(
                            "INSERT INTO vehicle_observation VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            arrayOf(
                                3L,
                                "finished-fixture",
                                2_000L,
                                "SDK_STATE_OBSERVATION",
                                "ERROR",
                                "ERROR",
                                null,
                                "ERROR",
                                null,
                                "ERROR",
                                null,
                            ),
                        )
                        db.execSQL(
                            "INSERT INTO vehicle_observation VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            arrayOf(
                                14L,
                                "active-fixture",
                                1_500L,
                                "SDK_STATE_OBSERVATION",
                                "CONNECTED",
                                "AVAILABLE",
                                "PARK",
                                "AVAILABLE",
                                "OFF",
                                "AVAILABLE",
                                0L,
                            ),
                        )
                        db.execSQL("UPDATE sqlite_sequence SET seq = 900 WHERE name = 'vehicle_observation'")
                    }
                    if (version >= 4) {
                        db.execSQL(
                            "INSERT INTO session_memo VALUES (?, ?, ?)",
                            arrayOf("finished-fixture", "Fixture memo", "11111111-1111-4111-8111-111111111111"),
                        )
                    }
                    if (version >= 5) {
                        insertRun(db, "preserved-run-first", "finished-fixture", null)
                        insertRun(db, "preserved-run-second", "finished-fixture", null)
                        db.execSQL(
                            "UPDATE vehicle_recording_run SET status = 'INTERRUPTED', endedAtEpochMillis = NULL WHERE runId = ?",
                            arrayOf("preserved-run-second"),
                        )
                    }
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = error("The production migrations must upgrade the fixture")
            }
        val configuration =
            SupportSQLiteOpenHelper.Configuration
                .builder(
                    RuntimeEnvironment.getApplication(),
                ).name(name)
                .callback(callback)
                .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            val sqlite = helper.writableDatabase
            val tables =
                buildList {
                    add("preparation")
                    if (version >= 2) {
                        add("practice_session")
                        add("sqlite_sequence")
                    }
                    if (version >= 3) add("vehicle_observation")
                    if (version >= 4) add("session_memo")
                    if (version >= 5) add("vehicle_recording_run")
                }
            tables.associateWith { readTable(sqlite, it) }
        }
    }

    private fun insertSession(
        db: SupportSQLiteDatabase,
        rowId: Long,
        sessionId: String,
        endedAt: Long?,
    ) {
        db.execSQL(
            "INSERT INTO practice_session VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                rowId,
                sessionId,
                "commute",
                "neighborhood",
                1,
                1,
                "sequence",
                "Fixture goal",
                "Fixture course",
                "Fixture task",
                "DEMO",
                1_000L,
                endedAt,
                if (endedAt ==
                    null
                ) {
                    1
                } else {
                    null
                },
            ),
        )
    }

    private fun insertRun(
        db: SupportSQLiteDatabase,
        runId: String,
        sessionId: String,
        slot: String?,
    ) {
        db.execSQL(
            "INSERT INTO vehicle_recording_run VALUES (?, ?, ?, ?, ?, ?, ?)",
            arrayOf(
                runId,
                sessionId,
                "process-fixture",
                2_000L,
                if (slot ==
                    null
                ) {
                    2_500L
                } else {
                    null
                },
                if (slot == null) "STOPPED" else "OPEN",
                slot,
            ),
        )
    }

    private fun readTable(
        db: SupportSQLiteDatabase,
        table: String,
    ): TableRows {
        val order =
            when (table) {
                "preparation" -> "slot"
                "practice_session" -> "rowId"
                "vehicle_observation" -> "id"
                "session_memo" -> "sessionId"
                "vehicle_recording_run" -> "rowid"
                "sqlite_sequence" -> "name"
                else -> error("Unknown fixture table")
            }
        return db.query("SELECT * FROM $table ORDER BY $order").use { cursor ->
            TableRows(
                cursor.columnNames.toList(),
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            (0 until cursor.columnCount).map { column ->
                                val value: Any? =
                                    when (cursor.getType(column)) {
                                        Cursor.FIELD_TYPE_NULL -> null
                                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                                        Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
                                        else -> error("Unexpected fixture storage type")
                                    }
                                cursor.getType(column) to value
                            },
                        )
                    }
                },
            )
        }
    }

    private fun expectConstraint(action: () -> Unit) {
        try {
            action()
            fail("Invalid fixture parent or duplicate open slot must be rejected")
        } catch (_: SQLiteConstraintException) {
            // The migrated SQLite constraints reject the invalid write.
        }
    }

    private data class TableRows(
        val columns: List<String>,
        val values: List<List<Pair<Int, Any?>>>,
    )
}
