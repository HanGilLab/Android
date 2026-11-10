package io.github.hangillab.hangil.core.database

import androidx.room.Room
import androidx.room.withTransaction
import io.github.hangillab.hangil.core.domain.LocationRecordingAppendResult
import io.github.hangillab.hangil.core.domain.LocationRecordingBeginResult
import io.github.hangillab.hangil.core.domain.LocationRecordingEvent
import io.github.hangillab.hangil.core.domain.LocationRecordingRun
import io.github.hangillab.hangil.core.domain.LocationRecordingRunStatus
import io.github.hangillab.hangil.core.domain.LocationRecordingStopReason
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import io.github.hangillab.hangil.core.domain.NextPracticeSaveResult
import io.github.hangillab.hangil.core.domain.NextPracticeSeed
import io.github.hangillab.hangil.core.domain.PlannedPracticeRoad
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionDeletionResult
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionReviewRoad
import io.github.hangillab.hangil.core.domain.SessionReviewRoadWriteResult
import io.github.hangillab.hangil.core.domain.SessionReviewSample
import io.github.hangillab.hangil.core.domain.SessionStartResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Real Room, owned callbacks and clocks only. No device coordinates, SDK calls or automatic matching. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomSessionReviewSampleTest {
    private val catalog = LocalPracticeCatalog()
    private val selection = PracticeSelection("commute", "neighborhood", 1, 1, "sequence")

    @Test fun `first road and sample save together only with the exact stored callback and a live final gate`() =
        runBlocking {
            withDatabase { db ->
                val f = seed(db)
                val repo = RoomSessionReviewRoadRepository(db, catalog)
                val locations = RoomLocationRecordingRepository(db)
                val before = locations.observe(f.source.sessionId).first()
                assertEquals(SessionReviewRoadWriteResult.Cleared, repo.clear(f.source, f.review) { true })
                val road = f.review.road
                val wrongOwner =
                    SessionReviewSample(
                        f.anchor.event,
                        "another process",
                        f.anchor.startedAtEpochMillis,
                        f.anchor.startedAtElapsedRealtimeMillis,
                    )
                assertEquals(
                    SessionReviewRoadWriteResult.SampleChanged,
                    repo.save(f.source, road, null, initialSample = wrongOwner) { true },
                )
                assertNull(repo.observe(f.source.sessionId).first())
                var gates = 0
                assertEquals(
                    SessionReviewRoadWriteResult.Blocked,
                    repo.save(f.source, road, null, initialSample = f.anchor) { ++gates < 3 },
                )
                assertEquals(3, gates)
                assertNull(repo.observe(f.source.sessionId).first())
                val linked = saved(repo.save(f.source, road, null, initialSample = f.anchor) { true })
                assertEquals(road, linked.road)
                assertEquals(f.anchor, linked.sampleAnchor)
                val second = SessionReviewSample.from(f.run, f.events[1])
                assertEquals(
                    SessionReviewRoadWriteResult.Conflict,
                    repo.save(f.source, road, linked, initialSample = second) { true },
                )
                assertEquals(linked, repo.observe(f.source.sessionId).first())
                assertEquals(before, locations.observe(f.source.sessionId).first())
            }
        }

    @Test fun `explicit sample identity survives run cleanup while memo replacement and unlink preserve original callbacks`() =
        runBlocking {
            withDatabase { db ->
                val f = seed(db)
                val repo = RoomSessionReviewRoadRepository(db, catalog)
                var linked = saved(repo.setSample(f.source, f.review, f.anchor) { true })
                assertEquals(f.anchor, linked.sampleAnchor)
                assertEquals(linked, saved(repo.setSample(f.source, linked, f.anchor) { true }))
                assertEquals(SessionReviewRoadWriteResult.SamplePresent, repo.clear(f.source, linked) { true })
                val roads = checkNotNull(NextPracticeSeed.resolve(catalog, f.source)).practice.course.roadSnapshots
                assertEquals(SessionReviewRoadWriteResult.SamplePresent, repo.save(f.source, roads[1], linked) { true })
                val locations = RoomLocationRecordingRepository(db)
                locations.end(f.run, LocationRecordingRunStatus.STOPPED, LocationRecordingStopReason.USER_STOP, 2_100, 20)
                assertEquals(linked, repo.observe(f.source.sessionId).first())
                linked = saved(repo.save(f.source, linked.road, linked, memoText = "same road memo") { true })
                assertEquals(f.anchor, linked.sampleAnchor)
                val second = SessionReviewSample.from(f.run, f.events[1])
                assertEquals(f.anchor.sample, second.sample)
                assertNotEquals(f.anchor, second)
                val replaced = saved(repo.setSample(f.source, linked, second) { true })
                assertNotEquals(linked.version, replaced.version)
                assertEquals(SessionReviewRoadWriteResult.Conflict, repo.setSample(f.source, linked, null) { true })
                val unlinked = saved(repo.setSample(f.source, replaced, null) { true })
                assertNull(unlinked.sampleAnchor)
                assertEquals("same road memo", unlinked.memoText)
                val relinked = saved(repo.setSample(f.source, unlinked, f.anchor) { true })
                val emptyMemo = saved(repo.save(f.source, relinked.road, relinked, memoText = "") { true })
                val otherRoad = saved(repo.save(f.source, roads[1], emptyMemo, discardSample = true) { true })
                assertNull(otherRoad.sampleAnchor)
                assertEquals(f.events, locations.observe(f.source.sessionId).first().events)
                assertEquals(SessionReviewRoadWriteResult.Cleared, repo.clear(f.source, otherRoad) { true })
                assertEquals(f.events, locations.observe(f.source.sessionId).first().events)
            }
        }

    @Test fun `source owner and review changes and the last live gate roll back writes`() =
        runBlocking {
            withDatabase { db ->
                val f = seed(db)
                val repo = RoomSessionReviewRoadRepository(db, catalog)
                val before = RoomLocationRecordingRepository(db).observe(f.source.sessionId).first()
                val wrongOwner =
                    SessionReviewSample(
                        f.anchor.event,
                        "another process",
                        f.anchor.startedAtEpochMillis,
                        f.anchor.startedAtElapsedRealtimeMillis,
                    )
                assertEquals(SessionReviewRoadWriteResult.SampleChanged, repo.setSample(f.source, f.review, wrongOwner) { true })
                val absent =
                    SessionReviewSample(
                        f.anchor.event.copy(id = Long.MAX_VALUE),
                        f.anchor.processId,
                        f.anchor.startedAtEpochMillis,
                        f.anchor.startedAtElapsedRealtimeMillis,
                    )
                assertEquals(SessionReviewRoadWriteResult.SampleChanged, repo.setSample(f.source, f.review, absent) { true })
                val mutations =
                    listOf(
                        "UPDATE location_recording_event SET longitude = longitude + 0.1 WHERE id = ${f.anchor.event.id}" to
                            SessionReviewRoadWriteResult.SampleChanged,
                        "UPDATE location_recording_run SET processId = 'changed owner'" to SessionReviewRoadWriteResult.SampleChanged,
                        "UPDATE practice_session SET taskTitle = 'changed source'" to SessionReviewRoadWriteResult.SourceChanged,
                        "UPDATE session_review_road SET memoText = 'changed review'" to SessionReviewRoadWriteResult.Conflict,
                    )
                for ((sql, rejected) in mutations) {
                    var checks = 0
                    assertEquals(
                        rejected,
                        repo.setSample(f.source, f.review, f.anchor) {
                            if (++checks == 2) db.openHelper.writableDatabase.execSQL(sql)
                            true
                        },
                    )
                    assertEquals(f.review, repo.observe(f.source.sessionId).first())
                    assertEquals(before, RoomLocationRecordingRepository(db).observe(f.source.sessionId).first())
                    assertEquals(listOf(f.source), RoomPracticeSessionRepository(db, catalog).observeSessions().first())
                }
                var gates = 0
                assertEquals(SessionReviewRoadWriteResult.Blocked, repo.setSample(f.source, f.review, f.anchor) { ++gates < 3 })
                assertEquals(3, gates)
                assertEquals(f.review, repo.observe(f.source.sessionId).first())
                val cancelled =
                    async {
                        val owner = checkNotNull(currentCoroutineContext()[Job])
                        var checks = 0
                        repo.setSample(f.source, f.review, f.anchor) {
                            if (++checks == 3) owner.cancel(CancellationException("Owned final gate cancellation"))
                            true
                        }
                    }
                try {
                    cancelled.await()
                } catch (_: CancellationException) {
                }
                assertTrue(cancelled.isCancelled)
                assertEquals(f.review, repo.observe(f.source.sessionId).first())
                assertEquals(before, RoomLocationRecordingRepository(db).observe(f.source.sessionId).first())
            }
        }

    @Test fun `changed or deleted events fail observers and reject target and deletion writes`() =
        runBlocking {
            for (delete in listOf(false, true)) {
                withDatabase { db ->
                    val f = seed(db)
                    val repo = RoomSessionReviewRoadRepository(db, catalog)
                    val linked = saved(repo.setSample(f.source, f.review, f.anchor) { true })
                    val seen = CopyOnWriteArrayList<SessionReviewRoad?>()
                    val first = CompletableDeferred<Unit>()
                    val failure = CompletableDeferred<Throwable>()
                    val watching =
                        launch(Dispatchers.Default) {
                            try {
                                repo.observe(f.source.sessionId).collect {
                                    seen.add(it)
                                    first.complete(Unit)
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                failure.complete(error)
                            }
                        }
                    try {
                        withTimeout(10_000) { first.await() }
                        db.withTransaction {
                            val sql =
                                if (delete) {
                                    "DELETE FROM location_recording_event WHERE id = ?"
                                } else {
                                    "UPDATE location_recording_event SET latitude = 38.0 WHERE id = ?"
                                }
                            db.openHelper.writableDatabase.execSQL(sql, arrayOf(f.anchor.event.id))
                        }
                        db.invalidationTracker.refreshAsync()
                        assertTrue(withTimeout(10_000) { failure.await() } is IllegalStateException)
                        assertEquals(listOf(linked), seen.toList())
                        val target = selection.copy(plannedRoad = PlannedPracticeRoad.from(linked))
                        val nextFailure =
                            runCatching {
                                RoomNextPracticeRepository(
                                    db,
                                    catalog,
                                ).saveHistorySelection(checkNotNull(NextPracticeSeed.resolve(catalog, f.source)), target, linked) {
                                    true
                                }
                            }.exceptionOrNull()
                        assertTrue(nextFailure is IllegalStateException)
                        assertEquals(selection, RoomPracticeRepository(db).observeSelection().first())
                        assertTrue(
                            runCatching {
                                RoomSessionDeletionRepository(
                                    db,
                                ).prepare(f.source) { true }
                            }.exceptionOrNull() is IllegalStateException,
                        )
                        assertEquals(listOf(f.source), RoomPracticeSessionRepository(db, catalog).observeSessions().first())
                    } finally {
                        watching.cancelAndJoin()
                    }
                }
            }
        }

    @Test fun `reopen preserves links while next targets omit samples and source deletion cascades`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val name = "review-sample-${UUID.randomUUID()}.db"
            check(!context.getDatabasePath(name).exists())

            fun open() = Room.databaseBuilder(context, PreparationDatabase::class.java, name).build()
            try {
                val linked: SessionReviewRoad
                val f: Fixture
                val first = open()
                try {
                    f = seed(first)
                    linked = saved(RoomSessionReviewRoadRepository(first, catalog).setSample(f.source, f.review, f.anchor) { true })
                } finally {
                    first.close()
                }
                val reopened = open()
                try {
                    assertEquals(linked, RoomSessionReviewRoadRepository(reopened, catalog).observe(f.source.sessionId).first())
                    val target = selection.copy(taskId = "arrival", plannedRoad = PlannedPracticeRoad.from(linked))
                    assertEquals(
                        NextPracticeSaveResult.Saved,
                        RoomNextPracticeRepository(reopened, catalog).saveHistorySelection(
                            checkNotNull(NextPracticeSeed.resolve(catalog, f.source)),
                            target,
                            linked,
                        ) { true },
                    )
                    val publicTarget = checkNotNull(reopened.preparationDao().current()?.plannedRoadJson)
                    assertFalse(
                        publicTarget.contains("\"event\"") || publicTarget.contains("sdkTimestampMillis") ||
                            publicTarget.contains(f.anchor.processId),
                    )
                    val deletions = RoomSessionDeletionRepository(reopened)
                    val original = (deletions.prepare(f.source) { true } as SessionDeletionPreparation.Ready).target
                    assertEquals(f.anchor, original.reviewRoad?.sampleAnchor)
                    assertEquals(SessionDeletionResult.Deleted, deletions.delete(original) { true })
                    assertNull(RoomSessionReviewRoadRepository(reopened, catalog).observe(f.source.sessionId).first())
                    assertTrue(
                        RoomLocationRecordingRepository(reopened)
                            .observe(f.source.sessionId)
                            .first()
                            .events
                            .isEmpty(),
                    )
                    assertEquals(target, RoomPracticeRepository(reopened).observeSelection().first())
                    reopened.openHelper.readableDatabase
                        .query("PRAGMA foreign_key_check")
                        .use { assertEquals(0, it.count) }
                } finally {
                    reopened.close()
                }
            } finally {
                if (context.getDatabasePath(name).exists()) check(context.deleteDatabase(name))
            }
        }

    private suspend fun seed(db: PreparationDatabase): Fixture {
        check(RoomPracticeRepository(db).saveSelection(selection) { true })
        val sessions = RoomPracticeSessionRepository(db, catalog)
        val practice = (catalog.resolve(selection) as Preparation.Ready).practice
        val active = (sessions.start(practice, 1_000) { true } as SessionStartResult.Started).session
        val locations = RoomLocationRecordingRepository(db)
        val run =
            (
                locations.begin(
                    active,
                    "owned-run",
                    "owned-private-process",
                    1_100,
                    10,
                ) { true } as LocationRecordingBeginResult.Begun
            ).run
        val raw = LocationUpdateReading.Sample(LocationUpdateSample(37.0, 127.0, -2.0, 421f, -5f, -42, 11))
        val events = (0..1).map { (locations.append(active, run, raw, 1_200) { true } as LocationRecordingAppendResult.Appended).event }
        val ended = (sessions.finish(active, 2_000) { true } as SessionFinishResult.Finished).session
        val road =
            checkNotNull(NextPracticeSeed.resolve(catalog, ended))
                .practice.course.roadSnapshots
                .first()
        val review = saved(RoomSessionReviewRoadRepository(db, catalog).save(ended, road, null) { true })
        return Fixture(ended, review, run, events, SessionReviewSample.from(run, events.first()))
    }

    private fun saved(result: SessionReviewRoadWriteResult): SessionReviewRoad = (result as SessionReviewRoadWriteResult.Saved).review

    private suspend fun withDatabase(block: suspend (PreparationDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PreparationDatabase::class.java).build()
        try {
            block(db)
        } finally {
            db.close()
        }
    }

    private data class Fixture(
        val source: PracticeSession,
        val review: SessionReviewRoad,
        val run: LocationRecordingRun,
        val events: List<LocationRecordingEvent>,
        val anchor: SessionReviewSample,
    )
}
