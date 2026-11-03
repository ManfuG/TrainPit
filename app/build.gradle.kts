plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

android {
    namespace = "io.github.manfug.trainpit"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.manfug.trainpit"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "FIREBASE_EMULATORS", "false")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    val releaseStore = providers.environmentVariable("TRAINPIT_KEYSTORE").orNull
    signingConfigs {
        if (releaseStore != null) {
            create("distribution") {
                storeFile = file(releaseStore)
                storePassword = providers.environmentVariable("TRAINPIT_STORE_PASSWORD").get()
                keyAlias = "trainpit"
                keyPassword = providers.environmentVariable("TRAINPIT_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        debug {
            buildConfigField("boolean", "FIREBASE_EMULATORS", (providers.gradleProperty("firebaseEmulators").orNull == "true").toString())
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (releaseStore != null) signingConfig = signingConfigs.getByName("distribution")
        }
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
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.fragment)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.coroutines.android)
    implementation(libs.work.runtime)
    androidTestImplementation(libs.android.test.runner)
    androidTestImplementation(libs.android.test.junit)
    androidTestImplementation(libs.android.test.rules)
}
