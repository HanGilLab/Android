package io.github.hangillab.hangil.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.NavigationGateway
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel
    @Inject
    constructor(
        private val vehicleGateway: VehicleGateway,
        navigationGateway: NavigationGateway,
        private val restrictionsProvider: DrivingRestrictionsProvider,
        repository: PracticeRepository,
        catalog: PracticeCatalog,
    ) : ViewModel() {
        private val retry = MutableStateFlow(0)

        // Source receipts are checked before combine can publish a newer UI snapshot.
        private val disclosureLock = Any()
        private var preparationOwner: Any? = null
        private var preparationJob: Job? = null
        private var receivedPreparation: PreparationLoadState = PreparationLoadState.Loading
        private val preparation =
            retry.flatMapLatest {
                val owner = Any()
                flow { emitAll(repository.observeSelection()) }
                    .map<_, PreparationLoadState> { PreparationLoadState.Loaded(catalog.resolve(it)) }
                    .onStart {
                        val starting = currentCoroutineContext()[Job]
                        synchronized(disclosureLock) {
                            preparationOwner = owner
                            preparationJob = starting
                            receivedPreparation = PreparationLoadState.Loading
                        }
                        emit(PreparationLoadState.Loading)
                    }.onCompletion { cause ->
                        if (cause == null) emit(PreparationLoadState.Failed)
                    }.catch {
                        currentCoroutineContext().ensureActive()
                        emit(PreparationLoadState.Failed)
                    }.onEach { load ->
                        synchronized(disclosureLock) {
                            if (preparationOwner === owner) receivedPreparation = load
                        }
                    }.onCompletion {
                        synchronized(disclosureLock) {
                            if (preparationOwner === owner) {
                                preparationOwner = null
                                preparationJob = null
                            }
                        }
                    }
            }
        val uiState =
            combine(vehicleGateway.state, navigationGateway.connection, restrictionsProvider.restrictions, preparation) { v, n, r, p ->
                HomeUiState(v, n, r, p)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), HomeUiState())

        fun canPrepare(): Boolean =
            uiState.value.preparation is PreparationLoadState.Loaded &&
                allowsPracticeInteraction(vehicleGateway.state.value, restrictionsProvider.restrictions.value)

        internal fun canExpandPreparation(expected: PreparedPractice): Boolean =
            synchronized(disclosureLock) {
                val current = (receivedPreparation as? PreparationLoadState.Loaded)?.preparation as? Preparation.Ready
                preparationJob?.isActive == true && current?.practice == expected &&
                    allowsPracticeInteraction(vehicleGateway.state.value, restrictionsProvider.restrictions.value)
            }

        fun retryLoading() {
            if (uiState.value.allowsDetailedInteraction) retry.value += 1
        }

        fun canViewMap(): Boolean = allowsPracticeInteraction(vehicleGateway.state.value, restrictionsProvider.restrictions.value)

        fun canViewHistory(): Boolean = allowsPracticeInteraction(vehicleGateway.state.value, restrictionsProvider.restrictions.value)
    }
