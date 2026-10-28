package io.github.hangillab.hangil.feature.map

import androidx.lifecycle.ViewModelStore
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.LastKnownLocation
import io.github.hangillab.hangil.core.domain.LocationGateway
import io.github.hangillab.hangil.core.domain.LocationReading
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class LocationProbeViewModelTest {
    private val vehicle =
        object : VehicleGateway {
            override val state = MutableStateFlow(parked())
        }
    private val restrictions =
        object : DrivingRestrictionsProvider {
            override val restrictions = MutableStateFlow(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))
        }
    private var calls = 0
    private var answer: suspend () -> LocationReading = { LocationReading.Empty }
    private val gateway =
        object : LocationGateway {
            override suspend fun readLastKnown(): LocationReading {
                ++calls
                return answer()
            }
        }
    private val store = ViewModelStore()

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun `unknown vehicle UX and live D block reads without awaiting UI state`() =
        runTest {
            val model = model()
            vehicle.state.value = VehicleState()
            model.readLastKnown()
            vehicle.state.value = parked()
            restrictions.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNKNOWN)
            model.readLastKnown()
            restrictions.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.RESTRICTED)
            model.readLastKnown()
            restrictions.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
            vehicle.state.value = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.DRIVE))
            model.readLastKnown()
            runCurrent()
            assertEquals(0, calls)
            assertEquals(LocationProbeState.Idle, model.state.value)
            assertNull(model.beginPermissionRequest())
        }

    @Test fun `duplicate clicks issue only one active read`() =
        runTest {
            var callback: Continuation<LocationReading>? = null
            answer = { suspendCoroutine { callback = it } }
            val model = model()
            model.readLastKnown()
            model.readLastKnown()
            assertEquals(LocationProbeState.Loading, model.state.value)
            runCurrent()
            model.readLastKnown()
            runCurrent()
            assertEquals(1, calls)
            callback!!.resume(LocationReading.Empty)
            runCurrent()
            assertEquals(LocationProbeState.Finished(LocationReading.Empty), model.state.value)
        }

    @Test fun `denied read never requests permission automatically and denied result never rereads`() =
        runTest {
            answer = { LocationReading.PermissionDenied }
            val model = model()
            assertNull(model.beginPermissionRequest())
            model.readLastKnown()
            runCurrent()
            assertEquals(LocationProbeState.Finished(LocationReading.PermissionDenied), model.state.value)
            assertEquals(1, calls)
            val token = model.beginPermissionRequest()
            assertNotNull(token)
            assertEquals(LocationProbeState.AwaitingPermission, model.state.value)
            assertNull(model.beginPermissionRequest())
            model.readLastKnown()
            model.onPermissionResult(token!!, LocationPermissionResult.DENIED)
            runCurrent()
            assertEquals(LocationProbeState.Finished(LocationReading.PermissionDenied), model.state.value)
            assertEquals(1, calls)
            model.onPermissionResult(token, LocationPermissionResult.GRANTED)
            runCurrent()
            assertEquals(1, calls)
        }

    @Test fun `permission launch failure remains an error rather than a denial`() =
        runTest {
            answer = { LocationReading.PermissionDenied }
            val model = model()
            model.readLastKnown()
            runCurrent()
            model.onPermissionResult(model.beginPermissionRequest()!!, LocationPermissionResult.FAILED)
            runCurrent()
            assertEquals(LocationProbeState.Finished(LocationReading.Error), model.state.value)
            assertEquals(1, calls)
            assertNull(model.beginPermissionRequest())
        }

    @Test fun `granted permission rereads once while parked and rejects duplicate callbacks`() =
        runTest {
            answer = { LocationReading.PermissionDenied }
            val model = model()
            model.readLastKnown()
            runCurrent()
            val token = model.beginPermissionRequest()!!
            answer = { LocationReading.Empty }
            model.onPermissionResult(token, LocationPermissionResult.GRANTED)
            model.onPermissionResult(token, LocationPermissionResult.GRANTED)
            runCurrent()
            assertEquals(2, calls)
            assertEquals(LocationProbeState.Finished(LocationReading.Empty), model.state.value)
        }

    @Test fun `permission result after live D cannot start a read even before the gate collector runs`() =
        runTest {
            answer = { LocationReading.PermissionDenied }
            val model = model()
            model.readLastKnown()
            runCurrent()
            val token = model.beginPermissionRequest()!!
            vehicle.state.value = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.DRIVE))
            model.onPermissionResult(token, LocationPermissionResult.GRANTED)
            assertEquals(LocationProbeState.Idle, model.state.value)
            runCurrent()
            vehicle.state.value = parked()
            runCurrent()
            model.onPermissionResult(token, LocationPermissionResult.GRANTED)
            runCurrent()
            assertEquals(1, calls)
            assertEquals(LocationProbeState.Idle, model.state.value)
        }

    @Test fun `hidden probe invalidates a pending permission request`() =
        runTest {
            answer = { LocationReading.PermissionDenied }
            val model = model()
            model.readLastKnown()
            runCurrent()
            val token = model.beginPermissionRequest()!!
            model.onHidden()
            model.onPermissionResult(token, LocationPermissionResult.GRANTED)
            runCurrent()
            assertEquals(1, calls)
            assertEquals(LocationProbeState.Idle, model.state.value)
        }

    @Test fun `late noncooperative SDK result cannot replace a newer request`() =
        runTest {
            val callbacks = mutableListOf<Continuation<LocationReading>>()
            answer = { suspendCoroutine { callbacks += it } }
            val model = model()
            model.readLastKnown()
            runCurrent()
            model.onHidden()
            model.readLastKnown()
            runCurrent()
            assertEquals(2, calls)
            callbacks[0].resume(sample())
            runCurrent()
            assertEquals(LocationProbeState.Loading, model.state.value)
            callbacks[1].resume(LocationReading.Empty)
            runCurrent()
            assertEquals(LocationProbeState.Finished(LocationReading.Empty), model.state.value)
        }

    @Test fun `hidden probe cancels work and forgets an unmodified raw sample`() =
        runTest {
            var cancelled = false
            answer = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            val model = model()
            model.readLastKnown()
            runCurrent()
            model.onHidden()
            assertEquals(LocationProbeState.Idle, model.state.value)
            runCurrent()
            assertTrue(cancelled)
            val reading = sample()
            answer = { reading }
            model.readLastKnown()
            runCurrent()
            assertSame(reading, (model.state.value as LocationProbeState.Finished).reading)
            model.onHidden()
            assertEquals(LocationProbeState.Idle, model.state.value)
        }

    @Test fun `vehicle state loss automatically clears finished data and cancels loading`() =
        runTest {
            answer = { sample() }
            val model = model()
            model.readLastKnown()
            runCurrent()
            assertTrue(model.state.value is LocationProbeState.Finished)
            vehicle.state.value = VehicleState()
            runCurrent()
            assertEquals(LocationProbeState.Idle, model.state.value)
            vehicle.state.value = parked()
            runCurrent()
            var cancelled = false
            answer = {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            model.readLastKnown()
            runCurrent()
            restrictions.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNKNOWN)
            runCurrent()
            assertTrue(cancelled)
            assertEquals(LocationProbeState.Idle, model.state.value)
        }

    @Test fun `hiding before a queued read starts never calls the SDK`() =
        runTest {
            val model = model()
            model.readLastKnown()
            model.onHidden()
            runCurrent()
            assertEquals(0, calls)
            assertEquals(LocationProbeState.Idle, model.state.value)
        }

    @Test fun `ordinary failures become error while cancellation remains cancellation`() =
        runTest {
            answer = { error("synthetic failure") }
            val model = model()
            model.readLastKnown()
            runCurrent()
            assertEquals(LocationProbeState.Finished(LocationReading.Error), model.state.value)
            answer = { throw CancellationException("synthetic cancellation") }
            model.readLastKnown()
            runCurrent()
            assertEquals(LocationProbeState.Idle, model.state.value)
            answer = { LocationReading.Empty }
            model.readLastKnown()
            runCurrent()
            assertEquals(LocationProbeState.Finished(LocationReading.Empty), model.state.value)
        }

    @Test fun `cleared model cannot revive from a late callback or accept another read`() =
        runTest {
            var callback: Continuation<LocationReading>? = null
            answer = { suspendCoroutine { callback = it } }
            val model = model()
            model.readLastKnown()
            runCurrent()
            store.clear()
            callback!!.resume(sample())
            model.readLastKnown()
            runCurrent()
            assertEquals(1, calls)
            assertEquals(LocationProbeState.Idle, model.state.value)
            assertNull(model.beginPermissionRequest())
        }

    private fun model(): LocationProbeViewModel = LocationProbeViewModel(vehicle, restrictions, gateway).also { store.put("probe", it) }

    private fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

    private fun sample(): LocationReading =
        LocationReading.LastKnown(
            LastKnownLocation(
                latitude = 37.5665,
                longitude = 126.9780,
                altitudeRaw = 31.0,
                headingRaw = 12.0f,
                confidenceRaw = 0.3f,
                sdkTimestampMillis = 123L,
                receivedAtElapsedRealtimeMillis = 456L,
            ),
        )
}
