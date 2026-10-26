package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilTheme
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.UxRestrictionState

@Composable
fun HomeRoute(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(state)
}

@Composable
internal fun HomeScreen(state: HomeUiState) {
    Scaffold { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.home_intro), modifier = Modifier.widthIn(max = 720.dp))
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.widthIn(max = 720.dp)) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${stringResource(R.string.connection_vehicle)} · ${connectionLabel(state.vehicleConnection)}")
                    Text("${stringResource(R.string.connection_navigation)} · ${connectionLabel(state.navigationConnection)}")
                    val restrictionMessage =
                        when {
                            state.restrictions.allowsDetailedInteraction -> R.string.restriction_allowed
                            state.restrictions.parkingState == ParkingState.UNKNOWN ||
                                state.restrictions.uxRestrictionState == UxRestrictionState.UNKNOWN -> R.string.restriction_unknown
                            else -> R.string.restriction_locked
                        }
                    Text(stringResource(restrictionMessage))
                }
            }
            Text(stringResource(R.string.bootstrap_notice), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun connectionLabel(state: ConnectionState): String =
    stringResource(
        when (state) {
            ConnectionState.DISCONNECTED -> R.string.connection_disconnected
            ConnectionState.CONNECTING -> R.string.connection_connecting
            ConnectionState.CONNECTED -> R.string.connection_connected
            ConnectionState.UNAVAILABLE -> R.string.connection_unavailable
        },
    )

@Preview(widthDp = 1024, heightDp = 600, showBackground = true)
@Composable
private fun HomePreview() {
    HanGilTheme { HomeScreen(HomeUiState()) }
}
