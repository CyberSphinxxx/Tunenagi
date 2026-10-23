// Tunenagi core module — owned shared domain/data surface.
//
// Versions come from gradle/libs.versions.toml; project constants from gradle.properties.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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

    // The database is exercised on the JVM: Robolectric supplies the Android framework and the
    // SQLite implementation Room talks to, so the schema, the foreign keys and the DAO
    // transactions are asserted by an ordinary test run on any machine (ADR-008).
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    // The exported Room schemas are test *assets*: a future migration test builds its "old
    // database" from the committed JSON rather than from the current entities, which is the only
    // way a migration that works solely on an empty install gets caught.
    sourceSets.getByName("test").assets.srcDir("$projectDir/schemas")

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

    // Media3. The session API is `api` because the playback layer hands the app a
    // `MediaController`: the UI attaches to the service through this type, and the app module
    // must not pin its own Media3 version to do it (one home for each pinned fact). The player
    // itself stays `implementation` — nothing outside `core.playback` may build one.
    api(libs.media3.session)
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
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Room's schema JSON is the committed contract for every version this app has ever shipped.
// KSP writes it here; the migration tests read it back.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The toolchain-contract test reads the repository's real version catalog. Inject the
// path instead of letting the test guess a working directory, so the assertion covers the
// file this build actually resolved.
tasks.withType<Test>().configureEach {
    systemProperty(
        "tunenagi.versionCatalog",
        rootProject.layout.projectDirectory.file("gradle/libs.versions.toml").asFile.absolutePath
    )

    // The live provider smoke (TUN-0306) is opt-in and CI must never call the provider
    // (ADR-017, SPEC/PROVIDERS.md): without this property the suite's Assume skips it, and
    // with it Gradle forwards the flag to the test JVM and echoes the stdout that carries
    // the recorded statuses. `providers.systemProperty` rather than `System.getProperty` so
    // the configuration cache treats the flag as a real input instead of replaying a stale
    // configuration that never saw it.
    val audiusLive = providers.systemProperty("audius.live")
    if (audiusLive.isPresent) {
        systemProperty("audius.live", audiusLive.get())
        testLogging.showStandardStreams = true
    }
}
