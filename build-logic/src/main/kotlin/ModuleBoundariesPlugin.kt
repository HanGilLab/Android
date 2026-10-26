import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

internal object ModuleBoundaries {
    private val allowed =
        mapOf(
            ":app" to setOf(":feature:home", ":core:domain", ":core:designsystem", ":core:database", ":core:vehicle"),
            ":core:domain" to emptySet(),
            ":core:designsystem" to emptySet(),
            ":core:database" to setOf(":core:domain"),
            ":core:vehicle" to setOf(":core:domain"),
        )

    fun violations(graph: Map<String, Set<String>>): List<String> =
        graph.flatMap { (module, dependencies) ->
            val permitted =
                if (module.startsWith(":feature:")) {
                    setOf(":core:domain", ":core:designsystem")
                } else {
                    allowed[module]
                }
            if (permitted == null) {
                listOf("$module: 새 모듈의 허용 의존성을 ModuleBoundaries에 선언하세요.")
            } else {
                (dependencies - permitted).sorted().map { "$module -> $it: 허용되지 않은 모듈 의존성" }
            }
        }
}

class ModuleBoundariesPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        require(target == target.rootProject) { "루트 프로젝트에만 적용하세요." }
        target.tasks.register("checkModuleBoundaries") {
            group = "verification"
            description = "feature 간 의존과 domain의 플랫폼 의존 등 모듈 경계 위반을 검사합니다."
            doLast {
                val graph =
                    target.subprojects.filter { it.buildFile.isFile }.associate { project ->
                        project.path to
                            project.configurations
                                .flatMap { it.dependencies.withType(ProjectDependency::class.java) }
                                .map { it.path }
                                .filter { it != project.path }
                                .toSet()
                    }
                val violations = ModuleBoundaries.violations(graph)
                if (violations.isNotEmpty()) throw GradleException(violations.joinToString("\n"))
                logger.lifecycle("모듈 경계 검사 통과 (${graph.size} modules)")
            }
        }
    }
}
