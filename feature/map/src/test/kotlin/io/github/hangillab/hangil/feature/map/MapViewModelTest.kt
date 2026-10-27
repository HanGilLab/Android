package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {
    private val vehicle =
        object : VehicleGateway {
            override val state = MutableStateFlow(VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK)))
        }
    private val restrictions =
        object : DrivingRestrictionsProvider {
            override val restrictions = MutableStateFlow(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))
        }

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `unknown or restricted UX never exposes a parked map`() =
        runTest {
            val model = MapViewModel(vehicle, restrictions)
            subscribe(model)
            restrictions.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNKNOWN)
            runCurrent()
            assertFalse(model.uiState.value.allowsDetailedInteraction)
            assertNull(model.onMapLoading(0))
            restrictions.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.RESTRICTED)
            runCurrent()
            assertFalse(model.uiState.value.allowsDetailedInteraction)
            restrictions.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
            runCurrent()
            assertTrue(model.uiState.value.allowsDetailedInteraction)
        }

    @Test fun `new D state blocks retry and renderer callbacks before UI recombines`() =
        runTest {
            val model = MapViewModel(vehicle, restrictions)
            subscribe(model)
            val token = model.onMapLoading(0)!!
            vehicle.state.value = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.DRIVE))
            model.retry()
            model.onMapRendered(0, token)
            assertNull(model.onMapLoading(0))
            runCurrent()
            assertFalse(model.uiState.value.allowsDetailedInteraction)
            assertEquals(0, model.uiState.value.attempt)
            assertEquals(MapLoadState.LOADING, model.uiState.value.load)
        }

    @Test fun `disconnection and permission denial remove detailed access despite cached P restrictions`() =
        runTest {
            val model = MapViewModel(vehicle, restrictions)
            subscribe(model)
            vehicle.state.value = VehicleState(ConnectionState.DISCONNECTED, VehicleReading.Available(GearPosition.PARK))
            runCurrent()
            assertFalse(model.uiState.value.allowsDetailedInteraction)
            vehicle.state.value = VehicleState(ConnectionState.CONNECTED, VehicleReading.PermissionDenied)
            runCurrent()
            assertFalse(model.uiState.value.allowsDetailedInteraction)
        }

    @Test fun `failed rendering stays failed until retry and ignores prior attempt callbacks`() =
        runTest {
            val model = MapViewModel(vehicle, restrictions)
            subscribe(model)
            val token = model.onMapLoading(0)!!
            model.onMapFailed(0, token)
            model.onMapRendered(0, token)
            runCurrent()
            assertEquals(MapLoadState.FAILED, model.uiState.value.load)
            model.retry()
            model.onMapRendered(0, token)
            model.onMapFailed(0, token)
            runCurrent()
            assertEquals(1, model.uiState.value.attempt)
            assertEquals(MapLoadState.LOADING, model.uiState.value.load)
            val retried = model.onMapLoading(1)!!
            model.onMapRendered(1, retried)
            runCurrent()
            assertEquals(MapLoadState.READY, model.uiState.value.load)
        }

    @Test fun `recreated map ignores callbacks from the previous native view`() =
        runTest {
            val model = MapViewModel(vehicle, restrictions)
            subscribe(model)
            val previous = model.onMapLoading(0)!!
            val current = model.onMapLoading(0)!!
            assertNotEquals(previous, current)
            model.onMapRendered(0, previous)
            runCurrent()
            assertEquals(MapLoadState.LOADING, model.uiState.value.load)
            model.onMapRendered(0, current)
            model.onMapFailed(0, previous)
            runCurrent()
            assertEquals(MapLoadState.READY, model.uiState.value.load)
        }

    private fun TestScope.subscribe(model: MapViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
        runCurrent()
    }
}
