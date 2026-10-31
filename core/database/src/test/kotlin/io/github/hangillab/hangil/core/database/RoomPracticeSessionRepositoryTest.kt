package io.github.hangillab.hangil.core.database

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomPracticeSessionRepositoryTest {
    private val catalog = LocalPracticeCatalog()
    private val selection = PracticeSelection("commute", "neighborhood", 1, 1, "sequence")
    private val practice = (catalog.resolve(selection) as Preparation.Ready).practice

    private fun memoryDatabase() =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PreparationDatabase::class.java).build()

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

    @Test fun `version one migration preserves preparation and permits new sessions`() =
        runBlocking {
            val name = "session-migration-test.db"
            val context = RuntimeEnvironment.getApplication()
            context.deleteDatabase(name)
            val source =
                checkNotNull(
                    javaClass.classLoader?.getResourceAsStream("io.github.hangillab.hangil.core.database.PreparationDatabase/1.json"),
                )
            val schema = source.bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
            val config =
                SupportSQLiteOpenHelper.Configuration
                    .builder(context)
                    .name(name)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(1) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                val entities = schema.getJSONArray("entities")
                                for (index in 0 until entities.length()) {
                                    val entity = entities.getJSONObject(index)
                                    db.execSQL(entity.getString("createSql").replace('$' + "{TABLE_NAME}", entity.getString("tableName")))
                                }
                                val setup = schema.getJSONArray("setupQueries")
                                for (index in 0 until setup.length()) db.execSQL(setup.getString(index))
                                db.execSQL(
                                    "INSERT INTO preparation VALUES(1, ?, ?, ?, ?, ?)",
                                    arrayOf<Any>(
                                        selection.goalId,
                                        selection.courseId,
                                        selection.courseVersion,
                                        selection.briefingVersion,
                                        selection.taskId,
                                    ),
                                )
                            }

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = error("Unexpected test upgrade")
                        },
                    ).build()
            FrameworkSQLiteOpenHelperFactory().create(config).use { assertEquals(1, it.writableDatabase.version) }
            open(name).useDatabase {
                assertEquals(selection, RoomPracticeRepository(it).observeSelection().first())
                val repository = RoomPracticeSessionRepository(it, catalog)
                assertTrue(repository.observeSessions().first().isEmpty())
                assertTrue(repository.start(practice, 1_000) { true } is SessionStartResult.Started)
                assertEquals(6, it.openHelper.writableDatabase.version)
                assertEquals(null, RoomSessionMemoRepository(it).observe("absent-fixture").first())
            }
        }

    @Test fun `active and finished snapshots survive close reopen and later preparation changes`() =
        runBlocking {
            val name = "session-restore-test.db"
            RuntimeEnvironment.getApplication().deleteDatabase(name)
            val started =
                open(name).useDatabase {
                    RoomPracticeRepository(it).saveSelection(selection) { true }
                    (RoomPracticeSessionRepository(it, catalog).start(practice, 1_000) { true } as SessionStartResult.Started).session
                }
            open(name).useDatabase {
                val repository = RoomPracticeSessionRepository(it, catalog)
                assertEquals(listOf(started), repository.observeSessions().first())
                assertEquals(selection, started.selection)
                assertEquals(practice.goal.title, started.goalTitle)
                assertEquals(practice.course.title, started.courseTitle)
                assertEquals(practice.task.title, started.taskTitle)
                assertEquals(CourseReviewStatus.DEMO, started.courseReviewStatus)
                RoomPracticeRepository(it).saveSelection(selection.copy(taskId = "arrival")) { true }
                assertEquals(listOf(started), repository.observeSessions().first())
                assertTrue(repository.finish(started.sessionId, 2_000) { true } is SessionFinishResult.Finished)
            }
            open(name).useDatabase {
                val restored = RoomPracticeSessionRepository(it, catalog).observeSessions().first().single()
                assertEquals(started.copy(endedAtEpochMillis = 2_000), restored)
                assertFalse(restored.isActive)
            }
        }

    @Test fun `concurrent start requests keep exactly one active session`() =
        runBlocking {
            memoryDatabase().useDatabase {
                RoomPracticeRepository(it).saveSelection(selection) { true }
                val repository = RoomPracticeSessionRepository(it, catalog)
                val results =
                    (1..4)
                        .map { offset -> async(Dispatchers.Default) { repository.start(practice, 1_000L + offset) { true } } }
                        .awaitAll()
                assertEquals(1, results.count { result -> result is SessionStartResult.Started })
                assertEquals(3, results.count { result -> result is SessionStartResult.AlreadyActive })
                assertEquals(1, repository.observeSessions().first().count { session -> session.isActive })
            }
        }

    @Test fun `database index rejects a second active session even outside repository`() =
        runBlocking {
            memoryDatabase().useDatabase {
                RoomPracticeRepository(it).saveSelection(selection) { true }
                RoomPracticeSessionRepository(it, catalog).start(practice, 1_000) { true }
                val active = checkNotNull(it.practiceSessionDao().active())
                try {
                    it.practiceSessionDao().insert(active.copy(rowId = 0, sessionId = "conflicting-active"))
                    fail("The second active session must violate the unique slot")
                } catch (_: SQLiteException) {
                    assertEquals(
                        1,
                        it
                            .practiceSessionDao()
                            .observe()
                            .first()
                            .size,
                    )
                }
            }
        }

    @Test fun `stale changed or outdated preparation cannot start a session`() =
        runBlocking {
            memoryDatabase().useDatabase {
                val preparation = RoomPracticeRepository(it)
                val repository = RoomPracticeSessionRepository(it, catalog)
                assertEquals(SessionStartResult.PreparationChanged, repository.start(practice, 1_000) { true })
                preparation.saveSelection(selection.copy(taskId = "arrival")) { true }
                assertEquals(SessionStartResult.PreparationChanged, repository.start(practice, 1_000) { true })
                preparation.saveSelection(selection.copy(courseVersion = 99)) { true }
                assertEquals(SessionStartResult.PreparationChanged, repository.start(practice, 1_000) { true })
                preparation.saveSelection(selection) { true }
                val staleLabels = practice.copy(course = practice.course.copy(title = "Old title"))
                assertEquals(SessionStartResult.PreparationChanged, repository.start(staleLabels, 1_000) { true })
                assertTrue(repository.observeSessions().first().isEmpty())
            }
        }

    @Test fun `permission lost before or during start rolls back without a session`() =
        runBlocking {
            memoryDatabase().useDatabase {
                RoomPracticeRepository(it).saveSelection(selection) { true }
                val repository = RoomPracticeSessionRepository(it, catalog)
                assertEquals(SessionStartResult.Blocked, repository.start(practice, 1_000) { false })
                var checks = 0
                assertEquals(SessionStartResult.Blocked, repository.start(practice, 1_000) { ++checks == 1 })
                assertEquals(2, checks)
                assertTrue(repository.observeSessions().first().isEmpty())
                assertEquals(selection, RoomPracticeRepository(it).observeSelection().first())
            }
        }

    @Test fun `permission lost before or during finish preserves active session`() =
        runBlocking {
            memoryDatabase().useDatabase {
                RoomPracticeRepository(it).saveSelection(selection) { true }
                val repository = RoomPracticeSessionRepository(it, catalog)
                val started = (repository.start(practice, 1_000) { true } as SessionStartResult.Started).session
                assertEquals(SessionFinishResult.Blocked, repository.finish(started.sessionId, 2_000) { false })
                var checks = 0
                assertEquals(SessionFinishResult.Blocked, repository.finish(started.sessionId, 2_000) { ++checks == 1 })
                assertEquals(2, checks)
                assertEquals(listOf(started), repository.observeSessions().first())
            }
        }

    @Test fun `duplicate and stale finish targets never finish the newer active session`() =
        runBlocking {
            memoryDatabase().useDatabase {
                RoomPracticeRepository(it).saveSelection(selection) { true }
                val repository = RoomPracticeSessionRepository(it, catalog)
                val first = (repository.start(practice, 1_000) { true } as SessionStartResult.Started).session
                val ended = (repository.finish(first.sessionId, 2_000) { true } as SessionFinishResult.Finished).session
                val second = (repository.start(practice, 3_000) { true } as SessionStartResult.Started).session
                assertEquals(SessionFinishResult.AlreadyFinished(ended), repository.finish(first.sessionId, 4_000) { true })
                assertEquals(SessionFinishResult.Missing, repository.finish("unknown-session", 4_000) { true })
                assertEquals(listOf(second, ended), repository.observeSessions().first())
            }
        }

    @Test fun `nonpositive or reversed clock values are errors and preserve storage`() =
        runBlocking {
            memoryDatabase().useDatabase {
                RoomPracticeRepository(it).saveSelection(selection) { true }
                val repository = RoomPracticeSessionRepository(it, catalog)
                assertEquals(SessionStartResult.InvalidTime, repository.start(practice, 0) { true })
                assertTrue(repository.observeSessions().first().isEmpty())
                val started = (repository.start(practice, 1_000) { true } as SessionStartResult.Started).session
                assertEquals(SessionFinishResult.InvalidTime, repository.finish(started.sessionId, 0) { true })
                assertEquals(SessionFinishResult.InvalidTime, repository.finish(started.sessionId, 999) { true })
                assertEquals(listOf(started), repository.observeSessions().first())
            }
        }

    @Test fun `newer session stays first when wall clock moves behind an earlier finished record`() =
        runBlocking {
            memoryDatabase().useDatabase {
                RoomPracticeRepository(it).saveSelection(selection) { true }
                val repository = RoomPracticeSessionRepository(it, catalog)
                val first = (repository.start(practice, 2_000) { true } as SessionStartResult.Started).session
                val ended = (repository.finish(first.sessionId, 3_000) { true } as SessionFinishResult.Finished).session
                val newer = (repository.start(practice, 1_000) { true } as SessionStartResult.Started).session
                assertEquals(listOf(newer, ended), repository.observeSessions().first())
                val newerEnded = (repository.finish(newer.sessionId, 1_500) { true } as SessionFinishResult.Finished).session
                assertEquals(listOf(newerEnded, ended), repository.observeSessions().first())
            }
        }

    @Test fun `sqlite errors propagate rather than returning mutation success`() =
        runBlocking {
            memoryDatabase().useDatabase {
                RoomPracticeRepository(it).saveSelection(selection) { true }
                it.openHelper.writableDatabase.execSQL("DROP TABLE practice_session")
                try {
                    RoomPracticeSessionRepository(it, catalog).start(practice, 1_000) { true }
                    fail("Missing table must not be reported as a successful start")
                } catch (_: SQLiteException) {
                    // Only the expected storage failure is accepted; no normal session result is fabricated.
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
