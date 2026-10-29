package io.github.hangillab.hangil.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/** Optional parked detail. Callers place it inside their interaction and lifecycle gates. */
@Composable
fun ParkedDisclosure(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val action = stringResource(if (expanded) R.string.parked_detail_hide else R.string.parked_detail_show)
    val state = stringResource(if (expanded) R.string.parked_detail_expanded else R.string.parked_detail_collapsed)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.sm)) {
        TextButton(
            onClick = { expanded = !expanded },
            modifier =
                modifier
                    .fillMaxWidth()
                    .heightIn(
                        min = HanGilDimensions.compactActionMinHeight,
                    ).semantics { stateDescription = state },
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HanGilSpacing.md)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(action, style = MaterialTheme.typography.titleMedium)
            }
        }
        if (expanded) Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.sm), content = content)
    }
}
