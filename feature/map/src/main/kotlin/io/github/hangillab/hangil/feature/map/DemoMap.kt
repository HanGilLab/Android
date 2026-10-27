package io.github.hangillab.hangil.feature.map

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.RenderingEngine
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.tile.TileOperation
import java.util.concurrent.atomic.AtomicBoolean

@Composable
internal fun DemoMap(
    attempt: Int,
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
                onRendered = { onRendered(attempt, token) },
                onFailed = { onFailed(attempt, token) },
            )
        }
    val view = result.getOrNull()
    LaunchedEffect(result) {
        if (result.isFailure) onFailed(attempt, token)
    }
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
    onRendered: () -> Unit,
    onFailed: () -> Unit,
): Result<MapView> {
    var view: MapView? = null
    return try {
        // Match the single-backend artifact explicitly; device Vulkan advertising is not a compatibility test.
        MapLibre.getInstance(context.applicationContext, null, WellKnownTileServer.MapLibre, RenderingEngine.Type.OPENGL)
        val styleLoaded = AtomicBoolean(false)
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
            if (active.get() && styleLoaded.get() && fully) onRendered()
        }
        mapView.onCreate(savedState.bundle?.let(::Bundle))
        savedState.attach(mapView)
        mapView.getMapAsync { map ->
            if (active.get()) {
                if (savedState.bundle?.isEmpty != false) {
                    map.cameraPosition =
                        CameraPosition
                            .Builder()
                            .target(LatLng(37.5665, 126.9780))
                            .zoom(14.0)
                            .build()
                }
                map.uiSettings.isAttributionEnabled = true
                map.setStyle("https://tiles.openfreemap.org/styles/liberty") { styleLoaded.set(true) }
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
