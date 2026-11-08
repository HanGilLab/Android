package io.github.hangillab.hangil.feature.course

import io.github.hangillab.hangil.core.domain.PlannedPracticeRoad
import io.github.hangillab.hangil.core.domain.PracticeSelection

internal data class PreparationDraft(
    val step: PreparationStep = PreparationStep.GOAL,
    val goalId: String? = null,
    val courseId: String? = null,
    val courseVersion: Int? = null,
    val briefingVersion: Int? = null,
    val taskId: String? = null,
    val plannedRoad: PlannedPracticeRoad? = null,
) {
    fun selection(): PracticeSelection? {
        return PracticeSelection(
            goalId ?: return null,
            courseId ?: return null,
            courseVersion ?: return null,
            briefingVersion ?: return null,
            taskId ?: return null,
            plannedRoad,
        )
    }
}

/** Bundle-safe complete binding; a restored draft never adopts a different stored target. */
internal fun PracticeSelection?.savedFields(): ArrayList<String> {
    if (this == null) return arrayListOf("empty-preparation")
    val fields = arrayListOf("preparation-v2", goalId, courseId, courseVersion.toString(), briefingVersion.toString(), taskId)
    val plan = plannedRoad ?: return fields.apply { add("no-target") }
    fields.addAll(listOf("target", plan.sourceSessionId, plan.reviewVersion))
    val road = plan.road
    fields.addAll(
        listOf(
            road.id,
            road.title,
            road.highway,
            road.osmWayId.toString(),
            road.osmWayVersion.toString(),
            road.capturedAtEpochMillis.toString(),
            road.nodes.size.toString(),
        ),
    )
    road.nodes.forEach {
        fields.addAll(listOf(it.osmNodeId.toString(), it.osmNodeVersion.toString(), it.latitude.toString(), it.longitude.toString()))
    }
    return fields
}
