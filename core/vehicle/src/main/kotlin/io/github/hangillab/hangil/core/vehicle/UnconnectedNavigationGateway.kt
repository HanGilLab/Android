package io.github.hangillab.hangil.core.vehicle

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.NavigationGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnconnectedNavigationGateway
    @Inject
    constructor() : NavigationGateway {
        override val connection = MutableStateFlow(ConnectionState.UNAVAILABLE).asStateFlow()
    }
