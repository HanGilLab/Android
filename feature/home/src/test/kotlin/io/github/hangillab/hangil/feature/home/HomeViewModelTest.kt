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
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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
        }
    private val catalog =
        object : PracticeCatalog {
            override val goals = emptyList<PracticeGoal>()
            override val courses = emptyList<PracticeCourse>()
        }
    private var fail = false
    private val repository =
        object : PracticeRepository {
            override fun observeSelection(): Flow<PracticeSelection?> =
                flow {
                    if (fail) error("read failed")
                    emit(null)
                }

            override suspend fun saveSelection(
                selection: PracticeSelection,
                canCommit: () -> Boolean,
            ): Boolean = error("not used")
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
}
