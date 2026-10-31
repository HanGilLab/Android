package io.github.hangillab.hangil.feature.map

/** A transient choice bound to one renderer and one STARTED host, never a route/road segment. */
@ConsistentCopyVisibility
data class MapPointSelection internal constructor(
    val pointId: String,
    val nonce: Long,
    val rendered: Boolean = false,
    internal val canPresent: () -> Boolean,
)
