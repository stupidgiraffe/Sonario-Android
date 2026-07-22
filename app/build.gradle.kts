plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "ai.focal.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.focal.app"
        minSdk = 28          // Android 9. 8 Elite phones are far above this.
        targetSdk = 36
        versionCode = 11
        versionName = "1.0.0"
        ndk { abiFilters += "arm64-v8a" }  // modern phones; keeps APK lean
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Networking for source fetching (YouTube captions, web articles)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // HTML parsing for web-article extraction (the Jsoup analogue of BeautifulSoup)
    implementation("org.jsoup:jsoup:1.18.1")

    // PDF text extraction for the local-file picker.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Markdown rendering for the summary view (core + Material 3 module).
    implementation("com.mikepenz:multiplatform-markdown-renderer:0.27.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.27.0")

    // On-device LLM via llama.cpp, through the Llamatik Maven library.
    // No NDK, no native build: it ships prebuilt arm64 binaries.
    implementation("com.llamatik:library-android:1.8.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
