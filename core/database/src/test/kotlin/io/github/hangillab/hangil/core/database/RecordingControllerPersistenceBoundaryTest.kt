package io.github.hangillab.hangil.core.database

import androidx.room.Room
import io.github.hangillab.hangil.core.domain.BackgroundRecordingState
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeRecordingController
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.RecordingRunOpenResult
import io.github.hangillab.hangil.core.domain.RecordingRunRepository
import io.github.hangillab.hangil.core.domain.RecordingRunStatus
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import io.github.hangillab.hangil.core.domain.VehicleObservationRepository
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleRecordingRun
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Actual Room commits and production adapters with controlled result/notification delivery.
 * The first case delays an adapter return after Room open has committed; it does not claim to
 * reproduce a particular internal Room dispatcher defect. The second delays only the session
 * Flow notice while a production append reads the committed finished row. Vehicle input and
 * native-service hooks are synthetic; these are persistence/controller checks, not SDK/FGS proof.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingControllerPersistenceBoundaryTest {
    @Test fun `committed Room open is closed when stop cancels delayed adapter receipt`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database)
                val realRuns = RoomRecordingRunRepository(database)
                val committed = CompletableDeferred<VehicleRecordingRun>()
                val returnGate = CompletableDeferred<Unit>()
                val adapterReturned = AtomicBoolean(false)
                val delayedRuns =
                    object : RecordingRunRepository by realRuns {
                        override suspend fun open(
                            source: PracticeSession,
                            runId: String,
                            processId: String,
                            startedAtEpochMillis: Long,
                            canCommit: () -> Boolean,
                        ): RecordingRunOpenResult {
                            val result =
                                withContext(Dispatchers.IO) {
                                    val opened = realRuns.open(source, runId, processId, startedAtEpochMillis, canCommit)
                                    check(opened is RecordingRunOpenResult.Opened)
                                    // A fresh production query sees the committed row before the return is delayed.
                                    check(realRuns.observe(source.sessionId).first().single() == opened.run)
                                    committed.complete(opened.run)
                                    returnGate.await()
                                    opened
                                }
                            adapterReturned.set(true)
                            return result
                        }
                    }
                val scheduler = TestCoroutineScheduler()
                val scopeJob = SupervisorJob()
                val scope = CoroutineScope(scopeJob + StandardTestDispatcher(scheduler))
                val starts = CopyOnWriteArrayList<String>()
                val stops = CopyOnWriteArrayList<String>()
                val controller =
                    controller(
                        scope,
                        RoomPracticeSessionRepository(database, LocalPracticeCatalog()),
                        RoomVehicleObservationRepository(database),
                        delayedRuns,
                        MutableStateFlow(parked()),
                        starts,
                        stops,
                    )
                var primary: Throwable? = null
                try {
                    assertTrue(controller.requestBackground(source) { true })
                    pumpUntil(scheduler) { committed.isCompleted }
                    val row = committed.await()
                    assertEquals(
                        RecordingRunStatus.OPEN,
                        realRuns
                            .observe(source.sessionId)
                            .first()
                            .single()
                            .status,
                    )
                    assertFalse("Room COMMIT must precede adapter result receipt", adapterReturned.get())
                    assertTrue(controller.stopBackground(row.runId))
                    // Execute cleanup through prepare.cancelAndJoin while its adapter gate is still closed.
                    // An old cancellable prepare loses the return; a cancellation-safe receipt waits for it.
                    scheduler.runCurrent()
                    assertFalse(adapterReturned.get())
                    returnGate.complete(Unit)
                    pumpUntil(scheduler) { controller.background.value == BackgroundRecordingState.Disabled }
                    val terminal = realRuns.observe(source.sessionId).first().single()
                    assertEquals(row.copy(status = RecordingRunStatus.STOPPED, endedAtEpochMillis = 2_000), terminal)
                    assertEquals(source, database.practiceSessionDao().find(source.sessionId)?.session())
                    assertTrue("No native dispatch is allowed after the pending run was stopped", starts.isEmpty())
                    assertTrue(stops.isEmpty())
                    assertTrue(RoomVehicleObservationRepository(database).observe(source.sessionId).first().isEmpty())
                } catch (failure: Throwable) {
                    primary = failure
                    throw failure
                } finally {
                    returnGate.complete(Unit)
                    closeScope(scopeJob, scheduler, primary)
                }
            }
        }

    @Test fun `real finished-session append stops the same run before the session Flow notice`() =
        runBlocking {
            withDatabase { database ->
                val source = seed(database)
                val realSessions = RoomPracticeSessionRepository(database, LocalPracticeCatalog())
                val terminalNoticeBlocked = CompletableDeferred<Unit>()
                val terminalNoticeGate = CompletableDeferred<Unit>()
                val terminalNoticeDelivered = AtomicBoolean(false)
                val delayedSessions =
                    object : PracticeSessionRepository by realSessions {
                        override fun observeSessions() =
                            realSessions.observeSessions().transform { sessions ->
                                if (sessions.any { it.sessionId == source.sessionId && !it.isActive }) {
                                    terminalNoticeBlocked.complete(Unit)
                                    terminalNoticeGate.await()
                                    terminalNoticeDelivered.set(true)
                                }
                                emit(sessions)
                            }
                    }
                val realObservations = RoomVehicleObservationRepository(database)
                val appended = CompletableDeferred<Unit>()
                val finishedAppend = CompletableDeferred<Unit>()
                val observations =
                    object : VehicleObservationRepository by realObservations {
                        override suspend fun append(
                            sessionId: String,
                            observation: VehicleObservation,
                        ): VehicleObservationAppendResult {
                            val result = realObservations.append(sessionId, observation)
                            when (result) {
                                is VehicleObservationAppendResult.Appended -> appended.complete(Unit)
                                VehicleObservationAppendResult.FinishedSession -> finishedAppend.complete(Unit)
                                else -> Unit
                            }
                            return result
                        }
                    }
                val realRuns = RoomRecordingRunRepository(database)
                val vehicle = MutableStateFlow(parked())
                val scheduler = TestCoroutineScheduler()
                val scopeJob = SupervisorJob()
                val scope = CoroutineScope(scopeJob + StandardTestDispatcher(scheduler))
                val starts = CopyOnWriteArrayList<String>()
                val stops = CopyOnWriteArrayList<String>()
                val controller = controller(scope, delayedSessions, observations, realRuns, vehicle, starts, stops)
                var primary: Throwable? = null
                try {
                    assertTrue(controller.requestBackground(source) { true })
                    pumpUntil(scheduler) { starts.size == 1 }
                    val runId = starts.single()
                    val service = scope.launch { controller.recordInService(runId, source.sessionId) }
                    pumpUntil(scheduler) { appended.isCompleted }
                    val beforeFinish = realObservations.observe(source.sessionId).first()
                    assertEquals(1, beforeFinish.size)
                    assertEquals(BackgroundRecordingState.Active(runId, source.sessionId), controller.background.value)

                    assertTrue(realSessions.finish(source.sessionId, 2_000) { true } is SessionFinishResult.Finished)
                    assertEquals(source.copy(endedAtEpochMillis = 2_000), database.practiceSessionDao().find(source.sessionId)?.session())
                    pumpUntil(scheduler) { terminalNoticeBlocked.isCompleted }
                    assertFalse(terminalNoticeDelivered.get())
                    vehicle.value = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))
                    pumpUntil(scheduler) { finishedAppend.isCompleted }
                    pumpUntil(scheduler) { service.isCompleted }

                    assertFalse("Only the production append result may have delivered session completion", terminalNoticeDelivered.get())
                    assertEquals(BackgroundRecordingState.Disabled, controller.background.value)
                    val run = realRuns.observe(source.sessionId).first().single()
                    assertEquals(RecordingRunStatus.STOPPED, run.status)
                    assertEquals(2_000L, run.endedAtEpochMillis)
                    assertEquals(listOf(runId), stops.toList())
                    assertEquals(beforeFinish, realObservations.observe(source.sessionId).first())
                } catch (failure: Throwable) {
                    primary = failure
                    throw failure
                } finally {
                    terminalNoticeGate.complete(Unit)
                    closeScope(scopeJob, scheduler, primary)
                }
            }
        }

    private fun controller(
        scope: CoroutineScope,
        sessions: PracticeSessionRepository,
        observations: VehicleObservationRepository,
        runs: RecordingRunRepository,
        vehicle: MutableStateFlow<VehicleState>,
        starts: MutableList<String>,
        stops: MutableList<String>,
    ): PracticeRecordingController =
        PracticeRecordingController(
            vehicleGateway =
                object : VehicleGateway {
                    override val state = vehicle
                },
            sessionRepository = sessions,
            observationRepository = observations,
            restrictionsProvider =
                object : DrivingRestrictionsProvider {
                    override val restrictions = MutableStateFlow(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))
                },
            runRepository = runs,
            clock = Clock.fixed(Instant.ofEpochMilli(2_000), ZoneOffset.UTC),
            scope = scope,
            nextRunId = { "controller-room-boundary-run" },
            processId = "controller-room-boundary-process",
            startService = { runId, _ -> starts.add(runId) },
            stopService = { runId -> stops.add(runId) },
        )

    /** Real Room work runs independently; only controller continuations use this explicit scheduler. */
    private suspend fun pumpUntil(
        scheduler: TestCoroutineScheduler,
        condition: () -> Boolean,
    ) {
        withTimeout(10_000) {
            while (!condition()) {
                scheduler.runCurrent()
                delay(1)
            }
            scheduler.runCurrent()
        }
    }

    private suspend fun closeScope(
        job: Job,
        scheduler: TestCoroutineScheduler,
        primary: Throwable?,
    ) {
        try {
            job.cancel()
            pumpUntil(scheduler) { job.isCompleted }
        } catch (cleanup: Throwable) {
            if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
        }
    }

    private suspend fun seed(database: PreparationDatabase): PracticeSession {
        val source =
            PracticeSessionEntity(
                sessionId = "controller-room-boundary-session",
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
        database.practiceSessionDao().insert(source)
        return source.session()
    }

    private suspend fun <T> withDatabase(block: suspend (PreparationDatabase) -> T): T {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), PreparationDatabase::class.java).build()
        return try {
            block(database)
        } finally {
            database.close()
        }
    }

    private fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))
}
