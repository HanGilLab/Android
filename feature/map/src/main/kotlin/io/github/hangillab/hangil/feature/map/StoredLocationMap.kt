package io.github.hangillab.hangil.feature.map

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.designsystem.ParkedButton
import io.github.hangillab.hangil.core.domain.CourseRoadSection
import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.LocationRecordingEvent
import io.github.hangillab.hangil.core.domain.LocationRecordingRun
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import io.github.hangillab.hangil.core.domain.SessionReviewRoad
import io.github.hangillab.hangil.core.domain.SessionReviewSample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.RenderingEngine
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.tile.TileOperation
import org.maplibre.geojson.Point
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/** The caller creates this component only for an explicitly expanded, source-bound stored run. */
@Composable
fun StoredLocationMap(
    run: LocationRecordingRun,
    events: List<LocationRecordingEvent>,
    selectedEvent: LocationRecordingEvent?,
    canRead: () -> Boolean,
    modifier: Modifier = Modifier,
    reviewRoad: SessionReviewRoad? = null,
    inputChanges: Flow<Unit> = emptyFlow(),
    courseRoads: List<CourseRoadSnapshot> = emptyList(),
    onSaveCourseRoad: ((String) -> Unit)? = null,
    onSetRoadSection: ((CourseRoadSection?) -> Unit)? = null,
) {
    val input =
        remember(run, events, selectedEvent, courseRoads) {
            runCatching { StoredLocationMapInput.capture(run, events, selectedEvent, courseRoads) }.getOrNull()
        }
    // Latest inputs may invalidate a captured map, but never replace its captured permission guard.
    val suppliedRun by rememberUpdatedState(run)
    val suppliedEvents by rememberUpdatedState(events)
    val suppliedSelection by rememberUpdatedState(selectedEvent)
    val suppliedReview by rememberUpdatedState(reviewRoad)
    val suppliedCourseRoads by rememberUpdatedState(courseRoads)
    val suppliedSetRoadSection by rememberUpdatedState(onSetRoadSection)
    val owner = LocalLifecycleOwner.current
    val receipt = remember(input, reviewRoad, owner) { AtomicBoolean(true) }
    val withdrawn = remember(input, reviewRoad, owner) { mutableStateOf(false) }
    val inputChanged = remember(input, reviewRoad, owner) { mutableStateOf(false) }
    val capturedGuard = remember(input, reviewRoad, owner) { canRead }
    val permitted: () -> Boolean =
        remember(input, reviewRoad, owner) {
            {
                val unchanged =
                    input?.matches(suppliedRun, suppliedEvents, suppliedSelection, suppliedCourseRoads) != false &&
                        reviewRoad == suppliedReview
                if (!unchanged) {
                    inputChanged.value = true
                    receipt.set(false)
                }
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
    var previewChoice by remember(input, reviewRoad, owner) { mutableStateOf<Pair<CourseRoadSnapshot, Point>?>(null) }
    val previewSerial = remember(input, reviewRoad, owner) { mutableLongStateOf(0L) }
    val generation = previewSerial.longValue
    val nativeView = remember(input, reviewRoad, owner) { AtomicReference<ParkedMapView?>() }
    val preview = previewChoice
    val previewRoad = preview?.first
    val mapRoad = reviewRoad?.road ?: previewRoad
    val bringMapIntoView = remember(input, owner) { BringIntoViewRequester() }
    var backgroundMode by remember(input, reviewRoad, preview, owner) { mutableStateOf(StoredMapBackground.LOCAL) }
    val background = backgroundMode
    val online = preview == null && background == StoredMapBackground.ONLINE
    val fallback = background == StoredMapBackground.FAILED
    var attempt by remember(input, reviewRoad, owner) { mutableIntStateOf(0) }
    val capturedAttempt = attempt
    val reuseLocalView =
        preview != null && !online && !fallback && reviewRoad == null && selectedEvent?.reading is LocationUpdateReading.Sample &&
            !input?.courseRoads.isNullOrEmpty()
    val viewGeneration = generation.takeUnless { reuseLocalView }
    val renderer = remember(input, reviewRoad, owner, dark, fontScale, background, attempt, viewGeneration) { Any() }
    val suppliedRenderer by rememberUpdatedState(renderer)
    val rendererPermitted: () -> Boolean = {
        suppliedRenderer === renderer && permitted() && backgroundMode == background && attempt == capturedAttempt &&
            (reuseLocalView || previewSerial.longValue == generation)
    }
    val mapPermitted: () -> Boolean = {
        rendererPermitted() && previewSerial.longValue == generation && previewChoice == preview
    }
    val load = remember(renderer, generation) { mutableStateOf(MapLoadState.LOADING) }
    val suppliedLoad by rememberUpdatedState(load)
    val onFailed: () -> Unit = {
        if (mapPermitted()) {
            if (online) {
                backgroundMode = StoredMapBackground.FAILED
            } else {
                load.value = MapLoadState.FAILED
            }
        }
    }
    val retry: () -> Unit = {
        if (mapPermitted() && load.value == MapLoadState.FAILED) attempt++
    }
    val pointFill = MaterialTheme.colorScheme.primary.toArgb()
    val pointBorder = MaterialTheme.colorScheme.surface.toArgb()
    val roadColor = MaterialTheme.colorScheme.tertiary.toArgb()
    val sectionColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val publicSectionColor = MaterialTheme.colorScheme.secondary.toArgb()
    val localBackground = pointBorder.takeUnless { online }
    val sourceMatches = reviewRoad == null || input == null || reviewRoad.source.copy(endedAtEpochMillis = null) == input.run.source
    Column(modifier.fillMaxWidth().testTag("stored_location_map"), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
        if (inputChanged.value) {
            Text(
                stringResource(R.string.stored_map_changed),
                Modifier.testTag("stored_location_map_changed"),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else if (withdrawn.value || !permitted()) {
            Text(
                stringResource(R.string.stored_map_locked),
                Modifier.testTag("stored_location_map_locked"),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else if (input == null || !sourceMatches) {
            Text(
                stringResource(R.string.stored_map_invalid),
                Modifier.testTag("stored_location_map_invalid"),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Text(
                stringResource(R.string.stored_map_counts, input.samples.size, input.nonSampleCount),
                Modifier.fillMaxWidth().testTag("stored_location_map_counts"),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (input.samples.isEmpty()) {
                Text(
                    stringResource(R.string.stored_map_empty),
                    Modifier.testTag("stored_location_map_empty"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else if (!StoredLocationPoint.canProject(input)) {
                Text(
                    stringResource(R.string.stored_map_unavailable),
                    Modifier.testTag("stored_location_map_unavailable"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else if (mapRoad?.canProjectRoad() == false) {
                Text(
                    stringResource(R.string.stored_map_road_unavailable),
                    Modifier.testTag("stored_location_map_road_unavailable"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                val geometryResult = remember(load) { CompletableDeferred<StoredLocationMapGeometry>() }
                var roadGeometry by remember(load) { mutableStateOf<StoredLocationMapGeometry?>(null) }
                LaunchedEffect(geometryResult) {
                    try {
                        val prepared =
                            withContext(Dispatchers.Default) {
                                StoredLocationMapGeometry.prepare(
                                    input,
                                    mapRoad,
                                    reviewRoad,
                                    preview != null,
                                    generation.takeIf { reuseLocalView },
                                )
                            }
                        if (suppliedLoad === load && load.value == MapLoadState.LOADING && mapPermitted()) {
                            if (reviewRoad != null || input.courseRoads.isNotEmpty()) roadGeometry = prepared
                            geometryResult.complete(prepared)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (suppliedLoad === load && load.value == MapLoadState.LOADING) onFailed()
                    } catch (_: LinkageError) {
                        if (suppliedLoad === load && load.value == MapLoadState.LOADING) onFailed()
                    } finally {
                        if (!geometryResult.isCompleted) geometryResult.cancel()
                    }
                }
                val selectedDescription =
                    if (input.selectedEvent?.reading is LocationUpdateReading.Sample) {
                        stringResource(R.string.stored_map_selected_sample, input.selectedEvent.ordinal)
                    } else {
                        stringResource(R.string.stored_map_selected_without_coordinates)
                    }
                Text(
                    selectedDescription,
                    Modifier.fillMaxWidth().testTag("stored_location_map_selected"),
                    style = MaterialTheme.typography.titleLarge,
                )
                val selectedSample = (input.selectedEvent?.reading as? LocationUpdateReading.Sample)?.sample
                val section = roadGeometry?.section
                val anchor = reviewRoad?.sampleAnchor
                val savedSection = anchor?.roadSection
                val publicSection = roadGeometry?.publicSection
                val publicSectionLegend =
                    publicSection?.let {
                        stringResource(
                            if (savedSection ==
                                null
                            ) {
                                R.string.stored_map_public_road_section_preview
                            } else {
                                R.string.stored_map_public_road_section_saved
                            },
                        )
                    }
                val sectionLegend = section?.let { stringResource(R.string.stored_map_road_section) }
                if (sectionLegend != null && load.value == MapLoadState.READY) {
                    Text(
                        sectionLegend,
                        Modifier.fillMaxWidth().testTag("stored_location_map_road_section"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (publicSectionLegend != null && load.value == MapLoadState.READY) {
                    Text(
                        publicSectionLegend,
                        Modifier.fillMaxWidth().testTag(
                            if (savedSection ==
                                null
                            ) {
                                "stored_location_map_public_road_section_preview"
                            } else {
                                "stored_location_map_public_road_section_saved"
                            },
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (reviewRoad != null && selectedSample != null && roadGeometry != null) {
                    val meters = roadGeometry?.reviewDistance
                    Text(
                        when {
                            meters == null -> stringResource(R.string.stored_map_road_distance_unknown)
                            meters < 1 -> stringResource(R.string.stored_map_road_distance_under_one)
                            else -> stringResource(R.string.stored_map_road_distance_meters, meters.roundToInt())
                        },
                        Modifier.fillMaxWidth().testTag("stored_location_map_road_distance"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else if (reviewRoad == null && selectedSample != null && input.courseRoads.isNotEmpty()) {
                    key(input, owner) {
                        StoredLocationCourseRoads(
                            selectedSample,
                            input.courseRoads,
                            roadGeometry?.suggestedRoadId,
                            previewRoad,
                            permitted,
                            onPreview = { road ->
                                if (permitted()) {
                                    val next =
                                        if (previewChoice?.first == road) {
                                            null
                                        } else {
                                            StoredLocationRoadDistance.nearestPoint(selectedSample, road)?.let { road to it }
                                        }
                                    if (next != previewChoice) {
                                        previewSerial.longValue++
                                        previewChoice = next
                                        nativeView.get()?.let { view ->
                                            view.recheckInput()
                                            view.invalidate()
                                        }
                                    }
                                }
                            },
                            onSave = onSaveCourseRoad,
                        )
                    }
                }
                if (preview == null) {
                    Text(
                        stringResource(if (reviewRoad == null) R.string.stored_map_scope else R.string.stored_map_review_road_scope),
                        Modifier.fillMaxWidth().testTag(
                            if (reviewRoad == null) "stored_location_map_scope" else "stored_location_map_review_road_scope",
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Text(
                    stringResource(R.string.stored_map_uncertain),
                    Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val hasTrace = remember(input) { StoredLocationTrace.hasLinks(input) }
                if (preview == null && hasTrace) {
                    Text(
                        stringResource(R.string.stored_map_trace_scope),
                        Modifier.fillMaxWidth().testTag("stored_location_map_trace_scope"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (fallback && load.value == MapLoadState.READY) {
                    Text(
                        stringResource(R.string.stored_map_without_background),
                        Modifier.fillMaxWidth().testTag("stored_location_map_without_background"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                val previewLegend = previewRoad?.let { stringResource(R.string.stored_map_course_road_preview_legend, it.title) }
                if (previewLegend != null) {
                    Text(
                        previewLegend,
                        Modifier.fillMaxWidth().testTag("stored_location_course_road_preview_legend"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                val description =
                    if (previewLegend != null) {
                        "$selectedDescription. $previewLegend"
                    } else if (reviewRoad == null) {
                        stringResource(R.string.stored_map_content_description, input.samples.size, selectedDescription)
                    } else {
                        stringResource(
                            R.string.stored_map_review_road_content_description,
                            input.samples.size,
                            reviewRoad.road.title,
                            selectedDescription,
                        )
                    }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(HanGilDimensions.mapPreviewHeight)
                        .bringIntoViewRequester(bringMapIntoView)
                        .testTag(if (load.value == MapLoadState.READY) "stored_location_map_ready" else "stored_location_map_container"),
                ) {
                    if (load.value == MapLoadState.FAILED) {
                        Column(
                            Modifier.testTag("stored_location_map_failed"),
                            verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg),
                        ) {
                            Text(stringResource(R.string.map_failed), style = MaterialTheme.typography.bodyLarge)
                            Button(
                                onClick = retry,
                                modifier =
                                    Modifier
                                        .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                                        .testTag("stored_location_map_retry")
                                        .semantics {
                                            onClick {
                                                retry()
                                                true
                                            }
                                        },
                            ) {
                                Text(stringResource(R.string.map_retry), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    } else {
                        key(renderer) {
                            NativeStoredLocationMap(
                                input,
                                geometryResult,
                                nativeView,
                                generation.takeIf { reuseLocalView },
                                mapRoad,
                                preview?.second,
                                savedSection == null,
                                localBackground,
                                dark,
                                fontScale,
                                pointFill,
                                pointBorder,
                                roadColor,
                                sectionColor,
                                publicSectionColor,
                                rendererPermitted = rendererPermitted,
                                canRender = { suppliedLoad === load && load.value != MapLoadState.FAILED && mapPermitted() },
                                inputChanges = inputChanges,
                                canInteract = { mapPermitted() && load.value == MapLoadState.READY },
                                onRendered = {
                                    if (mapPermitted() &&
                                        load.value == MapLoadState.LOADING
                                    ) {
                                        load.value = MapLoadState.READY
                                    }
                                },
                                onFailed = onFailed,
                                contentDescription = listOfNotNull(description, sectionLegend, publicSectionLegend).joinToString(" "),
                                modifier = Modifier.matchParentSize().testTag("stored_location_map_view"),
                            )
                        }
                        if (load.value == MapLoadState.LOADING) {
                            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surface))
                            Column(
                                Modifier.align(Alignment.Center).testTag("stored_location_map_loading"),
                                verticalArrangement = Arrangement.spacedBy(HanGilSpacing.md),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.map_loading), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
                val exactAnchor =
                    selectedSample != null && anchor?.sameCallback(SessionReviewSample.from(run, checkNotNull(selectedEvent))) == true
                if (onSetRoadSection != null && publicSection != null && exactAnchor && load.value == MapLoadState.READY) {
                    ParkedButton(
                        onClick = {
                            if (mapPermitted() && suppliedLoad === load && load.value == MapLoadState.READY &&
                                suppliedSetRoadSection != null
                            ) {
                                onSetRoadSection(if (savedSection == null) section?.publicRange else null)
                            }
                        },
                        modifier =
                            Modifier.testTag(
                                if (savedSection == null) "stored_location_map_use_road_section" else "stored_location_map_use_whole_road",
                            ),
                        prominent = false,
                    ) {
                        Text(
                            stringResource(
                                if (savedSection ==
                                    null
                                ) {
                                    R.string.stored_map_use_road_section
                                } else {
                                    R.string.stored_map_use_whole_road
                                },
                            ),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                if (reviewRoad != null || (selectedSample != null && input.courseRoads.isNotEmpty())) {
                    Text(
                        stringResource(R.string.map_road_attribution),
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (preview == null && (online || load.value == MapLoadState.READY)) {
                    if (!online) {
                        Text(
                            stringResource(R.string.stored_map_background_notice),
                            Modifier.fillMaxWidth().testTag("stored_location_map_background_notice"),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val (action, tag) =
                        when (background) {
                            StoredMapBackground.LOCAL -> R.string.stored_map_show_background to "stored_location_map_show_background"
                            StoredMapBackground.ONLINE -> R.string.stored_map_hide_background to "stored_location_map_hide_background"
                            StoredMapBackground.FAILED -> R.string.stored_map_retry_background to "stored_location_map_retry_background"
                        }
                    key(load) {
                        ParkedButton(
                            onClick = {
                                if (mapPermitted() && suppliedLoad === load && (online || load.value == MapLoadState.READY)) {
                                    attempt++
                                    backgroundMode = if (online) StoredMapBackground.LOCAL else StoredMapBackground.ONLINE
                                }
                            },
                            modifier = Modifier.testTag(tag),
                            prominent = false,
                        ) {
                            Text(stringResource(action), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                if (online) {
                    MapCredits(
                        dark,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val previewPrepared = preview != null && (roadGeometry != null || load.value == MapLoadState.FAILED)
                LaunchedEffect(preview, previewPrepared) {
                    if (previewPrepared && mapPermitted()) bringMapIntoView.bringIntoView()
                }
                LaunchedEffect(load, load.value) {
                    if (load.value == MapLoadState.LOADING) {
                        delay(20_000)
                        if (load.value == MapLoadState.LOADING) onFailed()
                    }
                }
            }
        }
    }
}

@Composable
private fun StoredLocationCourseRoads(
    sample: LocationUpdateSample,
    roads: List<CourseRoadSnapshot>,
    suggestedRoadId: String?,
    preview: CourseRoadSnapshot?,
    permitted: () -> Boolean,
    onPreview: (CourseRoadSnapshot) -> Unit,
    onSave: ((String) -> Unit)?,
) {
    val distances =
        remember(sample, roads) {
            roads
                .map { road ->
                    val meters = StoredLocationRoadDistance.meters(sample, road)
                    road to meters?.let { if (it < 1) 0 else it.roundToInt() }
                }.sortedBy { (_, meters) -> meters ?: Int.MAX_VALUE }
        }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.md)) {
        Text(
            stringResource(R.string.stored_map_course_roads),
            Modifier.fillMaxWidth().testTag("stored_location_course_roads"),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(stringResource(R.string.stored_map_course_roads_scope), style = MaterialTheme.typography.bodyLarge)
        distances.forEachIndexed { index, (road, meters) ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.sm)) {
                Text(
                    when (meters) {
                        null -> stringResource(R.string.stored_map_course_road_unknown, road.title)
                        0 -> stringResource(R.string.stored_map_course_road_under_one, road.title)
                        else -> stringResource(R.string.stored_map_course_road_meters, road.title, meters)
                    },
                    Modifier.fillMaxWidth().testTag("stored_location_course_road_$index"),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (road.id == suggestedRoadId) {
                    Text(
                        stringResource(R.string.stored_map_course_road_suggestion),
                        Modifier.fillMaxWidth().testTag("stored_location_course_road_suggestion"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (meters != null && road.canProjectRoad()) {
                    ParkedButton(
                        onClick = { onPreview(road) },
                        modifier = Modifier.fillMaxWidth().testTag("stored_location_course_road_preview_$index"),
                        prominent = false,
                    ) {
                        Text(
                            if (road == preview) {
                                stringResource(R.string.stored_map_course_road_preview_close)
                            } else {
                                stringResource(R.string.stored_map_course_road_preview, road.title)
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                if (onSave != null && meters != null) {
                    ParkedButton(
                        onClick = { if (permitted()) onSave(road.id) },
                        modifier = Modifier.fillMaxWidth().testTag("stored_location_course_road_save_$index"),
                        prominent = false,
                    ) {
                        Text(stringResource(R.string.stored_map_course_road_save, road.title), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeStoredLocationMap(
    input: StoredLocationMapInput,
    geometry: Deferred<StoredLocationMapGeometry>,
    nativeView: AtomicReference<ParkedMapView?>,
    renderGeneration: Long?,
    road: CourseRoadSnapshot?,
    nearest: Point?,
    publicSectionIsCandidate: Boolean,
    localBackground: Int?,
    dark: Boolean,
    fontScale: Float,
    pointFill: Int,
    pointBorder: Int,
    roadColor: Int,
    sectionColor: Int,
    publicSectionColor: Int,
    rendererPermitted: () -> Boolean,
    canRender: () -> Boolean,
    inputChanges: Flow<Unit>,
    canInteract: () -> Boolean,
    onRendered: () -> Unit,
    onFailed: () -> Unit,
    contentDescription: String,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val active = remember { AtomicBoolean(true) }
    val savedState = remember { MapViewSavedState() }
    val styleReady = remember { CompletableDeferred<Pair<MapLibreMap, Style>>() }
    var rendererFailed by remember { mutableStateOf(false) }
    val rendered = remember { AtomicReference<Deferred<StoredLocationMapGeometry>?>() }
    val suppliedGeometry by rememberUpdatedState(geometry)
    val liveRender by rememberUpdatedState(canRender)
    val liveInput by rememberUpdatedState(canInteract)
    val liveFailure by rememberUpdatedState(onFailed)
    val liveRenderer by rememberUpdatedState(rendererPermitted)
    val result =
        remember(context, owner) {
            createStoredMap(
                context,
                savedState,
                styleReady,
                localBackground,
                dark,
                active,
                current = { active.get() && liveRenderer() },
                canDraw = { active.get() && liveRender() },
                canInteract = { active.get() && liveInput() && rendered.get() === suppliedGeometry },
                onFailed = {
                    if (active.get() && liveRenderer()) {
                        rendererFailed = true
                        if (liveRender()) liveFailure()
                    }
                },
            )
        }
    LaunchedEffect(result) { if (result.isFailure && canRender()) onFailed() }
    val view = result.getOrNull() ?: return
    LaunchedEffect(view, geometry) {
        val current = { active.get() && canRender() }
        if (rendererFailed) {
            if (current()) onFailed()
            return@LaunchedEffect
        }
        try {
            val (map, style) = styleReady.await()
            val prepared = geometry.await()
            withContext(Dispatchers.Main.immediate) {
                if (!current()) return@withContext
                applyStoredMapFrame(
                    view,
                    map,
                    style,
                    input.selectedSampleId,
                    renderGeneration,
                    prepared,
                    road,
                    nearest,
                    publicSectionIsCandidate,
                    fontScale,
                    pointFill,
                    pointBorder,
                    roadColor,
                    sectionColor,
                    publicSectionColor,
                    current,
                )
                if (current() && map.style === style) {
                    rendered.set(geometry)
                    view.invalidate()
                    onRendered()
                    view.recheckInput()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (current()) onFailed()
        } catch (_: LinkageError) {
            if (current()) onFailed()
        }
    }
    LaunchedEffect(view, owner, inputChanges) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            inputChanges.collect {
                withContext(Dispatchers.Main.immediate) {
                    if (active.get() && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        // Latch revocation even before the native map is bound.
                        liveRender()
                        view.recheckInput()
                    }
                }
            }
        }
    }
    DisposableEffect(view, owner) {
        nativeView.set(view)
        val observer = MapViewLifecycle(view, savedState) { active.set(false) }
        val memory =
            object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                override fun onLowMemory() = observer.lowMemory()
            }
        owner.lifecycle.addObserver(observer)
        observer.synchronize(owner.lifecycle.currentState)
        context.applicationContext.registerComponentCallbacks(memory)
        onDispose {
            active.set(false)
            nativeView.compareAndSet(view, null)
            styleReady.cancel()
            owner.lifecycle.removeObserver(observer)
            context.applicationContext.unregisterComponentCallbacks(memory)
            observer.destroy()
        }
    }
    AndroidView(
        factory = { view },
        modifier = modifier,
        update = {
            it.contentDescription = contentDescription
            it.recheckInput()
        },
    )
}

private fun createStoredMap(
    context: Context,
    savedState: MapViewSavedState,
    styleReady: CompletableDeferred<Pair<MapLibreMap, Style>>,
    localBackground: Int?,
    dark: Boolean,
    active: AtomicBoolean,
    current: () -> Boolean,
    canDraw: () -> Boolean,
    canInteract: () -> Boolean,
    onFailed: () -> Unit,
): Result<ParkedMapView> {
    var created: ParkedMapView? = null
    return try {
        check(current()) { "Stored map owner expired" }
        MapLibre.getInstance(context.applicationContext, null, WellKnownTileServer.MapLibre, RenderingEngine.Type.OPENGL)
        val options =
            MapLibreMapOptions
                .createFromAttributes(context)
                .textureMode(true)
                .attributionEnabled(true)
                .localIdeographFontFamilyEnabled(true)
                .localIdeographFontFamily("sans-serif")
        val view = ParkedMapView(context, options, canInteract, canDraw)
        created = view
        val main = Handler(Looper.getMainLooper())
        val fail = {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                if (current()) onFailed()
            } else {
                main.post { if (current()) onFailed() }
            }
            Unit
        }
        view.addOnDidFailLoadingMapListener { fail() }
        view.addOnRenderErrorListener { fail() }
        view.addOnShaderCompileFailedListener { _, _, _ -> fail() }
        view.addOnGlyphsErrorListener { _, _, _ -> fail() }
        view.addOnSpriteErrorListener { _, _ -> fail() }
        view.addOnTileActionListener { operation, _, _, _, _, _, _ -> if (operation == TileOperation.Error) fail() }
        view.onCreate(savedState.bundle)
        savedState.attach(view)
        view.getMapAsync { map ->
            if (current()) {
                try {
                    view.bindMap(map)
                    val style = Style.Builder()
                    if (localBackground != null) {
                        val color = (localBackground and 0xFFFFFF).toString(16).padStart(6, '0')
                        style.fromJson(
                            """
                            {"version":8,"sources":{},"layers":[
                              {"id":"stored-map-background","type":"background","paint":{"background-color":"#$color"}}
                            ]}
                            """.trimIndent(),
                        )
                    } else {
                        style.fromUri("https://tiles.openfreemap.org/styles/" + if (dark) "dark" else "liberty")
                    }
                    map.setStyle(style) { loaded ->
                        if (current()) styleReady.complete(map to loaded)
                    }
                } catch (_: Exception) {
                    fail()
                } catch (_: LinkageError) {
                    fail()
                }
            }
        }
        Result.success(view)
    } catch (failure: Exception) {
        active.set(false)
        created?.let {
            savedState.detach(it)
            it.onDestroy()
        }
        Result.failure(failure)
    } catch (failure: LinkageError) {
        active.set(false)
        created?.let {
            savedState.detach(it)
            it.onDestroy()
        }
        Result.failure(failure)
    }
}

private suspend fun applyStoredMapFrame(
    view: ParkedMapView,
    map: MapLibreMap,
    style: Style,
    selectedSampleId: String?,
    renderGeneration: Long?,
    prepared: StoredLocationMapGeometry,
    road: CourseRoadSnapshot?,
    nearest: Point?,
    publicSectionIsCandidate: Boolean,
    fontScale: Float,
    pointFill: Int,
    pointBorder: Int,
    roadColor: Int,
    sectionColor: Int,
    publicSectionColor: Int,
    current: () -> Boolean,
) {
    if (renderGeneration != null) clearStoredMapOverlays(style)
    road?.let {
        RoadPreviewLayers.add(style, listOf(it), roadColor)
        RoadPreviewLayers.select(style, it.id)
    }
    prepared.section?.projected?.let { StoredLocationRoadSection.add(style, it, sectionColor) }
    prepared.publicSection?.let {
        StoredLocationRoadSection.addPublic(style, it, publicSectionColor, publicSectionIsCandidate)
    }
    prepared.trace?.let { StoredLocationTrace.add(style, it, pointFill) }
    StoredLocationPoint.add(style, prepared.points, selectedSampleId, pointFill, pointBorder, fontScale)
    nearest?.let {
        StoredLocationRoadProjection.add(style, it, roadColor, pointBorder, fontScale)
    }

    val done = CompletableDeferred<Unit>()
    val fitted = AtomicBoolean(false)
    val fullyRendered =
        MapView.OnDidFinishRenderingFrameListener { fully, _, _ ->
            if (fully && fitted.get()) done.complete(Unit)
        }
    val padding = (CoursePreviewLayers.viewportPaddingDp(fontScale) * view.resources.displayMetrics.density).roundToInt()

    fun fitCamera() {
        if (current() && !fitted.get() && view.width > padding * 2 && view.height > padding * 2) {
            try {
                val camera =
                    prepared.bounds?.let { CameraUpdateFactory.newLatLngBounds(it, padding) }
                        ?: CameraUpdateFactory.newLatLngZoom(prepared.center, 15.0)
                map.moveCamera(camera)
                fitted.set(true)
                map.triggerRepaint()
            } catch (failure: Exception) {
                done.completeExceptionally(failure)
            } catch (failure: LinkageError) {
                done.completeExceptionally(failure)
            }
        }
    }
    val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitCamera() }
    view.addOnDidFinishRenderingFrameListener(fullyRendered)
    view.addOnLayoutChangeListener(layout)
    try {
        fitCamera()
        done.await()
    } finally {
        view.removeOnLayoutChangeListener(layout)
        view.removeOnDidFinishRenderingFrameListener(fullyRendered)
    }
    if (renderGeneration != null) {
        val expected = renderGeneration.toString()
        val roadId = checkNotNull(road).id
        val expectedProjection = checkNotNull(nearest)
        val viewport = RectF()
        while (current()) {
            viewport.set(0f, 0f, view.width.toFloat(), view.height.toFloat())
            val pointCurrent =
                map.queryRenderedFeatures(viewport, StoredLocationPoint.LAYER).any {
                    it.getStringProperty(StoredLocationPoint.RENDER_GENERATION) == expected
                }
            if (pointCurrent) {
                val roadFeatures = map.queryRenderedFeatures(viewport, RoadPreviewLayers.SELECTION)
                val projection = map.queryRenderedFeatures(viewport, StoredLocationRoadProjection.LAYER)
                val expectedPixel =
                    map.projection.toScreenLocation(
                        LatLng(expectedProjection.latitude(), expectedProjection.longitude()),
                    )
                val projectionCurrent =
                    projection.isNotEmpty() &&
                        projection.all { feature ->
                            val point = feature.geometry() as? Point ?: return@all false
                            val pixel = map.projection.toScreenLocation(LatLng(point.latitude(), point.longitude()))
                            abs(pixel.x - expectedPixel.x) <= 1f && abs(pixel.y - expectedPixel.y) <= 1f
                        }
                if (projectionCurrent && roadFeatures.isNotEmpty() && roadFeatures.all { it.getStringProperty("road_id") == roadId }) {
                    break
                }
            }
            map.triggerRepaint()
            delay(16)
        }
    }
}

private fun clearStoredMapOverlays(style: Style) {
    for (id in STORED_OVERLAY_LAYERS) {
        if (style.getLayer(id) != null) check(style.removeLayer(id))
    }
    for (id in STORED_OVERLAY_SOURCES) {
        if (style.getSource(id) != null) check(style.removeSource(id))
    }
}

private val STORED_OVERLAY_LAYERS =
    listOf(
        StoredLocationRoadProjection.LAYER,
        StoredLocationPoint.SELECTION,
        StoredLocationPoint.LAYER,
        StoredLocationTrace.LAYER,
        RoadPreviewLayers.SELECTION,
        RoadPreviewLayers.BASE,
    )

private val STORED_OVERLAY_SOURCES =
    listOf(
        StoredLocationRoadProjection.SOURCE,
        StoredLocationPoint.SOURCE,
        StoredLocationTrace.SOURCE,
        RoadPreviewLayers.SOURCE,
    )

private fun CourseRoadSnapshot.canProjectRoad(): Boolean = nodes.all { it.latitude in -85.0511287798066..85.0511287798066 }
