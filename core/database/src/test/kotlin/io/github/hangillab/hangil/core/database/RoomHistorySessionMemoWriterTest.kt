package io.github.hangillab.hangil.core.database

import android.database.Cursor
import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.withTransaction
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclarationResult
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.RecordingRunOpenResult
import io.github.hangillab.hangil.core.domain.RecordingRunStatus
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionDeletionResult
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

/** Real Room transactions with owned synthetic data; no SDK or production user store is touched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomHistorySessionMemoWriterTest {
    private fun memoryDatabase() =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PreparationDatabase::class.java).build()

    @Test fun `an older existing memo is updated while the original latest-only writer still rejects it`() =
        runBlocking {
            memoryDatabase().useDatabase { db ->
                val fixture = seed(db)
                val latest = RoomSessionMemoRepository(db)
                val before = snapshot(db, excludingMemo = fixture.source.sessionId)
                assertEquals(
                    SessionMemoWriteResult.SessionChanged,
                    latest.save(
                        fixture.source.sessionId,
                        "old API must not change",
                        fixture.memo.version,
                    ) {
                        true
                    },
                )
                val text = " \n나중에 고친 메모 🙂\n끝  "
                val result = saved(RoomHistorySessionMemoWriter(db).update(fixture.source, fixture.memo, text) { true })
                assertEquals(fixture.source.sessionId, result.sessionId)
                assertEquals(text, result.text)
                assertNotEquals(fixture.memo.version, result.version)
                assertEquals(result.version, UUID.fromString(result.version).toString())
                assertEquals(result, latest.observe(fixture.source.sessionId).first())
                assertEquals(before, snapshot(db, excludingMemo = fixture.source.sessionId))
                assertEquals(6, db.openHelper.readableDatabase.version)
                assertEquals(
                    SessionMemoWriteResult.SessionChanged,
                    latest.save(fixture.source.sessionId, "still old API", result.version) { true },
                )
                assertEquals(SessionMemoWriteResult.SessionChanged, latest.delete(fixture.source.sessionId, result.version) { true })
                assertEquals(result, latest.observe(fixture.source.sessionId).first())
            }
        }

    @Test fun `same text still changes the version and stale whole memo cannot overwrite it`() =
        runBlocking {
            memoryDatabase().useDatabase { db ->
                val fixture = seed(db)
                val writer = RoomHistorySessionMemoWriter(db)
                val next = saved(writer.update(fixture.source, fixture.memo, fixture.memo.text) { true })
                assertEquals(fixture.memo.text, next.text)
                assertNotEquals(fixture.memo.version, next.version)
                assertEquals(SessionMemoWriteResult.Conflict, writer.update(fixture.source, fixture.memo, "stale update") { true })
                assertEquals(next, RoomSessionMemoRepository(db).observe(fixture.source.sessionId).first())
            }
        }

    @Test fun `missing session and missing memo are distinct and no missing note is inserted`() =
        runBlocking {
            memoryDatabase().useDatabase { db ->
                val fixture = seed(db)
                val writer = RoomHistorySessionMemoWriter(db)
                assertEquals(1, db.sessionMemoDao().delete(fixture.source.sessionId, fixture.memo.version))
                val before = snapshot(db)
                assertEquals(SessionMemoWriteResult.Conflict, writer.update(fixture.source, fixture.memo, "must not create") { true })
                assertEquals(before, snapshot(db))
                assertEquals(null, RoomSessionMemoRepository(db).observe(fixture.source.sessionId).first())
                assertEquals(1, db.practiceSessionDao().deleteFinished(fixture.source.sessionId))
                val missing = snapshot(db)
                assertEquals(
                    SessionMemoWriteResult.MissingSession,
                    writer.update(fixture.source, fixture.memo, "must not resurrect") { true },
                )
                assertEquals(missing, snapshot(db))
            }
        }

    @Test fun `expected memo session mismatch is changed rather than a write to either record`() =
        runBlocking {
            memoryDatabase().useDatabase { db ->
                val fixture = seed(db)
                val before = snapshot(db)
                assertEquals(
                    SessionMemoWriteResult.SessionChanged,
                    RoomHistorySessionMemoWriter(
                        db,
                    ).update(fixture.source, fixture.memo.copy(sessionId = "newer"), "wrong target") {
                        true
                    },
                )
                assertEquals(before, snapshot(db))
            }
        }

    @Test fun `every independently changeable source field must match the full selected snapshot`() =
        runBlocking {
            memoryDatabase().useDatabase { db ->
                val fixture = seed(db)
                val source = fixture.source
                val variants =
                    listOf(
                        source.copy(selection = source.selection.copy(goalId = "other-goal")),
                        source.copy(selection = source.selection.copy(courseId = "other-course")),
                        source.copy(selection = source.selection.copy(courseVersion = source.selection.courseVersion + 1)),
                        source.copy(selection = source.selection.copy(briefingVersion = source.selection.briefingVersion + 1)),
                        source.copy(selection = source.selection.copy(taskId = "other-task")),
                        source.copy(goalTitle = "other goal title"),
                        source.copy(courseTitle = "other course title"),
                        source.copy(taskTitle = "other task title"),
                        source.copy(startedAtEpochMillis = source.startedAtEpochMillis + 1),
                        source.copy(endedAtEpochMillis = checkNotNull(source.endedAtEpochMillis) + 1),
                    )
                val before = snapshot(db)
                for (expected in variants) {
                    assertEquals(
                        SessionMemoWriteResult.SessionChanged,
                        RoomHistorySessionMemoWriter(
                            db,
                        ).update(expected, fixture.memo, "wrong snapshot") {
                            true
                        },
                    )
                    assertEquals(before, snapshot(db))
                }
            }
        }

    @Test fun `a source changed in actual Room is rejected before any session flow notification is needed`() =
        runBlocking {
            memoryDatabase().useDatabase { db ->
                val fixture = seed(db)
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE practice_session SET taskTitle = ? WHERE sessionId = ?",
                    arrayOf("new stored snapshot", fixture.source.sessionId),
                )
                val before = snapshot(db)
                assertEquals(
                    SessionMemoWriteResult.SessionChanged,
                    RoomHistorySessionMemoWriter(
                        db,
                    ).update(fixture.source, fixture.memo, "stale source") {
                        true
                    },
                )
                assertEquals(before, snapshot(db))
            }
        }

    @Test fun `whole memo text as well as version is required even if a writer retained the same version`() =
        runBlocking {
            memoryDatabase().useDatabase { db ->
                val fixture = seed(db)
                val writer = RoomHistorySessionMemoWriter(db)
                val before = snapshot(db)
                assertEquals(
                    SessionMemoWriteResult.Conflict,
                    writer.update(
                        fixture.source,
                        fixture.memo.copy(text = "not the read text"),
                        "replacement",
                    ) {
                        true
                    },
                )
                assertEquals(
                    SessionMemoWriteResult.Conflict,
                    writer.update(
                        fixture.source,
                        fixture.memo.copy(version = UUID.randomUUID().toString()),
                        "replacement",
                    ) {
                        true
                    },
                )
                assertEquals(before, snapshot(db))
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE session_memo SET text = ? WHERE sessionId = ?",
                    arrayOf("different actual text", fixture.source.sessionId),
                )
                val changed = snapshot(db)
                assertEquals(SessionMemoWriteResult.Conflict, writer.update(fixture.source, fixture.memo, "must not overwrite") { true })
                assertEquals(changed, snapshot(db))
            }
        }

    @Test fun `a matching active source and a separate unfinished session block history updates`() =
        runBlocking {
            memoryDatabase().useDatabase { db ->
                val fixture = seed(db)
                val writer = RoomHistorySessionMemoWriter(db)
                val sqlite = db.openHelper.writableDatabase
                sqlite.execSQL(
                    "UPDATE practice_session SET endedAtEpochMillis = NULL, activeSlot = 1 WHERE sessionId = ?",
                    arrayOf(fixture.source.sessionId),
                )
                val active = checkNotNull(db.practiceSessionDao().find(fixture.source.sessionId)).session()
                val beforeActive = snapshot(db)
                assertEquals(SessionMemoWriteResult.SessionActive, writer.update(active, fixture.memo, "active source") { true })
                assertEquals(beforeActive, snapshot(db))
                sqlite.execSQL(
                    "UPDATE practice_session SET endedAtEpochMillis = ?, activeSlot = NULL WHERE sessionId = ?",
                    arrayOf(checkNotNull(fixture.source.endedAtEpochMillis), fixture.source.sessionId),
                )
                seedSession(db, "active-other", 5_000, null)
                val beforeOther = snapshot(db)
                assertEquals(SessionMemoWriteResult.SessionActive, writer.update(fixture.source, fixture.memo, "other active") { true })
                assertEquals(beforeOther, snapshot(db))
            }
        }

    @Test fun `unfinished markers with a broken active slot cannot be mistaken for no active session`() =
        runBlocking {
            memoryDatabase().useDatabase { db ->
                val fixture = seed(db)
                seedSession(db, "broken-other", 5_000, 6_000)
                val sqlite = db.openHelper.writableDatabase
                for (mutation in listOf("endedAtEpochMillis = NULL, activeSlot = NULL", "endedAtEpochMillis = 6000, activeSlot = 2")) {
                    sqlite.execSQL("UPDATE practice_session SET $mutation WHERE sessionId = ?", arrayOf("broken-other"))
                    val before = snapshot(db)
                    assertEquals(
                        SessionMemoWriteResult.SessionActive,
                        RoomHistorySessionMemoWriter(
                            db,
                        ).update(fixture.source, fixture.memo, "blocked") {
                            true
                        },
                    )
                    assertEquals(before, snapshot(db))
                }
            }
        }

    @Test fun `invalid new text leaves the original note unchanged and the Unicode limit preserves all code points`() =
        runBlocking {
            memoryDatabase().useDatabase { db ->
                val fixture = seed(db)
                val writer = RoomHistorySessionMemoWriter(db)
                val before = snapshot(db)
                for (text in listOf("", " \n\t", "x".repeat(281), "🙂".repeat(281), "memo\u0000value", "\uD800", "\uDC00")) {
                    assertEquals(SessionMemoWriteResult.InvalidText, writer.update(fixture.source, fixture.memo, text) { true })
                    assertEquals(before, snapshot(db))
                }
                val text = "🙂".repeat(280)
                assertEquals(text, saved(writer.update(fixture.source, fixture.memo, text) { true }).text)
            }
        }

    @Test fun `concurrent exact updates have one saved winner and one conflict`() =
        runBlocking {
            memoryDatabase().useDatabase { db ->
                val fixture = seed(db)
                val writer = RoomHistorySessionMemoWriter(db)
                val first = async(Dispatchers.Default) { writer.update(fixture.source, fixture.memo, "first writer") { true } }
                val second = async(Dispatchers.Default) { writer.update(fixture.source, fixture.memo, "second writer") { true } }
                val results = listOf(first.await(), second.await())
                assertEquals(1, results.count { it is SessionMemoWriteResult.Saved })
                assertEquals(1, results.count { it == SessionMemoWriteResult.Conflict })
                val winner = saved(results.single { it is SessionMemoWriteResult.Saved })
                assertEquals(winner, RoomSessionMemoRepository(db).observe(fixture.source.sessionId).first())
                assertNotEquals(fixture.memo.version, winner.version)
            }
        }

    @Test fun `permission denial before or after the actual update leaves every original field unchanged`() =
        runBlocking {
            memoryDatabase().useDatabase { db ->
                val fixture = seed(db)
                val writer = RoomHistorySessionMemoWriter(db)
                val before = snapshot(db)
                assertEquals(SessionMemoWriteResult.Blocked, writer.update(fixture.source, fixture.memo, "denied before") { false })
                assertEquals(before, snapshot(db))
                var checks = 0
                var sawUpdate = false
                assertEquals(
                    SessionMemoWriteResult.Blocked,
                    writer.update(fixture.source, fixture.memo, "denied after") {
                        if (++checks == 2) {
                            val uncommitted = rawMemo(db, fixture.source.sessionId)
                            assertEquals("denied after", uncommitted.first)
                            assertNotEquals(fixture.memo.version, uncommitted.second)
                            sawUpdate = true
                            false
                        } else {
                            true
                        }
                    },
                )
                assertTrue(sawUpdate)
                assertEquals(2, checks)
                assertEquals(before, snapshot(db))
            }
        }

    @Test fun `cancelling in the post-write gate while returning true still rolls back the actual edit`() =
        runBlocking {
            memoryDatabase().useDatabase { db ->
                val fixture = seed(db)
                val before = snapshot(db)
                val cancelled = CancellationException("controlled post-write cancellation")
                var checks = 0
                var sawUpdate = false
                val operation =
                    async(Dispatchers.Default) {
                        val job = checkNotNull(currentCoroutineContext()[Job])
                        RoomHistorySessionMemoWriter(db).update(fixture.source, fixture.memo, "cancelled actual update") {
                            if (++checks == 2) {
                                assertEquals("cancelled actual update", rawMemo(db, fixture.source.sessionId).first)
                                sawUpdate = true
                                job.cancel(cancelled)
                            }
                            true
                        }
                    }
                try {
                    operation.await()
                    fail("A cancelled post-write gate must not acknowledge Saved")
                } catch (actual: CancellationException) {
                    assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === cancelled })
                }
                assertTrue(sawUpdate)
                assertEquals(before, snapshot(db))
            }
        }

    @Test fun `throwing post-write permission gate propagates its failure and rolls back`() =
        runBlocking {
            memoryDatabase().useDatabase { db ->
                val fixture = seed(db)
                val before = snapshot(db)
                val failure = IOException("controlled permission callback failure")
                var checks = 0
                try {
                    RoomHistorySessionMemoWriter(db).update(fixture.source, fixture.memo, "uncommitted") {
                        if (++checks == 2) throw failure
                        true
                    }
                    fail("An independent gate failure must not become a successful result")
                } catch (actual: IOException) {
                    assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === failure })
                }
                assertEquals(before, snapshot(db))
            }
        }

    @Test fun `cancelling an outer transaction rolls back a locally acknowledged update`() =
        runBlocking {
            memoryDatabase().useDatabase { db ->
                val fixture = seed(db)
                val before = snapshot(db)
                val updated = CompletableDeferred<Unit>()
                val job =
                    launch(Dispatchers.Default) {
                        db.withTransaction {
                            assertTrue(
                                RoomHistorySessionMemoWriter(
                                    db,
                                ).update(fixture.source, fixture.memo, "nested update") { true } is SessionMemoWriteResult.Saved,
                            )
                            updated.complete(Unit)
                            awaitCancellation()
                        }
                    }
                withTimeout(10_000) { updated.await() }
                job.cancelAndJoin()
                assertEquals(before, snapshot(db))
            }
        }

    @Test fun `adapter result cancellation after Room returns is resolved by rereading without a second write`() =
        runBlocking {
            memoryDatabase().useDatabase { db ->
                val fixture = seed(db)
                val committed = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val writer = RoomHistorySessionMemoWriter(db)
                val operation =
                    async(Dispatchers.Default) {
                        val result = writer.update(fixture.source, fixture.memo, "durable before adapter response") { true }
                        committed.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        result
                    }
                withTimeout(10_000) { committed.await() }
                operation.cancel()
                release.complete(Unit)
                try {
                    operation.await()
                    fail("The controlled adapter result must remain cancelled")
                } catch (_: CancellationException) {
                    // Room already returned before the adapter delayed delivery; no internal dispatcher claim.
                }
                val durable = checkNotNull(RoomSessionMemoRepository(db).observe(fixture.source.sessionId).first())
                assertEquals("durable before adapter response", durable.text)
                assertNotEquals(fixture.memo.version, durable.version)
                assertEquals(SessionMemoWriteResult.Conflict, writer.update(fixture.source, fixture.memo, "stale retry") { true })
                assertEquals(durable, RoomSessionMemoRepository(db).observe(fixture.source.sessionId).first())
            }
        }

    @Test fun `SQL update failure propagates without modifying the original note`() =
        runBlocking {
            memoryDatabase().useDatabase { db ->
                val fixture = seed(db)
                val sqlite = db.openHelper.writableDatabase
                sqlite.execSQL(
                    "CREATE TRIGGER reject_history_memo AFTER UPDATE ON session_memo " +
                        "BEGIN SELECT RAISE(ABORT, 'controlled update failure'); END",
                )
                val before = snapshot(db)
                try {
                    RoomHistorySessionMemoWriter(db).update(fixture.source, fixture.memo, "rejected SQL") { true }
                    fail("An actual SQL failure must propagate")
                } catch (_: SQLiteException) {
                    // Preserve the real SQL failure rather than reporting Conflict or success.
                }
                assertEquals(before, snapshot(db))
            }
        }

    @Test fun `corrupt memo text version and original blob types are errors rather than absence or conflict`() =
        runBlocking {
            memoryDatabase().useDatabase { db ->
                val fixture = seed(db)
                val sqlite = db.openHelper.writableDatabase
                val mutations =
                    listOf(
                        "text = ''",
                        "text = '   '",
                        "version = ''",
                        "version = 'not-a-token'",
                        "text = CAST(text AS BLOB)",
                        "version = CAST(version AS BLOB)",
                    )
                for (mutation in mutations) {
                    sqlite.execSQL(
                        "UPDATE session_memo SET text = ?, version = ? WHERE sessionId = ?",
                        arrayOf(fixture.memo.text, fixture.memo.version, fixture.source.sessionId),
                    )
                    sqlite.execSQL("UPDATE session_memo SET $mutation WHERE sessionId = ?", arrayOf(fixture.source.sessionId))
                    val before = snapshot(db)
                    expectInvalid { RoomHistorySessionMemoWriter(db).update(fixture.source, fixture.memo, "must not repair") { true } }
                    assertEquals(before, snapshot(db))
                }
            }
        }

    @Test fun `coercible session storage and inconsistent markers cannot match the captured source`() =
        runBlocking {
            memoryDatabase().useDatabase { db ->
                val fixture = seed(db)
                val sqlite = db.openHelper.writableDatabase
                val original = checkNotNull(db.practiceSessionDao().find(fixture.source.sessionId))
                val mutations =
                    listOf(
                        "startedAtEpochMillis = 1000.5",
                        "endedAtEpochMillis = 2000.5",
                        "courseVersion = 1.5",
                        "briefingVersion = 4294967297",
                        "goalTitle = CAST(goalTitle AS BLOB)",
                        "courseReviewStatus = 'UNKNOWN'",
                        "endedAtEpochMillis = 500",
                        "endedAtEpochMillis = 2000, activeSlot = 1",
                        "taskTitle = ''",
                    )
                for (mutation in mutations) {
                    sqlite.execSQL("UPDATE practice_session SET $mutation WHERE sessionId = ?", arrayOf(fixture.source.sessionId))
                    val before = snapshot(db)
                    expectInvalid {
                        RoomHistorySessionMemoWriter(
                            db,
                        ).update(fixture.source, fixture.memo, "must not coerce source") { true }
                    }
                    assertEquals(before, snapshot(db))
                    sqlite.execSQL(
                        "UPDATE practice_session SET startedAtEpochMillis = ?, endedAtEpochMillis = ?, " +
                            "courseVersion = ?, briefingVersion = ?, goalTitle = ?, " +
                            "courseReviewStatus = ?, activeSlot = NULL, taskTitle = ? WHERE sessionId = ?",
                        arrayOf(
                            original.startedAtEpochMillis,
                            original.endedAtEpochMillis,
                            original.courseVersion,
                            original.briefingVersion,
                            original.goalTitle,
                            original.courseReviewStatus,
                            original.taskTitle,
                            original.sessionId,
                        ),
                    )
                }
            }
        }

    @Test fun `history update invalidates an earlier exact session deletion confirmation`() =
        runBlocking {
            memoryDatabase().useDatabase { db ->
                val fixture = seed(db)
                val deletion = RoomSessionDeletionRepository(db)
                val target = (deletion.prepare(fixture.source) { true } as SessionDeletionPreparation.Ready).target
                val next = saved(RoomHistorySessionMemoWriter(db).update(fixture.source, fixture.memo, fixture.memo.text) { true })
                assertNotEquals(target.memoVersion, next.version)
                val before = snapshot(db)
                assertEquals(SessionDeletionResult.Changed, deletion.delete(target) { true })
                assertEquals(before, snapshot(db))
                assertEquals(next, RoomSessionMemoRepository(db).observe(fixture.source.sessionId).first())
            }
        }

    @Test fun `the updated note survives close and reopen with the same schema and unrelated data`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val name = "history-memo-owned-${UUID.randomUUID()}.db"

            fun open() =
                Room
                    .databaseBuilder(context, PreparationDatabase::class.java, name)
                    .addMigrations(
                        PreparationMigration1To2,
                        PreparationMigration2To3,
                        PreparationMigration3To4,
                        PreparationMigration4To5,
                        PreparationMigration5To6,
                    ).build()
            try {
                var expected: SessionMemo? = null
                var retained: Map<String, List<List<Pair<Int, Any?>>>>? = null
                open().useDatabase { db ->
                    val fixture = seed(db)
                    expected = saved(RoomHistorySessionMemoWriter(db).update(fixture.source, fixture.memo, " \n지속되는 수정 🙂\n ") { true })
                    retained = snapshot(db)
                }
                open().useDatabase { db ->
                    assertEquals(expected, RoomSessionMemoRepository(db).observe(checkNotNull(expected).sessionId).first())
                    assertEquals(retained, snapshot(db))
                    assertEquals(6, db.openHelper.readableDatabase.version)
                    db.openHelper.readableDatabase
                        .query("PRAGMA foreign_key_check")
                        .use { assertEquals(0, it.count) }
                }
            } finally {
                context.deleteDatabase(name)
            }
        }

    private suspend fun seed(db: PreparationDatabase): Fixture {
        db.preparationDao().upsert(
            PreparationEntity(goalId = "commute", courseId = "neighborhood", courseVersion = 1, briefingVersion = 1, taskId = "sequence"),
        )
        seedSession(db, "older", 1_000, null)
        val active = checkNotNull(db.practiceSessionDao().find("older")).session()
        val runs = RoomRecordingRunRepository(db)
        assertTrue(runs.open(active, "fixture-run", "fixture-process", 1_100) { true } is RecordingRunOpenResult.Opened)
        assertTrue(runs.finish("fixture-run", "fixture-process", RecordingRunStatus.STOPPED, 1_200))
        assertTrue(
            RoomVehicleObservationRepository(
                db,
            ).append(
                "older",
                VehicleObservation(1_300, VehicleState(ConnectionState.ERROR, brakePressed = VehicleReading.Error)),
            ) is VehicleObservationAppendResult.Appended,
        )
        assertTrue(
            RoomPracticeSessionRepository(db, LocalPracticeCatalog()).finish("older", 2_000) { true } is SessionFinishResult.Finished,
        )
        val source = checkNotNull(db.practiceSessionDao().find("older")).session()
        val memo = saved(RoomSessionMemoRepository(db).save("older", " \n원래 검증 메모 🙂\n ", null) { true })
        assertTrue(RoomCourseCompletionRepository(db).declare(source, 2_100) { true } is CourseCompletionDeclarationResult.Saved)
        seedSession(db, "newer", 3_000, 4_000)
        saved(RoomSessionMemoRepository(db).save("newer", "unrelated latest note", null) { true })
        return Fixture(source, memo)
    }

    private suspend fun seedSession(
        db: PreparationDatabase,
        id: String,
        started: Long,
        ended: Long?,
    ) {
        db.practiceSessionDao().insert(
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
                startedAtEpochMillis = started,
                endedAtEpochMillis = ended,
                activeSlot = if (ended == null) 1 else null,
            ),
        )
    }

    private data class Fixture(
        val source: PracticeSession,
        val memo: SessionMemo,
    )

    private fun saved(result: SessionMemoWriteResult) = (result as SessionMemoWriteResult.Saved).memo

    private fun rawMemo(
        db: PreparationDatabase,
        id: String,
    ): Pair<String, String> =
        db.openHelper.writableDatabase.query("SELECT text, version FROM session_memo WHERE sessionId = ?", arrayOf(id)).use {
            check(it.moveToFirst())
            it.getString(0) to it.getString(1)
        }

    private fun snapshot(
        db: PreparationDatabase,
        excludingMemo: String? = null,
    ): Map<String, List<List<Pair<Int, Any?>>>> =
        listOf(
            "preparation",
            "practice_session",
            "vehicle_observation",
            "session_memo",
            "vehicle_recording_run",
            "course_completion_declaration",
            "sqlite_sequence",
        ).associateWith { table ->
            val predicate = if (table == "session_memo" && excludingMemo != null) " WHERE sessionId != ?" else ""
            val args = if (predicate.isNotEmpty()) arrayOf<Any?>(excludingMemo) else emptyArray()
            db.openHelper.writableDatabase.query("SELECT rowid, * FROM $table$predicate ORDER BY rowid", args).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            (0 until cursor.columnCount).map { index ->
                                val value: Any? =
                                    when (cursor.getType(index)) {
                                        Cursor.FIELD_TYPE_NULL -> null
                                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                                        Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).toList()
                                        else -> error("Invalid fixture SQLite type")
                                    }
                                cursor.getType(index) to value
                            },
                        )
                    }
                }
            }
        }

    private suspend fun expectInvalid(action: suspend () -> Any?) {
        try {
            action()
            fail("Malformed stored data must not become absence, conflict or success")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SQLiteException) {
            // SQLite may reject the original physical type before Room builds its projection.
        } catch (_: IllegalArgumentException) {
            // A stored unknown enum is not a normal domain source.
        } catch (_: IllegalStateException) {
            // Explicit stored-type and model validation failures propagate.
        }
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
                val original = primary
                if (original == null) throw cleanup else original.addSuppressed(cleanup)
            }
        }
    }
}
