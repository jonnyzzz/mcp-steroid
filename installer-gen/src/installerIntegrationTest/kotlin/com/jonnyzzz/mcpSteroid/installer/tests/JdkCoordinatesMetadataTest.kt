/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer.tests

import com.jonnyzzz.mcpSteroid.installer.ALL_PLATFORMS
import com.jonnyzzz.mcpSteroid.installer.JdkCoordinates
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Validates the GENERATED `jdk-coordinates.json` (`:installer-gen:generateJdkCoordinates`, computed by
 * downloading + inspecting the real JDK 25 archives) against the PINNED source
 * (`jdk-downloader/jdk25-pinned.json`). This is the metadata-correctness guard the user asked for: every
 * one of the 5 platforms must be present, carry the exact pinned vendor/version/url/sha256/format, and
 * have a non-empty `javaHomeSubpath` that the generator INFERRED from the archive (the pinned source
 * never carries one). No network + no Docker — it asserts on Gradle-produced files only.
 */
class JdkCoordinatesMetadataTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val generated: JdkCoordinates by lazy {
        val prop = "test.installer.jdk.coordinates"
        val path = System.getProperty(prop) ?: error("required system property '$prop' not set (configured in installer-gen/build.gradle.kts)")
        val f = File(path)
        require(f.isFile) { "generated jdk-coordinates.json missing: $f (run :installer-gen:generateJdkCoordinates)" }
        json.decodeFromString(f.readText())
    }

    private val pinned: JdkCoordinates by lazy {
        val f = ProjectHomeDirectory.requireProjectHomeDirectory().resolve("jdk-downloader/jdk25-pinned.json").toFile()
        require(f.isFile) { "pinned source missing: $f" }
        json.decodeFromString(f.readText())
    }

    @Test
    fun `generated coordinates cover exactly the five supported platforms`() {
        require(generated.platforms.keys == ALL_PLATFORMS.toSet()) {
            "generated platforms ${generated.platforms.keys} != expected ${ALL_PLATFORMS.toSet()}"
        }
        require(pinned.platforms.keys == ALL_PLATFORMS.toSet()) {
            "pinned platforms ${pinned.platforms.keys} != expected ${ALL_PLATFORMS.toSet()}"
        }
    }

    @Test
    fun `each generated platform preserves the pinned vendor, version, url, sha256 and format`() {
        ALL_PLATFORMS.forEach { key ->
            val g = generated.platforms.getValue(key)
            val p = pinned.platforms.getValue(key)
            require(g.vendor == p.vendor) { "$key: vendor drift '${g.vendor}' != pinned '${p.vendor}'" }
            require(g.version == p.version) { "$key: version drift '${g.version}' != pinned '${p.version}'" }
            require(g.url == p.url) { "$key: url drift '${g.url}' != pinned '${p.url}'" }
            require(g.sha256 == p.sha256) { "$key: sha256 drift '${g.sha256}' != pinned '${p.sha256}'" }
            require(g.format == p.format) { "$key: format drift '${g.format}' != pinned '${p.format}'" }
        }
    }

    @Test
    fun `each generated platform has a valid sha256, a known format and an inferred javaHomeSubpath`() {
        ALL_PLATFORMS.forEach { key ->
            val g = generated.platforms.getValue(key)
            require(g.sha256.matches(Regex("[0-9a-f]{64}"))) { "$key: sha256 not 64 lowercase hex: '${g.sha256}'" }
            require(g.format in setOf("zip", "tar.gz", "tar.xz")) { "$key: unknown format '${g.format}'" }
            // The pinned source never carries javaHomeSubpath — the generator must INFER and set it.
            require(g.javaHomeSubpath.isNotBlank()) { "$key: javaHomeSubpath was not inferred (empty)" }
            require(!g.javaHomeSubpath.startsWith("/") && !g.javaHomeSubpath.endsWith("/")) {
                "$key: javaHomeSubpath '${g.javaHomeSubpath}' must be a relative, non-trailing-slash archive subpath"
            }
        }
        // The pinned source intentionally omits javaHomeSubpath; prove that contract so a drift is caught.
        require(pinned.platforms.values.all { it.javaHomeSubpath.isBlank() }) {
            "jdk25-pinned.json must NOT carry javaHomeSubpath — it is inferred by the generator"
        }
    }
}
