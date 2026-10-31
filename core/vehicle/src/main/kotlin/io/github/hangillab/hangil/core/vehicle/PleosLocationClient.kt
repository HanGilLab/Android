package io.github.hangillab.hangil.core.vehicle

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.hangillab.hangil.core.domain.LocationReading
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** An instance serves one request only; no continuous location registration or background collection. */
internal class PleosLocationClient(
    context: Context,
) : LocationClient {
    private val bindingContext = SdkBindingContext(context)
    private val sdk = FusedLocation(bindingContext)
    private val handler = Handler(Looper.getMainLooper())
    private val sink = AtomicReference<((LocationReading) -> Unit)?>(null)
    private val cancelled = AtomicBoolean(false)
    private var initialized = false
    private var started = false
    private var closed = false
    private val listener =
        object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                // Receipt time is separate from the SDK's undocumented timestamp time base.
                val reading = location.toLocationReading(SystemClock.elapsedRealtime())
                deliver(reading)
            }

            override fun onError(exception: Exception) {
                // SDK service errors are flattened to Exception(String); never infer a status from that message.
                deliver(if (exception is SecurityException) LocationReading.PermissionDenied else LocationReading.Error)
            }
        }

    override fun isSupported(): Boolean = sdk.isSupported()

    override fun hasPermission(): Boolean =
        bindingContext.checkSelfPermission(FusedLocationPermission.NAME) == PackageManager.PERMISSION_GRANTED

    override fun readLastKnown(onResult: (LocationReading) -> Unit) {
        check(!closed && !started) { "Location client has already been used" }
        if (cancelled.get()) throw CancellationException()
        started = true
        if (!hasPermission()) throw SecurityException()
        check(sink.compareAndSet(null, onResult))
        if (cancelled.get()) {
            sink.set(null)
            throw CancellationException()
        }
        initialized = true
        sdk.initialize()
        sdk.getLastKnownLocation(listener)
    }

    override fun cancelPending() {
        cancelled.set(true)
        sink.set(null)
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelPending()
        var failure: Exception? = null
        try {
            bindingContext.close()
        } catch (cleanup: Exception) {
            failure = cleanup
        }
        if (initialized) {
            try {
                // BindingContext is already closed before SDK release; a late retry cannot reach the platform.
                sdk.release()
            } catch (cleanup: Exception) {
                val previous = failure
                if (previous == null) {
                    failure = cleanup
                } else if (previous !== cleanup) {
                    previous.addSuppressed(cleanup)
                }
            }
        }
        failure?.let { throw it }
    }

    private fun deliver(reading: LocationReading) {
        handler.post {
            val current = sink.getAndSet(null) ?: return@post
            current(if (hasPermission()) reading else LocationReading.PermissionDenied)
        }
    }
}
