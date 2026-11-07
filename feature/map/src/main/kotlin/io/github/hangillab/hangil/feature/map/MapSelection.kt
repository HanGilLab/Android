package io.github.hangillab.hangil.feature.map

/** One transient map choice bound to a renderer and STARTED host, never an observed route. */
@ConsistentCopyVisibility
data class MapSelection internal constructor(
    val target: Target,
    val nonce: Long,
    val rendered: Boolean = false,
    internal val canPresent: () -> Boolean,
) {
    val pointId: String? get() = (target as? Target.Point)?.id
    val roadId: String? get() = (target as? Target.Road)?.id

    sealed interface Target {
        data class Point(
            val id: String,
        ) : Target

        data class Road(
            val id: String,
        ) : Target
    }
}
