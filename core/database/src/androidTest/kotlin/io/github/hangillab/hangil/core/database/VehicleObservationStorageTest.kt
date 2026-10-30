package io.github.hangillab.hangil.core.database

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.TurnSignalState
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import io.github.hangillab.hangil.core.domain.VehicleObservationSource
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Runs only in the isolated library-test package. Fixture rows are not user SDK records. */
@RunWith(AndroidJUnit4::class)
class VehicleObservationStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun isolatedRoundTripPreservesStatesAndAppendOrder() =
        runBlocking {
            assertIsolatedPackage()
            val name = "observation-fixture-${UUID.randomUUID()}.db"
            check(!context.getDatabasePath(name).exists())
            val catalog = LocalPracticeCatalog()
            val selection = PracticeSelection("commute", "neighborhood", 1, 1, "sequence")
            val practice = (catalog.resolve(selection) as Preparation.Ready).practice
            val observations =
                listOf(
                    VehicleObservation(
                        2_000,
                        VehicleState(
                            ConnectionState.CONNECTED,
                            VehicleReading.Available(GearPosition.PARK),
                            VehicleReading.Available(TurnSignalState.OFF),
                            VehicleReading.Available(false),
                        ),
                    ),
                    VehicleObservation(
                        1_500,
                        VehicleState(
                            ConnectionState.CONNECTED,
                            VehicleReading.Available(GearPosition.DRIVE),
                            VehicleReading.Available(TurnSignalState.LEFT),
                            VehicleReading.Available(true),
                        ),
                    ),
                    VehicleObservation(1_500, VehicleState()),
                    VehicleObservation(
                        1_400,
                        VehicleState(
                            ConnectionState.UNAVAILABLE,
                            VehicleReading.Unsupported,
                            VehicleReading.Unsupported,
                            VehicleReading.Unsupported,
                        ),
                    ),
                    VehicleObservation(
                        1_300,
                        VehicleState(
                            ConnectionState.PERMISSION_DENIED,
                            VehicleReading.PermissionDenied,
                            VehicleReading.PermissionDenied,
                            VehicleReading.PermissionDenied,
                        ),
                    ),
                    VehicleObservation(
                        1_200,
                        VehicleState(ConnectionState.ERROR, VehicleReading.Error, VehicleReading.Error, VehicleReading.Error),
                    ),
                )
            try {
                val sessionId =
                    open(name).useDatabase { db ->
                        RoomPracticeRepository(db).saveSelection(selection) { true }
                        val sessions = RoomPracticeSessionRepository(db, catalog)
                        val session = (sessions.start(practice, 1_000) { true } as SessionStartResult.Started).session
                        val repository = RoomVehicleObservationRepository(db)
                        assertEquals(
                            VehicleObservationAppendResult.MissingSession,
                            repository.append("absent-fixture", observations.first()),
                        )
                        assertEquals(
                            VehicleObservationAppendResult.InvalidTime,
                            repository.append(session.sessionId, observations.first().copy(observedAtEpochMillis = 0)),
                        )
                        observations.forEach {
                            assertTrue(
                                repository.append(session.sessionId, it) is VehicleObservationAppendResult.Appended,
                            )
                        }
                        assertTrue(sessions.finish(session.sessionId, 3_000) { true } is SessionFinishResult.Finished)
                        assertEquals(
                            VehicleObservationAppendResult.FinishedSession,
                            repository.append(session.sessionId, observations.last()),
                        )
                        session.sessionId
                    }
                open(name).useDatabase { db ->
                    val stored = RoomVehicleObservationRepository(db).observe(sessionId).first()
                    assertEquals(observations, stored.map { it.observation })
                    assertTrue(stored.all { it.sessionId == sessionId && it.id > 0 })
                    assertTrue(stored.zipWithNext().all { (a, b) -> a.id < b.id })
                    assertTrue(stored.all { it.observation.source == VehicleObservationSource.SDK_STATE_OBSERVATION })
                    assertEquals(4, db.openHelper.writableDatabase.version)
                    db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM session_memo").use { cursor ->
                        check(cursor.moveToFirst())
                        assertEquals(0, cursor.getInt(0))
                    }
                }
                phase("ISOLATED_FIXTURE_ROUND_TRIP_PASSED")
            } finally {
                if (context.getDatabasePath(name).exists()) check(context.deleteDatabase(name))
            }
        }

    /** Host supplies a private copy after recording ownership. Default discovery does not use it. */
    @Test fun privateVersionTwoCopyMigratesWithoutChangingExistingRows() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowPrivateDatabaseCopy") == "true")
        assertIsolatedPackage()
        val file = context.getDatabasePath("observation-private-copy.db")
        check(file.isFile)
        val previous =
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(2, db.version)
                listOf(readRows(db, "preparation"), readRows(db, "practice_session"))
            }
        open(file.name).useDatabase { db ->
            val sqlite = db.openHelper.writableDatabase
            assertEquals(4, sqlite.version)
            // Do not put original row contents in assertion messages or instrumentation output.
            assertTrue(
                "Preparation rows must be preserved",
                previous[0] == sqlite.query("SELECT * FROM preparation ORDER BY rowid").use(::readRows),
            )
            assertTrue(
                "Session rows must be preserved",
                previous[1] == sqlite.query("SELECT * FROM practice_session ORDER BY rowid").use(::readRows),
            )
            sqlite.query("SELECT COUNT(*) FROM vehicle_observation").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            sqlite.query("SELECT COUNT(*) FROM session_memo").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            sqlite.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        }
        phase("PRIVATE_COPY_MIGRATION_PRESERVED_ROWS_NEW_TABLE_EMPTY")
    }

    private fun assertIsolatedPackage() {
        assertEquals("io.github.hangillab.hangil.core.database.test", context.packageName)
    }

    private fun open(name: String) =
        Room
            .databaseBuilder(context, PreparationDatabase::class.java, name)
            .addMigrations(PreparationMigration1To2, PreparationMigration2To3, PreparationMigration3To4)
            .build()

    private fun readRows(
        database: SQLiteDatabase,
        table: String,
    ) = database.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use(::readRows)

    private fun readRows(cursor: Cursor): List<List<Any?>> =
        buildList {
            while (cursor.moveToNext()) {
                add(
                    (0 until cursor.columnCount).map { column ->
                        when (cursor.getType(column)) {
                            Cursor.FIELD_TYPE_NULL -> null
                            Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                            Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                            Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                            else -> error("Unexpected legacy value type")
                        }
                    },
                )
            }
        }

    private inline fun <T> PreparationDatabase.useDatabase(block: (PreparationDatabase) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }

    private fun phase(name: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "OBSERVATION_STORAGE_$name\n") })
    }
}
