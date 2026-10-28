package io.github.hangillab.hangil.core.vehicle

import io.github.hangillab.hangil.core.domain.LocationReading

/** SDK operations and close run on the reader's main dispatcher; cancellation only clears the atomic sink. */
internal interface LocationClient {
    fun isSupported(): Boolean

    fun hasPermission(): Boolean

    fun readLastKnown(onResult: (LocationReading) -> Unit)

    fun cancelPending()

    fun close()
}
