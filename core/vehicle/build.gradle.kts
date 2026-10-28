plugins {
    id("hangil.android.library")
    id("hangil.android.hilt")
}

android {
    namespace = "io.github.hangillab.hangil.core.vehicle"
    useLibrary("android.car")
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.coroutines.core)
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.vehicleData)
    implementation(libs.pleos.fusedLocation)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}
