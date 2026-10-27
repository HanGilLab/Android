package io.github.hangillab.hangil.core.vehicle

import ai.pleos.playground.location.fused.model.LocationData
import io.github.hangillab.hangil.core.domain.LocationReading
import io.github.hangillab.hangil.core.domain.LocationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSampleMappingTest {
    @Test fun `last known samples preserve raw time and confidence without claiming freshness`() {
        val source = LocationData(12.345, 23.456, -8.0, -2.0f, -4.5f, 17L)
        val first = (source.toLocationReading(1_000) as LocationReading.LastKnown).sample
        val later = (source.toLocationReading(90_000) as LocationReading.LastKnown).sample
        assertEquals(17L, first.sdkTimestampMillis)
        assertEquals(first.sdkTimestampMillis, later.sdkTimestampMillis)
        assertEquals(1_000L, first.receivedAtElapsedRealtimeMillis)
        assertEquals(90_000L, later.receivedAtElapsedRealtimeMillis)
        assertEquals(-4.5f, first.confidenceRaw)
        assertEquals(-2.0f, first.headingRaw)
        assertEquals(-8.0, first.altitudeRaw, 0.0)
        assertEquals(LocationSource.VEHICLE_SDK_LAST_KNOWN, first.source)
        assertFalse(first.toString().contains("12.345"))
        assertFalse(first.toString().contains("23.456"))
    }

    @Test fun `null does not become a zero coordinate`() {
        assertEquals(LocationReading.Empty, null.toLocationReading(1_000))
        assertTrue(LocationData(0.0, 0.0, 0.0, 0f, 0f, 0L).toLocationReading(1_000) is LocationReading.LastKnown)
    }

    @Test fun `invalid coordinates and nonfinite raw values are rejected`() {
        listOf(
            LocationData(Double.NaN, 0.0, 0.0, 0f, 0f, 0L),
            LocationData(0.0, Double.POSITIVE_INFINITY, 0.0, 0f, 0f, 0L),
            LocationData(90.01, 0.0, 0.0, 0f, 0f, 0L),
            LocationData(0.0, -180.01, 0.0, 0f, 0f, 0L),
            LocationData(0.0, 0.0, Double.NaN, 0f, 0f, 0L),
            LocationData(0.0, 0.0, 0.0, Float.NaN, 0f, 0L),
            LocationData(0.0, 0.0, 0.0, 0f, Float.NEGATIVE_INFINITY, 0L),
        ).forEach { assertEquals(LocationReading.Error, it.toLocationReading(1_000)) }
    }
}
