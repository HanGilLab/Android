package io.github.hangillab.hangil.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.RecordingController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackgroundRecordingViewModel
    @Inject
    constructor(
        private val controller: RecordingController,
    ) : ViewModel() {
        private val lock = Any()
        private var revision = 0L
        private var issued: Any? = null
        private val current = MutableStateFlow(BackgroundRecordingUiState(revision, controller.background.value))
        val state = current.asStateFlow()

        init {
            viewModelScope.launch {
                controller.background.collect { value ->
                    synchronized(lock) { current.value = BackgroundRecordingUiState(++revision, value) }
                }
            }
        }

        fun enable(
            expected: BackgroundRecordingUiState,
            source: PracticeSession,
            canStart: () -> Boolean,
        ): Boolean =
            synchronized(lock) {
                if (current.value !== expected || !runCatching(canStart).getOrDefault(false)) return@synchronized false
                val action = Any()
                issued = action
                controller.requestBackground(source) {
                    synchronized(lock) { issued === action && runCatching(canStart).getOrDefault(false) }
                }
            }

        fun stop(
            expected: BackgroundRecordingUiState,
            runId: String,
            canStop: () -> Boolean,
        ): Boolean =
            synchronized(lock) {
                if (current.value !== expected || !runCatching(canStop).getOrDefault(false)) return@synchronized false
                issued = null
                controller.stopBackground(runId)
            }
    }
