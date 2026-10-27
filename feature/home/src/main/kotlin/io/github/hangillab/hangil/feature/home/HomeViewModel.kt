package io.github.hangillab.hangil.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.NavigationGateway
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.core.domain.toDrivingRestrictions
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeUiState(
    val vehicle: VehicleState = VehicleState(),
    val navigationConnection: ConnectionState = ConnectionState.UNAVAILABLE,
    val restrictions: DrivingRestrictions = DrivingRestrictions(),
) {
    // combine의 별도 스트림이 갱신되는 사이에도 이전 주차 값으로 잠금을 풀지 않는다.
    val allowsDetailedInteraction: Boolean
        get() =
            restrictions.allowsDetailedInteraction &&
                vehicle.toDrivingRestrictions(restrictions.uxRestrictionState).allowsDetailedInteraction
}

@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        vehicleGateway: VehicleGateway,
        navigationGateway: NavigationGateway,
        restrictionsProvider: DrivingRestrictionsProvider,
    ) : ViewModel() {
        val uiState =
            combine(vehicleGateway.state, navigationGateway.connection, restrictionsProvider.restrictions) {
                vehicle,
                navigation,
                restrictions,
                ->
                HomeUiState(vehicle, navigation, restrictions)
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0),
                HomeUiState(),
            )
    }
