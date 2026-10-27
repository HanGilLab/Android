package io.github.hangillab.hangil.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.LocationGateway
import io.github.hangillab.hangil.core.domain.LocationReading
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocationProbeViewModel
    @Inject
    constructor(
        private val vehicleGateway: VehicleGateway,
        private val restrictionsProvider: DrivingRestrictionsProvider,
        private val locationGateway: LocationGateway,
    ) : ViewModel() {
        private val lock = Any()
        private val mutableState = MutableStateFlow<LocationProbeState>(LocationProbeState.Idle)
        val state = mutableState.asStateFlow()
        private var generation = 0L
        private var permissionToken: Long? = null
        private var readJob: Job? = null
        private var cleared = false

        init {
            viewModelScope.launch {
                combine(vehicleGateway.state, restrictionsProvider.restrictions, ::allowsPracticeInteraction)
                    .distinctUntilChanged()
                    .collect { allowed ->
                        if (!allowed && !allowsInteraction()) onHidden()
                    }
            }
        }

        fun readLastKnown() {
            val job =
                synchronized(lock) {
                    if (cleared || !allowsInteraction() || mutableState.value == LocationProbeState.Loading ||
                        mutableState.value == LocationProbeState.AwaitingPermission
                    ) {
                        null
                    } else {
                        startReadLocked()
                    }
                }
            job?.start()
        }

        /** Only the explicit permission button after a denied read may start a system request. */
        fun beginPermissionRequest(): Long? =
            synchronized(lock) {
                if (cleared || !allowsInteraction() ||
                    mutableState.value != LocationProbeState.Finished(LocationReading.PermissionDenied)
                ) {
                    null
                } else {
                    val token = ++generation
                    permissionToken = token
                    mutableState.value = LocationProbeState.AwaitingPermission
                    token
                }
            }

        fun onPermissionResult(
            token: Long,
            result: LocationPermissionResult,
        ) {
            var cancelled: Job? = null
            val job =
                synchronized(lock) {
                    if (cleared || permissionToken != token || mutableState.value != LocationProbeState.AwaitingPermission) {
                        null
                    } else if (!allowsInteraction()) {
                        cancelled = invalidateLocked()
                        null
                    } else {
                        permissionToken = null
                        when (result) {
                            LocationPermissionResult.GRANTED -> startReadLocked()
                            LocationPermissionResult.DENIED -> {
                                ++generation
                                mutableState.value = LocationProbeState.Finished(LocationReading.PermissionDenied)
                                null
                            }
                            LocationPermissionResult.FAILED -> {
                                ++generation
                                mutableState.value = LocationProbeState.Finished(LocationReading.Error)
                                null
                            }
                        }
                    }
                }
            cancelled?.cancel()
            job?.start()
        }

        /** Forget the raw sample before cancelling work that may deliver a late SDK result. */
        fun onHidden() {
            val job = synchronized(lock) { invalidateLocked() }
            job?.cancel()
        }

        override fun onCleared() {
            val job =
                synchronized(lock) {
                    cleared = true
                    invalidateLocked()
                }
            job?.cancel()
            super.onCleared()
        }

        private fun startReadLocked(): Job {
            val token = ++generation
            permissionToken = null
            mutableState.value = LocationProbeState.Loading
            return viewModelScope
                .launch(start = CoroutineStart.LAZY) {
                    try {
                        if (!isCurrentAndAllowed(token)) return@launch
                        finish(token, locationGateway.readLastKnown())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        finish(token, LocationReading.Error)
                    } finally {
                        synchronized(lock) {
                            if (generation == token) {
                                readJob = null
                                if (mutableState.value == LocationProbeState.Loading) mutableState.value = LocationProbeState.Idle
                            }
                        }
                    }
                }.also { readJob = it }
        }

        private fun finish(
            token: Long,
            reading: LocationReading,
        ) {
            synchronized(lock) {
                if (!cleared && generation == token && mutableState.value == LocationProbeState.Loading) {
                    mutableState.value =
                        if (allowsInteraction()) LocationProbeState.Finished(reading) else LocationProbeState.Idle
                }
            }
        }

        private fun invalidateLocked(): Job? {
            ++generation
            permissionToken = null
            mutableState.value = LocationProbeState.Idle
            return readJob.also { readJob = null }
        }

        private fun isCurrentAndAllowed(token: Long): Boolean =
            synchronized(lock) { !cleared && generation == token && allowsInteraction() }

        private fun allowsInteraction(): Boolean =
            allowsPracticeInteraction(vehicleGateway.state.value, restrictionsProvider.restrictions.value)
    }
