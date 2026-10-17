package com.tunenagi.core

import java.io.File

/**
 * Reads the pinned toolchain versions from `gradle/libs.versions.toml` and enforces the
 * cross-version invariants the Android build depends on.
 *
 * These invariants fail in ways a compiler cannot describe: a KSP release built for a
 * different Kotlin line, or a catalog entry that quietly disappears, surfaces later as an
 * unrelated configuration error. Asserting them here turns that into an immediate,
 * readable failure. This is real code, not test scaffolding — the unit test in
 * `core/src/test` exercises the positive and the failure paths.
 */
object ToolchainCatalog {

    /**
     * Parses the `[versions]` table out of a Gradle version-catalog document.
     * Keys from the `[libraries]`/`[plugins]` tables are not versions and are skipped.
     */
    fun parse(text: String): Map<String, String> {
        val versions = LinkedHashMap<String, String>()
        var inVersionsTable = false
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("[") -> inVersionsTable = line == "[versions]"
                !inVersionsTable || line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val separator = line.indexOf('=')
                    if (separator > 0) {
                        val key = line.substring(0, separator).trim()
                        val value = line.substring(separator + 1).trim().trim('"')
                        if (key.isNotEmpty() && value.isNotEmpty()) versions[key] = value
                    }
                }
            }
        }
        return versions
    }

    /** Reads and parses the catalog, failing loudly when the file is missing or empty. */
    fun read(catalogFile: File): Map<String, String> {
        require(catalogFile.isFile) { "version catalog not found: ${catalogFile.absolutePath}" }
        val versions = parse(catalogFile.readText())
        require(versions.isNotEmpty()) { "version catalog has no [versions] entries: ${catalogFile.absolutePath}" }
        return versions
    }

    /** Returns a required version, or fails naming the missing key. */
    fun required(versions: Map<String, String>, key: String): String =
        versions[key] ?: throw IllegalArgumentException("version catalog is missing the required key '$key'")

    /**
     * KSP publishes one artifact per Kotlin release (`2.0.20-1.0.25`). A catalog that
     * pins them to different Kotlin lines cannot resolve, so it is rejected here.
     */
    fun assertKspMatchesKotlin(versions: Map<String, String>) {
        val kotlin = required(versions, "kotlin")
        val ksp = required(versions, "ksp")
        check(ksp.startsWith("$kotlin-")) {
            "ksp '$ksp' is not built for kotlin '$kotlin'"
        }
    }

    /**
     * The Compose compiler is shipped by the Kotlin release that owns it, so the Compose
     * BOM must not be pinned ahead of the Compose line that compiler targets. Fails when
     * either key is missing rather than passing silently.
     */
    fun assertComposeBomMatchesKotlin(versions: Map<String, String>) {
        val kotlinLine = required(versions, "kotlin").substringBeforeLast('.')
        val composeBomYear = required(versions, "composeBom").substringBefore('.').toIntOrNull()
            ?: throw IllegalArgumentException("composeBom '${versions["composeBom"]}' is not a dated BOM version")
        check(composeBomYear >= 2024) {
            "composeBom '${versions["composeBom"]}' predates the kotlin '$kotlinLine' Compose compiler line"
        }
    }
}
