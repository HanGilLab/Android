package io.github.hangillab.hangil.feature.course

import androidx.lifecycle.SavedStateHandle
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.ParkingState
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
            override val courses =
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

    @Test fun `draft restores only matching catalog versions`() =
        runTest {
            val state = SavedStateHandle()
            val first = model(state)
            observe(first)
            prepare(first)
            val restored = model(state)
            observe(restored)
            assertEquals(PreparationStep.BRIEFING, restored.uiState.value.step)
            assertEquals("t", restored.uiState.value.taskId)
            state["briefingVersion"] = 1
            val outdated = model(state)
            observe(outdated)
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
}
