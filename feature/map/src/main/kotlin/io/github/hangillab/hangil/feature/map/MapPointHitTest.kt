package io.github.hangillab.hangil.feature.map

import android.graphics.PointF
import android.os.Looper
import io.github.hangillab.hangil.core.domain.CourseMapPreview
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point
import java.math.BigDecimal
import kotlin.math.hypot

/** A fixed preview hit is not a road segment, entrance, device location or navigation destination. */
internal object MapPointHitTest {
    private const val CIRCLES = "hangil-course-preview-circles"
    private const val MAX_PROJECTION_ERROR_PX = 2.0

    /**
     * Coordinates are MapView-local Android pixels. The pinned SDK handles its own pixel ratio.
     * The caller owns the accepted touch, renderer and live parking/UX checks before and after this
     * synchronous Main-thread lookup. Missing SDK structure/errors propagate, never become no-hit.
     */
    fun find(
        map: MapLibreMap,
        preview: CourseMapPreview,
        x: Float,
        y: Float,
    ): String? {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Native preview hit testing requires the UI thread" }
        if (!x.isFinite() || !y.isFinite() || x < 0f || y < 0f) return null
        val style = checkNotNull(map.style) { "Missing ready preview style" }
        validateStyle(style)
        val features = map.queryRenderedFeatures(PointF(x, y), CIRCLES)
        check(map.style === style) { "Preview style changed during native hit lookup" }
        validateStyle(style)
        val projection = map.projection
        val hit =
            match(preview, features) { latitude, longitude ->
                val screen = projection.toScreenLocation(LatLng(latitude, longitude))
                screen.x.toDouble() to screen.y.toDouble()
            }
        check(map.style === style) { "Preview style changed during native hit projection" }
        validateStyle(style)
        return hit
    }

    /**
     * Pure metadata/geometry boundary. Every hit must be valid; do not discard a corrupt candidate
     * to pick another. Tile/world copies of one valid ID are allowed, distinct IDs are ambiguous.
     * Geometry is tile-quantized by the SDK, so the bounded comparison uses Euclidean screen pixels
     * rather than exact source coordinates. This is not a geographic accuracy measurement.
     * Projection errors propagate to the caller's SDK failure boundary.
     */
    internal fun match(
        preview: CourseMapPreview,
        features: List<Feature>,
        project: (latitude: Double, longitude: Double) -> Pair<Double, Double>,
    ): String? {
        var chosen: String? = null
        for (feature in features) {
            val id = text(feature, "point_id") ?: return null
            val index = preview.points.indexOfFirst { it.id == id }
            if (index < 0) return null
            val expected = preview.points[index]
            if (text(feature, "title") != expected.title || text(feature, "preview_id") != preview.id ||
                !numberMatches(feature, "preview_version", preview.version) || !numberMatches(feature, "order", index + 1)
            ) {
                return null
            }
            val point = feature.geometry() as? Point ?: return null
            if (point.coordinates().size != 2) return null
            val latitude = point.latitude()
            val longitude = point.longitude()
            if (!latitude.isFinite() || latitude !in -90.0..90.0 || !longitude.isFinite() || longitude !in -180.0..180.0) return null
            val actualScreen = project(latitude, longitude)
            val expectedScreen = project(expected.latitude, expected.longitude)
            if (!actualScreen.first.isFinite() || !actualScreen.second.isFinite() ||
                !expectedScreen.first.isFinite() || !expectedScreen.second.isFinite() ||
                hypot(actualScreen.first - expectedScreen.first, actualScreen.second - expectedScreen.second) > MAX_PROJECTION_ERROR_PX
            ) {
                return null
            }
            if (chosen != null && chosen != id) return null
            chosen = id
        }
        return chosen
    }

    private fun validateStyle(style: Style) {
        check(style.isFullyLoaded) { "Preview style is not fully loaded" }
        val layer = checkNotNull(style.getLayerAs<CircleLayer>(CIRCLES)) { "Missing fixed preview hit layer" }
        check(layer.sourceId == CoursePreviewLayers.SOURCE) { "Wrong fixed preview hit source" }
        // The SDK's typed single-source getter can return a cached Java wrapper. The complete
        // source list comes from NativeMap, so require one current source of the expected type.
        val source = style.sources.singleOrNull { it.id == CoursePreviewLayers.SOURCE }
        check(source is GeoJsonSource) { "Missing or invalid current fixed preview GeoJSON source" }
    }

    private fun text(
        feature: Feature,
        key: String,
    ): String? {
        val value = feature.properties()?.get(key) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
        return value.asString
    }

    private fun numberMatches(
        feature: Feature,
        key: String,
        expected: Int,
    ): Boolean {
        val value = feature.properties()?.get(key) ?: return false
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return false
        return try {
            value.asBigDecimal.compareTo(BigDecimal.valueOf(expected.toLong())) == 0
        } catch (_: NumberFormatException) {
            false
        }
    }
}
