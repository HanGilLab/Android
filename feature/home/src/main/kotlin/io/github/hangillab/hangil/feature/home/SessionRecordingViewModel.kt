package io.github.hangillab.hangil.feature.home

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.RecordingController
import javax.inject.Inject

/** The app-level controller owns recording; this ViewModel only connects the visible host. */
@HiltViewModel
class SessionRecordingViewModel
    @Inject
    constructor(
        private val controller: RecordingController,
    ) : ViewModel() {
        val state = controller.state

        suspend fun recordWhileStarted() = controller.recordWhileStarted()

        fun retry(): Boolean = controller.retry()
    }
