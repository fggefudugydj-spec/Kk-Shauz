plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aicouncil"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aicouncil"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
}

dependencies {
    implementation("com.google.mediapipe:tasks-genai:0.10.22")
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
