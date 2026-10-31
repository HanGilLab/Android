package io.github.hangillab.hangil.core.vehicle

internal interface NavigationClient {
    fun isSupported(): Boolean

    fun hasPermission(): Boolean

    fun connect(
        onConnected: () -> Unit,
        onFailed: (Throwable) -> Unit,
    )

    fun requestForeground()

    fun cancelPending()

    fun close()
}
