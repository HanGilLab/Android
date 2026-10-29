package io.github.hangillab.hangil.core.vehicle

import ai.pleos.playground.navi.helper.NaviHelper
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Dedicated library-test package only. Never requests route, information, screen, or permissions. */
@RunWith(AndroidJUnit4::class)
class NaviInitialConnectionTest {
    @Test fun initialConnectionReleasesAndFreshInstanceReconnects() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.github.hangillab.hangil.core.vehicle.test", context.packageName)
        assertNavigationPermissionsAbsent(context)
        assertDeclaredMinimumServiceVersion(context)
        phase("DECLARED_MINIMUM_SERVICE_VERSION_SATISFIED")
        observeInitialConnectionThenRelease(context, 1)
        observeInitialConnectionThenRelease(context, 2)
        assertNavigationPermissionsAbsent(context)
    }

    private fun observeInitialConnectionThenRelease(
        context: Context,
        instance: Int,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bindingContext = NaviBindingContext(context)
        val finished = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        val outcome = AtomicReference<Outcome?>(null)
        val callbackCount = AtomicInteger(0)
        var sdk: NaviHelper? = null
        var failure: Throwable? = null
        try {
            val startupFailure = AtomicReference<Throwable?>(null)
            instrumentation.runOnMainSync {
                try {
                    sdk = NaviHelper(bindingContext)
                    sdk!!.initialize(
                        onConnected = {
                            if (!closed.get()) {
                                callbackCount.incrementAndGet()
                                if (outcome.compareAndSet(null, Outcome.INITIAL_CONNECTION_OBSERVED)) finished.countDown()
                            }
                        },
                        onFailed = { cause ->
                            if (!closed.get()) {
                                callbackCount.incrementAndGet()
                                val result =
                                    if (cause is SecurityException) {
                                        Outcome.INITIAL_PERMISSION_DENIED
                                    } else {
                                        Outcome.INITIAL_CONNECTION_FAILED
                                    }
                                if (outcome.compareAndSet(null, result)) finished.countDown()
                            }
                        },
                    )
                } catch (cause: Throwable) {
                    startupFailure.set(cause)
                }
            }
            startupFailure.get()?.let { throw AssertionError("SDK 초기화 호출이 실패했습니다", it) }
            assertTrue("NaviHelper 초기 연결 결과가 제한 시간 안에 도착하지 않았습니다", finished.await(25, TimeUnit.SECONDS))
            assertEquals("초기 실패나 권한 거부를 연결 성공으로 처리하지 않습니다", Outcome.INITIAL_CONNECTION_OBSERVED, outcome.get())
            assertEquals("최초 연결 결과는 한 번만 관찰되어야 합니다", 1, callbackCount.get())
            phase("INSTANCE_${instance}_INITIAL_CONNECTION_OBSERVED")
        } catch (cause: Throwable) {
            failure = cause
        } finally {
            closed.set(true)
            val releaseFailure = AtomicReference<Throwable?>(null)
            instrumentation.runOnMainSync {
                try {
                    sdk?.release()
                } catch (cause: Throwable) {
                    releaseFailure.set(cause)
                }
            }
            val cleanup =
                runCatching {
                    releaseFailure.get()?.let { throw AssertionError("SDK release 호출이 실패했습니다", it) }
                    val snapshot = bindingContext.snapshot()
                    assertEquals("release 후 실제로 소유한 플랫폼 연결이 남으면 안 됩니다", 0, snapshot.activeBindings)
                    assertEquals("SDK가 삼킨 플랫폼 해제 실패도 없어야 합니다", 0, snapshot.cleanupFailures)
                    if (outcome.get() == Outcome.INITIAL_CONNECTION_OBSERVED) {
                        assertTrue("실제 플랫폼 바인딩이 성공해야 합니다", snapshot.successfulBinds > 0)
                        assertTrue("실제 플랫폼 연결 callback을 관찰해야 합니다", snapshot.connectedCallbacks > 0)
                        assertTrue("실제 플랫폼 해제 호출을 관찰해야 합니다", snapshot.unbindCalls > 0)
                    }
                    phase(
                        "INSTANCE_${instance}_RELEASED_BIND_CALLS=${snapshot.bindCalls}" +
                            "_CONNECTED_CALLBACKS=${snapshot.connectedCallbacks}_UNBIND_CALLS=${snapshot.unbindCalls}" +
                            "_ACTIVE_BINDINGS=${snapshot.activeBindings}_CALLBACKS_AFTER_UNBIND=${snapshot.callbacksAfterUnbind}",
                    )
                }
            cleanup.exceptionOrNull()?.let { cleanupFailure ->
                val original = failure
                if (original == null) failure = cleanupFailure else original.addSuppressed(cleanupFailure)
            }
        }
        failure?.let { throw it }
    }

    private fun assertNavigationPermissionsAbsent(context: Context) {
        val requested =
            context.packageManager
                .getPackageInfo(
                    context.packageName,
                    PackageManager.GET_PERMISSIONS,
                ).requestedPermissions
                .orEmpty()
        listOf(
            "pleos.car.permission.NAVI_ROUTE",
            "pleos.car.permission.NAVI_ROUTE_SEARCH",
            "pleos.car.permission.NAVI_CUSTOM_MAP",
            "pleos.car.permission.CAR_DRIVE",
            "pleos.car.permission.CAR_LIGHTS",
            "pleos.car.permission.FUSED_LOCATION",
        ).forEach { permission ->
            assertFalse("연결 검증에 기능 권한을 선언하지 않습니다", permission in requested)
            assertEquals("연결 검증에 기능 권한을 허용하지 않습니다", PackageManager.PERMISSION_DENIED, context.checkSelfPermission(permission))
        }
    }

    private fun assertDeclaredMinimumServiceVersion(context: Context) {
        val metadata = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData
        assertEquals("2.0.1", metadata.getString("ai.pleos.playground.service.navi.helper.min_version_name"))
        assertEquals(11, metadata.getInt("ai.pleos.playground.service.navi.helper.min_version_code"))
        val service = context.packageManager.getPackageInfo("ai.pleos.playground.service.navi", 0)
        assertTrue("설치된 서비스는 SDK manifest의 최소 버전 조건을 충족해야 합니다", service.longVersionCode >= 11)
    }

    private fun phase(name: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("stream", "NAVI_CONNECTION_$name\n") },
        )
    }

    private enum class Outcome { INITIAL_CONNECTION_OBSERVED, INITIAL_CONNECTION_FAILED, INITIAL_PERMISSION_DENIED }
}
