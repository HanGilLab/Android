package io.github.hangillab.hangil.core.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SdkBindingContextTest {
    @Test fun `false bind releases its dispatcher and never forwards callbacks`() {
        val platform = TrackingContext().apply { bindResult = false }
        val boundary = SdkBindingContext(platform)
        val sdk = CountingConnection()
        assertFalse(boundary.bindService(Intent(), sdk, Context.BIND_AUTO_CREATE))
        assertEquals(1, platform.unbinds.get())
        assertTrue(platform.registered.isEmpty())
        platform.connections.single().onServiceConnected(COMPONENT, Binder())
        assertEquals(0, sdk.connected)
        boundary.close()
        assertEquals(1, platform.unbinds.get())
    }

    @Test fun `throwing bind releases the allocated dispatcher and preserves the exception`() {
        val rejected = SecurityException("bind rejected")
        val platform = TrackingContext().apply { bindFailure = rejected }
        val boundary = SdkBindingContext(platform)
        val sdk = CountingConnection()
        assertSame(rejected, assertThrows(SecurityException::class.java) { boundary.bindService(Intent(), sdk, 0) })
        assertEquals(1, platform.unbinds.get())
        assertTrue(platform.registered.isEmpty())
        platform.connections.single().onServiceConnected(COMPONENT, Binder())
        assertEquals(0, sdk.connected)
        boundary.close()
    }

    @Test fun `close blocks future platform binds and all late connection callbacks`() {
        val platform = TrackingContext()
        val boundary = SdkBindingContext(platform)
        val sdk = CountingConnection()
        assertTrue(boundary.bindService(Intent(), sdk, 0))
        val proxy = platform.connections.single()
        assertNotSame(sdk, proxy)
        proxy.onServiceConnected(COMPONENT, Binder())
        assertEquals(1, sdk.connected)
        boundary.close()
        proxy.onServiceConnected(COMPONENT, Binder())
        proxy.onServiceDisconnected(COMPONENT)
        proxy.onBindingDied(COMPONENT)
        proxy.onNullBinding(COMPONENT)
        assertEquals(1, sdk.connected)
        assertEquals(0, sdk.otherCallbacks)
        repeat(3) { assertFalse(boundary.bindService(Intent(), sdk, 0)) }
        boundary.unbindService(sdk)
        assertEquals(1, platform.binds.get())
        assertEquals(1, platform.unbinds.get())
        assertTrue(platform.registered.isEmpty())
    }

    @Test fun `a rebound SDK connection gets a new proxy and the previous proxy stays inactive`() {
        val platform = TrackingContext()
        val boundary = SdkBindingContext(platform)
        val sdk = CountingConnection()
        assertTrue(boundary.bindService(Intent(), sdk, 0))
        val previous = platform.connections.single()
        boundary.unbindService(sdk)
        assertTrue(boundary.bindService(Intent(), sdk, 0))
        val current = platform.connections.last()
        assertNotSame(previous, current)
        previous.onServiceConnected(COMPONENT, Binder())
        current.onServiceConnected(COMPONENT, Binder())
        assertEquals(1, sdk.connected)
        boundary.close()
        assertEquals(2, platform.unbinds.get())
        assertTrue(platform.registered.isEmpty())
    }

    @Test fun `a callback received synchronously during a rejected bind is discarded`() {
        val platform =
            TrackingContext().apply {
                bindResult = false
                onBind = { it.onServiceConnected(COMPONENT, Binder()) }
            }
        val boundary = SdkBindingContext(platform)
        val sdk = CountingConnection()
        assertFalse(boundary.bindService(Intent(), sdk, 0))
        assertEquals(0, sdk.connected)
        boundary.close()
    }

    @Test fun `a synchronous callback is delivered only after its bind succeeds`() {
        val platform = TrackingContext().apply { onBind = { it.onServiceConnected(COMPONENT, Binder()) } }
        val boundary = SdkBindingContext(platform)
        val sdk = CountingConnection()
        assertTrue(boundary.bindService(Intent(), sdk, 0))
        assertEquals(1, sdk.connected)
        boundary.close()
    }

    @Test fun `cleanup failures remain observable while callbacks and later binds stay blocked`() {
        val failure = IllegalStateException("unbind failed")
        val platform = TrackingContext()
        val boundary = SdkBindingContext(platform)
        val sdk = CountingConnection()
        assertTrue(boundary.bindService(Intent(), sdk, 0))
        platform.unbindFailure = failure
        assertSame(failure, assertThrows(IllegalStateException::class.java) { boundary.close() })
        platform.connections.single().onServiceConnected(COMPONENT, Binder())
        assertEquals(0, sdk.connected)
        assertFalse(boundary.bindService(Intent(), sdk, 0))
        platform.unbindFailure = null
        assertSame(failure, assertThrows(IllegalStateException::class.java) { boundary.close() })
        assertTrue(platform.registered.isEmpty())
    }

    @Test fun `an already released dispatcher is the only tolerated unbind failure`() {
        val platform = TrackingContext()
        val boundary = SdkBindingContext(platform)
        val sdk = CountingConnection()
        assertTrue(boundary.bindService(Intent(), sdk, 0))
        platform.registered.clear()
        boundary.close()
        boundary.close()
        assertEquals(1, platform.unbinds.get())
    }

    @Test fun `close racing with an in flight bind cleans it before allowing any later retry`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val platform =
            TrackingContext().apply {
                onBind = {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
        val boundary = SdkBindingContext(platform)
        val sdk = CountingConnection()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val bind = workers.submit<Boolean> { boundary.bindService(Intent(), sdk, 0) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val close =
                workers.submit {
                    closeStarted.countDown()
                    boundary.close()
                }
            assertTrue(closeStarted.await(10, TimeUnit.SECONDS))
            assertFalse(close.isDone)
            release.countDown()
            assertTrue(bind.get(10, TimeUnit.SECONDS))
            close.get(10, TimeUnit.SECONDS)
            assertTrue(platform.registered.isEmpty())
            assertEquals(1, platform.binds.get())
            assertEquals(1, platform.unbinds.get())
            platform.connections.single().onServiceConnected(COMPONENT, Binder())
            assertEquals(0, sdk.connected)
            assertFalse(boundary.bindService(Intent(), sdk, 0))
            assertEquals(1, platform.binds.get())
        } finally {
            release.countDown()
            workers.shutdownNow()
        }
    }

    private class TrackingContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val binds = AtomicInteger()
        val unbinds = AtomicInteger()
        val connections = CopyOnWriteArrayList<ServiceConnection>()
        val registered = Collections.newSetFromMap(IdentityHashMap<ServiceConnection, Boolean>())
        var bindResult = true
        var bindFailure: RuntimeException? = null
        var unbindFailure: RuntimeException? = null
        var onBind: (ServiceConnection) -> Unit = {}

        override fun getApplicationContext(): Context = this

        override fun bindService(
            service: Intent,
            conn: ServiceConnection,
            flags: Int,
        ): Boolean {
            binds.incrementAndGet()
            connections += conn
            registered += conn
            onBind(conn)
            bindFailure?.let { throw it }
            return bindResult
        }

        override fun unbindService(conn: ServiceConnection) {
            unbinds.incrementAndGet()
            unbindFailure?.let { throw it }
            if (!registered.remove(conn)) throw IllegalArgumentException("already released")
        }
    }

    private class CountingConnection : ServiceConnection {
        var connected = 0
        var otherCallbacks = 0

        override fun onServiceConnected(
            name: ComponentName,
            service: IBinder,
        ) {
            connected++
        }

        override fun onServiceDisconnected(name: ComponentName) {
            otherCallbacks++
        }

        override fun onBindingDied(name: ComponentName) {
            otherCallbacks++
        }

        override fun onNullBinding(name: ComponentName) {
            otherCallbacks++
        }
    }

    private companion object {
        val COMPONENT = ComponentName("example.location", "ExampleService")
    }
}
