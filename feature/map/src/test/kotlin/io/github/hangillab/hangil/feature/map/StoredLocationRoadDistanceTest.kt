package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoredLocationRoadDistanceTest {
    @Test fun `distance uses the nearest segment in meters regardless of raw SDK fields`() {
        val road = road(-0.01, -0.01, 0.01)
        val sample = LocationUpdateSample(0.0001, 0.0, 0.0, 0f, -1f, -4, 100)
        val meters = checkNotNull(StoredLocationRoadDistance.meters(sample, road))
        assertEquals(11.12, meters, 0.03)
        assertEquals(
            meters,
            checkNotNull(
                StoredLocationRoadDistance.meters(sample.copy(confidenceRaw = 200f, headingRaw = 270f, sdkTimestampMillis = 1), road),
            ),
            0.0,
        )
        assertEquals(0.0, checkNotNull(StoredLocationRoadDistance.meters(sample.copy(latitude = 0.0), road)), 0.03)
    }

    @Test fun `coordinates beyond a segment use its end and unsupported longitude spans are unknown`() {
        val sample = LocationUpdateSample(0.0, 0.0101, 0.0, 0f, 0f, 0, 100)
        assertEquals(11.12, checkNotNull(StoredLocationRoadDistance.meters(sample, road(-0.01, 0.01))), 0.03)
        assertNull(StoredLocationRoadDistance.meters(sample.copy(longitude = 179.99), road(179.9, -179.9)))
    }

    private fun road(vararg longitudes: Double) =
        CourseRoadSnapshot(
            "road",
            "Road",
            "residential",
            1,
            1,
            1,
            longitudes.mapIndexed { index, longitude -> CourseRoadSnapshot.Node(index + 1L, 1, 0.0, longitude) },
        )
}
