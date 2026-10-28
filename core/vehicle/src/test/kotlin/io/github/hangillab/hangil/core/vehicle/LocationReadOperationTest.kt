package io.github.hangillab.hangil.core.vehicle

import io.github.hangillab.hangil.core.domain.LocationReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocationReadOperationTest {
    @Test fun `unsupported and denied requests never start SDK reads`() =
        runTest {
            val unsupported = FakeClient(supported = false)
            val denied = FakeClient(permission = false)
            val dispatcher = StandardTestDispatcher(testScheduler)
            assertEquals(LocationReading.Unsupported, LocationReadOperation({ unsupported }, dispatcher).readLastKnown())
            assertEquals(LocationReading.PermissionDenied, LocationReadOperation({ denied }, dispatcher).readLastKnown())
            assertEquals(0, unsupported.reads)
            assertEquals(0, denied.reads)
            assertEquals(1, unsupported.closes)
            assertEquals(1, denied.closes)
        }

    @Test fun `twenty second timeout clears pending sink and closes once`() =
        runTest {
            val client = FakeClient()
            val result = async { LocationReadOperation({ client }, StandardTestDispatcher(testScheduler)).readLastKnown() }
            runCurrent()
            assertEquals(1, client.reads)
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(LocationReading.TimedOut, result.await())
            assertNull(client.sink)
            assertEquals(1, client.closes)
            assertEquals(1, client.cancellations)
        }

    @Test fun `external cancellation propagates and late callbacks cannot complete a request`() =
        runTest {
            val client = FakeClient()
            val result = async { LocationReadOperation({ client }, StandardTestDispatcher(testScheduler)).readLastKnown() }
            runCurrent()
            val late = client.sink!!
            result.cancel(CancellationException("screen left"))
            runCurrent()
            late(LocationReading.Empty)
            assertTrue(result.isCancelled)
            assertNull(client.sink)
            assertEquals(1, client.closes)
        }

    @Test fun `empty and generic SDK failures are distinct from permission denial`() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val empty = FakeClient(automaticResult = LocationReading.Empty)
            val generic = FakeClient(readFailure = Exception("permission denied unsupported empty"))
            val security = FakeClient(readFailure = SecurityException())
            assertEquals(LocationReading.Empty, LocationReadOperation({ empty }, dispatcher).readLastKnown())
            assertEquals(LocationReading.Error, LocationReadOperation({ generic }, dispatcher).readLastKnown())
            assertEquals(LocationReading.PermissionDenied, LocationReadOperation({ security }, dispatcher).readLastKnown())
            assertEquals(1, empty.closes)
            assertEquals(1, generic.closes)
            assertEquals(1, security.closes)
        }

    @Test fun `cleanup failure cannot be reported as a successfully completed reading`() =
        runTest {
            val client = FakeClient(automaticResult = LocationReading.Empty, closeFailure = IllegalStateException("cleanup failed"))
            val result = LocationReadOperation({ client }, StandardTestDispatcher(testScheduler)).readLastKnown()
            assertEquals(LocationReading.Error, result)
            assertEquals(1, client.closes)
        }

    @Test fun `cancellation during cleanup preserves the cleanup failure`() =
        runTest {
            val cleanupFailure = IllegalStateException("cleanup failed")
            val cancellation = CancellationException("screen left during cleanup")
            lateinit var result: Deferred<LocationReading>
            val client =
                FakeClient(
                    automaticResult = LocationReading.Empty,
                    closeFailure = cleanupFailure,
                    onClose = { result.cancel(cancellation) },
                )
            result = async { LocationReadOperation({ client }, StandardTestDispatcher(testScheduler)).readLastKnown() }
            runCurrent()
            val failure = runCatching { result.await() }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertTrue(cancellation.suppressed.any { it === cleanupFailure })
            // Coroutine stacktrace recovery may wrap the original cancellation as a cause.
            assertTrue(generateSequence(failure) { it.cause }.any { cause -> cause.suppressed.any { it === cleanupFailure } })
        }

    @Test fun `cleanup failure after cancelling a pending read survives on the caller cancellation`() =
        runTest {
            val cleanupFailure = IllegalStateException("cleanup failed")
            val cancellation = CancellationException("screen left during read")
            val client = FakeClient(closeFailure = cleanupFailure)
            val result = async { LocationReadOperation({ client }, StandardTestDispatcher(testScheduler)).readLastKnown() }
            runCurrent()
            result.cancel(cancellation)
            runCurrent()
            val failure = runCatching { result.await() }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertTrue(cancellation.suppressed.any { it === cleanupFailure })
            assertTrue(generateSequence(failure) { it.cause }.any { cause -> cause.suppressed.any { it === cleanupFailure } })
            assertEquals(1, client.closes)
        }

    @Test fun `mutex prevents overlapping SDK instances and duplicate completion`() =
        runTest {
            val clients = mutableListOf<FakeClient>()
            val reader =
                LocationReadOperation(
                    { FakeClient().also(clients::add) },
                    StandardTestDispatcher(testScheduler),
                )
            val first = async { reader.readLastKnown() }
            val second = async { reader.readLastKnown() }
            runCurrent()
            assertEquals(1, clients.size)
            assertFalse(second.isCompleted)
            val callback = clients.single().sink!!
            callback(LocationReading.Empty)
            callback(LocationReading.Error)
            runCurrent()
            assertEquals(LocationReading.Empty, first.await())
            assertEquals(1, clients.first().closes)
            assertEquals(2, clients.size)
            clients.last().sink!!(LocationReading.Empty)
            runCurrent()
            assertEquals(LocationReading.Empty, second.await())
            assertEquals(1, clients.last().closes)
        }

    private class FakeClient(
        private val supported: Boolean = true,
        private val permission: Boolean = true,
        private val automaticResult: LocationReading? = null,
        private val readFailure: Exception? = null,
        private val closeFailure: Exception? = null,
        private val onClose: () -> Unit = {},
    ) : LocationClient {
        var reads = 0
        var closes = 0
        var cancellations = 0
        var sink: ((LocationReading) -> Unit)? = null

        override fun isSupported(): Boolean = supported

        override fun hasPermission(): Boolean = permission

        override fun readLastKnown(onResult: (LocationReading) -> Unit) {
            reads++
            sink = onResult
            readFailure?.let { throw it }
            automaticResult?.let(onResult)
        }

        override fun cancelPending() {
            cancellations++
            sink = null
        }

        override fun close() {
            closes++
            sink = null
            onClose()
            closeFailure?.let { throw it }
        }
    }
}
