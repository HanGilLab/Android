package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun LocationSampleRawFields(sample: LocationUpdateSample) {
    Text(
        stringResource(R.string.location_history_raw_warning),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        stringResource(R.string.location_history_latitude, sample.latitude.toString()),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        stringResource(R.string.location_history_longitude, sample.longitude.toString()),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        stringResource(R.string.location_history_altitude, sample.altitudeRaw.toString()),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        stringResource(R.string.location_history_heading, sample.headingRaw.toString()),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        stringResource(R.string.location_history_confidence, sample.confidenceRaw.toString()),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        stringResource(R.string.location_history_sdk_time, sample.sdkTimestampMillis.toString()),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
internal fun locationHistoryTime(epochMillis: Long): String {
    val fallback = stringResource(R.string.location_history_time_unknown)
    return runCatching {
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss"))
    }.getOrDefault(fallback)
}
