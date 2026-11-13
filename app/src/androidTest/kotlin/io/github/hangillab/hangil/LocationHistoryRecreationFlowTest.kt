package io.github.hangillab.hangil

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.database.OwnedPracticeTestStore
import io.github.hangillab.hangil.core.designsystem.HanGilTheme
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.LocationRecordingBeginResult
import io.github.hangillab.hangil.core.domain.LocationRecordingEndResult
import io.github.hangillab.hangil.core.domain.LocationRecordingRepository
import io.github.hangillab.hangil.core.domain.LocationRecordingStopReason
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.feature.home.LocationHistoryRoute
import io.github.hangillab.hangil.feature.home.LocationHistoryViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Real Activity recreation, the app's exact source owner and real stored-location UI with owned Room. */
@RunWith(AndroidJUnit4::class)
class LocationHistoryRecreationFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()

    @Test fun locationHistoryKeepsOlderExactSourceWhenActivityRecreates() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("manualLocationHistoryRecreation") == "true")
        val scale = checkNotNull(args.getString("expectedFontScale")).toFloat()
        val dark = checkNotNull(args.getString("expectedDark")).toBooleanStrict()
        val configuration = compose.activity.resources.configuration
        assertEquals(scale, configuration.fontScale, 0.01f)
        assertEquals(dark, configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)

        val store = OwnedPracticeTestStore.create(InstrumentationRegistry.getInstrumentation().targetContext)
        val activeReaders = AtomicInteger()
        var primary: Throwable? = null
        try {
            val (older, newer) = seed(store)
            val sessions = store.sessions
            val locations = store.locationRecordings
            val reads = ConcurrentLinkedQueue<String>()

            fun <T> tracked(upstream: Flow<T>) =
                flow {
                    activeReaders.incrementAndGet()
                    try {
                        emitAll(upstream)
                    } finally {
                        activeReaders.decrementAndGet()
                    }
                }

            val model =
                LocationHistoryViewModel(
                    object : PracticeSessionRepository by sessions {
                        override fun observeSession(sessionId: String) = tracked(sessions.observeSession(sessionId))
                    },
                    object : LocationRecordingRepository by locations {
                        override fun observe(sessionId: String) =
                            tracked(
                                flow {
                                    reads.add(sessionId)
                                    emitAll(locations.observe(sessionId))
                                },
                            )
                    },
                    object : VehicleGateway {
                        override val state =
                            MutableStateFlow(VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK)))
                    },
                    object : DrivingRestrictionsProvider {
                        override val restrictions =
                            MutableStateFlow(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))
                    },
                    store.memos,
                )

            val scenario = compose.activityRule.scenario
            val originalActivity = compose.activity
            lateinit var originalOwner: ExactSessionNavigationViewModel
            lateinit var locationToken: String
            lateinit var finishedToken: String
            scenario.onActivity { activity ->
                val models = ViewModelProvider(activity)
                originalOwner = models[ExactSessionNavigationViewModel.LOCATION_HISTORY_KEY, ExactSessionNavigationViewModel::class.java]
                locationToken = checkNotNull(originalOwner.open(older))
                finishedToken = checkNotNull(models[ExactSessionNavigationViewModel::class.java].open(newer))
            }

            fun renderLocationHistory() {
                scenario.onActivity { activity ->
                    val models = ViewModelProvider(activity)
                    val owner = models[ExactSessionNavigationViewModel.LOCATION_HISTORY_KEY, ExactSessionNavigationViewModel::class.java]
                    assertSame(originalOwner, owner)
                    assertEquals(newer, models[ExactSessionNavigationViewModel::class.java].source(finishedToken))
                    assertNull(owner.source(finishedToken))
                    val source = checkNotNull(owner.source(locationToken))
                    assertEquals(older, source)
                    activity.setContent {
                        HanGilTheme { LocationHistoryRoute(source, onClose = {}, viewModel = model) }
                    }
                }
            }

            fun awaitLocationContent() {
                compose.waitUntil(20_000) {
                    compose.onAllNodesWithTag("location_history_content").fetchSemanticsNodes(false).size == 1
                }
                compose.onNodeWithTag("location_history_content").performScrollTo().assertIsDisplayed()
            }

            renderLocationHistory()
            awaitLocationContent()
            val beforeRecreationReads = reads.size
            assertTrue(beforeRecreationReads > 0)
            scenario.recreate()
            assertNotSame(originalActivity, compose.activity)
            compose.waitUntil(20_000) { activeReaders.get() == 0 }

            renderLocationHistory()
            awaitLocationContent()
            assertTrue(reads.size > beforeRecreationReads)
            assertTrue(reads.all { it == older.sessionId })
            runBlocking {
                assertEquals(listOf(newer, older), sessions.observeSessions().first())
                assertEquals(
                    1,
                    locations
                        .observe(older.sessionId)
                        .first()
                        .runs.size,
                )
                assertTrue(
                    locations
                        .observe(newer.sessionId)
                        .first()
                        .runs
                        .isEmpty(),
                )
            }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                compose.activityRule.scenario.onActivity { it.setContent {} }
                compose.waitUntil(20_000) { activeReaders.get() == 0 }
                store.close()
            } catch (cleanup: Throwable) {
                val original = primary
                if (original == null) throw cleanup else original.addSuppressed(cleanup)
            }
        }
    }

    private fun seed(store: OwnedPracticeTestStore) =
        runBlocking {
            val course = store.catalog.courses.first()
            val goal = store.catalog.goals.first { it.id in course.goalIds }
            val selection = PracticeSelection(goal.id, course.id, course.version, course.briefingVersion, course.tasks.first().id)
            check(store.preparation.saveSelection(selection) { true })
            val practice = (store.catalog.resolve(selection) as Preparation.Ready).practice
            val sessions = store.sessions
            val locations = store.locationRecordings
            val older = (sessions.start(practice, 1_000) { true } as SessionStartResult.Started).session
            val run =
                (locations.begin(older, "owned-run", "owned-process", 1_010, 10) { true } as LocationRecordingBeginResult.Begun).run
            val reason = LocationRecordingStopReason.USER_STOP
            check(locations.end(run, reason.status, reason, 1_020, 20) is LocationRecordingEndResult.Ended)
            val endedOlder = (sessions.finish(older, 1_030) { true } as SessionFinishResult.Finished).session
            val newer = (sessions.start(practice, 2_000) { true } as SessionStartResult.Started).session
            val endedNewer = (sessions.finish(newer, 2_030) { true } as SessionFinishResult.Finished).session
            endedOlder to endedNewer
        }
}
