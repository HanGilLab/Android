plugins {
    id("hangil.android.library")
    id("hangil.android.compose")
}

android { namespace = "io.github.hangillab.hangil.core.designsystem" }

dependencies {
    implementation(libs.lifecycle.runtime.compose)
    testImplementation(libs.junit)
}
