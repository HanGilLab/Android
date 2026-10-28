package io.github.hangillab.hangil

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.designsystem.HanGilTheme
import io.github.hangillab.hangil.core.designsystem.PracticeBadge
import io.github.hangillab.hangil.core.designsystem.PracticeButton
import io.github.hangillab.hangil.core.designsystem.PracticeChoice
import io.github.hangillab.hangil.core.designsystem.PracticeEmptyNotice
import io.github.hangillab.hangil.core.designsystem.PracticeErrorNotice
import io.github.hangillab.hangil.core.designsystem.PracticeLoadingNotice
import io.github.hangillab.hangil.core.designsystem.PracticeLockedNotice
import io.github.hangillab.hangil.core.designsystem.PracticePage
import io.github.hangillab.hangil.core.designsystem.PracticePanel
import io.github.hangillab.hangil.core.designsystem.PracticeTextButton
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Injected presentation states only. Never substitutes for SDK/DB fault or parking tests. */
@RunWith(AndroidJUnit4::class)
class ComponentCatalogFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()

    @Test fun sharedStatesActionsAndDisabledControls() {
        assertEnvironment()
        compose.setContent { Catalog() }
        compose.onNodeWithText("공통 UI 상태 점검").assertIsDisplayed()
        capture("card", "card")
        touch("primary")
        compose.onNodeWithTag("primary_count").assertTextEquals("주요 행동: 1")
        touch("secondary")
        compose.onNodeWithTag("secondary_count").assertTextEquals("보조 행동: 1")
        compose
            .onNodeWithTag("disabled")
            .performScrollTo()
            .assertIsNotEnabled()
            .assertHeightIsAtLeast(76.dp)
            .assertWidthIsAtLeast(64.dp)
            .performTouchInput { click() }
        compose.onNodeWithTag("primary_count").assertTextEquals("주요 행동: 1")
        capture("disabled", "actions")

        compose.onNodeWithTag("choice_a").assertIsSelected()
        touch("choice_b")
        compose
            .onNodeWithTag("choice_b")
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertHeightIsAtLeast(120.dp)
        compose.onNodeWithTag("choice_a").assertIsNotSelected()
        capture("choice_b", "selected")
        compose
            .onNodeWithTag("choice_disabled")
            .performScrollTo()
            .assertIsNotEnabled()
            .assertHeightIsAtLeast(120.dp)
            .performTouchInput { click() }
        compose.onNodeWithTag("choice_b").assertIsSelected()
        compose.onNodeWithTag("choice_disabled").assertIsNotSelected()
        capture("choice_disabled", "unavailable")

        capture("loading", "loading")
        compose
            .onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
            .assertIsDisplayed()
        capture("empty", "empty")
        capture("error", "error")
        touch("retry")
        compose.onNodeWithTag("retry_count").assertTextEquals("재시도 요청: 1")
        capture("retry_count", "retry")
        capture("locked", "locked")
    }

    @Composable
    private fun Catalog() {
        var primaryCount by remember { mutableIntStateOf(0) }
        var secondaryCount by remember { mutableIntStateOf(0) }
        var retryCount by remember { mutableIntStateOf(0) }
        var selected by remember { mutableIntStateOf(0) }
        HanGilTheme {
            Surface(Modifier.fillMaxSize()) {
                Column {
                    Column(Modifier.padding(horizontal = HanGilSpacing.pageInset, vertical = HanGilSpacing.sm)) {
                        Text("공통 UI 상태 점검", style = MaterialTheme.typography.titleLarge)
                        Text("표시 예시, 실제 SDK/저장소 상태 아님", style = MaterialTheme.typography.bodyMedium)
                    }
                    Box(Modifier.weight(1f)) {
                        PracticePage {
                            PracticePanel(Modifier.testTag("card")) {
                                Text("주차 화면의 기본 카드", style = MaterialTheme.typography.headlineSmall)
                                PracticeBadge("시연용 표시 예시")
                                Text("긴 설명도 여러 줄로 표시하며 색만으로 상태를 전달하지 않아요.")
                            }
                            PracticeButton(onClick = { primaryCount++ }, modifier = Modifier.fillMaxWidth().testTag("primary")) {
                                Text("연습 준비", style = MaterialTheme.typography.titleLarge)
                            }
                            Text("주요 행동: $primaryCount", Modifier.testTag("primary_count"))
                            PracticeTextButton(onClick = { secondaryCount++ }, modifier = Modifier.testTag("secondary")) {
                                Text("돌아가기", style = MaterialTheme.typography.titleLarge)
                            }
                            Text("보조 행동: $secondaryCount", Modifier.testTag("secondary_count"))
                            PracticeButton(onClick = { primaryCount++ }, modifier = Modifier.testTag("disabled"), enabled = false) {
                                Text("처리 중에는 누를 수 없어요")
                            }
                            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.md)) {
                                PracticeChoice(
                                    "연습 구간 A",
                                    "선택 상태를 재현하는 예시이며 실제 지도 좌표가 아니에요.",
                                    selected == 0,
                                    { selected = 0 },
                                    Modifier.testTag("choice_a"),
                                )
                                PracticeChoice(
                                    "연습 구간 B",
                                    "설명을 읽고 다른 항목을 선택해 볼 수 있어요. 긴 설명도 생략하지 않아요.",
                                    selected == 1,
                                    { selected = 1 },
                                    Modifier.testTag("choice_b"),
                                )
                                PracticeChoice("현재 선택할 수 없는 구간", "이 예시는 비활성 상태이며 누르더라도 선택이 바뀌지 않아요.", selected == 2, {
                                    selected = 2
                                }, Modifier.testTag("choice_disabled"), enabled = false)
                            }
                            PracticeLoadingNotice("기록을 불러오는 표시 예시예요. 완료나 오류로 추정하지 않아요.", Modifier.testTag("loading"))
                            PracticeEmptyNotice(
                                "표시할 기록이 없어요",
                                "빈 결과를 명시적으로 받은 화면의 표시 예시예요. 미확인이나 오류를 빈 결과로 바꾸지 않아요.",
                                Modifier.testTag("empty"),
                            )
                            PracticeErrorNotice(
                                "기록을 불러오지 못했어요",
                                "이 화면은 오류 표시 예시예요. 다시 시도 버튼은 요청만 전달하며 성공을 뜻하지 않아요.",
                                Modifier.testTag("error"),
                            ) {
                                PracticeTextButton(onClick = { retryCount++ }, modifier = Modifier.testTag("retry")) {
                                    Text("다시 시도", style = MaterialTheme.typography.titleLarge)
                                }
                            }
                            Text("재시도 요청: $retryCount", Modifier.testTag("retry_count"))
                            Box(Modifier.testTag("locked")) { PracticeLockedNotice() }
                        }
                    }
                }
            }
        }
    }

    private fun assertEnvironment() {
        val args = InstrumentationRegistry.getArguments()
        val mode = checkNotNull(args.getString("theme_mode"))
        val scale = checkNotNull(args.getString("font_scale")).toFloat()
        val expected =
            when (mode) {
                "light" -> Configuration.UI_MODE_NIGHT_NO
                "dark" -> Configuration.UI_MODE_NIGHT_YES
                else -> error("Explicit theme_mode required")
            }
        val configuration = compose.activity.resources.configuration
        assertEquals("io.github.hangillab.hangil.debug", compose.activity.packageName)
        assertEquals(expected, configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals(scale, configuration.fontScale, 0.01f)
    }

    private fun touch(tag: String) {
        compose
            .onNodeWithTag(tag)
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(64.dp)
            .assertWidthIsAtLeast(64.dp)
            .performClick()
    }

    private fun capture(
        tag: String,
        name: String,
    ) {
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("공통 UI 상태 점검").assertIsDisplayed()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val expected = compose.activity.packageName
        compose.waitUntil(20_000) {
            val root = automation.rootInActiveWindow
            root?.packageName?.toString() == expected && compose.activity.hasWindowFocus()
        }
        val args = InstrumentationRegistry.getArguments()
        val prefix = "components-${args.getString("theme_mode")}-${args.getString("font_scale")}"
        val bitmap = checkNotNull(automation.takeScreenshot())
        val directory = File(compose.activity.cacheDir, "component-preview").apply { mkdirs() }
        File(directory, "$prefix-$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
