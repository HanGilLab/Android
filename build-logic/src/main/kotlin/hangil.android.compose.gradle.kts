import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

plugins.withId("com.android.application") {
    extensions.configure<ApplicationExtension> { buildFeatures.compose = true }
}
plugins.withId("com.android.library") {
    extensions.configure<LibraryExtension> { buildFeatures.compose = true }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
dependencies {
    add("implementation", platform(libs.findLibrary("compose-bom").get()))
    add("implementation", libs.findLibrary("compose-ui").get())
    add("implementation", libs.findLibrary("compose-material3").get())
    add("implementation", libs.findLibrary("compose-ui-tooling-preview").get())
    add("debugImplementation", libs.findLibrary("compose-ui-tooling").get())
}
