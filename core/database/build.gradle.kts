plugins {
    id("hangil.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android { namespace = "io.github.hangillab.hangil.core.database" }

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
}
