package io.github.hangillab.hangil.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.NavigationGateway
import io.github.hangillab.hangil.core.domain.VehicleGateway
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeUiState(
    val vehicleConnection: ConnectionState = ConnectionState.DISCONNECTED,
    val navigationConnection: ConnectionState = ConnectionState.UNAVAILABLE,
    val restrictions: DrivingRestrictions = DrivingRestrictions(),
)

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        vehicleGateway: VehicleGateway,
        navigationGateway: NavigationGateway,
        restrictionsProvider: DrivingRestrictionsProvider,
    ) : ViewModel() {
        val uiState =
            combine(vehicleGateway.connection, navigationGateway.connection, restrictionsProvider.restrictions) {
                vehicle,
                navigation,
                restrictions,
                ->
                HomeUiState(vehicle, navigation, restrictions)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())
    }
