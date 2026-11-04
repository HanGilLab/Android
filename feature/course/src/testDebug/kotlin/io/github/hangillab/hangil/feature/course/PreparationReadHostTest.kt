package io.github.hangillab.hangil.feature.course

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.hangillab.hangil.core.designsystem.HanGilTheme
import io.github.hangillab.hangil.core.domain.PracticeGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real production Compose with controlled screen inputs; no repository, SDK or device calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w840dp-h600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreparationReadHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val goals =
        listOf(
            PracticeGoal("a", "익숙한 길에서 준비하기", "저장한 목표를 확인해요"),
            PracticeGoal("b", "다른 목표로 준비하기", "직접 바꿀 수 있어요"),
        )
    private val prefilled =
        CourseUiState(allowsInteraction = true, goals = goals, goal = goals.first(), preparationRead = PreparationReadState.READY)
    private val chosenGoals = mutableListOf<String>()
    private var next = 0
    private var back = 0
    private var retries = 0

    @Test fun readStatesInDefaultLight() = checkReadStates(1f, false, "default-light")

    @Test fun readStatesWithLargeLightText() = checkReadStates(1.3f, false, "large-light")

    @Test fun readStatesWithLargeDarkText() = checkReadStates(1.3f, true, "large-dark")

    @Test
    @Config(qualifiers = "w1680dp-h900dp-mdpi")
    fun readStatesInWideLargeDarkLayout() = checkReadStates(1.3f, true, "wide-large-dark", 1680, 900)

    private fun checkReadStates(
        fontScale: Float,
        dark: Boolean,
        name: String,
        width: Int = 840,
        height: Int = 600,
    ) {
        val state = mutableStateOf(prefilled)
        render(state, fontScale, dark, width, height)
        compose.onNodeWithTag(if (width == 1680) "preparation_choices_wide" else "preparation_choices_stacked").assertExists()
        if (width == 1680) {
            val first = compose.onNodeWithTag("goal_a").getUnclippedBoundsInRoot()
            val second = compose.onNodeWithTag("goal_b").getUnclippedBoundsInRoot()
            assertTrue("The second goal must occupy the right column", second.left >= first.right)
        }
        compose
            .onNodeWithTag("goal_a")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsSelected()
        compose.onNodeWithTag("goal_b").assertIsNotSelected()
        assertEquals(listOf(0, 0, 0), listOf(next, back, retries))
        assertTrue(chosenGoals.isEmpty())
        action(compose.onNodeWithTag("preparation_next")).performClick()
        action(compose.onNodeWithText("돌아가기")).performClick()
        assertEquals(listOf(1, 1, 0), listOf(next, back, retries))

        compose.runOnIdle { state.value = prefilled.copy(preparationRead = PreparationReadState.LOADING) }
        compose.onNodeWithTag("preparation_read_loading").assertIsDisplayed()
        capture("$name-loading")
        compose.onNodeWithText("준비 내용을 불러오고 있어요.").assertIsDisplayed()
        assertChoicesAndNextAbsent()
        compose.onNodeWithTag("preparation_title").assertDoesNotExist()
        compose.onNodeWithTag("preparation_read_retry").assertDoesNotExist()
        action(compose.onNodeWithText("돌아가기")).performClick()
        assertEquals(2, back)

        compose.runOnIdle { state.value = prefilled.copy(preparationRead = PreparationReadState.FAILED) }
        compose.onNodeWithTag("preparation_read_loading").assertDoesNotExist()
        compose.onNodeWithTag("preparation_read_failed").assertIsDisplayed()
        compose.onNodeWithText("준비 내용을 확인하지 못했어요.").assertIsDisplayed()
        assertChoicesAndNextAbsent()
        compose.onNodeWithTag("preparation_title").assertDoesNotExist()
        capture("$name-failed")
        action(compose.onNodeWithTag("preparation_read_retry")).performClick()
        assertEquals(1, retries)
        assertEquals(1, next)
        assertTrue(chosenGoals.isEmpty())

        compose.runOnIdle { state.value = prefilled.copy(goal = null, preparationRead = PreparationReadState.OUTDATED) }
        compose.onNodeWithTag("preparation_read_outdated").assertIsDisplayed()
        capture("$name-outdated")
        compose.onNodeWithText("저장된 준비가 바뀌었어요. 다시 선택해 주세요.").assertIsDisplayed()
        compose.onNodeWithTag("preparation_read_failed").assertDoesNotExist()
        compose.onNodeWithTag("preparation_read_retry").assertDoesNotExist()
        compose
            .onNodeWithTag("goal_a")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotSelected()
        compose.onNodeWithTag("goal_b").assertIsNotSelected()
        compose
            .onNodeWithTag("preparation_next")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
        action(compose.onNodeWithTag("goal_b")).performClick()
        assertEquals(listOf("b"), chosenGoals)

        compose.runOnIdle { state.value = prefilled }
        compose.onNodeWithTag("preparation_read_outdated").assertDoesNotExist()
        compose
            .onNodeWithTag("goal_a")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsSelected()
        capture("$name-ready")
        assertEquals(listOf(1, 2, 1), listOf(next, back, retries))
        assertEquals(listOf("b"), chosenGoals)

        compose.runOnIdle { state.value = prefilled.copy(allowsInteraction = false, preparationRead = PreparationReadState.FAILED) }
        compose.onNodeWithTag("preparation_locked_title").assertIsDisplayed()
        compose.onNodeWithTag("preparation_read_failed").assertDoesNotExist()
        compose.onNodeWithTag("preparation_read_retry").assertDoesNotExist()
        compose.onNodeWithTag("preparation_read_outdated").assertDoesNotExist()
        assertChoicesAndNextAbsent()
        action(compose.onNodeWithText("돌아가기")).performClick()
        assertEquals(listOf(1, 3, 1), listOf(next, back, retries))
        assertEquals(listOf("b"), chosenGoals)
    }

    private fun render(
        state: State<CourseUiState>,
        fontScale: Float,
        dark: Boolean,
        width: Int,
        height: Int,
    ) {
        val configuration = compose.activity.resources.configuration
        assertEquals(width, configuration.screenWidthDp)
        assertEquals(height, configuration.screenHeightDp)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                HanGilTheme(darkTheme = dark) {
                    CourseScreen(
                        state.value,
                        onGoal = { chosenGoals += it },
                        onCourse = { error("No course choice was requested") },
                        onTask = { error("No task choice was requested") },
                        onNext = { next++ },
                        onBack = { back++ },
                        onRetryPreparationRead = { retries++ },
                        canExpandDetails = { false },
                    )
                }
            }
        }
        compose.waitForIdle()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertEquals(width.toFloat(), (root.right - root.left).value, 1f)
        assertEquals(height.toFloat(), (root.bottom - root.top).value, 1f)
    }

    private fun assertChoicesAndNextAbsent() {
        listOf("goal_choices", "course_choices", "briefing_tasks", "preparation_next").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
    }

    private fun action(node: SemanticsNodeInteraction): SemanticsNodeInteraction {
        node
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHeightIsAtLeast(64.dp)
        val bounds = node.getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue(
            "Action must fit the viewport",
            bounds.left >= root.left && bounds.right <= root.right && bounds.top >= root.top && bounds.bottom <= root.bottom,
        )
        return node
    }

    private fun capture(name: String) {
        val directory = System.getenv("HANGIL_HOST_UI_OUTPUT_DIR")?.let(::File) ?: return
        check(directory.isDirectory || directory.mkdirs())
        val bitmap =
            compose.runOnIdle {
                val view = compose.activity.window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).apply { view.draw(Canvas(this)) }
            }
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
