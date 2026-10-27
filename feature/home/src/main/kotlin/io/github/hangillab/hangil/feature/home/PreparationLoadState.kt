package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.Preparation

sealed interface PreparationLoadState {
    data object Loading : PreparationLoadState

    data object Failed : PreparationLoadState

    data class Loaded(
        val preparation: Preparation,
    ) : PreparationLoadState
}
