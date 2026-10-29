plugins {
    id("hangil.android.library")
    id("hangil.android.hilt")
}

android {
    namespace = "io.github.hangillab.hangil.core.vehicle"
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
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
    androidTestImplementation(libs.pleos.naviHelper)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
