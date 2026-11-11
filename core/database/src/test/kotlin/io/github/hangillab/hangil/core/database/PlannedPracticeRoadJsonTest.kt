package io.github.hangillab.hangil.core.database

import io.github.hangillab.hangil.core.domain.CourseRoadSection
import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.PlannedPracticeRoad
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlannedPracticeRoadJsonTest {
    private val target =
        PlannedPracticeRoad(
            "source",
            "version",
            CourseRoadSnapshot(
                "road",
                "Quoted \"road\"",
                "residential",
                Long.MAX_VALUE,
                Int.MAX_VALUE,
                Long.MAX_VALUE,
                listOf(
                    CourseRoadSnapshot.Node(Long.MAX_VALUE, Int.MAX_VALUE, -0.0, 127.12345678901234),
                    CourseRoadSnapshot.Node(2, 3, java.lang.Double.MIN_VALUE, 127.12345678901236),
                ),
            ),
        )

    @Test fun `canonical snapshot preserves every integer bit coordinate precision signed zero and node order`() {
        val encoded = checkNotNull(PlannedPracticeRoadJson.encode(target))
        assertTrue(encoded.startsWith("{\"format\":1,"))
        assertEquals(target, PlannedPracticeRoadJson.decode(encoded))
        assertEquals(encoded, PlannedPracticeRoadJson.encode(PlannedPracticeRoadJson.decode(encoded)))
        assertNull(PlannedPracticeRoadJson.decode(null))
        assertNull(PlannedPracticeRoadJson.encode(null))
    }

    @Test fun `section targets round trip with integer indices and still preserve the complete public road`() {
        val road = target.road.copy(nodes = target.road.nodes + CourseRoadSnapshot.Node(3, 1, 0.01, 127.13))
        val withSection = PlannedPracticeRoad(target.sourceSessionId, target.reviewVersion, road, CourseRoadSection(0, 1))
        val encoded = checkNotNull(PlannedPracticeRoadJson.encode(withSection))
        assertTrue(encoded.startsWith("{\"format\":2,"))
        assertEquals(withSection, PlannedPracticeRoadJson.decode(encoded))
        assertEquals(road, PlannedPracticeRoadJson.decode(encoded)?.road)
        val invalid =
            listOf(
                encoded.replace("\"firstNodeIndex\":0", "\"firstNodeIndex\":\"0\""),
                encoded.replace("\"lastNodeIndex\":1", "\"lastNodeIndex\":3"),
                encoded.replace("\"format\":2", "\"format\":1"),
                JSONObject(encoded).apply { remove("section") }.toString(),
            )
        invalid.forEach { raw -> assertThrows(IllegalStateException::class.java) { PlannedPracticeRoadJson.decode(raw) } }
    }

    @Test fun `malformed noncanonical duplicate unknown partial and coerced fields never become absence`() {
        val encoded = checkNotNull(PlannedPracticeRoadJson.encode(target))
        val invalid =
            listOf(
                "",
                "null",
                "{}",
                encoded + " ",
                "{\"format\":1," + encoded.substring(1),
                JSONObject(encoded).put("unknown", true).toString(),
                JSONObject(encoded).put("format", "1").toString(),
                JSONObject(encoded).put("origin", "SDK_UPDATE").toString(),
                JSONObject(encoded).apply { getJSONObject("road").put("wayId", Long.MAX_VALUE.toString()) }.toString(),
                JSONObject(encoded).apply { getJSONObject("road").remove("title") }.toString(),
                JSONObject(encoded).apply { getJSONObject("road").getJSONArray("nodes").getJSONObject(0).put("latitude", 0) }.toString(),
                JSONObject(
                    encoded,
                ).apply { getJSONObject("road").getJSONArray("nodes").getJSONObject(0).put("latitude", "NaN") }.toString(),
                JSONObject(
                    encoded,
                ).apply { getJSONObject("road").getJSONArray("nodes").getJSONObject(0).put("version", 4_294_967_297L) }.toString(),
            )
        invalid.forEach { raw -> assertThrows(IllegalStateException::class.java) { PlannedPracticeRoadJson.decode(raw) } }
    }
}
