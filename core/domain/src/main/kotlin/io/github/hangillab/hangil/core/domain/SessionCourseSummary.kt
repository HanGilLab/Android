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

    /** The validated records and course totals belong to one repository emission. */
    class Snapshot private constructor(
        val finished: List<PracticeSession>,
        private val positions: Map<String, Int>,
    ) {
        private val totalsByCourse = mutableMapOf<CourseContext, Totals>()

        fun indexOf(sessionId: String?): Int = sessionId?.let(positions::get) ?: -1

        fun summaryAt(index: Int): SessionCourseSummary {
            val source = finished[index]
            val course = CourseContext.from(source)
            val totals = synchronized(totalsByCourse) { totalsByCourse.getOrPut(course) { summarize(course, finished) } }
            return SessionCourseSummary(source, totals.sessionCount, totals.tasks)
        }

        companion object {
            /** Invalid or ambiguous records never become an empty or partially repaired summary. */
            fun capture(records: List<PracticeSession>): Snapshot? {
                val positions = mutableMapOf<String, Int>()
                val finished =
                    buildList {
                        for (record in records.toList()) {
                            val position = if (record.isActive) -1 else size
                            if (!valid(record) || positions.put(record.sessionId, position) != null) return null
                            if (!record.isActive) add(record)
                        }
                    }
                return Snapshot(finished, positions)
            }
        }
    }

    private data class CourseContext(
        val goalId: String,
        val courseId: String,
        val courseVersion: Int,
        val briefingVersion: Int,
        val goalTitle: String,
        val courseTitle: String,
        val reviewStatus: CourseReviewStatus,
    ) {
        fun matches(record: PracticeSession): Boolean =
            record.selection.goalId == goalId &&
                record.selection.courseId == courseId &&
                record.selection.courseVersion == courseVersion &&
                record.selection.briefingVersion == briefingVersion &&
                record.goalTitle == goalTitle &&
                record.courseTitle == courseTitle &&
                record.courseReviewStatus == reviewStatus

        companion object {
            fun from(session: PracticeSession): CourseContext =
                CourseContext(
                    session.selection.goalId,
                    session.selection.courseId,
                    session.selection.courseVersion,
                    session.selection.briefingVersion,
                    session.goalTitle,
                    session.courseTitle,
                    session.courseReviewStatus,
                )
        }
    }

    private data class Totals(
        val sessionCount: Int,
        val tasks: List<SessionTaskCount>,
    )

    companion object {
        fun from(
            source: PracticeSession,
            records: List<PracticeSession>,
        ): SessionCourseSummary? {
            val snapshot = records.toList()
            val identities = mutableSetOf<String>()
            if (snapshot.any { !valid(it) || !identities.add(it.sessionId) }) return null
            if (source.isActive || snapshot.singleOrNull { it.sessionId == source.sessionId } != source) return null

            val totals = summarize(CourseContext.from(source), snapshot)
            return SessionCourseSummary(source, totals.sessionCount, totals.tasks)
        }

        private fun summarize(
            course: CourseContext,
            records: List<PracticeSession>,
        ): Totals {
            val counts = linkedMapOf<Pair<String, String>, Int>()
            var sessionCount = 0
            for (record in records) {
                if (record.isActive || !course.matches(record)) continue
                val key = record.selection.taskId to record.taskTitle
                counts[key] = (counts[key] ?: 0) + 1
                sessionCount++
            }
            val tasks =
                buildList {
                    for ((key, count) in counts) add(SessionTaskCount(key.first, key.second, count))
                }
            return Totals(sessionCount, tasks)
        }

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
