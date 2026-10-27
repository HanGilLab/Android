package io.github.hangillab.hangil.feature.map

internal data class MapRenderAttempt(
    val id: Int = 0,
    val load: MapLoadState = MapLoadState.LOADING,
    val token: Long = 0,
)
