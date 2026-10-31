package io.github.hangillab.hangil.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.NavigationGateway
import io.github.hangillab.hangil.core.domain.NavigationScreenResult
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NavigationScreenViewModel
    @Inject
    constructor(
        private val vehicle: VehicleGateway,
        private val restrictions: DrivingRestrictionsProvider,
        private val navigation: NavigationGateway,
    ) : ViewModel() {
        private val lock = Any()
        private var hostSerial = 0L
        private var revision = 0L
        private var host: Host? = null
        private val current = MutableStateFlow(NavigationScreenUiState())
        val state = current.asStateFlow()

        fun beginHost(canKeepOpen: () -> Boolean): Long =
            synchronized(lock) {
                host?.let(::retire)
                val owner = Host(++hostSerial, canKeepOpen)
                host = owner
                publish(owner, if (allowed(owner)) NavigationScreenUiState.Phase.IDLE else NavigationScreenUiState.Phase.LOCKED)
                owner.monitor =
                    viewModelScope.launch {
                        try {
                            combine(vehicle.state, restrictions.restrictions) { _, _ -> Unit }.collect {
                                synchronized(lock) {
                                    if (host === owner) {
                                        if (!allowed(owner)) {
                                            deny(owner)
                                        } else if (current.value.phase ==
                                            NavigationScreenUiState.Phase.LOCKED
                                        ) {
                                            publish(owner, NavigationScreenUiState.Phase.IDLE)
                                        }
                                    }
                                }
                            }
                            monitoringFailed(owner)
                        } catch (cancelled: CancellationException) {
                            if (currentCoroutineContext().isActive) monitoringFailed(owner)
                            throw cancelled
                        } catch (_: Exception) {
                            monitoringFailed(owner)
                        }
                    }
                owner.id
            }

        fun endHost(id: Long) =
            synchronized(lock) {
                val owner = host
                if (owner?.id == id) {
                    retire(owner)
                    host = null
                    current.value = NavigationScreenUiState(revision = ++revision)
                }
            }

        fun open(expected: NavigationScreenUiState): Boolean {
            val work =
                synchronized(lock) {
                    val owner = host ?: return@synchronized null
                    // A stale callback cannot deny a new owner or borrow its current parking state.
                    if (current.value !== expected || expected.host != owner.id ||
                        expected.phase !in listOf(NavigationScreenUiState.Phase.IDLE, NavigationScreenUiState.Phase.RESULT)
                    ) {
                        return@synchronized null
                    }
                    if (!allowed(owner)) {
                        deny(owner)
                        return@synchronized null
                    }
                    publish(owner, NavigationScreenUiState.Phase.OPENING)
                    val request = current.value
                    val job =
                        viewModelScope.launch(start = CoroutineStart.LAZY) {
                            val caller = checkNotNull(currentCoroutineContext()[Job])
                            val result =
                                try {
                                    navigation.requestForeground {
                                        synchronized(lock) {
                                            host === owner && current.value === request && caller.isActive && allowed(owner)
                                        }
                                    }
                                } catch (cancelled: CancellationException) {
                                    if (!currentCoroutineContext().isActive) throw cancelled
                                    NavigationScreenResult.ERROR
                                } catch (_: Exception) {
                                    NavigationScreenResult.ERROR
                                }
                            synchronized(lock) {
                                if (host === owner && current.value === request && caller.isActive) {
                                    if (allowed(owner)) {
                                        publish(owner, NavigationScreenUiState.Phase.RESULT, result)
                                    } else {
                                        deny(owner)
                                    }
                                }
                            }
                        }
                    owner.request = job
                    job
                } ?: return false
            work.start()
            return true
        }

        private fun allowed(owner: Host): Boolean =
            !owner.monitorFailed && runCatching(owner.alive).getOrDefault(false) &&
                allowsPracticeInteraction(vehicle.state.value, restrictions.restrictions.value)

        private fun monitoringFailed(owner: Host) =
            synchronized(lock) {
                if (host === owner) {
                    owner.monitorFailed = true
                    deny(owner)
                }
            }

        private fun deny(owner: Host) {
            owner.request?.cancel()
            owner.request = null
            if (current.value.phase != NavigationScreenUiState.Phase.LOCKED) publish(owner, NavigationScreenUiState.Phase.LOCKED)
        }

        private fun retire(owner: Host) {
            owner.monitor?.cancel()
            owner.request?.cancel()
        }

        private fun publish(
            owner: Host,
            phase: NavigationScreenUiState.Phase,
            result: NavigationScreenResult? = null,
        ) {
            current.value = NavigationScreenUiState(owner.id, ++revision, phase, result)
        }

        private class Host(
            val id: Long,
            val alive: () -> Boolean,
        ) {
            var monitor: Job? = null
            var request: Job? = null
            var monitorFailed = false
        }
    }
