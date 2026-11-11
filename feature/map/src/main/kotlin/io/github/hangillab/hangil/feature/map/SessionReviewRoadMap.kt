package io.github.hangillab.hangil.feature.map

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.domain.CourseRoadSection
import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.PracticeSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Read-only rendering for an explicitly opened, exact finished source. The caller owns road
 * selection persistence; a native style acknowledgement never acknowledges a repository write.
 */
@Composable
fun SessionReviewRoadMap(
    source: PracticeSession,
    roads: List<CourseRoadSnapshot>,
    selectedRoadId: String?,
    canPresent: () -> Boolean,
    inputChanges: Flow<Unit>,
    modifier: Modifier = Modifier,
    section: CourseRoadSection? = null,
) {
    // Snapshot on each composition so mutations of an external list cannot mutate remembered keys.
    val preview = MapPreview.from(source, roads)
    val sectionRoad = preview.roads.singleOrNull { it.id == selectedRoadId }
    val publicRoadSection =
        remember(sectionRoad, section) {
            if (sectionRoad == null || section == null) null else StoredLocationRoadSection.publicLine(sectionRoad, section)
        }
    val sectionOwner = section?.let { selectedRoadId to it }
    val suppliedSectionOwner by rememberUpdatedState(sectionOwner)
    val owner = LocalLifecycleOwner.current
    val receipt = remember(preview, owner) { AtomicBoolean(true) }
    val withdrawn = remember(preview, owner) { mutableStateOf(false) }
    val capturedGuard = remember(preview, owner) { canPresent }
    val permitted: () -> Boolean =
        remember(preview, owner) {
            {
                val allowed =
                    receipt.get() && owner.lifecycle.currentState == Lifecycle.State.RESUMED &&
                        try {
                            capturedGuard()
                        } catch (_: Exception) {
                            false
                        }
                val current = allowed && receipt.get() && owner.lifecycle.currentState == Lifecycle.State.RESUMED
                if (!current) {
                    receipt.set(false)
                    withdrawn.value = true
                }
                current
            }
        }
    DisposableEffect(owner, receipt) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                    receipt.set(false)
                    withdrawn.value = true
                }
            }
        owner.lifecycle.addObserver(observer)
        onDispose {
            receipt.set(false)
            owner.lifecycle.removeObserver(observer)
        }
    }
    val dark = isSystemInDarkTheme()
    val fontScale = LocalDensity.current.fontScale
    var attempt by remember(preview, owner) { mutableIntStateOf(0) }
    val capturedAttempt = attempt
    val load = remember(preview, owner, dark, fontScale, capturedAttempt, sectionOwner) { mutableStateOf(MapLoadState.LOADING) }
    val renderer = remember(load) { AtomicBoolean(true) }
    val tokens = remember(preview, owner) { AtomicLong() }
    val token = remember(load) { tokens.incrementAndGet() }
    val current: () -> Boolean =
        remember(load) {
            { renderer.get() && attempt == capturedAttempt && sectionOwner == suppliedSectionOwner && permitted() }
        }
    DisposableEffect(renderer) { onDispose { renderer.set(false) } }
    val nonces = remember(preview, owner) { AtomicLong() }
    val selectedNonce = remember(load) { AtomicLong(0) }
    val invalidSelection = selectedRoadId != null && preview.roads.none { it.id == selectedRoadId }
    val choice =
        remember(load, selectedRoadId) {
            val selection =
                if (selectedRoadId == null || invalidSelection) {
                    null
                } else {
                    val nonce = nonces.incrementAndGet()
                    MapSelection(MapSelection.Target.Road(selectedRoadId), nonce) {
                        current() && load.value == MapLoadState.READY && selectedNonce.get() == nonce
                    }
                }
            selectedNonce.set(selection?.nonce ?: 0)
            selection
        }
    val acknowledged = remember(load, choice?.nonce) { mutableStateOf(false) }
    val canApply: (Int, Long, Long) -> Boolean = { expectedAttempt, expectedToken, nonce ->
        expectedAttempt == capturedAttempt && expectedToken == token && current() &&
            load.value == MapLoadState.READY && selectedNonce.get() == nonce &&
            (nonce == 0L || choice?.nonce == nonce)
    }
    val changes = remember(inputChanges, receipt) { inputChanges.onEach { permitted() } }
    val retry: () -> Unit = {
        if (current() && attempt == capturedAttempt && load.value == MapLoadState.FAILED) attempt++
    }
    Column(modifier.fillMaxWidth().testTag("session_review_road_map"), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
        if (withdrawn.value || !permitted()) {
            Text(
                stringResource(R.string.session_review_road_map_locked),
                Modifier.testTag("session_review_road_map_locked"),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else if (invalidSelection ||
            preview.roads.any { road -> road.nodes.any { it.latitude !in -85.0511287798066..85.0511287798066 } }
        ) {
            Text(
                stringResource(R.string.session_review_road_map_unavailable),
                Modifier.testTag("session_review_road_map_unavailable"),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Text(stringResource(R.string.session_review_road_map_scope), style = MaterialTheme.typography.bodyLarge)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(HanGilDimensions.mapPreviewHeight)
                    .testTag(
                        if (load.value ==
                            MapLoadState.READY
                        ) {
                            "session_review_road_map_ready"
                        } else {
                            "session_review_road_map_container"
                        },
                    ),
            ) {
                if (load.value == MapLoadState.FAILED) {
                    Column(
                        Modifier.testTag("session_review_road_map_failed"),
                        verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg),
                    ) {
                        Text(stringResource(R.string.map_failed), style = MaterialTheme.typography.bodyLarge)
                        Button(
                            onClick = retry,
                            modifier =
                                Modifier
                                    .heightIn(
                                        min = HanGilDimensions.secondaryActionMinHeight,
                                    ).testTag("session_review_road_map_retry"),
                        ) { Text(stringResource(R.string.map_retry), style = MaterialTheme.typography.titleMedium) }
                    }
                } else {
                    key(preview, owner, dark, fontScale, capturedAttempt, sectionOwner) {
                        DemoMap(
                            attempt = capturedAttempt,
                            preview = preview,
                            dark = dark,
                            fontScale = fontScale,
                            pointFill = MaterialTheme.colorScheme.primary.toArgb(),
                            pointText = MaterialTheme.colorScheme.onPrimary.toArgb(),
                            pointBorder = MaterialTheme.colorScheme.surface.toArgb(),
                            contentDescription =
                                stringResource(
                                    R.string.session_review_road_map_content_description,
                                    source.courseTitle,
                                    preview.roads.joinToString(", ") { it.title },
                                ),
                            onLoading = { expectedAttempt ->
                                if (expectedAttempt == capturedAttempt && current() && load.value != MapLoadState.FAILED) {
                                    load.value = MapLoadState.LOADING
                                    token
                                } else {
                                    null
                                }
                            },
                            canInteract = { expectedAttempt, expectedToken ->
                                expectedAttempt == capturedAttempt && expectedToken == token && current() &&
                                    load.value == MapLoadState.READY
                            },
                            inputChanges = changes,
                            onRendered = { expectedAttempt, expectedToken ->
                                if (expectedAttempt == capturedAttempt && expectedToken == token && current() &&
                                    load.value == MapLoadState.LOADING
                                ) {
                                    load.value = MapLoadState.READY
                                }
                            },
                            onFailed = { expectedAttempt, expectedToken ->
                                if (expectedAttempt == capturedAttempt && expectedToken == token &&
                                    current()
                                ) {
                                    load.value = MapLoadState.FAILED
                                }
                            },
                            selection = choice?.copy(rendered = acknowledged.value),
                            canApplySelection = canApply,
                            onSelectionApplied = { expectedAttempt, expectedToken, nonce ->
                                if (nonce > 0 && canApply(expectedAttempt, expectedToken, nonce)) acknowledged.value = true
                            },
                            onNativeSelect = { _, _ -> },
                            canRender = { current() && load.value != MapLoadState.FAILED },
                            publicRoadSection = publicRoadSection,
                            publicRoadSectionColor = MaterialTheme.colorScheme.secondary.toArgb(),
                            modifier = Modifier.matchParentSize().testTag("session_review_road_map_view"),
                        )
                    }
                    if (load.value == MapLoadState.LOADING) {
                        Column(Modifier.align(Alignment.Center), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.md)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.map_loading), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            if (publicRoadSection != null && load.value == MapLoadState.READY) {
                Text(
                    stringResource(R.string.session_review_road_map_public_road_section),
                    Modifier.testTag("session_review_road_map_public_road_section"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (choice != null) {
                Text(
                    if (acknowledged.value) {
                        preview.roads.single { it.id == selectedRoadId }.title
                    } else {
                        stringResource(R.string.map_road_selection_pending)
                    },
                    Modifier.testTag(
                        if (acknowledged.value) "session_review_road_map_selection_ready" else "session_review_road_map_selection_pending",
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(
                stringResource(R.string.map_road_attribution),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MapCredits(
                dark,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LaunchedEffect(load) {
                delay(20_000)
                if (current() && load.value == MapLoadState.LOADING) load.value = MapLoadState.FAILED
            }
        }
    }
}
