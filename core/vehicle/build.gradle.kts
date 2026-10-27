plugins {
    id("hangil.android.library")
    id("hangil.android.hilt")
}

android {
    namespace = "io.github.hangillab.hangil.core.vehicle"
    useLibrary("android.car")
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.coroutines.core)
    implementation(libs.pleos.vehicle)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
