package io.github.hangillab.hangil.core.vehicle

import io.github.hangillab.hangil.core.domain.NavigationScreenResult
import io.github.hangillab.hangil.core.domain.PracticeNavigationRoute

internal interface NavigationClient {
    fun isSupported(): Boolean

    fun hasPermission(): Boolean

    fun hasRoutePermissions(): Boolean

    fun connect(
        onConnected: () -> Unit,
        onFailed: (Throwable) -> Unit,
    )

    fun requestForeground()

    suspend fun requestRoute(
        route: PracticeNavigationRoute,
        canDispatch: () -> Boolean,
        timeoutMillis: Long,
    ): NavigationScreenResult

    fun cancelPending()

    fun close()
}
