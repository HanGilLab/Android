package io.github.hangillab.hangil.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

@HiltViewModel
class MapViewModel
    @Inject
    constructor(
        private val vehicleGateway: VehicleGateway,
        private val restrictionsProvider: DrivingRestrictionsProvider,
    ) : ViewModel() {
        private val render = MutableStateFlow(MapRenderAttempt())
        private val tokens = AtomicLong()
        val uiState =
            combine(vehicleGateway.state, restrictionsProvider.restrictions, render) { vehicle, restrictions, attempt ->
                MapUiState(vehicle, restrictions, attempt.id, attempt.load)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), MapUiState())

        fun onMapLoading(attempt: Int): Long? {
            if (!allowsInteraction() || render.value.id != attempt) return null
            val token = tokens.incrementAndGet()
            render.update { if (it.id == attempt) it.copy(token = token, load = MapLoadState.LOADING) else it }
            return token
        }

        fun onMapRendered(
            attempt: Int,
            token: Long,
        ) {
            if (!allowsInteraction()) return
            render.update {
                if (it.id == attempt && it.token == token && it.load == MapLoadState.LOADING) it.copy(load = MapLoadState.READY) else it
            }
        }

        fun onMapFailed(
            attempt: Int,
            token: Long,
        ) {
            if (!allowsInteraction()) return
            render.update { if (it.id == attempt && it.token == token) it.copy(load = MapLoadState.FAILED) else it }
        }

        fun retry() {
            if (allowsInteraction()) render.update { MapRenderAttempt(it.id + 1) }
        }

        private fun allowsInteraction(): Boolean =
            allowsPracticeInteraction(vehicleGateway.state.value, restrictionsProvider.restrictions.value)
    }
