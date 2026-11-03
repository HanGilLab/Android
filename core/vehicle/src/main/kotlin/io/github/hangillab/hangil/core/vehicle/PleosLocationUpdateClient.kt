package io.github.hangillab.hangil.core.vehicle

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock

/** The operation serializes SDK calls on main; callbacks may arrive on any Binder thread. */
internal class PleosLocationUpdateClient private constructor(
    private val binding: SdkBindingContext,
    private val sdkFactory: (Context) -> Sdk,
    private val clock: () -> Long,
) : LocationUpdateClient {
    constructor(context: Context) : this(SdkBindingContext(context), ::ActualSdk, SystemClock::elapsedRealtime)

    internal constructor(context: Context, sdk: Sdk, clock: () -> Long) : this(SdkBindingContext(context), { sdk }, clock)

    internal interface Sdk {
        fun isSupported(): Boolean

        fun initialize()

        fun register(listener: FusedLocationListener)

        fun unregister(listener: FusedLocationListener)

        fun release()
    }

    private var sdk: Sdk? = null
    private var callback: FusedLocationListener? = null
    private var registeredListener: LocationUpdateClient.Listener? = null

    @Volatile private var sink: LocationUpdateClient.Listener? = null

    @Volatile private var closed = false

    private fun instance(): Sdk = sdk ?: sdkFactory(binding).also { sdk = it }

    override fun isSupported(): Boolean = instance().isSupported()

    override fun hasPermission(): Boolean = binding.checkSelfPermission(FusedLocationPermission.NAME) == PackageManager.PERMISSION_GRANTED

    override fun initialize() {
        check(!closed) { "Location update client is closed" }
        instance().initialize()
    }

    override fun register(listener: LocationUpdateClient.Listener) {
        check(!closed && callback == null) { "Location update client has already been used" }
        registeredListener = listener
        sink = listener
        val actual =
            object : FusedLocationListener {
                override fun onChanged(location: LocationData?) {
                    val current = sink ?: return
                    val reading =
                        try {
                            location.toLocationUpdateReading(clock())
                        } catch (failure: Exception) {
                            current.onAdapterError(failure)
                            return
                        }
                    if (sink === current) current.onReading(reading)
                }

                override fun onError(exception: Exception) {
                    val current = sink ?: return
                    val receipt =
                        try {
                            clock()
                        } catch (failure: Exception) {
                            exception.addSuppressed(failure)
                            current.onAdapterError(exception)
                            return
                        }
                    if (sink === current) current.onSdkError(exception, receipt)
                }
            }
        // Store before the external call: registration can callback or fail after partial work.
        callback = actual
        instance().register(actual)
    }

    override fun unregister(listener: LocationUpdateClient.Listener) {
        check(registeredListener === listener) { "Location update listener ownership changed" }
        sink = null
        callback?.let { instance().unregister(it) }
    }

    override fun closeBinding() {
        closed = true
        sink = null
        binding.close()
    }

    override fun release() {
        sdk?.release()
    }

    private class ActualSdk(
        context: Context,
    ) : Sdk {
        private val actual = FusedLocation(context)

        override fun isSupported(): Boolean = actual.isSupported()

        override fun initialize() = actual.initialize()

        override fun register(listener: FusedLocationListener) = actual.registerFusedLocationCallback(listener)

        override fun unregister(listener: FusedLocationListener) = actual.unregisterFusedLocationCallback(listener)

        override fun release() = actual.release()
    }
}
