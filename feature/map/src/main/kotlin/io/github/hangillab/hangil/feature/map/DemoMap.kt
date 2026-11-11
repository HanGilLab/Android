package io.github.hangillab.hangil.feature.map

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.RenderingEngine
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.tile.TileOperation
import org.maplibre.geojson.LineString
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

@Composable
internal fun DemoMap(
    attempt: Int,
    preview: MapPreview,
    dark: Boolean,
    fontScale: Float,
    pointFill: Int,
    pointText: Int,
    pointBorder: Int,
    contentDescription: String,
    onLoading: (Int) -> Long?,
    canInteract: (Int, Long) -> Boolean,
    inputChanges: Flow<Unit>,
    onRendered: (Int, Long) -> Unit,
    onFailed: (Int, Long) -> Unit,
    selection: MapSelection?,
    canApplySelection: (Int, Long, Long) -> Boolean,
    onSelectionApplied: (Int, Long, Long) -> Unit,
    onNativeSelect: (String, () -> Boolean) -> Unit,
    modifier: Modifier = Modifier,
    canRender: () -> Boolean = { true },
    publicRoadSection: LineString? = null,
    publicRoadSectionColor: Int = pointFill,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val savedState = rememberSaveable(saver = MapViewSavedState.Saver) { MapViewSavedState() }
    val token = remember(attempt, owner) { onLoading(attempt) } ?: return
    val active = remember(attempt, owner) { AtomicBoolean(true) }
    val result =
        remember(context, attempt, token, owner) {
            createMap(
                context,
                savedState,
                active,
                preview,
                dark,
                fontScale,
                pointFill,
                pointText,
                pointBorder,
                publicRoadSection,
                publicRoadSectionColor,
                canInteract = {
                    active.get() && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                        canInteract(attempt, token)
                },
                canRender = canRender,
                onRendered = { onRendered(attempt, token) },
                onFailed = { onFailed(attempt, token) },
            )
        }
    val selectionApplier = remember(result) { MapSelectionApplier() }
    val view = result.getOrNull()
    LaunchedEffect(result) { if (result.isFailure) onFailed(attempt, token) }
    if (view == null) return
    LaunchedEffect(view, owner) {
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
            it.setPointTapAction { map, x, y, nativeOwner ->
                fun current(): Boolean =
                    nativeOwner() && active.get() && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                        canInteract(attempt, token)
                if (current()) {
                    try {
                        val pointId = MapPointHitTest.find(map, preview, x, y)
                        if (pointId != null && current()) onNativeSelect(pointId, nativeOwner)
                    } catch (_: Exception) {
                        if (current()) onFailed(attempt, token)
                    } catch (_: LinkageError) {
                        if (current()) onFailed(attempt, token)
                    }
                }
            }
            it.recheckInput()
            selectionApplier.apply(
                it,
                selection,
                canApply = { nonce ->
                    active.get() && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
                        canApplySelection(attempt, token, nonce)
                },
                onApplied = { nonce -> onSelectionApplied(attempt, token, nonce) },
                onFailed = { onFailed(attempt, token) },
            )
        },
    )
}

private fun createMap(
    context: Context,
    savedState: MapViewSavedState,
    active: AtomicBoolean,
    preview: MapPreview,
    dark: Boolean,
    fontScale: Float,
    pointFill: Int,
    pointText: Int,
    pointBorder: Int,
    publicRoadSection: LineString?,
    publicRoadSectionColor: Int,
    canInteract: () -> Boolean,
    canRender: () -> Boolean,
    onRendered: () -> Unit,
    onFailed: () -> Unit,
): Result<ParkedMapView> {
    var view: MapView? = null
    return try {
        MapLibre.getInstance(context.applicationContext, null, WellKnownTileServer.MapLibre, RenderingEngine.Type.OPENGL)
        val styleLoaded = AtomicBoolean(false)
        val cameraFitted = AtomicBoolean(false)
        val fail = { if (active.get()) onFailed() }
        val options =
            MapLibreMapOptions
                .createFromAttributes(context)
                .textureMode(true)
                .attributionEnabled(true)
                .localIdeographFontFamilyEnabled(true)
                .localIdeographFontFamily("sans-serif")
        val mapView = ParkedMapView(context, options, canInteract, canRender)
        view = mapView
        mapView.addOnDidFailLoadingMapListener { fail() }
        mapView.addOnRenderErrorListener { fail() }
        mapView.addOnShaderCompileFailedListener { _, _, _ -> fail() }
        mapView.addOnGlyphsErrorListener { _, _, _ -> fail() }
        mapView.addOnSpriteErrorListener { _, _ -> fail() }
        mapView.addOnTileActionListener { operation, _, _, _, _, _, _ -> if (operation == TileOperation.Error) fail() }
        mapView.addOnDidFinishRenderingMapListener { fully ->
            if (active.get() && styleLoaded.get() && cameraFitted.get() && fully) onRendered()
        }
        mapView.onCreate(savedState.bundle?.let(::Bundle))
        savedState.attach(mapView)
        mapView.getMapAsync { map ->
            if (active.get()) {
                try {
                    mapView.bindMap(map)
                    map.uiSettings.isAttributionEnabled = true
                    val styleUrl = "https://tiles.openfreemap.org/styles/" + if (dark) "dark" else "liberty"
                    map.setStyle(styleUrl) { style ->
                        if (active.get()) {
                            try {
                                RoadPreviewLayers.add(style, preview.roads, pointFill)
                                publicRoadSection?.let {
                                    StoredLocationRoadSection.addPublic(
                                        style,
                                        it,
                                        publicRoadSectionColor,
                                        candidate = false,
                                    )
                                }
                                CoursePreviewLayers.add(style, preview, pointFill, pointText, pointBorder, fontScale)
                                styleLoaded.set(true)
                                val coordinates =
                                    preview.points.map { LatLng(it.latitude, it.longitude) } +
                                        preview.roads.flatMap { road -> road.nodes.map { LatLng(it.latitude, it.longitude) } }
                                val sameCoordinate =
                                    coordinates.all {
                                        it.latitude == coordinates.first().latitude && it.longitude == coordinates.first().longitude
                                    }
                                val bounds =
                                    if (sameCoordinate) {
                                        null
                                    } else {
                                        LatLngBounds
                                            .Builder()
                                            .includes(coordinates)
                                            .build()
                                    }
                                val density = context.resources.displayMetrics.density
                                val padding = (CoursePreviewLayers.viewportPaddingDp(fontScale) * density).roundToInt()
                                var layout: View.OnLayoutChangeListener? = null
                                val fitCamera = {
                                    if (active.get() && !cameraFitted.get() && mapView.width > padding * 2 &&
                                        mapView.height > padding * 2
                                    ) {
                                        try {
                                            val camera =
                                                if (sameCoordinate) {
                                                    CameraUpdateFactory.newLatLngZoom(coordinates.first(), 14.0)
                                                } else {
                                                    CameraUpdateFactory.newLatLngBounds(checkNotNull(bounds), padding)
                                                }
                                            map.moveCamera(camera)
                                            cameraFitted.set(true)
                                            layout?.let(mapView::removeOnLayoutChangeListener)
                                        } catch (_: Exception) {
                                            fail()
                                        }
                                    }
                                }
                                layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitCamera() }
                                mapView.addOnLayoutChangeListener(checkNotNull(layout))
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
        Result.success(mapView)
    } catch (failure: Exception) {
        active.set(false)
        view?.let {
            savedState.detach(it)
            it.onDestroy()
        }
        Result.failure(failure)
    } catch (failure: LinkageError) {
        active.set(false)
        view?.let {
            savedState.detach(it)
            it.onDestroy()
        }
        Result.failure(failure)
    }
}
