package io.github.hangillab.hangil

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.designsystem.HanGilTheme
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.NavigationGateway
import io.github.hangillab.hangil.core.domain.NavigationScreenResult
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.feature.home.NavigationScreenRoute
import io.github.hangillab.hangil.feature.home.NavigationScreenViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Real Compose/lifecycle and synthetic vehicle/SDK timing. Never calls the real Navi service. */
@RunWith(AndroidJUnit4::class)
class NavigationScreenBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()

    @Test fun silentDRejectsCapturedClickAndSilentPCannotResurrectIt() =
        fixture { f ->
            val old = clickAction()
            compose.runOnIdle { f.live.silent(parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))) }
            invoke(old)
            compose.runOnIdle { f.live.silent(parked()) }
            invoke(old)
            assertEquals(0, f.gateway.calls.get())
            compose.runOnIdle { f.live.emit(parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))) }
            compose.waitForIdle()
            compose.runOnIdle { f.live.emit(parked()) }
            waitOpen()
            compose.onNodeWithTag("navigation_screen_open").performScrollTo().performClick()
            compose.waitUntil(20_000) { f.gateway.calls.get() == 1 }
            assertTrue(f.gateway.guard())
            compose.runOnIdle { f.gateway.finish.complete(NavigationScreenResult.REQUESTED) }
            compose.waitUntil(20_000) { nodes("navigation_screen_result").size == 1 }
            invoke(old)
            assertEquals(1, f.gateway.calls.get())
        }

    @Test fun stoppedAndReenteredHostCannotReplayOldNativeClick() =
        fixture { f ->
            val paused = clickAction()
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            invoke(paused)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitOpen()
            invoke(paused)
            assertEquals(0, f.gateway.calls.get())
            val old = clickAction()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            invoke(old)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitOpen()
            invoke(old)
            assertEquals(0, f.gateway.calls.get())
            val beforeClose = clickAction()
            compose.runOnIdle { f.visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle { f.visible.value = true }
            waitOpen()
            invoke(beforeClose)
            assertEquals(0, f.gateway.calls.get())
            compose.onNodeWithTag("navigation_screen_open").performScrollTo().performClick()
            compose.waitUntil(20_000) { f.gateway.calls.get() == 1 }
        }

    @Test fun denialDuringConnectionRetiresDispatchAndDoesNotRetryAtP() =
        fixture { f ->
            compose.onNodeWithTag("navigation_screen_open").performScrollTo().performClick()
            compose.waitUntil(20_000) { f.gateway.calls.get() == 1 }
            compose.runOnIdle { f.live.emit(parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))) }
            compose.waitForIdle()
            assertFalse(f.gateway.guard())
            compose.runOnIdle { f.live.emit(parked()) }
            waitOpen()
            assertEquals(1, f.gateway.calls.get())
            assertTrue(nodes("navigation_screen_connecting").isEmpty())
            assertTrue(nodes("navigation_screen_result").isEmpty())
        }

    private fun fixture(block: (Fixture) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("manualNavigationScreenBoundary") == "true")
        val f = Fixture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val factory =
                object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        check(modelClass == NavigationScreenViewModel::class.java)
                        @Suppress("UNCHECKED_CAST")
                        return NavigationScreenViewModel(
                            object : VehicleGateway {
                                override val state = f.live
                            },
                            object : DrivingRestrictionsProvider {
                                override val restrictions =
                                    MutableStateFlow(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))
                            },
                            f.gateway,
                        ) as T
                    }
                }
            f.model = ViewModelProvider(compose.activity, factory)[NavigationScreenViewModel::class.java]
        }
        var primary: Throwable? = null
        try {
            compose.setContent {
                HanGilTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (f.visible.value) NavigationScreenRoute(f.model)
                    }
                }
            }
            waitOpen()
            assertEquals(0, f.gateway.calls.get())
            block(f)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                compose.runOnIdle { f.visible.value = false }
                compose.waitForIdle()
            } catch (cleanup: Throwable) {
                val original = primary
                if (original == null) throw cleanup else original.addSuppressed(cleanup)
            }
        }
    }

    private fun nodes(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    private fun waitOpen() {
        compose.waitUntil(20_000) { nodes("navigation_screen_open").size == 1 }
        compose.waitForIdle()
    }

    private fun clickAction(): () -> Boolean {
        compose.onNodeWithTag("navigation_screen_open").performScrollTo()
        return checkNotNull(nodes("navigation_screen_open").single().config[SemanticsActions.OnClick].action)
    }

    private fun invoke(action: () -> Boolean) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }
        compose.waitForIdle()
    }

    private class Fixture {
        val live = SilentState(parked())
        val gateway = Gateway()
        val visible = mutableStateOf(true)
        lateinit var model: NavigationScreenViewModel
    }

    private class Gateway : NavigationGateway {
        override val connection = MutableStateFlow(ConnectionState.DISCONNECTED)
        val calls = AtomicInteger(0)
        val finish = CompletableDeferred<NavigationScreenResult>()
        lateinit var guard: () -> Boolean

        override suspend fun requestForeground(canDispatch: () -> Boolean): NavigationScreenResult {
            calls.incrementAndGet()
            guard = canDispatch
            return finish.await()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
    private class SilentState<T>(
        initial: T,
        private val emitted: MutableStateFlow<T> = MutableStateFlow(initial),
    ) : StateFlow<T> by emitted {
        @Volatile private var live = initial
        override val value: T get() = live

        fun silent(value: T) {
            live = value
        }

        fun emit(value: T) {
            live = value
            emitted.value = value
        }
    }

    private companion object {
        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))
    }
}
