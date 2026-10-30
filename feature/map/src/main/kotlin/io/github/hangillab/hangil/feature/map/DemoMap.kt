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
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.hangillab.hangil.core.domain.CourseMapPreview
import org.maplibre.android.MapLibre
import org.maplibre.android.RenderingEngine
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.tile.TileOperation
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

@Composable
internal fun DemoMap(
    attempt: Int,
    preview: CourseMapPreview,
    dark: Boolean,
    fontScale: Float,
    pointFill: Int,
    pointText: Int,
    pointBorder: Int,
    contentDescription: String,
    onLoading: (Int) -> Long?,
    onRendered: (Int, Long) -> Unit,
    onFailed: (Int, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val savedState = rememberSaveable(saver = MapViewSavedState.Saver) { MapViewSavedState() }
    val token = remember(attempt) { onLoading(attempt) } ?: return
    val active = remember(attempt) { AtomicBoolean(true) }
    val result =
        remember(context, attempt) {
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
                onRendered = { onRendered(attempt, token) },
                onFailed = { onFailed(attempt, token) },
            )
        }
    val view = result.getOrNull()
    LaunchedEffect(result) { if (result.isFailure) onFailed(attempt, token) }
    if (view == null) return
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
    AndroidView(factory = { view }, modifier = modifier, update = { it.contentDescription = contentDescription })
}

private fun createMap(
    context: Context,
    savedState: MapViewSavedState,
    active: AtomicBoolean,
    preview: CourseMapPreview,
    dark: Boolean,
    fontScale: Float,
    pointFill: Int,
    pointText: Int,
    pointBorder: Int,
    onRendered: () -> Unit,
    onFailed: () -> Unit,
): Result<MapView> {
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
        val mapView = MapView(context, options)
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
                    map.uiSettings.isAttributionEnabled = true
                    val styleUrl = "https://tiles.openfreemap.org/styles/" + if (dark) "dark" else "liberty"
                    map.setStyle(styleUrl) { style ->
                        if (active.get()) {
                            try {
                                CoursePreviewLayers.add(style, preview, pointFill, pointText, pointBorder, fontScale)
                                styleLoaded.set(true)
                                val bounds =
                                    LatLngBounds
                                        .Builder()
                                        .includes(
                                            preview.points.map { LatLng(it.latitude, it.longitude) },
                                        ).build()
                                val padding = (40 * context.resources.displayMetrics.density).roundToInt()
                                var layout: View.OnLayoutChangeListener? = null
                                val fitCamera = {
                                    if (active.get() && !cameraFitted.get() && mapView.width > padding * 2 &&
                                        mapView.height > padding * 2
                                    ) {
                                        try {
                                            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
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
