package io.github.hangillab.hangil.core.vehicle

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.hangillab.hangil.core.domain.LocationGateway
import io.github.hangillab.hangil.core.domain.LocationReading
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PleosLocationGateway
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : LocationGateway {
        private val reader = LocationReadOperation({ PleosLocationClient(context) }, Dispatchers.Main.immediate)

        override suspend fun readLastKnown(): LocationReading = reader.readLastKnown()
    }
