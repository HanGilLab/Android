plugins {
    `kotlin-dsl`
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.composePlugin)
    implementation(libs.hilt.gradlePlugin)
    implementation(libs.ksp.gradlePlugin)
    testImplementation(libs.junit)
}

gradlePlugin {
    plugins {
        register("moduleBoundaries") {
            id = "hangil.module-boundaries"
            implementationClass = "ModuleBoundariesPlugin"
        }
    }
}
