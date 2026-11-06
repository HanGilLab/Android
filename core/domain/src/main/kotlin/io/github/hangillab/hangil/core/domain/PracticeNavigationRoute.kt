package io.github.hangillab.hangil.core.domain

/** Provider POIs for a native navigation request, separate from map preview points. */
data class PracticeNavigationRoute(
    val destination: Point,
    val waypoints: List<Point>,
) {
    init {
        require(waypoints.size in 1..2) { "A practice route requires one or two waypoints" }
        val identities = (waypoints + destination).map { it.poiId to it.poiSubId }
        require(identities.distinct().size == identities.size) { "Route POIs must be distinct" }
    }

    data class Point(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val poiId: String,
        val poiSubId: String,
        val address: String,
    ) {
        init {
            require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "Invalid route coordinates" }
            require(name.isNotBlank() && address.isNotBlank() && poiId.isNotBlank() && poiSubId.isNotBlank()) {
                "A route point requires its provider identity and address"
            }
        }

        /** The Pleos demo route returned an omitted sub-ID for the requested default entrance 0. */
        fun matchesProviderPoi(
            poiId: String?,
            poiSubId: String?,
        ): Boolean = this.poiId == poiId && this.poiSubId == (poiSubId ?: "0")
    }
}
