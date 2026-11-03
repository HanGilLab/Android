package io.github.hangillab.hangil.core.vehicle

import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real SDK listener/mapping and binding context, with an explicit controlled SDK-call seam. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PleosLocationUpdateClientTest {
    @Test fun `partially failed registration retains the same actual SDK listener for unregister`() {
        val sdk = FakeSdk().apply { registerFailure = IllegalStateException("controlled registration") }
        val listener = Capture()
        val client = PleosLocationUpdateClient(RuntimeEnvironment.getApplication(), sdk) { 7 }
        client.initialize()
        assertThrows(IllegalStateException::class.java) { client.register(listener) }
        val registered = sdk.registered
        client.unregister(listener)
        assertSame(registered, sdk.unregistered)
        assertThrows(IllegalStateException::class.java) { client.unregister(Capture()) }
        client.closeBinding()
        client.release()
        assertEquals(listOf("initialize", "register", "unregister", "release"), sdk.calls)
    }

    @Test fun `synchronous SDK callback has update source and null and error remain distinct`() {
        val sdk = FakeSdk().apply { onRegister = { it.onChanged(null) } }
        val listener = Capture()
        val client = PleosLocationUpdateClient(RuntimeEnvironment.getApplication(), sdk) { 7 }
        client.register(listener)
        assertEquals(listOf(LocationUpdateReading.Empty(7)), listener.readings)
        val sdkError = Exception("controlled message must not be inferred")
        sdk.registered!!.onError(sdkError)
        assertSame(sdkError, listener.sdkErrors.single())
        sdk.registered!!.onChanged(LocationData(0.0, 0.0, 0.0, 0f, 0f, -3L))
        assertEquals(-3L, (listener.readings.last() as LocationUpdateReading.Sample).sample.sdkTimestampMillis)
        client.unregister(listener)
        client.closeBinding()
        client.release()
    }

    @Test fun `mapping failures are adapter failures and late callbacks after cleanup have no sink`() {
        val sdk = FakeSdk()
        val listener = Capture()
        val client = PleosLocationUpdateClient(RuntimeEnvironment.getApplication(), sdk) { 7 }
        client.register(listener)
        val late = sdk.registered!!
        late.onChanged(LocationData(Double.NaN, 0.0, 0.0, 0f, 0f, 0L))
        assertEquals(1, listener.adapterErrors.size)
        assertEquals(0, listener.readings.size)
        client.unregister(listener)
        client.closeBinding()
        late.onChanged(null)
        late.onError(Exception("controlled late callback"))
        assertEquals(0, listener.readings.size)
        assertEquals(0, listener.sdkErrors.size)
        assertThrows(IllegalStateException::class.java) { client.initialize() }
        assertThrows(IllegalStateException::class.java) { client.register(listener) }
        client.release()
    }

    private class Capture : LocationUpdateClient.Listener {
        val readings = mutableListOf<LocationUpdateReading>()
        val sdkErrors = mutableListOf<Exception>()
        val adapterErrors = mutableListOf<Exception>()

        override fun onReading(reading: LocationUpdateReading) {
            readings += reading
        }

        override fun onSdkError(
            failure: Exception,
            receivedAtElapsedRealtimeMillis: Long,
        ) {
            sdkErrors += failure
        }

        override fun onAdapterError(failure: Exception) {
            adapterErrors += failure
        }
    }

    private class FakeSdk : PleosLocationUpdateClient.Sdk {
        val calls = mutableListOf<String>()
        var registered: FusedLocationListener? = null
        var unregistered: FusedLocationListener? = null
        var registerFailure: Exception? = null
        var onRegister: (FusedLocationListener) -> Unit = { }

        override fun isSupported(): Boolean = true

        override fun initialize() {
            calls += "initialize"
        }

        override fun register(listener: FusedLocationListener) {
            calls += "register"
            registered = listener
            onRegister(listener)
            registerFailure?.let { throw it }
        }

        override fun unregister(listener: FusedLocationListener) {
            calls += "unregister"
            unregistered = listener
        }

        override fun release() {
            calls += "release"
        }
    }
}
