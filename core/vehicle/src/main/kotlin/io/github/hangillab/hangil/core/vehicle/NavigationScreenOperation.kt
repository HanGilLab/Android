package io.github.hangillab.hangil.core.vehicle

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.NavigationScreenResult
import io.github.hangillab.hangil.core.domain.PracticeNavigationRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class NavigationScreenOperation(
    private val factory: () -> NavigationClient,
    private val main: CoroutineDispatcher,
    private val timeoutMillis: Long = 20_000,
    private val routeFactory: () -> NavigationClient = factory,
) {
    private val mutex = Mutex()
    private val currentConnection = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connection = currentConnection.asStateFlow()

    suspend fun request(canDispatch: () -> Boolean): NavigationScreenResult =
        execute(factory, NavigationClient::hasPermission, canDispatch) { client, _ ->
            client.requestForeground()
            NavigationScreenResult.REQUESTED
        }

    suspend fun requestRoute(
        route: PracticeNavigationRoute,
        canDispatch: () -> Boolean,
    ): NavigationScreenResult =
        execute(routeFactory, NavigationClient::hasRoutePermissions, canDispatch) { client, allowed ->
            client.requestRoute(route, allowed, timeoutMillis)
        }

    private suspend fun execute(
        createClient: () -> NavigationClient,
        hasPermission: (NavigationClient) -> Boolean,
        canDispatch: () -> Boolean,
        dispatch: suspend (NavigationClient, () -> Boolean) -> NavigationScreenResult,
    ): NavigationScreenResult {
        // A competing call is rejected, not queued for a later host or parking lifetime.
        if (!mutex.tryLock()) return NavigationScreenResult.BLOCKED
        val caller = currentCoroutineContext()[Job]
        var cleanupFailure: Exception? = null
        try {
            return withContext(main) {
                var client: NavigationClient? = null
                var result = NavigationScreenResult.ERROR

                fun allowed(): Boolean = caller?.isActive == true && runCatching(canDispatch).getOrDefault(false)
                try {
                    if (!allowed()) return@withContext NavigationScreenResult.BLOCKED
                    val current = createClient().also { client = it }
                    result =
                        when {
                            !current.isSupported() -> NavigationScreenResult.UNSUPPORTED
                            !hasPermission(current) -> NavigationScreenResult.PERMISSION_DENIED
                            else -> {
                                currentConnection.value = ConnectionState.CONNECTING
                                val connected =
                                    withTimeoutOrNull(timeoutMillis) {
                                        awaitConnection(current)
                                        true
                                    }
                                when {
                                    connected == null -> NavigationScreenResult.TIMED_OUT
                                    !allowed() -> NavigationScreenResult.BLOCKED
                                    !hasPermission(current) -> NavigationScreenResult.PERMISSION_DENIED
                                    else -> {
                                        currentCoroutineContext().ensureActive()
                                        currentConnection.value = ConnectionState.CONNECTED
                                        dispatch(current, ::allowed)
                                    }
                                }
                            }
                        }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: SecurityException) {
                    result = NavigationScreenResult.PERMISSION_DENIED
                } catch (_: Exception) {
                    result = NavigationScreenResult.ERROR
                } finally {
                    withContext(NonCancellable + main) {
                        try {
                            client?.close()
                        } catch (failure: Exception) {
                            cleanupFailure = failure
                            result =
                                when (result) {
                                    NavigationScreenResult.GUIDANCE_STARTED,
                                    NavigationScreenResult.GUIDANCE_UNCONFIRMED,
                                    -> NavigationScreenResult.GUIDANCE_UNCONFIRMED
                                    else -> NavigationScreenResult.ERROR
                                }
                        }
                        currentConnection.value = ConnectionState.DISCONNECTED
                    }
                }
                result
            }
        } catch (cancelled: CancellationException) {
            cleanupFailure?.let { failure ->
                if (cancelled !== failure && cancelled.suppressed.none { it === failure }) cancelled.addSuppressed(failure)
                try {
                    caller?.ensureActive()
                } catch (original: CancellationException) {
                    if (original !== failure && original.suppressed.none { it === failure }) original.addSuppressed(failure)
                }
            }
            throw cancelled
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun awaitConnection(client: NavigationClient): Unit =
        suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            continuation.invokeOnCancellation {
                finished.set(true)
                client.cancelPending()
            }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            try {
                client.connect(
                    { if (finished.compareAndSet(false, true)) continuation.resume(Unit) },
                    { cause -> if (finished.compareAndSet(false, true)) continuation.resumeWithException(cause) },
                )
            } catch (cancelled: CancellationException) {
                continuation.cancel(cancelled)
            } catch (failure: Exception) {
                if (finished.compareAndSet(false, true)) continuation.resumeWithException(failure)
            }
        }
}
