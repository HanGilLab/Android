package io.github.hangillab.hangil

import android.app.KeyguardManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import dagger.hilt.android.testing.UninstallModules
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.LocationGateway
import io.github.hangillab.hangil.core.domain.LocationReading
import io.github.hangillab.hangil.core.domain.LocationUpdateGateway
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.NavigationGateway
import io.github.hangillab.hangil.core.domain.NavigationScreenResult
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.core.vehicle.VehicleModule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.math.abs

/** Real Main, production Hilt VMs, Room and NavHost; vehicle/UX inputs are controlled, never SDK evidence. */
@HiltAndroidTest
@UninstallModules(VehicleModule::class)
@RunWith(AndroidJUnit4::class)
class FinishedReviewNextMainFlowTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val compose = createEmptyComposeRule()
    private lateinit var hilt: HiltAndroidRule
    private lateinit var host: MainActivity
    private lateinit var nextMemory: HistoryNextPracticeNavigationViewModel
    private val sdkCalls = AtomicInteger()
    private val vehicleValues = MutableStateFlow(VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK)))
    private val uxValues = MutableStateFlow(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))

    // HiltAndroidRule checks Application in its constructor. Construct even that rule only after opt-in.
    @get:Rule
    val manualHiltUi: TestRule =
        TestRule { base, description ->
            object : Statement() {
                override fun evaluate() {
                    assumeTrue(InstrumentationRegistry.getArguments().getString("manualFinishedReviewNextControlled") == "true")
                    check(instrument.targetContext.packageName == "io.github.hangillab.hangil.controlledmainui") {
                        "Use the isolated -PcontrolledMainUi=true build"
                    }
                    check(Build.VERSION.SDK_INT in 33..34) { "Use the isolated API 33 or 34 host" }
                    check(instrument.targetContext.applicationContext is HiltTestApplication) { "Use the opt-in Hilt UI runner" }
                    hilt = HiltAndroidRule(this@FinishedReviewNextMainFlowTest)
                    hilt.apply(compose.apply(base, description), description).evaluate()
                }
            }
        }

    @BindValue @JvmField
    val vehicle: VehicleGateway =
        object : VehicleGateway {
            override val state = vehicleValues
        }

    @BindValue @JvmField
    val restrictions: DrivingRestrictionsProvider =
        object : DrivingRestrictionsProvider {
            override val restrictions = uxValues
        }

    @BindValue @JvmField
    val navigation: NavigationGateway =
        object : NavigationGateway {
            override val connection = MutableStateFlow(ConnectionState.UNAVAILABLE)

            override suspend fun requestForeground(canDispatch: () -> Boolean): NavigationScreenResult = unexpectedSdk()
        }

    @BindValue @JvmField
    val location: LocationGateway =
        object : LocationGateway {
            override suspend fun readLastKnown(): LocationReading = unexpectedSdk()
        }

    @BindValue @JvmField
    val locationUpdates: LocationUpdateGateway =
        object : LocationUpdateGateway {
            override fun hasPermission(): Boolean = false

            override fun updates(
                canContinue: () -> Boolean,
                onRegistered: () -> Unit,
                onCleanupFailure: (Throwable) -> Unit,
            ): Flow<LocationUpdateReading> = unexpectedSdk()
        }

    @Inject lateinit var catalog: PracticeCatalog

    @Inject lateinit var preparation: PracticeRepository

    @Inject lateinit var sessions: PracticeSessionRepository

    @Test fun actualMainFinishReviewNextCancelChooseAndSaveWithControlledInputs() {
        val args = InstrumentationRegistry.getArguments()
        val scale = checkNotNull(args.getString("expectedFontScale")).toFloat()
        val dark = checkNotNull(args.getString("expectedDark")).toBooleanStrict()
        check(scale == 1f || scale == 1.3f)
        val configuration = instrument.targetContext.resources.configuration
        check(abs(configuration.fontScale - scale) < 0.01f)
        check((configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) == dark)
        check(!instrument.targetContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        hilt.inject()
        check(rows().isEmpty() && selection() == null) { "Start with a fresh isolated database" }
        val course = catalog.courses.first { it.tasks.size >= 2 && it.goalIds.isNotEmpty() }
        val goal = catalog.goals.first { it.id in course.goalIds }
        val firstTask = course.tasks.first()
        val editedTask = course.tasks.first { it.id != firstTask.id }
        val initialSelection = PracticeSelection(goal.id, course.id, course.version, course.briefingVersion, firstTask.id)
        val editedSelection = initialSelection.copy(taskId = editedTask.id)
        val captures =
            if (args.getString("captureFinishedReviewNextControlled") == "true") {
                File(instrument.targetContext.cacheDir, "screenshots/finished-review-next-main-${UUID.randomUUID()}").apply {
                    check(mkdirs())
                }
            } else {
                null
            }
        var scenario: ActivityScenario<MainActivity>? = null
        var primary: Throwable? = null
        try {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity {
                host = it
                nextMemory = ViewModelProvider(it)[HistoryNextPracticeNavigationViewModel::class.java]
            }
            click("home_prepare")
            click("goal_${goal.id}")
            click("preparation_next")
            click("course_${course.id}")
            click("preparation_next")
            click("task_${firstTask.id}")
            click("preparation_next")
            waitTag("session_start")
            check(selection() == initialSelection)
            val staleStart = action("session_start")
            editTask(editedTask.id)
            waitTag("saved_task")
            compose.onNodeWithTag("saved_task", useUnmergedTree = true).assertTextContains(editedTask.title)
            check(selection() == editedSelection)
            waitStartEnabled()
            invoke(staleStart)
            compose.onNodeWithTag("session_start", useUnmergedTree = true).assertIsEnabled()
            check(listOf("session_saving", "session_feedback", "session_active").all { nodes(it).isEmpty() }) {
                "The retired start must not dispatch a session write"
            }
            check(rows().isEmpty() && selection() == editedSelection)
            editTask(firstTask.id)
            waitTag("session_start")
            check(selection() == initialSelection)
            click("session_start")
            val started = awaitRows { rows -> rows.any { it.isActive } }.single { it.isActive }
            check(started.selection == initialSelection)
            editTask(editedTask.id)
            waitTag("session_active_task")
            waitTag("saved_task")
            compose.onNodeWithTag("session_active_task", useUnmergedTree = true).assertTextContains(firstTask.title)
            compose.onNodeWithTag("saved_task", useUnmergedTree = true).assertTextContains(editedTask.title)
            check(selection() == editedSelection && rows() == listOf(started))
            click("session_finish")
            val ended =
                awaitRows { rows -> rows.singleOrNull { it.sessionId == started.sessionId }?.isActive == false }.single {
                    it.sessionId ==
                        started.sessionId
                }
            check(ended == started.copy(endedAtEpochMillis = checkNotNull(ended.endedAtEpochMillis)))
            check(selection() == editedSelection)
            waitTag("session_finish_review")
            check(nodes("history_ready").isEmpty()) { "Finishing must not navigate automatically" }
            click("session_finish_review")
            assertExact(ended)
            capture(captures, "exact-review")
            val oldNext = action("history_prepare_next")
            instrument.runOnMainSync {
                vehicleValues.value = vehicleValues.value.copy(gear = VehicleReading.Available(GearPosition.DRIVE))
                uxValues.value = DrivingRestrictions(ParkingState.NOT_PARKED, UxRestrictionState.RESTRICTED)
            }
            waitTag("history_locked")
            invoke(oldNext)
            check(nextMemory.entry.value == null)
            capture(captures, "controlled-D-locked")
            instrument.runOnMainSync {
                vehicleValues.value = vehicleValues.value.copy(gear = VehicleReading.Available(GearPosition.PARK))
                uxValues.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
            }
            assertExact(ended)
            invoke(oldNext)
            check(nextMemory.entry.value == null && nodes("next_practice_ready").isEmpty())
            val currentNext = action("history_prepare_next")
            click("history_prepare_next")
            waitTag("next_practice_ready")
            val entry = checkNotNull(nextMemory.entry.value)
            check(entry.source == ended) { "Main must hand off the whole exact finish source" }
            invoke(currentNext)
            check(nextMemory.entry.value === entry) { "A duplicated action must not replace the actual Main navigation token" }
            compose.onNodeWithTag("next_practice_save").performScrollTo().assertIsNotEnabled()
            capture(captures, "next-unselected")
            click("next_task_${firstTask.id}")
            click("next_practice_close")
            assertExact(ended)
            check(nextMemory.entry.value == null && selection() == editedSelection)
            invoke(currentNext)
            check(nextMemory.entry.value == null && nodes("next_practice_ready").isEmpty())
            capture(captures, "cancelled-same-review")
            click("history_prepare_next")
            waitTag("next_practice_choose_first")
            click("next_task_${firstTask.id}")
            capture(captures, "next-choice-before-save")
            click("next_practice_save")
            assertExact(ended)
            check(selection() == initialSelection)
            click("history_close")
            waitTag("saved_task")
            compose.onNodeWithTag("saved_task", useUnmergedTree = true).assertTextContains(firstTask.title)
            waitTag("session_start")
            check(rows() == listOf(ended)) { "Saving next preparation must not change sessions" }
            capture(captures, "home-next-ready")
            click("session_recent_review")
            assertExact(ended)
            click("history_close")
            waitTag("session_start")
            check(selection() == initialSelection && rows() == listOf(ended))
            check(sdkCalls.get() == 0)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                scenario?.close()
                check(sdkCalls.get() == 0) { "A forbidden SDK boundary was invoked" }
            } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
        }
    }

    private fun unexpectedSdk(): Nothing {
        sdkCalls.incrementAndGet()
        throw AssertionError("SDK-free Main test invoked a forbidden location or navigation boundary")
    }

    private fun nodes(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes(false)

    private fun waitTag(tag: String) {
        compose.waitUntil(20_000) { nodes(tag).size == 1 }
        requireHost()
    }

    private fun requireHost() {
        // The actual Main window owns this native focus; an accessibility "active" window can be absent.
        compose.waitUntil(20_000) {
            var focusedMain = false
            instrument.runOnMainSync {
                focusedMain = host.lifecycle.currentState == Lifecycle.State.RESUMED &&
                    host.hasWindowFocus() && host.window.decorView.isAttachedToWindow &&
                    host.display?.let { it.isValid && it.displayId == Display.DEFAULT_DISPLAY } == true
            }
            focusedMain
        }
        check(!instrument.targetContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        compose.onRoot(useUnmergedTree = true).assertIsDisplayed()
    }

    private fun click(tag: String) {
        waitTag(tag)
        compose
            .onNodeWithTag(tag, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
        requireHost()
    }

    private fun waitStartEnabled() {
        compose.waitUntil(20_000) {
            nodes("session_start").singleOrNull()?.config?.getOrNull(SemanticsProperties.Disabled) == null &&
                nodes("session_start").size == 1
        }
        requireHost()
    }

    private fun editTask(taskId: String) {
        click("home_prepare")
        click("preparation_next")
        click("preparation_next")
        click("task_$taskId")
        click("preparation_next")
    }

    private fun action(tag: String): () -> Boolean {
        waitTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
        return checkNotNull(
            nodes(tag)
                .single()
                .config
                .getOrNull(SemanticsActions.OnClick)
                ?.action,
        )
    }

    private fun invoke(callback: () -> Boolean) {
        instrument.runOnMainSync { callback() }
        compose.waitForIdle()
    }

    private fun assertExact(source: PracticeSession) {
        waitTag("history_ready")
        check(
            nodes("history_course")
                .single()
                .config[SemanticsProperties.Text]
                .single()
                .text == source.courseTitle,
        )
        check(
            nodes("history_goal")
                .single()
                .config[SemanticsProperties.Text]
                .single()
                .text == source.goalTitle,
        )
        check(
            nodes("history_task")
                .single()
                .config[SemanticsProperties.Text]
                .single()
                .text == source.taskTitle,
        )
        check(nodes("history_older").isEmpty() && nodes("history_newer").isEmpty())
    }

    private fun rows(): List<PracticeSession> = runBlocking { withTimeout(20_000) { sessions.observeSessions().first() } }

    private fun awaitRows(predicate: (List<PracticeSession>) -> Boolean): List<PracticeSession> =
        runBlocking { withTimeout(20_000) { sessions.observeSessions().first(predicate) } }

    private fun selection(): PracticeSelection? = runBlocking { withTimeout(20_000) { preparation.observeSelection().first() } }

    private fun capture(
        directory: File?,
        name: String,
    ) {
        if (directory == null) return
        requireHost()
        compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
}
