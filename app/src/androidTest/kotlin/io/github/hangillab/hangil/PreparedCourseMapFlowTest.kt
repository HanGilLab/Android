package io.github.hangillab.hangil

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.AtomicFile
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseMapPreview
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.PracticeCourse
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.tile.TileOperation
import org.maplibre.geojson.Point
import java.io.File
import java.io.FileOutputStream
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Explicit -e manualPreparedCourseMap true on the dedicated debug app, with external DDS P/D/P.
 * Uses two current catalog previews and the real repository, SDK, native renderer and data graph.
 * It never creates sessions, appends observations, edits notes or changes permissions/network.
 * Original preparation must exist: the repository has no clear-selection API for restoring null.
 * Only confirmed owned selections can be restored, with actual P and UX permission rechecked.
 * Private ownership evidence is kept on every failure. OSM centers are fixed illustrations,
 * not GPS, road geometry, navigable destinations or proof of route/driver review.
 */
@RunWith(AndroidJUnit4::class)
class PreparedCourseMapFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun preparedCoursesReplaceNativePointsAndKeepDataAcrossLifecycleAndDrivingLock() {
        assumeTrue(
            "전용 실제 지도와 DDS 복구를 준비한 명시적 실행에서만 검사합니다",
            InstrumentationRegistry.getArguments().getString("manualPreparedCourseMap") == "true",
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(
            "전용 debug 앱에서만 기존 준비를 일시 변경합니다",
            instrumentation.targetContext.packageName == "io.github.hangillab.hangil.debug",
        )
        val graph =
            EntryPointAccessors.fromApplication(
                instrumentation.targetContext.applicationContext,
                RecordingTestEntryPoint::class.java,
            )
        val file = ownerFile()
        assertTrue("미완료 소유 기록을 덮거나 중복 검사를 시작하면 안 됩니다", !ownerEvidenceExists(file))
        runBlocking {
            val original = graph.preparation().observeSelection().first()
            assertTrue("기존 준비가 없으면 원상복구할 수 없어 시작하지 않습니다", original != null)
            val originalSelection = checkNotNull(original)
            val before = snapshotData(graph)
            assertTrue("기존 활성 연습이 있으면 지도 검사를 시작하지 않습니다", before.noActiveSessions)
            val courses =
                listOf("neighborhood", "intersections").map { id ->
                    checkNotNull(graph.catalog().courses.singleOrNull { it.id == id }) { "Current demo course unavailable" }
                }
            assertTrue("서로 다른 두 코스에 세 시연 지점이 있어야 합니다", courses.all { it.mapPreview?.points?.size == 3 })
            val choices = courses.map { selectionFor(it, originalSelection, graph) }
            val owner =
                JSONObject()
                    .put("schema", 1)
                    .put("producer", "PreparedCourseMapFlowTest")
                    .put("run_nonce", UUID.randomUUID().toString())
                    .put("owner_pid", Process.myPid())
                    .put("original_selection", selectionJson(originalSelection))
                    .put("original_data", before.json())
                    .put("owned_selections", JSONArray())
                    .put("phase", "started")
            reserveOwner(file)
            writeOwner(file, owner)
            val confirmedChoices = mutableListOf<PracticeSelection>()
            val sdkObserver = launch { graph.vehicle().state.collect {} }
            var scenarioPassed = false
            var primaryFailure: Throwable? = null
            try {
                awaitAllowed(graph)
                choose(graph, choices[0], owner, file, confirmedChoices)
                touch("home_map")
                val first = checkNotNull(courses[0].mapPreview)
                assertMapText(courses[0], first)
                val firstCamera = awaitNativePreview(first)
                capture("prepared-map-neighborhood", "map_view", listOf("map_view"))
                capture("prepared-map-neighborhood-points", "map_point_2", listOf("map_point_0", "map_point_1", "map_point_2"))

                // No close/navigation here: the current map must replace its source on selection.
                choose(graph, choices[1], owner, file, confirmedChoices)
                val second = checkNotNull(courses[1].mapPreview)
                assertMapText(courses[1], second)
                val secondCamera = awaitNativePreview(second)
                assertTrue(
                    "별개 시연 지역의 선택 변경은 카메라 기준도 갱신해야 합니다",
                    abs(firstCamera.latitude - secondCamera.latitude) > 0.0000001 ||
                        abs(firstCamera.longitude - secondCamera.longitude) > 0.0000001,
                )
                capture("prepared-map-intersections", "map_view", listOf("map_view"))
                compose.activityRule.scenario.recreate()
                assertMapText(courses[1], second)
                awaitNativePreview(second)
                touch("map_close")
                touch("home_map")
                assertMapText(courses[1], second)
                awaitNativePreview(second)
                assertTrue("화면 이동과 재생성이 기존 기록을 바꾸면 안 됩니다", snapshotData(graph) == before)

                // Every native callback/query above has completed before external D can detach it.
                phase("PREPARED_MAP_AWAIT_D")
                awaitGear(graph, GearPosition.DRIVE)
                waitFor("map_locked")
                assertAbsent("map_view")
                assertAbsent("map_ready")
                assertAbsent("map_point_0")
                capture("prepared-map-driving", "map_locked", listOf("map_locked"))
                assertTrue("주행 잠금이 기존 기록을 바꾸면 안 됩니다", snapshotData(graph) == before)
                phase("PREPARED_MAP_AWAIT_P")
                awaitAllowed(graph)
                assertMapText(courses[1], second)
                awaitNativePreview(second)
                capture("prepared-map-returned", "map_view", listOf("map_view"))
                assertTrue("P 복귀가 선택을 바꾸면 안 됩니다", graph.preparation().observeSelection().first() == choices[1])
                scenarioPassed = true
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                try {
                    restoreOriginal(graph, originalSelection, confirmedChoices)
                    assertTrue("지도 검사는 모든 세션과 관찰, 메모를 그대로 보존해야 합니다", snapshotData(graph) == before)
                    assertTrue("원래 준비의 다섯 필드를 모두 복구해야 합니다", graph.preparation().observeSelection().first() == originalSelection)
                    owner.put("phase", if (scenarioPassed) "restored" else "restored_after_failure")
                    writeOwner(file, owner)
                    if (scenarioPassed) {
                        assertTrue("본인의 소유 기록만 정리해야 합니다", readOwner(file).getString("run_nonce") == owner.getString("run_nonce"))
                        try {
                            AtomicFile(file).delete()
                        } catch (_: Exception) {
                            throw AssertionError("본인 소유 기록을 정리하지 못했습니다")
                        }
                        assertTrue("성공한 검사 소유 기록을 정리해야 합니다", !ownerEvidenceExists(file))
                    }
                } catch (restorationFailure: Throwable) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(AssertionError("원상복구가 완료되지 않아 비공개 소유 기록을 보존했습니다"))
                    } else {
                        throw restorationFailure
                    }
                } finally {
                    sdkObserver.cancelAndJoin()
                }
            }
            phase("PREPARED_MAP_COMPLETED")
        }
    }

    /**
     * Explicit network fault injection, never a performance measurement or GPS observation.
     * The external runner preflights the public tile endpoint and preserves device settings.
     * A synthetic camera offset must produce an actual remote tile request and tile error;
     * a cached ready frame alone cannot satisfy this test. No caches or stored data are cleared.
     */
    @Test fun injectedRemoteTileFailureRetriesPreparedPreviewWithoutDataChanges() {
        assumeTrue(
            "명시적인 실제 지도 네트워크 오류 주입에서만 검사합니다",
            InstrumentationRegistry.getArguments().getString("manualPreparedMapNetwork") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("전용 debug 앱에서만 오류를 주입합니다", context.packageName == "io.github.hangillab.hangil.debug")
        assertTrue("미완료 지도 소유 기록과 다른 검사를 겹치면 안 됩니다", !ownerEvidenceExists(ownerFile()))
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, RecordingTestEntryPoint::class.java)
        runBlocking {
            val original = graph.preparation().observeSelection().first()
            val preparation = graph.catalog().resolve(original) as? Preparation.Ready
            assertTrue("현재 준비와 유효한 지도가 있어야 합니다", original != null && preparation?.practice?.course?.mapPreview != null)
            val course = checkNotNull(preparation).practice.course
            val preview = checkNotNull(course.mapPreview)
            val before = snapshotData(graph)
            assertTrue("활성 연습 중에는 네트워크 검사를 시작하지 않습니다", before.noActiveSessions)
            val originalProxy = shell("settings get global http_proxy").trimEnd('\n', '\r')
            assertTrue("원래 프록시를 단일 인자로 안전하게 복구할 수 있어야 합니다", restorableProxy(originalProxy))
            val originalRoute = proxyRoute()
            assertTrue("원래 프록시 경로를 확인할 수 있어야 합니다", originalRoute.isNotEmpty())
            val sdkObserver = launch { graph.vehicle().state.collect {} }
            var primaryFailure: Throwable? = null
            try {
                awaitAllowed(graph)
                touch("home_map")
                assertMapText(course, preview)
                awaitNativePreview(preview)
                phase("PREPARED_MAP_NETWORK_READY")
                shell("settings put global http_proxy 127.0.0.1:9")
                assertTrue("오류 주입 프록시가 실제 적용되어야 합니다", shell("settings get global http_proxy").trim() == "127.0.0.1:9")
                compose.waitUntil(20_000) { proxyRoute().any { it.type() != Proxy.Type.DIRECT } }
                assertTrue("합성 카메라 조작 직전에도 P와 UX가 허용되어야 합니다", liveAllowed(graph))
                requestSyntheticRemoteTile(preview)
                waitFor("map_error")
                waitFor("map_retry")
                assertAbsent("map_ready")
                phase("PREPARED_MAP_NETWORK_INJECTED_ERROR_CONFIRMED")
                // Recover connectivity for retry, then restore the exact previous setting in finally.
                shell("settings put global http_proxy :0")
                compose.waitUntil(20_000) {
                    val route = proxyRoute()
                    route.isNotEmpty() && route.all { it.type() == Proxy.Type.DIRECT }
                }
                touch("map_retry")
                assertMapText(course, preview)
                awaitNativePreview(preview)
                assertAbsent("map_error")
                phase("PREPARED_MAP_NETWORK_RETRY_CONFIRMED")
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                try {
                    restoreProxy(originalProxy)
                    assertTrue("원래 프록시 설정을 정확하게 복구해야 합니다", shell("settings get global http_proxy").trimEnd('\n', '\r') == originalProxy)
                    compose.waitUntil(20_000) { proxyRoute() == originalRoute }
                    assertTrue("네트워크 검사가 준비 선택을 변경하면 안 됩니다", graph.preparation().observeSelection().first() == original)
                    assertTrue("세션과 관찰, 메모를 모두 보존해야 합니다", snapshotData(graph) == before)
                } catch (restorationFailure: Throwable) {
                    if (primaryFailure != null) {
                        primaryFailure.addSuppressed(AssertionError("프록시 또는 자료의 최종 보존 확인을 완료하지 못했습니다"))
                    } else {
                        throw restorationFailure
                    }
                } finally {
                    sdkObserver.cancelAndJoin()
                }
            }
            phase("PREPARED_MAP_NETWORK_COMPLETED")
        }
    }

    private suspend fun requestSyntheticRemoteTile(preview: CourseMapPreview) {
        val original = preview.points.first()
        val latitude = original.latitude + 1.0
        val longitude = original.longitude + 1.0
        val moved = CompletableDeferred<Unit>()
        val requested = CompletableDeferred<Unit>()
        val failed = CompletableDeferred<Unit>()
        val remoteTiles = ConcurrentHashMap.newKeySet<String>()
        compose.runOnIdle {
            val view = mapViews(compose.activity.window.decorView).singleOrNull { it.isAttachedToWindow && it.visibility == View.VISIBLE }
            if (view == null) {
                moved.completeExceptionally(AssertionError("연결된 실제 지도에서만 합성 카메라 검사를 시작합니다"))
            } else {
                view.getMapAsync { map ->
                    if (Looper.myLooper() != Looper.getMainLooper() || !view.isAttachedToWindow || view.visibility != View.VISIBLE) {
                        moved.completeExceptionally(AssertionError("UI 스레드의 연결된 지도에서만 카메라를 변경합니다"))
                    } else {
                        try {
                            view.addOnTileActionListener { operation, x, y, z, _, _, source ->
                                if (source != SOURCE_ID && z in 8..22) {
                                    val probe = tileFor(latitude, longitude, z)
                                    val previous = tileFor(original.latitude, original.longitude, z)
                                    val nearProbe = abs(x - probe.first) <= 2 && abs(y - probe.second) <= 2
                                    val distinct = abs(probe.first - previous.first) > 4 || abs(probe.second - previous.second) > 4
                                    if (nearProbe && distinct) {
                                        val tile = "$source:$z:$x:$y"
                                        if (operation == TileOperation.RequestedFromNetwork) {
                                            remoteTiles += tile
                                            requested.complete(Unit)
                                        }
                                        if (operation == TileOperation.Error && tile in remoteTiles) failed.complete(Unit)
                                    }
                                }
                            }
                            map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), 17.0))
                            val target = map.cameraPosition.target
                            assertTrue(
                                "합성 카메라는 공개 시연 지점과 다른 지역이어야 합니다",
                                target != null && abs(target.latitude - latitude) < 0.000001 &&
                                    abs(target.longitude - longitude) < 0.000001,
                            )
                            moved.complete(Unit)
                        } catch (_: Throwable) {
                            moved.completeExceptionally(AssertionError("실제 지도에서 합성 카메라 요청을 완료하지 못했습니다"))
                        }
                    }
                }
            }
        }
        withTimeout(5_000) { moved.await() }
        phase("PREPARED_MAP_NETWORK_SYNTHETIC_CAMERA_REQUESTED")
        withTimeout(90_000) {
            requested.await()
            failed.await()
        }
        phase("PREPARED_MAP_NETWORK_REMOTE_TILE_ERROR_OBSERVED")
    }

    private fun tileFor(
        latitude: Double,
        longitude: Double,
        zoom: Int,
    ): Pair<Int, Int> {
        val scale = (1 shl zoom).toDouble()
        val radians = latitude * PI / 180.0
        val x = floor((longitude + 180.0) / 360.0 * scale).toInt()
        val y = floor((1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0 * scale).toInt()
        return x to y
    }

    private fun proxyRoute(): List<Proxy> =
        ProxySelector.getDefault()?.select(URI("https://tiles.openfreemap.org/styles/liberty")).orEmpty()

    private fun restorableProxy(value: String): Boolean = value == "null" || value.matches(Regex("""[A-Za-z0-9._:\[\]-]+"""))

    private fun restoreProxy(original: String) {
        require(restorableProxy(original))
        shell("settings put global http_proxy :0")
        shell(
            if (original == "null") {
                "settings delete global http_proxy"
            } else {
                "settings put global http_proxy " + original
            },
        )
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
            ).bufferedReader()
            .use { it.readText() }

    /**
     * Recovery/acknowledgement is separate evidence, never a pass for the interrupted map run.
     * Pending writes have no repository CAS token: identical five-field choices from another
     * writer cannot be distinguished. That ambiguity requires the extra explicit pending flag.
     */
    @Test fun recoverOrAcknowledgePreparedMapOwnership() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "지도 소유 기록의 명시적 복구 실행에서만 처리합니다",
            args.getString("manualPreparedCourseMap") == "true" && args.getString("recoverPreparedCourseMap") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("전용 debug 앱에서만 복구합니다", context.packageName == "io.github.hangillab.hangil.debug")
        val file = ownerFile()
        assertTrue(
            "완전한 소유 기록이 없거나 쓰기 도중 파일이 있으면 증거를 그대로 보존합니다",
            file.isFile && !File(file.path + ".new").exists(),
        )
        val owner = readOwner(file)
        val nonce = owner.optString("run_nonce")
        val validNonce = runCatching { UUID.fromString(nonce).toString() == nonce }.getOrDefault(false)
        assertTrue(
            "본인 지도 검사의 유효한 소유 기록이어야 합니다",
            owner.opt("schema") == 1 && owner.optString("producer") == "PreparedCourseMapFlowTest" && validNonce,
        )
        val phases = setOf("started", "selection_write_requested", "selection_confirmed", "restored_after_failure", "restored")
        assertTrue("알 수 없는 단계의 소유 기록을 정리하면 안 됩니다", owner.optString("phase") in phases)
        val originalData = owner.optJSONObject("original_data")
        assertTrue(
            "원본 전체 자료의 기준이 있어야 복구할 수 있습니다",
            originalData != null && originalData.keys().asSequence().toSet() == setOf("sessions", "observations", "memos") &&
                originalData.opt("sessions") is String && originalData.optJSONObject("observations") != null &&
                originalData.optJSONObject("memos") != null,
        )
        val expectedData = checkNotNull(originalData) { "원본 자료의 기준이 필요합니다" }
        val original = readSelection(owner.getJSONObject("original_selection"))
        val confirmed =
            owner.getJSONArray("owned_selections").let { values ->
                (0 until values.length()).map { readSelection(values.getJSONObject(it)) }
            }
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, RecordingTestEntryPoint::class.java)
        runBlocking {
            awaitAllowed(graph)
            val before = snapshotData(graph)
            assertTrue("기존 활성 연습이 있으면 지도 복구를 진행하지 않습니다", before.noActiveSessions)
            assertTrue("원래 세션과 관찰, 메모가 모두 일치해야 합니다", matchesOriginalData(before, expectedData))
            val current = graph.preparation().observeSelection().first()
            val pending = owner.optJSONObject("pending_selection")?.let(::readSelection)
            val pendingOnly = current != original && confirmed.none { it == current }
            if (pendingOnly) {
                assertTrue(
                    "미확인 쓰기는 명시적 의도 확인과 정확한 선택 일치가 없으면 복구하지 않습니다",
                    owner.optString("phase") == "selection_write_requested" && pending != null && current == pending &&
                        args.getString("acknowledgePendingMapWrite") == "true",
                )
            }
            if (current != original) {
                assertTrue("현재 선택이 확인된 본인 선택이어야 합니다", current != null && (current in confirmed || pendingOnly))
                assertTrue("실제 P와 UX 동시 허용을 복구 직전에 다시 확인합니다", liveAllowed(graph))
                assertTrue("복구 직전 다른 선택으로 바뀌면 덮어쓰지 않습니다", graph.preparation().observeSelection().first() == current)
                assertTrue(
                    "실제 저장소의 주차 확인을 거쳐 원래 준비를 복구해야 합니다",
                    graph.preparation().saveSelection(original) { liveAllowed(graph) },
                )
            }
            assertTrue("원래 준비의 모든 필드를 확인해야 합니다", graph.preparation().observeSelection().first() == original)
            val after = snapshotData(graph)
            assertTrue("원래 모든 기록은 복구 뒤에도 같아야 합니다", after.noActiveSessions && matchesOriginalData(after, expectedData))
            assertTrue("복구 완료 시점에도 실제 P와 UX가 허용되어야 합니다", liveAllowed(graph))
            assertTrue("다른 소유 기록이나 새 처리 내역은 삭제하지 않습니다", readOwner(file).toString() == owner.toString())
            try {
                AtomicFile(file).delete()
            } catch (_: Exception) {
                throw AssertionError("확인한 지도 소유 기록을 정리하지 못했습니다")
            }
            assertTrue("동일 소유 기록만 복구 확인 뒤 정리해야 합니다", !ownerEvidenceExists(file))
            phase("PREPARED_MAP_RECOVERY_COMPLETED")
        }
    }

    private fun readSelection(value: JSONObject): PracticeSelection {
        try {
            require(value.keys().asSequence().toSet() == setOf("goalId", "courseId", "courseVersion", "briefingVersion", "taskId"))
            val goal = value.get("goalId") as String
            val course = value.get("courseId") as String
            val task = value.get("taskId") as String
            val courseVersion = value.get("courseVersion") as Number
            val briefingVersion = value.get("briefingVersion") as Number
            require(goal.isNotBlank() && course.isNotBlank() && task.isNotBlank())
            require(courseVersion.toDouble() == courseVersion.toInt().toDouble() && courseVersion.toInt() > 0)
            require(briefingVersion.toDouble() == briefingVersion.toInt().toDouble() && briefingVersion.toInt() > 0)
            return PracticeSelection(goal, course, courseVersion.toInt(), briefingVersion.toInt(), task)
        } catch (_: Exception) {
            throw AssertionError("소유 기록의 준비 값이 불완전해 복구하지 않습니다")
        }
    }

    private fun matchesOriginalData(
        actual: ExistingData,
        expected: JSONObject,
    ): Boolean {
        fun matches(
            values: Map<String, String>,
            stored: JSONObject,
        ): Boolean = values.keys == stored.keys().asSequence().toSet() && values.all { (key, value) -> stored.opt(key) == value }
        return actual.sessions == expected.opt("sessions") &&
            matches(actual.observations, expected.getJSONObject("observations")) &&
            matches(actual.memos, expected.getJSONObject("memos"))
    }

    private fun selectionFor(
        course: PracticeCourse,
        original: PracticeSelection,
        graph: RecordingTestEntryPoint,
    ): PracticeSelection {
        val goal =
            graph.catalog().goals.firstOrNull { it.id == original.goalId && it.id in course.goalIds }
                ?: graph.catalog().goals.first { it.id in course.goalIds }
        val task = course.tasks.first()
        return PracticeSelection(goal.id, course.id, course.version, course.briefingVersion, task.id)
    }

    private suspend fun choose(
        graph: RecordingTestEntryPoint,
        selection: PracticeSelection,
        owner: JSONObject,
        file: File,
        confirmed: MutableList<PracticeSelection>,
    ) {
        assertTrue("실제 P와 UX 동시 허용에서만 준비를 일시 변경합니다", liveAllowed(graph))
        owner.put("phase", "selection_write_requested").put("pending_selection", selectionJson(selection))
        writeOwner(file, owner)
        assertTrue("실제 저장소가 주차 재확인 뒤 준비를 저장해야 합니다", graph.preparation().saveSelection(selection) { liveAllowed(graph) })
        withTimeout(20_000) { graph.preparation().observeSelection().first { it == selection } }
        if (selection !in confirmed) confirmed += selection
        owner.put("owned_selections", JSONArray(confirmed.map(::selectionJson))).put("phase", "selection_confirmed")
        writeOwner(file, owner)
    }

    private suspend fun restoreOriginal(
        graph: RecordingTestEntryPoint,
        original: PracticeSelection,
        confirmed: List<PracticeSelection>,
    ) {
        val current = graph.preparation().observeSelection().first()
        if (current == original) return
        assertTrue("다른 작업의 준비 선택은 덮어쓰지 않습니다", current != null && current in confirmed)
        assertTrue("P와 UX가 확인되지 않으면 복구 쓰기를 하지 않습니다", liveAllowed(graph))
        assertTrue("복구 직전에도 검사 소유 선택이어야 합니다", graph.preparation().observeSelection().first() == current)
        assertTrue("원래 준비 복구도 실제 저장소의 주차 재확인을 거쳐야 합니다", graph.preparation().saveSelection(original) { liveAllowed(graph) })
    }

    private fun liveAllowed(graph: RecordingTestEntryPoint): Boolean =
        allowsPracticeInteraction(graph.vehicle().state.value, graph.restrictions().restrictions.value)

    private suspend fun awaitAllowed(graph: RecordingTestEntryPoint) {
        withTimeout(90_000) {
            combine(graph.vehicle().state, graph.restrictions().restrictions) { vehicle, restrictions ->
                allowsPracticeInteraction(vehicle, restrictions)
            }.first { it }
        }
    }

    private suspend fun awaitGear(
        graph: RecordingTestEntryPoint,
        expected: GearPosition,
    ) {
        withTimeout(90_000) {
            graph.vehicle().state.first {
                it.connection == ConnectionState.CONNECTED && it.gear == VehicleReading.Available(expected)
            }
        }
    }

    private fun assertMapText(
        course: PracticeCourse,
        preview: CourseMapPreview,
    ) {
        waitFor("map_ready")
        assertReadTag("map_course_title", course.title)
        assertReadTag("map_preview_title", preview.title)
        for ((index, point) in preview.points.withIndex()) assertReadTag("map_point_$index", point.title)
        scrollVisible("map_demo_badge")
        assertAbsent("map_point_3")
        listOf("empty", "outdated", "failed", "retry", "unavailable", "loading").forEach { assertAbsent("map_preparation_$it") }
    }

    /** Source queries may repeat tile features; each returned copy must match one of exactly three logical points. */
    private suspend fun awaitNativePreview(preview: CourseMapPreview): CameraSnapshot =
        withTimeout(90_000) {
            var result: CameraSnapshot? = null
            while (result == null) {
                val attempt = CompletableDeferred<CameraSnapshot?>()
                compose.runOnIdle {
                    val candidates =
                        mapViews(compose.activity.window.decorView).filter { it.isAttachedToWindow && it.visibility == View.VISIBLE }
                    val view = candidates.singleOrNull()
                    if (view == null || view.width <= 0 || view.height <= 0) {
                        attempt.complete(null)
                    } else {
                        view.getMapAsync { map ->
                            // Both checks and every JNI-backed query stay on the UI thread. No native
                            // handle escapes this callback or is inspected after its view detaches.
                            if (Looper.myLooper() != Looper.getMainLooper()) {
                                attempt.completeExceptionally(AssertionError("지도 검사는 UI 스레드에서만 실행해야 합니다"))
                            } else if (!view.isAttachedToWindow || view.visibility != View.VISIBLE) {
                                attempt.complete(null)
                            } else {
                                try {
                                    val style = map.style
                                    val source = style?.getSource(SOURCE_ID) as? GeoJsonSource
                                    val circles = style?.getLayer(CIRCLES_ID) as? CircleLayer
                                    val numbers = style?.getLayer(NUMBERS_ID) as? SymbolLayer
                                    var valid =
                                        source != null && circles != null && numbers != null &&
                                            circles.sourceId == SOURCE_ID && numbers.sourceId == SOURCE_ID &&
                                            circles.visibility.value != "none" && numbers.visibility.value != "none"
                                    val features = source?.querySourceFeatures(null).orEmpty()
                                    val expectedIds = preview.points.map { it.id }.toSet()
                                    val foundIds = features.map { it.getStringProperty("point_id") }.toSet()
                                    valid = valid && foundIds == expectedIds
                                    for (feature in features) {
                                        val index = preview.points.indexOfFirst { it.id == feature.getStringProperty("point_id") }
                                        if (index < 0) {
                                            valid = false
                                            break
                                        }
                                        val expected = preview.points[index]
                                        val point = feature.geometry() as? Point
                                        valid = valid && point != null && feature.getStringProperty("title") == expected.title &&
                                            feature.getNumberProperty("order").toDouble() == (index + 1).toDouble() &&
                                            feature.getStringProperty("preview_id") == preview.id &&
                                            feature.getNumberProperty("preview_version").toDouble() == preview.version.toDouble()
                                        if (point != null) {
                                            val actualLocation = LatLng(point.latitude(), point.longitude())
                                            val actualScreen = map.projection.toScreenLocation(actualLocation)
                                            val expectedLocation = LatLng(expected.latitude, expected.longitude)
                                            val expectedScreen = map.projection.toScreenLocation(expectedLocation)
                                            // Native source queries can expose tile-quantized geometry.
                                            // Verify its placement within one physical pixel, never claim GPS accuracy.
                                            valid = valid && abs(actualScreen.x - expectedScreen.x) <= 1f &&
                                                abs(actualScreen.y - expectedScreen.y) <= 1f
                                            val margin = 8f * view.resources.displayMetrics.density
                                            val x = expectedScreen.x
                                            val y = expectedScreen.y
                                            valid = valid && x >= margin && y >= margin &&
                                                x <= view.width - margin && y <= view.height - margin
                                        }
                                    }
                                    val camera = map.cameraPosition
                                    val target = camera.target
                                    val snapshot =
                                        if (valid && target != null) {
                                            CameraSnapshot(target.latitude, target.longitude, camera.zoom)
                                        } else {
                                            null
                                        }
                                    attempt.complete(snapshot)
                                } catch (_: Throwable) {
                                    attempt.completeExceptionally(AssertionError("실제 지도 source 또는 camera 검사를 완료하지 못했습니다"))
                                }
                            }
                        }
                    }
                }
                result = withTimeout(5_000) { attempt.await() }
                if (result == null) delay(150)
            }
            checkNotNull(result)
        }

    private fun mapViews(view: View): List<MapView> =
        when (view) {
            is MapView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { mapViews(view.getChildAt(it)) }
            else -> emptyList()
        }

    private suspend fun snapshotData(graph: RecordingTestEntryPoint): ExistingData =
        withTimeout(30_000) {
            val sessions = graph.sessions().observeSessions().first()
            ExistingData(
                sessions.toString(),
                sessions.associate {
                    it.sessionId to
                        graph
                            .observations()
                            .observe(it.sessionId)
                            .first()
                            .toString()
                },
                sessions.associate {
                    it.sessionId to
                        graph
                            .memos()
                            .observe(it.sessionId)
                            .first()
                            .toString()
                },
                sessions.none { it.isActive },
            )
        }

    private fun assertReadTag(
        tag: String,
        expected: String,
    ) {
        compose.waitUntil(30_000) { exists(tag) && nodeTexts(nodes(tag).single()).any { expected in it } }
        scrollVisible(tag)
    }

    private fun nodeTexts(node: SemanticsNode): List<String> =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text } + node.children.flatMap(::nodeTexts)

    private fun nodes(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun exists(tag: String): Boolean = nodes(tag).size == 1

    private fun waitFor(tag: String) {
        compose.waitUntil(90_000) { exists(tag) }
    }

    private fun assertAbsent(tag: String) {
        assertTrue("잠금 또는 준비 오류 상태에서 지도 세부가 남으면 안 됩니다", nodes(tag).isEmpty())
    }

    private fun touch(tag: String) {
        waitFor(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun scrollVisible(tag: String) {
        waitFor(tag)
        compose.onNodeWithTag(tag).performScrollTo()
        assertFullyVisible(listOf(tag))
    }

    private fun assertFullyVisible(tags: List<String>) {
        compose.waitUntil(15_000) {
            var available: Rect? = null
            var originX = 0
            var originY = 0
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val decor = compose.activity.window.decorView
                val screen = IntArray(2)
                val window = IntArray(2)
                decor.getLocationOnScreen(screen)
                decor.getLocationInWindow(window)
                val rectangle = Rect(screen[0], screen[1], screen[0] + decor.width, screen[1] + decor.height)
                val frame = Rect()
                decor.getWindowVisibleDisplayFrame(frame)
                if (rectangle.intersect(frame)) available = rectangle
                originX = screen[0] - window[0]
                originY = screen[1] - window[1]
            }
            val viewport = available
            viewport != null &&
                tags.all { tag ->
                    val node = nodes(tag).singleOrNull()
                    if (node == null) {
                        false
                    } else {
                        val bounds = node.boundsInWindow
                        bounds.width >= node.size.width - 1 && bounds.height >= node.size.height - 1 &&
                            bounds.left + originX >= viewport.left - 1 && bounds.top + originY >= viewport.top - 1 &&
                            bounds.right + originX <= viewport.right + 1 && bounds.bottom + originY <= viewport.bottom + 1
                    }
                }
        }
    }

    private fun capture(
        name: String,
        scrollTarget: String,
        visibleTags: List<String>,
    ) {
        scrollVisible(scrollTarget)
        assertFullyVisible(visibleTags)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val expectedPackage = instrumentation.targetContext.packageName
        compose.waitUntil(20_000) { automation.rootInActiveWindow?.packageName?.toString() == expectedPackage }
        automation.waitForIdle(500, 5_000)
        assertTrue("실제 앱 전면이 확인된 경우에만 캡처합니다", automation.rootInActiveWindow?.packageName?.toString() == expectedPackage)
        val bitmap = checkNotNull(automation.takeScreenshot())
        val directory = File(instrumentation.targetContext.cacheDir, "screenshots").apply { mkdirs() }
        try {
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun phase(value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "$value\n") })
    }

    private fun selectionJson(selection: PracticeSelection) =
        JSONObject()
            .put("goalId", selection.goalId)
            .put("courseId", selection.courseId)
            .put("courseVersion", selection.courseVersion)
            .put("briefingVersion", selection.briefingVersion)
            .put("taskId", selection.taskId)

    private fun ownerFile() =
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "prepared-course-map-flow-owner.json")

    private fun ownerEvidenceExists(file: File) = file.exists() || File(file.path + ".bak").exists() || File(file.path + ".new").exists()

    private fun reserveOwner(file: File) {
        val created =
            try {
                file.createNewFile()
            } catch (_: Exception) {
                false
            }
        assertTrue("새 소유 기록만 사용할 수 있습니다", created)
    }

    private fun readOwner(file: File): JSONObject =
        try {
            AtomicFile(file).openRead().use { JSONObject(it.bufferedReader().readText()) }
        } catch (_: Exception) {
            throw AssertionError("비공개 지도 소유 기록을 읽지 못했습니다")
        }

    private fun writeOwner(
        file: File,
        owner: JSONObject,
    ) {
        if (file.length() > 0) assertTrue("다른 소유 기록은 덮어쓰지 않습니다", readOwner(file).getString("run_nonce") == owner.getString("run_nonce"))
        val atomic = AtomicFile(file)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(owner.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (_: Exception) {
            stream?.let { atomic.failWrite(it) }
            throw AssertionError("지도 검사 소유 기록을 저장하지 못했습니다")
        }
    }

    private data class ExistingData(
        val sessions: String,
        val observations: Map<String, String>,
        val memos: Map<String, String>,
        val noActiveSessions: Boolean,
    ) {
        fun json() = JSONObject().put("sessions", sessions).put("observations", JSONObject(observations)).put("memos", JSONObject(memos))
    }

    private data class CameraSnapshot(
        val latitude: Double,
        val longitude: Double,
        val zoom: Double,
    )

    private companion object {
        const val SOURCE_ID = "hangil-course-preview-points"
        const val CIRCLES_ID = "hangil-course-preview-circles"
        const val NUMBERS_ID = "hangil-course-preview-numbers"
    }
}
