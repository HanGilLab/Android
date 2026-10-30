package io.github.hangillab.hangil.core.domain

/** Count of stored ended sessions with the same task ID and historical title, not task executions. */
data class SessionTaskCount(
    val taskId: String,
    val taskTitle: String,
    val sessionCount: Int,
) {
    init {
        require(taskId.isNotBlank()) { "Invalid summary task ID" }
        require(taskTitle.isNotBlank()) { "Invalid summary task title" }
        require(sessionCount > 0) { "Invalid summary session count" }
    }
}
