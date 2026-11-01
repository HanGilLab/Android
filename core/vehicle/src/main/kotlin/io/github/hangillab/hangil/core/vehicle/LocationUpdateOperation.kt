package io.github.hangillab.hangil.core.vehicle

import io.github.hangillab.hangil.core.domain.LocationUpdateErrorOrigin
import io.github.hangillab.hangil.core.domain.LocationUpdateGateway
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/** Cold, foreground-owner stream. This class does not interpret canContinue as P or UX permission. */
internal class LocationUpdateOperation(
    private val clientFactory: () -> LocationUpdateClient,
    private val mainDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val idleMillis: Long = LocationUpdateGateway.NO_UPDATE_TIMEOUT_MILLIS,
    private val pollMillis: Long = 100,
    private val capacity: Int = 64,
    private val eventDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    @Volatile private var lastFailure: Throwable? = null

    init {
        require(idleMillis > 0 && pollMillis > 0 && capacity > 0)
    }

    internal fun failureCause(): Throwable? = lastFailure

    fun updates(canContinue: () -> Boolean): Flow<LocationUpdateReading> = updates(canContinue, {})

    /** onRegistered acknowledges the local SDK call return, not a remote registration ACK. */
    fun updates(
        canContinue: () -> Boolean,
        onRegistered: () -> Unit,
    ): Flow<LocationUpdateReading> = updates(canContinue, onRegistered, {})

    fun updates(
        canContinue: () -> Boolean,
        onRegistered: () -> Unit,
        onCleanupFailure: (Throwable) -> Unit,
    ): Flow<LocationUpdateReading> =
        callbackFlow {
            val callerJob = currentCoroutineContext()[Job]
            val run = Run(canContinue, onRegistered) { isActive }
            var cancellation: CancellationException? = null
            val watcher =
                launch {
                    while (isActive && !run.revoked.isCompleted) {
                        delay(pollMillis)
                        if (run.isClosed()) {
                            run.checkTerminalOwner()
                        } else if (run.checkAccess()) {
                            run.checkIdle()
                        }
                    }
                }
            try {
                withContext(mainDispatcher) { run.start() }
                while (!run.isClosed()) {
                    val reading = run.poll()
                    if (reading == null) {
                        select {
                            run.ended.onAwait { }
                            run.available.onReceive { }
                        }
                    } else if (run.checkAccess()) {
                        deliver(run, reading)
                    }
                }
                // Terminal delivery follows local cleanup, so cleanup-only failures cannot look successful.
                withContext(NonCancellable + mainDispatcher) { run.cleanup() }
                if (run.checkTerminalOwner()) {
                    run.terminal()?.let { reading ->
                        select {
                            run.revoked.onAwait { }
                            channel.onSend(reading) { }
                        }
                    }
                }
            } catch (failure: CancellationException) {
                cancellation = failure
                run.cancel(failure)
                throw failure
            } finally {
                // Keep the whole completion sequence non-cancellable. A dispatcher hop back from
                // cleanup alone can throw prompt cancellation before diagnostics are delivered.
                withContext(NonCancellable) {
                    run.revokeSink()
                    withContext(mainDispatcher) { run.cleanup() }
                    watcher.cancelAndJoin()
                    cancellation?.let { cancelled ->
                        run.cleanupFailures().forEach { failure ->
                            generateSequence<Throwable>(cancelled) { it.cause }.forEach { it.preserve(failure) }
                            try {
                                callerJob?.ensureActive()
                            } catch (original: CancellationException) {
                                generateSequence<Throwable>(original) { it.cause }.forEach { it.preserve(failure) }
                            }
                        }
                    }
                    // A channel/flowOn boundary need not preserve upstream exception identity.
                    // The callback completes before the producer joins, even after cancellation.
                    val cleanupFailures = run.cleanupFailures()
                    cleanupFailures.firstOrNull()?.let { first ->
                        cleanupFailures.drop(1).forEach { first.preserve(it) }
                        try {
                            onCleanupFailure(first)
                        } catch (notificationFailure: Throwable) {
                            first.preserve(notificationFailure)
                        }
                    }
                    lastFailure = run.failureCause()
                    channel.close()
                }
            }
        }.buffer(Channel.RENDEZVOUS).flowOn(eventDispatcher)

    private suspend fun ProducerScope<LocationUpdateReading>.deliver(
        run: Run,
        reading: LocationUpdateReading,
    ) {
        // An owner expiry or terminal error interrupts a send waiting for the downstream writer.
        select {
            run.ended.onAwait { }
            channel.onSend(reading) { }
        }
    }

    private fun Throwable.preserve(failure: Throwable) {
        if (failure !== this && suppressed.none { it === failure }) addSuppressed(failure)
    }

    private inner class Run(
        private val canContinue: () -> Boolean,
        private val onRegistered: () -> Unit,
        private val collectorActive: () -> Boolean,
    ) : LocationUpdateClient.Listener {
        private val lock = Any()
        private val queue = ArrayDeque<LocationUpdateReading>()
        val available = Channel<Unit>(Channel.CONFLATED)
        val ended = CompletableDeferred<Unit>()
        val revoked = CompletableDeferred<Unit>()

        @Volatile private var client: LocationUpdateClient? = null
        private var closed = false
        private var result: LocationUpdateReading? = null
        private var initializeAttempted = false
        private var registerAttempted = false
        private var cleanupStarted = false
        private var lastCallbackAt: Long? = null
        private var primaryFailure: Throwable? = null
        private val cleanupErrors = mutableListOf<Throwable>()

        fun start() {
            try {
                if (!ownerAllowed()) return
                val current = clientFactory().also { client = it }
                if (!checkAccess()) return
                val supported = current.isSupported()
                if (!checkAccess()) return
                if (!supported) {
                    finish(LocationUpdateReading.Unsupported(now()))
                    return
                }
                if (!beginInitialize()) return
                current.initialize()
                if (!checkAccess() || !beginRegister()) return
                current.register(this)
                if (!checkAccess()) return
                onRegistered()
                if (checkAccess()) synchronized(lock) { if (lastCallbackAt == null) lastCallbackAt = now() }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                onAdapterError(failure)
            }
        }

        private fun beginInitialize(): Boolean =
            synchronized(lock) {
                if (closed || !collectorActive()) false else true.also { initializeAttempted = true }
            }

        private fun beginRegister(): Boolean =
            synchronized(lock) {
                if (closed || !collectorActive()) false else true.also { registerAttempted = true }
            }

        fun isClosed(): Boolean = synchronized(lock) { closed }

        fun terminal(): LocationUpdateReading? = synchronized(lock) { result }

        fun poll(): LocationUpdateReading? = synchronized(lock) { if (closed) null else queue.removeFirstOrNull() }

        fun failureCause(): Throwable? = synchronized(lock) { primaryFailure }

        fun cleanupFailures(): List<Throwable> = synchronized(lock) { cleanupErrors.toList() }

        /** Recheck after the external permission call: it can revoke the opaque owner reentrantly. */
        fun checkAccess(): Boolean {
            if (isClosed() || !ownerAllowed()) return false
            val permission =
                try {
                    client?.hasPermission() ?: return false
                } catch (failure: Exception) {
                    synchronized(lock) { preserveFailure(failure) }
                    if (!ownerAllowed()) return false
                    onAdapterError(failure)
                    return false
                }
            if (!ownerAllowed() || isClosed()) return false
            if (!permission) {
                try {
                    finish(LocationUpdateReading.PermissionDenied(now()))
                } catch (failure: Exception) {
                    onAdapterError(failure)
                }
                return false
            }
            return !isClosed()
        }

        fun checkTerminalOwner(): Boolean {
            if (!ownerAllowed()) return false
            try {
                // Metadata about denied/unknown permission must remain reportable without a sample.
                client?.hasPermission()
            } catch (failure: Exception) {
                synchronized(lock) {
                    val first = primaryFailure == null
                    preserveFailure(failure)
                    if (first) replaceWithAdapterError(failure)
                }
            }
            return ownerAllowed()
        }

        private fun ownerAllowed(): Boolean {
            if (!collectorActive() || revoked.isCompleted) {
                revokeOwner()
                return false
            }
            val allowed =
                try {
                    canContinue()
                } catch (failure: Exception) {
                    synchronized(lock) { preserveFailure(failure) }
                    false
                }
            if (!allowed || !collectorActive() || revoked.isCompleted) {
                revokeOwner()
                return false
            }
            return true
        }

        override fun onReading(reading: LocationUpdateReading) {
            if (!checkAccess()) return
            if (reading !is LocationUpdateReading.Sample && reading !is LocationUpdateReading.Empty) {
                onAdapterError(IllegalArgumentException("Unexpected SDK update callback kind"))
                return
            }
            try {
                synchronized(lock) {
                    if (closed || !collectorActive()) return
                    lastCallbackAt = now()
                    if (queue.size >= capacity) {
                        val failure = IllegalStateException("Location update buffer overflow")
                        preserveFailure(failure)
                        closeLocked(error(failure, LocationUpdateErrorOrigin.ADAPTER))
                    } else {
                        queue.addLast(reading)
                    }
                }
                if (isClosed()) signalClosed() else available.trySend(Unit)
            } catch (failure: Exception) {
                onAdapterError(failure)
            }
        }

        override fun onSdkError(
            failure: Exception,
            receivedAtElapsedRealtimeMillis: Long,
        ) {
            if (!metadataAllowed(failure)) return
            synchronized(lock) {
                if (closed) return
                val reading =
                    try {
                        LocationUpdateReading.Error(
                            LocationUpdateErrorOrigin.SDK_CALLBACK,
                            failure.javaClass.name,
                            receivedAtElapsedRealtimeMillis,
                        )
                    } catch (receiptFailure: Exception) {
                        preserveFailure(receiptFailure)
                        null
                    }
                closeLocked(reading)
            }
            signalClosed()
        }

        override fun onAdapterError(failure: Exception) {
            if (!metadataAllowed(failure)) return
            synchronized(lock) {
                if (closed) return
                val reading =
                    try {
                        error(failure, LocationUpdateErrorOrigin.ADAPTER)
                    } catch (clockFailure: Exception) {
                        preserveFailure(clockFailure)
                        null
                    }
                closeLocked(reading)
            }
            signalClosed()
        }

        private fun metadataAllowed(failure: Exception): Boolean {
            if (isClosed() || !ownerAllowed()) return false
            synchronized(lock) { preserveFailure(failure) }
            try {
                // An actual SDK error remains SDK_CALLBACK even if permission is now false.
                client?.hasPermission()
            } catch (permissionFailure: Exception) {
                synchronized(lock) { preserveFailure(permissionFailure) }
            }
            return ownerAllowed() && !isClosed()
        }

        fun checkIdle() {
            try {
                synchronized(lock) {
                    if (closed) return
                    val previous = lastCallbackAt ?: return
                    val observed = now()
                    if (observed - previous >= idleMillis) closeLocked(LocationUpdateReading.NoUpdate(observed))
                }
                if (isClosed()) signalClosed()
            } catch (failure: Exception) {
                onAdapterError(failure)
            }
        }

        private fun finish(reading: LocationUpdateReading) {
            synchronized(lock) { if (!closed) closeLocked(reading) }
            signalClosed()
        }

        fun cancel(failure: CancellationException) {
            synchronized(lock) { preserveFailure(failure) }
            revokeOwner()
        }

        private fun revokeOwner() {
            synchronized(lock) {
                result = null
                closeLocked(null)
            }
            revoked.complete(Unit)
            signalClosed()
        }

        fun revokeSink() {
            synchronized(lock) { if (!closed) closeLocked(null) }
            signalClosed()
        }

        private fun closeLocked(terminal: LocationUpdateReading?) {
            if (closed) return
            closed = true
            result = terminal
            queue.clear()
        }

        private fun signalClosed() {
            // Resume coroutines after releasing the guard; no SDK cleanup or writer can reenter under it.
            ended.complete(Unit)
        }

        /** Called only after setup unwinds, on the API dispatcher, even if the collector was cancelled. */
        fun cleanup() {
            val current =
                synchronized(lock) {
                    if (cleanupStarted) return
                    cleanupStarted = true
                    if (!closed) closeLocked(null)
                    client
                }
            signalClosed()
            if (current == null) return
            if (registerAttempted) cleanupStep { current.unregister(this) }
            cleanupStep { current.closeBinding() }
            if (initializeAttempted) cleanupStep { current.release() }
        }

        private fun cleanupStep(action: () -> Unit) {
            try {
                action()
            } catch (failure: Exception) {
                synchronized(lock) {
                    cleanupErrors += failure
                    val first = primaryFailure == null
                    preserveFailure(failure)
                    if (first && !revoked.isCompleted) replaceWithAdapterError(failure)
                }
            }
        }

        private fun replaceWithAdapterError(failure: Throwable) {
            result =
                try {
                    error(failure, LocationUpdateErrorOrigin.ADAPTER)
                } catch (clockFailure: Exception) {
                    preserveFailure(clockFailure)
                    null
                }
        }

        private fun error(
            failure: Throwable,
            origin: LocationUpdateErrorOrigin,
        ): LocationUpdateReading.Error = LocationUpdateReading.Error(origin, failure.javaClass.name, now())

        private fun now(): Long = clock().also { require(it >= 0) { "Invalid location update receipt time" } }

        private fun preserveFailure(failure: Throwable) {
            val first = primaryFailure
            if (first == null) primaryFailure = failure else first.preserve(failure)
        }
    }
}
