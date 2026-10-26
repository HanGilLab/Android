package io.github.hangillab.hangil.core.vehicle

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.NavigationGateway
import io.github.hangillab.hangil.core.domain.VehicleGateway

@Module
@InstallIn(SingletonComponent::class)
abstract class VehicleModule {
    @Binds
    abstract fun vehicleGateway(implementation: UnconnectedVehicleGateway): VehicleGateway

    @Binds
    abstract fun navigationGateway(implementation: UnconnectedNavigationGateway): NavigationGateway

    @Binds
    abstract fun drivingRestrictions(implementation: UnknownDrivingRestrictionsProvider): DrivingRestrictionsProvider
}
