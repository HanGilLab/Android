package io.github.hangillab.hangil.core.vehicle

import io.github.hangillab.hangil.core.domain.LocationUpdateReading

/** One collection owns this client and the exact listener passed to register. */
internal interface LocationUpdateClient {
    interface Listener {
        fun onReading(reading: LocationUpdateReading)

        fun onSdkError(
            failure: Exception,
            receivedAtElapsedRealtimeMillis: Long,
        )

        fun onAdapterError(failure: Exception)
    }

    fun isSupported(): Boolean

    fun hasPermission(): Boolean

    fun initialize()

    fun register(listener: Listener)

    fun unregister(listener: Listener)

    fun closeBinding()

    fun release()
}
