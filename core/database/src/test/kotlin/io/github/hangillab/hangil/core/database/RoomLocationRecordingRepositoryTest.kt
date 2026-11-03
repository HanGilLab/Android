package io.github.hangillab.hangil.core.database

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.room.withTransaction
import io.github.hangillab.hangil.core.domain.LocationRecordingAppendResult
import io.github.hangillab.hangil.core.domain.LocationRecordingBeginResult
import io.github.hangillab.hangil.core.domain.LocationRecordingEndResult
import io.github.hangillab.hangil.core.domain.LocationRecordingRepository
import io.github.hangillab.hangil.core.domain.LocationRecordingRun
import io.github.hangillab.hangil.core.domain.LocationRecordingRunStatus
import io.github.hangillab.hangil.core.domain.LocationRecordingSnapshot
import io.github.hangillab.hangil.core.domain.LocationRecordingStopReason
import io.github.hangillab.hangil.core.domain.LocationUpdateErrorOrigin
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import io.github.hangillab.hangil.core.domain.PracticeSession
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

/** Real Room, controlled payloads/clocks/permission predicates. No actual SDK or permission grant. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomLocationRecordingRepositoryTest {
    @Test fun `raw update payload and append order survive positive wall clock reversal`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repo = RoomLocationRecordingRepository(db)
                val run = begun(repo.begin(source, "run", "process", 500, 100) { true })
                val first = sample(101)
                val second = sample(101).copy(sample = first.sample.copy(sdkTimestampMillis = -99, confidenceRaw = -7f))
                appended(repo.append(source, run, first, 400) { true })
                appended(repo.append(source, run, second, 300) { true })
                val snapshot = repo.observe(source.sessionId).first()
                assertEquals(listOf(first, second), snapshot.events.map { it.reading })
                assertEquals(listOf(400L, 300L), snapshot.events.map { it.observedAtEpochMillis })
                assertEquals(listOf(1, 2), snapshot.events.map { it.ordinal })
                assertTrue(snapshot.events.zipWithNext().all { (a, b) -> a.id < b.id })
                assertTrue(
                    repo.end(
                        run,
                        LocationRecordingRunStatus.STOPPED,
                        LocationRecordingStopReason.USER_STOP,
                        200,
                        102,
                    ) is LocationRecordingEndResult.Ended,
                )
                assertEquals(source, db.practiceSessionDao().find(source.sessionId)?.session())
            }
        }

    @Test fun `pointless statuses remain NULL rather than fabricated zero coordinates`() =
        runBlocking {
            val readings =
                listOf<LocationUpdateReading>(
                    LocationUpdateReading.Empty(101),
                    LocationUpdateReading.PermissionDenied(101),
                    LocationUpdateReading.Unsupported(101),
                    LocationUpdateReading.NoUpdate(101),
                    LocationUpdateReading.Error(LocationUpdateErrorOrigin.SDK_CALLBACK, IllegalStateException::class.java.name, 101),
                    LocationUpdateReading.Error(LocationUpdateErrorOrigin.ADAPTER, IllegalArgumentException::class.java.name, 101),
                )
            for (reading in readings) {
                withDatabase { db ->
                    val source = seed(db)
                    val repo = RoomLocationRecordingRepository(db)
                    val run = begun(repo.begin(source, "run", "process", 2_000, 100) { true })
                    val event = appended(repo.append(source, run, reading, 2_100) { true }).event
                    assertEquals(reading, event.reading)
                    db.openHelper.readableDatabase
                        .query(
                            "SELECT latitude, longitude, altitudeRaw, headingRaw, confidenceRaw, sdkTimestampMillis, sampleSource FROM location_recording_event",
                        ).use { cursor ->
                            check(cursor.moveToFirst())
                            repeat(cursor.columnCount) { assertTrue(cursor.isNull(it)) }
                        }
                }
            }
        }

    @Test fun `begin checks every source field and distinguishes missing from the same ended source`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repo = RoomLocationRecordingRepository(db)
                val s = source.selection
                val changed =
                    listOf(
                        source.copy(selection = s.copy(goalId = "other")),
                        source.copy(selection = s.copy(courseId = "other")),
                        source.copy(selection = s.copy(taskId = "other")),
                        source.copy(selection = s.copy(courseVersion = 2)),
                        source.copy(selection = s.copy(briefingVersion = 2)),
                        source.copy(goalTitle = "other"),
                        source.copy(courseTitle = "other"),
                        source.copy(taskTitle = "other"),
                        source.copy(startedAtEpochMillis = 999),
                        source.copy(endedAtEpochMillis = 2_000),
                    )
                for (expected in changed) {
                    assertEquals(
                        LocationRecordingBeginResult.SourceChanged,
                        repo.begin(
                            expected,
                            "run",
                            "process",
                            2_000,
                            100,
                        ) {
                            true
                        },
                    )
                }
                assertEquals(
                    LocationRecordingBeginResult.MissingSession,
                    repo.begin(
                        source.copy(sessionId = "absent"),
                        "run",
                        "process",
                        2_000,
                        100,
                    ) {
                        true
                    },
                )
                assertEquals(1, db.practiceSessionDao().finish(source.sessionId, 2_000))
                assertEquals(LocationRecordingBeginResult.FinishedSession, repo.begin(source, "run", "process", 2_100, 100) { true })
                db.openHelper.writableDatabase.execSQL("UPDATE practice_session SET goalTitle = 'changed'")
                assertEquals(LocationRecordingBeginResult.SourceChanged, repo.begin(source, "run", "process", 2_100, 100) { true })
                assertTrue(
                    repo
                        .observe(source.sessionId)
                        .first()
                        .runs
                        .isEmpty(),
                )
            }
        }

    @Test fun `open is global and run identities are never reused after termination`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repo = RoomLocationRecordingRepository(db)
                val run = begun(repo.begin(source, "run", "process", 2_000, 100) { true })
                assertEquals(LocationRecordingBeginResult.AlreadyOpen, repo.begin(source, "other", "process", 2_100, 101) { true })
                assertEquals(1, db.practiceSessionDao().finish(source.sessionId, 3_000))
                val next = seed(db, "next")
                assertEquals(LocationRecordingBeginResult.AlreadyOpen, repo.begin(next, "next-run", "process", 3_100, 102) { true })
                repo.end(run, LocationRecordingRunStatus.STOPPED, LocationRecordingStopReason.SESSION_FINISHED, 3_000, 102)
                expectInvalid { repo.begin(next, "run", "process", 3_100, 103) { true } }
                assertTrue(repo.begin(next, "fresh", "process", 3_100, 103) { true } is LocationRecordingBeginResult.Begun)
            }
        }

    @Test fun `begin predicate denial after insertion rolls back row and sequence`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repo = RoomLocationRecordingRepository(db)
                val before = sequence(db)
                var reads = 0
                assertEquals(LocationRecordingBeginResult.Blocked, repo.begin(source, "run", "process", 2_000, 100) { ++reads == 1 })
                assertEquals(2, reads)
                assertTrue(
                    repo
                        .observe(source.sessionId)
                        .first()
                        .runs
                        .isEmpty(),
                )
                assertEquals(before, sequence(db))
            }
        }

    @Test fun `append predicate denial after insert rolls back the event and sequence`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repo = RoomLocationRecordingRepository(db)
                val run = begun(repo.begin(source, "run", "process", 2_000, 100) { true })
                val before = sequence(db)
                var reads = 0
                assertEquals(LocationRecordingAppendResult.Blocked, repo.append(source, run, sample(101), 2_100) { ++reads == 1 })
                assertEquals(2, reads)
                assertTrue(
                    repo
                        .observe(source.sessionId)
                        .first()
                        .events
                        .isEmpty(),
                )
                assertEquals(before, sequence(db))
            }
        }

    @Test fun `actual job cancellation during the final predicate rolls back begin and append`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repo = RoomLocationRecordingRepository(db)
                val opening =
                    launch(Dispatchers.Default) {
                        val job = currentCoroutineContext()[Job]
                        var checks = 0
                        repo.begin(source, "cancelled", "process", 2_000, 100) {
                            if (++checks == 2) job?.cancel()
                            true
                        }
                    }
                opening.join()
                assertTrue(opening.isCancelled)
                assertTrue(
                    repo
                        .observe(source.sessionId)
                        .first()
                        .runs
                        .isEmpty(),
                )
                val run = begun(repo.begin(source, "run", "process", 2_000, 100) { true })
                val appending =
                    launch(Dispatchers.Default) {
                        val job = currentCoroutineContext()[Job]
                        var checks = 0
                        repo.append(source, run, sample(101), 2_100) {
                            if (++checks == 2) job?.cancel()
                            true
                        }
                    }
                appending.join()
                assertTrue(appending.isCancelled)
                assertTrue(
                    repo
                        .observe(source.sessionId)
                        .first()
                        .events
                        .isEmpty(),
                )
            }
        }

    @Test fun `an enclosing cancelled transaction rolls back an acknowledged append`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repo = RoomLocationRecordingRepository(db)
                val run = begun(repo.begin(source, "run", "process", 2_000, 100) { true })
                val inserted = CompletableDeferred<Unit>()
                val worker =
                    launch(Dispatchers.Default) {
                        db.withTransaction {
                            appended(repo.append(source, run, sample(101), 2_100) { true })
                            inserted.complete(Unit)
                            awaitCancellation()
                        }
                    }
                withTimeout(10_000) { inserted.await() }
                worker.cancelAndJoin()
                assertTrue(
                    repo
                        .observe(source.sessionId)
                        .first()
                        .events
                        .isEmpty(),
                )
            }
        }

    @Test fun `append requires the frozen open owner and exact active source`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repo = RoomLocationRecordingRepository(db)
                val run = begun(repo.begin(source, "run", "process", 2_000, 100) { true })
                assertEquals(
                    LocationRecordingAppendResult.OwnerChanged,
                    repo.append(source, run.copy(processId = "foreign"), sample(101), 2_100) { true },
                )
                assertEquals(
                    LocationRecordingAppendResult.MissingRun,
                    repo.append(source, run.copy(runId = "missing"), sample(101), 2_100) { true },
                )
                assertEquals(
                    LocationRecordingAppendResult.SourceChanged,
                    repo.append(source.copy(goalTitle = "other"), run, sample(101), 2_100) { true },
                )
                assertEquals(1, db.practiceSessionDao().finish(source.sessionId, 3_000))
                assertEquals(LocationRecordingAppendResult.FinishedSession, repo.append(source, run, sample(101), 3_100) { true })
                db.openHelper.writableDatabase.execSQL("UPDATE practice_session SET goalTitle = 'different'")
                assertEquals(LocationRecordingAppendResult.SourceChanged, repo.append(source, run, sample(101), 3_100) { true })
                assertTrue(
                    repo
                        .observe(source.sessionId)
                        .first()
                        .events
                        .isEmpty(),
                )
            }
        }

    @Test fun `sample and terminal metadata use current predicates and terminal metadata closes append ownership`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repo = RoomLocationRecordingRepository(db)
                val run = begun(repo.begin(source, "run", "process", 2_000, 100) { true })
                assertEquals(LocationRecordingAppendResult.Blocked, repo.append(source, run, sample(101), 2_100) { false })
                val denied = LocationUpdateReading.PermissionDenied(102)
                appended(repo.append(source, run, denied, 2_100) { true })
                assertEquals(LocationRecordingAppendResult.RunClosed, repo.append(source, run, sample(103), 2_200) { true })
                assertEquals(
                    listOf(denied),
                    repo
                        .observe(source.sessionId)
                        .first()
                        .events
                        .map { it.reading },
                )
            }
        }

    @Test fun `receipt regression and nonpositive wall time are rejected without reordering`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repo = RoomLocationRecordingRepository(db)
                val run = begun(repo.begin(source, "run", "process", 2_000, 100) { true })
                assertEquals(LocationRecordingAppendResult.InvalidTime, repo.append(source, run, sample(99), 2_100) { true })
                assertEquals(LocationRecordingAppendResult.InvalidTime, repo.append(source, run, sample(101), 0) { true })
                appended(repo.append(source, run, sample(102), 2_100) { true })
                assertEquals(LocationRecordingAppendResult.InvalidTime, repo.append(source, run, sample(101), 2_200) { true })
                assertEquals(
                    1,
                    repo
                        .observe(source.sessionId)
                        .first()
                        .events.size,
                )
            }
        }

    @Test fun `controlled last slot fixture admits only one concurrent append at the cap`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repo = RoomLocationRecordingRepository(db)
                val run = begun(repo.begin(source, "run", "process", 2_000, 100) { true })
                // Seed the boundary through actual DAO inserts; only the final racing calls use append.
                db.withTransaction {
                    repeat(LocationRecordingRepository.MAX_EVENTS_PER_RUN - 1) { index ->
                        db.locationRecordingDao().insertEvent(
                            LocationRecordingEventEntity.from(
                                source.sessionId,
                                run.runId,
                                index + 1,
                                2_100,
                                LocationUpdateReading.Empty(101),
                            ),
                        )
                    }
                }
                val start = CompletableDeferred<Unit>()
                val a =
                    async(Dispatchers.Default) {
                        start.await()
                        repo.append(source, run, sample(102), 2_200) { true }
                    }
                val b =
                    async(Dispatchers.Default) {
                        start.await()
                        repo.append(source, run, LocationUpdateReading.Empty(102), 2_200) { true }
                    }
                start.complete(Unit)
                val results = listOf(a.await(), b.await())
                assertEquals(1, results.count { it is LocationRecordingAppendResult.Appended && it.reachedLimit })
                assertEquals(1, results.count { it == LocationRecordingAppendResult.LimitReached })
                assertEquals(
                    1024,
                    repo
                        .observe(source.sessionId)
                        .first()
                        .events.size,
                )
            }
        }

    @Test fun `durable terminal owner receipt confirms the first marker without reconstructing OPEN`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repo = RoomLocationRecordingRepository(db)
                val opened = begun(repo.begin(source, "terminal-receipt", "process", 2_000, 100) { true })
                appended(repo.append(source, opened, sample(102), 2_100) { true })
                val first =
                    repo.end(opened, LocationRecordingRunStatus.FAILED, LocationRecordingStopReason.WRITE_ERROR, null, null)
                        as LocationRecordingEndResult.Ended
                val durable = repo.observe(source.sessionId).first()
                val receipt = durable.runs.single()
                val beforeSequence = sequence(db)
                assertEquals(first.run, receipt)
                assertEquals(LocationRecordingRunStatus.FAILED, receipt.status)
                assertEquals(
                    LocationRecordingEndResult.AlreadyEnded(first.run),
                    repo.end(receipt, LocationRecordingRunStatus.STOPPED, LocationRecordingStopReason.USER_STOP, 3_000, 103),
                )
                assertEquals(durable, repo.observe(source.sessionId).first())
                assertEquals(beforeSequence, sequence(db))
                assertEquals(source, db.practiceSessionDao().find(source.sessionId)?.session())
                assertEquals(null, receipt.endedAtEpochMillis)
                assertEquals(null, receipt.endedAtElapsedRealtimeMillis)
            }
        }

    @Test fun `terminal cleanup receipt still rejects every changed immutable owner tuple`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repo = RoomLocationRecordingRepository(db)
                val opened = begun(repo.begin(source, "terminal-owner", "process", 2_000, 100) { true })
                appended(repo.append(source, opened, sample(102), 2_100) { true })
                assertTrue(
                    repo.end(opened, LocationRecordingRunStatus.STOPPED, LocationRecordingStopReason.USER_STOP, 3_000, 110)
                        is LocationRecordingEndResult.Ended,
                )
                val durable = repo.observe(source.sessionId).first()
                val receipt = durable.runs.single()
                val beforeSequence = sequence(db)
                val changed =
                    listOf(
                        receipt.copy(processId = "foreign-process"),
                        receipt.copy(source = source.copy(sessionId = "foreign-session")),
                        receipt.copy(source = source.copy(goalTitle = "changed source")),
                        receipt.copy(source = source.copy(selection = source.selection.copy(courseVersion = 2))),
                        receipt.copy(startedAtEpochMillis = 2_001),
                        receipt.copy(startedAtElapsedRealtimeMillis = 101),
                    )
                for (foreign in changed) {
                    assertEquals(
                        LocationRecordingEndResult.OwnerChanged,
                        repo.end(foreign, LocationRecordingRunStatus.FAILED, LocationRecordingStopReason.WRITE_ERROR, null, null),
                    )
                    assertEquals(durable, repo.observe(source.sessionId).first())
                    assertEquals(beforeSequence, sequence(db))
                }
            }
        }

    @Test fun `exact cleanup survives source end but preserves the first terminal marker`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repo = RoomLocationRecordingRepository(db)
                val run = begun(repo.begin(source, "run", "process", 2_000, 100) { true })
                appended(repo.append(source, run, sample(102), 2_100) { true })
                assertEquals(
                    LocationRecordingEndResult.InvalidTime,
                    repo.end(run, LocationRecordingRunStatus.STOPPED, LocationRecordingStopReason.USER_STOP, 2_200, 101),
                )
                assertEquals(
                    LocationRecordingEndResult.InvalidTime,
                    repo.end(run, LocationRecordingRunStatus.STOPPED, LocationRecordingStopReason.USER_STOP, 2_200, null),
                )
                assertEquals(
                    LocationRecordingEndResult.InvalidTermination,
                    repo.end(run, LocationRecordingRunStatus.OPEN, LocationRecordingStopReason.USER_STOP, null, null),
                )
                assertEquals(
                    LocationRecordingEndResult.OwnerChanged,
                    repo.end(
                        run.copy(processId = "other"),
                        LocationRecordingRunStatus.STOPPED,
                        LocationRecordingStopReason.USER_STOP,
                        null,
                        null,
                    ),
                )
                assertEquals(1, db.practiceSessionDao().finish(source.sessionId, 3_000))
                val ended =
                    repo.end(
                        run,
                        LocationRecordingRunStatus.FAILED,
                        LocationRecordingStopReason.WRITE_ERROR,
                        null,
                        null,
                    ) as LocationRecordingEndResult.Ended
                assertEquals(
                    LocationRecordingEndResult.AlreadyEnded(ended.run),
                    repo.end(run, LocationRecordingRunStatus.STOPPED, LocationRecordingStopReason.USER_STOP, 3_100, 103),
                )
                assertEquals(null, ended.run.endedAtEpochMillis)
                assertEquals(null, ended.run.endedAtElapsedRealtimeMillis)
            }
        }

    @Test fun `recovery preserves events and old interruption while never inventing an end`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repo = RoomLocationRecordingRepository(db)
                val old = begun(repo.begin(source, "old", "old-process", 2_000, 100) { true })
                appended(repo.append(source, old, sample(101), 2_100) { true })
                repo.recoverInterrupted("fresh-process")
                val interrupted = repo.observe(source.sessionId).first()
                assertEquals(LocationRecordingRunStatus.INTERRUPTED, interrupted.runs.single().status)
                assertEquals(null, interrupted.runs.single().endedAtEpochMillis)
                val current = begun(repo.begin(source, "current", "fresh-process", 1_500, 50) { true })
                repo.recoverInterrupted("fresh-process")
                val actual = repo.observe(source.sessionId).first()
                assertEquals(interrupted.runs + current, actual.runs)
                assertEquals(interrupted.events, actual.events)
            }
        }

    @Test fun `deletion compares full location children and cascade cannot resurrect them`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repo = RoomLocationRecordingRepository(db)
                val run = begun(repo.begin(source, "run", "process", 2_000, 100) { true })
                appended(repo.append(source, run, sample(101), 2_100) { true })
                db.practiceSessionDao().finish(source.sessionId, 3_000)
                val ended = checkNotNull(db.practiceSessionDao().find(source.sessionId)).session()
                val deletions = RoomSessionDeletionRepository(db)
                val target = (deletions.prepare(ended) { true } as SessionDeletionPreparation.Ready).target
                repo.end(run, LocationRecordingRunStatus.STOPPED, LocationRecordingStopReason.SESSION_FINISHED, 3_000, 102)
                assertEquals(SessionDeletionResult.Changed, deletions.delete(target) { true })
                val fresh = (deletions.prepare(ended) { true } as SessionDeletionPreparation.Ready).target
                db.openHelper.writableDatabase.execSQL("UPDATE location_recording_event SET latitude = 9.5")
                assertEquals(SessionDeletionResult.Changed, deletions.delete(fresh) { true })
                val latest = (deletions.prepare(ended) { true } as SessionDeletionPreparation.Ready).target
                assertEquals(SessionDeletionResult.Deleted, deletions.delete(latest) { true })
                assertEquals(LocationRecordingSnapshot(emptyList(), emptyList()), repo.observe(source.sessionId).first())
                assertEquals(
                    LocationRecordingEndResult.MissingRun,
                    repo.end(run, LocationRecordingRunStatus.FAILED, LocationRecordingStopReason.WRITE_ERROR, null, null),
                )
                repo.recoverInterrupted("next-process")
                db.openHelper.readableDatabase
                    .query("PRAGMA foreign_key_check")
                    .use { assertEquals(0, it.count) }
            }
        }

    @Test fun `physical type corruption or impossible point shape is never repaired to empty`() =
        runBlocking {
            val corruptions =
                listOf(
                    "UPDATE location_recording_run SET goalTitle = CAST('x' AS BLOB)",
                    "UPDATE location_recording_run SET openSlot = 2",
                    "UPDATE location_recording_event SET latitude = CAST('x' AS BLOB)",
                    "UPDATE location_recording_event SET sdkTimestampMillis = 1.5",
                    "UPDATE location_recording_event SET kind = 'EMPTY'",
                    "UPDATE location_recording_event SET ordinal = 2",
                )
            for (sql in corruptions) {
                withDatabase { db ->
                    val source = seed(db)
                    val repo = RoomLocationRecordingRepository(db)
                    val run = begun(repo.begin(source, "run", "process", 2_000, 100) { true })
                    appended(repo.append(source, run, sample(101), 2_100) { true })
                    db.openHelper.writableDatabase.execSQL(sql)
                    expectInvalid { repo.observe(source.sessionId).first() }
                    expectInvalid { repo.recoverInterrupted("next-process") }
                }
            }
        }

    @Test fun `controlled result loss after actual Room return does not erase its committed open`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repo = RoomLocationRecordingRepository(db)
                val committed = CompletableDeferred<Unit>()
                val worker =
                    launch(Dispatchers.Default) {
                        begun(repo.begin(source, "committed", "process", 2_000, 100) { true })
                        // Adapter delivery is held only after the real Room call returned, not inside Room COMMIT.
                        committed.complete(Unit)
                        awaitCancellation()
                    }
                withTimeout(10_000) { committed.await() }
                worker.cancelAndJoin()
                val actual =
                    repo
                        .observe(source.sessionId)
                        .first()
                        .runs
                        .single()
                assertEquals("committed", actual.runId)
                assertEquals(LocationRecordingRunStatus.OPEN, actual.status)
                assertTrue(
                    repo.end(
                        actual,
                        LocationRecordingRunStatus.STOPPED,
                        LocationRecordingStopReason.USER_STOP,
                        2_100,
                        101,
                    ) is LocationRecordingEndResult.Ended,
                )
                assertEquals(
                    1,
                    repo
                        .observe(source.sessionId)
                        .first()
                        .runs.size,
                )
            }
        }

    @Test fun `SQL append failure propagates and never manufactures a saved event`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repo = RoomLocationRecordingRepository(db)
                val run = begun(repo.begin(source, "run", "process", 2_000, 100) { true })
                val before = repo.observe(source.sessionId).first()
                val beforeSequence = sequence(db)
                db.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER controlled_location_failure BEFORE INSERT ON location_recording_event " +
                        "BEGIN SELECT RAISE(ABORT, 'controlled location insert failure'); END",
                )
                expectInvalid { repo.append(source, run, sample(101), 2_100) { true } }
                assertEquals(before, repo.observe(source.sessionId).first())
                assertEquals(beforeSequence, sequence(db))
            }
        }

    @Test fun `deletion guard denial rolls back session and every location child`() =
        runBlocking {
            withDatabase { db ->
                val source = seed(db)
                val repo = RoomLocationRecordingRepository(db)
                val run = begun(repo.begin(source, "run", "process", 2_000, 100) { true })
                appended(repo.append(source, run, sample(101), 2_100) { true })
                repo.end(run, LocationRecordingRunStatus.STOPPED, LocationRecordingStopReason.USER_STOP, 2_200, 102)
                db.practiceSessionDao().finish(source.sessionId, 3_000)
                val ended = checkNotNull(db.practiceSessionDao().find(source.sessionId)).session()
                val deletions = RoomSessionDeletionRepository(db)
                val target = (deletions.prepare(ended) { true } as SessionDeletionPreparation.Ready).target
                val beforeSequence = sequence(db)
                var checks = 0
                assertEquals(SessionDeletionResult.Blocked, deletions.delete(target) { ++checks == 1 })
                assertEquals(2, checks)
                assertEquals(ended, db.practiceSessionDao().find(source.sessionId)?.session())
                assertEquals(target.locationRecording, repo.observe(source.sessionId).first())
                assertEquals(beforeSequence, sequence(db))
            }
        }

    private fun sample(receipt: Long) = LocationUpdateReading.Sample(LocationUpdateSample(12.5, 34.5, -2.0, 421f, -5f, -42, receipt))

    private fun begun(result: LocationRecordingBeginResult) = (result as LocationRecordingBeginResult.Begun).run

    private fun appended(result: LocationRecordingAppendResult) = result as LocationRecordingAppendResult.Appended

    private suspend fun seed(
        db: PreparationDatabase,
        id: String = "source",
    ): PracticeSession {
        val entity =
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
                endedAtEpochMillis = null,
                activeSlot = 1,
            )
        db.practiceSessionDao().insert(entity)
        return entity.session()
    }

    private fun sequence(db: PreparationDatabase): List<Pair<String, Long>> =
        db.openHelper.readableDatabase.query("SELECT name, seq FROM sqlite_sequence ORDER BY name").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getLong(1)) }
        }

    private suspend fun <T> withDatabase(block: suspend (PreparationDatabase) -> T): T {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PreparationDatabase::class.java).build()
        var primary: Throwable? = null
        try {
            return block(db)
        } catch (
            failure: Throwable,
        ) {
            primary = failure
            throw failure
        } finally {
            try {
                db.close()
            } catch (
                cleanup: Throwable,
            ) {
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
        }
    }

    private suspend fun expectInvalid(block: suspend () -> Any?) {
        try {
            block()
            fail("Malformed location storage must fail")
        } catch (
            cancelled: CancellationException,
        ) {
            throw cancelled
        } catch (_: IllegalStateException) {
        } catch (_: IllegalArgumentException) {
        } catch (_: SQLiteException) {
        }
    }
}
