package io.github.hangillab.hangil.feature.course

import androidx.lifecycle.SavedStateHandle
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PlannedPracticeRoad
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeCourse
import io.github.hangillab.hangil.core.domain.PracticeGoal
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeTask
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CourseViewModelTest {
    private val catalog =
        object : PracticeCatalog {
            override val goals = listOf(PracticeGoal("a", "목표 A", ""), PracticeGoal("b", "목표 B", ""))
            override var courses =
                listOf(
                    PracticeCourse(
                        "c",
                        2,
                        3,
                        "코스",
                        "",
                        emptyList(),
                        listOf(PracticeTask("t", "과제", ""), PracticeTask("u", "과제2", "")),
                        setOf("a", "b"),
                    ),
                )
        }
    private val vehicle =
        object : VehicleGateway {
            override val state = MutableStateFlow(VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK)))
        }
    private val restrictions =
        object : DrivingRestrictionsProvider {
            override val restrictions = MutableStateFlow(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))
        }
    private val repository = TestPracticeRepository()

    private fun model(state: SavedStateHandle = SavedStateHandle()) = CourseViewModel(catalog, repository, vehicle, restrictions, state)

    private fun TestScope.observe(model: CourseViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
        runCurrent()
    }

    private fun prepare(model: CourseViewModel) {
        model.selectGoal("a")
        model.next()
        model.selectCourse("c")
        model.next()
        model.selectTask("t")
    }

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `details use live P and UX before combined state catches up`() =
        runTest {
            val model = model()
            observe(model)
            prepare(model)
            runCurrent()
            val displayed = model.uiState.value
            assertTrue(model.canRevealDetails(displayed))
            vehicle.state.value = vehicle.state.value.copy(gear = VehicleReading.Available(GearPosition.DRIVE))
            assertEquals(displayed, model.uiState.value)
            assertFalse(model.canRevealDetails(displayed))
            vehicle.state.value = vehicle.state.value.copy(gear = VehicleReading.Available(GearPosition.PARK))
            for (ux in UxRestrictionState.entries.filter { it != UxRestrictionState.UNRESTRICTED }) {
                restrictions.restrictions.value = DrivingRestrictions(ParkingState.PARKED, ux)
                assertFalse(model.canRevealDetails(displayed))
            }
            restrictions.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
            assertTrue(model.canRevealDetails(displayed))
            vehicle.state.value = VehicleState()
            assertFalse(model.canRevealDetails(displayed))
            assertEquals(0, repository.calls)
        }

    @Test fun `details reject a changed draft before publication while current briefing and demo remain readable`() =
        runTest {
            val model = model()
            observe(model)
            prepare(model)
            runCurrent()
            val old = model.uiState.value
            model.selectTask("u")
            assertEquals(old, model.uiState.value)
            assertFalse(model.canRevealDetails(old))
            runCurrent()
            assertTrue(model.canRevealDetails(model.uiState.value))
            assertFalse(model.canRevealDetails(old.copy(course = old.course?.copy(title = "다른 원문"))))
            model.back()
            assertFalse(model.canRevealDetails(old))
            runCurrent()
            assertEquals(PreparationStep.COURSE, model.uiState.value.step)
            assertTrue(model.canRevealDetails(model.uiState.value))
            model.back()
            runCurrent()
            assertFalse(model.canRevealDetails(model.uiState.value))
            assertEquals(0, repository.calls)
        }

    @Test fun `details reject a stopped or closed reader without starting a save`() =
        runTest {
            val model = model()
            val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
            runCurrent()
            prepare(model)
            runCurrent()
            val old = model.uiState.value
            assertTrue(model.canRevealDetails(old))
            observer.cancel()
            runCurrent()
            assertFalse(model.canRevealDetails(old))
            observe(model)
            assertTrue(model.canRevealDetails(model.uiState.value))
            model.leave()
            assertFalse(model.canRevealDetails(model.uiState.value))
            assertEquals(0, repository.calls)
        }

    @Test fun `complete flow saves one task and emits completion once`() =
        runTest {
            val model = model()
            observe(model)
            prepare(model)
            val events = mutableListOf<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.saved.collect { events += it } }
            model.next()
            model.next()
            runCurrent()
            assertEquals(PracticeSelection("a", "c", 2, 3, "t"), repository.selection.value)
            assertEquals(1, repository.calls)
            assertEquals(1, events.size)
        }

    @Test fun `new gear event blocks actions before combined UI catches up`() =
        runTest {
            val model = model()
            observe(model)
            assertTrue(model.uiState.value.allowsInteraction)
            vehicle.state.value = vehicle.state.value.copy(gear = VehicleReading.Available(GearPosition.DRIVE))
            model.selectGoal("a")
            model.next()
            runCurrent()
            assertNull(model.uiState.value.goal)
            assertFalse(model.uiState.value.allowsInteraction)
            assertEquals(0, repository.calls)
        }

    @Test fun `unknown UX or disconnection blocks saving`() =
        runTest {
            val model = model()
            observe(model)
            prepare(model)
            restrictions.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNKNOWN)
            model.next()
            runCurrent()
            assertEquals(0, repository.calls)
            restrictions.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
            vehicle.state.value = VehicleState()
            model.next()
            runCurrent()
            assertEquals(0, repository.calls)
        }

    @Test fun `queued save is cancelled when driving and does not resume on parking`() =
        runTest {
            val old = PracticeSelection("b", "c", 2, 3, "u")
            repository.selection.value = old
            val gate = CompletableDeferred<Unit>()
            repository.beforeWrite = { gate.await() }
            val model = model()
            observe(model)
            prepare(model)
            model.next()
            runCurrent()
            assertTrue(model.uiState.value.saving)
            vehicle.state.value = vehicle.state.value.copy(gear = VehicleReading.Available(GearPosition.DRIVE))
            runCurrent()
            vehicle.state.value = vehicle.state.value.copy(gear = VehicleReading.Available(GearPosition.PARK))
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertEquals(old, repository.selection.value)
            assertFalse(model.uiState.value.saving)
            assertEquals("t", model.uiState.value.taskId)
        }

    @Test fun `leaving the visible screen cancels a pending write`() =
        runTest {
            val model = model()
            val collecting = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
            runCurrent()
            prepare(model)
            val gate = CompletableDeferred<Unit>()
            repository.beforeWrite = { gate.await() }
            model.next()
            runCurrent()
            collecting.cancel()
            runCurrent()
            gate.complete(Unit)
            runCurrent()
            assertNull(repository.selection.value)
        }

    @Test fun `failure preserves selection and draft and permits retry`() =
        runTest {
            repository.beforeWrite = { error("disk unavailable") }
            val model = model()
            observe(model)
            prepare(model)
            model.next()
            runCurrent()
            assertNull(repository.selection.value)
            assertTrue(model.uiState.value.saveFailed)
            assertEquals("t", model.uiState.value.taskId)
            repository.beforeWrite = {}
            model.next()
            runCurrent()
            assertEquals("t", repository.selection.value?.taskId)
        }

    @Test fun `changing goal clears course and task without writing`() =
        runTest {
            val model = model()
            observe(model)
            prepare(model)
            model.back()
            model.back()
            model.selectGoal("b")
            runCurrent()
            assertEquals(
                "b",
                model.uiState.value.goal
                    ?.id,
            )
            assertNull(model.uiState.value.course)
            assertNull(model.uiState.value.taskId)
            assertEquals(0, repository.calls)
        }

    @Test fun `reselecting the same course after going back retains the chosen task until explicit save`() =
        runTest {
            val model = model()
            observe(model)
            prepare(model)
            runCurrent()
            assertEquals("t", model.uiState.value.taskId)
            assertTrue(model.back())
            runCurrent()
            assertEquals(PreparationStep.COURSE, model.uiState.value.step)
            model.selectCourse("c")
            model.next()
            runCurrent()
            assertEquals(PreparationStep.BRIEFING, model.uiState.value.step)
            assertEquals("t", model.uiState.value.taskId)
            assertTrue(model.uiState.value.canContinue)
            assertEquals(0, repository.calls)
            assertNull(repository.selection.value)
            model.next()
            runCurrent()
            assertEquals(PracticeSelection("a", "c", 2, 3, "t"), repository.selection.value)
            assertEquals(1, repository.calls)
        }

    @Test fun `selecting another course clears the old task even when the task ID also exists there`() =
        runTest {
            catalog.courses = catalog.courses + catalog.courses.single().copy(id = "other")
            val model = model()
            observe(model)
            prepare(model)
            assertTrue(model.back())
            model.selectCourse("other")
            model.next()
            runCurrent()
            assertEquals(
                "other",
                model.uiState.value.course
                    ?.id,
            )
            assertEquals(PreparationStep.BRIEFING, model.uiState.value.step)
            assertNull(model.uiState.value.taskId)
            assertFalse(model.uiState.value.canContinue)
            assertEquals(0, repository.calls)
        }

    @Test fun `changed course or briefing versions require the task to be selected again`() =
        runTest {
            val original = catalog.courses.single()
            for (changed in listOf(original.copy(version = 3), original.copy(briefingVersion = 4))) {
                catalog.courses = listOf(original)
                val model = model()
                observe(model)
                prepare(model)
                assertTrue(model.back())
                catalog.courses = listOf(changed)
                model.selectCourse("c")
                model.next()
                runCurrent()
                assertEquals(changed, model.uiState.value.course)
                assertEquals(PreparationStep.BRIEFING, model.uiState.value.step)
                assertNull(model.uiState.value.taskId)
                assertFalse(model.uiState.value.canContinue)
                assertEquals(0, repository.calls)
            }
        }

    @Test fun `a task removed from the unchanged course cannot survive reselection`() =
        runTest {
            val model = model()
            observe(model)
            prepare(model)
            assertTrue(model.back())
            catalog.courses = catalog.courses.map { course -> course.copy(tasks = course.tasks.filterNot { it.id == "t" }) }
            model.selectCourse("c")
            model.next()
            runCurrent()
            assertEquals(PreparationStep.BRIEFING, model.uiState.value.step)
            assertNull(model.uiState.value.taskId)
            assertFalse(model.uiState.value.canContinue)
            assertEquals(0, repository.calls)
        }

    @Test fun `fresh preparation edit retains the stored selection without reselection or implicit writes`() =
        runTest {
            val stored = PracticeSelection("b", "c", 2, 3, "u")
            repository.selection.value = stored
            val model = model(SavedStateHandle())
            observe(model)
            assertEquals(PreparationStep.GOAL, model.uiState.value.step)
            assertEquals(
                "b",
                model.uiState.value.goal
                    ?.id,
            )
            assertEquals(
                "c",
                model.uiState.value.course
                    ?.id,
            )
            assertEquals("u", model.uiState.value.taskId)
            assertTrue(model.uiState.value.canContinue)
            assertEquals(0, repository.calls)
            assertEquals(stored, repository.selection.value)
            model.next()
            runCurrent()
            assertEquals(PreparationStep.COURSE, model.uiState.value.step)
            assertEquals(
                "c",
                model.uiState.value.course
                    ?.id,
            )
            assertTrue(model.uiState.value.canContinue)
            model.next()
            runCurrent()
            assertEquals(PreparationStep.BRIEFING, model.uiState.value.step)
            assertEquals("u", model.uiState.value.taskId)
            assertTrue(model.uiState.value.canContinue)
            assertTrue(model.back())
            assertTrue(model.back())
            assertFalse(model.back())
            runCurrent()
            assertFalse(model.uiState.value.allowsInteraction)
            assertEquals(0, repository.calls)
            assertEquals(stored, repository.selection.value)
        }

    @Test fun hydratedEditRejectsReplayedNextAndSameStepReturnBeforeFreshSave() =
        runTest {
            val stored = PracticeSelection("b", "c", 2, 3, "u")
            repository.selection.value = stored
            val model = model()
            observe(model)
            val initialGoal = model.uiState.value
            assertEquals(PreparationStep.GOAL, initialGoal.step)
            assertEquals(stored.taskId, initialGoal.taskId)
            val oldNext = { model.next(initialGoal.actionGeneration) }

            repeat(3) { oldNext() }
            runCurrent()
            assertEquals(0, repository.calls)
            assertEquals(PreparationStep.COURSE, model.uiState.value.step)

            assertTrue(model.back(model.uiState.value.actionGeneration))
            runCurrent()
            assertEquals(PreparationStep.GOAL, model.uiState.value.step)
            oldNext()
            runCurrent()
            assertEquals(PreparationStep.GOAL, model.uiState.value.step)

            model.next(model.uiState.value.actionGeneration)
            runCurrent()
            model.next(model.uiState.value.actionGeneration)
            runCurrent()
            assertEquals(PreparationStep.BRIEFING, model.uiState.value.step)
            model.selectTask("t", model.uiState.value.actionGeneration)
            runCurrent()
            assertEquals(0, repository.calls)
            assertEquals(stored, repository.selection.value)

            val saveGeneration = model.uiState.value.actionGeneration
            model.next(saveGeneration)
            runCurrent()
            model.next(saveGeneration)
            runCurrent()
            assertEquals(1, repository.calls)
            assertEquals(stored.copy(taskId = "t"), repository.selection.value)
        }

    @Test fun `the stored selection is read once and later emissions cannot overwrite an owned edit`() =
        runTest {
            val stored = PracticeSelection("b", "c", 2, 3, "u")
            repository.selection.value = stored
            val model = model()
            val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
            runCurrent()
            assertEquals(1, repository.readCalls)
            repository.selection.value = PracticeSelection("a", "c", 2, 3, "t")
            runCurrent()
            assertEquals(
                "b",
                model.uiState.value.goal
                    ?.id,
            )
            assertEquals("u", model.uiState.value.taskId)
            model.selectGoal("a")
            runCurrent()
            assertEquals(
                "a",
                model.uiState.value.goal
                    ?.id,
            )
            assertNull(model.uiState.value.course)
            assertNull(model.uiState.value.taskId)
            observer.cancel()
            runCurrent()
            observe(model)
            assertEquals(1, repository.readCalls)
            assertEquals(
                "a",
                model.uiState.value.goal
                    ?.id,
            )
            assertNull(model.uiState.value.course)
            assertEquals(0, repository.calls)
        }

    @Test fun `owned partial and invalid drafts do not backfill from a different stored selection`() =
        runTest {
            repository.selection.value = PracticeSelection("b", "c", 2, 3, "u")
            repository.read = { repository.selection }
            for ((state, expectedGoal) in listOf(
                SavedStateHandle(mapOf("goal" to "a")) to "a",
                SavedStateHandle(mapOf("goal" to "missing")) to null,
            )) {
                val model = model(state)
                observe(model)
                assertEquals(PreparationReadState.READY, model.uiState.value.preparationRead)
                assertEquals(PreparationStep.GOAL, model.uiState.value.step)
                assertEquals(
                    expectedGoal,
                    model.uiState.value.goal
                        ?.id,
                )
                assertNull(model.uiState.value.course)
                assertNull(model.uiState.value.taskId)
                assertEquals(if (expectedGoal == "a") 1 else 2, repository.readCalls)
                assertEquals(0, repository.calls)
            }
        }

    @Test fun `a delayed first read blocks edits and duplicate retry until it installs a valid selection`() =
        runTest {
            val stored = PracticeSelection("b", "c", 2, 3, "u")
            repository.selection.value = stored
            val gate = CompletableDeferred<Unit>()
            repository.read = {
                flow {
                    gate.await()
                    emit(stored)
                }
            }
            val model = model()
            observe(model)
            assertEquals(PreparationReadState.LOADING, model.uiState.value.preparationRead)
            assertTrue(model.uiState.value.allowsInteraction)
            assertFalse(model.uiState.value.canContinue)
            model.selectGoal("a")
            model.next()
            model.selectCourse("c")
            model.selectTask("t")
            model.retryPreparationRead()
            runCurrent()
            assertEquals(PreparationStep.GOAL, model.uiState.value.step)
            assertNull(model.uiState.value.goal)
            assertNull(model.uiState.value.course)
            assertNull(model.uiState.value.taskId)
            assertFalse(model.canRevealDetails(model.uiState.value))
            assertEquals(1, repository.readCalls)
            assertEquals(0, repository.calls)
            gate.complete(Unit)
            runCurrent()
            assertEquals(PreparationReadState.READY, model.uiState.value.preparationRead)
            assertEquals(
                "b",
                model.uiState.value.goal
                    ?.id,
            )
            assertEquals("u", model.uiState.value.taskId)
            assertTrue(model.uiState.value.canContinue)
            assertEquals(stored, repository.selection.value)
            assertEquals(0, repository.calls)
        }

    @Test fun `read failure stays failed across a stopped reader and only explicit retry starts a new read`() =
        runTest {
            val stored = PracticeSelection("b", "c", 2, 3, "u")
            repository.selection.value = stored
            repository.read = { flow { error("read unavailable") } }
            val state = SavedStateHandle()
            val model = model(state)
            val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
            runCurrent()
            assertEquals(PreparationReadState.FAILED, model.uiState.value.preparationRead)
            assertTrue(model.uiState.value.allowsInteraction)
            assertFalse(model.uiState.value.canContinue)
            model.selectGoal("a")
            model.next()
            runCurrent()
            assertNull(model.uiState.value.goal)
            assertFalse(state.contains("step"))
            assertFalse(state.contains("initialPreparationOutdated"))
            observer.cancel()
            runCurrent()
            observe(model)
            assertEquals(PreparationReadState.FAILED, model.uiState.value.preparationRead)
            assertEquals(1, repository.readCalls)
            repository.read = { flowOf(stored) }
            model.retryPreparationRead()
            model.retryPreparationRead()
            runCurrent()
            assertEquals(2, repository.readCalls)
            assertEquals(PreparationReadState.READY, model.uiState.value.preparationRead)
            assertEquals(
                "b",
                model.uiState.value.goal
                    ?.id,
            )
            assertEquals("u", model.uiState.value.taskId)
            assertEquals(stored, repository.selection.value)
            assertEquals(0, repository.calls)
        }

    @Test fun `completion without a value is a failed read while an explicit null is a known empty preparation`() =
        runTest {
            repository.read = { emptyFlow() }
            val model = model()
            observe(model)
            assertEquals(PreparationReadState.FAILED, model.uiState.value.preparationRead)
            assertFalse(model.uiState.value.canContinue)
            repository.read = { flowOf(null) }
            model.retryPreparationRead()
            runCurrent()
            assertEquals(PreparationReadState.READY, model.uiState.value.preparationRead)
            assertEquals(PreparationStep.GOAL, model.uiState.value.step)
            assertNull(model.uiState.value.goal)
            assertNull(model.uiState.value.course)
            assertNull(model.uiState.value.taskId)
            assertEquals(2, repository.readCalls)
            assertEquals(0, repository.calls)
        }

    @Test fun `a cancelled old read cannot fail or replace a later successful reader`() =
        runTest {
            val oldGate = CompletableDeferred<Unit>()
            repository.read = {
                flow {
                    withContext(NonCancellable) { oldGate.await() }
                    error("cancelled old read failed late")
                }
            }
            val model = model()
            val oldObserver = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
            runCurrent()
            assertEquals(PreparationReadState.LOADING, model.uiState.value.preparationRead)
            oldObserver.cancel()
            runCurrent()
            val replacement = PracticeSelection("b", "c", 2, 3, "u")
            repository.read = { flowOf(replacement) }
            observe(model)
            assertEquals(PreparationReadState.READY, model.uiState.value.preparationRead)
            assertEquals(
                "b",
                model.uiState.value.goal
                    ?.id,
            )
            oldGate.complete(Unit)
            runCurrent()
            assertEquals(PreparationReadState.READY, model.uiState.value.preparationRead)
            assertEquals(
                "b",
                model.uiState.value.goal
                    ?.id,
            )
            assertEquals("u", model.uiState.value.taskId)
            assertEquals(2, repository.readCalls)
            assertEquals(0, repository.calls)
        }

    @Test fun `closing during a delayed read cannot install a draft or reopen the reader`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val stored = PracticeSelection("b", "c", 2, 3, "u")
            repository.selection.value = stored
            repository.read = {
                flow {
                    withContext(NonCancellable) { gate.await() }
                    emit(stored)
                }
            }
            val state = SavedStateHandle()
            val model = model(state)
            val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
            runCurrent()
            model.leave()
            gate.complete(Unit)
            runCurrent()
            assertFalse(model.uiState.value.allowsInteraction)
            assertEquals(PreparationReadState.LOADING, model.uiState.value.preparationRead)
            assertNull(model.uiState.value.goal)
            assertNull(model.uiState.value.taskId)
            assertFalse(state.contains("step"))
            assertFalse(state.contains("initialPreparationOutdated"))
            observer.cancel()
            runCurrent()
            observe(model)
            model.retryPreparationRead()
            model.selectGoal("a")
            runCurrent()
            assertFalse(model.uiState.value.allowsInteraction)
            assertEquals(1, repository.readCalls)
            assertEquals(stored, repository.selection.value)
            assertEquals(0, repository.calls)
        }

    @Test fun `an outdated stored selection stays distinct and never upgrades itself or overwrites storage`() =
        runTest {
            val outdated = PracticeSelection("b", "c", 1, 3, "u")
            repository.selection.value = outdated
            val state = SavedStateHandle()
            val model = model(state)
            observe(model)
            assertEquals(PreparationReadState.OUTDATED, model.uiState.value.preparationRead)
            assertNull(model.uiState.value.goal)
            assertNull(model.uiState.value.course)
            assertNull(model.uiState.value.taskId)
            assertFalse(model.uiState.value.canContinue)
            assertEquals(true, state.get<Boolean>("initialPreparationOutdated"))
            val restored = model(state)
            observe(restored)
            assertEquals(PreparationReadState.OUTDATED, restored.uiState.value.preparationRead)
            assertEquals(2, repository.readCalls)
            restored.selectGoal("a")
            runCurrent()
            assertEquals(PreparationReadState.READY, restored.uiState.value.preparationRead)
            assertEquals(false, state.get<Boolean>("initialPreparationOutdated"))
            assertEquals(
                "a",
                restored.uiState.value.goal
                    ?.id,
            )
            assertNull(restored.uiState.value.course)
            assertNull(restored.uiState.value.taskId)
            assertEquals(outdated, repository.selection.value)
            assertEquals(0, repository.calls)
        }

    @Test fun `retry and edits use current P and UX before combined state catches up`() =
        runTest {
            repository.read = { flow { error("read unavailable") } }
            val model = model()
            observe(model)
            assertEquals(PreparationReadState.FAILED, model.uiState.value.preparationRead)
            vehicle.state.value = vehicle.state.value.copy(gear = VehicleReading.Available(GearPosition.DRIVE))
            model.retryPreparationRead()
            runCurrent()
            assertEquals(1, repository.readCalls)
            vehicle.state.value = vehicle.state.value.copy(gear = VehicleReading.Available(GearPosition.PARK))
            restrictions.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNKNOWN)
            model.retryPreparationRead()
            runCurrent()
            assertEquals(1, repository.readCalls)
            restrictions.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
            val gate = CompletableDeferred<Unit>()
            val stored = PracticeSelection("b", "c", 2, 3, "u")
            repository.read = {
                flow {
                    gate.await()
                    emit(stored)
                }
            }
            model.retryPreparationRead()
            runCurrent()
            assertEquals(2, repository.readCalls)
            vehicle.state.value = vehicle.state.value.copy(gear = VehicleReading.Available(GearPosition.DRIVE))
            gate.complete(Unit)
            runCurrent()
            assertEquals(PreparationReadState.READY, model.uiState.value.preparationRead)
            assertFalse(model.uiState.value.allowsInteraction)
            model.selectGoal("a")
            model.next()
            runCurrent()
            assertEquals(PreparationStep.GOAL, model.uiState.value.step)
            assertEquals(
                "b",
                model.uiState.value.goal
                    ?.id,
            )
            assertEquals("u", model.uiState.value.taskId)
            vehicle.state.value = vehicle.state.value.copy(gear = VehicleReading.Available(GearPosition.PARK))
            runCurrent()
            assertTrue(model.uiState.value.allowsInteraction)
            assertTrue(model.uiState.value.canContinue)
            assertEquals(0, repository.calls)
        }

    @Test fun `draft restores only matching catalog versions`() =
        runTest {
            val state = SavedStateHandle()
            val first = model(state)
            observe(first)
            prepare(first)
            val restored = model(state)
            observe(restored)
            assertEquals(2, repository.readCalls)
            assertEquals(PreparationStep.BRIEFING, restored.uiState.value.step)
            assertEquals("t", restored.uiState.value.taskId)
            state["briefingVersion"] = 1
            val outdated = model(state)
            observe(outdated)
            assertEquals(3, repository.readCalls)
            assertEquals(PreparationStep.COURSE, outdated.uiState.value.step)
            assertNull(outdated.uiState.value.course)
        }

    @Test fun `system back revokes a queued save before transition collectors stop`() =
        runTest {
            val old = PracticeSelection("b", "c", 2, 3, "u")
            repository.selection.value = old
            val gate = CompletableDeferred<Unit>()
            repository.beforeWrite = { withContext(NonCancellable) { gate.await() } }
            val model = model()
            observe(model)
            val events = mutableListOf<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.saved.collect { events += it } }
            prepare(model)
            model.next()
            runCurrent()
            assertTrue(model.uiState.value.saving)
            assertFalse(model.back())
            // Keep the UI collector alive, as NavHost does during its exit animation.
            gate.complete(Unit)
            runCurrent()
            assertEquals(old, repository.selection.value)
            assertTrue(events.isEmpty())
            assertFalse(model.uiState.value.allowsInteraction)
        }

    @Test fun `leaving cannot reopen permissions when an old screen resubscribes`() =
        runTest {
            val model = model()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
            runCurrent()
            model.leave()
            collector.cancel()
            runCurrent()
            observe(model)
            model.selectGoal("a")
            runCurrent()
            assertNull(model.uiState.value.goal)
            assertFalse(model.uiState.value.allowsInteraction)
        }

    @Test fun plannedTargetSurvivesSameContextTaskSaveAndAChangedStoredPreparationRejectsTheOldDraft() =
        runTest {
            val plan = plan()
            val original = PracticeSelection("a", "c", 2, 3, "t", plan)
            repository.selection.value = original
            val model = model()
            observe(model)
            assertEquals(plan, model.uiState.value.plannedRoad)
            model.next()
            model.next()
            model.selectTask("u")
            model.next()
            runCurrent()
            assertEquals(original.copy(taskId = "u"), repository.selection.value)
            val stale = this@CourseViewModelTest.model()
            observe(stale)
            stale.next()
            stale.next()
            stale.selectTask("t")
            val replacement = original.copy(goalId = "b", plannedRoad = null)
            repository.selection.value = replacement
            stale.next()
            runCurrent()
            assertEquals(replacement, repository.selection.value)
            assertTrue(stale.uiState.value.saveFailed)
        }

    @Test fun restoredDraftOnlyRecoversItsTargetFromTheSameFullStoredBinding() =
        runTest {
            val original = PracticeSelection("a", "c", 2, 3, "t", plan())
            repository.selection.value = original
            val state = SavedStateHandle()
            val first = model(state)
            observe(first)
            first.next()
            first.next()
            first.selectTask("u")
            val snapshot = SavedStateHandle(state.keys().associateWith { state.get<Any?>(it) })
            val restored = model(snapshot)
            observe(restored)
            assertEquals(original.plannedRoad, restored.uiState.value.plannedRoad)
            assertEquals("u", restored.uiState.value.taskId)
            val changed = original.copy(plannedRoad = PlannedPracticeRoad("previous", "v2", checkNotNull(original.plannedRoad).road))
            repository.selection.value = changed
            val different = model(SavedStateHandle(state.keys().associateWith { state.get<Any?>(it) }))
            observe(different)
            assertEquals(PreparationReadState.FAILED, different.uiState.value.preparationRead)
            assertFalse(different.uiState.value.canContinue)
            different.retryPreparationRead()
            runCurrent()
            assertEquals(changed.plannedRoad, different.uiState.value.plannedRoad)
            assertEquals("t", different.uiState.value.taskId)
            assertEquals(0, repository.calls)
            state.keys().forEach { assertFalse(state.get<Any?>(it) is PlannedPracticeRoad) }
        }

    @Test fun changingGoalPreviewsTargetRemovalAndReturningToTheOriginalContextRestoresItWithoutWriting() =
        runTest {
            val original = PracticeSelection("a", "c", 2, 3, "t", plan())
            repository.selection.value = original
            val model = model()
            observe(model)
            model.selectGoal("b")
            runCurrent()
            assertNull(model.uiState.value.plannedRoad)
            assertTrue(model.uiState.value.plannedRoadCleared)
            model.selectGoal("a")
            model.next()
            model.selectCourse("c")
            runCurrent()
            assertEquals(original.plannedRoad, model.uiState.value.plannedRoad)
            assertFalse(model.uiState.value.plannedRoadCleared)
            model.leave()
            runCurrent()
            assertEquals(original, repository.selection.value)
            assertEquals(0, repository.calls)
        }

    @Test fun stalePreparationCanBeExplicitlyReloadedAfterARejectedSaveWithoutReusingTheFailedDraftOrOldAction() =
        runTest {
            catalog.courses = catalog.courses + catalog.courses.single().copy(id = "other")
            val original = PracticeSelection("a", "c", 2, 3, "t", plan())
            repository.selection.value = original
            val state = SavedStateHandle()
            val model = model(state)
            observe(model)
            model.next()
            model.next()
            model.selectTask("u")
            runCurrent()
            val displayed = model.uiState.value
            val replacement =
                original.copy(
                    goalId = "b",
                    taskId = "t",
                    plannedRoad = PlannedPracticeRoad("different-source", "new-version", plan().road),
                )
            repository.selection.value = replacement
            model.next(displayed.actionGeneration)
            runCurrent()
            assertTrue(model.uiState.value.saveFailed)
            assertEquals("u", model.uiState.value.taskId)
            assertEquals(original.plannedRoad, model.uiState.value.plannedRoad)
            val writesAfterFailure = repository.calls
            model.next(displayed.actionGeneration)
            runCurrent()
            assertEquals(writesAfterFailure, repository.calls)
            model.next(model.uiState.value.actionGeneration)
            runCurrent()
            assertTrue(model.uiState.value.saveFailed)
            assertEquals(replacement, repository.selection.value)
            model.retryPreparationRead()
            runCurrent()
            assertFalse(model.uiState.value.saveFailed)
            assertEquals(PreparationStep.GOAL, model.uiState.value.step)
            assertEquals(
                "b",
                model.uiState.value.goal
                    ?.id,
            )
            assertEquals("t", model.uiState.value.taskId)
            assertEquals(replacement.plannedRoad, model.uiState.value.plannedRoad)
            val fresh = model.uiState.value
            model.next(displayed.actionGeneration)
            model.selectGoal("a", displayed.actionGeneration)
            assertTrue("A stale Back is consumed without closing fresh preparation", model.back(displayed.actionGeneration))
            runCurrent()
            assertEquals(fresh, model.uiState.value)
            assertEquals(replacement, repository.selection.value)
            assertEquals(writesAfterFailure + 1, repository.calls)
            model.next(fresh.actionGeneration)
            runCurrent()
            val courseStep = model.uiState.value
            assertEquals(PreparationStep.COURSE, courseStep.step)
            model.selectCourse("other", displayed.actionGeneration)
            assertTrue(model.back(displayed.actionGeneration))
            runCurrent()
            assertEquals(courseStep, model.uiState.value)
            model.next(courseStep.actionGeneration)
            runCurrent()
            val briefing = model.uiState.value
            assertEquals(PreparationStep.BRIEFING, briefing.step)
            model.selectTask("u", displayed.actionGeneration)
            assertTrue(model.back(displayed.actionGeneration))
            runCurrent()
            assertEquals(briefing, model.uiState.value)
            model.selectTask("u", briefing.actionGeneration)
            runCurrent()
            assertEquals("u", model.uiState.value.taskId)
            assertEquals(replacement, repository.selection.value)
            assertEquals(replacement.savedFields(), state.get<Any?>("preparationBaselineFields"))
        }

    private fun plan() =
        PlannedPracticeRoad(
            "previous",
            "v1",
            CourseRoadSnapshot(
                "road",
                "목표 도로",
                "residential",
                99,
                1,
                1000,
                listOf(CourseRoadSnapshot.Node(1, 1, 37.0, 127.0), CourseRoadSnapshot.Node(2, 1, 37.001, 127.001)),
            ),
        )
}
