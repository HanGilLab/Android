package io.github.hangillab.hangil.feature.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.hangillab.hangil.core.domain.PlannedPracticeRoad

@Composable
internal fun PlannedRoadSummary(
    plan: PlannedPracticeRoad,
    tag: String,
) {
    Text(
        stringResource(
            if (plan.section == null) R.string.planned_road_summary else R.string.planned_road_section_summary,
            plan.road.title,
        ),
        Modifier.testTag(tag),
        style = MaterialTheme.typography.titleLarge,
    )
    Text(stringResource(R.string.planned_road_scope), style = MaterialTheme.typography.bodyLarge)
}
