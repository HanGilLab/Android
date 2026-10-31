package io.github.hangillab.hangil.core.database

import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import io.github.hangillab.hangil.core.domain.VehicleState
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
class SessionMemoMigrationTest {
    @Test fun `version three migration preserves all prior fields storage types IDs sequences and creates no memos`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val name = "session-memo-v3-preservation-fixture.db"
            context.deleteDatabase(name)
            try {
                val before = createVersionThree(name)
                open(name).useDatabase { database ->
                    val sqlite = database.openHelper.writableDatabase
                    assertEquals(6, sqlite.version)
                    assertEquals(before, snapshot(sqlite))
                    assertTrue(rows(sqlite, "SELECT * FROM session_memo ORDER BY sessionId").values.isEmpty())
                    sqlite.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
                    val observations = RoomVehicleObservationRepository(database)
                    assertEquals(1, observations.observe("fixture-finished").first().size)
                    assertEquals(2, observations.observe("fixture-active").first().size)

                    // The preexisting sequence is intentionally ahead of every extant observation.
                    // Adding a new table must not reset it or reuse an old observation identity.
                    val appended = observations.append("fixture-active", VehicleObservation(300, VehicleState()))
                    assertTrue(appended is VehicleObservationAppendResult.Appended)
                    assertEquals(901L, (appended as VehicleObservationAppendResult.Appended).id)
                    assertEquals(before.getValue("preparation"), rows(sqlite, "SELECT * FROM preparation ORDER BY slot"))
                    assertEquals(before.getValue("practice_session"), rows(sqlite, "SELECT * FROM practice_session ORDER BY rowId"))
                }
            } finally {
                context.deleteDatabase(name)
            }
        }

    @Test fun `migrated memo table rejects orphan and duplicate rows and cascades only its linked fixture session`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val name = "session-memo-v3-constraints-fixture.db"
            context.deleteDatabase(name)
            try {
                val before = createVersionThree(name)
                open(name).useDatabase { database ->
                    val sqlite = database.openHelper.writableDatabase
                    assertEquals(before, snapshot(sqlite))
                    assertConstraintFailure {
                        sqlite.execSQL(
                            "INSERT INTO session_memo(sessionId, text, version) VALUES (?, ?, ?)",
                            arrayOf("missing-fixture", "Fixture memo", "11111111-1111-4111-8111-111111111111"),
                        )
                    }
                    sqlite.execSQL(
                        "INSERT INTO session_memo(sessionId, text, version) VALUES (?, ?, ?)",
                        arrayOf("fixture-finished", "Fixture memo", "11111111-1111-4111-8111-111111111111"),
                    )
                    val stored = rows(sqlite, "SELECT * FROM session_memo ORDER BY sessionId")
                    assertConstraintFailure {
                        sqlite.execSQL(
                            "INSERT INTO session_memo(sessionId, text, version) VALUES (?, ?, ?)",
                            arrayOf("fixture-finished", "Must not replace", "22222222-2222-4222-8222-222222222222"),
                        )
                    }
                    assertEquals(stored, rows(sqlite, "SELECT * FROM session_memo ORDER BY sessionId"))
                    sqlite.execSQL("DELETE FROM practice_session WHERE sessionId = ?", arrayOf("fixture-finished"))
                    assertTrue(rows(sqlite, "SELECT * FROM session_memo ORDER BY sessionId").values.isEmpty())
                    assertEquals(2, rows(sqlite, "SELECT * FROM vehicle_observation ORDER BY id").values.size)
                    assertEquals(before.getValue("preparation"), rows(sqlite, "SELECT * FROM preparation ORDER BY slot"))
                    sqlite.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
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

    private fun createVersionThree(name: String): Map<String, TableData> {
        val context = RuntimeEnvironment.getApplication()
        val schema =
            checkNotNull(
                javaClass.classLoader?.getResourceAsStream("io.github.hangillab.hangil.core.database.PreparationDatabase/3.json"),
            ).bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val callback =
            object : SupportSQLiteOpenHelper.Callback(3) {
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
                    db.execSQL(
                        "INSERT INTO preparation VALUES(1, ?, ?, ?, ?, ?)",
                        arrayOf("commute", "neighborhood", 1, 1, "sequence"),
                    )
                    insertSession(db, 7, "fixture-finished", 2_000)
                    insertSession(db, 12, "fixture-active", null)
                    db.execSQL(
                        "INSERT INTO vehicle_observation VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            3L,
                            "fixture-finished",
                            2_000L,
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
                    db.execSQL(
                        "INSERT INTO vehicle_observation VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf(9L, "fixture-active", 500L, "SDK_STATE_OBSERVATION", "ERROR", "ERROR", null, "ERROR", null, "ERROR", null),
                    )
                    db.execSQL(
                        "INSERT INTO vehicle_observation VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            14L,
                            "fixture-active",
                            500L,
                            "SDK_STATE_OBSERVATION",
                            "CONNECTED",
                            "AVAILABLE",
                            "DRIVE",
                            "AVAILABLE",
                            "LEFT",
                            "AVAILABLE",
                            1L,
                        ),
                    )
                    db.execSQL("UPDATE sqlite_sequence SET seq = 80 WHERE name = 'practice_session'")
                    db.execSQL("UPDATE sqlite_sequence SET seq = 900 WHERE name = 'vehicle_observation'")
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = error("The fixture must be migrated by the production Room database")
            }
        val configuration =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(name)
                .callback(callback)
                .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            val sqlite = helper.writableDatabase
            assertEquals(3, sqlite.version)
            snapshot(sqlite)
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
                if (endedAt == null) 1 else null,
            ),
        )
    }

    private fun snapshot(db: SupportSQLiteDatabase) =
        mapOf(
            "preparation" to rows(db, "SELECT * FROM preparation ORDER BY slot"),
            "practice_session" to rows(db, "SELECT * FROM practice_session ORDER BY rowId"),
            "vehicle_observation" to rows(db, "SELECT * FROM vehicle_observation ORDER BY id"),
            "sqlite_sequence" to rows(db, "SELECT * FROM sqlite_sequence ORDER BY name"),
        )

    private fun rows(
        db: SupportSQLiteDatabase,
        query: String,
    ): TableData =
        db.query(query).use { cursor ->
            TableData(
                cursor.columnNames.toList(),
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            (0 until cursor.columnCount).map { column ->
                                cursor.getType(column) to
                                    when (cursor.getType(column)) {
                                        Cursor.FIELD_TYPE_NULL -> null
                                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                                        Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
                                        else -> error("Unexpected fixture storage type")
                                    }
                            },
                        )
                    }
                },
            )
        }

    private fun assertConstraintFailure(action: () -> Unit) {
        try {
            action()
            fail("Invalid fixture reference or duplicate memo must be rejected")
        } catch (_: SQLiteConstraintException) {
            // The constraint failure must leave the existing row untouched.
        }
    }

    private inline fun <T> PreparationDatabase.useDatabase(block: (PreparationDatabase) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }

    private data class TableData(
        val columns: List<String>,
        val values: List<List<Pair<Int, Any?>>>,
    )
}
