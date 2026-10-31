package io.github.hangillab.hangil.core.database

import android.database.Cursor
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VehicleObservationMigrationTest {
    private val selection = PracticeSelection("commute", "neighborhood", 1, 1, "sequence")

    @Test fun `version two preserves rows and creates empty observations at version three before reaching current version`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val name = "vehicle-observation-v2-migration-fixture.db"
            context.deleteDatabase(name)
            try {
                val before = createLegacy(name, 2)
                verifyVersionThreeBoundary(name, before)
                open(name).useDatabase { database ->
                    val sqlite = database.openHelper.writableDatabase
                    assertEquals(5, sqlite.version)
                    assertEquals(before.getValue("preparation"), rows(sqlite, "preparation", "slot"))
                    assertEquals(before.getValue("practice_session"), rows(sqlite, "practice_session", "rowId"))
                    assertEquals(emptyList<List<Any?>>(), rows(sqlite, "vehicle_observation", "id"))
                    assertTrue(rows(sqlite, "session_memo", "sessionId").isEmpty())
                    sqlite.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
                    val repository = RoomVehicleObservationRepository(database)
                    assertTrue(repository.observe("legacy-finished").first().isEmpty())
                    assertTrue(repository.observe("legacy-active").first().isEmpty())
                    assertEquals(
                        VehicleObservationAppendResult.FinishedSession,
                        repository.append("legacy-finished", VehicleObservation(5_000, VehicleState())),
                    )
                    val appended = repository.append("legacy-active", VehicleObservation(500, VehicleState()))
                    assertTrue(appended is VehicleObservationAppendResult.Appended)
                    assertEquals(before.getValue("preparation"), rows(sqlite, "preparation", "slot"))
                    assertEquals(before.getValue("practice_session"), rows(sqlite, "practice_session", "rowId"))
                }
            } finally {
                context.deleteDatabase(name)
            }
        }

    @Test fun `version one migrates through version two without losing preparation or fabricating observations`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val name = "vehicle-observation-v1-migration-fixture.db"
            context.deleteDatabase(name)
            try {
                val before = createLegacy(name, 1)
                open(name).useDatabase { database ->
                    val sqlite = database.openHelper.writableDatabase
                    assertEquals(5, sqlite.version)
                    assertEquals(before.getValue("preparation"), rows(sqlite, "preparation", "slot"))
                    assertTrue(rows(sqlite, "practice_session", "rowId").isEmpty())
                    assertTrue(rows(sqlite, "vehicle_observation", "id").isEmpty())
                    assertTrue(rows(sqlite, "session_memo", "sessionId").isEmpty())
                    val catalog = LocalPracticeCatalog()
                    val practice = (catalog.resolve(selection) as Preparation.Ready).practice
                    val started = RoomPracticeSessionRepository(database, catalog).start(practice, 1_000) { true }
                    val session = (started as SessionStartResult.Started).session
                    val repository = RoomVehicleObservationRepository(database)
                    val appended = repository.append(session.sessionId, VehicleObservation(2_000, VehicleState()))
                    assertTrue(appended is VehicleObservationAppendResult.Appended)
                    assertEquals(1, repository.observe(session.sessionId).first().size)
                    assertEquals(selection, RoomPracticeRepository(database).observeSelection().first())
                }
            } finally {
                context.deleteDatabase(name)
            }
        }

    private fun open(name: String) =
        Room
            .databaseBuilder(RuntimeEnvironment.getApplication(), PreparationDatabase::class.java, name)
            .addMigrations(PreparationMigration1To2, PreparationMigration2To3, PreparationMigration3To4, PreparationMigration4To5)
            .build()

    /** Keep the original v2 to v3 contract explicit although the production Room target is v4. */
    private fun verifyVersionThreeBoundary(
        name: String,
        before: Map<String, List<List<Any?>>>,
    ) {
        val callback =
            object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) = error("The version two fixture must already exist")

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) {
                    assertEquals(2, oldVersion)
                    assertEquals(3, newVersion)
                    PreparationMigration2To3.migrate(db)
                }
            }
        val configuration =
            SupportSQLiteOpenHelper.Configuration
                .builder(RuntimeEnvironment.getApplication())
                .name(name)
                .callback(callback)
                .build()
        FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            val sqlite = helper.writableDatabase
            assertEquals(3, sqlite.version)
            assertEquals(before.getValue("preparation"), rows(sqlite, "preparation", "slot"))
            assertEquals(before.getValue("practice_session"), rows(sqlite, "practice_session", "rowId"))
            assertTrue(rows(sqlite, "vehicle_observation", "id").isEmpty())
            sqlite.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        }
    }

    private fun createLegacy(
        name: String,
        version: Int,
    ): Map<String, List<List<Any?>>> {
        val context = RuntimeEnvironment.getApplication()
        val resource = "io.github.hangillab.hangil.core.database.PreparationDatabase/$version.json"
        val schema =
            checkNotNull(javaClass.classLoader?.getResourceAsStream(resource)).bufferedReader().use {
                JSONObject(it.readText()).getJSONObject("database")
            }
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
                    db.execSQL("INSERT INTO preparation VALUES(1, ?, ?, ?, ?, ?)", arrayOf("commute", "neighborhood", 1, 1, "sequence"))
                    if (version == 2) {
                        insertLegacySession(db, 7, "legacy-finished", 2_000)
                        insertLegacySession(db, 12, "legacy-active", null)
                    }
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = error("The fixture must be opened by the production Room migrations")
            }
        val configuration =
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(name)
                .callback(callback)
                .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).use { helper ->
            val sqlite = helper.writableDatabase
            assertEquals(version, sqlite.version)
            buildMap {
                put("preparation", rows(sqlite, "preparation", "slot"))
                if (version == 2) put("practice_session", rows(sqlite, "practice_session", "rowId"))
            }
        }
    }

    private fun insertLegacySession(
        database: SupportSQLiteDatabase,
        rowId: Long,
        sessionId: String,
        endedAt: Long?,
    ) {
        database.execSQL(
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

    private fun rows(
        database: SupportSQLiteDatabase,
        table: String,
        order: String,
    ): List<List<Any?>> =
        database.query("SELECT * FROM `$table` ORDER BY `$order`").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        (0 until cursor.columnCount).map { index ->
                            when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                                else -> error("Unexpected fixture column type")
                            }
                        },
                    )
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
