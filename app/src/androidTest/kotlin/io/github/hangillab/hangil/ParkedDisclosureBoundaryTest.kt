package io.github.hangillab.hangil

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.designsystem.HanGilTheme
import io.github.hangillab.hangil.core.designsystem.ParkedDisclosure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import io.github.hangillab.hangil.core.designsystem.R as DesignR

/** Controlled component inputs only, never real SDK/Room faults, screenshots or performance data. */
@RunWith(AndroidJUnit4::class)
class ParkedDisclosureBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()

    @Test fun silentDeniedOpeningLeavesNoIntentAndFreshAllowedClickWorks() =
        withFixture { fixture ->
            val old = clickAction()
            compose.runOnIdle {
                fixture.allowed.set(false)
                old.invoke()
            }
            assertCollapsed()
            compose.runOnIdle {
                fixture.allowed.set(true)
                fixture.probe.intValue++
            }
            assertCollapsed()
            freshClick()
            assertExpanded(0)
        }

    @Test fun closingIsAlwaysAllowedWithoutCallingTheDeniedGuard() =
        withFixture { fixture ->
            freshClick()
            assertExpanded(0)
            val close = clickAction()
            compose.runOnIdle {
                fixture.allowed.set(false)
                val reads = fixture.guardReads.get()
                close.invoke()
                assertEquals("접기는 최신 허용 조건을 호출하지 않고 항상 가능해야 합니다", reads, fixture.guardReads.get())
            }
            assertCollapsed()
            compose.runOnIdle {
                fixture.allowed.set(true)
                fixture.probe.intValue++
            }
            assertCollapsed()
            freshClick()
            assertExpanded(0)
        }

    @Test fun aReadOnlyRenderDenialClearsTheRememberedOpeningIntent() =
        withFixture { fixture ->
            freshClick()
            assertExpanded(0)
            compose.runOnIdle {
                fixture.allowed.set(false)
                fixture.probe.intValue++
            }
            assertCollapsed()
            compose.runOnIdle {
                fixture.allowed.set(true)
                fixture.probe.intValue++
            }
            assertCollapsed()
            freshClick()
            assertExpanded(0)
        }

    @Test fun stoppedEpochNeverReauthorizesOldCallbacksAfterResume() =
        withFixture {
            val oldClosed = clickAction()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { oldClosed.invoke() }
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            assertCollapsed()
            compose.runOnIdle { oldClosed.invoke() }
            assertCollapsed()
            freshClick()
            assertExpanded(0)
            val oldOpened = clickAction()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            assertCollapsed()
            compose.runOnIdle { oldOpened.invoke() }
            assertCollapsed()
            freshClick()
            assertExpanded(0)
        }

    @Test fun capturedSourceGuardAndDisposedCallbackNeverAdoptANewerSource() =
        withFixture { fixture ->
            val sourceZero = clickAction()
            compose.runOnIdle {
                fixture.liveSource.set(1)
                fixture.source.intValue = 1
            }
            assertCollapsed()
            compose.runOnIdle { sourceZero.invoke() }
            assertCollapsed()
            freshClick()
            assertExpanded(1)
            compose.runOnIdle {
                fixture.liveSource.set(2)
                fixture.source.intValue = 2
            }
            // Existing source-1 intent cannot carry automatically into source 2.
            assertCollapsed()
            val beforeDispose = clickAction()
            compose.runOnIdle { fixture.shown.value = false }
            compose.waitForIdle()
            assertTrue("dispose 뒤 컴포넌트가 없어야 합니다", nodes(TOGGLE).isEmpty())
            compose.runOnIdle { fixture.shown.value = true }
            waitForToggle()
            compose.runOnIdle {
                val reads = fixture.guardReads.get()
                beforeDispose.invoke()
                assertEquals("폐기된 callback은 새 guard를 읽으면 안 됩니다", reads, fixture.guardReads.get())
            }
            assertCollapsed()
            freshClick()
            assertExpanded(2)
        }

    private fun withFixture(block: (Fixture) -> Unit) {
        assumeTrue(
            "공유 컴포넌트의 명시적인 제어 입력 검사에서만 실행합니다",
            InstrumentationRegistry.getArguments().getString("manualHistoryDisclosureBoundary") == "true",
        )
        val fixture = Fixture()
        var primaryFailure: Throwable? = null
        try {
            compose.setContent {
                HanGilTheme {
                    if (fixture.shown.value) {
                        val source = fixture.source.intValue
                        val probe = fixture.probe.intValue
                        val guard =
                            remember(source) {
                                {
                                    fixture.guardReads.incrementAndGet()
                                    fixture.allowed.get() && fixture.liveSource.get() == source
                                }
                            }
                        ParkedDisclosure(
                            title = "제어 입력 $source / $probe",
                            canExpand = guard,
                            modifier = Modifier.testTag(TOGGLE),
                        ) {
                            Text("검사 전용 내용 $source", Modifier.testTag(DETAIL))
                        }
                    }
                }
            }
            waitForToggle()
            assertCollapsed()
            block(fixture)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                if (!compose.activity.lifecycle.currentState
                        .isAtLeast(Lifecycle.State.RESUMED)
                ) {
                    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                }
                compose.runOnIdle { fixture.shown.value = false }
                compose.waitForIdle()
                assertTrue("검사 뒤 제어 컴포넌트를 폐기해야 합니다", nodes(TOGGLE).isEmpty() && nodes(DETAIL).isEmpty())
            } catch (cleanupFailure: Throwable) {
                val original = primaryFailure
                if (original == null) throw cleanupFailure
                original.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun waitForToggle() {
        compose.waitUntil(20_000) { nodes(TOGGLE).size == 1 }
        compose.waitForIdle()
    }

    private fun clickAction(): () -> Boolean {
        waitForToggle()
        return checkNotNull(
            nodes(TOGGLE)
                .single()
                .config
                .getOrNull(SemanticsActions.OnClick)
                ?.action,
        )
    }

    private fun freshClick() {
        compose.onNodeWithTag(TOGGLE, useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    private fun assertCollapsed() {
        waitForToggle()
        assertTrue("거부/재개 뒤 상세가 남으면 안 됩니다", nodes(DETAIL).isEmpty())
        assertEquals(
            text(DesignR.string.parked_detail_collapsed),
            nodes(TOGGLE)
                .single()
                .config
                .getOrNull(SemanticsProperties.StateDescription),
        )
    }

    private fun assertExpanded(source: Int) {
        compose.waitUntil(20_000) { nodes(DETAIL).size == 1 }
        assertTrue("현재 제어 source의 내용만 표시해야 합니다", nodeTexts(nodes(DETAIL).single()).contains("검사 전용 내용 $source"))
        assertEquals(
            text(DesignR.string.parked_detail_expanded),
            nodes(TOGGLE)
                .single()
                .config
                .getOrNull(SemanticsProperties.StateDescription),
        )
    }

    private fun nodeTexts(node: SemanticsNode): List<String> =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text } + node.children.flatMap(::nodeTexts)

    private fun nodes(tag: String): List<SemanticsNode> =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun text(resource: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resource)

    private class Fixture {
        val allowed = AtomicBoolean(true)
        val liveSource = AtomicInteger(0)
        val guardReads = AtomicInteger(0)
        val source = mutableIntStateOf(0)
        val probe = mutableIntStateOf(0)
        val shown = mutableStateOf(true)
    }

    private companion object {
        const val TOGGLE = "controlled_disclosure"
        const val DETAIL = "controlled_detail"
    }
}
