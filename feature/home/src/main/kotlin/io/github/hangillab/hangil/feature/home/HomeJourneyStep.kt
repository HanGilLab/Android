package io.github.hangillab.hangil.feature.home

/** Guidance for the next available action, not proof of driving or review completion. */
enum class HomeJourneyStep {
    LOCKED,
    LOADING,
    RECOVER,
    PREPARE,
    READY,
    ACTIVE,
    REPEAT,
}
