/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer.tests

import com.jonnyzzz.mcpSteroid.installer.ALL_PLATFORMS
import com.jonnyzzz.mcpSteroid.installer.JdkCoordinateResolver
import com.jonnyzzz.mcpSteroid.installer.parseJdkArg
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * "jdk-downloader downloads correct binaries": the test task downloads the 5 pinned JDK 25 archives and
 * passes their specs (platform|vendor|version|format|sha256|url|file) via `test.installer.jdk.specs`. Here
 * we run the SAME resolver the installer generator uses over those REAL files — it re-hashes each archive
 * and cross-checks it against the pinned sha256 (fail-fast on a corrupt download or a stale pin), and infers
 * `javaHomeSubpath` from the real layout — then asserts the resulting metadata is well-formed for all 5
 * platforms. No intermediate jdk-coordinates.json: the coordinates are computed ad-hoc from the files.
 */
class JdkCoordinatesMetadataTest {
    private val artifacts by lazy {
        val specs = System.getProperty("test.installer.jdk.specs")
            ?: error("required system property 'test.installer.jdk.specs' not set (configured in site-gen/build.gradle.kts)")
        specs.trim().lines().filter { it.isNotBlank() }.map { parseJdkArg(it) }
    }

    @Test
    fun `the pinned set covers exactly the five supported platforms`() {
        assertEquals(ALL_PLATFORMS.toSet(), artifacts.map { it.platformKey }.toSet())
    }

    @Test
    fun `resolving the real downloads verifies sha256 and infers javaHomeSubpath for every platform`() {
        // resolve() re-hashes each file, requires computed == pinned sha256 (the download-correctness check),
        // and infers javaHomeSubpath (fail-fast if the archive has no bin/java) — for all 5 or it throws.
        val coords = JdkCoordinateResolver.resolve(artifacts)
        assertEquals(ALL_PLATFORMS.toSet(), coords.platforms.keys)
        ALL_PLATFORMS.forEach { key ->
            val e = coords.platforms.getValue(key)
            assertTrue(e.sha256.matches(Regex("[0-9a-f]{64}")), "$key sha256: ${e.sha256}")
            assertTrue(e.format in setOf("zip", "tar.gz", "tar.xz"), "$key format: ${e.format}")
            assertTrue(e.javaHomeSubpath.isNotBlank(), "$key javaHomeSubpath was not inferred")
            assertTrue(
                !e.javaHomeSubpath.startsWith("/") && !e.javaHomeSubpath.endsWith("/"),
                "$key javaHomeSubpath must be a relative, non-trailing-slash subpath: '${e.javaHomeSubpath}'",
            )
        }
    }
}
