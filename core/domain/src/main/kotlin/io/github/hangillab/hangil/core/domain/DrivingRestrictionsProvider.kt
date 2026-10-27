package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.flow.StateFlow

interface DrivingRestrictionsProvider {
    val restrictions: StateFlow<DrivingRestrictions>
}
