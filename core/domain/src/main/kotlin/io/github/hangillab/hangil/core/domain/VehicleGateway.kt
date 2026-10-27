package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.flow.StateFlow

interface VehicleGateway {
    val state: StateFlow<VehicleState>
}
