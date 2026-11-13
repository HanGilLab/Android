package io.github.hangillab.hangil.feature.home

data class HomeUiState(
    val allowsDetailedInteraction: Boolean = false,
    val vehiclePermissionDenied: Boolean = false,
    val preparation: PreparationLoadState = PreparationLoadState.Loading,
    internal val preparationRevision: Long = 0,
)
