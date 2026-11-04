package io.github.hangillab.hangil.core.database

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.hangillab.hangil.core.domain.NextPracticeSaveResult
import io.github.hangillab.hangil.core.domain.NextPracticeSeed
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeCourse
import io.github.hangillab.hangil.core.domain.PracticeGoal
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomNextPracticeRepositoryTest {
    private val sourceSelection = PracticeSelection("commute", "neighborhood", 1, 1, "sequence")
    private val target = sourceSelection.copy(taskId = "arrival")
    private val original = PracticeSelection("shopping", "intersections", 1, 1, "review")

    @Test fun `valid seed atomically replaces only the preparation with exact integer versions`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val fixture = fixture(database)
                val otherRows = tables(database, includePreparation = false)
                assertEquals(NextPracticeSaveResult.Saved, fixture.repository.saveSelection(fixture.seed, target) { true })
                assertEquals(target, database.preparationDao().current()?.selection())
                assertEquals(otherRows, tables(database, includePreparation = false))
                database.openHelper.readableDatabase
                    .query(
                        "SELECT slot, typeof(courseVersion), typeof(briefingVersion) FROM preparation",
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(1, cursor.getInt(0))
                        assertEquals("integer", cursor.getString(1))
                        assertEquals("integer", cursor.getString(2))
                        assertTrue(!cursor.moveToNext())
                    }
            }
        }

    @Test fun `choosing the source task and repeating a valid save do not start or append anything`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val fixture = fixture(database)
                val before = tables(database, includePreparation = false)
                repeat(2) {
                    assertEquals(
                        NextPracticeSaveResult.Saved,
                        fixture.repository.saveSelection(fixture.seed, sourceSelection) { true },
                    )
                }
                assertEquals(sourceSelection, database.preparationDao().current()?.selection())
                assertEquals(before, tables(database, includePreparation = false))
                assertEquals(null, database.practiceSessionDao().active())
            }
        }

    @Test fun `denied live permission has priority and leaves every existing row unchanged`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val fixture = fixture(database)
                val before = tables(database)
                assertEquals(
                    NextPracticeSaveResult.Blocked,
                    fixture.repository.saveSelection(fixture.seed, target.copy(taskId = "missing")) { false },
                )
                assertEquals(before, tables(database))
            }
        }

    @Test fun `post write denial rolls back an existing or previously absent preparation`() =
        runBlocking {
            for (hasPreparation in listOf(false, true)) {
                memoryDatabase().useDatabase { database ->
                    val fixture = fixture(database, hasPreparation)
                    val before = tables(database)
                    var checks = 0
                    assertEquals(
                        NextPracticeSaveResult.Blocked,
                        fixture.repository.saveSelection(fixture.seed, target) { ++checks == 1 },
                    )
                    assertEquals(2, checks)
                    assertEquals(before, tables(database))
                }
            }
        }

    @Test fun `missing changed latest or active sources are rejected before overwriting preparation`() =
        runBlocking {
            val changes: List<(SupportSQLiteDatabase) -> Unit> =
                listOf(
                    { it.execSQL("DELETE FROM practice_session WHERE sessionId = ?", arrayOf("next-source")) },
                    { it.execSQL("UPDATE practice_session SET taskTitle = 'changed title' WHERE sessionId = ?", arrayOf("next-source")) },
                    { cloneSource(it, "later", 100, 200) },
                    { cloneSource(it, "active", 3_000, null) },
                )
            for (change in changes) {
                memoryDatabase().useDatabase { database ->
                    val fixture = fixture(database)
                    change(database.openHelper.writableDatabase)
                    val before = tables(database)
                    assertEquals(NextPracticeSaveResult.SourceChanged, fixture.repository.saveSelection(fixture.seed, target) { true })
                    assertEquals(before, tables(database))
                }
            }
        }

    @Test fun `post write source changes reject and roll back both the upsert and controlled mutation`() =
        runBlocking {
            // Controlled same-transaction mutations exercise the post-check. They are not claims that
            // another SQLite writer can bypass the transaction's normal serialization.
            val changes: List<(SupportSQLiteDatabase) -> Unit> =
                listOf(
                    { it.execSQL("DELETE FROM practice_session WHERE sessionId = ?", arrayOf("next-source")) },
                    { it.execSQL("UPDATE practice_session SET goalTitle = 'changed goal' WHERE sessionId = ?", arrayOf("next-source")) },
                    { it.execSQL("UPDATE practice_session SET taskId = 'arrival' WHERE sessionId = ?", arrayOf("next-source")) },
                    { it.execSQL("UPDATE practice_session SET endedAtEpochMillis = 2500 WHERE sessionId = ?", arrayOf("next-source")) },
                    { cloneSource(it, "later", 100, 200) },
                    { cloneSource(it, "active", 3_000, null) },
                )
            for (change in changes) {
                memoryDatabase().useDatabase { database ->
                    val fixture = fixture(database)
                    val before = tables(database)
                    var checks = 0
                    assertEquals(
                        NextPracticeSaveResult.SourceChanged,
                        fixture.repository.saveSelection(fixture.seed, target) {
                            if (++checks == 2) change(database.openHelper.writableDatabase)
                            true
                        },
                    )
                    assertEquals(2, checks)
                    assertEquals(before, tables(database))
                }
            }
        }

    @Test fun `latest source follows created row order even when wall clock button times regress`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val fixture = fixture(database)
                cloneSource(database.openHelper.writableDatabase, "newer-created", 100, 200)
                assertEquals(NextPracticeSaveResult.SourceChanged, fixture.repository.saveSelection(fixture.seed, target) { true })
                val latest = checkNotNull(database.practiceSessionDao().latestFinished()).session()
                assertEquals("newer-created", latest.sessionId)
                val seed = checkNotNull(NextPracticeSeed.resolve(fixture.catalog, latest))
                assertEquals(NextPracticeSaveResult.Saved, fixture.repository.saveSelection(seed, target) { true })
                assertEquals(target, database.preparationDao().current()?.selection())
            }
        }

    @Test fun `target cannot change goal course either version or select an absent task`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val fixture = fixture(database)
                val before = tables(database)
                listOf(
                    target.copy(goalId = "shopping"),
                    target.copy(courseId = "intersections"),
                    target.copy(courseVersion = 2),
                    target.copy(briefingVersion = 2),
                    target.copy(taskId = "missing"),
                    target.copy(taskId = ""),
                ).forEach {
                    assertEquals(NextPracticeSaveResult.Unavailable, fixture.repository.saveSelection(fixture.seed, it) { true })
                    assertEquals(before, tables(database))
                }
            }
        }

    @Test fun `a copied seed with a forged practice is independently rejected by fresh resolution`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val fixture = fixture(database)
                val before = tables(database)
                val forged =
                    fixture.seed.copy(
                        practice =
                            fixture.seed.practice.copy(
                                course =
                                    fixture.seed.practice.course
                                        .copy(summary = "not the current catalog"),
                            ),
                    )
                assertEquals(NextPracticeSaveResult.Unavailable, fixture.repository.saveSelection(forged, target) { true })
                assertEquals(before, tables(database))
            }
        }

    @Test fun `catalog changes since display reject even when IDs versions and stored titles still match`() =
        runBlocking {
            for (change in catalogEdits()) {
                memoryDatabase().useDatabase { database ->
                    val fixture = fixture(database)
                    val before = tables(database)
                    change(fixture.catalog)
                    assertEquals(NextPracticeSaveResult.Unavailable, fixture.repository.saveSelection(fixture.seed, target) { true })
                    assertEquals(before, tables(database))
                }
            }
        }

    @Test fun `post write catalog changes roll back preparation rather than returning a rejected committed write`() =
        runBlocking {
            for (change in catalogEdits()) {
                memoryDatabase().useDatabase { database ->
                    val fixture = fixture(database)
                    val before = tables(database)
                    var checks = 0
                    assertEquals(
                        NextPracticeSaveResult.Unavailable,
                        fixture.repository.saveSelection(fixture.seed, target) {
                            if (++checks == 2) change(fixture.catalog)
                            true
                        },
                    )
                    assertEquals(2, checks)
                    assertEquals(before, tables(database))
                }
            }
        }

    @Test fun `blocked then changed source then unavailable is the deterministic rejection priority`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val fixture = fixture(database)
                cloneSource(database.openHelper.writableDatabase, "active", 3_000, null)
                fixture.catalog.courses = emptyList()
                val before = tables(database)
                assertEquals(NextPracticeSaveResult.Blocked, fixture.repository.saveSelection(fixture.seed, target) { false })
                assertEquals(NextPracticeSaveResult.SourceChanged, fixture.repository.saveSelection(fixture.seed, target) { true })
                assertEquals(before, tables(database))
                database.openHelper.writableDatabase.execSQL("DELETE FROM practice_session WHERE sessionId = 'active'")
                val afterDeletion = tables(database)
                assertEquals(NextPracticeSaveResult.Unavailable, fixture.repository.saveSelection(fixture.seed, target) { true })
                assertEquals(afterDeletion, tables(database))
            }
        }

    @Test fun `real SQLite write failure propagates and preserves preparation and all other data`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val fixture = fixture(database)
                val before = tables(database)
                for (operation in listOf("INSERT", "UPDATE")) {
                    database.openHelper.writableDatabase.execSQL(
                        "CREATE TRIGGER reject_next_$operation AFTER $operation ON preparation " +
                            "BEGIN SELECT RAISE(ABORT, 'fixture next write failure'); END",
                    )
                }
                try {
                    fixture.repository.saveSelection(fixture.seed, target) { true }
                    fail("SQLite failure must propagate")
                } catch (_: SQLiteException) {
                    assertEquals(before, tables(database))
                }
            }
        }

    @Test fun `post write predicate exception propagates unchanged and rolls back the upsert`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val fixture = fixture(database)
                val before = tables(database)
                val failure = IOException("fixture predicate failure")
                var checks = 0
                try {
                    fixture.repository.saveSelection(fixture.seed, target) { if (++checks == 2) throw failure else true }
                    fail("Predicate exception must propagate")
                } catch (observed: IOException) {
                    assertOriginalCause(failure, observed)
                }
                assertEquals(before, tables(database))
            }
        }

    @Test fun `post write catalog exception is not disguised as unavailable or saved`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val fixture = fixture(database)
                val before = tables(database)
                val failure = IOException("fixture catalog read failure")
                var checks = 0
                try {
                    fixture.repository.saveSelection(fixture.seed, target) {
                        if (++checks == 2) fixture.catalog.failure = failure
                        true
                    }
                    fail("Catalog failure must propagate")
                } catch (observed: IOException) {
                    assertOriginalCause(failure, observed)
                }
                assertEquals(before, tables(database))
            }
        }

    @Test fun `post write cancellation keeps its original exception and rolls back preparation`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val fixture = fixture(database)
                val before = tables(database)
                val cancelled = CancellationException("fixture cancelled write")
                var checks = 0
                try {
                    fixture.repository.saveSelection(fixture.seed, target) { if (++checks == 2) throw cancelled else true }
                    fail("Cancellation must propagate")
                } catch (observed: CancellationException) {
                    assertOriginalCause(cancelled, observed)
                }
                assertEquals(before, tables(database))
                assertEquals(NextPracticeSaveResult.Saved, fixture.repository.saveSelection(fixture.seed, target) { true })
            }
        }

    @Test fun `cancellation requested by an otherwise true predicate cannot commit a successful preparation`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val fixture = fixture(database)
                val before = tables(database)
                val cancelled = CancellationException("fixture owner cancellation")
                val attempt =
                    async(Dispatchers.Default) {
                        val owner = checkNotNull(currentCoroutineContext()[Job])
                        var checks = 0
                        fixture.repository.saveSelection(fixture.seed, target) {
                            if (++checks == 2) owner.cancel(cancelled)
                            true
                        }
                    }
                try {
                    attempt.await()
                    fail("Cancelled owner must not receive Saved")
                } catch (observed: CancellationException) {
                    assertOriginalCause(cancelled, observed)
                }
                assertEquals(before, tables(database))
                assertEquals(NextPracticeSaveResult.Saved, fixture.repository.saveSelection(fixture.seed, target) { true })
            }
        }

    @Test fun `concurrent session start and next save are serialized by the same database transaction`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val fixture = fixture(database)
                RoomPracticeRepository(database).saveSelection(sourceSelection) { true }
                val gate = CompletableDeferred<Unit>()
                val next =
                    async(Dispatchers.Default) {
                        gate.await()
                        fixture.repository.saveSelection(fixture.seed, target) { true }
                    }
                val start =
                    async(Dispatchers.Default) {
                        gate.await()
                        RoomPracticeSessionRepository(database, fixture.catalog).start(fixture.seed.practice, 3_000) { true }
                    }
                gate.complete(Unit)
                val nextResult = withTimeout(10_000) { next.await() }
                val startResult = withTimeout(10_000) { start.await() }
                if (nextResult == NextPracticeSaveResult.Saved) {
                    assertEquals(SessionStartResult.PreparationChanged, startResult)
                    assertEquals(null, database.practiceSessionDao().active())
                    assertEquals(target, database.preparationDao().current()?.selection())
                } else {
                    assertEquals(NextPracticeSaveResult.SourceChanged, nextResult)
                    assertTrue(startResult is SessionStartResult.Started)
                    assertEquals(
                        1,
                        database
                            .practiceSessionDao()
                            .observe()
                            .first()
                            .count { it.session().isActive },
                    )
                    assertEquals(sourceSelection, database.preparationDao().current()?.selection())
                }
                assertEquals(fixture.source, database.practiceSessionDao().find(fixture.source.sessionId)?.session())
                assertEquals(
                    1,
                    database
                        .vehicleObservationDao()
                        .observe(fixture.source.sessionId)
                        .first()
                        .size,
                )
                assertTrue(database.sessionMemoDao().find(fixture.source.sessionId) != null)
            }
        }

    @Test fun `concurrent next choices leave exactly one complete selection and preserve source data`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val fixture = fixture(database)
                val before = tables(database, includePreparation = false)
                val gate = CompletableDeferred<Unit>()
                val first =
                    async(Dispatchers.Default) {
                        gate.await()
                        fixture.repository.saveSelection(fixture.seed, target) { true }
                    }
                val second =
                    async(Dispatchers.Default) {
                        gate.await()
                        fixture.repository.saveSelection(fixture.seed, sourceSelection) { true }
                    }
                gate.complete(Unit)
                assertEquals(NextPracticeSaveResult.Saved, withTimeout(10_000) { first.await() })
                assertEquals(NextPracticeSaveResult.Saved, withTimeout(10_000) { second.await() })
                assertTrue(database.preparationDao().current()?.selection() in listOf(target, sourceSelection))
                assertEquals(before, tables(database, includePreparation = false))
                database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM preparation").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(1, it.getInt(0))
                }
            }
        }

    @Test fun `saved selection and source observations and memo survive database close and reopen`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val name = "next-practice-" + UUID.randomUUID() + ".db"

            fun open() = Room.databaseBuilder(context, PreparationDatabase::class.java, name).build()
            try {
                val expected =
                    open().useDatabase { database ->
                        val fixture = fixture(database)
                        assertEquals(NextPracticeSaveResult.Saved, fixture.repository.saveSelection(fixture.seed, target) { true })
                        tables(database)
                    }
                open().useDatabase { database ->
                    assertEquals(expected, tables(database))
                    assertEquals(target, RoomPracticeRepository(database).observeSelection().first())
                    val latest = checkNotNull(database.practiceSessionDao().latestFinished()).session()
                    val seed = checkNotNull(NextPracticeSeed.resolve(LocalPracticeCatalog(), latest))
                    assertEquals(
                        NextPracticeSaveResult.Saved,
                        RoomNextPracticeRepository(database, LocalPracticeCatalog()).saveSelection(seed, target) { true },
                    )
                    assertEquals(expected, tables(database))
                    assertEquals(7, database.openHelper.readableDatabase.version)
                }
            } finally {
                context.deleteDatabase(name)
            }
        }

    @Test fun `corrupt stored source status propagates its read failure without overwriting preparation`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val fixture = fixture(database)
                database.openHelper.writableDatabase.execSQL("UPDATE practice_session SET courseReviewStatus = 'INVALID'")
                val before = tables(database)
                try {
                    fixture.repository.saveSelection(fixture.seed, target) { true }
                    fail("Invalid stored status must not become a normal result")
                } catch (_: IllegalArgumentException) {
                    assertEquals(before, tables(database))
                }
            }
        }

    /** Accept only documented coroutine recovery copies; repository wrapping or a lost cause fails. */
    @Test fun `real course version pre write rejects and preserves all typed rows`() = checkSourceStorage("courseVersion = 1.5", false)

    @Test fun `real course version post write rejects and rolls back all typed rows`() = checkSourceStorage("courseVersion = 1.5", true)

    @Test fun `overflow briefing version pre write rejects and preserves all typed rows`() =
        checkSourceStorage("briefingVersion = 4294967297", false)

    @Test fun `overflow briefing version post write rejects and rolls back all typed rows`() =
        checkSourceStorage("briefingVersion = 4294967297", true)

    @Test fun `real start time pre write rejects and preserves all typed rows`() =
        checkSourceStorage(
            "startedAtEpochMillis = 1000.5",
            false,
        )

    @Test fun `real start time post write rejects and rolls back all typed rows`() =
        checkSourceStorage(
            "startedAtEpochMillis = 1000.5",
            true,
        )

    @Test fun `real end time pre write rejects and preserves all typed rows`() = checkSourceStorage("endedAtEpochMillis = 2000.5", false)

    @Test fun `real end time post write rejects and rolls back all typed rows`() = checkSourceStorage("endedAtEpochMillis = 2000.5", true)

    @Test fun `blob task title pre write rejects and preserves all typed rows`() =
        checkSourceStorage("taskTitle = CAST(taskTitle AS BLOB)", false)

    @Test fun `blob task title post write rejects and rolls back all typed rows`() =
        checkSourceStorage("taskTitle = CAST(taskTitle AS BLOB)", true)

    @Test fun `different latest blob row cannot borrow text source validation before write`() = checkBlobIdentityAlias(false)

    @Test fun `different latest blob row cannot borrow text source validation after write`() = checkBlobIdentityAlias(true)

    @Test fun `unfinished row without active slot rejects before write`() = checkUnfinishedSource(false)

    @Test fun `unfinished row without active slot rejects after write`() = checkUnfinishedSource(true)

    @Test fun `history selection uses the older full source while latest selection still rejects it`() =
        runBlocking {
            for (hasPreparation in listOf(false, true)) {
                memoryDatabase().useDatabase { database ->
                    val fixture = historyFixture(database, hasPreparation)
                    val before = physicalTables(database)
                    assertEquals(NextPracticeSaveResult.SourceChanged, fixture.repository.saveSelection(fixture.seed, target) { true })
                    assertEquals(before, physicalTables(database))
                    assertEquals(NextPracticeSaveResult.Saved, fixture.repository.saveHistorySelection(fixture.seed, target) { true })
                    assertEquals(target, database.preparationDao().current()?.selection())
                    assertEquals(before - "preparation", physicalTables(database) - "preparation")
                    assertEquals(fixture.source, database.practiceSessionDao().find(fixture.source.sessionId)?.session())
                }
            }
        }

    @Test fun `history selection does not follow an unrelated ended insertion during the post check`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val fixture = historyFixture(database)
                val before = physicalTables(database)
                var expectedOtherRows: Map<String, List<List<String?>>>? = null
                var checks = 0
                assertEquals(
                    NextPracticeSaveResult.Saved,
                    fixture.repository.saveHistorySelection(fixture.seed, target) {
                        if (++checks == 2) {
                            assertTargetWritten(database)
                            cloneSource(database.openHelper.writableDatabase, "history-post-newer", 300, 400)
                            expectedOtherRows = physicalTables(database) - "preparation"
                        }
                        true
                    },
                )
                assertEquals(2, checks)
                assertEquals(target, database.preparationDao().current()?.selection())
                assertEquals(expectedOtherRows, physicalTables(database) - "preparation")
                assertEquals(before["vehicle_observation"], physicalTables(database)["vehicle_observation"])
                assertEquals(before["session_memo"], physicalTables(database)["session_memo"])
                assertEquals(fixture.source, database.practiceSessionDao().find(fixture.source.sessionId)?.session())
                assertEquals("history-post-newer", database.practiceSessionDao().latestFinished()?.sessionId)
            }
        }

    @Test fun `history selection rejects wrong missing active and changed same ID sources without replacing them`() =
        runBlocking {
            val changes: List<(SupportSQLiteDatabase) -> Unit> =
                listOf(
                    { it.execSQL("DELETE FROM practice_session WHERE sessionId = 'next-source'") },
                    { it.execSQL("UPDATE practice_session SET goalTitle = 'changed goal' WHERE sessionId = 'next-source'") },
                    { it.execSQL("UPDATE practice_session SET taskId = 'arrival' WHERE sessionId = 'next-source'") },
                    { it.execSQL("UPDATE practice_session SET endedAtEpochMillis = 2500 WHERE sessionId = 'next-source'") },
                    { it.execSQL("UPDATE practice_session SET endedAtEpochMillis = NULL, activeSlot = 1 WHERE sessionId = 'next-source'") },
                    {
                        cloneSource(it, "replacement-holder", 3_000, 4_000)
                        it.execSQL("DELETE FROM practice_session WHERE sessionId = 'next-source'")
                        it.execSQL("UPDATE practice_session SET sessionId = 'next-source' WHERE sessionId = 'replacement-holder'")
                    },
                )
            for (hasPreparation in listOf(false, true)) {
                for (afterWrite in listOf(false, true)) {
                    for (change in changes) {
                        memoryDatabase().useDatabase { database ->
                            val fixture = historyFixture(database, hasPreparation)
                            if (!afterWrite) change(database.openHelper.writableDatabase)
                            val before = physicalTables(database)
                            var checks = 0
                            assertEquals(
                                NextPracticeSaveResult.SourceChanged,
                                fixture.repository.saveHistorySelection(fixture.seed, target) {
                                    if (++checks == 2 && afterWrite) {
                                        assertTargetWritten(database)
                                        change(database.openHelper.writableDatabase)
                                    }
                                    true
                                },
                            )
                            assertEquals(if (afterWrite) 2 else 1, checks)
                            assertEquals(before, physicalTables(database))
                        }
                    }
                }
                memoryDatabase().useDatabase { database ->
                    val fixture = historyFixture(database, hasPreparation)
                    val before = physicalTables(database)
                    for (source in listOf(fixture.source.copy(sessionId = "missing"), fixture.source.copy(taskTitle = "wrong receipt"))) {
                        val seed = fixture.seed.copy(source = source)
                        assertEquals(NextPracticeSaveResult.SourceChanged, fixture.repository.saveHistorySelection(seed, target) { true })
                        assertEquals(before, physicalTables(database))
                    }
                }
            }
        }

    @Test fun `history selection rejects an unfinished row without an active slot before and after the write`() =
        runBlocking {
            for (hasPreparation in listOf(false, true)) {
                for (afterWrite in listOf(false, true)) {
                    memoryDatabase().useDatabase { database ->
                        val fixture = historyFixture(database, hasPreparation)

                        fun corrupt() {
                            val sqlite = database.openHelper.writableDatabase
                            cloneSource(sqlite, "hidden-unfinished", 3_000, null)
                            sqlite.execSQL("UPDATE practice_session SET activeSlot = NULL WHERE sessionId = 'hidden-unfinished'")
                            sqlite
                                .query(
                                    "SELECT endedAtEpochMillis, activeSlot FROM practice_session WHERE sessionId = 'hidden-unfinished'",
                                ).use {
                                    assertTrue(it.moveToFirst())
                                    assertTrue(it.isNull(0) && it.isNull(1))
                                }
                        }
                        if (!afterWrite) {
                            corrupt()
                            assertEquals(null, database.practiceSessionDao().active())
                            assertEquals(1L, database.practiceSessionDao().unfinishedCount())
                        }
                        val before = physicalTables(database)
                        var checks = 0
                        assertEquals(
                            NextPracticeSaveResult.SourceChanged,
                            fixture.repository.saveHistorySelection(fixture.seed, target) {
                                if (++checks == 2 && afterWrite) {
                                    assertTargetWritten(database)
                                    corrupt()
                                }
                                true
                            },
                        )
                        assertEquals(if (afterWrite) 2 else 1, checks)
                        assertEquals(before, physicalTables(database))
                    }
                }
            }
        }

    @Test fun `history selection checks physical storage of the selected older row before and after writing`() =
        runBlocking {
            val corruptions =
                listOf(
                    Triple("courseVersion", "1.5", "real"),
                    Triple("briefingVersion", "4294967297", "integer"),
                    Triple("startedAtEpochMillis", "1000.5", "real"),
                    Triple("endedAtEpochMillis", "2000.5", "real"),
                    Triple("taskTitle", "CAST(taskTitle AS BLOB)", "blob"),
                )
            for (hasPreparation in listOf(false, true)) {
                for (afterWrite in listOf(false, true)) {
                    for ((column, value, storageType) in corruptions) {
                        memoryDatabase().useDatabase { database ->
                            val fixture = historyFixture(database, hasPreparation)

                            fun corrupt() {
                                val sqlite = database.openHelper.writableDatabase
                                sqlite.execSQL("UPDATE practice_session SET $column = $value WHERE sessionId = 'next-source'")
                                sqlite.query("SELECT typeof($column), $column FROM practice_session WHERE sessionId = 'next-source'").use {
                                    assertTrue(it.moveToFirst())
                                    assertEquals(storageType, it.getString(0))
                                    when (storageType) {
                                        "real" -> assertEquals(value.toDouble(), it.getDouble(1), 0.0)
                                        "integer" -> assertEquals(value.toLong(), it.getLong(1))
                                        "blob" ->
                                            assertEquals(
                                                fixture.source.taskTitle
                                                    .toByteArray()
                                                    .toList(),
                                                it.getBlob(1).toList(),
                                            )
                                    }
                                }
                            }
                            if (!afterWrite) corrupt()
                            val before = physicalTables(database)
                            var checks = 0
                            expectInvalidSourceStorage {
                                fixture.repository.saveHistorySelection(fixture.seed, target) {
                                    if (++checks == 2 && afterWrite) {
                                        assertTargetWritten(database)
                                        corrupt()
                                    }
                                    true
                                }
                            }
                            assertEquals(if (afterWrite) 2 else 1, checks)
                            assertEquals(before, physicalTables(database))
                            assertEquals(
                                1,
                                database.practiceSessionDao().countValidNextPracticeSourceStorage(
                                    checkNotNull(database.practiceSessionDao().find("history-newer")).rowId,
                                    "history-newer",
                                ),
                            )
                        }
                    }
                }
            }
        }

    @Test fun `history selection binds the TEXT row and never substitutes its BLOB identity alias`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val fixture = historyFixture(database)
                insertBlobIdentityAlias(database)
                val before = physicalTables(database)
                assertEquals(NextPracticeSaveResult.Saved, fixture.repository.saveHistorySelection(fixture.seed, target) { true })
                assertEquals(before - "preparation", physicalTables(database) - "preparation")
                val afterHistory = physicalTables(database)
                expectInvalidSourceStorage { fixture.repository.saveSelection(fixture.seed, target) { true } }
                assertEquals(afterHistory, physicalTables(database))
                database.openHelper.writableDatabase.execSQL("DELETE FROM practice_session WHERE sessionId = 'next-source'")
                val withoutTextSource = physicalTables(database)
                assertEquals(
                    NextPracticeSaveResult.SourceChanged,
                    fixture.repository.saveHistorySelection(fixture.seed, sourceSelection) { true },
                )
                assertEquals(withoutTextSource, physicalTables(database))
            }
        }

    @Test fun `history permission denial has priority and post denial rolls back absent or existing preparation`() =
        runBlocking {
            for (hasPreparation in listOf(false, true)) {
                memoryDatabase().useDatabase { database ->
                    val fixture = historyFixture(database, hasPreparation)
                    val before = physicalTables(database)
                    assertEquals(
                        NextPracticeSaveResult.Blocked,
                        fixture.repository.saveHistorySelection(fixture.seed, target.copy(taskId = "missing")) { false },
                    )
                    assertEquals(before, physicalTables(database))
                    var checks = 0
                    assertEquals(
                        NextPracticeSaveResult.Blocked,
                        fixture.repository.saveHistorySelection(fixture.seed, target) {
                            if (++checks == 2) assertTargetWritten(database)
                            checks == 1
                        },
                    )
                    assertEquals(2, checks)
                    assertEquals(before, physicalTables(database))
                }
            }
        }

    @Test fun `history catalog or target changes never commit a partially validated preparation`() =
        runBlocking {
            for (hasPreparation in listOf(false, true)) {
                for (afterWrite in listOf(false, true)) {
                    for (change in catalogEdits()) {
                        memoryDatabase().useDatabase { database ->
                            val fixture = historyFixture(database, hasPreparation)
                            if (!afterWrite) change(fixture.catalog)
                            val before = physicalTables(database)
                            var checks = 0
                            assertEquals(
                                NextPracticeSaveResult.Unavailable,
                                fixture.repository.saveHistorySelection(fixture.seed, target) {
                                    if (++checks == 2 && afterWrite) {
                                        assertTargetWritten(database)
                                        change(fixture.catalog)
                                    }
                                    true
                                },
                            )
                            assertEquals(if (afterWrite) 2 else 1, checks)
                            assertEquals(before, physicalTables(database))
                        }
                    }
                }
                memoryDatabase().useDatabase { database ->
                    val fixture = historyFixture(database, hasPreparation)
                    val before = physicalTables(database)
                    for (selection in listOf(
                        target.copy(goalId = "shopping"),
                        target.copy(courseVersion = 2),
                        target.copy(taskId = "missing"),
                    )) {
                        assertEquals(
                            NextPracticeSaveResult.Unavailable,
                            fixture.repository.saveHistorySelection(fixture.seed, selection) { true },
                        )
                        assertEquals(before, physicalTables(database))
                    }
                }
            }
        }

    @Test fun `history post write predicate catalog and cancellation failures retain their causes and roll back`() =
        runBlocking {
            for (hasPreparation in listOf(false, true)) {
                for (kind in listOf("predicate", "catalog", "cancellation")) {
                    memoryDatabase().useDatabase { database ->
                        val fixture = historyFixture(database, hasPreparation)
                        val before = physicalTables(database)
                        val failure =
                            if (kind ==
                                "cancellation"
                            ) {
                                CancellationException("history cancellation")
                            } else {
                                IOException("history $kind failure")
                            }
                        var checks = 0
                        try {
                            fixture.repository.saveHistorySelection(fixture.seed, target) {
                                if (++checks == 2) {
                                    assertTargetWritten(database)
                                    if (kind == "catalog") fixture.catalog.failure = failure else throw failure
                                }
                                true
                            }
                            fail("History failures must propagate instead of returning a normal result")
                        } catch (observed: Exception) {
                            assertOriginalCause(failure, observed)
                        }
                        assertEquals(2, checks)
                        assertEquals(before, physicalTables(database))
                    }
                }
            }
        }

    @Test fun `history owner cancellation from a true post predicate cannot commit`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val fixture = historyFixture(database)
                val before = physicalTables(database)
                val cancelled = CancellationException("history owner cancellation")
                val attempt =
                    async(Dispatchers.Default) {
                        val owner = checkNotNull(currentCoroutineContext()[Job])
                        var checks = 0
                        fixture.repository.saveHistorySelection(fixture.seed, target) {
                            if (++checks == 2) {
                                assertTargetWritten(database)
                                owner.cancel(cancelled)
                            }
                            true
                        }
                    }
                try {
                    attempt.await()
                    fail("Cancelled history owner must not receive Saved")
                } catch (observed: CancellationException) {
                    assertOriginalCause(cancelled, observed)
                }
                assertEquals(before, physicalTables(database))
                assertEquals(NextPracticeSaveResult.Saved, fixture.repository.saveHistorySelection(fixture.seed, target) { true })
            }
        }

    @Test fun `history SQLite write failure propagates and preserves all physical rows and schema`() =
        runBlocking {
            for (hasPreparation in listOf(false, true)) {
                memoryDatabase().useDatabase { database ->
                    val fixture = historyFixture(database, hasPreparation)
                    for (operation in listOf("INSERT", "UPDATE")) {
                        database.openHelper.writableDatabase.execSQL(
                            "CREATE TRIGGER reject_history_$operation AFTER $operation ON preparation " +
                                "BEGIN SELECT RAISE(ABORT, 'history write failure'); END",
                        )
                    }
                    val before = physicalTables(database)
                    try {
                        fixture.repository.saveHistorySelection(fixture.seed, target) { true }
                        fail("History SQLite failure must propagate")
                    } catch (_: SQLiteException) {
                        assertEquals(before, physicalTables(database))
                    }
                }
            }
        }

    @Test fun `history selection and concurrent session start keep serialized outcomes without changing old source data`() =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val fixture = historyFixture(database)
                RoomPracticeRepository(database).saveSelection(sourceSelection) { true }
                val childRows = tables(database, includePreparation = false) - "practice_session" - "sqlite_sequence"
                val gate = CompletableDeferred<Unit>()
                val next =
                    async(Dispatchers.Default) {
                        gate.await()
                        fixture.repository.saveHistorySelection(fixture.seed, target) { true }
                    }
                val start =
                    async(Dispatchers.Default) {
                        gate.await()
                        RoomPracticeSessionRepository(database, fixture.catalog).start(fixture.seed.practice, 3_000) { true }
                    }
                gate.complete(Unit)
                val nextResult = withTimeout(10_000) { next.await() }
                val startResult = withTimeout(10_000) { start.await() }
                if (nextResult == NextPracticeSaveResult.Saved) {
                    assertEquals(SessionStartResult.PreparationChanged, startResult)
                    assertEquals(null, database.practiceSessionDao().active())
                    assertEquals(target, database.preparationDao().current()?.selection())
                } else {
                    assertEquals(NextPracticeSaveResult.SourceChanged, nextResult)
                    assertTrue(startResult is SessionStartResult.Started)
                    assertEquals(sourceSelection, database.preparationDao().current()?.selection())
                }
                assertEquals(fixture.source, database.practiceSessionDao().find(fixture.source.sessionId)?.session())
                assertEquals(childRows, tables(database, includePreparation = false) - "practice_session" - "sqlite_sequence")
            }
        }

    private suspend fun historyFixture(
        database: PreparationDatabase,
        hasPreparation: Boolean = true,
    ): Fixture =
        fixture(database, hasPreparation).also {
            cloneSource(database.openHelper.writableDatabase, "history-newer", 100, 200)
            assertEquals("history-newer", database.practiceSessionDao().latestFinished()?.sessionId)
        }

    private fun checkSourceStorage(
        mutation: String,
        afterWrite: Boolean,
    ) = runBlocking {
        memoryDatabase().useDatabase { database ->
            val fixture = fixture(database)
            if (!afterWrite) {
                database.openHelper.writableDatabase.execSQL("UPDATE practice_session SET $mutation")
                if (!mutation.contains("BLOB")) assertEquals(fixture.source, database.practiceSessionDao().latestFinished()?.session())
            }
            val before = physicalTables(database)
            var checks = 0
            expectInvalidSourceStorage {
                fixture.repository.saveSelection(fixture.seed, target) {
                    if (++checks == 2 && afterWrite) {
                        assertTargetWritten(database)
                        database.openHelper.writableDatabase.execSQL("UPDATE practice_session SET $mutation")
                    }
                    true
                }
            }
            assertEquals(if (afterWrite) 2 else 1, checks)
            assertEquals(before, physicalTables(database))
        }
    }

    private fun checkBlobIdentityAlias(afterWrite: Boolean) =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val fixture = fixture(database)
                val originalRow = checkNotNull(database.practiceSessionDao().latestFinished()).rowId
                if (!afterWrite) {
                    insertBlobIdentityAlias(database)
                    val latestRow =
                        database.openHelper.readableDatabase
                            .query("SELECT rowId, typeof(sessionId) FROM practice_session ORDER BY rowId DESC LIMIT 1")
                            .use { cursor ->
                                assertTrue(cursor.moveToFirst())
                                assertTrue(cursor.getLong(0) > originalRow)
                                assertEquals("blob", cursor.getString(1))
                                cursor.getLong(0)
                            }
                    assertEquals(1, database.practiceSessionDao().countValidDeletionStorage(fixture.source.sessionId))
                    assertEquals(
                        1,
                        database.practiceSessionDao().countValidNextPracticeSourceStorage(originalRow, fixture.source.sessionId),
                    )
                    assertEquals(0, database.practiceSessionDao().countValidNextPracticeSourceStorage(latestRow, fixture.source.sessionId))
                }
                val before = physicalTables(database)
                var checks = 0
                expectInvalidSourceStorage {
                    fixture.repository.saveSelection(fixture.seed, target) {
                        if (++checks == 2 && afterWrite) {
                            assertTargetWritten(database)
                            insertBlobIdentityAlias(database)
                        }
                        true
                    }
                }
                assertEquals(if (afterWrite) 2 else 1, checks)
                assertEquals(before, physicalTables(database))
            }
        }

    private fun checkUnfinishedSource(afterWrite: Boolean) =
        runBlocking {
            memoryDatabase().useDatabase { database ->
                val fixture = fixture(database)

                fun insertUnfinished() {
                    cloneSource(database.openHelper.writableDatabase, "unfinished", 3_000, null)
                    database.openHelper.writableDatabase.execSQL(
                        "UPDATE practice_session SET activeSlot = NULL WHERE sessionId = 'unfinished'",
                    )
                }
                if (!afterWrite) {
                    insertUnfinished()
                    assertEquals(null, database.practiceSessionDao().active())
                    assertEquals(1L, database.practiceSessionDao().unfinishedCount())
                }
                val before = physicalTables(database)
                var checks = 0
                assertEquals(
                    NextPracticeSaveResult.SourceChanged,
                    fixture.repository.saveSelection(fixture.seed, target) {
                        if (++checks == 2 && afterWrite) {
                            assertTargetWritten(database)
                            insertUnfinished()
                        }
                        true
                    },
                )
                assertEquals(if (afterWrite) 2 else 1, checks)
                assertEquals(before, physicalTables(database))
            }
        }

    private suspend fun expectInvalidSourceStorage(block: suspend () -> Unit) {
        try {
            block()
            fail("Invalid physical source must not produce a normal save result")
        } catch (failure: IllegalStateException) {
            assertEquals(IllegalStateException::class.java, failure.javaClass)
        } catch (failure: SQLiteException) {
            // Some native cursor implementations reject BLOB-to-string reads before entity construction.
            assertEquals(SQLiteException::class.java, failure.javaClass)
        }
    }

    private fun assertTargetWritten(database: PreparationDatabase) {
        database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM preparation WHERE goalId = 'commute' AND courseId = 'neighborhood' AND taskId = 'arrival'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
    }

    private fun insertBlobIdentityAlias(database: PreparationDatabase) {
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO practice_session(sessionId,goalId,courseId,courseVersion,briefingVersion,taskId," +
                "goalTitle,courseTitle,taskTitle,courseReviewStatus,startedAtEpochMillis,endedAtEpochMillis,activeSlot) " +
                "SELECT CAST(sessionId AS BLOB),goalId,courseId,courseVersion,briefingVersion,taskId," +
                "goalTitle,courseTitle,taskTitle,courseReviewStatus,startedAtEpochMillis,endedAtEpochMillis,activeSlot " +
                "FROM practice_session WHERE sessionId = 'next-source'",
        )
    }

    /** All fixture table values, types, physical row order, schema and sqlite_sequence are compared. */
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

    private fun assertOriginalCause(
        expected: Throwable,
        observed: Throwable,
    ) {
        assertEquals("Propagated exception type must remain exact", expected.javaClass, observed.javaClass)
        assertEquals("Propagated exception message must remain exact", expected.message, observed.message)
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current = observed
        while (current !== expected) {
            assertTrue("Exception recovery must not contain a cause cycle", visited.add(current))
            assertEquals("Every recovered wrapper must retain the exact type", expected.javaClass, current.javaClass)
            assertEquals("Every recovered wrapper must retain the exact message", expected.message, current.message)
            assertTrue(
                "Only an actual coroutine recovery boundary may introduce a wrapper",
                current.stackTrace.any { it.className.startsWith("_COROUTINE.") },
            )
            val cause = current.cause ?: throw AssertionError("The original exception identity was lost")
            assertEquals("A recovery cause must retain the original type", expected.javaClass, cause.javaClass)
            assertEquals("A recovery cause must retain the original message", expected.message, cause.message)
            current = cause
        }
        assertSame("The final cause must be the original exception object", expected, current)
    }

    private fun catalogEdits(): List<(MutableCatalog) -> Unit> =
        listOf(
            { catalog -> catalog.goals = catalog.goals.map { it.copy(description = "changed goal details") } },
            { catalog -> catalog.courses = catalog.courses.map { it.copy(summary = "changed summary") } },
            { catalog -> catalog.courses = catalog.courses.map { it.copy(sections = it.sections + "new section") } },
            { catalog -> catalog.courses = catalog.courses.map { it.copy(goalIds = it.goalIds + "new-goal") } },
            { catalog ->
                catalog.courses =
                    catalog.courses.map { course ->
                        course.copy(tasks = course.tasks.map { it.copy(description = "changed task details") })
                    }
            },
            { catalog ->
                catalog.courses =
                    catalog.courses.map { course ->
                        course.copy(tasks = course.tasks.filterNot { it.id == target.taskId })
                    }
            },
            { catalog ->
                catalog.courses =
                    catalog.courses.map { course ->
                        course.copy(
                            mapPreview =
                                course.mapPreview?.let { preview ->
                                    preview.copy(points = preview.points.map { it.copy(osmVersion = it.osmVersion + 1) })
                                },
                        )
                    }
            },
        )

    private suspend fun fixture(
        database: PreparationDatabase,
        hasPreparation: Boolean = true,
    ): Fixture {
        val catalog = MutableCatalog()
        val practice = (catalog.resolve(sourceSelection) as Preparation.Ready).practice
        database.practiceSessionDao().insert(
            PracticeSessionEntity(
                sessionId = "next-source",
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
        database.vehicleObservationDao().insert(VehicleObservationEntity.from("next-source", VehicleObservation(1_500, VehicleState())))
        database.sessionMemoDao().insert(SessionMemoEntity("next-source", "fixture note", "00000000-0000-0000-0000-000000000001"))
        if (hasPreparation) assertTrue(RoomPracticeRepository(database).saveSelection(original) { true })
        val source = checkNotNull(database.practiceSessionDao().find("next-source")).session()
        val seed = checkNotNull(NextPracticeSeed.resolve(catalog, source))
        return Fixture(catalog, source, seed, RoomNextPracticeRepository(database, catalog))
    }

    private fun memoryDatabase() =
        Room
            .inMemoryDatabaseBuilder(
                RuntimeEnvironment.getApplication(),
                PreparationDatabase::class.java,
            ).build()

    private fun cloneSource(
        database: SupportSQLiteDatabase,
        id: String,
        started: Long,
        ended: Long?,
    ) {
        database.execSQL(
            "INSERT INTO practice_session(sessionId,goalId,courseId,courseVersion,briefingVersion,taskId," +
                "goalTitle,courseTitle,taskTitle,courseReviewStatus,startedAtEpochMillis,endedAtEpochMillis,activeSlot) " +
                "SELECT ?,goalId,courseId,courseVersion,briefingVersion,taskId,goalTitle,courseTitle,taskTitle," +
                "courseReviewStatus,?,?,? FROM practice_session WHERE sessionId = 'next-source'",
            arrayOf(id, started, ended, if (ended == null) 1 else null),
        )
    }

    private fun tables(
        database: PreparationDatabase,
        includePreparation: Boolean = true,
    ): Map<String, List<List<String?>>> {
        val targets =
            listOf(
                "practice_session" to "rowId",
                "vehicle_observation" to "id",
                "session_memo" to "sessionId",
                "sqlite_sequence" to "name",
            ) +
                if (includePreparation) listOf("preparation" to "slot") else emptyList()
        return targets.associate { (table, key) ->
            table to
                database.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY $key").use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                (0 until cursor.columnCount).map { column ->
                                    if (cursor.isNull(column)) null else cursor.getType(column).toString() + ":" + cursor.getString(column)
                                },
                            )
                        }
                    }
                }
        }
    }

    private inline fun <T> PreparationDatabase.useDatabase(block: (PreparationDatabase) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }

    private data class Fixture(
        val catalog: MutableCatalog,
        val source: PracticeSession,
        val seed: NextPracticeSeed,
        val repository: RoomNextPracticeRepository,
    )

    private class MutableCatalog : PracticeCatalog {
        private val original = LocalPracticeCatalog()
        var failure: Exception? = null
        override var goals: List<PracticeGoal> = original.goals
            get() {
                failure?.let { throw it }
                return field
            }
        override var courses: List<PracticeCourse> = original.courses
    }
}
