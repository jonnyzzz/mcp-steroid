/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Core #92 invariant: the shared name disambiguation the IDE and devrig both use. */
class ProjectNamingTest {
    @Test
    fun `same raw name plus different paths yield different unique names`() {
        val dir = Files.createTempDirectory("pn")
        val a = Files.createDirectories(dir.resolve("a/dupproj"))
        val b = Files.createDirectories(dir.resolve("b/dupproj"))
        val pid = 1234L
        assertNotEquals(
            uniqueProjectName("dupproj", a.toString(), pid),
            uniqueProjectName("dupproj", b.toString(), pid),
            "two same-named projects at different paths must be individually addressable",
        )
    }

    @Test
    fun `unique name keeps the raw name prefix plus an 8-char base62 suffix, and is deterministic`() {
        val home = Files.createDirectories(Files.createTempDirectory("pn").resolve("dupproj")).toString()
        val first = uniqueProjectName("dupproj", home, 1234L)
        assertEquals(first, uniqueProjectName("dupproj", home, 1234L), "must be deterministic (recompute, never cache)")
        assertTrue(first.startsWith("dupproj-"), "must keep the raw name as a prefix: $first")
        val suffix = first.removePrefix("dupproj-")
        assertEquals(8, suffix.length, "8-char hash suffix: $first")
        assertTrue(suffix.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }, "base62 suffix only: $first")
    }

    @Test
    fun `same path plus different pids yield different names (devrig world-uniqueness salt)`() {
        val home = Files.createDirectories(Files.createTempDirectory("pn").resolve("dupproj")).toString()
        assertNotEquals(uniqueProjectName("dupproj", home, 1L), uniqueProjectName("dupproj", home, 2L))
    }
}
