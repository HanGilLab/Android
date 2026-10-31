package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.NavigationGateway
import io.github.hangillab.hangil.core.domain.NavigationScreenResult
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NavigationScreenViewModelTest {
    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun teardown() {
        Dispatchers.resetMain()
    }

    @Test fun observationAndHostStartNeverAutomaticallyOpenNavigation() =
        runTest {
            val f = Fixture()
            val host = f.model.beginHost { true }
            runCurrent()
            assertEquals(NavigationScreenUiState.Phase.IDLE, f.model.state.value.phase)
            assertEquals(0, f.navigation.calls)
            f.model.endHost(host)
            runCurrent()
        }

    @Test fun explicitClickReportsOnlySdkRequestAndDropsDuplicateClick() =
        runTest {
            val f = Fixture()
            val host = f.model.beginHost { true }
            runCurrent()
            val displayed = f.model.state.value
            assertTrue(f.model.open(displayed))
            assertFalse(f.model.open(displayed))
            runCurrent()
            assertEquals(1, f.navigation.calls)
            assertTrue(f.navigation.guard())
            f.navigation.finish.complete(NavigationScreenResult.REQUESTED)
            runCurrent()
            assertEquals(NavigationScreenResult.REQUESTED, f.model.state.value.result)
            assertFalse(f.model.open(displayed))
            f.model.endHost(host)
            runCurrent()
        }

    @Test fun liveDenialWhileConnectingDiscardsIntentAcrossPReturn() =
        runTest {
            val f = Fixture()
            val host = f.model.beginHost { true }
            runCurrent()
            assertTrue(f.model.open(f.model.state.value))
            runCurrent()
            f.vehicleState.value = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))
            runCurrent()
            assertFalse(f.navigation.guard())
            assertEquals(NavigationScreenUiState.Phase.LOCKED, f.model.state.value.phase)
            f.vehicleState.value = parked()
            runCurrent()
            assertEquals(NavigationScreenUiState.Phase.IDLE, f.model.state.value.phase)
            assertEquals(1, f.navigation.calls)
            f.model.endHost(host)
            runCurrent()
        }

    @Test fun stoppedHostCannotPublishLateResultOrLendNewHostItsRequest() =
        runTest {
            val f = Fixture()
            f.navigation.ignoreCancellation = true
            var alive = true
            val host = f.model.beginHost { alive }
            runCurrent()
            val old = f.model.state.value
            assertTrue(f.model.open(old))
            runCurrent()
            alive = false
            f.model.endHost(host)
            val fresh = f.model.beginHost { true }
            runCurrent()
            assertFalse(f.navigation.guard())
            assertFalse(f.model.open(old))
            f.navigation.finish.complete(NavigationScreenResult.REQUESTED)
            runCurrent()
            assertEquals(NavigationScreenUiState.Phase.IDLE, f.model.state.value.phase)
            assertEquals(fresh, f.model.state.value.host)
            f.model.endHost(fresh)
            runCurrent()
        }

    @Test fun sourceOfOldHostDisposalCannotCloseCurrentHost() =
        runTest {
            val f = Fixture()
            val old = f.model.beginHost { true }
            val fresh = f.model.beginHost { true }
            runCurrent()
            val current = f.model.state.value
            f.model.endHost(old)
            assertTrue(f.model.open(current))
            runCurrent()
            assertTrue(f.navigation.guard())
            f.model.endHost(fresh)
            runCurrent()
        }

    @Test fun missingVehicleOrUxNeverInitializesNavigation() =
        runTest {
            val cases =
                listOf(
                    parked().copy(connection = ConnectionState.DISCONNECTED) to unrestricted(),
                    parked().copy(gear = VehicleReading.Unknown) to unrestricted(),
                    parked().copy(gear = VehicleReading.PermissionDenied) to unrestricted(),
                    parked() to DrivingRestrictions(ParkingState.UNKNOWN, UxRestrictionState.UNRESTRICTED),
                    parked() to DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNKNOWN),
                    parked() to DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.RESTRICTED),
                )
            for ((vehicle, restrictions) in cases) {
                val f = Fixture()
                f.vehicleState.value = vehicle
                f.restrictionState.value = restrictions
                val host = f.model.beginHost { true }
                runCurrent()
                assertFalse(f.model.open(f.model.state.value))
                assertEquals(0, f.navigation.calls)
                f.model.endHost(host)
                runCurrent()
            }
        }

    @Test fun expiredOrThrowingHostFailsClosedBeforeCall() =
        runTest {
            val f = Fixture()
            var alive = true
            val host = f.model.beginHost { alive }
            runCurrent()
            val old = f.model.state.value
            alive = false
            assertFalse(f.model.open(old))
            assertEquals(0, f.navigation.calls)
            f.model.endHost(host)
            val throwing = f.model.beginHost { error("expired") }
            runCurrent()
            assertFalse(f.model.open(f.model.state.value))
            assertEquals(0, f.navigation.calls)
            f.model.endHost(throwing)
        }

    @Test fun allUnconfirmedResultsStayDistinctAndRequireFreshExplicitAction() =
        runTest {
            for (result in NavigationScreenResult.entries) {
                val f = Fixture()
                val host = f.model.beginHost { true }
                runCurrent()
                val old = f.model.state.value
                assertTrue(f.model.open(old))
                runCurrent()
                f.navigation.finish.complete(result)
                runCurrent()
                assertEquals(result, f.model.state.value.result)
                assertFalse(f.model.open(old))
                assertEquals(1, f.navigation.calls)
                f.model.endHost(host)
                runCurrent()
            }
        }

    @Test fun independentCancellationBecomesAnErrorRatherThanInfiniteOpening() =
        runTest {
            val f = Fixture()
            f.navigation.failure = CancellationException("independent")
            val host = f.model.beginHost { true }
            runCurrent()
            assertTrue(f.model.open(f.model.state.value))
            runCurrent()
            assertEquals(NavigationScreenResult.ERROR, f.model.state.value.result)
            f.model.endHost(host)
            runCurrent()
        }

    @Test fun silentDRejectionCannotBorrowSilentPOrReuseOldButton() =
        runTest {
            val f = Fixture()
            val host = f.model.beginHost { true }
            runCurrent()
            val old = f.model.state.value
            f.vehicleState.silent(parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE)))
            assertFalse(f.model.open(old))
            f.vehicleState.silent(parked())
            assertFalse(f.model.open(old))
            assertEquals(0, f.navigation.calls)
            f.vehicleState.value = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))
            runCurrent()
            f.vehicleState.value = parked()
            runCurrent()
            assertTrue(f.model.open(f.model.state.value))
            runCurrent()
            assertEquals(1, f.navigation.calls)
            f.model.endHost(host)
            runCurrent()
        }

    @OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
    private class SilentState<T>(
        initial: T,
        private val emitted: MutableStateFlow<T> = MutableStateFlow(initial),
    ) : kotlinx.coroutines.flow.StateFlow<T> by emitted {
        private var live = initial
        override var value: T
            get() = live
            set(value) {
                live = value
                emitted.value = value
            }

        fun silent(value: T) {
            live = value
        }
    }

    private class Fixture {
        val vehicleState = SilentState(parked())
        val restrictionState = MutableStateFlow(unrestricted())
        val vehicle =
            object : VehicleGateway {
                override val state = vehicleState
            }
        val restrictions =
            object : DrivingRestrictionsProvider {
                override val restrictions = restrictionState
            }
        val navigation = Gateway()
        val model = NavigationScreenViewModel(vehicle, restrictions, navigation)
    }

    private class Gateway : NavigationGateway {
        override val connection = MutableStateFlow(ConnectionState.DISCONNECTED)
        var calls = 0
        var ignoreCancellation = false
        var failure: Exception? = null
        val finish = CompletableDeferred<NavigationScreenResult>()
        lateinit var guard: () -> Boolean

        override suspend fun requestForeground(canDispatch: () -> Boolean): NavigationScreenResult {
            calls++
            guard = canDispatch
            failure?.let { throw it }
            return if (ignoreCancellation) withContext(NonCancellable) { finish.await() } else finish.await()
        }
    }

    companion object {
        private fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        private fun unrestricted() = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
    }
}
