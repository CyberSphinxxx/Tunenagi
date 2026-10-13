// Tunenagi app module — owned.
//
// Toolchain/library versions come from gradle/libs.versions.toml. Project constants that
// are Tunenagi-specific (SDK levels, application id, version code/name) live in
// gradle.properties and are read here, so each fact has exactly one home.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val targetSdkValue = providers.gradleProperty("tunenagi.target.sdk").map(String::toInt).get()
val minSdkValue = providers.gradleProperty("tunenagi.min.sdk").map(String::toInt).get()

android {
    namespace = "com.tunenagi.player"
    compileSdk = targetSdkValue

    defaultConfig {
        applicationId = providers.gradleProperty("tunenagi.applicationId").get()
        minSdk = minSdkValue
        targetSdk = targetSdkValue
        versionCode = providers.gradleProperty("tunenagi.versionCode").map(String::toInt).get()
        versionName = providers.gradleProperty("tunenagi.versionName").get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            // Minification is deliberately off until the release path is exercised with a
            // real keep-rule audit (see SKILLS/release.md).
            isMinifyEnabled = false
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
    }

    // Android UI tests that do not need an attached device. Robolectric runs the real
    // Android runtime (framework classes, resources, Compose) inside the JVM, so the shell's
    // navigation behaviour is verified by an automated check on every machine, not only where
    // an emulator happens to be installed. `src/sharedTest` holds the test sources that run in
    // both places: the JVM variant here and the instrumented variant on a device.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    sourceSets.getByName("test").java.srcDir("src/sharedTest/java")
    sourceSets.getByName("androidTest").java.srcDir("src/sharedTest/java")

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // Used directly for the tab icons; declared rather than relied on transitively.
    implementation(libs.compose.material.icons.core)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Media3 playback surface (wired to real playback in the audio-engine task)
    implementation(libs.media3.exoplayer)

    // Core Kotlin libs
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Retrofit + OkHttp surface (wired to real providers in the provider task)
    implementation(libs.okhttp)
    implementation(libs.retrofit)

    // Room surface (schema added with the persistence task)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    // JVM-hosted Android runtime for the shared Compose UI tests (no device required).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
