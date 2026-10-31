package io.github.hangillab.hangil.core.vehicle

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.hangillab.hangil.core.domain.NavigationGateway
import io.github.hangillab.hangil.core.domain.NavigationScreenResult
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PleosNavigationGateway
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : NavigationGateway {
        private val operation = NavigationScreenOperation({ PleosNavigationClient(context) }, Dispatchers.Main.immediate)
        override val connection = operation.connection

        override suspend fun requestForeground(canDispatch: () -> Boolean): NavigationScreenResult = operation.request(canDispatch)
    }
