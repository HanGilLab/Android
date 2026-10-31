package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.BackgroundRecordingState

/** A display receipt prevents an old Disabled button from reviving after a complete run cycle. */
data class BackgroundRecordingUiState(
    val revision: Long,
    val background: BackgroundRecordingState,
)
