package io.github.hangillab.hangil.core.vehicle

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.LocationGateway
import io.github.hangillab.hangil.core.domain.LocationUpdateGateway
import io.github.hangillab.hangil.core.domain.NavigationGateway
import io.github.hangillab.hangil.core.domain.VehicleGateway

@Module
@InstallIn(SingletonComponent::class)
abstract class VehicleModule {
    @Binds
    abstract fun locationGateway(implementation: PleosLocationGateway): LocationGateway

    @Binds
    abstract fun locationUpdateGateway(implementation: PleosLocationUpdateGateway): LocationUpdateGateway

    @Binds
    abstract fun vehicleGateway(implementation: PleosVehicleGateway): VehicleGateway

    @Binds
    abstract fun navigationGateway(implementation: PleosNavigationGateway): NavigationGateway

    @Binds
    abstract fun drivingRestrictions(implementation: AaosDrivingRestrictionsProvider): DrivingRestrictionsProvider
}
