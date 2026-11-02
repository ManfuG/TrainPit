plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.manfug.trainpit"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.manfug.trainpit"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:sync"))
}
