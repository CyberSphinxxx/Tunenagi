package com.tunenagi.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit coverage for the pinned-toolchain guard.
 *
 * The failure paths matter as much as the happy path here: a guard that silently accepts
 * a drifted catalog is worse than no guard, so every rejection rule is asserted.
 */
class ToolchainCatalogTest {

    private val sampleCatalog = """
        # comment before the table
        [versions]
        agp = "8.7.3"
        kotlin = "2.0.20"
        ksp = "2.0.20-1.0.25"
        composeBom = "2024.10.01"

        [libraries]
        okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }

        [plugins]
        android-application = { id = "com.android.application", version.ref = "agp" }
    """.trimIndent()

    @Test
    fun `parse reads only the versions table`() {
        val versions = ToolchainCatalog.parse(sampleCatalog)

        assertEquals("8.7.3", versions["agp"])
        assertEquals("2.0.20", versions["kotlin"])
        assertEquals("2024.10.01", versions["composeBom"])
        // Table keys are not versions; if they leaked in, the guard would accept garbage.
        assertTrue("library table leaked into versions", "okhttp" !in versions)
        assertTrue("plugin table leaked into versions", "android-application" !in versions)
    }

    @Test
    fun `read rejects a missing catalog file`() {
        val missing = File("does-not-exist/libs.versions.toml")

        val failure = assertThrows(IllegalArgumentException::class.java) {
            ToolchainCatalog.read(missing)
        }

        assertTrue(
            "failure should name the path, was: ${failure.message}",
            failure.message!!.contains("libs.versions.toml")
        )
    }

    @Test
    fun `required rejects a missing key`() {
        val versions = ToolchainCatalog.parse(sampleCatalog)

        val failure = assertThrows(IllegalArgumentException::class.java) {
            ToolchainCatalog.required(versions, "media3")
        }

        assertTrue("failure should name the key", failure.message!!.contains("media3"))
    }

    @Test
    fun `ksp must be built for the pinned kotlin release`() {
        ToolchainCatalog.assertKspMatchesKotlin(ToolchainCatalog.parse(sampleCatalog))

        val drifted = ToolchainCatalog.parse(sampleCatalog) + ("ksp" to "1.9.24-1.0.20")
        val failure = assertThrows(IllegalStateException::class.java) {
            ToolchainCatalog.assertKspMatchesKotlin(drifted)
        }

        assertTrue("failure should name the kotlin line", failure.message!!.contains("1.9.24"))
    }

    @Test
    fun `compose bom must not predate the kotlin compose compiler line`() {
        ToolchainCatalog.assertComposeBomMatchesKotlin(ToolchainCatalog.parse(sampleCatalog))

        val tooOld = ToolchainCatalog.parse(sampleCatalog) + ("composeBom" to "2023.10.01")
        assertThrows(IllegalStateException::class.java) {
            ToolchainCatalog.assertComposeBomMatchesKotlin(tooOld)
        }

        val malformed = ToolchainCatalog.parse(sampleCatalog) + ("composeBom" to "latest")
        assertThrows(IllegalArgumentException::class.java) {
            ToolchainCatalog.assertComposeBomMatchesKotlin(malformed)
        }
    }

    @Test
    fun `the real catalog in this repository satisfies the invariants`() {
        val path = requireNotNull(System.getProperty("tunenagi.versionCatalog")) {
            "the test task must supply -Dtunenagi.versionCatalog (see core/build.gradle.kts)"
        }
        val versions = ToolchainCatalog.read(File(path))

        ToolchainCatalog.assertKspMatchesKotlin(versions)
        ToolchainCatalog.assertComposeBomMatchesKotlin(versions)
        // Deliberately a shape check, not a re-pin: the point is that the pin exists and
        // is a resolvable version, not that this test restates the number.
        assertTrue(
            "agp pin looks wrong: ${versions["agp"]}",
            Regex("""^\d+\.\d+\.\d+$""").matches(ToolchainCatalog.required(versions, "agp"))
        )
    }
}
