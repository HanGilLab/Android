package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.NavigationGateway
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeCourse
import io.github.hangillab.hangil.core.domain.PracticeGoal
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeTask
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val vehicle =
        object : VehicleGateway {
            override val state = MutableStateFlow(VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK)))
        }
    private val restrictions =
        object : DrivingRestrictionsProvider {
            override val restrictions = MutableStateFlow(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))
        }
    private val navigation =
        object : NavigationGateway {
            override val connection = MutableStateFlow(ConnectionState.UNAVAILABLE)

            override suspend fun requestForeground(
                canDispatch: () -> Boolean,
            ): io.github.hangillab.hangil.core.domain.NavigationScreenResult = error("Home state tests must not request navigation")
        }
    private val goal = PracticeGoal("goal", "연습 목표", "목표 설명")
    private val task = PracticeTask("task", "오늘의 과제", "과제 설명")
    private val course = PracticeCourse("course", 1, 2, "시연 코스", "코스 설명", listOf("출발"), listOf(task), setOf(goal.id))
    private val practice = PreparedPractice(goal, course, task)
    private val selection = PracticeSelection(goal.id, course.id, course.version, course.briefingVersion, task.id)
    private var currentCourses = listOf(course)
    private val catalog =
        object : PracticeCatalog {
            override val goals = listOf(goal)
            override val courses get() = currentCourses
        }
    private var fail = false
    private var reads = 0
    private var writes = 0
    private var selectionSource: Flow<PracticeSelection?> = MutableStateFlow(null)
    private val repository =
        object : PracticeRepository {
            override fun observeSelection(): Flow<PracticeSelection?> =
                flow {
                    reads++
                    if (fail) error("read failed")
                    emitAll(selectionSource)
                }

            override suspend fun saveSelection(
                selection: PracticeSelection,
                canCommit: () -> Boolean,
            ): Boolean {
                writes++
                error("not used")
            }
        }

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `read failure blocks editing until retry succeeds`() =
        runTest {
            fail = true
            val model = HomeViewModel(vehicle, navigation, restrictions, repository, catalog)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
            runCurrent()
            assertEquals(PreparationLoadState.Failed, model.uiState.value.preparation)
            assertFalse(model.canPrepare())
            assertTrue(model.canViewMap())
            assertTrue(model.canViewHistory())
            fail = false
            model.retryLoading()
            runCurrent()
            assertEquals(PreparationLoadState.Loaded(Preparation.Empty), model.uiState.value.preparation)
            assertTrue(model.canPrepare())
        }

    @Test fun `live driving update blocks navigation before UI recombines`() =
        runTest {
            val model = HomeViewModel(vehicle, navigation, restrictions, repository, catalog)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
            runCurrent()
            assertTrue(model.canPrepare())
            assertTrue(model.canViewMap())
            assertTrue(model.canViewHistory())
            vehicle.state.value = VehicleState()
            assertFalse(model.canPrepare())
            assertFalse(model.canViewMap())
            assertFalse(model.canViewHistory())
        }

    @Test fun `preparation expansion checks live vehicle and UX before the UI recombines without extra IO`() =
        runTest {
            selectionSource = MutableStateFlow(selection)
            val model = HomeViewModel(vehicle, navigation, restrictions, repository, catalog)
            observe(model)
            repeat(10) { assertTrue(model.canExpandPreparation(practice)) }
            vehicle.state.value = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.DRIVE))
            assertTrue(model.uiState.value.allowsDetailedInteraction)
            assertFalse(model.canExpandPreparation(practice))
            vehicle.state.value = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))
            restrictions.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.RESTRICTED)
            assertFalse(model.canExpandPreparation(practice))
            restrictions.restrictions.value = DrivingRestrictions()
            assertFalse(model.canExpandPreparation(practice))
            restrictions.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
            vehicle.state.value = VehicleState()
            assertFalse(model.canExpandPreparation(practice))
            assertEquals(1, reads)
            assertEquals(0, writes)
        }

    @Test fun `received preparation rejects an old full snapshot before downstream UI publication`() =
        runTest {
            val advance = CompletableDeferred<Unit>()
            val checked = CompletableDeferred<Unit>()
            lateinit var model: HomeViewModel
            val replacement = practice.copy(course = course.copy(summary = "수정된 코스 설명"))
            selectionSource =
                flow {
                    emit(selection)
                    advance.await()
                    currentCourses = listOf(replacement.course)
                    backgroundScope.launch {
                        assertEquals(
                            Preparation.Ready(practice),
                            (model.uiState.value.preparation as PreparationLoadState.Loaded).preparation,
                        )
                        assertFalse(model.canExpandPreparation(practice))
                        assertTrue(model.canExpandPreparation(replacement))
                        checked.complete(Unit)
                    }
                    emit(selection)
                    awaitCancellation()
                }
            model = HomeViewModel(vehicle, navigation, restrictions, repository, catalog)
            observe(model)
            assertTrue(model.canExpandPreparation(practice))
            assertFalse(model.canExpandPreparation(practice.copy(task = task.copy(description = "다른 과제 설명"))))
            advance.complete(Unit)
            runCurrent()
            assertTrue(checked.isCompleted)
            assertEquals(Preparation.Ready(replacement), (model.uiState.value.preparation as PreparationLoadState.Loaded).preparation)
            assertEquals(1, reads)
            assertEquals(0, writes)
        }

    @Test fun `a preparation read failure invalidates the old disclosure before UI failure delivery`() =
        runTest {
            val failRead = CompletableDeferred<Unit>()
            val checked = CompletableDeferred<Unit>()
            lateinit var model: HomeViewModel
            selectionSource =
                flow {
                    emit(selection)
                    failRead.await()
                    backgroundScope.launch {
                        assertTrue(model.uiState.value.preparation is PreparationLoadState.Loaded)
                        assertFalse(model.canExpandPreparation(practice))
                        checked.complete(Unit)
                    }
                    error("fixture read failure")
                }
            model = HomeViewModel(vehicle, navigation, restrictions, repository, catalog)
            observe(model)
            assertTrue(model.canExpandPreparation(practice))
            failRead.complete(Unit)
            runCurrent()
            assertTrue(checked.isCompleted)
            assertEquals(PreparationLoadState.Failed, model.uiState.value.preparation)
            assertFalse(model.canExpandPreparation(practice))
            assertEquals(1, reads)
            assertEquals(0, writes)
        }

    @Test fun `a normally completed preparation stream publishes failure instead of retaining ready`() =
        runTest {
            selectionSource = flowOf(selection)
            val model = HomeViewModel(vehicle, navigation, restrictions, repository, catalog)
            observe(model)
            assertEquals(PreparationLoadState.Failed, model.uiState.value.preparation)
            assertFalse(model.canExpandPreparation(practice))
            assertEquals(1, reads)
            assertEquals(0, writes)
        }

    @Test fun `stopping the visible reader revokes preparation expansion and a fresh subscription restores it`() =
        runTest {
            selectionSource = MutableStateFlow(selection)
            val model = HomeViewModel(vehicle, navigation, restrictions, repository, catalog)
            assertFalse(model.canExpandPreparation(practice))
            val observation = observe(model)
            assertTrue(model.canExpandPreparation(practice))
            observation.cancel()
            runCurrent()
            assertFalse(model.canExpandPreparation(practice))
            observe(model)
            assertTrue(model.canExpandPreparation(practice))
            assertEquals(2, reads)
            assertEquals(0, writes)
        }

    @Test fun normalPreparationCompletionPublishesFailureAfterVisibleReady() =
        runTest {
            val complete = CompletableDeferred<Unit>()
            selectionSource =
                flow {
                    emit(selection)
                    complete.await()
                }
            val model = HomeViewModel(vehicle, navigation, restrictions, repository, catalog)
            observe(model)
            assertEquals(
                Preparation.Ready(practice),
                (model.uiState.value.preparation as PreparationLoadState.Loaded).preparation,
            )
            assertTrue(model.canExpandPreparation(practice))
            complete.complete(Unit)
            runCurrent()
            assertEquals(PreparationLoadState.Failed, model.uiState.value.preparation)
            assertFalse(model.canExpandPreparation(practice))
            assertEquals(1, reads)
            assertEquals(0, writes)
        }

    @Test fun `retry cancellation restarts preparation without publishing a false read failure`() =
        runTest {
            selectionSource = MutableStateFlow(selection)
            val model = HomeViewModel(vehicle, navigation, restrictions, repository, catalog)
            val seen = mutableListOf<PreparationLoadState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.uiState.collect { seen += it.preparation }
            }
            runCurrent()
            assertTrue(model.canExpandPreparation(practice))
            model.retryLoading()
            runCurrent()
            assertEquals(PreparationLoadState.Loaded(Preparation.Ready(practice)), model.uiState.value.preparation)
            assertTrue(model.canExpandPreparation(practice))
            assertTrue(seen.none { it == PreparationLoadState.Failed })
            assertEquals(2, reads)
            assertEquals(0, writes)
        }

    @Test fun `diagnostic disclosure live read gate rejects stale parked UI without extra IO`() =
        runTest {
            val parked = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))
            val unrestricted = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
            val model = HomeViewModel(vehicle, navigation, restrictions, repository, catalog)
            observe(model)
            val displayed = model.uiState.value
            assertTrue(displayed.allowsDetailedInteraction)
            assertTrue(model.canViewMap())
            vehicle.state.value = parked.copy(gear = VehicleReading.Available(GearPosition.DRIVE))
            assertFalse(model.canViewMap())
            assertEquals(displayed, model.uiState.value)
            vehicle.state.value = parked
            restrictions.restrictions.value = unrestricted.copy(uxRestrictionState = UxRestrictionState.RESTRICTED)
            assertFalse(model.canViewMap())
            assertEquals(displayed, model.uiState.value)
            restrictions.restrictions.value = unrestricted.copy(uxRestrictionState = UxRestrictionState.UNKNOWN)
            assertFalse(model.canViewMap())
            assertEquals(displayed, model.uiState.value)
            restrictions.restrictions.value = unrestricted
            vehicle.state.value = parked.copy(gear = VehicleReading.Unknown)
            assertFalse(model.canViewMap())
            assertEquals(displayed, model.uiState.value)
            vehicle.state.value = parked.copy(connection = ConnectionState.DISCONNECTED)
            assertFalse(model.canViewMap())
            assertEquals(displayed, model.uiState.value)
            vehicle.state.value = parked
            repeat(10) { assertTrue(model.canViewMap()) }
            assertEquals(displayed, model.uiState.value)
            assertEquals(1, reads)
            assertEquals(0, writes)
        }

    private fun TestScope.observe(model: HomeViewModel): Job {
        val observation = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
        runCurrent()
        return observation
    }
}
