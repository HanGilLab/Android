package io.github.hangillab.hangil.core.vehicle

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Collections

/**
 * Debug-only, single-use registration probe. A return from register/unregister is not a remote
 * connection/delivery/stop acknowledgement. The time/event bound cannot interrupt a blocking SDK
 * call. Callbacks and canContinue may run on different threads and must not perform UI work.
 * No coordinates, SDK timestamp, confidence or exception messages leave this API.
 */
class FusedLocationUpdateProbe internal constructor(
    private val driverFactory: () -> Driver,
    private val canContinue: () -> Boolean,
    onEvent: (Event) -> Unit,
    private val clock: () -> Long,
    private val scheduler: Scheduler,
    private val timeoutMillis: Long = MAX_TIMEOUT_MILLIS,
) {
    constructor(
        context: Context,
        canContinue: () -> Boolean,
        onEvent: (Event) -> Unit,
        timeoutMillis: Long = MAX_TIMEOUT_MILLIS,
    ) : this({ SdkDriver(context) }, canContinue, onEvent, SystemClock::elapsedRealtime, AndroidScheduler(), timeoutMillis)

    enum class Kind { LOCATION_CALLBACK, EMPTY_CALLBACK, SDK_ERROR }

    enum class CallbackSource { SDK_UPDATE }

    enum class Termination {
        EXPLICIT_CLOSE,
        CANCELLED,
        GATE_BLOCKED,
        PERMISSION_DENIED,
        UNSUPPORTED,
        TIMED_OUT,
        SDK_ERROR,
        SETUP_ERROR,
        OBSERVER_ERROR,
        EVENT_LIMIT,
    }

    enum class Stage {
        DRIVER_CREATION,
        SUPPORT_CHECK,
        PERMISSION_CHECK,
        GATE_CHECK,
        INITIALIZE,
        REGISTER,
        SDK_CALLBACK,
        OBSERVER,
        UNREGISTER,
        BINDING_CLOSE,
        RELEASE,
        SCHEDULER,
        TIMER_CANCEL,
    }

    data class Metadata(
        val source: CallbackSource,
        val available: Boolean,
        val validCoordinates: Boolean?,
        val appReceiptElapsedRealtimeMillis: Long,
    )

    data class Event(
        val kind: Kind,
        val metadata: Metadata,
        val errorClassName: String? = null,
    )

    data class Failure(
        val stage: Stage,
        val exceptionClassName: String,
        val primary: Boolean,
    )

    data class Snapshot(
        val started: Boolean,
        val closed: Boolean,
        val termination: Termination?,
        /** All required cleanup calls finished; inspect failures/returned flags for their outcomes. */
        val cleanupComplete: Boolean,
        val supported: Boolean?,
        val permissionGranted: Boolean?,
        val initializeAttempted: Boolean,
        val initializeReturned: Boolean,
        val registerAttempted: Boolean,
        val registerReturned: Boolean,
        val unregisterAttempted: Boolean,
        val unregisterReturned: Boolean,
        val bindingCloseAttempted: Boolean,
        val bindingCloseReturned: Boolean,
        val releaseRequired: Boolean,
        val releaseAttempted: Boolean,
        val releaseReturned: Boolean,
        val acceptedCallbackCount: Int,
        val ignoredCallbackCount: Int,
        val events: List<Event>,
        val failures: List<Failure>,
    )

    internal interface Listener {
        /** null is a real null callback; Boolean describes structure only, never accuracy. */
        fun onChanged(validCoordinates: Boolean?)

        fun onError(failure: Exception)
    }

    internal interface Driver {
        fun isSupported(): Boolean

        fun hasPermission(): Boolean

        fun initialize()

        fun register(listener: Listener)

        fun unregister(listener: Listener)

        fun closeBinding()

        fun release()
    }

    internal interface Scheduler {
        /** Enqueue only; implementations must not execute the action inline from schedule. */
        fun schedule(
            delayMillis: Long,
            action: () -> Unit,
        ): Cancellation
    }

    internal fun interface Cancellation {
        fun cancel()
    }

    private val lock = Any()
    private var sink: ((Event) -> Unit)? = onEvent
    private var driver: Driver? = null
    private var started = false
    private var startedAt = 0L
    private var setupInProgress = false
    private var closed = false
    private var termination: Termination? = null
    private var cleanupStarted = false
    private var cleanupComplete = false
    private var scheduled: Cancellation? = null
    private var supported: Boolean? = null
    private var permissionGranted: Boolean? = null
    private var initializeAttempted = false
    private var initializeReturned = false
    private var registerAttempted = false
    private var registerReturned = false
    private var unregisterAttempted = false
    private var unregisterReturned = false
    private var bindingCloseAttempted = false
    private var bindingCloseReturned = false
    private var releaseAttempted = false
    private var releaseReturned = false
    private var ignoredCallbacks = 0
    private val events = mutableListOf<Event>()
    private val failures = mutableListOf<Failure>()
    private var primaryFailure: Throwable? = null
    private val listener =
        object : Listener {
            override fun onChanged(validCoordinates: Boolean?) = receive(validCoordinates, null)

            override fun onError(failure: Exception) = receive(null, failure)
        }

    init {
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS)
    }

    /** Single-use and idempotent; repeated starts never register another listener. */
    fun start(): Snapshot {
        val begin =
            synchronized(lock) {
                if (started || closed) {
                    false
                } else {
                    started = true
                    setupInProgress = true
                    startedAt = clock()
                    true
                }
            }
        if (!begin) return snapshot()
        var stage = Stage.SCHEDULER
        try {
            scheduleTick()
            if (checkAccess()) {
                stage = Stage.DRIVER_CREATION
                val created = driverFactory()
                synchronized(lock) { driver = created }
                if (checkAccess()) {
                    stage = Stage.SUPPORT_CHECK
                    val actual = created.isSupported()
                    synchronized(lock) { supported = actual }
                    if (!actual) {
                        terminate(Termination.UNSUPPORTED)
                    } else if (checkAccess()) {
                        stage = Stage.INITIALIZE
                        synchronized(lock) { initializeAttempted = true }
                        created.initialize()
                        synchronized(lock) { initializeReturned = true }
                        if (checkAccess()) {
                            stage = Stage.REGISTER
                            synchronized(lock) { registerAttempted = true }
                            created.register(listener)
                            synchronized(lock) { registerReturned = true }
                            checkAccess()
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            synchronized(lock) {
                preserve(stage, failure)
                markClosed(Termination.SETUP_ERROR)
            }
        } finally {
            synchronized(lock) { setupInProgress = false }
            drainCleanup()
        }
        return snapshot()
    }

    /** Revoke the sink before touching SDK or platform cleanup. Cleanup may wait for setup unwind. */
    fun close(): Snapshot {
        terminate(Termination.EXPLICIT_CLOSE)
        return snapshot()
    }

    fun cancel(): Snapshot {
        terminate(Termination.CANCELLED)
        return snapshot()
    }

    fun snapshot(): Snapshot =
        synchronized(lock) {
            Snapshot(
                started,
                closed,
                termination,
                cleanupComplete,
                supported,
                permissionGranted,
                initializeAttempted,
                initializeReturned,
                registerAttempted,
                registerReturned,
                unregisterAttempted,
                unregisterReturned,
                bindingCloseAttempted,
                bindingCloseReturned,
                initializeAttempted,
                releaseAttempted,
                releaseReturned,
                events.size,
                ignoredCallbacks,
                Collections.unmodifiableList(events.toList()),
                Collections.unmodifiableList(failures.toList()),
            )
        }

    /** Controlled tests only. Never print its message or expose it to instrumentation/JUnit logs. */
    internal fun failureCause(): Throwable? = synchronized(lock) { primaryFailure }

    private fun checkAccess(): Boolean {
        val allowed =
            synchronized(lock) {
                if (closed) {
                    false
                } else if (clock() - startedAt >= timeoutMillis) {
                    markClosed(Termination.TIMED_OUT)
                    false
                } else {
                    val gate =
                        try {
                            canContinue()
                        } catch (failure: Throwable) {
                            preserve(Stage.GATE_CHECK, failure)
                            false
                        }
                    if (!gate) {
                        markClosed(Termination.GATE_BLOCKED)
                        false
                    } else if (closed) {
                        false
                    } else {
                        val current = driver
                        if (current == null) {
                            true
                        } else {
                            val permitted =
                                try {
                                    current.hasPermission()
                                } catch (failure: Throwable) {
                                    preserve(Stage.PERMISSION_CHECK, failure)
                                    markClosed(Termination.SETUP_ERROR)
                                    null
                                }
                            permissionGranted = permitted
                            if (permitted == false) markClosed(Termination.PERMISSION_DENIED)
                            if (closed || permitted != true) {
                                false
                            } else if (clock() - startedAt >= timeoutMillis) {
                                markClosed(Termination.TIMED_OUT)
                                false
                            } else {
                                val currentGate =
                                    try {
                                        canContinue()
                                    } catch (failure: Throwable) {
                                        preserve(Stage.GATE_CHECK, failure)
                                        false
                                    }
                                if (!currentGate) {
                                    markClosed(Termination.GATE_BLOCKED)
                                } else if (!closed && clock() - startedAt >= timeoutMillis) {
                                    markClosed(Termination.TIMED_OUT)
                                }
                                !closed && currentGate && permissionGranted == true
                            }
                        }
                    }
                }
            }
        if (!allowed) drainCleanup()
        return allowed
    }

    private fun receive(
        validCoordinates: Boolean?,
        error: Exception?,
    ) {
        if (!checkAccess()) {
            synchronized(lock) { countIgnored() }
            return
        }
        synchronized(lock) {
            val receipt = clock()
            if (closed) {
                countIgnored()
            } else if (events.size >= MAX_EVENTS) {
                markClosed(Termination.EVENT_LIMIT)
                countIgnored()
            } else if (receipt - startedAt >= timeoutMillis) {
                markClosed(Termination.TIMED_OUT)
                countIgnored()
            } else {
                val kind =
                    if (error !=
                        null
                    ) {
                        Kind.SDK_ERROR
                    } else if (validCoordinates == null) {
                        Kind.EMPTY_CALLBACK
                    } else {
                        Kind.LOCATION_CALLBACK
                    }
                val event =
                    Event(
                        kind,
                        Metadata(
                            CallbackSource.SDK_UPDATE,
                            error == null && validCoordinates != null,
                            if (error == null) validCoordinates else null,
                            receipt,
                        ),
                        error?.javaClass?.name,
                    )
                if (error != null) preserve(Stage.SDK_CALLBACK, error)
                events += event
                try {
                    sink?.invoke(event)
                } catch (failure: Throwable) {
                    preserve(Stage.OBSERVER, failure)
                    markClosed(Termination.OBSERVER_ERROR)
                }
                if (error != null) markClosed(Termination.SDK_ERROR)
                if (events.size >= MAX_EVENTS) markClosed(Termination.EVENT_LIMIT)
            }
        }
        drainCleanup()
    }

    private fun scheduleTick() {
        val delay =
            synchronized(lock) {
                if (closed) return
                minOf(POLL_MILLIS, (timeoutMillis - (clock() - startedAt)).coerceAtLeast(1))
            }
        val task =
            try {
                scheduler.schedule(delay) {
                    synchronized(lock) { scheduled = null }
                    if (checkAccess()) scheduleTick()
                }
            } catch (failure: Throwable) {
                synchronized(lock) {
                    preserve(Stage.SCHEDULER, failure)
                    markClosed(Termination.SETUP_ERROR)
                }
                drainCleanup()
                return
            }
        val cancel =
            synchronized(lock) {
                if (closed) {
                    true
                } else {
                    scheduled = task
                    false
                }
            }
        if (cancel) {
            try {
                task.cancel()
            } catch (failure: Throwable) {
                synchronized(lock) { preserve(Stage.TIMER_CANCEL, failure) }
            }
        }
    }

    private fun terminate(reason: Termination) {
        synchronized(lock) { markClosed(reason) }
        drainCleanup()
    }

    private fun markClosed(reason: Termination) {
        if (closed) return
        closed = true
        sink = null
        termination = reason
    }

    private fun drainCleanup() {
        val work =
            synchronized(lock) {
                if (!closed || setupInProgress || cleanupStarted) return
                cleanupStarted = true
                val task = scheduled
                scheduled = null
                task to driver
            }
        work.first?.let { task -> cleanup(Stage.TIMER_CANCEL, {}, { task.cancel() }, {}) }
        val current = work.second
        if (current != null) {
            if (synchronized(lock) { registerAttempted }) {
                cleanup(Stage.UNREGISTER, { unregisterAttempted = true }, { current.unregister(listener) }, { unregisterReturned = true })
            }
            cleanup(Stage.BINDING_CLOSE, { bindingCloseAttempted = true }, { current.closeBinding() }, { bindingCloseReturned = true })
            if (synchronized(lock) { initializeAttempted }) {
                cleanup(Stage.RELEASE, { releaseAttempted = true }, { current.release() }, { releaseReturned = true })
            }
        }
        synchronized(lock) { cleanupComplete = true }
    }

    private fun cleanup(
        stage: Stage,
        before: () -> Unit,
        action: () -> Unit,
        returned: () -> Unit,
    ) {
        synchronized(lock) { before() }
        try {
            action()
            synchronized(lock) { returned() }
        } catch (failure: Throwable) {
            synchronized(lock) { preserve(stage, failure) }
        }
    }

    private fun preserve(
        stage: Stage,
        failure: Throwable,
    ) {
        val primary = primaryFailure
        val first = primary == null
        if (first) {
            primaryFailure = failure
        } else if (primary !== failure && primary!!.suppressed.none { it === failure }) {
            primary.addSuppressed(failure)
        }
        failures += Failure(stage, failure.javaClass.name, first)
    }

    private fun countIgnored() {
        if (ignoredCallbacks < Int.MAX_VALUE) ignoredCallbacks++
    }

    private class AndroidScheduler : Scheduler {
        private val handler = Handler(Looper.getMainLooper())

        override fun schedule(
            delayMillis: Long,
            action: () -> Unit,
        ): Cancellation {
            val runnable = Runnable { action() }
            check(handler.postDelayed(runnable, delayMillis)) { "Probe scheduler is unavailable" }
            return Cancellation { handler.removeCallbacks(runnable) }
        }
    }

    private class SdkDriver(
        context: Context,
    ) : Driver {
        private val binding = SdkBindingContext(context)
        private var sdk: FusedLocation? = null
        private var callback: FusedLocationListener? = null
        private var registeredListener: Listener? = null

        private fun instance(): FusedLocation = sdk ?: FusedLocation(binding).also { sdk = it }

        override fun isSupported(): Boolean = instance().isSupported()

        override fun hasPermission(): Boolean =
            binding.checkSelfPermission(FusedLocationPermission.NAME) == PackageManager.PERMISSION_GRANTED

        override fun initialize() {
            instance().initialize()
        }

        override fun register(listener: Listener) {
            check(callback == null)
            registeredListener = listener
            val actual =
                object : FusedLocationListener {
                    override fun onChanged(location: LocationData?) {
                        val valid =
                            location?.let {
                                it.latitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude.isFinite() &&
                                    it.longitude in -180.0..180.0
                            }
                        listener.onChanged(valid)
                    }

                    override fun onError(exception: Exception) {
                        listener.onError(exception)
                    }
                }
            callback = actual
            instance().registerFusedLocationCallback(actual)
        }

        override fun unregister(listener: Listener) {
            check(registeredListener === listener)
            callback?.let { instance().unregisterFusedLocationCallback(it) }
        }

        override fun closeBinding() {
            binding.close()
        }

        override fun release() {
            sdk?.release()
        }
    }

    private companion object {
        const val MAX_TIMEOUT_MILLIS = 20_000L
        const val POLL_MILLIS = 100L
        const val MAX_EVENTS = 64
    }
}
