package io.github.hangillab.hangil.core.domain

data class PracticeSelection(
    val goalId: String,
    val courseId: String,
    val courseVersion: Int,
    val briefingVersion: Int,
    val taskId: String,
)
