package io.github.hangillab.hangil.feature.home

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics

/** Keep an accessibility action attached to the callback and source that were displayed. */
internal fun Modifier.frozenObservationClick(
    action: () -> Unit,
    enabled: Boolean = true,
): Modifier =
    semantics {
        onClick {
            if (enabled) {
                action()
                true
            } else {
                false
            }
        }
    }
