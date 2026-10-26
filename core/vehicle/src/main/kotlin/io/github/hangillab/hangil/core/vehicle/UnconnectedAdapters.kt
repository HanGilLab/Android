package io.github.hangillab.hangil.core.vehicle

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.NavigationGateway
import io.github.hangillab.hangil.core.domain.VehicleGateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnconnectedVehicleGateway
    @Inject
    constructor() : VehicleGateway {
        override val connection = MutableStateFlow(ConnectionState.DISCONNECTED).asStateFlow()
    }

@Singleton
class UnconnectedNavigationGateway
    @Inject
    constructor() : NavigationGateway {
        override val connection = MutableStateFlow(ConnectionState.UNAVAILABLE).asStateFlow()
    }

@Singleton
class UnknownDrivingRestrictionsProvider
    @Inject
    constructor() : DrivingRestrictionsProvider {
        override val restrictions = MutableStateFlow(DrivingRestrictions()).asStateFlow()
    }
