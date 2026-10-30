package io.github.hangillab.hangil.core.domain

/**
 * Ended button records from one exact historical course context, in received repository order.
 * Counts do not establish actual task executions, course completion or driving ability.
 * No current catalog, clock sorting or vehicle observations are used.
 */
class SessionCourseSummary private constructor(
    val source: PracticeSession,
    val sessionCount: Int,
    val tasks: List<SessionTaskCount>,
) {
    override fun equals(other: Any?): Boolean =
        other is SessionCourseSummary && source == other.source && sessionCount == other.sessionCount && tasks == other.tasks

    override fun hashCode(): Int = 31 * (31 * source.hashCode() + sessionCount) + tasks.hashCode()

    companion object {
        /** Invalid or ambiguous records never become an empty or partially repaired summary. */
        fun from(
            source: PracticeSession,
            records: List<PracticeSession>,
        ): SessionCourseSummary? {
            val snapshot = records.toList()
            val identities = mutableSetOf<String>()
            if (snapshot.any { !valid(it) || !identities.add(it.sessionId) }) return null
            if (source.isActive || snapshot.singleOrNull { it.sessionId == source.sessionId } != source) return null

            val counts = linkedMapOf<Pair<String, String>, Int>()
            var sessionCount = 0
            for (record in snapshot) {
                if (record.isActive || !sameCourse(record, source)) continue
                val key = record.selection.taskId to record.taskTitle
                counts[key] = (counts[key] ?: 0) + 1
                sessionCount++
            }
            val tasks =
                buildList {
                    for ((key, count) in counts) add(SessionTaskCount(key.first, key.second, count))
                }
            return SessionCourseSummary(source, sessionCount, tasks)
        }

        private fun sameCourse(
            record: PracticeSession,
            source: PracticeSession,
        ): Boolean =
            record.selection.goalId == source.selection.goalId &&
                record.selection.courseId == source.selection.courseId &&
                record.selection.courseVersion == source.selection.courseVersion &&
                record.selection.briefingVersion == source.selection.briefingVersion &&
                record.goalTitle == source.goalTitle &&
                record.courseTitle == source.courseTitle &&
                record.courseReviewStatus == source.courseReviewStatus

        private fun valid(record: PracticeSession): Boolean {
            val selection = record.selection
            val endedAt = record.endedAtEpochMillis
            return record.sessionId.isNotBlank() && selection.goalId.isNotBlank() &&
                selection.courseId.isNotBlank() && selection.taskId.isNotBlank() &&
                selection.courseVersion > 0 && selection.briefingVersion > 0 && record.goalTitle.isNotBlank() &&
                record.courseTitle.isNotBlank() && record.taskTitle.isNotBlank() && record.startedAtEpochMillis > 0 &&
                (endedAt == null || endedAt >= record.startedAtEpochMillis)
        }
    }
}
