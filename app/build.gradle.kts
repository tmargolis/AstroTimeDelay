plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.toddmargolis.astroandroidapp"
    // Use the NDK 29 version you just downloaded
    ndkVersion = "29.0.14206865"

    compileSdk = 35 // SDK 36 is likely in "Preview" or very new; 35 is the stable Android 15 target

    defaultConfig {
        applicationId = "com.toddmargolis.astroandroidapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // REMOVE RenderScript - it is incompatible with 16 KB alignment
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        jniLibs {
            // This is required for 16 KB devices
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)

    // Sync all CameraX to 1.4.2+ for 16 KB support
    val camerax_version = "1.4.2"
    implementation("androidx.camera:camera-camera2:$camerax_version")
    implementation("androidx.camera:camera-lifecycle:$camerax_version")
    implementation("androidx.camera:camera-view:$camerax_version")
    implementation("androidx.camera:camera-core:$camerax_version")

    // ML Kit Object Detection
    implementation("com.google.mlkit:object-detection:17.0.2")

    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}