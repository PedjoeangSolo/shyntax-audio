plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.shyntax.audio"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.shyntax.audio"
        minSdk = 26
        targetSdk = 35
        versionCode = 19
        versionName = "1.5.0"
    }
    signingConfigs {
        create("release") {
            val storePath = System.getenv("ANDROID_KEYSTORE_PATH")
            storeFile = if (storePath.isNullOrBlank()) file("release-keystore.jks") else file(storePath)
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Use the same checked-out Media3 1.5.1 source modules as the FFmpeg decoder.
    // Mixing Maven Media3 artifacts with source modules duplicates classes
    // (for example androidx.media3.common.AdOverlayInfo$Builder) during D8.
    implementation(project(":media3-lib-exoplayer"))
    implementation(project(":media3-lib-session"))
    implementation(project(":media3-lib-ui"))
    implementation(project(":media3-lib-decoder-ffmpeg"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.guava:guava:33.3.1-android")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
