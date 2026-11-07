package io.github.hangillab.hangil.feature.map

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
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
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.RenderingEngine
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.tile.TileOperation
import java.util.concurrent.atomic.AtomicBoolean

/** The caller creates this component only for an explicitly expanded, source-bound stored row. */
@Composable
fun StoredLocationMap(
    sample: LocationUpdateSample,
    canRead: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val owner = LocalLifecycleOwner.current
    val receipt = remember(sample, owner) { AtomicBoolean(true) }
    val capturedGuard = remember(sample, owner) { canRead }
    val permitted: () -> Boolean =
        remember(sample, owner) {
            {
                val allowed =
                    receipt.get() && owner.lifecycle.currentState == Lifecycle.State.RESUMED &&
                        try {
                            capturedGuard()
                        } catch (_: Exception) {
                            false
                        }
                val current = allowed && receipt.get() && owner.lifecycle.currentState == Lifecycle.State.RESUMED
                if (!current) receipt.set(false)
                current
            }
        }
    DisposableEffect(owner, receipt) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                    receipt.set(false)
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
    var attempt by remember(sample, owner) { mutableIntStateOf(0) }
    val capturedAttempt = attempt
    val load = remember(sample, owner, dark, fontScale, attempt) { mutableStateOf(MapLoadState.LOADING) }
    val retry: () -> Unit = {
        if (attempt == capturedAttempt && load.value == MapLoadState.FAILED && permitted()) attempt++
    }
    val pointFill = MaterialTheme.colorScheme.primary.toArgb()
    val pointBorder = MaterialTheme.colorScheme.surface.toArgb()
    Column(modifier.fillMaxWidth().testTag("stored_location_map"), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
        if (!permitted()) {
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
        } else {
            Text(stringResource(R.string.stored_map_scope), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
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
                    key(sample, owner, dark, fontScale, attempt) {
                        NativeStoredLocationMap(
                            sample,
                            dark,
                            fontScale,
                            pointFill,
                            pointBorder,
                            permitted,
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
    dark: Boolean,
    fontScale: Float,
    pointFill: Int,
    pointBorder: Int,
    canRender: () -> Boolean,
    canInteract: () -> Boolean,
    onRendered: () -> Unit,
    onFailed: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val active = remember { AtomicBoolean(true) }
    val savedState = remember { MapViewSavedState() }
    val result =
        remember(context, owner) {
            createStoredMap(
                context,
                sample,
                dark,
                fontScale,
                pointFill,
                pointBorder,
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
    val description = stringResource(R.string.stored_map_content_description)
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
    dark: Boolean,
    fontScale: Float,
    pointFill: Int,
    pointBorder: Int,
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
                                StoredLocationPoint.add(loaded, sample, pointFill, pointBorder, fontScale)
                                styled.set(true)
                                map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(sample.latitude, sample.longitude), 15.0))
                                cameraFitted.set(true)
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
