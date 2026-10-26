plugins {
    id("hangil.android.library")
    id("hangil.android.compose")
    id("hangil.android.hilt")
}

android { namespace = "io.github.hangillab.hangil.feature.home" }

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:designsystem"))
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
