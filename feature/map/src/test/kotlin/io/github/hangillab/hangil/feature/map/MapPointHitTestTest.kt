package io.github.hangillab.hangil.feature.map

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import io.github.hangillab.hangil.core.domain.CourseMapPreview
import io.github.hangillab.hangil.core.domain.CoursePreviewPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.IOException
import java.math.BigDecimal

/** Pure hit validation, not SDK rendering, touch delivery, geography accuracy or performance. */
class MapPointHitTestTest {
    private val preview =
        CourseMapPreview(
            "fixed-preview",
            1,
            "Fixed preview",
            listOf(
                CoursePreviewPoint("one", "Fixed one", 37.0, 127.0, 1, 1),
                CoursePreviewPoint("two", "Fixed two", 37.1, 127.1, 2, 1),
            ),
        )

    @Test fun noHitsDoNotInventAnIdOrCallProjection() {
        val result = MapPointHitTest.match(preview, emptyList()) { _, _ -> error("Empty hit must not project") }
        assertNull(result)
    }

    @Test fun oneFullyMatchingHitReturnsItsFrozenId() {
        assertEquals("one", match(listOf(feature())))
        assertEquals("two", match(listOf(feature(1))))
    }

    @Test fun identicalTileCopiesOfOneIdStillRepresentOneChoice() {
        assertEquals("one", match(listOf(feature(), feature(), feature())))
    }

    @Test fun multipleDistinctValidIdsAreAmbiguousInEitherSdkOrder() {
        assertNull(match(listOf(feature(), feature(1))))
        assertNull(match(listOf(feature(1), feature())))
    }

    @Test fun anUnknownIdNeverFallsBackToAnotherTitleOrPoint() {
        val candidate = feature().apply { addStringProperty("point_id", "unknown") }
        assertNull(match(listOf(candidate)))
    }

    @Test fun aMissingOrWrongPreviewIdentityIsNotAccepted() {
        for (identity in listOf("", "other-preview")) {
            val candidate = feature().apply { addStringProperty("preview_id", identity) }
            assertNull(match(listOf(candidate)))
        }
        val missing = feature().apply { checkNotNull(properties()).remove("preview_id") }
        assertNull(match(listOf(missing)))
    }

    @Test fun changedPreviewVersionAndFractionalVersionCannotMatchAnIntegerVersion() {
        for (version in listOf<Number>(2, 1.25, 4_294_967_297L, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(match(listOf(feature().apply { addNumberProperty("preview_version", version) })))
        }
    }

    @Test fun numericStringsAndBooleansAreNotCoercedToPreviewVersionsOrOrders() {
        for (key in listOf("preview_version", "order")) {
            assertNull(match(listOf(feature().apply { addStringProperty(key, "1") })))
            assertNull(match(listOf(feature().apply { addBooleanProperty(key, true) })))
        }
    }

    @Test fun aNumberWithHiddenFractionCannotBecomeOneThroughDoubleRounding() {
        for (key in listOf("preview_version", "order")) {
            val candidate = feature()
            checkNotNull(candidate.properties()).add(key, JsonPrimitive(BigDecimal("1.00000000000000000000001")))
            assertNull(match(listOf(candidate)))
        }
    }

    @Test fun anEquivalentJsonNumberRepresentationStillMatches() {
        val candidate = feature()
        checkNotNull(candidate.properties()).add("preview_version", JsonPrimitive(BigDecimal("1.0")))
        checkNotNull(candidate.properties()).add("order", JsonPrimitive(BigDecimal("1E+0")))
        assertEquals("one", match(listOf(candidate)))
    }

    @Test fun orderIsBoundToTheCurrentPreviewRatherThanTheHitListOrder() {
        assertNull(match(listOf(feature().apply { addNumberProperty("order", 2) })))
        assertNull(match(listOf(feature(1).apply { addNumberProperty("order", 1) })))
        assertNull(match(listOf(feature().apply { addNumberProperty("order", 1.5) })))
        assertNull(match(listOf(feature().apply { addNumberProperty("order", 0) })))
    }

    @Test fun titleMustMatchTheCurrentFrozenPointWithoutTrimmingOrFallback() {
        for (title in listOf("", "Fixed one ", "Fixed two")) {
            assertNull(match(listOf(feature().apply { addStringProperty("title", title) })))
        }
    }

    @Test fun allTextIdentityFieldsRequireActualJsonStrings() {
        for (key in listOf("point_id", "title", "preview_id")) {
            assertNull(match(listOf(feature().apply { addBooleanProperty(key, false) })))
            assertNull(match(listOf(feature().apply { addNumberProperty(key, 1) })))
            val objectValue = feature()
            checkNotNull(objectValue.properties()).add(key, JsonObject())
            assertNull(match(listOf(objectValue)))
        }
    }

    @Test fun missingNullObjectAndArrayPropertiesDoNotBecomeDefaultValues() {
        for (key in listOf("point_id", "title", "preview_id", "preview_version", "order")) {
            val missing = feature().apply { checkNotNull(properties()).remove(key) }
            assertNull(match(listOf(missing)))
            for (replacement in listOf(JsonNull.INSTANCE, JsonObject(), JsonArray())) {
                val candidate = feature()
                checkNotNull(candidate.properties()).add(key, replacement)
                assertNull(match(listOf(candidate)))
            }
        }
    }

    @Test fun missingEntirePropertiesIsAnInvalidHitRatherThanAnExceptionOrEmptyRepair() {
        val candidate = Feature.fromGeometry(Point.fromLngLat(127.0, 37.0), null as JsonObject?)
        assertNull(match(listOf(candidate)))
    }

    @Test fun oneInvalidHitCannotBeDiscardedToSelectAValidHitInEitherOrder() {
        val corrupt = feature().apply { addStringProperty("preview_id", "wrong") }
        assertNull(match(listOf(feature(), corrupt)))
        assertNull(match(listOf(corrupt, feature())))
    }

    @Test fun evenADuplicateIdWithInvalidMetadataMakesTheHitSetUnusable() {
        val corrupt = feature().apply { addNumberProperty("order", 99) }
        assertNull(match(listOf(feature(), corrupt)))
    }

    @Test fun nullAndNonPointGeometryCannotBeAPlaceHit() {
        val properties = feature().properties()
        assertNull(match(listOf(Feature.fromGeometry(null, properties))))
        val line = LineString.fromLngLats(listOf(Point.fromLngLat(127.0, 37.0), Point.fromLngLat(127.1, 37.1)))
        assertNull(match(listOf(Feature.fromGeometry(line, properties))))
    }

    @Test fun nonfiniteAndOutOfRangePointCoordinatesAreRejectedBeforeProjection() {
        val coordinates =
            listOf(
                Double.NaN to 37.0,
                Double.POSITIVE_INFINITY to 37.0,
                127.0 to Double.NaN,
                127.0 to Double.NEGATIVE_INFINITY,
                180.001 to 37.0,
                -180.001 to 37.0,
                127.0 to 90.001,
                127.0 to -90.001,
            )
        for ((longitude, latitude) in coordinates) {
            val candidate = withGeometry(Point.fromLngLat(longitude, latitude))
            val result = MapPointHitTest.match(preview, listOf(candidate)) { _, _ -> error("Invalid point must not project") }
            assertNull(result)
        }
    }

    @Test fun anExtraAltitudeIsNotTheTwoDimensionalFrozenGeometry() {
        assertNull(match(listOf(withGeometry(Point.fromLngLat(127.0, 37.0, 50.0)))))
    }

    @Test fun tileQuantizedCoordinatesAreAcceptedWhenTheirProjectedErrorIsWithinTwoPixels() {
        val quantized = withGeometry(Point.fromLngLat(127.001, 37.0009))
        assertEquals("one", match(listOf(quantized)))
    }

    @Test fun exactTwoPixelBoundaryIsAcceptedButLargerErrorsAreRejected() {
        val quantized = withGeometry(Point.fromLngLat(127.001, 37.0))

        fun atError(error: Double) =
            MapPointHitTest.match(preview, listOf(quantized)) { latitude, longitude ->
                if (latitude == 37.0 && longitude == 127.0) 0.0 to 0.0 else error to 0.0
            }
        assertEquals("one", atError(2.0))
        assertNull(atError(2.000001))
    }

    @Test fun theScreenErrorBoundIsEuclideanAndCannotGrowToADiagonalSquare() {
        val quantized = withGeometry(Point.fromLngLat(127.001, 37.001))
        val result =
            MapPointHitTest.match(preview, listOf(quantized)) { latitude, longitude ->
                if (latitude == 37.0 && longitude == 127.0) 0.0 to 0.0 else 1.5 to 1.5
            }
        assertNull(result)
    }

    @Test fun wrongGeometryCannotBorrowMatchingIdentityProperties() {
        assertNull(match(listOf(withGeometry(Point.fromLngLat(127.1, 37.1)))))
    }

    @Test fun nonfiniteActualOrExpectedScreenCoordinatesNeverBecomeAHit() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertNull(MapPointHitTest.match(preview, listOf(feature())) { _, _ -> bad to 0.0 })
            val quantized = withGeometry(Point.fromLngLat(127.001, 37.0))
            val result =
                MapPointHitTest.match(preview, listOf(quantized)) { latitude, longitude ->
                    if (latitude == 37.0 && longitude == 127.0) bad to 0.0 else 0.0 to 0.0
                }
            assertNull(result)
        }
    }

    @Test fun projectionFailuresPropagateRatherThanBecomingAnOrdinaryNoHit() {
        val failure = IOException("fixture projection failure")
        try {
            MapPointHitTest.match(preview, listOf(feature())) { _, _ -> throw failure }
            fail("Projection failures must not become null")
        } catch (actual: IOException) {
            assertSame(failure, actual)
        }
    }

    @Test fun matchingIsReadOnlyForFeatureGeometryAndMetadata() {
        val candidate = feature()
        val before = candidate.toJson()
        assertEquals("one", match(listOf(candidate)))
        assertEquals(before, candidate.toJson())
        assertEquals("one", preview.points.first().id)
        assertEquals(127.0, preview.points.first().longitude, 0.0)
    }

    private fun feature(index: Int = 0): Feature = checkNotNull(CoursePreviewLayers.features(preview).features())[index]

    private fun withGeometry(point: Point): Feature = Feature.fromGeometry(point, feature().properties())

    private fun match(features: List<Feature>): String? =
        MapPointHitTest.match(preview, features) { latitude, longitude ->
            longitude * 1_000.0 to latitude * 1_000.0
        }
}
