package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** Independently pinned OSM node shapes, not a navigation or recorded vehicle route. */
internal object RoadPreviewLayers {
    const val SOURCE = "hangil-course-preview-roads"
    const val BASE = "hangil-course-preview-road-lines"
    const val SELECTION = "hangil-course-preview-road-selection"

    fun features(roads: List<CourseRoadSnapshot>): FeatureCollection =
        FeatureCollection.fromFeatures(
            roads.map { road ->
                Feature
                    .fromGeometry(
                        LineString.fromLngLats(road.nodes.map { Point.fromLngLat(it.longitude, it.latitude) }),
                    ).apply {
                        addStringProperty("road_id", road.id)
                        addStringProperty("title", road.title)
                        addStringProperty("highway", road.highway)
                        addStringProperty("osm_way_id", road.osmWayId.toString())
                        addNumberProperty("osm_way_version", road.osmWayVersion)
                        addStringProperty("road_source", road.sourceCode)
                    }
            },
        )

    fun add(
        style: Style,
        roads: List<CourseRoadSnapshot>,
        color: Int,
    ) {
        style.addSource(GeoJsonSource(SOURCE, features(roads)))
        style.addLayer(
            LineLayer(BASE, SOURCE).withProperties(
                lineColor(color),
                lineWidth(4f),
                lineOpacity(0.65f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            LineLayer(SELECTION, SOURCE)
                .withProperties(
                    lineColor(color),
                    lineWidth(10f),
                    lineOpacity(1f),
                    lineCap(Property.LINE_CAP_ROUND),
                    lineJoin(Property.LINE_JOIN_ROUND),
                ).withFilter(selectionFilter(null)),
        )
    }

    fun selectionFilter(roadId: String?): Expression =
        if (roadId == null) Expression.literal(false) else Expression.eq(get("road_id"), Expression.literal(roadId))

    fun select(
        style: Style,
        roadId: String?,
    ) {
        val layer = checkNotNull(style.getLayerAs<LineLayer>(SELECTION)) { "Missing road preview selection layer" }
        check(layer.sourceId == SOURCE) { "Wrong road preview selection source" }
        layer.setFilter(selectionFilter(roadId))
    }
}
