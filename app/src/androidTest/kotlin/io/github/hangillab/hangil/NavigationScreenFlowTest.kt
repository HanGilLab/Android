package io.github.hangillab.hangil

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import io.github.hangillab.hangil.feature.home.R as HomeR

/** Actual Main/SDK/default-navigation foreground test. Never requests route, info, or location. */
@RunWith(AndroidJUnit4::class)
class NavigationScreenFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val evidence by lazy {
        File(context.cacheDir, "screenshots/native-navigation-screen-${UUID.randomUUID()}").apply { check(mkdirs()) }
    }

    @Test fun actualDefaultNavigationOpensAndFreshHostReturnsWithoutDataWrites() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("실제 전면 전환과 원래 자료 보존을 준비한 실행에서만 검사합니다", args.getString("manualNativeNavigationScreen") == "true")
        val expectedNavigationPackage = checkNotNull(args.getString("expectedNavigationPackage"))
        assertTrue(
            "같은 일반 SDK 서비스와 다른 실제 기본 내비 앱이어야 합니다",
            expectedNavigationPackage != context.packageName && expectedNavigationPackage.startsWith("ai."),
        )
        assertTrue(
            "전용 debug API34만 검사합니다",
            context.packageName == "io.github.hangillab.hangil.debug" && Build.VERSION.SDK_INT == 34 &&
                context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
        val font = checkNotNull(args.getString("expectedFontScale")).toFloat()
        val dark = args.getString("expectedDark") == "true"
        val configuration = context.resources.configuration
        assertTrue("명시한 실제 글꼴을 사용해야 합니다", kotlin.math.abs(configuration.fontScale - font) < 0.001f)
        assertTrue(
            "명시한 실제 테마를 사용해야 합니다",
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) == dark,
        )
        assertPermissionBoundary()
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, RecordingTestEntryPoint::class.java)
        val before = snapshot(graph)
        assertTrue("활성 연습은 새로 만들거나 변경하지 않습니다", before.sessions.none { it.isActive })
        assertTrue("원래 준비/이력을 유지한 실제 앱이어야 합니다", before.preparation != null && before.sessions.isNotEmpty())
        var primary: Throwable? = null
        try {
            awaitAllowed(graph)
            awaitPackage(context.packageName)
            waitTag("navigation_screen_open")
            verifyGlyphs("navigation_screen_scope")
            verifyGlyphs("navigation_screen_open")
            assertText("navigation_screen_scope", context.getString(HomeR.string.navigation_screen_scope))
            capture("main-before-request")
            val old = clickAction()
            compose.onNodeWithTag("navigation_screen_open").performScrollTo().performClick()
            awaitStableNavigation(expectedNavigationPackage)
            capture("actual-navigation-first")
            phase("NAVI_SCREEN_ACTUAL_FOREGROUND_OBSERVED")
            returnToMain()
            waitTag("navigation_screen_open")
            assertTrue(
                "복귀한 호스트는 옛 결과나 연결 중 상태를 가져오면 안 됩니다",
                nodes("navigation_screen_result").isEmpty() && nodes("navigation_screen_connecting").isEmpty(),
            )
            instrumentation.runOnMainSync { old() }
            compose.waitForIdle()
            assertTrue("옛 클릭은 복귀한 Main을 떠나면 안 됩니다", packageNow() == context.packageName)
            capture("main-fresh-return")
            val beforeDriving = clickAction()
            phase("NAVI_SCREEN_AWAIT_D")
            runBlocking {
                withTimeout(90_000) {
                    combine(graph.vehicle().state, graph.restrictions().restrictions) { v, r -> v to r }
                        .first { (v, r) -> v.gear == VehicleReading.Available(GearPosition.DRIVE) && !allowsPracticeInteraction(v, r) }
                }
            }
            waitTag("home_locked_title")
            assertTrue("D에서는 내비 상세 버튼이 없어야 합니다", nodes("navigation_screen_open").isEmpty())
            instrumentation.runOnMainSync { beforeDriving() }
            compose.waitForIdle()
            assertTrue("실제 D의 옛 클릭은 Main을 떠나면 안 됩니다", packageNow() == context.packageName)
            capture("main-real-d-locked")
            phase("NAVI_SCREEN_AWAIT_P")
            awaitAllowed(graph, 90_000)
            waitTag("navigation_screen_open")
            assertTrue(
                "P 복귀만으로 요청을 실행하면 안 됩니다",
                nodes("navigation_screen_result").isEmpty() && nodes("navigation_screen_connecting").isEmpty(),
            )
            instrumentation.runOnMainSync { beforeDriving() }
            compose.waitForIdle()
            assertTrue("옛 P 클릭은 새 P 호스트를 빌리면 안 됩니다", packageNow() == context.packageName)
            compose.onNodeWithTag("navigation_screen_open").performScrollTo().performClick()
            awaitStableNavigation(expectedNavigationPackage)
            capture("actual-navigation-fresh-request")
            returnToMain()
            waitTag("navigation_screen_open")
            verifyGlyphs("navigation_screen_scope")
            verifyGlyphs("navigation_screen_open")
            capture("main-final-return")
            assertTrue("모든 원래 domain 자료가 정확히 유지돼야 합니다", before == snapshot(graph))
            assertPermissionBoundary()
            phase("NAVI_SCREEN_COMPLETED")
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                returnToMain()
                assertTrue("실패해도 원래 자료를 지우거나 대체하지 않습니다", before == snapshot(graph))
            } catch (cleanup: Throwable) {
                val original = primary
                if (original == null) throw cleanup else original.addSuppressed(cleanup)
            }
        }
    }

    private fun assertPermissionBoundary() {
        val permissions =
            context.packageManager
                .getPackageInfo(
                    context.packageName,
                    PackageManager.GET_PERMISSIONS,
                ).requestedPermissions
                .orEmpty()
        assertTrue(
            "화면 권한만 정상 선언/허용되어야 합니다",
            "pleos.car.permission.NAVI_CUSTOM_MAP" in permissions &&
                context.checkSelfPermission("pleos.car.permission.NAVI_CUSTOM_MAP") == PackageManager.PERMISSION_GRANTED,
        )
        assertTrue(
            "경로나 경로 정보 권한으로 확대하지 않습니다",
            listOf("pleos.car.permission.NAVI_ROUTE", "pleos.car.permission.NAVI_ROUTE_SEARCH").none {
                it in
                    permissions
            },
        )
        val info = context.packageManager.getPermissionInfo("pleos.car.permission.NAVI_CUSTOM_MAP", 0)
        assertTrue(
            "이 검사에서 새 dangerous 허용 흐름을 대신하면 안 됩니다",
            info.protectionLevel and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE ==
                android.content.pm.PermissionInfo.PROTECTION_NORMAL,
        )
    }

    private fun awaitAllowed(
        graph: RecordingTestEntryPoint,
        timeout: Long = 25_000,
    ) = runBlocking {
        withTimeout(timeout) {
            combine(graph.vehicle().state, graph.restrictions().restrictions) { v, r -> v to r }.first { (v, r) ->
                allowsPracticeInteraction(v, r)
            }
        }
        Unit
    }

    private fun snapshot(graph: RecordingTestEntryPoint): ExistingData =
        runBlocking {
            withTimeout(20_000) {
                val sessions = graph.sessions().observeSessions().first()
                ExistingData(
                    graph.preparation().observeSelection().first(),
                    sessions,
                    sessions.map { graph.observations().observe(it.sessionId).first() },
                    sessions.map { graph.memos().observe(it.sessionId).first() },
                )
            }
        }

    private fun packageNow() =
        instrumentation.uiAutomation.rootInActiveWindow
            ?.packageName
            ?.toString()

    private fun awaitPackage(expected: String) {
        val start = SystemClock.elapsedRealtime()
        while (packageNow() != expected && SystemClock.elapsedRealtime() - start < 25_000) SystemClock.sleep(50)
        assertTrue("실제 전면 패키지가 요청한 앱이어야 합니다", packageNow() == expected)
    }

    private fun awaitStableNavigation(expected: String) {
        awaitPackage(expected)
        // Accessibility can change before the compositor presents the new window.
        // This settling wait is outside every performance measurement.
        instrumentation.uiAutomation.waitForIdle(1_000, 10_000)
        SystemClock.sleep(1_000)
        assertTrue("전환 뒤에도 실제 내비 전면이 유지돼야 합니다", packageNow() == expected)
    }

    private fun returnToMain() {
        context.startActivity(
            Intent(
                context,
                MainActivity::class.java,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        awaitPackage(context.packageName)
        compose.waitForIdle()
    }

    private fun nodes(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    private fun waitTag(tag: String) {
        compose.waitUntil(20_000) { nodes(tag).size == 1 }
        compose.waitForIdle()
    }

    private fun clickAction(): () -> Boolean {
        compose.onNodeWithTag("navigation_screen_open").performScrollTo()
        return checkNotNull(nodes("navigation_screen_open").single().config[SemanticsActions.OnClick].action)
    }

    private fun assertText(
        tag: String,
        expected: String,
    ) {
        val node = nodes(tag).single()
        assertTrue("실제 원문과 일치해야 합니다", node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == expected } == true)
    }

    private fun verifyGlyphs(tag: String) {
        waitTag(tag)
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val node = nodes(tag).single()
        val bounds = node.boundsInRoot
        assertTrue(
            "요소가 실제 앱 화면 안에 있어야 합니다",
            bounds.left >= -1 && bounds.top >= -1 && bounds.right <= compose.activity.window.decorView.width + 1 &&
                bounds.bottom <= compose.activity.window.decorView.height + 1,
        )
        if (tag == "navigation_screen_scope") {
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(
                "실제 TextLayout을 검사해야 합니다",
                node.config
                    .getOrNull(SemanticsActions.GetTextLayoutResult)
                    ?.action
                    ?.invoke(layouts) == true && layouts.isNotEmpty(),
            )
            assertTrue(
                "기본/확대 글자가 생략되거나 잘리면 안 됩니다",
                layouts.none { it.hasVisualOverflow || (0 until it.lineCount).any(it::isLineEllipsized) },
            )
        }
    }

    private fun capture(name: String) {
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(evidence, "$name.png").outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            screenshot.recycle()
        }
    }

    private fun phase(name: String) {
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "$name\n") })
    }

    private data class ExistingData(
        val preparation: PracticeSelection?,
        val sessions: List<PracticeSession>,
        val observations: List<List<SessionVehicleObservation>>,
        val memos: List<SessionMemo?>,
    )
}
