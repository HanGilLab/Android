package io.github.hangillab.hangil.core.vehicle

import io.github.hangillab.hangil.core.vehicle.FusedLocationUpdateProbe.CallbackSource
import io.github.hangillab.hangil.core.vehicle.FusedLocationUpdateProbe.Cancellation
import io.github.hangillab.hangil.core.vehicle.FusedLocationUpdateProbe.Driver
import io.github.hangillab.hangil.core.vehicle.FusedLocationUpdateProbe.Event
import io.github.hangillab.hangil.core.vehicle.FusedLocationUpdateProbe.Kind
import io.github.hangillab.hangil.core.vehicle.FusedLocationUpdateProbe.Listener
import io.github.hangillab.hangil.core.vehicle.FusedLocationUpdateProbe.Scheduler
import io.github.hangillab.hangil.core.vehicle.FusedLocationUpdateProbe.Stage
import io.github.hangillab.hangil.core.vehicle.FusedLocationUpdateProbe.Termination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Controlled driver tests verify probe ownership, not an actual SDK registration or location fix. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FusedLocationUpdateProbeTest {
    @Test fun `callbacks preserve presence validity and app receipt time without a location claim`() {
        val fixture = Fixture()
        fixture.probe.start()
        val firstReceipt = fixture.scheduler.now
        fixture.driver.listener.onChanged(true)
        fixture.scheduler.advanceBy(11)
        fixture.driver.listener.onChanged(false)
        fixture.scheduler.advanceBy(7)
        fixture.driver.listener.onChanged(null)

        val events = fixture.probe.snapshot().events
        assertEquals(listOf(Kind.LOCATION_CALLBACK, Kind.LOCATION_CALLBACK, Kind.EMPTY_CALLBACK), events.map { it.kind })
        assertEquals(listOf(true, true, false), events.map { it.metadata.available })
        assertEquals(listOf(true, false, null), events.map { it.metadata.validCoordinates })
        assertEquals(listOf(firstReceipt, firstReceipt + 11, firstReceipt + 18), events.map { it.metadata.appReceiptElapsedRealtimeMillis })
        assertTrue(events.all { it.metadata.source == CallbackSource.SDK_UPDATE })
        assertTrue(events.all { it.errorClassName == null })
        assertEquals(events, fixture.events)
        assertEquals(3, fixture.probe.snapshot().acceptedCallbackCount)
        assertFalse(fixture.probe.snapshot().closed)
        fixture.probe.close()
        assertCleanRegisteredClose(fixture, Termination.EXPLICIT_CLOSE)
    }

    @Test fun `synchronous callback close waits for registration unwind before cleanup`() {
        val driver = FakeDriver().apply { allocateBeforeCallback = false }
        val fixture = Fixture(driver)
        fixture.observer = { fixture.probe.close() }
        driver.onRegister = { it.onChanged(true) }

        fixture.probe.start()

        assertEquals(1, fixture.events.size)
        assertEquals(
            listOf("initialize", "register.enter", "register.return", "unregister", "binding.close", "release"),
            driver.resourceCalls,
        )
        assertTrue(fixture.probe.snapshot().registerReturned)
        assertCleanRegisteredClose(fixture, Termination.EXPLICIT_CLOSE)
        driver.listener.onChanged(true)
        driver.listener.onError(IllegalStateException("controlled late callback"))
        assertEquals(1, fixture.events.size)
        assertEquals(2, fixture.probe.snapshot().ignoredCallbackCount)
    }

    @Test fun `synchronous callback cancel cannot leave registration installed afterward`() {
        val driver = FakeDriver().apply { allocateBeforeCallback = false }
        val fixture = Fixture(driver)
        fixture.observer = { fixture.probe.cancel() }
        driver.onRegister = { it.onChanged(null) }

        fixture.probe.start()

        assertEquals(listOf(Kind.EMPTY_CALLBACK), fixture.events.map { it.kind })
        assertEquals(
            listOf("initialize", "register.enter", "register.return", "unregister", "binding.close", "release"),
            driver.resourceCalls,
        )
        assertCleanRegisteredClose(fixture, Termination.CANCELLED)
    }

    @Test fun `partially registered throwing call still unregisters and blocks its retained listener`() {
        val failure = IllegalStateException("controlled partial registration")
        val fixture = Fixture(FakeDriver().apply { registerFailure = failure })

        fixture.probe.start()

        val snapshot = fixture.probe.snapshot()
        assertTrue(snapshot.registerAttempted)
        assertFalse(snapshot.registerReturned)
        assertTrue(snapshot.unregisterReturned)
        assertTrue(snapshot.releaseReturned)
        assertTrue(fixture.driver.registered.isEmpty())
        assertTerminated(fixture, Termination.SETUP_ERROR)
        assertFailure(fixture, Stage.REGISTER, failure)
        fixture.driver.listener.onChanged(true)
        fixture.driver.listener.onError(IllegalStateException("controlled late callback"))
        assertTrue(fixture.events.isEmpty())
        assertEquals(2, fixture.probe.snapshot().ignoredCallbackCount)
    }

    @Test fun `initialization throw requires release but never attempts registration`() {
        val failure = IllegalStateException("controlled initialization")
        val fixture = Fixture(FakeDriver().apply { initializeFailure = failure })

        fixture.probe.start()

        val snapshot = fixture.probe.snapshot()
        assertTrue(snapshot.initializeAttempted)
        assertFalse(snapshot.initializeReturned)
        assertFalse(snapshot.registerAttempted)
        assertFalse(snapshot.unregisterAttempted)
        assertTrue(snapshot.releaseRequired)
        assertTrue(snapshot.releaseReturned)
        assertEquals(listOf("initialize", "binding.close", "release"), fixture.driver.resourceCalls)
        assertTerminated(fixture, Termination.SETUP_ERROR)
        assertFailure(fixture, Stage.INITIALIZE, failure)
    }

    @Test fun `blocked initial gate never creates or initializes an SDK driver`() {
        val fixture = Fixture().apply { gate = false }

        fixture.probe.start()

        assertEquals(0, fixture.creations)
        assertTrue(fixture.driver.resourceCalls.isEmpty())
        assertTerminated(fixture, Termination.GATE_BLOCKED)
        assertNull(fixture.probe.snapshot().supported)
        assertNull(fixture.probe.snapshot().permissionGranted)
        assertFalse(fixture.probe.snapshot().releaseRequired)
    }

    @Test fun `unsupported and denied drivers are distinct and never initialize`() {
        val unsupported = Fixture(FakeDriver().apply { supported = false })
        val denied = Fixture(FakeDriver().apply { permission = false })

        unsupported.probe.start()
        denied.probe.start()

        assertTerminated(unsupported, Termination.UNSUPPORTED)
        assertTerminated(denied, Termination.PERMISSION_DENIED)
        assertEquals(false, unsupported.probe.snapshot().supported)
        assertEquals(false, denied.probe.snapshot().permissionGranted)
        listOf(unsupported, denied).forEach { fixture ->
            assertFalse(fixture.probe.snapshot().initializeAttempted)
            assertFalse(fixture.probe.snapshot().registerAttempted)
            assertFalse(fixture.probe.snapshot().releaseRequired)
            assertEquals(listOf("binding.close"), fixture.driver.resourceCalls)
            assertTrue(fixture.events.isEmpty())
        }
    }

    @Test fun `driver creation failure remains setup failure without invented support metadata`() {
        val failure = IllegalStateException("controlled driver creation")
        val fixture = Fixture().apply { factoryFailure = failure }

        fixture.probe.start()

        assertEquals(1, fixture.creations)
        assertTerminated(fixture, Termination.SETUP_ERROR)
        assertFailure(fixture, Stage.DRIVER_CREATION, failure)
        assertNull(fixture.probe.snapshot().supported)
        assertNull(fixture.probe.snapshot().permissionGranted)
        assertFalse(fixture.probe.snapshot().bindingCloseAttempted)
        assertFalse(fixture.probe.snapshot().releaseAttempted)
    }

    @Test fun `gate and permission check exceptions preserve their exact failure stage`() {
        val gateFailure = IllegalStateException("controlled gate")
        val gate = Fixture().apply { this.gateFailure = gateFailure }
        val permissionFailure = IllegalStateException("controlled permission check")
        val permission = Fixture(FakeDriver().apply { this.permissionFailure = permissionFailure })

        gate.probe.start()
        permission.probe.start()

        assertTerminated(gate, Termination.GATE_BLOCKED)
        assertFailure(gate, Stage.GATE_CHECK, gateFailure)
        assertEquals(0, gate.creations)
        assertTerminated(permission, Termination.SETUP_ERROR)
        assertFailure(permission, Stage.PERMISSION_CHECK, permissionFailure)
        assertNull(permission.probe.snapshot().permissionGranted)
        assertFalse(permission.probe.snapshot().initializeAttempted)
        assertEquals(listOf("binding.close"), permission.driver.resourceCalls)
    }

    @Test fun `callback after live gate denial cannot deliver a location event`() {
        val fixture = Fixture()
        fixture.probe.start()
        fixture.gate = false

        fixture.driver.listener.onChanged(true)

        assertTrue(fixture.events.isEmpty())
        assertEquals(0, fixture.probe.snapshot().acceptedCallbackCount)
        assertCleanRegisteredClose(fixture, Termination.GATE_BLOCKED)
        fixture.gate = true
        fixture.driver.listener.onChanged(true)
        assertTrue(fixture.events.isEmpty())
    }

    @Test fun `permission revoked before a callback cannot be reported as an available location`() {
        val fixture = Fixture()
        fixture.probe.start()
        fixture.driver.permission = false

        fixture.driver.listener.onChanged(true)

        assertTrue(fixture.events.isEmpty())
        assertEquals(false, fixture.probe.snapshot().permissionGranted)
        assertEquals(0, fixture.probe.snapshot().acceptedCallbackCount)
        assertCleanRegisteredClose(fixture, Termination.PERMISSION_DENIED)
        fixture.driver.permission = true
        fixture.driver.listener.onChanged(true)
        assertTrue(fixture.events.isEmpty())
    }

    @Test fun `periodic guard closes a silent registration when the gate becomes false`() {
        val fixture = Fixture()
        fixture.probe.start()
        fixture.gate = false

        fixture.scheduler.advanceBy(99)
        assertFalse(fixture.probe.snapshot().closed)
        fixture.scheduler.advanceBy(1)

        assertCleanRegisteredClose(fixture, Termination.GATE_BLOCKED)
        assertTrue(fixture.events.isEmpty())
        fixture.gate = true
        fixture.scheduler.advanceBy(20_000)
        assertEquals(Termination.GATE_BLOCKED, fixture.probe.snapshot().termination)
        assertEquals(1, fixture.driver.resourceCalls.count { it == "unregister" })
    }

    @Test fun `periodic permission check closes without requiring another SDK callback`() {
        val fixture = Fixture()
        fixture.probe.start()
        fixture.driver.permission = false

        fixture.scheduler.advanceBy(100)

        assertCleanRegisteredClose(fixture, Termination.PERMISSION_DENIED)
        assertTrue(fixture.events.isEmpty())
    }

    @Test fun `timeout uses the injected elapsed clock and never accepts a later callback`() {
        val fixture = Fixture()
        fixture.probe.start()

        fixture.scheduler.advanceBy(19_999)
        assertFalse(fixture.probe.snapshot().closed)
        fixture.scheduler.advanceBy(1)

        assertCleanRegisteredClose(fixture, Termination.TIMED_OUT)
        assertTrue(fixture.events.isEmpty())
        fixture.driver.listener.onChanged(true)
        assertEquals(1, fixture.probe.snapshot().ignoredCallbackCount)
        assertEquals(0, fixture.probe.snapshot().acceptedCallbackCount)
    }

    @Test fun `explicit close discards callbacks even while cleanup invokes the listener`() {
        val fixture = Fixture()
        fixture.probe.start()
        fixture.driver.onUnregister = { fixture.driver.listener.onChanged(true) }
        fixture.driver.onBindingClose = { fixture.driver.listener.onError(IllegalStateException("controlled cleanup callback")) }
        fixture.driver.onRelease = { fixture.driver.listener.onChanged(null) }

        fixture.probe.close()
        fixture.probe.close()
        fixture.probe.cancel()

        assertCleanRegisteredClose(fixture, Termination.EXPLICIT_CLOSE)
        assertTrue(fixture.events.isEmpty())
        assertEquals(3, fixture.probe.snapshot().ignoredCallbackCount)
    }

    @Test fun `cancelling a running probe preserves cancellation and is idempotent`() {
        val fixture = Fixture()
        fixture.probe.start()

        fixture.probe.cancel()
        fixture.probe.cancel()
        fixture.probe.close()
        fixture.scheduler.advanceBy(30_000)

        assertCleanRegisteredClose(fixture, Termination.CANCELLED)
        assertTrue(fixture.events.isEmpty())
        assertNull(fixture.probe.failureCause())
    }

    @Test fun `generic SDK error is not guessed to mean permission denial from its message`() {
        val fixture = Fixture()
        val failure = IllegalStateException("permission denied unsupported empty")
        fixture.probe.start()

        fixture.driver.listener.onError(failure)

        assertCleanRegisteredClose(fixture, Termination.SDK_ERROR)
        assertFailure(fixture, Stage.SDK_CALLBACK, failure)
        val event = fixture.events.single()
        assertEquals(Kind.SDK_ERROR, event.kind)
        assertEquals(failure.javaClass.name, event.errorClassName)
        assertEquals(CallbackSource.SDK_UPDATE, event.metadata.source)
        assertEquals(false, event.metadata.available)
        assertNull(event.metadata.validCoordinates)
    }

    @Test fun `observer failure cannot leave the SDK registration alive`() {
        val failure = IllegalStateException("controlled observer")
        val fixture = Fixture().apply { observer = { throw failure } }
        fixture.probe.start()

        fixture.driver.listener.onChanged(true)

        assertCleanRegisteredClose(fixture, Termination.OBSERVER_ERROR)
        assertFailure(fixture, Stage.OBSERVER, failure)
        assertEquals(1, fixture.probe.snapshot().acceptedCallbackCount)
        assertEquals(
            1,
            fixture.probe
                .snapshot()
                .events.size,
        )
        fixture.driver.listener.onChanged(true)
        assertEquals(1, fixture.events.size)
    }

    @Test fun `unregister failure cannot skip binding close or release and remains primary`() {
        val unregister = IllegalStateException("controlled unregister")
        val binding = UnsupportedOperationException("controlled binding close")
        val release = IllegalArgumentException("controlled release")
        val fixture =
            Fixture(
                FakeDriver().apply {
                    unregisterFailure = unregister
                    bindingCloseFailure = binding
                    releaseFailure = release
                },
            )
        fixture.probe.start()

        fixture.probe.close()
        fixture.probe.close()

        val snapshot = fixture.probe.snapshot()
        assertTrue(snapshot.closed)
        assertEquals(Termination.EXPLICIT_CLOSE, snapshot.termination)
        assertTrue(snapshot.unregisterAttempted)
        assertFalse(snapshot.unregisterReturned)
        assertTrue(snapshot.bindingCloseAttempted)
        assertFalse(snapshot.bindingCloseReturned)
        assertTrue(snapshot.releaseAttempted)
        assertFalse(snapshot.releaseReturned)
        assertTrue(snapshot.cleanupComplete)
        assertSame(unregister, fixture.probe.failureCause())
        assertEquals(listOf(binding, release), unregister.suppressed.toList())
        assertEquals(listOf(Stage.UNREGISTER, Stage.BINDING_CLOSE, Stage.RELEASE), snapshot.failures.map { it.stage })
        assertEquals(listOf(true, false, false), snapshot.failures.map { it.primary })
        assertEquals(1, fixture.driver.resourceCalls.count { it == "unregister" })
        assertEquals(1, fixture.driver.resourceCalls.count { it == "binding.close" })
        assertEquals(1, fixture.driver.resourceCalls.count { it == "release" })
        fixture.driver.listener.onChanged(true)
        assertTrue(fixture.events.isEmpty())
    }

    @Test fun `setup failure stays primary when all cleanup operations also fail`() {
        val setup = IllegalStateException("controlled register")
        val unregister = UnsupportedOperationException("controlled unregister")
        val binding = IllegalArgumentException("controlled binding")
        val release = SecurityException("controlled release")
        val fixture =
            Fixture(
                FakeDriver().apply {
                    registerFailure = setup
                    unregisterFailure = unregister
                    bindingCloseFailure = binding
                    releaseFailure = release
                },
            )

        fixture.probe.start()

        assertSame(setup, fixture.probe.failureCause())
        assertEquals(listOf(unregister, binding, release), setup.suppressed.toList())
        val snapshot = fixture.probe.snapshot()
        assertEquals(Termination.SETUP_ERROR, snapshot.termination)
        assertEquals(listOf(Stage.REGISTER, Stage.UNREGISTER, Stage.BINDING_CLOSE, Stage.RELEASE), snapshot.failures.map { it.stage })
        assertEquals(listOf(true, false, false, false), snapshot.failures.map { it.primary })
        assertTrue(snapshot.cleanupComplete)
        assertTrue(snapshot.unregisterAttempted && snapshot.bindingCloseAttempted && snapshot.releaseAttempted)
    }

    @Test fun `initial scheduler failure never creates a driver or begins registration`() {
        val failure = IllegalStateException("controlled scheduler")
        val fixture = Fixture().apply { scheduler.scheduleFailure = failure }

        fixture.probe.start()

        assertTerminated(fixture, Termination.SETUP_ERROR)
        assertFailure(fixture, Stage.SCHEDULER, failure)
        assertEquals(0, fixture.creations)
        assertFalse(fixture.probe.snapshot().registerAttempted)
        assertFalse(fixture.probe.snapshot().releaseRequired)
        assertTrue(fixture.events.isEmpty())
    }

    @Test fun `scheduler failure after registration still cleans every owned resource`() {
        val failure = IllegalStateException("controlled scheduler")
        val fixture = Fixture()
        fixture.probe.start()
        fixture.scheduler.scheduleFailure = failure

        fixture.scheduler.advanceBy(100)

        assertCleanRegisteredClose(fixture, Termination.SETUP_ERROR)
        assertFailure(fixture, Stage.SCHEDULER, failure)
        assertTrue(fixture.events.isEmpty())
    }

    @Test fun `timer cancellation failure remains visible and cannot skip SDK cleanup`() {
        val failure = IllegalStateException("controlled timer cancellation")
        val fixture = Fixture()
        fixture.probe.start()
        fixture.scheduler.cancellationFailure = failure

        fixture.probe.close()

        val snapshot = fixture.probe.snapshot()
        assertTrue(snapshot.closed)
        assertTrue(snapshot.unregisterReturned)
        assertTrue(snapshot.bindingCloseReturned)
        assertTrue(snapshot.releaseReturned)
        assertEquals(Termination.EXPLICIT_CLOSE, snapshot.termination)
        assertFailure(fixture, Stage.TIMER_CANCEL, failure)
        assertTrue(fixture.driver.registered.isEmpty())
        fixture.scheduler.advanceBy(30_000)
        assertTrue(fixture.events.isEmpty())
    }

    @Test fun `event limit terminates after the last permitted callback and rejects the rest`() {
        val fixture = Fixture()
        fixture.probe.start()

        repeat(64) { fixture.driver.listener.onChanged(it % 2 == 0) }

        assertCleanRegisteredClose(fixture, Termination.EVENT_LIMIT)
        assertEquals(64, fixture.probe.snapshot().acceptedCallbackCount)
        assertEquals(
            64,
            fixture.probe
                .snapshot()
                .events.size,
        )
        assertEquals(64, fixture.events.size)
        repeat(3) { fixture.driver.listener.onChanged(null) }
        assertEquals(64, fixture.probe.snapshot().acceptedCallbackCount)
        assertEquals(3, fixture.probe.snapshot().ignoredCallbackCount)
        assertEquals(64, fixture.events.size)
    }

    @Test fun `reentrant observer callbacks cannot exceed the event bound`() {
        val fixture = Fixture()
        fixture.observer = {
            if (fixture.events.size < 65) fixture.driver.listener.onChanged(true)
        }
        fixture.probe.start()

        fixture.driver.listener.onChanged(true)

        assertCleanRegisteredClose(fixture, Termination.EVENT_LIMIT)
        assertEquals(64, fixture.probe.snapshot().acceptedCallbackCount)
        assertEquals(
            64,
            fixture.probe
                .snapshot()
                .events.size,
        )
        assertEquals(64, fixture.events.size)
        assertEquals(1, fixture.probe.snapshot().ignoredCallbackCount)
    }

    @Test fun `repeated start cannot allocate a second driver or registration`() {
        val fixture = Fixture()

        fixture.probe.start()
        fixture.probe.start()

        assertEquals(1, fixture.creations)
        assertEquals(1, fixture.driver.resourceCalls.count { it == "initialize" })
        assertEquals(1, fixture.driver.resourceCalls.count { it == "register.enter" })
        fixture.probe.close()
        fixture.probe.start()
        assertEquals(1, fixture.creations)
        assertCleanRegisteredClose(fixture, Termination.EXPLICIT_CLOSE)
    }

    @Test fun `closing before start permanently prevents driver creation`() {
        val fixture = Fixture()

        fixture.probe.close()
        fixture.probe.start()
        fixture.probe.cancel()

        val snapshot = fixture.probe.snapshot()
        assertFalse(snapshot.started)
        assertTrue(snapshot.closed)
        assertTrue(snapshot.cleanupComplete)
        assertEquals(Termination.EXPLICIT_CLOSE, snapshot.termination)
        assertEquals(0, fixture.creations)
        assertFalse(snapshot.registerAttempted)
        assertFalse(snapshot.releaseRequired)
        assertTrue(fixture.driver.resourceCalls.isEmpty())
        assertTrue(fixture.events.isEmpty())
    }

    @Test fun `permission check cannot lend an earlier gate to registration`() {
        val driver = FakeDriver()
        val fixture = Fixture(driver)
        driver.onPermissionCheck = {
            if (driver.resourceCalls.lastOrNull() == "initialize") fixture.gate = false
        }

        try {
            fixture.probe.start()

            assertFalse(fixture.gate)
            assertEquals(0, driver.resourceCalls.count { it == "register.enter" })
            val snapshot = fixture.probe.snapshot()
            assertTrue(snapshot.initializeReturned)
            assertFalse(snapshot.registerAttempted)
            assertFalse(snapshot.unregisterAttempted)
            assertTrue(snapshot.bindingCloseReturned)
            assertTrue(snapshot.releaseReturned)
            assertTerminated(fixture, Termination.GATE_BLOCKED)
            assertTrue(fixture.events.isEmpty())
        } finally {
            fixture.probe.close()
        }
    }

    @Test fun `permission check cannot lend an earlier gate to observer delivery`() {
        val fixture = Fixture()
        fixture.probe.start()
        fixture.driver.onPermissionCheck = { fixture.gate = false }

        try {
            fixture.driver.listener.onChanged(true)

            assertFalse(fixture.gate)
            assertEquals(0, fixture.events.size)
            assertEquals(0, fixture.probe.snapshot().acceptedCallbackCount)
            assertEquals(1, fixture.probe.snapshot().ignoredCallbackCount)
            assertCleanRegisteredClose(fixture, Termination.GATE_BLOCKED)
        } finally {
            fixture.probe.close()
        }
    }

    private fun assertTerminated(
        fixture: Fixture,
        termination: Termination,
    ) {
        val snapshot = fixture.probe.snapshot()
        assertTrue(snapshot.started)
        assertTrue(snapshot.closed)
        assertEquals(termination, snapshot.termination)
        assertTrue(snapshot.cleanupComplete)
    }

    private fun assertCleanRegisteredClose(
        fixture: Fixture,
        termination: Termination,
    ) {
        assertTerminated(fixture, termination)
        val snapshot = fixture.probe.snapshot()
        assertTrue(snapshot.initializeAttempted)
        assertTrue(snapshot.initializeReturned)
        assertTrue(snapshot.registerAttempted)
        assertTrue(snapshot.registerReturned)
        assertTrue(snapshot.unregisterAttempted)
        assertTrue(snapshot.unregisterReturned)
        assertTrue(snapshot.bindingCloseAttempted)
        assertTrue(snapshot.bindingCloseReturned)
        assertTrue(snapshot.releaseRequired)
        assertTrue(snapshot.releaseAttempted)
        assertTrue(snapshot.releaseReturned)
        assertTrue(fixture.driver.registered.isEmpty())
        assertEquals(1, fixture.driver.resourceCalls.count { it == "unregister" })
        assertEquals(1, fixture.driver.resourceCalls.count { it == "binding.close" })
        assertEquals(1, fixture.driver.resourceCalls.count { it == "release" })
    }

    private fun assertFailure(
        fixture: Fixture,
        stage: Stage,
        cause: Throwable,
    ) {
        assertSame(cause, fixture.probe.failureCause())
        val first =
            fixture.probe
                .snapshot()
                .failures
                .first()
        assertEquals(stage, first.stage)
        assertEquals(cause.javaClass.name, first.exceptionClassName)
        assertTrue(first.primary)
    }

    private class Fixture(
        val driver: FakeDriver = FakeDriver(),
    ) {
        val scheduler = ManualScheduler()
        val events = mutableListOf<Event>()
        var gate = true
        var gateFailure: Exception? = null
        var factoryFailure: Exception? = null
        var observer: (Event) -> Unit = {}
        var creations = 0
        val probe =
            FusedLocationUpdateProbe(
                driverFactory = {
                    creations++
                    factoryFailure?.let { throw it }
                    driver
                },
                canContinue = {
                    gateFailure?.let { throw it }
                    gate
                },
                onEvent = {
                    events += it
                    observer(it)
                },
                clock = { scheduler.now },
                scheduler = scheduler,
            )
    }

    private class FakeDriver : Driver {
        val resourceCalls = mutableListOf<String>()
        val registered = mutableSetOf<Listener>()
        var supported = true
        var permission = true
        var permissionFailure: Exception? = null
        var initializeFailure: Exception? = null
        var registerFailure: Exception? = null
        var unregisterFailure: Exception? = null
        var bindingCloseFailure: Exception? = null
        var releaseFailure: Exception? = null
        var allocateBeforeCallback = true
        var onPermissionCheck: () -> Unit = {}
        var onRegister: (Listener) -> Unit = {}
        var onUnregister: () -> Unit = {}
        var onBindingClose: () -> Unit = {}
        var onRelease: () -> Unit = {}
        lateinit var listener: Listener
            private set

        override fun isSupported(): Boolean = supported

        override fun hasPermission(): Boolean {
            permissionFailure?.let { throw it }
            onPermissionCheck()
            return permission
        }

        override fun initialize() {
            resourceCalls += "initialize"
            initializeFailure?.let { throw it }
        }

        override fun register(listener: Listener) {
            resourceCalls += "register.enter"
            this.listener = listener
            if (allocateBeforeCallback) registered += listener
            onRegister(listener)
            if (!allocateBeforeCallback) registered += listener
            registerFailure?.let { throw it }
            resourceCalls += "register.return"
        }

        override fun unregister(listener: Listener) {
            resourceCalls += "unregister"
            onUnregister()
            unregisterFailure?.let { throw it }
            check(registered.remove(listener)) { "Controlled listener was not registered" }
        }

        override fun closeBinding() {
            resourceCalls += "binding.close"
            onBindingClose()
            bindingCloseFailure?.let { throw it }
        }

        override fun release() {
            resourceCalls += "release"
            onRelease()
            releaseFailure?.let { throw it }
        }
    }

    private class ManualScheduler : Scheduler {
        var now = 10_000L
            private set
        var scheduleFailure: Exception? = null
        var cancellationFailure: Exception? = null
        private val tasks = mutableListOf<Task>()

        override fun schedule(
            delayMillis: Long,
            action: () -> Unit,
        ): Cancellation {
            scheduleFailure?.let { throw it }
            check(delayMillis >= 0)
            val task = Task(now + delayMillis, action)
            tasks += task
            return Cancellation {
                task.cancelled = true
                cancellationFailure?.let { throw it }
            }
        }

        fun advanceBy(millis: Long) {
            check(millis >= 0)
            val target = now + millis
            var actions = 0
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
                tasks.remove(next)
                now = next.at
                check(++actions <= 1_000) { "Controlled scheduler did not make progress" }
                next.action()
            }
            now = target
        }

        private class Task(
            val at: Long,
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )
    }
}
