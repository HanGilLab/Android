package io.github.hangillab.hangil

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import android.view.WindowInsets
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationSource
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import io.github.hangillab.hangil.feature.home.R as HomeR

/**
 * Opt-in two-process test, selected with -e manualMemo true and one named method at a time.
 * The external runner verifies no existing active session, the dedicated debug package and P/UX,
 * runs createEditAndLockMemo, force-stops the target process, then explicitly selects phase two.
 * It restores DDS and display settings on all exits and preserves the ownership cache on failure.
 * Existing user notes and rows are never edited. Only the UI-created owned session/note are added;
 * the owned session is retained and only its test note is explicitly deleted. Generic text is sent
 * through Compose text input commands while a real Android IME is visible, not physical key taps.
 * Full private comparison values stay in app-private cache. Reports contain no IDs, note values,
 * versions, observation timestamps, device details or filesystem paths. Phase two also performs
 * one controlled second-writer update of its owned memo and injects an old confirmation callback;
 * that boundary injection is not described as a person tapping a stale button or an SDK event.
 */
@RunWith(AndroidJUnit4::class)
class SessionMemoFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun createEditAndLockMemo() {
        preconditions()
        val file = ownerFile()
        assertTrue("미완료 검사 소유 기록이나 복구 파일이 있으면 새 연습을 만들지 않습니다", !ownerEvidenceExists(file))
        val graph = graph()
        runBlocking {
            val before = snapshot(graph)
            assertTrue("기존 활성 연습이 있으면 검사를 시작하지 않습니다", before.sessions.none { it.isActive })
            assertTrue("실제 UI 시작에 필요한 저장된 준비가 있어야 합니다", graph.preparation().observeSelection().first() != null)
            awaitGear(graph, GearPosition.PARK)
            waitFor("session_start")
            val owner =
                JSONObject()
                    .put("schema", 1)
                    .put("producer", "SessionMemoFlowTest")
                    .put("run_nonce", UUID.randomUUID().toString())
                    .put("owner_pid", Process.myPid())
                    .put("phase", "starting")
                    .put("baseline", before.json())
            reserveOwner(file)
            writeOwner(file, owner)
            val sdkObserver = launch { graph.vehicle().state.collect {} }
            try {
                touch("session_start")
                val owned =
                    withTimeout(20_000) {
                        graph
                            .sessions()
                            .observeSessions()
                            .first { rows ->
                                rows.count { it.isActive } == 1 && rows.any { it.isActive && it.sessionId !in before.ids }
                            }.single { it.isActive }
                    }
                // Preserve the new identity immediately, before waiting for later SDK/UI checks.
                owner.put("owned_session_id", owned.sessionId).put("phase", "created")
                writeOwner(file, owner)
                withTimeout(30_000) {
                    graph.observations().observe(owned.sessionId).first { rows ->
                        rows.any {
                            it.observation.source == VehicleObservationSource.SDK_STATE_OBSERVATION &&
                                it.observation.state.connection == ConnectionState.CONNECTED &&
                                it.observation.state.gear == VehicleReading.Available(GearPosition.PARK)
                        }
                    }
                }
                waitFor("session_finish")
                touch("session_finish")
                val finished =
                    withTimeout(20_000) {
                        graph
                            .sessions()
                            .observeSessions()
                            .first { rows ->
                                rows.any { it.sessionId == owned.sessionId && !it.isActive }
                            }.single { it.sessionId == owned.sessionId }
                    }
                val ownedRows = stableObservations(graph, owned.sessionId)
                assertTrue("검사 소유 세션에는 실제 SDK 앱 관찰이 있어야 합니다", ownedRows.isNotEmpty())
                owner
                    .put("phase", "finished")
                    .put("owned_session_value", finished.toString())
                    .put("owned_observations_value", ownedRows.toString())
                writeOwner(file, owner)
                assertTrue("새 검사 세션에 사용자 메모가 미리 있으면 덮어쓰지 않습니다", graph.memos().observe(owned.sessionId).first() == null)
                openMemo()
                waitFor("memo_textfield")
                assertField("")
                assertNodeText("memo_source", text(HomeR.string.memo_source))
                focusEditorAndAssertIme()
                compose.onNodeWithTag("memo_textfield").performTextInput(DRAFT)
                assertField(DRAFT)
                waitFor("memo_unsaved")
                capture("memo-input", "memo_textfield", scroll = false)
                phase("MEMO_AWAIT_D")
                awaitGear(graph, GearPosition.DRIVE)
                waitFor("home_locked_title")
                assertAbsent("memo_textfield")
                assertAbsent("memo_save")
                assertIme(false)
                assertTrue("주행 잠금 중 초안을 자동 저장하면 안 됩니다", graph.memos().observe(owned.sessionId).first() == null)
                capture("memo-driving", "home_locked_title")
                phase("MEMO_AWAIT_P")
                awaitGear(graph, GearPosition.PARK)
                waitFor("home_recent_memo")
                assertAbsent("memo_textfield")
                openMemo()
                waitFor("memo_textfield")
                assertField(DRAFT)
                waitFor("memo_unsaved")
                assertTrue("P 복귀로 초안이 저장되면 안 됩니다", graph.memos().observe(owned.sessionId).first() == null)
                // A second real IME check ensures save remains reachable with the keyboard open.
                focusEditorAndAssertIme()
                touch("memo_save")
                val firstSaved = awaitMemo(graph, owned.sessionId, DRAFT)
                assertTrue("저장 응답의 버전 토큰이 있어야 합니다", firstSaved.version.isNotBlank())
                waitFor("memo_saved_text")
                assertNodeText("memo_saved_text", DRAFT)
                assertIme(false)
                touch("memo_edit")
                waitFor("memo_textfield")
                focusEditorAndAssertIme()
                compose.onNodeWithTag("memo_textfield").performTextReplacement(CANCELLED_DRAFT)
                assertField(CANCELLED_DRAFT)
                touch("memo_cancel_edit")
                waitFor("memo_saved_text")
                assertNodeText("memo_saved_text", DRAFT)
                assertTrue("편집 취소는 저장 원문과 토큰을 바꾸면 안 됩니다", graph.memos().observe(owned.sessionId).first() == firstSaved)
                touch("memo_edit")
                waitFor("memo_textfield")
                focusEditorAndAssertIme()
                compose.onNodeWithTag("memo_textfield").performTextReplacement(FINAL_MEMO)
                assertField(FINAL_MEMO)
                touch("memo_save")
                val finalSaved = awaitMemo(graph, owned.sessionId, FINAL_MEMO)
                assertTrue("새 저장은 읽었던 버전과 다른 토큰을 돌려줘야 합니다", finalSaved.version.isNotBlank() && finalSaved.version != firstSaved.version)
                waitFor("memo_saved_text")
                assertNodeText("memo_saved_text", FINAL_MEMO)
                assertIme(false)
                capture("memo-saved", "memo_saved_text")
                compose.activityRule.scenario.recreate()
                waitFor("home_recent_memo")
                if (!exists("memo_saved_text")) openMemo()
                waitFor("memo_saved_text")
                assertNodeText("memo_saved_text", FINAL_MEMO)
                assertTrue("재생성 뒤 메모의 원문과 토큰을 유지해야 합니다", graph.memos().observe(owned.sessionId).first() == finalSaved)
                owner
                    .put("owned_memo_value", finalSaved.toString())
                    .put("owned_memo_text", finalSaved.text)
                    .put("owned_memo_version", finalSaved.version)
                    .put("phase", "await_process_restart")
                writeOwner(file, owner)
                assertOwnedAndOriginalPreserved(graph, owner, requireMemo = true)
                phase("MEMO_AWAIT_PROCESS_RESTART")
            } finally {
                sdkObserver.cancelAndJoin()
                assertOriginalPreserved(snapshot(graph), owner)
            }
        }
    }

    @Test fun restoreAndDeleteMemoAfterProcessRestart() {
        preconditions()
        val file = ownerFile()
        assertTrue("첫 단계의 비공개 소유 기록이 있어야 복원을 검사할 수 있습니다", file.exists())
        val owner = readOwner(file)
        assertTrue(
            "같은 계측이 만든 완료된 첫 단계만 이어서 처리합니다",
            owner.optInt("schema") == 1 && owner.optString("producer") == "SessionMemoFlowTest" &&
                owner.optString("phase") == "await_process_restart",
        )
        assertTrue("외부 runner가 실제 앱 프로세스를 종료한 뒤 새 프로세스에서 실행해야 합니다", owner.getInt("owner_pid") != Process.myPid())
        val ownedId = owner.getString("owned_session_id")
        val graph = graph()
        runBlocking {
            try {
                awaitGear(graph, GearPosition.PARK)
                assertOwnedAndOriginalPreserved(graph, owner, requireMemo = true)
                var expectedMemo = SessionMemo(ownedId, owner.getString("owned_memo_text"), owner.getString("owned_memo_version"))
                assertTrue("다른 프로세스가 같은 저장 원문과 토큰을 읽어야 합니다", graph.memos().observe(ownedId).first() == expectedMemo)
                waitFor("home_recent_memo")
                openMemo()
                waitFor("memo_saved_text")
                assertNodeText("memo_saved_text", expectedMemo.text)
                assertNodeText("memo_source", text(HomeR.string.memo_source))
                capture("memo-restored", "memo_saved_text")

                // Controlled boundary injection: retain v1's callback, then use the actual
                // repository as a second writer for this test's owned memo only. No baseline
                // user note is touched, and no physical stale-button tap is claimed.
                touch("memo_delete")
                waitFor("memo_confirm_delete")
                val delayedConfirmation =
                    nodes("memo_confirm_delete")
                        .single()
                        .config
                        .getOrNull(SemanticsActions.OnClick)
                        ?.action
                assertTrue("명시적 삭제 확인 콜백을 먼저 확보해야 합니다", delayedConfirmation != null)
                val replaced =
                    graph.memos().save(ownedId, SECOND_WRITER_MEMO, expectedMemo.version) {
                        allowsPracticeInteraction(graph.vehicle().state.value, graph.restrictions().restrictions.value)
                    }
                assertTrue("본인 검사 메모의 제어된 두 번째 쓰기가 성공해야 합니다", replaced is SessionMemoWriteResult.Saved)
                val newerMemo = (replaced as? SessionMemoWriteResult.Saved)?.memo
                assertTrue(
                    "두 번째 쓰기는 같은 검사 세션의 새 원문과 다른 토큰이어야 합니다",
                    newerMemo != null && newerMemo.sessionId == ownedId && newerMemo.text == SECOND_WRITER_MEMO &&
                        newerMemo.version.isNotBlank() && newerMemo.version != expectedMemo.version,
                )
                expectedMemo = checkNotNull(newerMemo)
                owner
                    .put("owned_memo_value", expectedMemo.toString())
                    .put("owned_memo_text", expectedMemo.text)
                    .put("owned_memo_version", expectedMemo.version)
                    .put("controlled_boundary", "stale_delete_confirmation")
                writeOwner(file, owner)
                assertNodeText("memo_saved_text", expectedMemo.text)
                compose.waitUntil(20_000) { !exists("memo_confirm_delete") }
                assertAbsent("memo_confirm_delete")
                compose.runOnIdle { checkNotNull(delayedConfirmation).invoke() }
                compose.waitForIdle()
                val changedAfterOldCallback = withTimeoutOrNull(1_500) { graph.memos().observe(ownedId).first { it != expectedMemo } }
                assertTrue(
                    "이전 버전 확인 콜백이 새 메모를 삭제하거나 바꾸면 안 됩니다",
                    changedAfterOldCallback == null && graph.memos().observe(ownedId).first() == expectedMemo,
                )
                if (exists("memo_reload")) touch("memo_reload")
                waitFor("memo_saved_text")
                assertNodeText("memo_saved_text", expectedMemo.text)
                assertOwnedAndOriginalPreserved(graph, owner, requireMemo = true)
                phase("MEMO_STALE_CONFIRMATION_VERIFIED")

                touch("memo_delete")
                waitFor("memo_confirm_delete")
                clickDialog("memo_cancel_delete")
                assertAbsent("memo_confirm_delete")
                assertTrue("삭제 취소는 검사 소유 메모도 지우면 안 됩니다", graph.memos().observe(ownedId).first() == expectedMemo)
                touch("memo_delete")
                waitFor("memo_confirm_delete")
                clickDialog("memo_confirm_delete")
                withTimeout(20_000) { graph.memos().observe(ownedId).first { it == null } }
                waitFor("memo_textfield")
                assertField("")
                assertAbsent("memo_saved_text")
                assertIme(false)
                capture("memo-empty", "memo_textfield")
                assertOwnedAndOriginalPreserved(graph, owner, requireMemo = false)
                val currentOwner = readOwner(file)
                assertTrue(
                    "다른 검사의 소유 기록을 삭제하면 안 됩니다",
                    currentOwner.optString("run_nonce") == owner.getString("run_nonce") &&
                        currentOwner.optString("owned_session_id") == ownedId &&
                        currentOwner.optString("phase") == "await_process_restart",
                )
                try {
                    AtomicFile(file).delete()
                } catch (_: Exception) {
                    throw AssertionError("본인 검사 소유 기록을 정리하지 못했습니다")
                }
                assertTrue("성공 뒤 본인이 만든 소유 기록만 정리해야 합니다", !file.exists())
                phase("MEMO_COMPLETED")
            } finally {
                assertOriginalPreserved(snapshot(graph), owner)
            }
        }
    }

    private fun preconditions() {
        assumeTrue("실제 IME, DDS와 프로세스 재시작을 준비한 명시적 실행에서만 검사합니다", InstrumentationRegistry.getArguments().getString("manualMemo") == "true")
        assertTrue(
            "전용 debug 앱에서만 검사 소유 자료를 만들 수 있습니다",
            InstrumentationRegistry.getInstrumentation().targetContext.packageName == "io.github.hangillab.hangil.debug",
        )
    }

    private fun graph(): RecordingTestEntryPoint =
        EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            RecordingTestEntryPoint::class.java,
        )

    private suspend fun snapshot(graph: RecordingTestEntryPoint): ExistingData =
        withTimeout(30_000) {
            val sessions = graph.sessions().observeSessions().first()
            ExistingData(
                graph
                    .preparation()
                    .observeSelection()
                    .first()
                    .toString(),
                sessions,
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
            )
        }

    private fun assertOriginalPreserved(
        after: ExistingData,
        owner: JSONObject,
    ) {
        val before = owner.getJSONObject("baseline")
        val originals = before.getJSONObject("sessions")
        val oldOrder = before.getJSONArray("session_order").strings()
        assertTrue("기존 준비의 모든 필드를 보존해야 합니다", after.preparation == before.getString("preparation"))
        assertTrue("기존 세션의 수와 순서를 보존해야 합니다", after.sessions.filter { originals.has(it.sessionId) }.map { it.sessionId } == oldOrder)
        for (session in after.sessions.filter { originals.has(it.sessionId) }) {
            assertTrue("기존 세션의 모든 필드를 보존해야 합니다", session.toString() == originals.getString(session.sessionId))
            assertTrue(
                "기존 모든 SDK 관찰 행을 보존해야 합니다",
                after.observations.getValue(session.sessionId) == before.getJSONObject("observations").getString(session.sessionId),
            )
            assertTrue(
                "사용자의 기존 메모와 버전은 변경하면 안 됩니다",
                after.memos.getValue(session.sessionId) == before.getJSONObject("memos").getString(session.sessionId),
            )
        }
        val owned = owner.optString("owned_session_id")
        if (owned.isNotEmpty()) {
            assertTrue(
                "본인이 만든 연습 하나 외에 새 세션을 추가하면 안 됩니다",
                after.sessions.size == oldOrder.size + 1 &&
                    after.sessions.singleOrNull { !originals.has(it.sessionId) }?.sessionId == owned,
            )
        } else {
            assertTrue("시작 확인 전에 세션이 중복 생성되면 안 됩니다", after.sessions.size <= oldOrder.size + 1)
        }
    }

    private suspend fun assertOwnedAndOriginalPreserved(
        graph: RecordingTestEntryPoint,
        owner: JSONObject,
        requireMemo: Boolean,
    ) {
        val after = snapshot(graph)
        assertOriginalPreserved(after, owner)
        val ownedId = owner.getString("owned_session_id")
        assertTrue("읽기와 메모 처리 뒤 활성 연습을 남기면 안 됩니다", after.sessions.none { it.isActive })
        assertTrue("실제 최근 종료 세션이 본인 검사 연습이어야 합니다", after.sessions.firstOrNull { !it.isActive }?.sessionId == ownedId)
        assertTrue(
            "검사 종료 세션의 모든 필드를 그대로 유지해야 합니다",
            after.sessions.single { it.sessionId == ownedId }.toString() == owner.getString("owned_session_value"),
        )
        assertTrue("메모 작업은 SDK 관찰 행을 추가하거나 변경하면 안 됩니다", after.observations.getValue(ownedId) == owner.getString("owned_observations_value"))
        val expectedMemo = if (requireMemo) owner.getString("owned_memo_value") else "null"
        assertTrue("검사 소유 메모의 실제 저장 상태가 맞아야 합니다", after.memos.getValue(ownedId) == expectedMemo)
    }

    private suspend fun awaitMemo(
        graph: RecordingTestEntryPoint,
        sessionId: String,
        expected: String,
    ): SessionMemo =
        withTimeout(20_000) {
            checkNotNull(graph.memos().observe(sessionId).first { it?.text == expected })
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

    private suspend fun stableObservations(
        graph: RecordingTestEntryPoint,
        sessionId: String,
    ): List<SessionVehicleObservation> =
        withTimeout(10_000) {
            var rows = graph.observations().observe(sessionId).first()
            var quietUntil = SystemClock.uptimeMillis() + 1_000
            while (SystemClock.uptimeMillis() < quietUntil) {
                delay(100)
                val next = graph.observations().observe(sessionId).first()
                if (next != rows) {
                    rows = next
                    quietUntil = SystemClock.uptimeMillis() + 1_000
                }
            }
            rows
        }

    private fun openMemo() {
        waitFor("home_recent_memo")
        if (!exists("memo_source")) touch("home_recent_memo")
    }

    private fun focusEditorAndAssertIme() {
        touch("memo_textfield")
        assertIme(true)
    }

    private fun assertIme(expected: Boolean) {
        compose.waitUntil(15_000) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            var insetsVisible: Boolean? = null
            instrumentation.runOnMainSync {
                if (Build.VERSION.SDK_INT >= 30) {
                    insetsVisible =
                        compose.activity.window.decorView.rootWindowInsets
                            ?.isVisible(WindowInsets.Type.ime())
                }
            }
            val imeWindow = instrumentation.uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            if (expected) insetsVisible == true || imeWindow else insetsVisible == false && !imeWindow
        }
    }

    private fun assertField(expected: String) {
        waitFor("memo_textfield")
        assertTrue(
            "편집 초안은 입력한 원문과 일치해야 합니다",
            nodes("memo_textfield")
                .single()
                .config
                .getOrNull(SemanticsProperties.EditableText)
                ?.text == expected,
        )
    }

    private fun assertNodeText(
        tag: String,
        expected: String,
    ) {
        compose.waitUntil(20_000) { exists(tag) && nodeTexts(nodes(tag).single()).contains(expected) }
        assertTrue("표시된 메모 또는 출처가 저장 원문과 일치해야 합니다", nodeTexts(nodes(tag).single()).contains(expected))
    }

    private fun nodeTexts(node: SemanticsNode): List<String> =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text } + node.children.flatMap(::nodeTexts)

    private fun nodes(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun exists(tag: String): Boolean = nodes(tag).size == 1

    private fun waitFor(tag: String) {
        compose.waitUntil(20_000) { exists(tag) }
    }

    private fun assertAbsent(tag: String) {
        assertTrue("숨겨야 할 메모 조작이 화면에 남으면 안 됩니다", nodes(tag).isEmpty())
    }

    private fun touch(tag: String) {
        waitFor(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun clickDialog(tag: String) {
        waitFor(tag)
        compose.onNodeWithTag(tag).performClick()
    }

    private fun text(resource: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resource)

    private fun phase(name: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "$name\n") })
    }

    private fun capture(
        name: String,
        visibleTag: String,
        scroll: Boolean = true,
    ) {
        waitFor(visibleTag)
        if (scroll) compose.onNodeWithTag(visibleTag).performScrollTo()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val expectedPackage = instrumentation.targetContext.packageName
        compose.waitUntil(20_000) { automation.rootInActiveWindow?.packageName?.toString() == expectedPackage }
        automation.waitForIdle(500, 5_000)
        assertTrue("실제 앱 전면을 확인한 뒤 캡처해야 합니다", automation.rootInActiveWindow?.packageName?.toString() == expectedPackage)
        val bitmap = checkNotNull(automation.takeScreenshot())
        val directory = File(instrumentation.targetContext.cacheDir, "screenshots").apply { mkdirs() }
        try {
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun ownerFile() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "session-memo-flow-owner.json")

    private fun ownerEvidenceExists(file: File): Boolean =
        file.exists() || File(file.path + ".bak").exists() || File(file.path + ".new").exists()

    private fun reserveOwner(file: File) {
        val reserved =
            try {
                file.createNewFile()
            } catch (_: Exception) {
                false
            }
        assertTrue("검사 소유 파일은 새로 만든 경우에만 사용합니다", reserved)
    }

    private fun readOwner(file: File): JSONObject =
        try {
            AtomicFile(file).openRead().use { JSONObject(it.bufferedReader().readText()) }
        } catch (_: Exception) {
            throw AssertionError("비공개 검사 소유 기록을 읽을 수 없습니다")
        }

    private fun writeOwner(
        file: File,
        owner: JSONObject,
    ) {
        if (file.length() != 0L) {
            assertTrue("다른 검사의 소유 기록을 덮어쓰면 안 됩니다", readOwner(file).optString("run_nonce") == owner.getString("run_nonce"))
        }
        val atomic = AtomicFile(file)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(owner.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (_: Exception) {
            stream?.let { atomic.failWrite(it) }
            throw AssertionError("비공개 검사 소유 기록을 보존하지 못했습니다")
        }
    }

    private data class ExistingData(
        val preparation: String,
        val sessions: List<PracticeSession>,
        val observations: Map<String, String>,
        val memos: Map<String, String>,
    ) {
        val ids get() = sessions.map { it.sessionId }.toSet()

        fun json() =
            JSONObject()
                .put("preparation", preparation)
                .put("session_order", JSONArray(sessions.map { it.sessionId }))
                .put("sessions", JSONObject(sessions.associate { it.sessionId to it.toString() }))
                .put("observations", JSONObject(observations))
                .put("memos", JSONObject(memos))
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private companion object {
        const val DRAFT = "계측 검증용 초안 A\n저장 전 화면 잠금 확인"
        const val CANCELLED_DRAFT = "계측 검증용 취소 초안"
        const val FINAL_MEMO = "계측 검증용 수정 메모 B\n사용자 기록과 구분한 입력 검증"
        const val SECOND_WRITER_MEMO = "계측 검증용 두 번째 쓰기 C\n이전 삭제 확인의 버전 경계 검증"
    }
}
