package io.github.hangillab.hangil.feature.home

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.hangillab.hangil.core.designsystem.HanGilTheme
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.PracticeCourse
import io.github.hangillab.hangil.core.domain.PracticeGoal
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeTask
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.VehicleRecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

/** Host-rendered production Compose only: no vehicle SDK, permission, repository, or device calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w840dp-h600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeShortcutHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val task = PracticeTask("task", "교차로에서 한 가지 장면 복습하기", "실제 주행 평가는 포함하지 않습니다")
    private val practice =
        PreparedPractice(
            PracticeGoal("goal", "익숙한 길에서 준비하기", ""),
            PracticeCourse("course", 2, 3, "생활 도로와 교차로 연습", "시연용 코스", emptyList(), listOf(task), setOf("goal")),
            task,
        )
    private val finished =
        PracticeSession(
            "finished-source",
            PracticeSelection("goal", "course", 2, 3, "task"),
            practice.goal.title,
            practice.course.title,
            task.title,
            CourseReviewStatus.DEMO,
            1_000,
            2_000,
        )
    private val repeat =
        SessionUiState(
            allowsInteraction = true,
            preparation = PreparationLoadState.Loaded(Preparation.Ready(practice)),
            records = SessionLoadState.Loaded(listOf(finished)),
        )
    private val nextSources = mutableListOf<String>()
    private var starts = 0
    private var prepares = 0
    private var retries = 0

    @Test fun repeatOffersOnePrimaryShortcutInDefaultLight() = checkRepeat(1f, false, "repeat-default-light")

    @Test fun repeatOffersOnePrimaryShortcutWithLargeLightText() = checkRepeat(1.3f, false, "repeat-large-light")

    @Test fun repeatOffersOnePrimaryShortcutWithLargeDarkText() = checkRepeat(1.3f, true, "repeat-large-dark")

    @Test
    @Config(qualifiers = "w1680dp-h900dp-mdpi")
    fun repeatOffersOnePrimaryShortcutInWideLargeDarkLayout() = checkRepeat(1.3f, true, "repeat-wide-large-dark", wide = true)

    @Test fun recordLoadingFailureLockAndActiveSessionNeverOfferNextShortcut() {
        val state = mutableStateOf(repeat.copy(records = SessionLoadState.Loading))
        render(state)
        val blocked =
            listOf(
                state.value,
                repeat.copy(records = SessionLoadState.Failed),
                repeat.copy(allowsInteraction = false),
                repeat.copy(
                    records = SessionLoadState.Loaded(listOf(finished.copy(sessionId = "active", endedAtEpochMillis = null), finished)),
                ),
            )
        for (value in blocked) {
            compose.runOnIdle { state.value = value }
            compose.onNodeWithTag("home_next_practice").assertDoesNotExist()
            assertNoAction()
        }
        compose
            .onNodeWithTag("session_finish")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
        // Existing finished-source recovery does not depend on the current preparation read.
        for (preparation in listOf(PreparationLoadState.Loading, PreparationLoadState.Failed)) {
            compose.runOnIdle { state.value = repeat.copy(preparation = preparation) }
            compose.onAllNodesWithTag("home_next_practice").assertCountEquals(1)
            compose
                .onNodeWithTag("home_next_practice")
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsEnabled()
        }
        assertNoAction()
    }

    @Test fun savingDisablesBothRepeatActionsAndCourseChange() {
        render(mutableStateOf(repeat.copy(operation = SessionOperationState.Saving)))
        for (tag in listOf("home_next_practice", "session_start", "home_prepare")) {
            compose
                .onNodeWithTag(tag)
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsNotEnabled()
        }
        assertNoAction()
    }

    @Test fun confirmedEmptyPreparationShowsNoninteractiveIllustrationBeforeExplicitChoice() =
        checkFirstPreparation(1f, false, "first-confirmed-empty")

    @Test fun confirmedEmptyPreparationShowsNoninteractiveIllustrationWithLargeDarkText() =
        checkFirstPreparation(1.3f, true, "first-confirmed-empty-large-dark")

    private fun checkFirstPreparation(
        fontScale: Float,
        dark: Boolean,
        name: String,
    ) {
        val state =
            mutableStateOf(
                repeat.copy(
                    preparation = PreparationLoadState.Loaded(Preparation.Empty),
                    records = SessionLoadState.Loaded(emptyList()),
                ),
            )
        render(state, fontScale, dark)
        compose.onNodeWithTag("home_step_prepare").assertIsDisplayed()
        compose.onNodeWithTag("home_illustration").assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithTag("home_next_practice").assertDoesNotExist()
        compose.onNodeWithTag("session_start").assertDoesNotExist()
        compose.onAllNodesWithTag("home_prepare").assertCountEquals(1)
        assertNoAction()
        capture(name)
        checkReachableText("home_prepare")
        compose.onNodeWithTag("home_prepare").performClick()
        assertEquals(1, prepares)
        assertEquals(0, starts)
        assertTrue(nextSources.isEmpty())
    }

    private fun checkRepeat(
        fontScale: Float,
        dark: Boolean,
        name: String,
        wide: Boolean = false,
    ) {
        render(mutableStateOf(repeat), fontScale, dark, if (wide) 1680 else 840, if (wide) 900 else 600)
        compose.onNodeWithTag(if (wide) "home_dashboard_wide" else "home_dashboard_stacked").assertExists()
        compose.onNodeWithTag(if (wide) "home_dashboard_stacked" else "home_dashboard_wide").assertDoesNotExist()
        compose.onAllNodesWithTag("home_next_practice").assertCountEquals(1)
        val next = compose.onNodeWithTag("home_next_practice").getUnclippedBoundsInRoot()
        val start = compose.onNodeWithTag("session_start").getUnclippedBoundsInRoot()
        val prepare = compose.onNodeWithTag("home_prepare").getUnclippedBoundsInRoot()
        assertTrue("Next task must precede repeat", next.bottom <= start.top)
        assertTrue("Repeat must precede course change", start.bottom <= prepare.top)
        if (wide) {
            val course = compose.onNodeWithTag("saved_course").getUnclippedBoundsInRoot()
            assertTrue("Saved preparation must occupy the right column", course.left >= next.right)
        }
        assertNoAction()
        compose.onNodeWithTag("home_next_practice").performScrollTo().assertIsDisplayed()
        capture(name)
        checkReachableText("home_next_practice")
        compose.onNodeWithTag("home_next_practice").performClick()
        assertEquals(listOf(finished.sessionId), nextSources)
        assertEquals(0, starts)
        assertEquals(0, prepares)
        checkReachableText("session_start")
        compose.onNodeWithTag("session_start").performClick()
        assertEquals(1, starts)
        checkReachableText("home_prepare")
        compose.onNodeWithTag("home_prepare").performClick()
        assertEquals(1, prepares)
        assertEquals(listOf(finished.sessionId), nextSources)
    }

    private fun render(
        state: State<SessionUiState>,
        fontScale: Float = 1f,
        dark: Boolean = false,
        expectedWidthDp: Int = 840,
        expectedHeightDp: Int = 600,
    ) {
        val configuration = compose.activity.resources.configuration
        assertEquals("The real Activity must use the configured viewport width", expectedWidthDp, configuration.screenWidthDp)
        assertEquals("The real Activity must use the configured viewport height", expectedHeightDp, configuration.screenHeightDp)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                HanGilTheme(darkTheme = dark) {
                    val current = state.value
                    HomeScreen(
                        HomeUiState(
                            allowsDetailedInteraction = current.allowsInteraction,
                            preparation = current.preparation,
                        ),
                        onRetry = { retries++ },
                        onMapPreview = {},
                        canExpandPreparation = { false },
                        sessionContent = {
                            SessionPanel(
                                current,
                                onStart = { starts++ },
                                onFinish = {},
                                onRetry = { retries++ },
                                onPrepare = { prepares++ },
                                onPrepareNext = { nextSources += it },
                                canExpandGuide = { _, _ -> false },
                                canExpandRecent = { false },
                                canPrepare = current.preparation is PreparationLoadState.Loaded,
                                recordingState = VehicleRecordingState.Idle,
                                onRetryRecording = {},
                            )
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertEquals(configuration.screenWidthDp.toFloat(), (root.right - root.left).value, 1f)
        assertEquals(configuration.screenHeightDp.toFloat(), (root.bottom - root.top).value, 1f)
    }

    private fun checkReachableText(tag: String) {
        val button = compose.onNodeWithTag(tag)
        button
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHeightIsAtLeast(72.dp)
        val bounds = button.getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue("$tag must fit horizontally", bounds.left >= root.left && bounds.right <= root.right)
        assertTrue("$tag must fit the viewport after scrolling", bounds.top >= root.top && bounds.bottom <= root.bottom)
        val textNodes =
            compose
                .onAllNodes(
                    hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
        assertTrue("$tag must contain rendered text", textNodes.isNotEmpty())
        compose.runOnIdle {
            for (node in textNodes) {
                val results = mutableListOf<TextLayoutResult>()
                assertTrue(node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results))
                assertTrue(results.isNotEmpty())
                val textBounds = node.boundsInRoot
                val buttonBounds = button.fetchSemanticsNode().boundsInRoot
                assertTrue(
                    "$tag text must stay within its button",
                    textBounds.left >= buttonBounds.left && textBounds.right <= buttonBounds.right,
                )
                assertTrue(
                    "$tag text must stay within its button height",
                    textBounds.top >= buttonBounds.top && textBounds.bottom <= buttonBounds.bottom,
                )
                for (layout in results) {
                    // String Text semantics rebuilds MultiParagraph at maxWidth while retaining
                    // its original natural size in Compose 1.9.4. Check actual line bounds instead
                    // of treating that reconstructed paragraph's unused width as clipped glyphs.
                    assertFalse("$tag text must fit its height", layout.didOverflowHeight)
                    for (line in 0 until layout.lineCount) {
                        assertFalse("$tag text must not ellipsize", layout.isLineEllipsized(line))
                        assertTrue(
                            "$tag line must fit its actual width",
                            layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= textBounds.width + 1f,
                        )
                        assertTrue(
                            "$tag line must fit its actual height",
                            layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= textBounds.height + 1f,
                        )
                    }
                    val input = layout.layoutInput
                    val measured =
                        TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection, cacheSize = 0).measure(
                            text = input.text,
                            style = input.style,
                            overflow = input.overflow,
                            softWrap = input.softWrap,
                            maxLines = input.maxLines,
                            placeholders = input.placeholders,
                            constraints = Constraints(maxWidth = textBounds.width.roundToInt(), maxHeight = textBounds.height.roundToInt()),
                        )
                    assertFalse("$tag must fit when remeasured in the actual node with the same font", measured.hasVisualOverflow)
                    assertEquals("$tag must include the entire label", input.text.length, measured.getLineEnd(measured.lineCount - 1))
                    for (line in 0 until measured.lineCount) {
                        assertFalse(
                            "$tag remeasured text must not ellipsize",
                            measured.isLineEllipsized(line),
                        )
                    }
                }
            }
        }
    }

    private fun assertNoAction() {
        assertEquals(0, starts)
        assertEquals(0, prepares)
        assertEquals(0, retries)
        assertTrue(nextSources.isEmpty())
    }

    private fun capture(name: String) {
        val directory = System.getenv("HANGIL_HOST_UI_OUTPUT_DIR")?.let(::File) ?: return
        check(directory.isDirectory || directory.mkdirs())
        // Compose captureToImage forces a Window redraw that is unsupported by this host.
        // Draw the same production hierarchy through Robolectric's native Android Canvas instead.
        val bitmap =
            compose.runOnIdle {
                val view = compose.activity.window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).apply { view.draw(Canvas(this)) }
            }
        val sampledColors =
            (0 until bitmap.width step 24).flatMap { x ->
                (0 until bitmap.height step 24).map { y -> bitmap.getPixel(x, y) }
            }
        assertTrue("Host capture must contain rendered content", sampledColors.distinct().size > 1)
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
