import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Motion alert settings live in the untracked local.properties so the secret and topic
// never land in git. Missing keys build as "" and leave alerts disabled.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun localString(key: String): String = "\"${localProps.getProperty(key, "").trim()}\""

android {
    namespace = "com.toddmargolis.astroandroidapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.toddmargolis.astroandroidapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        renderscriptTargetApi = 21
        renderscriptSupportModeEnabled = true

        buildConfigField("String", "ALERT_UPLOAD_URL", localString("alert.uploadUrl"))
        buildConfigField("String", "ALERT_SECRET", localString("alert.secret"))
        buildConfigField("String", "ALERT_NTFY_TOPIC", localString("alert.ntfyTopic"))
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    val camerax_version = "1.4.2"
    implementation("androidx.camera:camera-camera2:$camerax_version")
    implementation("androidx.camera:camera-lifecycle:$camerax_version")
    implementation("androidx.camera:camera-view:$camerax_version")
    implementation("androidx.camera:camera-core:$camerax_version")
}