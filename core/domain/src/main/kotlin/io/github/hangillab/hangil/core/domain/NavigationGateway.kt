package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.flow.StateFlow

interface NavigationGateway {
    /** Connection owned by the current request only, never proof of route readiness. */
    val connection: StateFlow<ConnectionState>

    /** Requests only the default screen; rechecks the caller's live ownership before dispatch. */
    suspend fun requestForeground(canDispatch: () -> Boolean): NavigationScreenResult
}
