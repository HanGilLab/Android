package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.CourseMapPreview
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Fixed public landmark points only. No route line or SDK/user location is represented. */
internal object CoursePreviewLayers {
    const val SOURCE = "hangil-course-preview-points"
    private const val CIRCLES = "hangil-course-preview-circles"
    private const val NUMBERS = "hangil-course-preview-numbers"

    fun features(preview: CourseMapPreview): FeatureCollection =
        FeatureCollection.fromFeatures(
            preview.points.mapIndexed { index, point ->
                Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)).apply {
                    addStringProperty("point_id", point.id)
                    addNumberProperty("order", index + 1)
                    addStringProperty("title", point.title)
                    addStringProperty("preview_id", preview.id)
                    addNumberProperty("preview_version", preview.version)
                }
            },
        )

    fun add(
        style: Style,
        preview: CourseMapPreview,
        fill: Int,
        text: Int,
        border: Int,
        fontScale: Float,
    ) {
        val textSize = 20f * fontScale
        style.addSource(GeoJsonSource(SOURCE, features(preview)))
        style.addLayer(
            CircleLayer(CIRCLES, SOURCE).withProperties(
                circleRadius(textSize + 3f),
                circleColor(fill),
                circleStrokeColor(border),
                circleStrokeWidth(3f),
            ),
        )
        style.addLayer(
            SymbolLayer(NUMBERS, SOURCE).withProperties(
                textField(Expression.toString(get("order"))),
                textFont(arrayOf("Noto Sans Regular")),
                textSize(textSize),
                textColor(text),
                textAllowOverlap(true),
                textIgnorePlacement(true),
            ),
        )
    }
}
