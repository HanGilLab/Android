package io.github.hangillab.hangil.feature.home

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock

@Module
@InstallIn(SingletonComponent::class)
internal object SessionClockModule {
    @Provides fun clock(): Clock = Clock.systemUTC()
}
