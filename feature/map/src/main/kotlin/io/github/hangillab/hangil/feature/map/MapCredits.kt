package io.github.hangillab.hangil.feature.map

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle

@Composable
internal fun MapCredits(
    dark: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    Text(stringResource(R.string.map_sources), modifier, color = color, style = style)
    if (dark) {
        Text(
            stringResource(R.string.map_dark_design_credit),
            modifier.testTag("map_dark_design_credit"),
            color = color,
            style = style,
        )
    }
}
