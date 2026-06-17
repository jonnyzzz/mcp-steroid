/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer.tests

import com.jonnyzzz.mcpSteroid.installer.ALL_PLATFORMS
import com.jonnyzzz.mcpSteroid.installer.JdkCoordinates
import com.jonnyzzz.mcpSteroid.installer.resolver.PinnedJdkCoordinates
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Validates the GENERATED `jdk-coordinates.json` (`:site-gen:generateJdkCoordinates`) against the
 * PINNED source (`jdk-downloader/jdk25-pinned.json`). Generation is a TASK DEPENDENCY of this lane, and it
 * fails fast unless every downloaded JDK's bytes match the sha256 FETCHED from the vendor — so the mere
 * fact this test runs means "jdk-downloader downloaded the correct binaries" (validated against the vendor,
 * not a hardcoded literal). Here we then assert the generated METADATA is well-formed: all 5 platforms,
 * vendor/version/url/format carried through from the pin, a real 64-hex sha256, and a `javaHomeSubpath`
 * the generator INFERRED from the archive (the pin never carries one — it carries a `sha256Url` instead).
 * No network + no Docker in this test itself — it asserts on Gradle-produced files.
 */
class JdkCoordinatesMetadataTest {
    private val json = Json { ignoreUnknownKeys = true }

    private val generated: JdkCoordinates by lazy {
        val prop = "test.installer.jdk.coordinates"
        val path = System.getProperty(prop) ?: error("required system property '$prop' not set (configured in site-gen/build.gradle.kts)")
        val f = File(path)
        require(f.isFile) { "generated jdk-coordinates.json missing: $f (run :site-gen:generateJdkCoordinates)" }
        json.decodeFromString(f.readText())
    }

    private val pinned: PinnedJdkCoordinates by lazy {
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
    fun `each generated platform preserves the pinned vendor, version, url and format`() {
        ALL_PLATFORMS.forEach { key ->
            val g = generated.platforms.getValue(key)
            val p = pinned.platforms.getValue(key)
            require(g.vendor == p.vendor) { "$key: vendor drift '${g.vendor}' != pinned '${p.vendor}'" }
            require(g.version == p.version) { "$key: version drift '${g.version}' != pinned '${p.version}'" }
            require(g.url == p.url) { "$key: url drift '${g.url}' != pinned '${p.url}'" }
            require(g.format == p.format) { "$key: format drift '${g.format}' != pinned '${p.format}'" }
            // sha256 is NOT pinned — it is fetched from the vendor + verified at generation. So we assert
            // the generated sha is a real digest, not equality with a (nonexistent) pinned literal.
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
    }

    @Test
    fun `the pin carries a vendor sha256Url instead of a hardcoded sha256`() {
        // The whole point of the redesign: no hardcoded shas. Each platform names a vendor endpoint the
        // generator fetches + verifies the download against; an absolute https URL is the contract.
        ALL_PLATFORMS.forEach { key ->
            val url = pinned.platforms.getValue(key).sha256Url
            require(url.startsWith("https://")) { "$key: sha256Url must be an absolute https URL, got '$url'" }
        }
    }
}
