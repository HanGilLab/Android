package io.github.hangillab.hangil.core.vehicle

import io.github.hangillab.hangil.core.domain.LocationReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal class LocationReadOperation(
    private val clientFactory: () -> LocationClient,
    private val mainDispatcher: CoroutineDispatcher,
    private val timeoutMillis: Long = 20_000,
) {
    private val requestMutex = Mutex()

    suspend fun readLastKnown(): LocationReading =
        requestMutex.withLock {
            val callerJob = currentCoroutineContext()[Job]
            var cleanupFailure: Exception? = null
            try {
                withContext(mainDispatcher) {
                    var client: LocationClient? = null
                    var result: LocationReading = LocationReading.Error
                    try {
                        val current = clientFactory().also { client = it }
                        result =
                            when {
                                !current.isSupported() -> LocationReading.Unsupported
                                !current.hasPermission() -> LocationReading.PermissionDenied
                                else -> withTimeoutOrNull(timeoutMillis) { awaitLocation(current) } ?: LocationReading.TimedOut
                            }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: SecurityException) {
                        result = LocationReading.PermissionDenied
                    } catch (_: Exception) {
                        result = LocationReading.Error
                    } finally {
                        withContext(NonCancellable + mainDispatcher) {
                            try {
                                client?.close()
                            } catch (failure: Exception) {
                                // Preserve this outside withContext too: its return can race with caller cancellation.
                                cleanupFailure = failure
                                result = LocationReading.Error
                            }
                        }
                    }
                    result
                }
            } catch (cancelled: CancellationException) {
                cleanupFailure?.let { failure ->
                    cancelled.preserveCleanupFailure(failure)
                    // withContext can throw a recovered copy. Deferred.await may instead expose the
                    // caller Job's earlier cancellation cause, so preserve the cleanup on that cause too.
                    try {
                        callerJob?.ensureActive()
                    } catch (callerCancellation: CancellationException) {
                        callerCancellation.preserveCleanupFailure(failure)
                    }
                }
                throw cancelled
            }
        }

    private fun CancellationException.preserveCleanupFailure(failure: Exception) {
        if (failure !== this && suppressed.none { it === failure }) addSuppressed(failure)
    }

    private suspend fun awaitLocation(client: LocationClient): LocationReading =
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            val complete: (LocationReading) -> Unit = { reading ->
                if (completed.compareAndSet(false, true)) continuation.resume(reading)
            }
            continuation.invokeOnCancellation {
                completed.set(true)
                client.cancelPending()
            }
            if (!continuation.isActive) return@suspendCancellableCoroutine
            try {
                client.readLastKnown(complete)
            } catch (cancelled: CancellationException) {
                continuation.cancel(cancelled)
            } catch (_: SecurityException) {
                complete(LocationReading.PermissionDenied)
            } catch (_: Exception) {
                complete(LocationReading.Error)
            }
        }
}
