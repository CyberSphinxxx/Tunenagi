// Tunenagi core module — owned shared domain/data surface.
//
// Versions come from gradle/libs.versions.toml; project constants from gradle.properties.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val targetSdkValue = providers.gradleProperty("tunenagi.target.sdk").map(String::toInt).get()
val minSdkValue = providers.gradleProperty("tunenagi.min.sdk").map(String::toInt).get()

android {
    namespace = "com.tunenagi.core"
    compileSdk = targetSdkValue

    defaultConfig {
        minSdk = minSdkValue
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

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
    implementation(libs.androidx.core.ktx)

    // Compose (shared surface with the app module; version from the BOM)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

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

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}

// The toolchain-contract test reads the repository's real version catalog. Inject the
// path instead of letting the test guess a working directory, so the assertion covers the
// file this build actually resolved.
tasks.withType<Test>().configureEach {
    systemProperty(
        "tunenagi.versionCatalog",
        rootProject.layout.projectDirectory.file("gradle/libs.versions.toml").asFile.absolutePath
    )
}
