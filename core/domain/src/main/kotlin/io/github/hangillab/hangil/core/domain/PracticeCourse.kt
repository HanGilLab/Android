package io.github.hangillab.hangil.core.domain

data class PracticeCourse(
    val id: String,
    val version: Int,
    val briefingVersion: Int,
    val title: String,
    val summary: String,
    val sections: List<String>,
    val tasks: List<PracticeTask>,
    val goalIds: Set<String>,
    val reviewStatus: CourseReviewStatus = CourseReviewStatus.DEMO,
)
