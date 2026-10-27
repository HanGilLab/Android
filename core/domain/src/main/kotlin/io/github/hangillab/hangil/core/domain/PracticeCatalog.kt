package io.github.hangillab.hangil.core.domain

interface PracticeCatalog {
    val goals: List<PracticeGoal>
    val courses: List<PracticeCourse>

    fun resolve(selection: PracticeSelection?): Preparation {
        if (selection == null) return Preparation.Empty
        val goal = goals.find { it.id == selection.goalId } ?: return Preparation.Outdated
        val course = courses.find { it.id == selection.courseId } ?: return Preparation.Outdated
        val task = course.tasks.find { it.id == selection.taskId } ?: return Preparation.Outdated
        if (goal.id !in course.goalIds || course.version != selection.courseVersion ||
            course.briefingVersion != selection.briefingVersion
        ) {
            return Preparation.Outdated
        }
        return Preparation.Ready(PreparedPractice(goal, course, task))
    }
}
