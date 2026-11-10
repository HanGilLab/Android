package io.github.hangillab.hangil.feature.course

import androidx.lifecycle.SavedStateHandle
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseMapPreview
import io.github.hangillab.hangil.core.domain.CoursePreviewPoint
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.NextPracticeRepository
import io.github.hangillab.hangil.core.domain.NextPracticeSaveResult
import io.github.hangillab.hangil.core.domain.NextPracticeSeed
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PlannedPracticeRoad
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeCourse
import io.github.hangillab.hangil.core.domain.PracticeGoal
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.PracticeTask
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionReviewRoad
import io.github.hangillab.hangil.core.domain.SessionReviewRoadRepository
import io.github.hangillab.hangil.core.domain.SessionReviewRoadWriteResult
import io.github.hangillab.hangil.core.domain.SessionReviewSample
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.SessionTaskCount
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
import kotlinx.coroutines.flow.combine
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

    @Test fun recordSummaryKeepsHistoricalCourseAndTaskTitleBoundaries() =
        runTest {
            val f = Fixture()
            val selectedSource = source()
            val next = source("next-record").copy(selection = selectedSource.selection.copy(taskId = "next"), taskTitle = nextTask().title)
            val renamedTask = source("renamed-task").copy(taskTitle = "이전 이름의 과제")
            f.sessions.values.value =
                listOf(
                    selectedSource,
                    source("repeat"),
                    next,
                    renamedTask,
                    source("different-goal").copy(selection = selectedSource.selection.copy(goalId = "another")),
                    source("different-course").copy(selection = selectedSource.selection.copy(courseId = "another")),
                    source("old-version").copy(selection = selectedSource.selection.copy(courseVersion = 2)),
                    source("old-briefing").copy(selection = selectedSource.selection.copy(briefingVersion = 2)),
                    source("renamed-goal").copy(goalTitle = "다른 목표 이름"),
                    source("renamed-course").copy(courseTitle = "다른 코스 이름"),
                )
            watch(f)
            val summary = ready(f).summary
            assertEquals(selectedSource, summary.source)
            assertEquals(CourseReviewStatus.DEMO, summary.source.courseReviewStatus)
            assertEquals(4, summary.sessionCount)
            assertEquals(
                listOf(
                    SessionTaskCount("old", oldTask().title, 2),
                    SessionTaskCount("next", nextTask().title, 1),
                    SessionTaskCount("old", renamedTask.taskTitle, 1),
                ),
                summary.tasks,
            )
            assertNull(ready(f).taskId)
            assertEquals(1, f.sessions.calls)
            assertEquals(0, f.sessions.writes)
            assertEquals(0, f.next.calls)
        }

    @Test fun recordSummaryUpdatesPreserveSelectedDraftAndSaveFailure() =
        runTest {
            val f = Fixture()
            watch(f)
            val events = events(f)
            val chosen = choose(f)
            f.next.handler = { _, _, _ -> NextPracticeSaveResult.Blocked }
            assertTrue(f.model.save("source", chosen.generation))
            runCurrent()
            assertTrue(ready(f).saveFailed)

            f.sessions.values.value = listOf(source(), source("repeat"))
            runCurrent()
            val updated = ready(f)
            assertEquals(2, updated.summary.sessionCount)
            assertEquals(chosen.generation, updated.generation)
            assertEquals(chosen.seed, updated.seed)
            assertEquals("next", updated.taskId)
            assertTrue(updated.saveFailed)
            assertFalse(updated.saving)
            assertTrue(updated.canSave)
            assertEquals(1, f.next.calls)
            assertTrue(events.isEmpty())
            assertEquals(1, f.sessions.calls)
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

    @Test fun `summary updates for the same seed preserve pending commit ownership`() =
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
            sourceEvents.emit(listOf(source(), source("repeat")))
            runCurrent()
            assertEquals(2, ready(f).summary.sessionCount)
            assertEquals(state.generation, ready(f).generation)
            assertEquals("next", ready(f).taskId)
            assertTrue(ready(f).saving)
            assertFalse(ready(f).saveFailed)
            assertTrue(f.next.guards.single()())
            release.complete(Unit)
            runCurrent()
            assertEquals(1, events.size)
            assertEquals("next", f.next.stored?.taskId)
        }

    @Test fun optionalRoadArrivalKeepsPendingTaskOnlySave() =
        runTest {
            val f = Fixture()
            val roads = MutableSharedFlow<SessionReviewRoad?>(replay = 1)
            f.reviews.query = { roads }
            watch(f)
            val events = events(f)
            val release = CompletableDeferred<Unit>()
            f.next.waitFor(release, ignoreCancellation = true)
            val state = choose(f)
            assertEquals(NextPracticeUiState.ReviewRoad.Loading, state.reviewRoad)
            assertTrue(f.model.save("source", state.generation))
            runCurrent()
            assertEquals(1, f.next.calls)
            assertTrue(f.next.guards.single()())

            val road = targetReview()
            roads.emit(road)
            val afterRead = ready(f)
            val stillOwnsWrite = f.next.guards.single()()
            release.complete(Unit)
            runCurrent()

            assertEquals(NextPracticeUiState.ReviewRoad.Available(road), afterRead.reviewRoad)
            assertTrue(afterRead.saving)
            assertTrue(stillOwnsWrite)
            assertEquals(1, f.next.calls)
            assertNull(f.next.expectedReviews.single())
            assertEquals("next", f.next.stored?.taskId)
            assertNull(f.next.stored?.plannedRoad)
            assertEquals(1, events.size)
        }

    @Test fun optionalRoadReadFailureKeepsUnconsumedTaskOnlyCompletion() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            f.reviews.query = {
                flow {
                    release.await()
                    throw IOException("review read failed")
                }
            }
            watch(f)
            val events = events(f)
            val state = choose(f)
            assertEquals(NextPracticeUiState.ReviewRoad.Loading, state.reviewRoad)
            assertTrue(f.model.save("source", state.generation))
            runCurrent()
            val token = events.single()

            release.complete(Unit)
            val afterRead = ready(f)

            assertEquals(NextPracticeUiState.ReviewRoad.Failed, afterRead.reviewRoad)
            assertNotEquals(state.generation, afterRead.generation)
            assertFalse(f.model.selectTask("old", "source", state.generation))
            assertTrue(f.model.consumeSaved(token))
            assertFalse(f.model.consumeSaved(token))
            assertEquals(1, f.next.calls)
            assertEquals("next", f.next.stored?.taskId)
            assertEquals(1, events.size)
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
            val malformed =
                listOf(
                    listOf(source(), source()),
                    listOf(source(ended = 999)),
                    listOf(source().copy(sessionId = " ")),
                    listOf(source(), source("older"), source("older")),
                    listOf(source(), source("older").copy(taskTitle = "")),
                )
            for (records in malformed) {
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

    @Test fun `changed pinned road source versions retire a restored task even when IDs and coordinates match`() =
        runTest {
            val road =
                CourseRoadSnapshot(
                    "road",
                    "공개 도로 fixture",
                    "residential",
                    10,
                    3,
                    1_000,
                    listOf(CourseRoadSnapshot.Node(1, 1, 37.0, 127.0), CourseRoadSnapshot.Node(2, 2, 37.1, 127.1)),
                )
            val f = Fixture()
            f.catalog.courses = f.catalog.courses.map { it.copy(roadSnapshots = listOf(road)) }
            val reader = watch(f)
            choose(f)
            val saved = copyState(f.handle)
            reader.cancelAndJoin()
            runCurrent()

            val same = Fixture(state = copyState(saved))
            same.catalog.courses = f.catalog.courses
            val sameReader = watch(same)
            assertEquals("next", ready(same).taskId)
            sameReader.cancelAndJoin()
            runCurrent()

            val changedSources =
                listOf(
                    road.copy(osmWayVersion = road.osmWayVersion + 1),
                    road.copy(nodes = listOf(road.nodes.first().copy(osmNodeVersion = 9)) + road.nodes.drop(1)),
                )
            for (changedRoad in changedSources) {
                val changed = Fixture(state = copyState(saved))
                changed.catalog.courses = f.catalog.courses.map { it.copy(roadSnapshots = listOf(changedRoad)) }
                val changedReader = watch(changed)
                assertEquals(source(), ready(changed).seed.source)
                assertNull(ready(changed).taskId)
                assertFalse(ready(changed).canSave)
                assertEquals(0, changed.next.calls)
                changedReader.cancelAndJoin()
                runCurrent()
            }
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

    @Test fun `history explicitly selects an older ended source and keeps it when a newer record arrives`() =
        runTest {
            val f = historyFixture()
            f.sessions.values.value = listOf(source("newer"), source())
            val h = watchHistory(f)
            val displayed = historyReady(h)
            assertEquals(source(), displayed.seed.source)
            assertNull(displayed.taskId)
            assertFalse(f.model.save("source", displayed.generation))
            assertTrue(f.model.selectTask("next", "source", displayed.generation))
            val chosen = historyReady(h)
            f.sessions.values.value = listOf(source("newest"), source("newer"), source())
            runCurrent()
            val updated = historyReady(h)
            assertEquals(chosen.seed, updated.seed)
            assertEquals(chosen.taskId, updated.taskId)
            assertEquals(chosen.generation, updated.generation)
            assertEquals(2, chosen.summary.sessionCount)
            assertEquals(3, updated.summary.sessionCount)
            val events = events(f)
            assertTrue(f.model.save("source", chosen.generation))
            runCurrent()
            assertEquals(1, f.next.historyCalls)
            assertEquals(0, f.next.latestCalls)
            assertEquals(
                source(),
                f.next.seeds
                    .single()
                    .source,
            )
            assertEquals("next", f.next.stored?.taskId)
            assertEquals(0, f.sessions.writes)
            assertEquals(1, events.size)
            assertTrue(f.model.consumeSaved(events.single()))
            assertFalse(f.model.consumeSaved(events.single()))
        }

    @Test fun `history mutation deletion and active sessions expire the selected source without latest fallback`() =
        runTest {
            for (rows in listOf(
                listOf(source("newer")),
                listOf(source("newer"), source().copy(taskTitle = "변경한 과제")),
                listOf(source("active", ended = null), source()),
            )) {
                val f = historyFixture()
                val h = watchHistory(f)
                val old = historyChoose(f, h)
                f.sessions.values.value = rows
                runCurrent()
                assertTrue(h.state.value is NextPracticeUiState.SourceChanged)
                assertFalse(f.model.canRevealSource("source", old.generation))
                assertFalse(f.model.selectTask("old", "source", old.generation))
                assertFalse(f.model.save("source", old.generation))
                assertFalse(f.model.retry())
                assertEquals(0, f.next.calls)
                assertEquals(0, f.sessions.writes)
                h.job.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `history old catalog versions stay unavailable and never convert to current seed`() =
        runTest {
            val f = historyFixture()
            f.catalog.courses = f.catalog.courses.map { it.copy(version = 2) }
            val h = watchHistory(f)
            assertTrue(h.state.value is NextPracticeUiState.Unavailable)
            assertEquals(0, f.next.calls)
            assertFalse(f.model.retry())
        }

    @Test fun `invalid history requests never displace an existing latest reader`() =
        runTest {
            val f = Fixture()
            val latest = watch(f)
            val current = choose(f)
            val calls = f.sessions.calls
            for (expected in listOf(null, source("other"), source(ended = null), source())) {
                val h = watchHistory(f, expected = expected)
                assertTrue(h.state.value is NextPracticeUiState.SourceChanged)
                assertEquals(current, ready(f))
                assertTrue(f.model.canRevealSource("source", current.generation))
                assertEquals(calls, f.sessions.calls)
                h.job.cancelAndJoin()
                runCurrent()
            }
            latest.cancelAndJoin()
        }

    @Test fun `invalid selected or latest collectors cannot displace a healthy history owner`() =
        runTest {
            val f = historyFixture()
            val h = watchHistory(f)
            val current = historyChoose(f, h)
            val calls = f.sessions.calls
            val accidentalLatest = watch(f)
            assertEquals(current, historyReady(h))
            assertEquals(calls, f.sessions.calls)
            accidentalLatest.cancelAndJoin()
            runCurrent()
            for (request in 0..2) {
                val invalid =
                    watchHistory(
                        f,
                        expected = if (request == 0) null else source(),
                        isCurrent = { request != 1 },
                        canKeepOpen = { request != 2 },
                    )
                assertTrue(invalid.state.value is NextPracticeUiState.SourceChanged)
                assertEquals(current, historyReady(h))
                assertTrue(f.model.canRevealSource("source", current.generation))
                assertEquals(calls, f.sessions.calls)
                invalid.job.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `missing memory malformed tokens and absent history source never query latest`() =
        runTest {
            for (token in listOf(null, "", " ", 42)) {
                val f = historyFixture(token = token)
                val h = watchHistory(f)
                assertTrue(h.state.value is NextPracticeUiState.SourceChanged)
                assertEquals(0, f.sessions.calls)
                assertFalse(f.model.canInteract())
                h.job.cancelAndJoin()
                runCurrent()
            }
            for (request in 0..2) {
                val f = historyFixture()
                val h =
                    watchHistory(
                        f,
                        expected = if (request == 0) null else source(),
                        isCurrent = { request != 1 },
                        canKeepOpen = { request != 2 },
                    )
                assertTrue(h.state.value is NextPracticeUiState.SourceChanged)
                assertEquals(0, f.sessions.calls)
                assertFalse(f.model.canInteract())
                h.job.cancelAndJoin()
                runCurrent()
            }
            val f = historyFixture()
            val latest = watch(f)
            assertEquals(0, f.sessions.calls)
            assertFalse(f.model.canInteract())
            latest.cancelAndJoin()
        }

    @Test fun `history host and navigation receipts revoke pending writes immediately and cannot revive`() =
        runTest {
            for (revokeHost in listOf(false, true)) {
                val f = historyFixture()
                var host = true
                var entry = true
                val h = watchHistory(f, isCurrent = { entry }, canKeepOpen = { host })
                val selected = historyChoose(f, h)
                val events = events(f)
                val release = CompletableDeferred<Unit>()
                f.next.waitFor(release, ignoreCancellation = true)
                assertTrue(f.model.save("source", selected.generation))
                runCurrent()
                val guard = f.next.guards.single()
                assertTrue(guard())
                if (revokeHost) host = false else entry = false
                assertFalse(guard())
                assertFalse(f.model.canInteract())
                assertFalse(f.model.canRevealSource("source", selected.generation))
                assertFalse(f.model.selectTask("old", "source", selected.generation))
                host = true
                entry = true
                assertFalse(guard())
                assertFalse(f.model.save("source", selected.generation))
                release.complete(Unit)
                runCurrent()
                assertNull(f.next.stored)
                assertTrue(events.isEmpty())
                h.job.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `history requires both P and unrestricted UX at callbacks and cannot acknowledge a revoked entry`() =
        runTest {
            for (blockVehicle in listOf(false, true)) {
                val f = historyFixture()
                val h = watchHistory(f)
                val selected = historyChoose(f, h)
                if (blockVehicle) {
                    f.vehicleState.value = driving()
                } else {
                    f.restrictionState.value = permitted().copy(uxRestrictionState = UxRestrictionState.RESTRICTED)
                }
                assertFalse(f.model.canRevealSource("source", selected.generation))
                assertFalse(f.model.save("source", selected.generation))
                runCurrent()
                assertEquals(NextPracticeUiState.Locked, h.state.value)
                assertEquals(0, f.next.calls)
                h.job.cancelAndJoin()
                runCurrent()
            }
            val f = historyFixture()
            var entry = true
            val h = watchHistory(f, isCurrent = { entry })
            val chosen = historyChoose(f, h)
            val events = events(f)
            f.model.save("source", chosen.generation)
            runCurrent()
            assertEquals(1, events.size)
            entry = false
            assertFalse(f.model.consumeSaved(events.single()))
            entry = true
            assertFalse(f.model.consumeSaved(events.single()))
            assertEquals(1, f.next.historyCalls)
        }

    @Test fun `history cancellation rejects old IO and acknowledgement before fresh resumed owner`() =
        runTest {
            val f = historyFixture()
            val old = watchHistory(f)
            val chosen = historyChoose(f, old)
            val events = events(f)
            val release = CompletableDeferred<Unit>()
            f.next.waitFor(release, ignoreCancellation = true)
            f.model.save("source", chosen.generation)
            runCurrent()
            val oldGuard = f.next.guards.single()
            old.job.cancel()
            assertFalse(oldGuard())
            assertFalse(f.model.canRevealSource("source", chosen.generation))
            val fresh = watchHistory(f)
            val resumed = historyReady(fresh)
            assertEquals("next", resumed.taskId)
            assertNotEquals(chosen.generation, resumed.generation)
            assertFalse(f.model.save("source", chosen.generation))
            release.complete(Unit)
            runCurrent()
            assertNull(f.next.stored)
            assertTrue(events.isEmpty())
            f.next.handler = f.next.defaultHandler
            assertTrue(f.model.save("source", resumed.generation))
            runCurrent()
            val token = events.single()
            fresh.job.cancelAndJoin()
            runCurrent()
            val final = watchHistory(f)
            assertEquals("next", historyReady(final).taskId)
            assertFalse(f.model.consumeSaved(token))
            assertFalse(f.model.canRevealSource("source", resumed.generation))
            assertEquals(2, f.next.historyCalls)
            assertEquals(0, f.sessions.writes)
        }

    @Test fun `history task restoration binds mode token and full source while process memory stays mandatory`() =
        runTest {
            val f = historyFixture()
            val h = watchHistory(f)
            historyChoose(f, h)
            val state = copyState(f.handle)
            h.job.cancelAndJoin()
            runCurrent()
            val same = Fixture(state = copyState(state))
            val resumed = watchHistory(same)
            assertEquals("next", historyReady(resumed).taskId)
            resumed.job.cancelAndJoin()
            runCurrent()
            val changedToken = copyState(state)
            changedToken["historyNextToken"] = "another-token"
            val newEntry = Fixture(state = changedToken)
            val newWatch = watchHistory(newEntry)
            assertNull(historyReady(newWatch).taskId)
            newWatch.job.cancelAndJoin()
            runCurrent()
            val changedSource = source().copy(startedAtEpochMillis = 999)
            val changed = Fixture(state = copyState(state))
            changed.sessions.values.value = listOf(changedSource)
            val changedWatch = watchHistory(changed, expected = changedSource)
            assertNull(historyReady(changedWatch).taskId)
            changedWatch.job.cancelAndJoin()
            runCurrent()
            val missingMemory = Fixture(state = copyState(state))
            val missing = watchHistory(missingMemory, expected = null)
            assertTrue(missing.state.value is NextPracticeUiState.SourceChanged)
            assertEquals(0, missingMemory.sessions.calls)
            missing.job.cancelAndJoin()
            val latestState = copyState(state)
            latestState.remove<Any?>("historyNextToken")
            val latest = Fixture(state = latestState)
            watch(latest)
            assertNull(ready(latest).taskId)
            assertEquals(0, latest.next.calls)
        }

    private fun historyFixture(token: Any? = "history-token"): Fixture =
        Fixture(state = SavedStateHandle(mapOf("nextPracticeSourceId" to "source", "historyNextToken" to token)))

    private fun TestScope.watchHistory(
        f: Fixture,
        expected: PracticeSession? = source(),
        isCurrent: () -> Boolean = { true },
        canKeepOpen: () -> Boolean = { true },
    ): HistoryWatch {
        val state = MutableStateFlow<NextPracticeUiState>(NextPracticeUiState.Loading)
        val job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model.observeHistory(expected, isCurrent, canKeepOpen).collect { state.value = it }
            }
        runCurrent()
        return HistoryWatch(state, job)
    }

    private fun TestScope.historyReady(h: HistoryWatch): NextPracticeUiState.Ready {
        runCurrent()
        return h.state.value as NextPracticeUiState.Ready
    }

    private fun TestScope.historyChoose(
        f: Fixture,
        h: HistoryWatch,
    ): NextPracticeUiState.Ready {
        val current = historyReady(h)
        assertTrue(f.model.selectTask("next", "source", current.generation))
        return historyReady(h)
    }

    private data class HistoryWatch(
        val state: StateFlow<NextPracticeUiState>,
        val job: Job,
    )

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

    @Test fun targetRoadIsExplicitSurvivesTaskChangeAndPassesTheWholeReviewToTheWriterWithoutCopyingMemo() =
        runTest {
            val f = Fixture()
            val review = targetReview()
            f.reviews.value.value = review
            watch(f)
            val initial = choose(f)
            assertNull(initial.targetReview)
            assertTrue(f.model.useReviewRoad("source", initial.generation))
            var current = ready(f)
            assertEquals(review, current.targetReview)
            assertTrue(f.model.selectTask("old", "source", current.generation))
            current = ready(f)
            assertEquals(review, current.targetReview)
            assertTrue(f.model.save("source", current.generation))
            runCurrent()
            assertEquals(review, f.next.expectedReviews.single())
            assertEquals(PlannedPracticeRoad.from(review), f.next.stored?.plannedRoad)
            assertEquals(review, f.reviews.value.value)
        }

    @Test fun changedReviewCancelsAPendingWriteAndNeedsFreshChoiceOrExplicitUnlink() =
        runTest {
            val f = Fixture()
            val old = targetReview()
            f.reviews.value.value = old
            watch(f)
            var state = choose(f)
            assertTrue(f.model.useReviewRoad("source", state.generation))
            state = ready(f)
            val release = CompletableDeferred<Unit>()
            f.next.waitFor(release, ignoreCancellation = true)
            assertTrue(f.model.save("source", state.generation))
            runCurrent()
            val replacement = SessionReviewRoad(old.source, old.road, "new-version", "changed original memo")
            f.reviews.value.value = replacement
            runCurrent()
            val changed = ready(f)
            assertNull(changed.targetReview)
            assertTrue(changed.targetChoiceRequired)
            assertFalse(changed.canSave)
            assertFalse(f.model.useReviewRoad("source", state.generation))
            release.complete(Unit)
            runCurrent()
            assertNull(f.next.stored)
            assertTrue(f.model.useReviewRoad("source", changed.generation))
            val selected = ready(f)
            assertTrue(selected.canSave)
            assertTrue(f.model.save("source", selected.generation))
            runCurrent()
            assertEquals(PlannedPracticeRoad.from(replacement), f.next.stored?.plannedRoad)
        }

    @Test fun targetReadFailureRemainsDistinctAndExplicitRetryNeverAutomaticallyReselectsTheOldTarget() =
        runTest {
            val f = Fixture()
            f.reviews.value.value = targetReview()
            watch(f)
            val chosen = choose(f)
            assertTrue(f.model.useReviewRoad("source", chosen.generation))
            f.reviews.failed.value = true
            runCurrent()
            var state = ready(f)
            assertEquals(NextPracticeUiState.ReviewRoad.Failed, state.reviewRoad)
            assertTrue(state.targetChoiceRequired)
            assertFalse(state.canSave)
            f.reviews.failed.value = false
            runCurrent()
            assertEquals(NextPracticeUiState.ReviewRoad.Failed, ready(f).reviewRoad)
            assertTrue(f.model.retryReviewRoad("source", state.generation))
            state = ready(f)
            assertTrue(state.reviewRoad is NextPracticeUiState.ReviewRoad.Available)
            assertNull(state.targetReview)
            assertTrue(state.targetChoiceRequired)
            assertTrue(f.model.unlinkReviewRoad("source", state.generation))
            state = ready(f)
            assertTrue(state.canSave)
            assertTrue(f.model.save("source", state.generation))
            runCurrent()
            assertNull(f.next.stored?.plannedRoad)
            assertNull(f.next.expectedReviews.single())
        }

    @Test fun restoredTargetIntentBlocksSavingUntilFreshUseOrExplicitUnlinkAndKeepsTheUnlinkChoice() =
        runTest {
            val selected = Fixture()
            val review = targetReview()
            selected.reviews.value.value = review
            watch(selected)
            val task = choose(selected)
            assertTrue(selected.model.useReviewRoad("source", task.generation))
            assertEquals(true, selected.handle.get<Any?>("nextPracticeTargetChoicePending"))

            val restored = Fixture(state = copyState(selected.handle))
            restored.reviews.value.value = review
            watch(restored)
            var state = ready(restored)
            assertEquals("next", state.taskId)
            assertNull(state.targetReview)
            assertTrue(state.targetChoiceRequired)
            assertFalse(state.canSave)
            assertFalse(restored.model.save("source", state.generation))
            assertEquals(0, restored.next.calls)
            assertTrue(restored.model.useReviewRoad("source", state.generation))
            state = ready(restored)
            assertEquals(review, state.targetReview)
            assertTrue(state.canSave)
            assertTrue(restored.model.save("source", state.generation))
            runCurrent()
            assertEquals(PlannedPracticeRoad.from(review), restored.next.stored?.plannedRoad)

            val waitingForChoice = Fixture(state = copyState(restored.handle))
            waitingForChoice.reviews.value.value = review
            watch(waitingForChoice)
            state = ready(waitingForChoice)
            assertTrue(state.targetChoiceRequired)
            assertTrue(waitingForChoice.model.unlinkReviewRoad("source", state.generation))
            assertEquals(false, waitingForChoice.handle.get<Any?>("nextPracticeTargetChoicePending"))
            val explicitlyUnlinked = Fixture(state = copyState(waitingForChoice.handle))
            explicitlyUnlinked.reviews.value.value = review
            watch(explicitlyUnlinked)
            state = ready(explicitlyUnlinked)
            assertEquals("next", state.taskId)
            assertNull(state.targetReview)
            assertFalse(state.targetChoiceRequired)
            assertTrue(state.canSave)
            assertEquals(0, explicitlyUnlinked.next.calls)
            assertTrue(explicitlyUnlinked.model.save("source", state.generation))
            runCurrent()
            assertNull(explicitlyUnlinked.next.stored?.plannedRoad)
            assertNull(explicitlyUnlinked.next.expectedReviews.single())
        }

    private fun targetReview(): SessionReviewRoad =
        SessionReviewRoad(
            source(),
            CourseRoadSnapshot(
                "road",
                "선택 도로",
                "residential",
                99,
                1,
                1000,
                listOf(CourseRoadSnapshot.Node(1, 1, 37.0, 127.0), CourseRoadSnapshot.Node(2, 1, 37.001, 127.001)),
            ),
            "review-v1",
            "source memo",
        )

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
        val reviews = FakeReviews()
        val handle = state ?: SavedStateHandle(mapOf("nextPracticeSourceId" to sourceArgument))
        val model = NextPracticeViewModel(vehicle, restrictions, sessions, catalog, next, handle, reviews)
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

    private class FakeReviews : SessionReviewRoadRepository {
        val value = MutableStateFlow<SessionReviewRoad?>(null)
        val failed = MutableStateFlow(false)
        var query: () -> Flow<SessionReviewRoad?> = {
            combine(failed, value) { bad, row -> if (bad) throw IOException("review read failed") else row }
        }

        override fun observe(sessionId: String): Flow<SessionReviewRoad?> = flow { emitAll(query()) }

        override suspend fun setSample(
            expectedSource: PracticeSession,
            expectedCurrent: SessionReviewRoad,
            sample: SessionReviewSample?,
            canCommit: () -> Boolean,
        ): SessionReviewRoadWriteResult = error("A next practice reference cannot change a review sample")

        override suspend fun save(
            expectedSource: PracticeSession,
            road: CourseRoadSnapshot,
            expectedCurrent: SessionReviewRoad?,
            memoText: String,
            discardMemo: Boolean,
            discardSample: Boolean,
            initialSample: SessionReviewSample?,
            canCommit: () -> Boolean,
        ): SessionReviewRoadWriteResult = error("Next preparation cannot write a review")

        override suspend fun clear(
            expectedSource: PracticeSession,
            expectedCurrent: SessionReviewRoad,
            discardMemo: Boolean,
            discardSample: Boolean,
            canCommit: () -> Boolean,
        ): SessionReviewRoadWriteResult = error("Next preparation cannot clear a review")
    }

    private class FakeNextRepository : NextPracticeRepository {
        val seeds = mutableListOf<NextPracticeSeed>()
        val expectedReviews = mutableListOf<SessionReviewRoad?>()
        val guards = mutableListOf<() -> Boolean>()
        var calls = 0
        var latestCalls = 0
        var historyCalls = 0
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
            expectedReview: SessionReviewRoad?,
            canCommit: () -> Boolean,
        ): NextPracticeSaveResult {
            expectedReviews += expectedReview
            latestCalls++
            return record(seed, selection, canCommit)
        }

        override suspend fun saveHistorySelection(
            seed: NextPracticeSeed,
            selection: PracticeSelection,
            expectedReview: SessionReviewRoad?,
            canCommit: () -> Boolean,
        ): NextPracticeSaveResult {
            expectedReviews += expectedReview
            historyCalls++
            return record(seed, selection, canCommit)
        }

        private suspend fun record(
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
