plugins {
    id("hangil.android.library")
    id("hangil.android.hilt")
}

android { namespace = "io.github.hangillab.hangil.core.vehicle" }

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
}
