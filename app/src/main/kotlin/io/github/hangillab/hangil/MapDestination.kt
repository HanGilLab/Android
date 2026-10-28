package io.github.hangillab.hangil

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.hangillab.hangil.core.vehicle.FusedLocationPermission
import io.github.hangillab.hangil.feature.map.LocationPermissionResult
import io.github.hangillab.hangil.feature.map.MapRoute

@Composable
internal fun MapDestination(
    onClose: () -> Unit,
    showDiagnostics: Boolean,
) {
    var completion by remember { mutableStateOf<((LocationPermissionResult) -> Unit)?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val callback = completion
            completion = null
            callback?.invoke(if (granted) LocationPermissionResult.GRANTED else LocationPermissionResult.DENIED)
        }
    DisposableEffect(Unit) { onDispose { completion = null } }
    MapRoute(
        onClose = onClose,
        showDiagnostics = showDiagnostics,
        onRequestLocationPermission = { callback ->
            if (completion == null) {
                completion = callback
                try {
                    launcher.launch(FusedLocationPermission.NAME)
                } catch (_: RuntimeException) {
                    completion = null
                    callback(LocationPermissionResult.FAILED)
                }
            }
        },
    )
}
