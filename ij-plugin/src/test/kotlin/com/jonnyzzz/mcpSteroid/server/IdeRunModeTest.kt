/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdeRunModeTest {

    @Test
    fun `all flags false is normal UI`() {
        assertEquals(
            IdeRunMode.NORMAL_UI,
            classifyIdeRunMode(isUnitTest = false, isRemoteDevBackend = false, isHeadless = false)
        )
    }

    @Test
    fun `headless flag alone is headless`() {
        assertEquals(
            IdeRunMode.HEADLESS,
            classifyIdeRunMode(isUnitTest = false, isRemoteDevBackend = false, isHeadless = true)
        )
    }

    @Test
    fun `remote dev backend flag alone is remote dev backend`() {
        assertEquals(
            IdeRunMode.REMOTE_DEV_BACKEND,
            classifyIdeRunMode(isUnitTest = false, isRemoteDevBackend = true, isHeadless = false)
        )
    }

    @Test
    fun `unit test flag alone is unit test`() {
        assertEquals(
            IdeRunMode.UNIT_TEST,
            classifyIdeRunMode(isUnitTest = true, isRemoteDevBackend = false, isHeadless = false)
        )
    }

    @Test
    fun `remote dev backend beats headless`() {
        // A remote-dev backend can be launched with the AWT headless flag set
        // (e.g. the rdserver-headless command) — it must still classify as backend.
        assertEquals(
            IdeRunMode.REMOTE_DEV_BACKEND,
            classifyIdeRunMode(isUnitTest = false, isRemoteDevBackend = true, isHeadless = true)
        )
    }

    @Test
    fun `unit test beats headless`() {
        // Test processes run with the AWT headless flag set — no headless WARN noise in tests.
        assertEquals(
            IdeRunMode.UNIT_TEST,
            classifyIdeRunMode(isUnitTest = true, isRemoteDevBackend = false, isHeadless = true)
        )
    }

    @Test
    fun `unit test beats remote dev backend and headless`() {
        assertEquals(
            IdeRunMode.UNIT_TEST,
            classifyIdeRunMode(isUnitTest = true, isRemoteDevBackend = true, isHeadless = true)
        )
    }

    @Test
    fun `headless warning and client notice cite issue 177`() {
        assertTrue(
            "WARN text should cite mcp-steroid#177: $HEADLESS_UNSUPPORTED_WARNING",
            HEADLESS_UNSUPPORTED_WARNING.contains("mcp-steroid#177")
        )
        assertTrue(
            "MCP client notice should cite mcp-steroid#177: $HEADLESS_MCP_CLIENT_NOTICE",
            HEADLESS_MCP_CLIENT_NOTICE.contains("mcp-steroid#177")
        )
        assertTrue(
            "MCP client notice must stay one line: $HEADLESS_MCP_CLIENT_NOTICE",
            !HEADLESS_MCP_CLIENT_NOTICE.contains("\n")
        )
    }
}
