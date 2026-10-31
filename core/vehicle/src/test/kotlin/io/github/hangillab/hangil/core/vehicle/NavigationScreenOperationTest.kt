package io.github.hangillab.hangil.core.vehicle

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.NavigationScreenResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NavigationScreenOperationTest {
    @Test fun noOwnershipDoesNotEvenCreateAClient() =
        runTest {
            var factories = 0
            val operation =
                NavigationScreenOperation({
                    factories++
                    Client()
                }, StandardTestDispatcher(testScheduler))
            assertEquals(NavigationScreenResult.BLOCKED, operation.request { false })
            assertEquals(0, factories)
        }

    @Test fun throwingOwnershipFailsClosed() =
        runTest {
            val operation = NavigationScreenOperation({ error("Must not initialize") }, StandardTestDispatcher(testScheduler))
            assertEquals(NavigationScreenResult.BLOCKED, operation.request { error("Expired host") })
        }

    @Test fun connectedCallIsOnlyRequestedAndAlwaysCloses() =
        runTest {
            val client = Client()
            val operation = NavigationScreenOperation({ client }, StandardTestDispatcher(testScheduler))
            val result = async { operation.request { true } }
            runCurrent()
            assertEquals(ConnectionState.CONNECTING, operation.connection.value)
            assertEquals(0, client.requests)
            client.connected()
            runCurrent()
            assertEquals(NavigationScreenResult.REQUESTED, result.await())
            assertEquals(1, client.requests)
            assertEquals(1, client.closes)
            assertEquals(ConnectionState.DISCONNECTED, operation.connection.value)
            // A duplicate or late callback cannot submit another request.
            client.connected()
            client.failed(IllegalStateException())
            runCurrent()
            assertEquals(1, client.requests)
        }

    @Test fun denialWhileConnectingPreventsDispatchEvenAfterConnection() =
        runTest {
            val client = Client()
            var allowed = true
            val operation = NavigationScreenOperation({ client }, StandardTestDispatcher(testScheduler))
            val result = async { operation.request { allowed } }
            runCurrent()
            allowed = false
            client.connected()
            runCurrent()
            assertEquals(NavigationScreenResult.BLOCKED, result.await())
            assertEquals(0, client.requests)
            assertEquals(1, client.closes)
        }

    @Test fun permissionLossAfterConnectIsNotARequest() =
        runTest {
            val client = Client()
            val operation = NavigationScreenOperation({ client }, StandardTestDispatcher(testScheduler))
            val result = async { operation.request { true } }
            runCurrent()
            client.permission = false
            client.connected()
            runCurrent()
            assertEquals(NavigationScreenResult.PERMISSION_DENIED, result.await())
            assertEquals(0, client.requests)
        }

    @Test fun unsupportedDoesNotInitialize() =
        runTest {
            val client = Client().apply { supported = false }
            val operation = NavigationScreenOperation({ client }, StandardTestDispatcher(testScheduler))
            assertEquals(NavigationScreenResult.UNSUPPORTED, operation.request { true })
            assertEquals(0, client.connects)
            assertEquals(1, client.closes)
        }

    @Test fun missingPermissionDoesNotInitialize() =
        runTest {
            val client = Client().apply { permission = false }
            val operation = NavigationScreenOperation({ client }, StandardTestDispatcher(testScheduler))
            assertEquals(NavigationScreenResult.PERMISSION_DENIED, operation.request { true })
            assertEquals(0, client.connects)
            assertEquals(1, client.closes)
        }

    @Test fun timeoutRetiresLateConnectionWithoutAutomaticRetry() =
        runTest {
            val client = Client()
            val operation = NavigationScreenOperation({ client }, StandardTestDispatcher(testScheduler), 100)
            val result = async { operation.request { true } }
            runCurrent()
            advanceTimeBy(101)
            runCurrent()
            assertEquals(NavigationScreenResult.TIMED_OUT, result.await())
            assertTrue(client.cancelled)
            assertEquals(1, client.closes)
            client.connected()
            runCurrent()
            assertEquals(0, client.requests)
        }

    @Test fun callerCancellationClosesAndRejectsLateSuccess() =
        runTest {
            val client = Client()
            val operation = NavigationScreenOperation({ client }, StandardTestDispatcher(testScheduler))
            val result = async { operation.request { true } }
            runCurrent()
            result.cancelAndJoin()
            client.connected()
            runCurrent()
            assertEquals(0, client.requests)
            assertTrue(client.cancelled)
            assertEquals(1, client.closes)
            assertEquals(ConnectionState.DISCONNECTED, operation.connection.value)
        }

    @Test fun competingRequestIsRejectedRatherThanQueued() =
        runTest {
            val clients = mutableListOf<Client>()
            val operation = NavigationScreenOperation({ Client().also(clients::add) }, StandardTestDispatcher(testScheduler))
            val first = async { operation.request { true } }
            runCurrent()
            assertEquals(NavigationScreenResult.BLOCKED, operation.request { true })
            assertEquals(1, clients.size)
            first.cancelAndJoin()
            val fresh = async { operation.request { true } }
            runCurrent()
            clients[0].connected()
            runCurrent()
            assertFalse(fresh.isCompleted)
            clients[1].connected()
            runCurrent()
            assertEquals(NavigationScreenResult.REQUESTED, fresh.await())
            assertEquals(0, clients[0].requests)
            assertEquals(1, clients[1].requests)
        }

    @Test fun initializationFailureAndSecurityFailureStayDistinct() =
        runTest {
            for ((cause, expected) in listOf(
                IllegalStateException() to NavigationScreenResult.ERROR,
                SecurityException() to NavigationScreenResult.PERMISSION_DENIED,
            )) {
                val client = Client()
                val operation = NavigationScreenOperation({ client }, StandardTestDispatcher(testScheduler))
                val result = async { operation.request { true } }
                runCurrent()
                client.failed(cause)
                runCurrent()
                assertEquals(expected, result.await())
                assertEquals(0, client.requests)
                assertEquals(1, client.closes)
            }
        }

    @Test fun factoryFailureIsAnErrorWithoutSuccessDefault() =
        runTest {
            val operation = NavigationScreenOperation({ error("Unavailable factory") }, StandardTestDispatcher(testScheduler))
            assertEquals(NavigationScreenResult.ERROR, operation.request { true })
        }

    @Test fun synchronousConnectExceptionIsObservedAndClosed() =
        runTest {
            val client = Client().apply { connectFailure = SecurityException() }
            val operation = NavigationScreenOperation({ client }, StandardTestDispatcher(testScheduler))
            assertEquals(NavigationScreenResult.PERMISSION_DENIED, operation.request { true })
            assertEquals(0, client.requests)
            assertEquals(1, client.closes)
        }

    @Test fun requestExceptionDoesNotRetryOrPretendNothingWasSent() =
        runTest {
            val client = Client().apply { requestFailure = IllegalStateException() }
            val operation = NavigationScreenOperation({ client }, StandardTestDispatcher(testScheduler))
            val result = async { operation.request { true } }
            runCurrent()
            client.connected()
            runCurrent()
            assertEquals(NavigationScreenResult.ERROR, result.await())
            assertEquals(1, client.requests)
            assertEquals(1, client.closes)
        }

    @Test fun cleanupFailureCannotBecomeConfirmedRequest() =
        runTest {
            val client = Client().apply { closeFailure = IllegalStateException() }
            val operation = NavigationScreenOperation({ client }, StandardTestDispatcher(testScheduler))
            val result = async { operation.request { true } }
            runCurrent()
            client.connected()
            runCurrent()
            assertEquals(NavigationScreenResult.ERROR, result.await())
            assertEquals(1, client.requests)
            assertEquals(ConnectionState.DISCONNECTED, operation.connection.value)
        }

    @Test fun cleanupFailureIsPreservedOnOriginalCancellation() =
        runTest {
            val cleanup = IllegalStateException("close")
            val client = Client().apply { closeFailure = cleanup }
            val operation = NavigationScreenOperation({ client }, StandardTestDispatcher(testScheduler))
            val result = async { operation.request { true } }
            runCurrent()
            val original = CancellationException("caller")
            result.cancel(original)
            result.join()
            assertTrue(original.suppressed.contains(cleanup))
            assertEquals(0, client.requests)
            assertEquals(1, client.closes)
        }

    private class Client : NavigationClient {
        var supported = true
        var permission = true
        var connects = 0
        var requests = 0
        var closes = 0
        var cancelled = false
        var connectFailure: Exception? = null
        var requestFailure: Exception? = null
        var closeFailure: Exception? = null
        lateinit var connected: () -> Unit
        lateinit var failed: (Throwable) -> Unit

        override fun isSupported() = supported

        override fun hasPermission() = permission

        override fun connect(
            onConnected: () -> Unit,
            onFailed: (Throwable) -> Unit,
        ) {
            connects++
            connectFailure?.let { throw it }
            connected = onConnected
            failed = onFailed
        }

        override fun requestForeground() {
            requests++
            requestFailure?.let { throw it }
        }

        override fun cancelPending() {
            cancelled = true
        }

        override fun close() {
            closes++
            cancelled = true
            closeFailure?.let { throw it }
        }
    }
}
