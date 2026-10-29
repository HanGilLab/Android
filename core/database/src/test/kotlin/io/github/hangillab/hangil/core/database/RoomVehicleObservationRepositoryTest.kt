package io.github.hangillab.hangil.core.database

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.withTransaction
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.TurnSignalState
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import io.github.hangillab.hangil.core.domain.VehicleObservationSource
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomVehicleObservationRepositoryTest {
    private val sessionId = "observation-fixture-session"

    private fun memoryDatabase() =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PreparationDatabase::class.java).build()

    @Test fun `all reading variants and connection states round trip without inventing values`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seedSession(database)
                val repository = RoomVehicleObservationRepository(database)
                val states =
                    ConnectionState.entries.map { VehicleState(connection = it) } +
                        listOf(
                            VehicleState(
                                ConnectionState.CONNECTED,
                                VehicleReading.Unsupported,
                                VehicleReading.Unsupported,
                                VehicleReading.Unsupported,
                            ),
                            VehicleState(
                                ConnectionState.PERMISSION_DENIED,
                                VehicleReading.PermissionDenied,
                                VehicleReading.PermissionDenied,
                                VehicleReading.PermissionDenied,
                            ),
                            VehicleState(ConnectionState.ERROR, VehicleReading.Error, VehicleReading.Error, VehicleReading.Error),
                        ) +
                        GearPosition.entries.mapIndexed { index, gear ->
                            VehicleState(
                                ConnectionState.CONNECTED,
                                VehicleReading.Available(gear),
                                VehicleReading.Available(TurnSignalState.entries[index % TurnSignalState.entries.size]),
                                VehicleReading.Available(index % 2 == 0),
                            )
                        }
                val observations = states.mapIndexed { index, state -> VehicleObservation(2_000L + index, state) }
                val expected =
                    observations.map { observation ->
                        val result = repository.append(sessionId, observation) as VehicleObservationAppendResult.Appended
                        SessionVehicleObservation(result.id, sessionId, observation)
                    }
                assertEquals(expected, repository.observe(sessionId).first())
                assertTrue(expected.all { it.observation.source == VehicleObservationSource.SDK_STATE_OBSERVATION })
                val rows =
                    database
                        .vehicleObservationDao()
                        .observe(sessionId)
                        .first()
                        .map { it.entity }
                assertTrue(rows.all { it.source == "SDK_STATE_OBSERVATION" })
                assertTrue(rows.filter { it.gearStatus != "AVAILABLE" }.all { it.gearValue == null })
                assertTrue(rows.filter { it.turnStatus != "AVAILABLE" }.all { it.turnValue == null })
                assertTrue(rows.filter { it.brakeStatus != "AVAILABLE" }.all { it.brakeValue == null })
            }
        }

    @Test fun `false brake and OFF indicator remain distinct from missing unsupported denied and failed readings`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seedSession(database)
                val repository = RoomVehicleObservationRepository(database)
                val actualOff =
                    VehicleState(
                        ConnectionState.CONNECTED,
                        VehicleReading.Available(GearPosition.PARK),
                        VehicleReading.Available(TurnSignalState.OFF),
                        VehicleReading.Available(false),
                    )
                repository.append(sessionId, VehicleObservation(2_000, VehicleState()))
                repository.append(sessionId, VehicleObservation(2_001, actualOff))
                val stored = repository.observe(sessionId).first()
                assertEquals(VehicleState(), stored[0].observation.state)
                assertEquals(actualOff, stored[1].observation.state)
                val rows =
                    database
                        .vehicleObservationDao()
                        .observe(sessionId)
                        .first()
                        .map { it.entity }
                assertEquals("UNKNOWN", rows[0].brakeStatus)
                assertEquals(null, rows[0].brakeValue)
                assertEquals("AVAILABLE", rows[1].brakeStatus)
                assertEquals(0L, rows[1].brakeValue)
                assertEquals("OFF", rows[1].turnValue)
            }
        }

    @Test fun `append IDs preserve duplicate times and positive wall clock regressions in insertion order`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seedSession(database)
                val repository = RoomVehicleObservationRepository(database)
                val times = listOf(4_000L, 500L, 500L, 3_000L)
                val ids =
                    times.map { time ->
                        val result = repository.append(sessionId, VehicleObservation(time, VehicleState()))
                        (result as VehicleObservationAppendResult.Appended).id
                    }
                assertTrue(ids.all { it > 0 })
                assertEquals(ids.distinct(), ids)
                assertEquals(ids.sorted(), ids)
                val stored = repository.observe(sessionId).first()
                assertEquals(ids, stored.map { it.id })
                assertEquals(times, stored.map { it.observation.observedAtEpochMillis })
            }
        }

    @Test fun `missing finished and nonpositive requests are explicit and never insert an observation`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val repository = RoomVehicleObservationRepository(database)
                val observation = VehicleObservation(2_000, VehicleState())
                assertEquals(VehicleObservationAppendResult.MissingSession, repository.append(sessionId, observation))
                seedSession(database, finished = true)
                assertEquals(VehicleObservationAppendResult.FinishedSession, repository.append(sessionId, observation))
                val activeId = "invalid-time-active-fixture"
                seedSession(database, activeId)
                for (time in listOf(0L, -1L, Long.MIN_VALUE)) {
                    assertEquals(
                        VehicleObservationAppendResult.InvalidTime,
                        repository.append(activeId, observation.copy(observedAtEpochMillis = time)),
                    )
                }
                assertTrue(repository.observe(sessionId).first().isEmpty())
                assertTrue(repository.observe(activeId).first().isEmpty())
                assertTrue(repository.observe("missing-fixture").first().isEmpty())
            }
        }

    @Test fun `foreign key rejects orphan rows and deleting one session cascades only its observations`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val repository = RoomVehicleObservationRepository(database)
                val observation = VehicleObservation(2_000, VehicleState())
                try {
                    database.vehicleObservationDao().insert(VehicleObservationEntity.from(sessionId, observation))
                    fail("The database must reject an observation without a parent session")
                } catch (_: SQLiteException) {
                    assertTrue(repository.observe(sessionId).first().isEmpty())
                }
                seedSession(database)
                repository.append(sessionId, observation)
                assertEquals(1, database.practiceSessionDao().finish(sessionId, 3_000))
                val secondId = "other-observation-fixture"
                seedSession(database, secondId)
                repository.append(secondId, observation)
                assertEquals(1, repository.observe(sessionId).first().size)
                assertEquals(1, repository.observe(secondId).first().size)
                database.openHelper.writableDatabase.execSQL("DELETE FROM practice_session WHERE sessionId = ?", arrayOf(sessionId))
                assertTrue(repository.observe(sessionId).first().isEmpty())
                assertEquals(1, repository.observe(secondId).first().size)
                database.openHelper.writableDatabase
                    .query("PRAGMA foreign_key_check")
                    .use { assertEquals(0, it.count) }
            }
        }

    @Test fun `unknown source status enum and malformed value combinations fail rather than normalize`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seedSession(database)
                val valid =
                    VehicleObservationEntity.from(
                        sessionId,
                        VehicleObservation(
                            2_000,
                            VehicleState(
                                ConnectionState.CONNECTED,
                                VehicleReading.Available(GearPosition.PARK),
                                VehicleReading.Available(TurnSignalState.OFF),
                                VehicleReading.Available(false),
                            ),
                        ),
                    )
                val corrupt =
                    listOf(
                        valid.copy(source = "DEMO"),
                        valid.copy(connection = "FUTURE_CONNECTION"),
                        valid.copy(gearStatus = "FUTURE_STATUS"),
                        valid.copy(gearStatus = "UNKNOWN"),
                        valid.copy(gearValue = null),
                        valid.copy(gearValue = "FUTURE_GEAR"),
                        valid.copy(turnStatus = "UNSUPPORTED"),
                        valid.copy(turnValue = null),
                        valid.copy(turnValue = "FUTURE_TURN"),
                        valid.copy(brakeStatus = "PERMISSION_DENIED"),
                        valid.copy(brakeValue = null),
                        valid.copy(brakeValue = 2L),
                        valid.copy(brakeValue = -1L),
                        valid.copy(brakeValue = 1L shl 32),
                        valid.copy(observedAtEpochMillis = 0),
                        valid.copy(id = -1),
                    )
                for (entity in corrupt) {
                    database.openHelper.writableDatabase.execSQL("DELETE FROM vehicle_observation")
                    database.vehicleObservationDao().insert(entity)
                    val failure = runCatching { RoomVehicleObservationRepository(database).observe(sessionId).first() }.exceptionOrNull()
                    assertTrue(
                        "Invalid stored rows must fail decoding",
                        failure is IllegalArgumentException || failure is IllegalStateException,
                    )
                }
            }
        }

    @Test fun `cancelling an enclosing transaction rolls back an appended row`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seedSession(database)
                val repository = RoomVehicleObservationRepository(database)
                val inserted = CompletableDeferred<Unit>()
                val writer =
                    launch(Dispatchers.Default) {
                        database.withTransaction {
                            val appended = repository.append(sessionId, VehicleObservation(2_000, VehicleState()))
                            assertTrue(appended is VehicleObservationAppendResult.Appended)
                            inserted.complete(Unit)
                            awaitCancellation()
                        }
                    }
                withTimeout(10_000) { inserted.await() }
                writer.cancelAndJoin()
                assertTrue(repository.observe(sessionId).first().isEmpty())
                assertTrue(checkNotNull(database.practiceSessionDao().find(sessionId)).session().isActive)
            }
        }

    @Test fun `noninteger SQLite storage never becomes a normal brake value or observation time`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seedSession(database)
                val repository = RoomVehicleObservationRepository(database)
                val observation =
                    VehicleObservation(2_000, VehicleState(ConnectionState.CONNECTED, brakePressed = VehicleReading.Available(false)))
                val appended = repository.append(sessionId, observation) as VehicleObservationAppendResult.Appended
                val sqlite = database.openHelper.writableDatabase
                val corruptBrakes = listOf<Pair<Any, String>>(0.9 to "real", 1.5 to "real", "invalid" to "text")
                for ((value, storageType) in corruptBrakes) {
                    sqlite.execSQL("UPDATE vehicle_observation SET brakeValue = ? WHERE id = ?", arrayOf(value, appended.id))
                    sqlite.query("SELECT typeof(brakeValue) FROM vehicle_observation").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(storageType, cursor.getString(0))
                    }
                    val failure = runCatching { repository.observe(sessionId).first() }.exceptionOrNull()
                    assertTrue("Noninteger brake storage must fail before domain restoration", failure is IllegalStateException)
                }
                sqlite.execSQL(
                    "UPDATE vehicle_observation SET brakeValue = 0, observedAtEpochMillis = ? WHERE id = ?",
                    arrayOf(1.5, appended.id),
                )
                sqlite.query("SELECT typeof(observedAtEpochMillis) FROM vehicle_observation").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("real", cursor.getString(0))
                }
                val failure = runCatching { repository.observe(sessionId).first() }.exceptionOrNull()
                assertTrue("Noninteger observation time must fail before domain restoration", failure is IllegalStateException)
            }
        }

    @Test fun `SQLite insert failure propagates and rolls back without returning success`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                seedSession(database)
                database.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER reject_observation_fixture AFTER INSERT ON vehicle_observation " +
                        "BEGIN SELECT RAISE(ABORT, 'fixture insert failure'); END",
                )
                try {
                    RoomVehicleObservationRepository(database).append(sessionId, VehicleObservation(2_000, VehicleState()))
                    fail("SQLite failure must not produce an Appended result")
                } catch (_: SQLiteException) {
                    assertTrue(
                        database
                            .vehicleObservationDao()
                            .observe(sessionId)
                            .first()
                            .isEmpty(),
                    )
                }
            }
        }

    @Test fun `concurrent finish and append have serializable outcomes without orphan observations`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val observations = RoomVehicleObservationRepository(database)
                val sessions = RoomPracticeSessionRepository(database, LocalPracticeCatalog())
                repeat(8) { index ->
                    val id = "concurrent-observation-fixture-$index"
                    seedSession(database, id)
                    val start = CompletableDeferred<Unit>()
                    val append =
                        async(Dispatchers.Default) {
                            start.await()
                            observations.append(id, VehicleObservation(1_500, VehicleState()))
                        }
                    val finish =
                        async(Dispatchers.Default) {
                            start.await()
                            sessions.finish(id, 2_000) { true }
                        }
                    start.complete(Unit)
                    val appendResult = append.await()
                    assertTrue(finish.await() is SessionFinishResult.Finished)
                    val rows = observations.observe(id).first()
                    when (appendResult) {
                        is VehicleObservationAppendResult.Appended -> assertEquals(listOf(appendResult.id), rows.map { it.id })
                        VehicleObservationAppendResult.FinishedSession -> assertTrue(rows.isEmpty())
                        else -> fail("An existing active or concurrently finished session must have a serializable result")
                    }
                    assertEquals(
                        VehicleObservationAppendResult.FinishedSession,
                        observations.append(id, VehicleObservation(2_500, VehicleState())),
                    )
                }
                database.openHelper.writableDatabase
                    .query("PRAGMA foreign_key_check")
                    .use { assertEquals(0, it.count) }
            }
        }

    @Test fun `persisted observations retain source readings and append order after database reopen`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val name = "vehicle-observation-reopen-fixture.db"
            context.deleteDatabase(name)

            fun open() = Room.databaseBuilder(context, PreparationDatabase::class.java, name).build()
            try {
                val expected =
                    open().useDatabase { database ->
                        seedSession(database)
                        val repository = RoomVehicleObservationRepository(database)
                        repository.append(
                            sessionId,
                            VehicleObservation(2_000, VehicleState(ConnectionState.ERROR, brakePressed = VehicleReading.Error)),
                        )
                        repository.append(
                            sessionId,
                            VehicleObservation(
                                500,
                                VehicleState(ConnectionState.CONNECTED, brakePressed = VehicleReading.Available(false)),
                            ),
                        )
                        repository.observe(sessionId).first()
                    }
                open().useDatabase { database ->
                    assertEquals(expected, RoomVehicleObservationRepository(database).observe(sessionId).first())
                }
            } finally {
                context.deleteDatabase(name)
            }
        }

    private suspend fun seedSession(
        database: PreparationDatabase,
        id: String = sessionId,
        finished: Boolean = false,
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
                endedAtEpochMillis = if (finished) 2_000 else null,
                activeSlot = if (finished) null else 1,
            ),
        )
    }

    private inline fun <T> PreparationDatabase.useDatabase(block: (PreparationDatabase) -> T): T {
        try {
            return block(this)
        } finally {
            close()
        }
    }
}
