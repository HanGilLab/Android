package io.github.hangillab.hangil.core.vehicle

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PleosLocationClientTest {
    @Test fun `cancelled client cannot reinstall a callback or start a new SDK request`() {
        val client = PleosLocationClient(RuntimeEnvironment.getApplication())
        var called = false
        client.cancelPending()
        assertThrows(CancellationException::class.java) { client.readLastKnown { called = true } }
        client.close()
        assertFalse(called)
    }
}
