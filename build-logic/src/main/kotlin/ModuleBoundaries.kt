

internal object ModuleBoundaries {
    private val allowed =
        mapOf(
            ":app" to setOf(":feature:home", ":feature:course", ":core:domain", ":core:designsystem", ":core:database", ":core:vehicle"),
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
