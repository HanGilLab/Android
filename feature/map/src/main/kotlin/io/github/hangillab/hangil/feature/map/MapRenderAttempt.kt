package io.github.hangillab.hangil.feature.map

/** Preparation and renderer identity move together so a new attempt never carries an old course. */
internal data class MapRenderAttempt(
    val id: Int = 0,
    val load: MapLoadState = MapLoadState.LOADING,
    val token: Long = 0,
    val reader: Long = 0,
    val preparation: MapPreparationState = MapPreparationState.Loading,
    val queryEpoch: Long = 0,
    val pointSelection: MapPointSelection? = null,
)
