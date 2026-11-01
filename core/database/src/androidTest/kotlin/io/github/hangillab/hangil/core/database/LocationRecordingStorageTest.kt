package io.github.hangillab.hangil.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.domain.LocationRecordingAppendResult
import io.github.hangillab.hangil.core.domain.LocationRecordingBeginResult
import io.github.hangillab.hangil.core.domain.LocationRecordingEndResult
import io.github.hangillab.hangil.core.domain.LocationRecordingRunStatus
import io.github.hangillab.hangil.core.domain.LocationRecordingStopReason
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionDeletionResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Actual Android Room in a unique library-test DB; readings/gates are controlled, never real SDK locations. */
@RunWith(AndroidJUnit4::class)
class LocationRecordingStorageTest {
    @Test fun isolatedRoundTripRecoveryAndCascadeUseActualRoom() =
        runBlocking {
            withOwnedDatabase { open ->
                val db = open()
                val entity = sourceEntity()
                db.practiceSessionDao().insert(entity)
                val source = entity.session()
                val repository = RoomLocationRecordingRepository(db)
                val run =
                    (
                        repository.begin(
                            source,
                            "fixture-run",
                            "old-process",
                            500,
                            100,
                        ) { true } as LocationRecordingBeginResult.Begun
                    ).run
                val reading = LocationUpdateReading.Sample(LocationUpdateSample(12.5, 34.5, -4.0, 421f, -5f, -42, 101))
                assertTrue(repository.append(source, run, reading, 400) { true } is LocationRecordingAppendResult.Appended)
                val before = repository.observe(source.sessionId).first()
                db.close()
                val reopened = open()
                val after = RoomLocationRecordingRepository(reopened)
                assertEquals(before, after.observe(source.sessionId).first())
                after.recoverInterrupted("new-process")
                val interrupted = after.observe(source.sessionId).first()
                assertEquals(LocationRecordingRunStatus.INTERRUPTED, interrupted.runs.single().status)
                assertEquals(null, interrupted.runs.single().endedAtEpochMillis)
                assertEquals(before.events, interrupted.events)
                reopened.practiceSessionDao().finish(source.sessionId, 2_000)
                val ended = checkNotNull(reopened.practiceSessionDao().find(source.sessionId)).session()
                val deletions = RoomSessionDeletionRepository(reopened)
                val target = (deletions.prepare(ended) { true } as SessionDeletionPreparation.Ready).target
                assertEquals(interrupted, target.locationRecording)
                assertEquals(SessionDeletionResult.Deleted, deletions.delete(target) { true })
                assertTrue(
                    after
                        .observe(source.sessionId)
                        .first()
                        .events
                        .isEmpty(),
                )
                assertTrue(
                    after
                        .observe(source.sessionId)
                        .first()
                        .runs
                        .isEmpty(),
                )
                assertEquals(
                    LocationRecordingEndResult.MissingRun,
                    after.end(run, LocationRecordingRunStatus.FAILED, LocationRecordingStopReason.WRITE_ERROR, null, null),
                )
                reopened.openHelper.readableDatabase
                    .query("PRAGMA foreign_key_check")
                    .use { assertEquals(0, it.count) }
            }
        }

    @Test fun revokedCommitPredicateRollsBackNativeAppendButCanPreserveDeniedMetadata() =
        runBlocking {
            withOwnedDatabase { open ->
                val db = open()
                val entity = sourceEntity()
                db.practiceSessionDao().insert(entity)
                val source = entity.session()
                val repository = RoomLocationRecordingRepository(db)
                val run =
                    (
                        repository.begin(
                            source,
                            "fixture-run",
                            "process",
                            2_000,
                            100,
                        ) { true } as LocationRecordingBeginResult.Begun
                    ).run
                val reading = LocationUpdateReading.Sample(LocationUpdateSample(12.5, 34.5, 0.0, 0f, 0f, 0, 101))
                var checks = 0
                assertEquals(LocationRecordingAppendResult.Blocked, repository.append(source, run, reading, 2_100) { ++checks == 1 })
                assertEquals(2, checks)
                assertTrue(
                    repository
                        .observe(source.sessionId)
                        .first()
                        .events
                        .isEmpty(),
                )
                assertTrue(
                    repository.append(source, run, LocationUpdateReading.PermissionDenied(102), 2_100) {
                        true
                    } is LocationRecordingAppendResult.Appended,
                )
                db.openHelper.readableDatabase
                    .query(
                        "SELECT latitude, longitude, altitudeRaw, headingRaw, confidenceRaw, sdkTimestampMillis FROM " +
                            "location_recording_event",
                    ).use { cursor ->
                        check(cursor.moveToFirst())
                        repeat(cursor.columnCount) { assertTrue(cursor.isNull(it)) }
                    }
                assertTrue(
                    repository.end(
                        run,
                        LocationRecordingRunStatus.FAILED,
                        LocationRecordingStopReason.PERMISSION_DENIED,
                        2_200,
                        103,
                    ) is LocationRecordingEndResult.Ended,
                )
                assertEquals(7, db.openHelper.readableDatabase.version)
            }
        }

    private fun sourceEntity() =
        PracticeSessionEntity(
            sessionId = "controlled-source",
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

    private suspend fun withOwnedDatabase(block: suspend (() -> PreparationDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "io.github.hangillab.hangil.core.database.test")
        val name = "location-fixture-${UUID.randomUUID()}.db"
        check(!context.getDatabasePath(name).exists())
        val handles = mutableListOf<PreparationDatabase>()
        var primary: Throwable? = null
        try {
            block { Room.databaseBuilder(context, PreparationDatabase::class.java, name).build().also(handles::add) }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            var allClosed = true
            for (handle in handles) {
                try {
                    handle.close()
                } catch (cleanup: Throwable) {
                    allClosed = false
                    val previous = primary ?: cleanupFailure
                    if (previous == null) {
                        cleanupFailure = cleanup
                    } else if (previous !== cleanup) {
                        previous.addSuppressed(cleanup)
                    }
                }
            }
            if (allClosed) {
                try {
                    if (context.getDatabasePath(name).exists()) check(context.deleteDatabase(name))
                } catch (cleanup: Throwable) {
                    val previous = primary
                    if (previous == null) cleanupFailure = cleanup else previous.addSuppressed(cleanup)
                }
            }
            cleanupFailure?.let { throw it }
        }
    }
}
