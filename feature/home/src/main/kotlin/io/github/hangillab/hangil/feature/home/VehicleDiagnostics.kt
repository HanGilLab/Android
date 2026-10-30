package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.TurnSignalState
import io.github.hangillab.hangil.core.domain.VehicleReading

@Composable
internal fun connectionLabel(state: ConnectionState): String =
    stringResource(
        when (state) {
            ConnectionState.DISCONNECTED -> R.string.connection_disconnected
            ConnectionState.CONNECTING -> R.string.connection_connecting
            ConnectionState.CONNECTED -> R.string.connection_connected
            ConnectionState.UNAVAILABLE -> R.string.connection_unavailable
            ConnectionState.PERMISSION_DENIED -> R.string.connection_permission_denied
            ConnectionState.ERROR -> R.string.connection_error
        },
    )

@Composable
internal fun VehicleDiagnostics(state: HomeUiState) {
    Card(modifier = Modifier.widthIn(max = HanGilDimensions.diagnosticsMaxWidth)) {
        Column(Modifier.padding(HanGilSpacing.xl), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.xs)) {
            Text(stringResource(R.string.vehicle_diagnostics), style = MaterialTheme.typography.titleMedium)
            Text("${stringResource(R.string.connection_vehicle)}: ${connectionLabel(state.vehicle.connection)}")
            Text("${stringResource(R.string.connection_navigation)}: ${connectionLabel(state.navigationConnection)}")
            Text(
                stringResource(
                    R.string.vehicle_gear,
                    readingLabel(state.vehicle.gear) {
                        when (it) {
                            GearPosition.PARK -> "P"
                            GearPosition.REVERSE -> "R"
                            GearPosition.NEUTRAL -> "N"
                            GearPosition.DRIVE -> "D"
                            GearPosition.OTHER -> stringResource(R.string.gear_other)
                        }
                    },
                ),
            )
            Text(
                stringResource(
                    R.string.vehicle_turn_signal,
                    readingLabel(state.vehicle.turnSignal) {
                        stringResource(
                            when (it) {
                                TurnSignalState.OFF -> R.string.turn_signal_off
                                TurnSignalState.LEFT -> R.string.turn_signal_left
                                TurnSignalState.RIGHT -> R.string.turn_signal_right
                            },
                        )
                    },
                ),
            )
            Text(
                stringResource(
                    R.string.vehicle_brake,
                    readingLabel(state.vehicle.brakePressed) {
                        stringResource(if (it) R.string.brake_pressed else R.string.brake_released)
                    },
                ),
            )
        }
    }
}

@Composable
internal fun <T : Any> readingLabel(
    reading: VehicleReading<T>,
    label: @Composable (T) -> String,
): String =
    when (reading) {
        is VehicleReading.Available -> label(reading.value)
        VehicleReading.Unknown -> stringResource(R.string.reading_unknown)
        VehicleReading.Unsupported -> stringResource(R.string.reading_unsupported)
        VehicleReading.PermissionDenied -> stringResource(R.string.connection_permission_denied)
        VehicleReading.Error -> stringResource(R.string.connection_error)
    }
