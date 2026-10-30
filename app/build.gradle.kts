plugins {
    id("hangil.android.application")
    id("hangil.android.compose")
    id("hangil.android.hilt")
}

android {
    namespace = "io.github.hangillab.hangil"
    defaultConfig {
        applicationId = "io.github.hangillab.hangil"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode =
            providers
                .gradleProperty("releaseVersionCode")
                .orElse("1")
                .get()
                .toInt()
        versionName = providers.gradleProperty("releaseVersion").orElse("0.1.0").get()
    }

    val signingVariables =
        listOf("HANGIL_KEYSTORE_PATH", "HANGIL_KEYSTORE_PASSWORD", "HANGIL_KEY_ALIAS", "HANGIL_KEY_PASSWORD")
    val hasReleaseSigning = signingVariables.all { !providers.environmentVariable(it).orNull.isNullOrBlank() }
    if (hasReleaseSigning) {
        signingConfigs.create("release") {
            storeFile = file(providers.environmentVariable("HANGIL_KEYSTORE_PATH").get())
            storePassword = providers.environmentVariable("HANGIL_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("HANGIL_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("HANGIL_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(project(":feature:home"))
    implementation(project(":feature:course"))
    implementation(project(":feature:map"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:domain"))
    implementation(project(":core:vehicle"))
    implementation(project(":core:database"))
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.coroutines.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(project(":core:domain"))
    androidTestImplementation(libs.coroutines.core)
    androidTestImplementation(libs.maplibre.android)
}
