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
                    assertEquals(5, database.openHelper.readableDatabase.version)
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
