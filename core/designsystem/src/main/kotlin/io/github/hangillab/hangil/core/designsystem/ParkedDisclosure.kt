package io.github.hangillab.hangil.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

/** Optional parked detail. Every opening and render rechecks its captured live/context gate. */
@Composable
fun ParkedDisclosure(
    title: String,
    canExpand: () -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var expanded by remember(lifecycle) { mutableStateOf(false) }
    var openingGuard by remember(lifecycle) { mutableStateOf<(() -> Boolean)?>(null) }
    var startedOwner by remember(lifecycle) { mutableStateOf(AtomicBoolean(false)) }

    fun collapse() {
        expanded = false
        openingGuard = null
    }

    DisposableEffect(lifecycle) {
        fun stopOwner() {
            startedOwner.set(false)
            collapse()
        }

        fun startOwner() {
            stopOwner()
            // An old callback must never regain permission when this lifecycle starts again.
            startedOwner = AtomicBoolean(true)
        }
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> startOwner()
                    Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> stopOwner()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !startedOwner.get()) startOwner()
        onDispose {
            stopOwner()
            lifecycle.removeObserver(observer)
        }
    }

    // Capture the epoch and guard from this composition, never an updated future source guard.
    val clickOwner = startedOwner

    fun ownsStartedContext(): Boolean = clickOwner.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun mayRender(): Boolean = ownsStartedContext() && openingGuard?.invoke() == true && canExpand()
    val toggle: () -> Unit = {
        if (expanded) {
            // Closing is always permitted, including after a live denial or STOP.
            collapse()
        } else if (ownsStartedContext() && canExpand()) {
            openingGuard = canExpand
            expanded = true
        } else {
            collapse()
        }
    }
    val visibleExpanded = expanded && mayRender()
    val action = stringResource(if (visibleExpanded) R.string.parked_detail_hide else R.string.parked_detail_show)
    val state = stringResource(if (visibleExpanded) R.string.parked_detail_expanded else R.string.parked_detail_collapsed)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
        TextButton(
            onClick = toggle,
            contentPadding = PaddingValues(HanGilSpacing.md),
            modifier =
                modifier
                    .fillMaxWidth()
                    .heightIn(min = HanGilDimensions.compactActionMinHeight)
                    .semantics {
                        stateDescription = state
                        // Foundation's default action reads a mutable clickable-node callback.
                        // Freeze this action too so an old accessibility action keeps its epoch.
                        onClick {
                            toggle()
                            true
                        }
                    },
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HanGilSpacing.md)) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(action, style = MaterialTheme.typography.titleMedium)
            }
        }
        // Recheck immediately at content composition as well as at the original click.
        val renderExpanded = visibleExpanded && mayRender()
        if (renderExpanded) Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg), content = content)
        SideEffect {
            // A read-only guard failure must discard intent, not resurrect it after P returns.
            if (expanded && !renderExpanded) collapse()
        }
    }
}
