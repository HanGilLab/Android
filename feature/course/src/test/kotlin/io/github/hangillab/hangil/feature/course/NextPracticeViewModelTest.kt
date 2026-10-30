package io.github.hangillab.hangil.feature.course

import androidx.lifecycle.SavedStateHandle
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseMapPreview
import io.github.hangillab.hangil.core.domain.CoursePreviewPoint
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.NextPracticeRepository
import io.github.hangillab.hangil.core.domain.NextPracticeSaveResult
import io.github.hangillab.hangil.core.domain.NextPracticeSeed
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeCourse
import io.github.hangillab.hangil.core.domain.PracticeGoal
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.PracticeTask
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.feature.course.NextPracticeUiState.FailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NextPracticeViewModelTest {
    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `construction is cold and no owner can select save retry or consume a completion`() =
        runTest {
            val f = Fixture()
            assertFalse(f.model.canInteract())
            assertFalse(f.model.retry())
            assertFalse(f.model.selectTask("next", "source", 1))
            assertFalse(f.model.save("source", 1))
            assertFalse(f.model.consumeSaved(1))
            runCurrent()
            assertEquals(0, f.sessions.calls)
            assertEquals(0, f.next.calls)
        }

    @Test fun `valid seed starts without a selected task or automatic save`() =
        runTest {
            val f = Fixture()
            watch(f)
            val state = ready(f)
            assertEquals(source(), state.seed.source)
            assertEquals("goal", state.seed.practice.goal.id)
            assertEquals("course", state.seed.practice.course.id)
            assertNull(state.taskId)
            assertFalse(state.canSave)
            assertTrue(f.model.canInteract())
            assertFalse(f.model.save("source", state.generation))
            assertEquals(0, f.next.calls)
            assertEquals(0, f.sessions.writes)
        }

    @Test fun `missing blank and wrong type source arguments do not query or choose the newest record`() =
        runTest {
            for (argument in listOf(null, "", " ", 7)) {
                val f = Fixture(sourceArgument = argument)
                val job = watch(f)
                assertTrue(f.model.uiState.value is NextPracticeUiState.SourceChanged)
                assertEquals(0, f.sessions.calls)
                assertTrue(f.model.canInteract())
                assertFalse(f.model.retry())
                job.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `every unconfirmed denied and restricted live state avoids the session query`() =
        runTest {
            val blocked =
                listOf(
                    parked().copy(connection = ConnectionState.DISCONNECTED) to permitted(),
                    parked().copy(connection = ConnectionState.PERMISSION_DENIED) to permitted(),
                    driving() to permitted(),
                    parked().copy(gear = VehicleReading.Unknown) to permitted(),
                    parked().copy(gear = VehicleReading.PermissionDenied) to permitted(),
                    parked() to permitted().copy(parkingState = ParkingState.UNKNOWN),
                    parked() to permitted().copy(uxRestrictionState = UxRestrictionState.UNKNOWN),
                    parked() to permitted().copy(uxRestrictionState = UxRestrictionState.RESTRICTED),
                )
            for ((vehicle, restrictions) in blocked) {
                val f = Fixture()
                f.vehicleState.value = vehicle
                f.restrictionState.value = restrictions
                val job = watch(f)
                assertEquals(NextPracticeUiState.Locked, f.model.uiState.value)
                assertFalse(f.model.canInteract())
                assertFalse(f.model.retry())
                assertEquals(0, f.sessions.calls)
                job.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `latest source follows repository creation order instead of the largest button clock`() =
        runTest {
            val f = Fixture()
            f.sessions.values.value = listOf(source(started = 100, ended = 200), source("older", started = 9_000, ended = 10_000))
            watch(f)
            assertEquals(100L, ready(f).seed.source.startedAtEpochMillis)
            assertEquals("source", ready(f).seed.source.sessionId)
        }

    @Test fun `active or changed source never silently switches the seed`() =
        runTest {
            val choices = listOf(emptyList(), listOf(source("other")), listOf(source("active", ended = null), source()))
            for (records in choices) {
                val f = Fixture()
                f.sessions.values.value = records
                val job = watch(f)
                assertTrue(f.model.uiState.value is NextPracticeUiState.SourceChanged)
                assertTrue(f.model.canInteract())
                assertFalse(f.model.retry())
                assertEquals(0, f.next.calls)
                job.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `source title and catalog version mismatches are explicit unavailable states`() =
        runTest {
            val f = Fixture()
            f.catalog.courses = f.catalog.courses.map { it.copy(version = 2) }
            watch(f)
            assertTrue(f.model.uiState.value is NextPracticeUiState.Unavailable)
            assertTrue(f.model.canInteract())
            assertFalse(f.model.retry())
        }

    @Test fun `manual targets and generations guard draft changes`() =
        runTest {
            val f = Fixture()
            watch(f)
            val old = ready(f)
            assertFalse(f.model.selectTask("missing", "source", old.generation))
            assertFalse(f.model.selectTask("next", "wrong", old.generation))
            assertFalse(f.model.selectTask("next", "source", old.generation + 1))
            assertTrue(f.model.selectTask("next", "source", old.generation))
            val current = ready(f)
            assertEquals("next", current.taskId)
            assertTrue(current.canSave)
            assertNotEquals(old.generation, current.generation)
            assertFalse(f.model.selectTask("old", "source", old.generation))
            assertFalse(f.model.save("source", old.generation))
            assertEquals(0, f.next.calls)
        }

    @Test fun `duplicate or blank target tasks cannot be manually selected`() =
        runTest {
            for (targets in listOf(
                listOf(PracticeTask("next", "A", ""), PracticeTask("next", "B", "")),
                listOf(
                    PracticeTask("next", " ", ""),
                ),
            )) {
                val f = Fixture()
                f.catalog.courses = f.catalog.courses.map { it.copy(tasks = listOf(oldTask()) + targets) }
                val job = watch(f)
                val state = ready(f)
                assertFalse(f.model.selectTask("next", "source", state.generation))
                assertNull(ready(f).taskId)
                job.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `live D rejects selected task and save callbacks before UI recombination`() =
        runTest {
            val f = Fixture()
            watch(f)
            val state = choose(f)
            f.vehicleState.value = driving()
            assertEquals(state, f.model.uiState.value)
            assertFalse(f.model.canInteract())
            assertFalse(f.model.selectTask("old", "source", state.generation))
            assertFalse(f.model.save("source", state.generation))
            runCurrent()
            assertEquals(NextPracticeUiState.Locked, f.model.uiState.value)
            assertEquals(0, f.sessions.active)
            assertEquals(0, f.next.calls)
        }

    @Test fun `commit publishes one owner bound token and consuming it once closes further interaction`() =
        runTest {
            val f = Fixture()
            watch(f)
            val events = events(f)
            val state = choose(f)
            assertTrue(f.model.save("source", state.generation))
            assertFalse(f.model.save("source", state.generation))
            runCurrent()
            assertEquals(1, f.next.calls)
            assertEquals("next", f.next.stored?.taskId)
            assertEquals(state.seed, f.next.seeds.single())
            assertEquals(1, events.size)
            assertTrue(f.model.consumeSaved(events.single()))
            assertFalse(f.model.consumeSaved(events.single()))
            assertFalse(f.model.canInteract())
            assertFalse(f.model.save("source", state.generation))
        }

    @Test fun `equal seed emissions preserve pending commit ownership`() =
        runTest {
            val f = Fixture()
            val sourceEvents = MutableSharedFlow<List<PracticeSession>>(replay = 1)
            sourceEvents.tryEmit(listOf(source()))
            f.sessions.querySource = { sourceEvents }
            watch(f)
            val events = events(f)
            val release = CompletableDeferred<Unit>()
            f.next.waitFor(release)
            val state = choose(f)
            f.model.save("source", state.generation)
            runCurrent()
            assertTrue(f.next.guards.single()())
            sourceEvents.emit(listOf(source()))
            runCurrent()
            assertTrue(ready(f).saving)
            assertTrue(f.next.guards.single()())
            release.complete(Unit)
            runCurrent()
            assertEquals(1, events.size)
            assertEquals("next", f.next.stored?.taskId)
        }

    @Test fun `blocked writes show failure and can be explicitly retried without any success event`() =
        runTest {
            val f = Fixture()
            watch(f)
            val events = events(f)
            f.next.handler = { _, _, _ -> NextPracticeSaveResult.Blocked }
            val state = choose(f)
            f.model.save("source", state.generation)
            runCurrent()
            assertTrue(ready(f).saveFailed)
            assertFalse(ready(f).saving)
            assertTrue(events.isEmpty())
            assertNull(f.next.stored)
            f.next.handler = f.next.defaultHandler
            val retry = ready(f)
            assertTrue(f.model.save("source", retry.generation))
            runCurrent()
            assertEquals(1, events.size)
        }

    @Test fun `DB source and full catalog rejection expire the route without a saved event`() =
        runTest {
            for ((result, expected) in listOf(
                NextPracticeSaveResult.SourceChanged to NextPracticeUiState.SourceChanged::class.java,
                NextPracticeSaveResult.Unavailable to NextPracticeUiState.Unavailable::class.java,
            )) {
                val f = Fixture()
                val job = watch(f)
                val events = events(f)
                f.next.handler = { _, _, _ -> result }
                val state = choose(f)
                f.model.save("source", state.generation)
                runCurrent()
                assertTrue(expected.isInstance(f.model.uiState.value))
                assertTrue(f.model.canInteract())
                assertFalse(f.model.retry())
                assertTrue(events.isEmpty())
                assertNull(f.next.stored)
                job.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `SQL like errors and independent cancellation do not become a successful preparation`() =
        runTest {
            for (failure in listOf(IOException("fixture write"), CancellationException("fixture independent cancellation"))) {
                val f = Fixture()
                val job = watch(f)
                val events = events(f)
                f.next.handler = { _, _, _ -> throw failure }
                val state = choose(f)
                f.model.save("source", state.generation)
                runCurrent()
                assertTrue(ready(f).saveFailed)
                assertFalse(ready(f).saving)
                assertTrue(job.isActive)
                assertTrue(events.isEmpty())
                assertNull(f.next.stored)
                job.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `a newly received active record cancels pending work and the IO guard rejects after cancellation`() =
        runTest {
            val f = Fixture()
            watch(f)
            val events = events(f)
            val release = CompletableDeferred<Unit>()
            f.next.waitFor(release, ignoreCancellation = true)
            val state = choose(f)
            f.model.save("source", state.generation)
            runCurrent()
            val guard = f.next.guards.single()
            assertTrue(guard())
            f.sessions.values.value = listOf(source("active", ended = null), source())
            runCurrent()
            assertTrue(f.model.uiState.value is NextPracticeUiState.SourceChanged)
            assertFalse(guard())
            release.complete(Unit)
            runCurrent()
            assertNull(f.next.stored)
            assertTrue(events.isEmpty())
        }

    @Test fun `same source ID with changed stored fields never replaces the pinned source`() =
        runTest {
            val f = Fixture()
            watch(f)
            choose(f)
            f.sessions.values.value = listOf(source().copy(startedAtEpochMillis = 900))
            runCurrent()
            assertTrue(f.model.uiState.value is NextPracticeUiState.SourceChanged)
            f.sessions.values.value = listOf(source())
            runCurrent()
            assertTrue(f.model.uiState.value is NextPracticeUiState.SourceChanged)
            assertEquals(0, f.next.calls)
        }

    @Test fun `received target description changes invalidate the complete pinned catalog seed`() =
        runTest {
            val f = Fixture()
            watch(f)
            choose(f)
            val modifiedTasks = listOf(oldTask(), nextTask().copy(description = "BB"))
            f.catalog.courses = f.catalog.courses.map { it.copy(tasks = modifiedTasks) }
            f.sessions.values.value = listOf(source(), source("older"))
            runCurrent()
            assertTrue(f.model.uiState.value is NextPracticeUiState.Unavailable)
            assertEquals(0, f.next.calls)
        }

    @Test fun `read factory failure requires explicit retry and never falls back to another source`() =
        runTest {
            val f = Fixture()
            f.sessions.factoryFailure = IOException("fixture read")
            watch(f)
            assertEquals(FailureReason.SESSION_READ, failed(f).reason)
            assertTrue(f.model.canInteract())
            f.sessions.factoryFailure = null
            assertTrue(f.model.retry())
            runCurrent()
            assertNull(ready(f).taskId)
        }

    @Test fun `normal read completion and internal cancellation remain explicit failures`() =
        runTest {
            for (mode in listOf(0, 1)) {
                val f = Fixture()
                f.sessions.querySource = {
                    flow {
                        if (mode == 0) {
                            emit(listOf(source()))
                        } else {
                            throw CancellationException("fixture independent read")
                        }
                    }
                }
                val job = watch(f)
                val reason =
                    if (mode == 0) {
                        FailureReason.SESSION_STREAM_COMPLETED
                    } else {
                        FailureReason.SESSION_READ
                    }
                assertEquals(reason, failed(f).reason)
                assertTrue(job.isActive)
                assertEquals(0, f.sessions.active)
                job.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `repeated source identity and invalid times fail rather than normalizing saved records`() =
        runTest {
            for (records in listOf(listOf(source(), source()), listOf(source(ended = 999)), listOf(source().copy(sessionId = " ")))) {
                val f = Fixture()
                f.sessions.values.value = records
                val job = watch(f)
                assertEquals(FailureReason.INVALID_SESSIONS, failed(f).reason)
                job.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `STOP invalidates IO and fresh reads retain the selected draft`() =
        runTest {
            val f = Fixture()
            val oldReader = watch(f)
            val events = events(f)
            val release = CompletableDeferred<Unit>()
            f.next.waitFor(release, ignoreCancellation = true)
            val state = choose(f)
            f.model.save("source", state.generation)
            runCurrent()
            val guard = f.next.guards.single()
            oldReader.cancelAndJoin()
            runCurrent()
            assertFalse(guard())
            assertEquals(0, f.sessions.active)
            watch(f)
            val current = ready(f)
            assertEquals("next", current.taskId)
            assertNotEquals(state.generation, current.generation)
            assertFalse(f.model.save("source", state.generation))
            release.complete(Unit)
            runCurrent()
            assertNull(f.next.stored)
            assertTrue(events.isEmpty())
        }

    @Test fun `leaving permanently rejects a pending commit callback and any later completion`() =
        runTest {
            val f = Fixture()
            watch(f)
            val events = events(f)
            val release = CompletableDeferred<Unit>()
            f.next.waitFor(release, ignoreCancellation = true)
            val state = choose(f)
            f.model.save("source", state.generation)
            runCurrent()
            f.model.leave()
            assertFalse(f.next.guards.single()())
            assertFalse(f.model.canInteract())
            assertFalse(f.model.retry())
            release.complete(Unit)
            runCurrent()
            assertTrue(events.isEmpty())
            assertNull(f.next.stored)
            assertEquals(0, f.sessions.active)
        }

    @Test fun `D and fresh P validation preserve a manual task but not an old generation`() =
        runTest {
            val f = Fixture()
            watch(f)
            val state = choose(f)
            f.vehicleState.value = driving()
            runCurrent()
            assertEquals(NextPracticeUiState.Locked, f.model.uiState.value)
            assertEquals(0, f.sessions.active)
            f.vehicleState.value = parked()
            runCurrent()
            val current = ready(f)
            assertEquals("next", current.taskId)
            assertNotEquals(state.generation, current.generation)
            assertFalse(f.model.save("source", state.generation))
            assertEquals(1, f.sessions.maximumActive)
        }

    @Test fun `a completion from the old reader cannot close a newly validated owner`() =
        runTest {
            val f = Fixture()
            val reader = watch(f)
            val events = events(f)
            val state = choose(f)
            f.model.save("source", state.generation)
            runCurrent()
            val token = events.single()
            reader.cancelAndJoin()
            runCurrent()
            watch(f)
            assertEquals("next", ready(f).taskId)
            assertFalse(f.model.consumeSaved(token))
            assertTrue(f.model.canInteract())
        }

    @Test fun `a completion invalidated by explicit retry is not replayed into the fresh query`() =
        runTest {
            val f = Fixture()
            watch(f)
            val events = events(f)
            val state = choose(f)
            f.model.save("source", state.generation)
            runCurrent()
            val token = events.single()
            assertTrue(f.model.retry())
            runCurrent()
            assertFalse(f.model.consumeSaved(token))
            assertEquals(1, events.size)
            assertEquals("next", ready(f).taskId)
        }

    @Test fun `a fresh ViewModel restores a manually chosen task only after exact primitive seed validation`() =
        runTest {
            val f = Fixture()
            val reader = watch(f)
            choose(f)
            val state = copyState(f.handle)
            reader.cancelAndJoin()
            runCurrent()
            val restored = Fixture(state = state)
            watch(restored)
            assertEquals("next", ready(restored).taskId)
            assertEquals(0, restored.next.calls)
        }

    @Test fun `equal hash codes do not allow a task to restore against different catalog description fields`() =
        runTest {
            assertEquals("Aa".hashCode(), "BB".hashCode())
            val f = Fixture()
            val reader = watch(f)
            choose(f)
            val saved = copyState(f.handle)
            reader.cancelAndJoin()
            runCurrent()
            val changed = Fixture(state = saved)
            val modifiedTasks = listOf(oldTask(), nextTask().copy(description = "BB"))
            changed.catalog.courses = changed.catalog.courses.map { it.copy(tasks = modifiedTasks) }
            watch(changed)
            assertNull(ready(changed).taskId)
            assertFalse(ready(changed).canSave)
        }

    @Test fun `a changed public map preview also invalidates persisted draft binding without changing source IDs`() =
        runTest {
            val f = Fixture()
            f.catalog.courses = f.catalog.courses.map { it.copy(mapPreview = preview()) }
            val reader = watch(f)
            choose(f)
            val saved = copyState(f.handle)
            reader.cancelAndJoin()
            runCurrent()
            val changed = Fixture(state = saved)
            changed.catalog.courses = changed.catalog.courses.map { it.copy(mapPreview = preview().copy(title = "변경된 표시 영역")) }
            watch(changed)
            assertNull(ready(changed).taskId)
        }

    @Test fun `a task key without a matching complete binding never auto selects a target`() =
        runTest {
            val f =
                Fixture(
                    state =
                        SavedStateHandle(
                            mapOf(
                                "nextPracticeSourceId" to "source",
                                "nextPracticeTaskId" to "next",
                                "nextPracticeSeedFields" to "wrong type",
                            ),
                        ),
                )
            watch(f)
            assertNull(ready(f).taskId)
            assertEquals(0, f.next.calls)
        }

    @Test fun `a cancelled delayed read cannot publish a seed into a later reader or query epoch`() =
        runTest {
            val f = Fixture()
            val late = CompletableDeferred<List<PracticeSession>>()
            f.sessions.querySource = {
                flow {
                    emit(late.await())
                    awaitCancellation()
                }
            }
            watch(f)
            f.sessions.querySource = { f.sessions.values }
            assertTrue(f.model.retry())
            runCurrent()
            val current = ready(f)
            late.complete(listOf(source("other")))
            runCurrent()
            assertEquals(current, ready(f))
            assertEquals(1, f.sessions.maximumActive)
        }

    @Test fun `direct live denied source receipt rechecks access even if D and P are conflated`() =
        runTest {
            val f = Fixture()
            val trigger = CompletableDeferred<Unit>()
            var starts = 0
            f.sessions.querySource = {
                flow {
                    starts++
                    if (starts == 1) {
                        emit(listOf(source()))
                        trigger.await()
                        f.vehicleState.value = driving()
                        try {
                            emit(listOf(source()))
                        } finally {
                            f.vehicleState.value = parked()
                        }
                        awaitCancellation()
                    } else {
                        emitAll(f.sessions.values)
                    }
                }
            }
            watch(f)
            choose(f)
            trigger.complete(Unit)
            runCurrent()
            assertEquals("next", ready(f).taskId)
            assertTrue(f.model.canInteract())
            assertEquals(1, f.sessions.active)
            assertEquals(1, f.sessions.maximumActive)
        }

    @Test fun `denied query start rechecks access before reading`() =
        runTest {
            val backing = MutableStateFlow(parked())
            var reads = 0
            val access =
                object : StateFlow<VehicleState> by backing {
                    override val value: VehicleState get() {
                        reads++
                        if (reads == 2) {
                            backing.value = driving()
                            val denied = backing.value
                            backing.value = parked()
                            return denied
                        }
                        return backing.value
                    }
                }
            val f = Fixture(accessSource = access)
            watch(f)
            assertNull(ready(f).taskId)
            assertEquals(1, f.sessions.calls)
            assertEquals(1, f.sessions.active)
        }

    @Test fun `denied read failures recheck access`() =
        runTest {
            for (mode in 0..2) {
                val backing = MutableStateFlow(parked())
                var armed = false
                var readDenied = false
                val access =
                    object : StateFlow<VehicleState> by backing {
                        override val value: VehicleState get() {
                            if (armed) {
                                armed = false
                                backing.value = driving()
                                val denied = backing.value
                                readDenied = true
                                backing.value = parked()
                                return denied
                            }
                            return backing.value
                        }
                    }
                val f = Fixture(accessSource = access)
                val trigger = CompletableDeferred<Unit>()
                var starts = 0
                f.sessions.querySource = {
                    flow {
                        starts++
                        if (starts == 1) {
                            emit(listOf(source()))
                            trigger.await()
                            armed = true
                            when (mode) {
                                0 -> throw IOException("fixture failure")
                                1 -> Unit
                                else ->
                                    throw CancellationException("fixture failure")
                            }
                        } else {
                            emitAll(f.sessions.values)
                        }
                    }
                }
                val job = watch(f)
                choose(f)
                trigger.complete(Unit)
                runCurrent()
                assertTrue(readDenied)
                assertEquals("next", ready(f).taskId)
                assertEquals(1, f.sessions.active)
                job.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `a failed screen fallback cannot leave a freshly ready route`() =
        runTest {
            val f = Fixture()
            f.sessions.factoryFailure = IOException("fixture old failure")
            watch(f)
            val old = failed(f)
            f.sessions.factoryFailure = null
            assertTrue(f.model.retry())
            val current = ready(f)
            assertFalse(f.model.leaveForNormalPreparation(old.generation))
            assertEquals(current, ready(f))
            assertTrue(f.model.canInteract())
        }

    @Test fun `a failed screen fallback cannot cancel a new pending save`() =
        runTest {
            val f = Fixture()
            f.sessions.factoryFailure = IOException("fixture old failure")
            watch(f)
            val old = failed(f)
            f.sessions.factoryFailure = null
            f.model.retry()
            runCurrent()
            val release = CompletableDeferred<Unit>()
            f.next.waitFor(release)
            val state = choose(f)
            assertTrue(f.model.save("source", state.generation))
            runCurrent()
            try {
                assertFalse(f.model.leaveForNormalPreparation(old.generation))
                assertTrue(ready(f).saving)
                assertTrue(f.next.guards.single()())
            } finally {
                release.complete(Unit)
            }
            runCurrent()
            assertEquals("next", f.next.stored?.taskId)
        }

    @Test fun `a failed screen fallback cannot leave loading while a fresh retry is pending`() =
        runTest {
            val f = Fixture()
            f.sessions.factoryFailure = IOException("fixture old failure")
            watch(f)
            val old = failed(f)
            val fresh = CompletableDeferred<List<PracticeSession>>()
            f.sessions.factoryFailure = null
            f.sessions.querySource = {
                flow {
                    emit(fresh.await())
                    awaitCancellation()
                }
            }
            assertTrue(f.model.retry())
            runCurrent()
            assertEquals(NextPracticeUiState.Loading, f.model.uiState.value)
            assertFalse(f.model.leaveForNormalPreparation(old.generation))
            fresh.complete(listOf(source()))
            runCurrent()
            assertNull(ready(f).taskId)
        }

    @Test fun `a new failure with the same reason receives a distinct fallback confirmation generation`() =
        runTest {
            val f = Fixture()
            f.sessions.factoryFailure = IOException("fixture repeated failure")
            watch(f)
            val old = failed(f)
            assertTrue(f.model.retry())
            val current = failed(f)
            assertEquals(old.reason, current.reason)
            assertNotEquals(old.generation, current.generation)
            assertFalse(f.model.leaveForNormalPreparation(old.generation))
            assertTrue(f.model.leaveForNormalPreparation(current.generation))
            assertFalse(f.model.canInteract())
        }

    @Test fun `a previous reader failure cannot leave a new reader with the same source failure`() =
        runTest {
            val f = Fixture()
            f.sessions.factoryFailure = IOException("fixture repeated failure")
            val reader = watch(f)
            val old = failed(f)
            reader.cancelAndJoin()
            runCurrent()
            assertFalse(f.model.leaveForNormalPreparation(old.generation))
            watch(f)
            val current = failed(f)
            assertNotEquals(old.generation, current.generation)
            assertFalse(f.model.leaveForNormalPreparation(old.generation))
            assertTrue(f.model.leaveForNormalPreparation(current.generation))
        }

    @Test fun `fallback rechecks actual denied or unknown UX before the old error screen updates`() =
        runTest {
            for (restriction in listOf(
                permitted().copy(uxRestrictionState = UxRestrictionState.RESTRICTED),
                permitted().copy(uxRestrictionState = UxRestrictionState.UNKNOWN),
            )) {
                val f = Fixture()
                f.sessions.factoryFailure = IOException("fixture failure")
                val reader = watch(f)
                val old = failed(f)
                f.restrictionState.value = restriction
                assertFalse(f.model.leaveForNormalPreparation(old.generation))
                runCurrent()
                assertEquals(NextPracticeUiState.Locked, f.model.uiState.value)
                f.restrictionState.value = permitted()
                val current = failed(f)
                assertNotEquals(old.generation, current.generation)
                assertFalse(f.model.leaveForNormalPreparation(old.generation))
                assertTrue(f.model.leaveForNormalPreparation(current.generation))
                reader.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `expired and unavailable fallbacks require their current generation and succeed only once`() =
        runTest {
            for (unavailable in listOf(false, true)) {
                val f = if (unavailable) Fixture() else Fixture(sourceArgument = null)
                if (unavailable) f.catalog.courses = f.catalog.courses.map { it.copy(version = 99) }
                val reader = watch(f)
                val generation =
                    when (val state = f.model.uiState.value) {
                        is NextPracticeUiState.SourceChanged -> state.generation
                        is NextPracticeUiState.Unavailable -> state.generation
                        else -> error("Expected an explicit source error")
                    }
                assertFalse(f.model.leaveForNormalPreparation(generation + 1))
                assertTrue(f.model.leaveForNormalPreparation(generation))
                assertFalse(f.model.leaveForNormalPreparation(generation))
                assertEquals(0, f.next.calls)
                reader.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `normal fallback rejects ready and closed screens`() =
        runTest {
            val f = Fixture()
            watch(f)
            val state = ready(f)
            assertFalse(f.model.leaveForNormalPreparation(state.generation))
            assertTrue(f.model.canInteract())
            f.model.leave()
            assertFalse(f.model.leaveForNormalPreparation(state.generation))
        }

    private fun TestScope.failed(f: Fixture): NextPracticeUiState.Failed {
        runCurrent()
        return f.model.uiState.value as NextPracticeUiState.Failed
    }

    private fun TestScope.watch(f: Fixture): Job {
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model.uiState.collect {}
            }
        runCurrent()
        return job
    }

    @Test fun `source reveal checks current identity and generation without selecting or saving`() =
        runTest {
            val f = Fixture()
            assertFalse(f.model.canRevealSource("source", 1))
            assertEquals(0, f.sessions.calls)
            watch(f)
            val initial = ready(f)
            val oldCallback = { f.model.canRevealSource(initial.seed.source.sessionId, initial.generation) }
            assertTrue(oldCallback())
            assertFalse(f.model.canRevealSource("different", initial.generation))
            assertFalse(f.model.canRevealSource("source", initial.generation + 1))
            assertEquals(initial, ready(f))
            val selected = choose(f)
            assertFalse(oldCallback())
            assertTrue(f.model.canRevealSource("source", selected.generation))
            assertEquals(1, f.sessions.calls)
            assertEquals(0, f.sessions.writes)
            assertEquals(0, f.next.calls)
            f.model.leave()
            assertFalse(f.model.canRevealSource("source", selected.generation))
        }

    @Test fun `source callbacks reject silent live D and unconfirmed UX before Ready recombines`() =
        runTest {
            val blocked =
                listOf(
                    driving() to permitted(),
                    parked().copy(gear = VehicleReading.Unknown) to permitted(),
                    parked().copy(gear = VehicleReading.PermissionDenied) to permitted(),
                    parked().copy(connection = ConnectionState.DISCONNECTED) to permitted(),
                    parked() to permitted().copy(parkingState = ParkingState.UNKNOWN),
                    parked() to permitted().copy(uxRestrictionState = UxRestrictionState.UNKNOWN),
                    parked() to permitted().copy(uxRestrictionState = UxRestrictionState.RESTRICTED),
                )
            for ((vehicle, restrictions) in blocked) {
                val f = Fixture()
                val job = watch(f)
                val state = ready(f)
                val oldCallback = { f.model.canRevealSource(state.seed.source.sessionId, state.generation) }
                assertTrue(oldCallback())
                f.vehicleState.value = vehicle
                f.restrictionState.value = restrictions
                assertEquals(state, f.model.uiState.value)
                assertFalse(oldCallback())
                assertEquals(1, f.sessions.calls)
                assertEquals(0, f.sessions.writes)
                assertEquals(0, f.next.calls)
                job.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `source retry rejects an old callback while Loading and after fresh same source validation`() =
        runTest {
            val f = Fixture()
            watch(f)
            val previous = ready(f)
            val oldCallback = { f.model.canRevealSource("source", previous.generation) }
            val fresh = CompletableDeferred<List<PracticeSession>>()
            f.sessions.querySource = {
                flow {
                    emit(fresh.await())
                    awaitCancellation()
                }
            }
            assertTrue(f.model.retry())
            assertFalse(oldCallback())
            runCurrent()
            assertEquals(NextPracticeUiState.Loading, f.model.uiState.value)
            fresh.complete(f.sessions.values.value)
            runCurrent()
            val current = ready(f)
            assertFalse(oldCallback())
            assertTrue(f.model.canRevealSource("source", current.generation))
            assertEquals(1, f.sessions.maximumActive)
            assertEquals(0, f.sessions.writes)
            assertEquals(0, f.next.calls)
        }

    @Test fun `source callbacks reject cancelled queries before cleanup and after reader reentry`() =
        runTest {
            val f = Fixture()
            var previous: NextPracticeUiState.Ready? = null
            var acceptedDuringCleanup: Boolean? = null
            f.sessions.querySource = {
                flow {
                    try {
                        emitAll(f.sessions.values)
                    } finally {
                        previous?.let { acceptedDuringCleanup = f.model.canRevealSource("source", it.generation) }
                    }
                }
            }
            val first = watch(f)
            previous = ready(f)
            val old = checkNotNull(previous)
            val oldCallback = { f.model.canRevealSource("source", old.generation) }
            first.cancelAndJoin()
            runCurrent()
            assertEquals(false, acceptedDuringCleanup)
            assertFalse(oldCallback())
            assertEquals(0, f.sessions.active)
            watch(f)
            val current = ready(f)
            assertEquals(old.seed, current.seed)
            assertFalse(oldCallback())
            assertTrue(f.model.canRevealSource("source", current.generation))
            assertEquals(1, f.sessions.maximumActive)
            assertEquals(0, f.sessions.writes)
            assertEquals(0, f.next.calls)
        }

    @Test fun `identical seed receipt denies reentrant reveal but preserves its validated generation`() =
        runTest {
            val f = Fixture()
            val deliver = CompletableDeferred<Unit>()
            var previous: NextPracticeUiState.Ready? = null
            var acceptedDuringResolution: Boolean? = null
            f.sessions.querySource = {
                flow {
                    emit(f.sessions.values.value)
                    deliver.await()
                    emit(
                        f.sessions.values.value
                            .toList(),
                    )
                    awaitCancellation()
                }
            }
            watch(f)
            previous = ready(f)
            val old = checkNotNull(previous)
            val oldCallback = { f.model.canRevealSource("source", old.generation) }
            f.catalog.onGoalsRead = {
                acceptedDuringResolution = oldCallback()
            }
            deliver.complete(Unit)
            runCurrent()
            val current = ready(f)
            assertEquals(false, acceptedDuringResolution)
            assertEquals(old.generation, current.generation)
            assertTrue(oldCallback())
            assertEquals(1, f.sessions.calls)
            assertEquals(0, f.sessions.writes)
            assertEquals(0, f.next.calls)
        }

    @Test fun `received source active or catalog changes reject old source callbacks while still parked`() =
        runTest {
            for (change in 0..2) {
                val f = Fixture()
                val deliver = CompletableDeferred<Unit>()
                f.sessions.querySource = {
                    flow {
                        emit(f.sessions.values.value)
                        deliver.await()
                        val rows =
                            when (change) {
                                0 -> listOf(source().copy(endedAtEpochMillis = 2_001))
                                1 -> listOf(source("active", ended = null), source())
                                else -> {
                                    f.catalog.courses =
                                        f.catalog.courses.map { course ->
                                            course.copy(tasks = course.tasks.map { it.copy(description = "변경된 과제 설명") })
                                        }
                                    listOf(source())
                                }
                            }
                        emit(rows)
                        awaitCancellation()
                    }
                }
                val job = watch(f)
                val previous = ready(f)
                val oldCallback = { f.model.canRevealSource("source", previous.generation) }
                deliver.complete(Unit)
                runCurrent()
                assertTrue(f.model.canInteract())
                assertFalse(oldCallback())
                if (change == 2) {
                    assertTrue(f.model.uiState.value is NextPracticeUiState.Unavailable)
                } else {
                    assertTrue(f.model.uiState.value is NextPracticeUiState.SourceChanged)
                }
                assertEquals(1, f.sessions.calls)
                assertEquals(0, f.sessions.writes)
                assertEquals(0, f.next.calls)
                job.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `source read failure cannot reveal a cached source before or after a new parked retry`() =
        runTest {
            val f = Fixture()
            val fail = CompletableDeferred<Unit>()
            f.sessions.querySource = {
                flow {
                    emit(f.sessions.values.value)
                    fail.await()
                    throw IOException("fixture source disclosure failure")
                }
            }
            watch(f)
            val previous = ready(f)
            val oldCallback = { f.model.canRevealSource("source", previous.generation) }
            fail.complete(Unit)
            runCurrent()
            assertTrue(f.model.canInteract())
            assertTrue(f.model.uiState.value is NextPracticeUiState.Failed)
            assertFalse(oldCallback())
            f.sessions.querySource = { f.sessions.values }
            assertTrue(f.model.retry())
            assertFalse(oldCallback())
            runCurrent()
            val current = ready(f)
            assertFalse(oldCallback())
            assertTrue(f.model.canRevealSource("source", current.generation))
            assertEquals(0, f.sessions.writes)
            assertEquals(0, f.next.calls)
        }

    @Test fun `a valid source remains readable during an explicit save without starting another write`() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            f.next.waitFor(release)
            watch(f)
            val state = choose(f)
            assertTrue(f.model.save("source", state.generation))
            runCurrent()
            val saving = ready(f)
            assertTrue(saving.saving)
            assertTrue(f.model.canRevealSource("source", saving.generation))
            assertTrue(f.model.canRevealSource("source", saving.generation))
            assertEquals(1, f.next.calls)
            assertEquals(1, f.sessions.calls)
            assertEquals(0, f.sessions.writes)
            release.complete(Unit)
            runCurrent()
            assertFalse(ready(f).saving)
            assertEquals(1, f.next.calls)
        }

    private fun TestScope.ready(f: Fixture): NextPracticeUiState.Ready {
        runCurrent()
        return f.model.uiState.value as NextPracticeUiState.Ready
    }

    private fun TestScope.choose(f: Fixture): NextPracticeUiState.Ready {
        val before = ready(f)
        assertTrue(f.model.selectTask("next", "source", before.generation))
        return ready(f)
    }

    private fun TestScope.events(f: Fixture): MutableList<Long> {
        val values = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            f.model.saved.collect { values += it }
        }
        runCurrent()
        return values
    }

    private fun copyState(state: SavedStateHandle): SavedStateHandle {
        val values = state.keys().associateWith { state.get<Any?>(it) }
        return SavedStateHandle(values)
    }

    private class Fixture(
        sourceArgument: Any? = "source",
        state: SavedStateHandle? = null,
        accessSource: StateFlow<VehicleState>? = null,
    ) {
        val vehicleState = MutableStateFlow(parked())
        val restrictionState = MutableStateFlow(permitted())
        val vehicle =
            object : VehicleGateway {
                override val state = accessSource ?: vehicleState
            }
        val restrictions =
            object : DrivingRestrictionsProvider {
                override val restrictions = restrictionState
            }
        val sessions = FakeSessions()
        val catalog = FakeCatalog()
        val next = FakeNextRepository()
        val handle = state ?: SavedStateHandle(mapOf("nextPracticeSourceId" to sourceArgument))
        val model = NextPracticeViewModel(vehicle, restrictions, sessions, catalog, next, handle)
    }

    private class FakeCatalog : PracticeCatalog {
        private val storedGoals = listOf(PracticeGoal("goal", "저장 목표", "목표 설명"))
        var onGoalsRead: (() -> Unit)? = null
        override val goals: List<PracticeGoal>
            get() {
                onGoalsRead?.invoke()
                return storedGoals
            }
        override var courses =
            listOf(
                PracticeCourse(
                    id = "course",
                    version = 1,
                    briefingVersion = 1,
                    title = "저장 코스",
                    summary = "요약",
                    sections = listOf("구간1", "구간2"),
                    tasks = listOf(oldTask(), nextTask()),
                    goalIds = setOf("goal"),
                ),
            )
    }

    private class FakeSessions : PracticeSessionRepository {
        val values = MutableStateFlow<List<PracticeSession>>(listOf(NextPracticeViewModelTest.source()))
        var querySource: () -> Flow<List<PracticeSession>> = { values }
        var factoryFailure: Exception? = null
        var calls = 0
        var active = 0
        var maximumActive = 0
        var writes = 0

        override fun observeSessions(): Flow<List<PracticeSession>> {
            calls++
            factoryFailure?.let { throw it }
            return flow {
                active++
                maximumActive = maxOf(maximumActive, active)
                try {
                    emitAll(querySource())
                } finally {
                    active--
                }
            }
        }

        override suspend fun start(
            practice: PreparedPractice,
            startedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionStartResult {
            writes++
            error("Next draft must not start a session")
        }

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult {
            writes++
            error("Next draft must not finish a session")
        }
    }

    private class FakeNextRepository : NextPracticeRepository {
        val seeds = mutableListOf<NextPracticeSeed>()
        val guards = mutableListOf<() -> Boolean>()
        var calls = 0
        var stored: PracticeSelection? = null
        val defaultHandler: suspend (NextPracticeSeed, PracticeSelection, () -> Boolean) -> NextPracticeSaveResult = ::writeAllowed
        var handler = defaultHandler

        private suspend fun writeAllowed(
            seed: NextPracticeSeed,
            selection: PracticeSelection,
            guard: () -> Boolean,
        ): NextPracticeSaveResult {
            if (!guard()) return NextPracticeSaveResult.Blocked
            stored = selection
            return NextPracticeSaveResult.Saved
        }

        fun waitFor(
            release: CompletableDeferred<Unit>,
            ignoreCancellation: Boolean = false,
        ) {
            handler = { seed, selection, guard ->
                if (ignoreCancellation) {
                    withContext(NonCancellable) { release.await() }
                } else {
                    release.await()
                }
                writeAllowed(seed, selection, guard)
            }
        }

        override suspend fun saveSelection(
            seed: NextPracticeSeed,
            selection: PracticeSelection,
            canCommit: () -> Boolean,
        ): NextPracticeSaveResult {
            calls++
            seeds += seed
            guards += canCommit
            return handler(seed, selection, canCommit)
        }
    }

    private companion object {
        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        fun driving() = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))

        fun permitted() = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)

        fun oldTask() = PracticeTask("old", "지난 과제", "기존 과제 설명")

        fun nextTask() = PracticeTask("next", "다음 과제", "Aa")

        fun source(
            id: String = "source",
            started: Long = 1_000,
            ended: Long? = 2_000,
        ) = PracticeSession(
            sessionId = id,
            selection = PracticeSelection("goal", "course", 1, 1, "old"),
            goalTitle = "저장 목표",
            courseTitle = "저장 코스",
            taskTitle = "지난 과제",
            courseReviewStatus = CourseReviewStatus.DEMO,
            startedAtEpochMillis = started,
            endedAtEpochMillis = ended,
        )

        fun preview() =
            CourseMapPreview(
                id = "preview",
                version = 1,
                title = "공개 표시점",
                points =
                    listOf(
                        CoursePreviewPoint("one", "공개 장소1", 37.0, 127.0, 1, 1),
                        CoursePreviewPoint("two", "공개 장소2", 37.1, 127.1, 2, 1),
                    ),
            )
    }
}
