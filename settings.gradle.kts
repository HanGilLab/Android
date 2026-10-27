pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven("https://nexus-playground.pleos.ai/repository/maven-releases/")
            }
            filter { includeGroup("ai.pleos.playground") }
        }
    }
}

rootProject.name = "HanGil"
include(":app", ":core:domain", ":core:designsystem", ":core:database", ":core:vehicle", ":feature:home", ":feature:course")
