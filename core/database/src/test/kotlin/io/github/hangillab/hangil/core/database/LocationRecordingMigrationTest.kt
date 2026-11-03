package io.github.hangillab.hangil.core.database

import android.database.Cursor
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationRecordingMigrationTest {
    private val oldTables =
        listOf(
            "preparation",
            "practice_session",
            "vehicle_observation",
            "session_memo",
            "vehicle_recording_run",
            "course_completion_declaration",
            "sqlite_sequence",
        )

    @Test fun `schema six migration preserves every old value physical type order and sequence`() {
        val context = RuntimeEnvironment.getApplication()
        val name = "location-migration-${UUID.randomUUID()}.db"
        check(!context.getDatabasePath(name).exists())
        var primary: Throwable? = null
        try {
            val before = createSix(name)
            Room
                .databaseBuilder(
                    context,
                    PreparationDatabase::class.java,
                    name,
                ).addMigrations(PreparationMigration6To7)
                .build()
                .useDatabase { db ->
                    val sqlite = db.openHelper.writableDatabase
                    assertEquals(7, sqlite.version)
                    assertTrue("Original schema6 rows/types/order/sequence must remain exact", before == snapshot(sqlite))
                    for (table in listOf("location_recording_run", "location_recording_event")) {
                        sqlite.query("SELECT COUNT(*) FROM $table").use {
                            check(it.moveToFirst())
                            assertEquals(0L, it.getLong(0))
                        }
                    }
                    sqlite.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
                }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                if (context.getDatabasePath(name).exists()) check(context.deleteDatabase(name))
            } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
        }
    }

    private fun createSix(name: String): Map<String, List<List<Any?>>> {
        val schema =
            checkNotNull(javaClass.classLoader?.getResourceAsStream("io.github.hangillab.hangil.core.database.PreparationDatabase/6.json"))
                .bufferedReader()
                .use { JSONObject(it.readText()).getJSONObject("database") }
        val callback =
            object : SupportSQLiteOpenHelper.Callback(6) {
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
                    db.execSQL("INSERT INTO preparation VALUES(1, 'commute', 'neighborhood', 1, 1, 'sequence')")
                    db.execSQL(
                        "INSERT INTO practice_session VALUES(7, 'fixture', 'commute', 'neighborhood', 1, 1, 'sequence', " +
                            "'Goal', 'Course', 'Task', 'DEMO', 1000, 2000, NULL)",
                    )
                    db.execSQL(
                        "INSERT INTO vehicle_observation VALUES(9, 'fixture', 1500, 'SDK_STATE_OBSERVATION', 'CONNECTED', " +
                            "'AVAILABLE', 'PARK', 'UNKNOWN', NULL, 'AVAILABLE', 0)",
                    )
                    db.execSQL(
                        "INSERT INTO session_memo VALUES('fixture', ?, '00000000-0000-0000-0000-000000000001')",
                        arrayOf("  Fixture\n한글 é 😀  "),
                    )
                    db.execSQL(
                        "INSERT INTO vehicle_recording_run VALUES('old-run', 'fixture', 'old-process', 1100, NULL, 'INTERRUPTED', NULL)",
                    )
                    db.execSQL(
                        "INSERT INTO course_completion_declaration VALUES('fixture', 'commute', 'neighborhood', 1, 1, " +
                            "'sequence', 'Goal', 'Course', 'Task', 'DEMO', 1000, 2000, 'fixture-version', 2100, 'USER_DECLARATION')",
                    )
                    db.execSQL("UPDATE sqlite_sequence SET seq = 80 WHERE name = 'practice_session'")
                    db.execSQL("UPDATE sqlite_sequence SET seq = 900 WHERE name = 'vehicle_observation'")
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = error("Only production migration may upgrade fixture")
            }
        val configuration =
            SupportSQLiteOpenHelper.Configuration
                .builder(
                    RuntimeEnvironment.getApplication(),
                ).name(name)
                .callback(callback)
                .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).use { snapshot(it.writableDatabase) }
    }

    private inline fun <T> PreparationDatabase.useDatabase(block: (PreparationDatabase) -> T): T {
        var primary: Throwable? = null
        try {
            return block(this)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                close()
            } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
        }
    }

    private fun snapshot(db: SupportSQLiteDatabase): Map<String, List<List<Any?>>> =
        oldTables.associateWith { table ->
            db.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            (0 until cursor.columnCount).flatMap { column ->
                                val type = cursor.getType(column)
                                listOf(
                                    type,
                                    when (type) {
                                        Cursor.FIELD_TYPE_NULL -> null
                                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                                        Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                                        else -> error("Unexpected fixture blob")
                                    },
                                )
                            },
                        )
                    }
                }
            }
        }
}
