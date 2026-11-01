package io.github.hangillab.hangil.core.vehicle

import io.github.hangillab.hangil.core.domain.LocationUpdateErrorOrigin
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/** Controlled driver and time, not a claim of actual SDK connection, movement or remote stop ACK. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationUpdateOperationTest {
    @Test fun `cold collections each own a client and do not initialize before collection`() =
        runTest {
            val clients = mutableListOf<FakeClient>()
            val operation =
                LocationUpdateOperation(
                    { FakeClient().also(clients::add) },
                    StandardTestDispatcher(testScheduler),
                    { testScheduler.currentTime },
                    eventDispatcher = StandardTestDispatcher(testScheduler),
                )
            val flow = operation.updates { true }
            assertEquals(0, clients.size)
            val first = launch { flow.collect() }
            val second = launch { flow.collect() }
            runCurrent()
            assertEquals(2, clients.size)
            first.cancel()
            runCurrent()
            assertEquals(1, clients.first().unregisters)
            assertEquals(0, clients.last().unregisters)
            second.cancel()
            runCurrent()
            clients.forEach { assertEquals(listOf("initialize", "register", "unregister", "binding", "release"), it.calls) }
        }

    @Test fun `an already revoked owner creates no SDK client`() =
        runTest {
            val operation =
                LocationUpdateOperation(
                    clientFactory = { error("A revoked owner cannot allocate a client") },
                    mainDispatcher = StandardTestDispatcher(testScheduler),
                    clock = { testScheduler.currentTime },
                    eventDispatcher = StandardTestDispatcher(testScheduler),
                )
            assertTrue(operation.updates { false }.toList().isEmpty())
        }

    @Test fun `SDK APIs use the API dispatcher while a synchronous callback does not run the writer there`() =
        runTest {
            val inApi = ThreadLocal<Boolean>()
            val delegate = StandardTestDispatcher(testScheduler)
            val api =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        delegate.dispatch(
                            context,
                            Runnable {
                                inApi.set(true)
                                try {
                                    block.run()
                                } finally {
                                    inApi.remove()
                                }
                            },
                        )
                    }
                }
            val client =
                FakeClient().apply {
                    onApi = { assertEquals(true, inApi.get()) }
                    onRegister = { it.onReading(sample(0)) }
                }
            val operation =
                LocationUpdateOperation(
                    clientFactory = {
                        assertEquals(true, inApi.get())
                        client
                    },
                    mainDispatcher = api,
                    clock = { testScheduler.currentTime },
                    eventDispatcher = StandardTestDispatcher(testScheduler),
                )
            val values = mutableListOf<LocationUpdateReading>()
            val job =
                launch {
                    operation.updates { true }.collect {
                        assertFalse(inApi.get() == true)
                        values += it
                    }
                }
            runCurrent()
            assertEquals(1, values.size)
            job.cancel()
            runCurrent()
            assertEquals(listOf("initialize", "register", "unregister", "binding", "release"), client.calls)
        }

    @Test fun `denied and unsupported are terminal with no initialize or registration`() =
        runTest {
            for (denied in listOf(true, false)) {
                val client =
                    FakeClient().apply {
                        permission = !denied
                        supported = denied
                    }
                val values = operation(client).updates { true }.toList()
                assertEquals(
                    if (denied) LocationUpdateReading.PermissionDenied(0) else LocationUpdateReading.Unsupported(0),
                    values.single(),
                )
                assertEquals(listOf("binding"), client.calls)
            }
        }

    @Test fun `permission lookup failure is class-only adapter error rather than denied`() =
        runTest {
            val failure = IllegalArgumentException("controlled private detail")
            val client = FakeClient().apply { permissionFailure = failure }
            val operation = operation(client)
            val result = operation.updates { true }.toList().single() as LocationUpdateReading.Error
            assertEquals(LocationUpdateErrorOrigin.ADAPTER, result.origin)
            assertEquals(failure.javaClass.name, result.exceptionClassName)
            assertFalse(result.toString().contains(failure.message!!))
            assertSame(failure, operation.failureCause())
            assertEquals(listOf("binding"), client.calls)
        }

    @Test fun `permission call cannot lend an earlier owner true to initialize or register`() =
        runTest {
            var allowed = true
            val client = FakeClient().apply { onPermission = { allowed = false } }
            assertTrue(operation(client).updates { allowed }.toList().isEmpty())
            assertEquals(listOf("binding"), client.calls)
        }

    @Test fun `permission query after initialize cannot lend the earlier owner to register`() =
        runTest {
            var allowed = true
            var checks = 0
            val client =
                FakeClient().apply {
                    onPermission = {
                        checks++
                        if (checks == 3) allowed = false
                    }
                }
            assertTrue(operation(client).updates { allowed }.toList().isEmpty())
            assertEquals(listOf("initialize", "binding", "release"), client.calls)
        }

    @Test fun `queued sample is checked again before delivery after permission loss`() =
        runTest {
            val client = FakeClient()
            val result = async { operation(client).updates { true }.toList() }
            runCurrent()
            client.listener!!.onReading(sample(0))
            client.permission = false
            runCurrent()
            assertEquals(listOf(LocationUpdateReading.PermissionDenied(0)), result.await())
        }

    @Test fun `permission lookup during callback rechecks current owner before downstream delivery`() =
        runTest {
            var allowed = true
            val client = FakeClient()
            val values = mutableListOf<LocationUpdateReading>()
            val job = launch { operation(client).updates { allowed }.toList(values) }
            runCurrent()
            client.onPermission = { allowed = false }
            client.listener!!.onReading(sample(0))
            runCurrent()
            assertTrue(values.isEmpty())
            assertTrue(job.isCompleted)
            assertEquals(1, client.unregisters)
        }

    @Test fun `identical callback samples are not conflated and Empty alone resets thirty second idle`() =
        runTest {
            val client = FakeClient()
            val values = mutableListOf<LocationUpdateReading>()
            val job = launch { operation(client).updates { true }.toList(values) }
            runCurrent()
            client.listener!!.onReading(sample(0))
            runCurrent()
            client.listener!!.onReading(sample(0))
            runCurrent()
            advanceTimeBy(29_900)
            client.listener!!.onReading(LocationUpdateReading.Empty(testScheduler.currentTime))
            runCurrent()
            advanceTimeBy(29_999)
            runCurrent()
            assertFalse(job.isCompleted)
            advanceTimeBy(101)
            runCurrent()
            assertEquals(2, values.filterIsInstance<LocationUpdateReading.Sample>().size)
            assertEquals(1, values.filterIsInstance<LocationUpdateReading.Empty>().size)
            assertTrue(values.last() is LocationUpdateReading.NoUpdate)
            assertTrue(job.isCompleted)
        }

    @Test fun `initial silence terminates at thirty seconds without inventing Empty or unsupported`() =
        runTest {
            val client = FakeClient()
            val result = async { operation(client).updates { true }.toList() }
            runCurrent()
            advanceTimeBy(29_999)
            runCurrent()
            assertFalse(result.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(LocationUpdateReading.NoUpdate(30_000)), result.await())
            assertEquals(1, client.unregisters)
        }

    @Test fun `SDK error keeps actual callback origin and first cause through independent cleanup failures`() =
        runTest {
            val sdkFailure = Exception("permission denied is still only SDK error text")
            val unregister = IllegalStateException("controlled unregister")
            val binding = IllegalArgumentException("controlled binding")
            val release = UnsupportedOperationException("controlled release")
            val client =
                FakeClient().apply {
                    unregisterFailure = unregister
                    bindingFailure = binding
                    releaseFailure = release
                }
            val operation = operation(client)
            val result = async { operation.updates { true }.toList() }
            runCurrent()
            val late = client.listener!!
            late.onSdkError(sdkFailure, 0)
            late.onAdapterError(Exception("controlled late failure"))
            runCurrent()
            val reading = result.await().single() as LocationUpdateReading.Error
            assertEquals(LocationUpdateErrorOrigin.SDK_CALLBACK, reading.origin)
            assertSame(sdkFailure, operation.failureCause())
            assertEquals(listOf(unregister, binding, release), sdkFailure.suppressed.toList())
            assertEquals(listOf("initialize", "register", "unregister", "binding", "release"), client.calls)
        }

    @Test fun `registration partial failure still unregisters the same listener then closes and releases`() =
        runTest {
            val failure = IllegalStateException("controlled partial registration")
            val client = FakeClient().apply { registerFailure = failure }
            val operation = operation(client)
            val result = operation.updates { true }.toList().single() as LocationUpdateReading.Error
            assertEquals(LocationUpdateErrorOrigin.ADAPTER, result.origin)
            assertSame(failure, operation.failureCause())
            assertSame(client.listener, client.unregistered)
            assertEquals(listOf("initialize", "register", "unregister", "binding", "release"), client.calls)
        }

    @Test fun `initialize partial failure closes binding and releases without pretending it registered`() =
        runTest {
            val client = FakeClient().apply { initializeFailure = IllegalStateException("controlled initialize") }
            assertTrue(operation(client).updates { true }.toList().single() is LocationUpdateReading.Error)
            assertEquals(listOf("initialize", "binding", "release"), client.calls)
        }

    @Test fun `synchronous registration callback and owner revocation never reenter downstream as success`() =
        runTest {
            var allowed = true
            val client =
                FakeClient().apply {
                    onRegister = { listener ->
                        listener.onReading(sample(0))
                        allowed = false
                        listener.onReading(LocationUpdateReading.Empty(0))
                    }
                }
            assertTrue(operation(client).updates { allowed }.toList().isEmpty())
            assertSame(client.listener, client.unregistered)
            assertEquals(1, client.unregisters)
        }

    @Test fun `bounded overflow is an explicit adapter failure and is not a successful dropped stream`() =
        runTest {
            val client = FakeClient().apply { onRegister = { listener -> repeat(65) { listener.onReading(sample(0)) } } }
            val operation = operation(client)
            val values = operation.updates { true }.toList()
            assertEquals(1, values.size)
            assertEquals(LocationUpdateErrorOrigin.ADAPTER, (values.single() as LocationUpdateReading.Error).origin)
            assertTrue(operation.failureCause() is IllegalStateException)
            assertEquals(1, client.unregisters)
        }

    @Test fun `permission loss with no callback is observed and late samples cannot continue the run`() =
        runTest {
            val client = FakeClient()
            val values = mutableListOf<LocationUpdateReading>()
            val job = launch { operation(client).updates { true }.toList(values) }
            runCurrent()
            val late = client.listener!!
            client.permission = false
            advanceTimeBy(100)
            runCurrent()
            late.onReading(sample(100))
            runCurrent()
            assertEquals(listOf(LocationUpdateReading.PermissionDenied(100)), values)
            assertTrue(job.isCompleted)
            assertEquals(1, client.unregisters)
        }

    @Test fun `blocked downstream send is interrupted when owner expires rather than delivering queued sample later`() =
        runTest {
            var allowed = true
            val client = FakeClient()
            val values = mutableListOf<LocationUpdateReading>()
            val job =
                launch {
                    operation(client).updates { allowed }.collect { value ->
                        values += value
                        awaitCancellation()
                    }
                }
            runCurrent()
            client.listener!!.onReading(sample(0))
            runCurrent()
            client.listener!!.onReading(sample(0))
            runCurrent()
            allowed = false
            advanceTimeBy(100)
            runCurrent()
            assertEquals(1, values.size)
            assertEquals(1, client.unregisters)
            job.cancel()
            runCurrent()
        }

    @Test fun `cleanup failure after normal idle becomes adapter failure and remaining cleanup still runs`() =
        runTest {
            val failure = IllegalStateException("controlled cleanup")
            val client = FakeClient().apply { unregisterFailure = failure }
            val operation = operation(client)
            val result = async { operation.updates { true }.toList() }
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(LocationUpdateErrorOrigin.ADAPTER, (result.await().single() as LocationUpdateReading.Error).origin)
            assertSame(failure, operation.failureCause())
            assertEquals(listOf("initialize", "register", "unregister", "binding", "release"), client.calls)
        }

    @Test fun `actual SDK error remains callback metadata when current permission is false`() =
        runTest {
            val client = FakeClient()
            val failure = Exception("controlled SDK message")
            val operation = operation(client)
            val result = async { operation.updates { true }.toList() }
            runCurrent()
            client.permission = false
            client.listener!!.onSdkError(failure, 0)
            runCurrent()
            val reading = result.await().single() as LocationUpdateReading.Error
            assertEquals(LocationUpdateErrorOrigin.SDK_CALLBACK, reading.origin)
            assertSame(failure, operation.failureCause())
        }

    @Test fun `cancellation reentering a synchronous register callback revokes before setup cleanup`() =
        runTest {
            val client = FakeClient()
            val values = mutableListOf<LocationUpdateReading>()
            val job = launch { operation(client).updates { true }.toList(values) }
            client.onRegister = { listener ->
                listener.onReading(sample(0))
                job.cancel()
                listener.onReading(LocationUpdateReading.Empty(0))
            }
            runCurrent()
            assertTrue(values.isEmpty())
            assertTrue(job.isCancelled)
            assertEquals(listOf("initialize", "register", "unregister", "binding", "release"), client.calls)
        }

    @Test fun `late gate exception cannot lend a successful permission lookup to a callback`() =
        runTest {
            var gateFailure: Exception? = null
            val client = FakeClient()
            val operation = operation(client)
            val values = mutableListOf<LocationUpdateReading>()
            val job =
                launch {
                    operation
                        .updates {
                            gateFailure?.let { throw it }
                            true
                        }.toList(values)
                }
            runCurrent()
            val failure = IllegalStateException("controlled gate")
            client.onPermission = { gateFailure = failure }
            client.listener!!.onReading(sample(0))
            runCurrent()
            assertTrue(values.isEmpty())
            assertTrue(job.isCompleted)
            assertSame(failure, operation.failureCause())
        }

    @Test fun `cancellation during terminal cleanup explicitly reports the first cleanup cause before join`() =
        runTest {
            val failure = IllegalStateException("controlled cleanup")
            val cancellation = CancellationException("controlled cleanup cancellation")
            val client = FakeClient().apply { unregisterFailure = failure }
            val operation = operation(client)
            val cleanup = mutableListOf<Throwable>()
            val result = async { operation.updates({ true }, {}, cleanup::add).toList() }
            runCurrent()
            client.onUnregister = { result.cancel(cancellation) }
            client.listener!!.onSdkError(Exception("controlled callback"), 0)
            runCurrent()
            assertTrue(runCatching { result.await() }.exceptionOrNull() is CancellationException)
            assertEquals(listOf(failure), cleanup)
            assertTrue(checkNotNull(operation.failureCause()).suppressed.any { it === failure })
            assertEquals(listOf("initialize", "register", "unregister", "binding", "release"), client.calls)
        }

    @Test fun `caller cancellation explicitly reports cleanup failures and ignores a reentrant late callback`() =
        runTest {
            val cleanup = IllegalStateException("controlled cleanup")
            val release = IllegalArgumentException("controlled release")
            val cancellation = CancellationException("controlled owner cancellation")
            val client =
                FakeClient().apply {
                    bindingFailure = cleanup
                    releaseFailure = release
                }
            val operation = operation(client)
            val outcomes = mutableListOf<Throwable>()
            val result = async { operation.updates({ true }, {}, outcomes::add).toList() }
            runCurrent()
            val late = client.listener!!
            client.onUnregister = { late.onReading(sample(0)) }
            result.cancel(cancellation)
            runCurrent()
            assertTrue(runCatching { result.await() }.exceptionOrNull() is CancellationException)
            assertEquals(listOf(cleanup), outcomes)
            assertTrue(cleanup.suppressed.any { it === release })
            assertEquals(1, client.unregisters)
        }

    @Test fun `revoked start during initialize never registers or acknowledges`() =
        runTest {
            var owner = true
            var acknowledgements = 0
            val client = FakeClient().apply { onInitialize = { owner = false } }
            val readings = operation(client).updates({ owner }, { acknowledgements++ }).toList()
            assertTrue(readings.isEmpty())
            assertEquals(0, acknowledgements)
            assertEquals(listOf("initialize", "binding", "release"), client.calls)
        }

    @Test fun `local registration return acknowledges before queued synchronous sample delivery`() =
        runTest {
            var acknowledged = false
            var startAllowed = true
            val readings = mutableListOf<LocationUpdateReading>()
            val client = FakeClient().apply { onRegister = { it.onReading(sample(0)) } }
            val stream =
                launch {
                    operation(client)
                        .updates({ acknowledged || startAllowed }, {
                            acknowledged = true
                            startAllowed = false
                        })
                        .collect {
                            assertTrue(acknowledged)
                            readings += it
                        }
                }
            runCurrent()
            assertTrue(acknowledged)
            assertEquals(1, readings.size)
            client.listener!!.onReading(sample(1))
            runCurrent()
            assertEquals(2, readings.size)
            stream.cancel()
            runCurrent()
            assertEquals(1, client.unregisters)
        }

    @Test fun `failed registration never acknowledges local handoff`() =
        runTest {
            var acknowledgements = 0
            val client = FakeClient().apply { registerFailure = IllegalStateException("controlled register") }
            val readings = operation(client).updates({ true }, { acknowledgements++ }).toList()
            assertEquals(0, acknowledgements)
            assertEquals(LocationUpdateErrorOrigin.ADAPTER, (readings.single() as LocationUpdateReading.Error).origin)
            assertEquals(1, client.unregisters)
        }

    private fun TestScope.operation(client: FakeClient): LocationUpdateOperation =
        LocationUpdateOperation(
            { client },
            StandardTestDispatcher(testScheduler),
            { testScheduler.currentTime },
            eventDispatcher = StandardTestDispatcher(testScheduler),
        )

    private fun sample(receipt: Long): LocationUpdateReading =
        LocationUpdateReading.Sample(LocationUpdateSample(0.0, 0.0, 0.0, 0f, 0f, -1, receipt))

    private class FakeClient : LocationUpdateClient {
        var supported = true
        var permission = true
        var permissionFailure: Exception? = null
        var initializeFailure: Exception? = null
        var registerFailure: Exception? = null
        var unregisterFailure: Exception? = null
        var bindingFailure: Exception? = null
        var releaseFailure: Exception? = null
        var onApi: () -> Unit = { }
        var onPermission: () -> Unit = { }
        var onInitialize: () -> Unit = { }
        var onRegister: (LocationUpdateClient.Listener) -> Unit = { }
        var onUnregister: () -> Unit = { }
        var listener: LocationUpdateClient.Listener? = null
        var unregistered: LocationUpdateClient.Listener? = null
        val calls = mutableListOf<String>()
        val unregisters: Int get() = calls.count { it == "unregister" }

        override fun isSupported(): Boolean {
            onApi()
            return supported
        }

        override fun hasPermission(): Boolean {
            onPermission()
            permissionFailure?.let { throw it }
            return permission
        }

        override fun initialize() {
            onApi()
            calls += "initialize"
            onInitialize()
            initializeFailure?.let { throw it }
        }

        override fun register(listener: LocationUpdateClient.Listener) {
            onApi()
            calls += "register"
            this.listener = listener
            onRegister(listener)
            registerFailure?.let { throw it }
        }

        override fun unregister(listener: LocationUpdateClient.Listener) {
            onApi()
            calls += "unregister"
            unregistered = listener
            onUnregister()
            unregisterFailure?.let { throw it }
        }

        override fun closeBinding() {
            onApi()
            calls += "binding"
            bindingFailure?.let { throw it }
        }

        override fun release() {
            onApi()
            calls += "release"
            releaseFailure?.let { throw it }
        }
    }
}
