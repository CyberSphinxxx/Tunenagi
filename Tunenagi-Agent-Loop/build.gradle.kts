// Tunenagi root buildscript — owned.
//
// Plugin versions are declared once here (through the version catalog in
// gradle/libs.versions.toml) and applied `false`, so each module opts in with
// `alias(libs.plugins...)` and nothing resolves a version implicitly.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}

/**
 * Prints the pinned toolchain versions this build actually resolves.
 *
 * Reads the version catalog rather than a copy, so the printed numbers cannot drift
 * from the numbers the build uses.
 */
tasks.register("printVersion") {
    group = "tunenagi"
    description = "Print the pinned AGP/Kotlin/Compose/Hilt/Room/Media3 versions used by this build."
    val catalog = layout.projectDirectory.file("gradle/libs.versions.toml")
    inputs.file(catalog)
    doLast {
        val inVersions = catalog.asFile.readLines()
            .dropWhile { it.trim() != "[versions]" }
            .drop(1)
            .takeWhile { !it.trim().startsWith("[") }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        println("--- tunenagi pinned versions (gradle/libs.versions.toml) ---")
        inVersions.forEach { println(it) }
        println("--- end pinned versions ---")
    }
}
