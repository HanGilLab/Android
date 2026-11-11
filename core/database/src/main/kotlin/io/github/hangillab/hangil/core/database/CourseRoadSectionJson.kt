package io.github.hangillab.hangil.core.database

import io.github.hangillab.hangil.core.domain.CourseRoadSection
import org.json.JSONObject

/** Only indices into an independently stored public road are encoded here. */
internal object CourseRoadSectionJson {
    fun fields(section: CourseRoadSection?): Array<Pair<String, String>> =
        if (section == null) {
            emptyArray()
        } else {
            arrayOf("section" to "{\"firstNodeIndex\":${section.firstNodeIndex},\"lastNodeIndex\":${section.lastNodeIndex}}")
        }

    fun decode(
        root: JSONObject,
        format: Long,
    ): CourseRoadSection? =
        when (format) {
            1L -> null
            2L -> {
                val section = root.get("section") as? JSONObject ?: error("Invalid road section object")
                CourseRoadSection(index(section, "firstNodeIndex"), index(section, "lastNodeIndex"))
            }
            else -> error("Unknown road section storage format")
        }

    private fun index(
        section: JSONObject,
        key: String,
    ): Int = section.get(key) as? Int ?: error("Invalid road section index")
}
