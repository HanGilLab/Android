package io.github.hangillab.hangil.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MapViewModel
    @Inject
    constructor(
        private val vehicleGateway: VehicleGateway,
        private val restrictionsProvider: DrivingRestrictionsProvider,
        private val repository: PracticeRepository,
        private val catalog: PracticeCatalog,
    ) : ViewModel() {
        private val render = MutableStateFlow(MapRenderAttempt())
        private val tokens = AtomicLong()
        private val readerIds = AtomicLong()
        private val reader = AtomicReference<Reader?>()
        private val preparationRetry = MutableStateFlow(0L)
        private val queryEpoch = AtomicLong()

        val uiState =
            flow {
                val owner = Reader(readerIds.incrementAndGet(), checkNotNull(currentCoroutineContext()[Job]))
                reader.set(owner)
                publishPreparation(owner, MapPreparationState.Loading, queryEpoch.get())
                try {
                    val preparation =
                        preparationRetry
                            .flatMapLatest { epoch ->
                                observeAccess(owner, epoch).map { epoch to it }
                            }.buffer(0)
                            .filter { (epoch, _) ->
                                reader.get() === owner && owner.job.isActive && epoch == queryEpoch.get()
                            }.map { it.second }
                    emitAll(
                        combine(
                            vehicleGateway.state,
                            restrictionsProvider.restrictions,
                            render,
                            preparation,
                        ) { vehicle, restrictions, attempt, _ ->
                            MapUiState(vehicle, restrictions, attempt.id, attempt.load, attempt.preparation)
                        },
                    )
                } finally {
                    if (reader.compareAndSet(owner, null)) {
                        render.update { MapRenderAttempt(it.id + 1) }
                    }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), MapUiState())

        fun onMapLoading(attempt: Int): Long? {
            val token = tokens.incrementAndGet()
            while (true) {
                val current = render.value
                if (!ownsPreparation(current) || current.id != attempt || current.load == MapLoadState.FAILED) return null
                if (!allowsInteraction()) {
                    // A rejected first composition must expose retry even if D/P receipts conflate.
                    if (render.compareAndSet(current, current.copy(token = 0, load = MapLoadState.FAILED))) return null
                } else if (render.compareAndSet(current, current.copy(token = token, load = MapLoadState.LOADING))) {
                    return token
                }
            }
        }

        /** Native input reads the fixed renderer/source owner and live gate before SDK dispatch. */
        fun canInteractWithMap(
            attempt: Int,
            token: Long,
        ): Boolean {
            if (token <= 0) return false
            val current = render.value
            return current.id == attempt && current.token == token && current.load == MapLoadState.READY &&
                canRender(current) && render.value === current
        }

        /** Wake cancellation for received changes without waiting for the displayed Compose state. */
        val mapInputChanges: Flow<Unit> =
            combine(vehicleGateway.state, restrictionsProvider.restrictions, render) { _, _, _ -> Unit }

        fun onMapRendered(
            attempt: Int,
            token: Long,
        ) {
            if (token <= 0) return
            render.update {
                if (canRender(it) && it.id == attempt && it.token == token && it.load == MapLoadState.LOADING) {
                    it.copy(load = MapLoadState.READY)
                } else {
                    it
                }
            }
        }

        fun onMapFailed(
            attempt: Int,
            token: Long,
        ) {
            if (token <= 0) return
            render.update {
                if (canRender(it) && it.id == attempt && it.token == token) it.copy(load = MapLoadState.FAILED) else it
            }
        }

        fun retry() {
            val expected = render.value
            render.update {
                if (it == expected && canRender(it)) it.copy(id = it.id + 1, token = 0, load = MapLoadState.LOADING) else it
            }
        }

        fun retryPreparation(): Boolean {
            val owner = reader.get()?.takeIf { it.job.isActive } ?: return false
            if (!allowsInteraction()) return false
            val epoch = queryEpoch.incrementAndGet()
            publishPreparation(owner, MapPreparationState.Loading, epoch)
            preparationRetry.value = epoch
            return true
        }

        private fun canRender(attempt: MapRenderAttempt): Boolean = ownsPreparation(attempt) && allowsInteraction()

        private fun ownsPreparation(attempt: MapRenderAttempt): Boolean {
            val owner = reader.get() ?: return false
            return owner.job.isActive && owner.id == attempt.reader && attempt.queryEpoch == queryEpoch.get() &&
                attempt.preparation is MapPreparationState.Ready
        }

        private fun allowsInteraction(): Boolean =
            allowsPracticeInteraction(vehicleGateway.state.value, restrictionsProvider.restrictions.value)

        private fun publishPreparation(
            owner: Reader,
            state: MapPreparationState,
            epoch: Long,
        ) {
            if (reader.get() !== owner || !owner.job.isActive || queryEpoch.get() != epoch) return
            render.update {
                if (reader.get() !== owner || !owner.job.isActive || queryEpoch.get() != epoch) {
                    it
                } else {
                    val permitted =
                        if (state is MapPreparationState.Ready && !allowsInteraction()) {
                            MapPreparationState.Locked
                        } else {
                            state
                        }
                    if (it.reader == owner.id && it.queryEpoch == epoch && it.preparation == permitted) {
                        it
                    } else {
                        MapRenderAttempt(it.id + 1, reader = owner.id, preparation = permitted, queryEpoch = epoch)
                    }
                }
            }
        }

        private fun invalidateReceivedSelection(
            owner: Reader,
            epoch: Long,
            selection: PracticeSelection?,
        ) {
            val current = render.value.preparation as? MapPreparationState.Ready ?: return
            val prepared = current.prepared
            val previous =
                PracticeSelection(
                    prepared.goal.id,
                    prepared.course.id,
                    prepared.course.version,
                    prepared.course.briefingVersion,
                    prepared.task.id,
                )
            if (previous != selection) publishPreparation(owner, MapPreparationState.Loading, epoch)
        }

        private fun observeAccess(
            owner: Reader,
            epoch: Long,
        ): Flow<MapPreparationState> =
            flow {
                emitAll(
                    combine(vehicleGateway.state, restrictionsProvider.restrictions) { vehicle, restrictions ->
                        allowsPracticeInteraction(vehicle, restrictions)
                    },
                )
            }.distinctUntilChanged()
                .map<_, MapPreparationState> {
                    if (it) MapPreparationState.Loading else MapPreparationState.Locked
                }.onCompletion { cause ->
                    if (cause == null) emit(MapPreparationState.Failed(MapPreparationState.FailureReason.AccessStreamCompleted))
                }.catch {
                    currentCoroutineContext().ensureActive()
                    emit(MapPreparationState.Failed(MapPreparationState.FailureReason.AccessRead))
                }.onEach { publishPreparation(owner, it, epoch) }
                .flatMapLatest { access ->
                    if (access == MapPreparationState.Loading) observePreparation(owner, epoch) else flowOf(access)
                }.buffer(0)

        private fun observePreparation(
            owner: Reader,
            epoch: Long,
        ): Flow<MapPreparationState> =
            flow { emitAll(repository.observeSelection()) }
                .map { selection ->
                    invalidateReceivedSelection(owner, epoch, selection)
                    resolve(selection)
                }.onStart { emit(MapPreparationState.Loading) }
                .onCompletion { cause ->
                    if (cause == null) emit(MapPreparationState.Failed(MapPreparationState.FailureReason.StreamCompleted))
                }.catch { failure ->
                    currentCoroutineContext().ensureActive()
                    emit(
                        MapPreparationState.Failed(
                            if (failure is InvalidPreparation) {
                                MapPreparationState.FailureReason.InvalidPreparation
                            } else {
                                MapPreparationState.FailureReason.Read
                            },
                        ),
                    )
                }.onEach { publishPreparation(owner, it, epoch) }

        private fun resolve(selection: PracticeSelection?): MapPreparationState =
            when (val prepared = catalog.resolve(selection)) {
                Preparation.Empty -> {
                    if (selection != null) throw InvalidPreparation()
                    MapPreparationState.Empty
                }
                Preparation.Outdated -> MapPreparationState.Outdated
                is Preparation.Ready -> {
                    val practice = prepared.practice
                    val actual =
                        PracticeSelection(
                            practice.goal.id,
                            practice.course.id,
                            practice.course.version,
                            practice.course.briefingVersion,
                            practice.task.id,
                        )
                    if (actual != selection || practice.goal.id !in practice.course.goalIds || practice.task !in practice.course.tasks) {
                        throw InvalidPreparation()
                    }
                    val preview = practice.course.mapPreview
                    if (preview == null) {
                        MapPreparationState.Unavailable(practice)
                    } else {
                        val snapshot = preview.copy(points = preview.points.toList())
                        MapPreparationState.Ready(practice.copy(course = practice.course.copy(mapPreview = snapshot)), snapshot)
                    }
                }
            }

        private class Reader(
            val id: Long,
            val job: Job,
        )

        private class InvalidPreparation : IllegalStateException()
    }
