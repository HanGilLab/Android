package io.github.hangillab.hangil.core.domain

sealed interface Preparation {
    data object Empty : Preparation

    data object Outdated : Preparation

    data class Ready(
        val practice: PreparedPractice,
    ) : Preparation
}
