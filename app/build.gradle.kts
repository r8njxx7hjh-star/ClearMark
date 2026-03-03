plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.drawingapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.drawingapp"
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
}

dependencies {
    // ── Existing ──────────────────────────────────────────────────
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.motion.prediction)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    // ── Google Ink API ────────────────────────────────────────────
    val inkVersion = "1.0.0-alpha04"
    implementation("androidx.ink:ink-authoring:$inkVersion")
    implementation("androidx.ink:ink-brush:$inkVersion")
    implementation("androidx.ink:ink-geometry:$inkVersion")
    implementation("androidx.ink:ink-nativeloader:$inkVersion")
    implementation("androidx.ink:ink-rendering:$inkVersion")
    implementation("androidx.ink:ink-strokes:$inkVersion")
    implementation("androidx.graphics:graphics-core:1.0.2")
}