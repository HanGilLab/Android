plugins {
    id("hangil.android.library")
    id("hangil.android.hilt")
    alias(libs.plugins.room)
}

android {
    namespace = "io.github.hangillab.hangil.core.database"
    testOptions.unitTests.isIncludeAndroidResources = true
    sourceSets.getByName("test").resources.srcDir("schemas")
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
}
