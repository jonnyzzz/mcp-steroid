/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.mcp.Root
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DevrigWorkspaceRootsTest {
    @Test fun `maps file uri roots to paths and appends cwd`() = runTest {
        val roots = DevrigWorkspaceRoots(
            rootsProvider = { listOf(Root(uri = "file:///tmp/ws-a"), Root(uri = "file:///tmp/ws-b")) },
            processCwd = { "/tmp/cwd" },
        )
        val dirs = roots.candidateDirs().map { it.toString() }
        assertTrue(dirs.contains("/tmp/ws-a"))
        assertTrue(dirs.contains("/tmp/ws-b"))
        assertTrue(dirs.contains("/tmp/cwd"))
        assertEquals("/tmp/ws-a", dirs.first()) // roots first, cwd fallback last
    }

    @Test fun `falls back to cwd when client advertises no roots`() = runTest {
        val roots = DevrigWorkspaceRoots(rootsProvider = { null }, processCwd = { "/tmp/only-cwd" })
        assertEquals(listOf(Path.of("/tmp/only-cwd")), roots.candidateDirs())
    }

    @Test fun `falls back to cwd when roots provider throws`() = runTest {
        val roots = DevrigWorkspaceRoots(
            rootsProvider = { error("boom") },
            processCwd = { "/tmp/cwd2" },
        )
        assertEquals(listOf(Path.of("/tmp/cwd2")), roots.candidateDirs())
    }

    @Test fun `caches the first result`() = runTest {
        var calls = 0
        val roots = DevrigWorkspaceRoots(
            rootsProvider = { calls++; emptyList() },
            processCwd = { "/tmp/c" },
        )
        roots.candidateDirs(); roots.candidateDirs()
        assertEquals(1, calls)
    }
}
