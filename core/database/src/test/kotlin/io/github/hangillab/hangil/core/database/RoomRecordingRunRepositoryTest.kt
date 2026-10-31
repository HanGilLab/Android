package io.github.hangillab.hangil.core.database

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.withTransaction
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.RecordingRunOpenResult
import io.github.hangillab.hangil.core.domain.RecordingRunStatus
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionDeletionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
class RoomRecordingRunRepositoryTest {
    @Test fun `open persists only the exact active source and preserves creation order despite clock reversal`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database)
                val repository = RoomRecordingRunRepository(database)
                val first = opened(repository.open(source, "first-fixture", "owner-fixture", 3_000) { true })
                assertEquals(RecordingRunStatus.OPEN, first.status)
                assertEquals(null, first.endedAtEpochMillis)
                assertTrue(repository.finish(first.runId, first.processId, RecordingRunStatus.STOPPED, 4_000))
                val second = opened(repository.open(source, "second-fixture", "owner-fixture", 2_000) { true })
                val runs = repository.observe(source.sessionId).first()
                assertEquals(listOf(first.runId, second.runId), runs.map { it.runId })
                assertEquals(RecordingRunStatus.STOPPED, runs.first().status)
                assertEquals(source, database.practiceSessionDao().find(source.sessionId)?.session())
            }
        }

    @Test fun `every active source snapshot field is checked and finished or missing sources cannot open`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database)
                val repository = RoomRecordingRunRepository(database)
                val selection = source.selection
                val mismatches =
                    listOf(
                        source.copy(sessionId = "missing-fixture"),
                        source.copy(selection = selection.copy(goalId = "other")),
                        source.copy(selection = selection.copy(courseId = "other")),
                        source.copy(selection = selection.copy(taskId = "other")),
                        source.copy(selection = selection.copy(courseVersion = 2)),
                        source.copy(selection = selection.copy(briefingVersion = 2)),
                        source.copy(goalTitle = "other"),
                        source.copy(courseTitle = "other"),
                        source.copy(taskTitle = "other"),
                        source.copy(startedAtEpochMillis = 900),
                        source.copy(endedAtEpochMillis = 1_500),
                    )
                for (snapshot in mismatches) {
                    assertEquals(
                        RecordingRunOpenResult.SourceChanged,
                        repository.open(snapshot, "run-fixture", "owner-fixture", 2_000) { true },
                    )
                }
                assertEquals(RecordingRunOpenResult.InvalidTime, repository.open(source, "run-fixture", "owner-fixture", 0) { true })
                assertEquals(RecordingRunOpenResult.InvalidTime, repository.open(source, "run-fixture", "owner-fixture", 999) { true })
                assertEquals(1, database.practiceSessionDao().finish(source.sessionId, 1_500))
                assertEquals(RecordingRunOpenResult.SourceChanged, repository.open(source, "run-fixture", "owner-fixture", 2_000) { true })
                assertTrue(repository.observe(source.sessionId).first().isEmpty())
            }
        }

    @Test fun `one open run per session is enforced and terminal run identities cannot be reused`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database)
                val repository = RoomRecordingRunRepository(database)
                val first = opened(repository.open(source, "run-fixture", "owner-fixture", 2_000) { true })
                assertEquals(RecordingRunOpenResult.AlreadyOpen, repository.open(source, "other-fixture", "other-owner", 2_500) { true })
                assertEquals(listOf(first), repository.observe(source.sessionId).first())
                assertTrue(repository.finish(first.runId, first.processId, RecordingRunStatus.STOPPED, null))
                val before = repository.observe(source.sessionId).first()
                val failure = runCatching { repository.open(source, first.runId, first.processId, 3_000) { true } }.exceptionOrNull()
                assertTrue("Reused recording identities must fail", failure is IllegalStateException || failure is SQLiteException)
                assertEquals(before, repository.observe(source.sessionId).first())
                assertTrue(repository.open(source, "fresh-fixture", first.processId, 3_000) { true } is RecordingRunOpenResult.Opened)
            }
        }

    @Test fun `denied or cancelled open rolls back even after insertion`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database)
                val repository = RoomRecordingRunRepository(database)
                assertEquals(RecordingRunOpenResult.Blocked, repository.open(source, "run-fixture", "owner-fixture", 2_000) { false })
                var checks = 0
                assertEquals(
                    RecordingRunOpenResult.Blocked,
                    repository.open(source, "run-fixture", "owner-fixture", 2_000) { ++checks == 1 },
                )
                assertEquals(2, checks)
                val worker =
                    launch(Dispatchers.Default) {
                        val owner = currentCoroutineContext()[Job]
                        var permits = 0
                        repository.open(source, "run-fixture", "owner-fixture", 2_000) {
                            if (++permits == 2) owner?.cancel()
                            true
                        }
                    }
                worker.join()
                assertTrue(worker.isCancelled)
                assertTrue(repository.observe(source.sessionId).first().isEmpty())
            }
        }

    @Test fun `cancelling an enclosing transaction rolls back an acknowledged open`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database)
                val repository = RoomRecordingRunRepository(database)
                val inserted = CompletableDeferred<Unit>()
                val worker =
                    launch(Dispatchers.Default) {
                        database.withTransaction {
                            opened(repository.open(source, "run-fixture", "owner-fixture", 2_000) { true })
                            inserted.complete(Unit)
                            awaitCancellation()
                        }
                    }
                withTimeout(10_000) { inserted.await() }
                worker.cancelAndJoin()
                assertTrue(repository.observe(source.sessionId).first().isEmpty())
            }
        }

    @Test fun `finish updates only the exact open owner and accepts explicit unknown terminal time`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database)
                val repository = RoomRecordingRunRepository(database)
                val run = opened(repository.open(source, "run-fixture", "owner-fixture", 2_000) { true })
                assertFalse(repository.finish(run.runId, "other-owner", RecordingRunStatus.STOPPED, 3_000))
                assertFalse(repository.finish("missing-fixture", run.processId, RecordingRunStatus.STOPPED, 3_000))
                assertFalse(repository.finish(run.runId, run.processId, RecordingRunStatus.OPEN, null))
                assertFalse(repository.finish(run.runId, run.processId, RecordingRunStatus.INTERRUPTED, null))
                assertFalse(repository.finish(run.runId, run.processId, RecordingRunStatus.STOPPED, 1_999))
                assertEquals(listOf(run), repository.observe(source.sessionId).first())
                assertEquals(1, database.practiceSessionDao().finish(source.sessionId, 3_000))
                assertTrue(repository.finish(run.runId, run.processId, RecordingRunStatus.FAILED, null))
                assertEquals(listOf(run.copy(status = RecordingRunStatus.FAILED)), repository.observe(source.sessionId).first())
                assertFalse(repository.finish(run.runId, run.processId, RecordingRunStatus.STOPPED, 4_000))
            }
        }

    @Test fun `recovery marks only older process open rows and never invents an end time or restarts`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database)
                val repository = RoomRecordingRunRepository(database)
                val stopped = opened(repository.open(source, "stopped-fixture", "old-owner", 1_100) { true })
                assertTrue(repository.finish(stopped.runId, stopped.processId, RecordingRunStatus.STOPPED, 1_500))
                val interrupted = opened(repository.open(source, "interrupted-fixture", "old-owner", 2_000) { true })
                repository.recoverInterrupted("current-owner")
                val expected =
                    listOf(
                        stopped.copy(status = RecordingRunStatus.STOPPED, endedAtEpochMillis = 1_500),
                        interrupted.copy(status = RecordingRunStatus.INTERRUPTED),
                    )
                assertEquals(expected, repository.observe(source.sessionId).first())
                val current = opened(repository.open(source, "current-fixture", "current-owner", 3_000) { true })
                repository.recoverInterrupted("current-owner")
                assertEquals(expected + current, repository.observe(source.sessionId).first())
                assertFalse(repository.finish(interrupted.runId, interrupted.processId, RecordingRunStatus.STOPPED, 4_000))
            }
        }

    @Test fun `changed recovered run invalidates deletion confirmation and deleted runs are never resurrected`() =
        runBlocking {
            withDatabase { database ->
                val active = seed(database)
                val repository = RoomRecordingRunRepository(database)
                val run = opened(repository.open(active, "run-fixture", "old-owner", 2_000) { true })
                assertEquals(1, database.practiceSessionDao().finish(active.sessionId, 3_000))
                val ended = checkNotNull(database.practiceSessionDao().find(active.sessionId)).session()
                val deletions = RoomSessionDeletionRepository(database)
                val target = (deletions.prepare(ended) { true } as SessionDeletionPreparation.Ready).target
                assertEquals(listOf(run), target.recordingRuns)
                repository.recoverInterrupted("current-owner")
                assertEquals(SessionDeletionResult.Changed, deletions.delete(target) { true })
                val refreshed = (deletions.prepare(ended) { true } as SessionDeletionPreparation.Ready).target
                assertEquals(RecordingRunStatus.INTERRUPTED, refreshed.recordingRuns.single().status)
                assertEquals(SessionDeletionResult.Deleted, deletions.delete(refreshed) { true })
                assertFalse(repository.finish(run.runId, run.processId, RecordingRunStatus.FAILED, null))
                repository.recoverInterrupted("current-owner")
                assertTrue(repository.observe(ended.sessionId).first().isEmpty())
                database.openHelper.writableDatabase
                    .query("PRAGMA foreign_key_check")
                    .use { assertEquals(0, it.count) }
            }
        }

    @Test fun `new run and terminal state changes are exact compared deletion fields`() =
        runBlocking {
            withDatabase { database ->
                val active = seed(database)
                val repository = RoomRecordingRunRepository(database)
                val run = opened(repository.open(active, "run-fixture", "owner-fixture", 2_000) { true })
                assertEquals(1, database.practiceSessionDao().finish(active.sessionId, 3_000))
                val source = checkNotNull(database.practiceSessionDao().find(active.sessionId)).session()
                val deletions = RoomSessionDeletionRepository(database)
                val target = (deletions.prepare(source) { true } as SessionDeletionPreparation.Ready).target
                assertEquals(SessionDeletionResult.Changed, deletions.delete(target.copy(recordingRuns = emptyList())) { true })
                assertTrue(repository.finish(run.runId, run.processId, RecordingRunStatus.STOPPED, 3_000))
                assertEquals(SessionDeletionResult.Changed, deletions.delete(target) { true })
                assertTrue(database.practiceSessionDao().find(source.sessionId) != null)
            }
        }

    @Test fun `malformed physical storage and state combinations fail rather than become normal runs`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database)
                val repository = RoomRecordingRunRepository(database)
                val valid =
                    VehicleRecordingRunEntity("run-fixture", source.sessionId, "owner-fixture", 2_000, null, "OPEN", source.sessionId)
                val mutations =
                    listOf(
                        "startedAtEpochMillis = 2000.5",
                        "status = 'STOPPED', openSessionId = NULL, endedAtEpochMillis = 2500.5",
                        "status = CAST('OPEN' AS BLOB)",
                        "status = 'FUTURE'",
                        "processId = CAST('owner-fixture' AS BLOB)",
                        "runId = CAST('run-fixture' AS BLOB)",
                        "openSessionId = CAST(sessionId AS BLOB)",
                        "startedAtEpochMillis = 0",
                        "endedAtEpochMillis = 2500",
                        "status = 'INTERRUPTED', endedAtEpochMillis = 2500, openSessionId = NULL",
                        "status = 'STOPPED'",
                    )
                for (mutation in mutations) {
                    database.openHelper.writableDatabase.execSQL("DELETE FROM vehicle_recording_run")
                    database.vehicleRecordingRunDao().insert(valid)
                    database.openHelper.writableDatabase.execSQL("UPDATE vehicle_recording_run SET $mutation")
                    expectInvalid { repository.observe(source.sessionId).first() }
                    expectInvalid { repository.recoverInterrupted("current-owner") }
                }
            }
        }

    @Test fun `SQLite insertion failure propagates and never leaves an open marker`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database)
                val repository = RoomRecordingRunRepository(database)
                database.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER reject_recording_fixture AFTER INSERT ON vehicle_recording_run " +
                        "BEGIN SELECT RAISE(ABORT, 'fixture insert failure'); END",
                )
                try {
                    repository.open(source, "run-fixture", "owner-fixture", 2_000) { true }
                    fail("A failed insert must not return an open marker")
                } catch (_: SQLiteException) {
                    assertTrue(repository.observe(source.sessionId).first().isEmpty())
                }
            }
        }

    @Test fun `concurrent recovery and deletion are serializable without resurrecting markers`() =
        runBlocking {
            withDatabase { database ->
                val active = seed(database)
                val repository = RoomRecordingRunRepository(database)
                opened(repository.open(active, "run-fixture", "old-owner", 2_000) { true })
                assertEquals(1, database.practiceSessionDao().finish(active.sessionId, 3_000))
                val source = checkNotNull(database.practiceSessionDao().find(active.sessionId)).session()
                val deletions = RoomSessionDeletionRepository(database)
                val target = (deletions.prepare(source) { true } as SessionDeletionPreparation.Ready).target
                val start = CompletableDeferred<Unit>()
                val deletion =
                    async(Dispatchers.Default) {
                        start.await()
                        deletions.delete(target) { true }
                    }
                val recovery =
                    async(Dispatchers.Default) {
                        start.await()
                        repository.recoverInterrupted("current-owner")
                    }
                start.complete(Unit)
                val result = deletion.await()
                recovery.await()
                when (result) {
                    SessionDeletionResult.Deleted -> assertTrue(repository.observe(source.sessionId).first().isEmpty())
                    SessionDeletionResult.Changed ->
                        assertEquals(
                            RecordingRunStatus.INTERRUPTED,
                            repository
                                .observe(source.sessionId)
                                .first()
                                .single()
                                .status,
                        )
                    else -> fail("Concurrent recovery and deletion must have a serializable result")
                }
            }
        }

    private fun opened(result: RecordingRunOpenResult) = (result as RecordingRunOpenResult.Opened).run

    private suspend fun seed(database: PreparationDatabase): PracticeSession {
        val entity =
            PracticeSessionEntity(
                sessionId = "session-fixture",
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
                endedAtEpochMillis = null,
                activeSlot = 1,
            )
        database.practiceSessionDao().insert(entity)
        return entity.session()
    }

    private suspend fun <T> withDatabase(block: suspend (PreparationDatabase) -> T): T {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PreparationDatabase::class.java).build()
        return try {
            block(database)
        } finally {
            database.close()
        }
    }

    private suspend fun expectInvalid(action: suspend () -> Any?) {
        try {
            action()
            fail("Malformed persisted recording state must fail decoding")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalStateException) {
            // Checked physical storage and state invariants reject corrupted fixtures.
        } catch (_: IllegalArgumentException) {
            // Unknown enum values are errors, never a fallback state.
        } catch (_: SQLiteException) {
            // SQLite can reject corrupt types before Room restores the checked row.
        }
    }
}
