package io.github.hangillab.hangil.core.database

import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.PlannedPracticeRoad
import org.json.JSONArray
import org.json.JSONObject

/** Canonical decimal strings preserve coordinate precision and signed zero without JSON number coercion. */
internal object PlannedPracticeRoadJson {
    fun encode(value: PlannedPracticeRoad?): String? {
        if (value == null) return null
        val road = value.road
        val nodes =
            road.nodes.joinToString(",", "[", "]") { node ->
                objectValue(
                    "id" to node.osmNodeId.toString(),
                    "version" to node.osmNodeVersion.toString(),
                    "latitude" to JSONObject.quote(node.latitude.toString()),
                    "longitude" to JSONObject.quote(node.longitude.toString()),
                )
            }
        return objectValue(
            "format" to "1",
            "origin" to JSONObject.quote(value.origin),
            "sourceSessionId" to JSONObject.quote(value.sourceSessionId),
            "reviewVersion" to JSONObject.quote(value.reviewVersion),
            "road" to
                objectValue(
                    "id" to JSONObject.quote(road.id),
                    "title" to JSONObject.quote(road.title),
                    "highway" to JSONObject.quote(road.highway),
                    "wayId" to road.osmWayId.toString(),
                    "wayVersion" to road.osmWayVersion.toString(),
                    "capturedAt" to road.capturedAtEpochMillis.toString(),
                    "nodes" to nodes,
                ),
        )
    }

    private fun objectValue(vararg fields: Pair<String, String>): String =
        fields.joinToString(",", "{", "}") { (key, value) -> JSONObject.quote(key) + ":" + value }

    fun decode(raw: String?): PlannedPracticeRoad? {
        if (raw == null) return null
        try {
            val root = JSONObject(raw)
            check(integer(root, "format") == 1L && text(root, "origin") == "USER_REVIEW_SELECTION")
            val road = root.get("road") as? JSONObject ?: error("Invalid road object")
            val nodes = road.get("nodes") as? JSONArray ?: error("Invalid road nodes")
            val decoded =
                PlannedPracticeRoad(
                    text(root, "sourceSessionId"),
                    text(root, "reviewVersion"),
                    CourseRoadSnapshot(
                        text(road, "id"),
                        text(road, "title"),
                        text(road, "highway"),
                        integer(road, "wayId"),
                        version(road, "wayVersion"),
                        integer(road, "capturedAt"),
                        (0 until nodes.length()).map { index ->
                            val node = nodes.get(index) as? JSONObject ?: error("Invalid road node")
                            CourseRoadSnapshot.Node(
                                integer(node, "id"),
                                version(node, "version"),
                                text(node, "latitude").toDouble(),
                                text(node, "longitude").toDouble(),
                            )
                        },
                    ),
                )
            check(encode(decoded) == raw)
            return decoded
        } catch (_: Exception) {
            throw IllegalStateException("Invalid stored planned road snapshot")
        }
    }

    private fun text(
        value: JSONObject,
        key: String,
    ): String = value.get(key) as? String ?: error("Invalid planned road text")

    private fun integer(
        value: JSONObject,
        key: String,
    ): Long =
        when (val raw = value.get(key)) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> error("Invalid planned road integer")
        }

    private fun version(
        value: JSONObject,
        key: String,
    ): Int {
        val raw = integer(value, key)
        check(raw in 1..Int.MAX_VALUE.toLong())
        return raw.toInt()
    }
}
