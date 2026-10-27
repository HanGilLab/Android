package io.github.hangillab.hangil.core.vehicle

import ai.pleos.playground.vehicle.constant.ErrorCode
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.TurnSignalState
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.core.domain.toDrivingRestrictions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ai.pleos.playground.vehicle.callback.exception.a as PleosSdkVehicleException

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleMonitorTest {
    @Test
    fun `gear failure invalidates session so late park callbacks cannot unlock it`() =
        runTest {
            val client = FakeClient()
            val states = mutableListOf<VehicleState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                VehicleMonitor({ client }).observe().collect { states += it }
            }
            runCurrent()
            client.observer.onGear(VehicleReading.Error)
            client.observer.onGear(VehicleReading.Available(GearPosition.PARK))
            runCurrent()
            assertEquals(ConnectionState.ERROR, states.last().connection)
            assertEquals(VehicleReading.Unknown, states.last().gear)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, client.closes)
        }

    @Test
    fun `burst of signal updates cannot drop the latest drive state`() =
        runTest {
            val client = FakeClient()
            val states = mutableListOf<VehicleState>()
            backgroundScope.launch {
                VehicleMonitor({ client }).observe().collect { states += it }
            }
            runCurrent()
            repeat(1_000) { client.observer.onBrake(VehicleReading.Available(it % 2 == 0)) }
            client.observer.onGear(VehicleReading.Available(GearPosition.DRIVE))
            runCurrent()
            assertEquals(VehicleReading.Available(GearPosition.DRIVE), states.last().gear)
        }

    @Test
    fun `callbacks update real readings and unknown never means zero`() =
        runTest {
            val client = FakeClient()
            val states = mutableListOf<VehicleState>()
            val job =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    VehicleMonitor({ client }).observe().collect { states += it }
                }
            runCurrent()
            assertEquals(ConnectionState.CONNECTED, states.last().connection)
            assertEquals(VehicleReading.Unknown, states.last().turnSignal)
            client.observer.onTurnSignal(VehicleReading.Available(TurnSignalState.LEFT))
            client.observer.onBrake(VehicleReading.Available(true))
            runCurrent()
            assertEquals(VehicleReading.Available(TurnSignalState.LEFT), states.last().turnSignal)
            assertEquals(VehicleReading.Available(true), states.last().brakePressed)
            client.observer.onBrake(VehicleReading.Error)
            runCurrent()
            assertEquals(VehicleReading.Error, states.last().brakePressed)
            job.cancel()
            runCurrent()
            assertEquals(1, client.closes)
        }

    @Test
    fun `park query cannot overwrite newer drive callback`() =
        runTest {
            val oldQuery = CompletableDeferred<VehicleReading<GearPosition>>()
            val client = FakeClient().apply { getGear = { oldQuery.await() } }
            val states = mutableListOf<VehicleState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                VehicleMonitor({ client }).observe().collect { states += it }
            }
            runCurrent()
            client.observer.onGear(VehicleReading.Available(GearPosition.DRIVE))
            oldQuery.complete(VehicleReading.Available(GearPosition.PARK))
            runCurrent()
            assertEquals(VehicleReading.Available(GearPosition.DRIVE), states.last().gear)
        }

    @Test
    fun `timeout clears park and readings then reconnect ignores old callbacks`() =
        runTest {
            val first = FakeClient()
            val second = FakeClient().apply { getGear = { VehicleReading.Available(GearPosition.DRIVE) } }
            var creations = 0
            val states = mutableListOf<VehicleState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                VehicleMonitor({ if (creations++ == 0) first else second }).observe().collect { states += it }
            }
            runCurrent()
            assertEquals(VehicleReading.Available(GearPosition.PARK), states.last().gear)
            first.observer.onBrake(VehicleReading.Available(true))
            first.getGear = { awaitCancellation() }
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(ConnectionState.DISCONNECTED, states.last().connection)
            assertEquals(VehicleReading.Unknown, states.last().gear)
            assertEquals(VehicleReading.Unknown, states.last().brakePressed)
            assertEquals(1, first.closes)
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(VehicleReading.Available(GearPosition.DRIVE), states.last().gear)
            first.observer.onGear(VehicleReading.Available(GearPosition.PARK))
            first.observer.onTurnSignal(VehicleReading.Available(TurnSignalState.RIGHT))
            runCurrent()
            assertEquals(VehicleReading.Available(GearPosition.DRIVE), states.last().gear)
            assertEquals(VehicleReading.Unknown, states.last().turnSignal)
        }

    @Test
    fun `denied permission releases client and does not retry continuously`() =
        runTest {
            val client = FakeClient().apply { openFailure = SecurityException() }
            val states = mutableListOf<VehicleState>()
            var creations = 0
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                VehicleMonitor({
                    creations++
                    client
                }).observe().collect { states += it }
            }
            runCurrent()
            assertEquals(ConnectionState.PERMISSION_DENIED, states.last().connection)
            assertEquals(VehicleReading.Unknown, states.last().gear)
            assertEquals(1, client.closes)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, creations)
        }

    @Test
    fun `unsupported platform never pretends to connect`() =
        runTest {
            val states = mutableListOf<VehicleState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                VehicleMonitor({ throw UnsupportedOperationException() }).observe().collect { states += it }
            }
            runCurrent()
            assertEquals(ConnectionState.UNAVAILABLE, states.last().connection)
            assertEquals(VehicleReading.Unknown, states.last().gear)
        }

    @Test
    fun `permission failure in a signal cannot be overwritten by park polling`() =
        runTest {
            val client = FakeClient()
            val states = mutableListOf<VehicleState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                VehicleMonitor({ client }).observe().collect { states += it }
            }
            runCurrent()
            client.observer.onTurnSignal(VehicleReading.PermissionDenied)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(ConnectionState.PERMISSION_DENIED, states.last().connection)
        }

    @Test
    fun `SDK permission failure in turn signal clears park and rejects late callbacks`() =
        assertSdkPermissionFailureInvalidatesPark { observer, failure -> observer.onTurnSignal(failure) }

    @Test
    fun `SDK permission failure in brake clears park and rejects late callbacks`() =
        assertSdkPermissionFailureInvalidatesPark { observer, failure -> observer.onBrake(failure) }

    @Test
    fun `SDK unsupported gear invalidates park without continuous retries`() =
        runTest {
            val client = FakeClient()
            val states = mutableListOf<VehicleState>()
            var creations = 0
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                VehicleMonitor({
                    creations++
                    client
                }).observe().collect { states += it }
            }
            runCurrent()
            client.observer.onGear(PleosSdkVehicleException(ErrorCode.ERROR_NOT_SUPPORTED_PROPERTY.id.toString()).toReadingFailure())
            client.observer.onGear(VehicleReading.Available(GearPosition.PARK))
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(ConnectionState.UNAVAILABLE, states.last().connection)
            assertEquals(VehicleReading.Unknown, states.last().gear)
            assertFalse(states.last().toDrivingRestrictions(UxRestrictionState.UNRESTRICTED).allowsDetailedInteraction)
            assertEquals(1, client.closes)
            assertEquals(1, creations)
        }

    private fun assertSdkPermissionFailureInvalidatesPark(reportFailure: (VehicleClient.Observer, VehicleReading<Nothing>) -> Unit) =
        runTest {
            val client = FakeClient()
            val states = mutableListOf<VehicleState>()
            var creations = 0
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                VehicleMonitor({
                    creations++
                    client
                }).observe().collect { states += it }
            }
            runCurrent()
            assertTrue(states.last().toDrivingRestrictions(UxRestrictionState.UNRESTRICTED).allowsDetailedInteraction)
            reportFailure(client.observer, PleosSdkVehicleException(ErrorCode.ERROR_SECURITY_PERMISSION.id.toString()).toReadingFailure())
            client.observer.onGear(VehicleReading.Available(GearPosition.PARK))
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(ConnectionState.PERMISSION_DENIED, states.last().connection)
            assertEquals(VehicleReading.Unknown, states.last().gear)
            assertFalse(states.last().toDrivingRestrictions(UxRestrictionState.UNRESTRICTED).allowsDetailedInteraction)
            assertEquals(1, client.closes)
            assertEquals(1, creations)
        }

    @Test
    fun `zero subscribers release SDK and remove cached parking before resubscription`() =
        runTest {
            val clients = mutableListOf<FakeClient>()
            val state =
                VehicleMonitor({ FakeClient().also { clients += it } }).observe().stateIn(
                    backgroundScope,
                    SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0),
                    VehicleState(),
                )
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect {} }
            runCurrent()
            assertEquals(ConnectionState.CONNECTED, state.value.connection)
            collector.cancel()
            runCurrent()
            assertEquals(1, clients.first().closes)
            assertEquals(VehicleState(), state.value)
            assertFalse(state.value.gear is VehicleReading.Available)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.collect {} }
            runCurrent()
            assertEquals(2, clients.size)
            assertTrue(state.value.gear is VehicleReading.Available)
        }

    private class FakeClient : VehicleClient {
        lateinit var observer: VehicleClient.Observer
        var openFailure: Exception? = null
        var getGear: suspend () -> VehicleReading<GearPosition> = { VehicleReading.Available(GearPosition.PARK) }
        var closes = 0

        override fun open(observer: VehicleClient.Observer) {
            openFailure?.let { throw it }
            this.observer = observer
        }

        override suspend fun readGear() = getGear()

        override fun close() {
            closes++
        }
    }
}
