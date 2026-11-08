package io.github.hangillab.hangil.feature.map

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.view.View
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
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import io.github.hangillab.hangil.core.domain.SessionReviewRoad
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
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.tile.TileOperation
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** The caller creates this component only for an explicitly expanded, source-bound stored row. */
@Composable
fun StoredLocationMap(
    sample: LocationUpdateSample,
    canRead: () -> Boolean,
    modifier: Modifier = Modifier,
    reviewRoad: SessionReviewRoad? = null,
    inputChanges: Flow<Unit> = emptyFlow(),
) {
    val owner = LocalLifecycleOwner.current
    val receipt = remember(sample, reviewRoad, owner) { AtomicBoolean(true) }
    val withdrawn = remember(sample, reviewRoad, owner) { mutableStateOf(false) }
    val capturedGuard = remember(sample, reviewRoad, owner) { canRead }
    val permitted: () -> Boolean =
        remember(sample, reviewRoad, owner) {
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
    var attempt by remember(sample, reviewRoad, owner) { mutableIntStateOf(0) }
    val capturedAttempt = attempt
    val load = remember(sample, reviewRoad, owner, dark, fontScale, attempt) { mutableStateOf(MapLoadState.LOADING) }
    val retry: () -> Unit = {
        if (attempt == capturedAttempt && load.value == MapLoadState.FAILED && permitted()) attempt++
    }
    val pointFill = MaterialTheme.colorScheme.primary.toArgb()
    val pointBorder = MaterialTheme.colorScheme.surface.toArgb()
    val roadColor = MaterialTheme.colorScheme.tertiary.toArgb()
    Column(modifier.fillMaxWidth().testTag("stored_location_map"), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
        if (withdrawn.value || !permitted()) {
            Text(
                stringResource(R.string.stored_map_locked),
                Modifier.testTag("stored_location_map_locked"),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else if (!StoredLocationPoint.canProject(sample)) {
            Text(
                stringResource(R.string.stored_map_unavailable),
                Modifier.testTag("stored_location_map_unavailable"),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else if (reviewRoad?.road?.nodes?.any { it.latitude !in -85.0511287798066..85.0511287798066 } == true) {
            Text(
                stringResource(R.string.stored_map_road_unavailable),
                Modifier.testTag("stored_location_map_road_unavailable"),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            Text(
                stringResource(if (reviewRoad == null) R.string.stored_map_scope else R.string.stored_map_review_road_scope),
                Modifier.fillMaxWidth().testTag(
                    if (reviewRoad ==
                        null
                    ) {
                        "stored_location_map_scope"
                    } else {
                        "stored_location_map_review_road_scope"
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.stored_map_uncertain),
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(HanGilDimensions.mapPreviewHeight)
                    .testTag(if (load.value == MapLoadState.READY) "stored_location_map_ready" else "stored_location_map_container"),
            ) {
                if (load.value == MapLoadState.FAILED) {
                    Column(Modifier.testTag("stored_location_map_failed"), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
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
                        ) { Text(stringResource(R.string.map_retry), style = MaterialTheme.typography.titleMedium) }
                    }
                } else {
                    key(sample, reviewRoad, owner, dark, fontScale, attempt) {
                        NativeStoredLocationMap(
                            sample,
                            reviewRoad,
                            dark,
                            fontScale,
                            pointFill,
                            pointBorder,
                            roadColor,
                            permitted,
                            inputChanges = inputChanges,
                            canInteract = { permitted() && load.value == MapLoadState.READY },
                            onRendered = { if (permitted() && load.value == MapLoadState.LOADING) load.value = MapLoadState.READY },
                            onFailed = { if (permitted()) load.value = MapLoadState.FAILED },
                            modifier = Modifier.matchParentSize().testTag("stored_location_map_view"),
                        )
                    }
                    if (load.value == MapLoadState.LOADING) {
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
            if (reviewRoad != null) {
                Text(
                    stringResource(R.string.map_road_attribution),
                    Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.map_sources),
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LaunchedEffect(load, load.value) {
                if (load.value == MapLoadState.LOADING) {
                    delay(20_000)
                    if (permitted() && load.value == MapLoadState.LOADING) load.value = MapLoadState.FAILED
                }
            }
        }
    }
}

@Composable
private fun NativeStoredLocationMap(
    sample: LocationUpdateSample,
    reviewRoad: SessionReviewRoad?,
    dark: Boolean,
    fontScale: Float,
    pointFill: Int,
    pointBorder: Int,
    roadColor: Int,
    canRender: () -> Boolean,
    inputChanges: Flow<Unit>,
    canInteract: () -> Boolean,
    onRendered: () -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val active = remember(sample, reviewRoad, owner) { AtomicBoolean(true) }
    val savedState = remember(sample, reviewRoad, owner) { MapViewSavedState() }
    val result =
        remember(context, sample, reviewRoad, owner) {
            createStoredMap(
                context,
                sample,
                reviewRoad,
                dark,
                fontScale,
                pointFill,
                pointBorder,
                roadColor,
                savedState,
                active,
                current = { active.get() && canRender() },
                canInteract = { active.get() && canInteract() },
                onRendered = { if (active.get() && canRender()) onRendered() },
                onFailed = { if (active.get() && canRender()) onFailed() },
            )
        }
    LaunchedEffect(result) { if (result.isFailure && canRender()) onFailed() }
    val view = result.getOrNull() ?: return
    LaunchedEffect(view, owner, inputChanges) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            inputChanges.collect {
                withContext(Dispatchers.Main.immediate) {
                    if (active.get() && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.recheckInput()
                }
            }
        }
    }
    DisposableEffect(view, owner) {
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
            owner.lifecycle.removeObserver(observer)
            context.applicationContext.unregisterComponentCallbacks(memory)
            observer.destroy()
        }
    }
    val description =
        if (reviewRoad == null) {
            stringResource(R.string.stored_map_content_description)
        } else {
            stringResource(R.string.stored_map_review_road_content_description, reviewRoad.road.title)
        }
    AndroidView(
        factory = { view },
        modifier = modifier,
        update = {
            it.contentDescription = description
            it.recheckInput()
        },
    )
}

private fun createStoredMap(
    context: Context,
    sample: LocationUpdateSample,
    reviewRoad: SessionReviewRoad?,
    dark: Boolean,
    fontScale: Float,
    pointFill: Int,
    pointBorder: Int,
    roadColor: Int,
    savedState: MapViewSavedState,
    active: AtomicBoolean,
    current: () -> Boolean,
    canInteract: () -> Boolean,
    onRendered: () -> Unit,
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
        val view = ParkedMapView(context, options, canInteract, current)
        created = view
        val styled = AtomicBoolean(false)
        val cameraFitted = AtomicBoolean(false)
        val fail = { if (current()) onFailed() }
        view.addOnDidFailLoadingMapListener { fail() }
        view.addOnRenderErrorListener { fail() }
        view.addOnShaderCompileFailedListener { _, _, _ -> fail() }
        view.addOnGlyphsErrorListener { _, _, _ -> fail() }
        view.addOnSpriteErrorListener { _, _ -> fail() }
        view.addOnTileActionListener { operation, _, _, _, _, _, _ -> if (operation == TileOperation.Error) fail() }
        view.addOnDidFinishRenderingMapListener { fully ->
            if (current() && styled.get() && cameraFitted.get() && fully) onRendered()
        }
        view.onCreate(savedState.bundle)
        savedState.attach(view)
        view.getMapAsync { map ->
            if (current()) {
                try {
                    view.bindMap(map)
                    val style = "https://tiles.openfreemap.org/styles/" + if (dark) "dark" else "liberty"
                    map.setStyle(style) { loaded ->
                        if (current()) {
                            try {
                                reviewRoad?.let {
                                    RoadPreviewLayers.add(loaded, listOf(it.road), roadColor)
                                    RoadPreviewLayers.select(loaded, it.road.id)
                                }
                                StoredLocationPoint.add(loaded, sample, pointFill, pointBorder, fontScale)
                                styled.set(true)
                                val coordinates =
                                    listOf(LatLng(sample.latitude, sample.longitude)) +
                                        reviewRoad
                                            ?.road
                                            ?.nodes
                                            .orEmpty()
                                            .map { LatLng(it.latitude, it.longitude) }
                                val sameCoordinate =
                                    coordinates.all {
                                        it.latitude == coordinates.first().latitude && it.longitude == coordinates.first().longitude
                                    }
                                val bounds =
                                    if (sameCoordinate) {
                                        null
                                    } else {
                                        LatLngBounds.Builder().includes(coordinates).build()
                                    }
                                val padding =
                                    (
                                        CoursePreviewLayers.viewportPaddingDp(
                                            fontScale,
                                        ) * context.resources.displayMetrics.density
                                    ).roundToInt()
                                var layout: View.OnLayoutChangeListener? = null
                                val fitCamera = {
                                    if (current() && !cameraFitted.get() && view.width > padding * 2 && view.height > padding * 2) {
                                        try {
                                            val camera =
                                                if (sameCoordinate) {
                                                    CameraUpdateFactory.newLatLngZoom(coordinates.first(), 15.0)
                                                } else {
                                                    CameraUpdateFactory.newLatLngBounds(checkNotNull(bounds), padding)
                                                }
                                            map.moveCamera(camera)
                                            cameraFitted.set(true)
                                            layout?.let(view::removeOnLayoutChangeListener)
                                        } catch (_: Exception) {
                                            fail()
                                        }
                                    }
                                }
                                layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitCamera() }
                                view.addOnLayoutChangeListener(checkNotNull(layout))
                                fitCamera()
                            } catch (_: Exception) {
                                fail()
                            } catch (_: LinkageError) {
                                fail()
                            }
                        }
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
