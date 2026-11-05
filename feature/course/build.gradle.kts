plugins {
    id("hangil.android.library")
    id("hangil.android.compose")
    id("hangil.android.hilt")
}

android {
    namespace = "io.github.hangillab.hangil.feature.course"
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:designsystem"))
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.viewmodel.savedstate)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.coroutines.test)
}
