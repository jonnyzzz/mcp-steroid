/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class McpServerListReviewTest {
    @Test
    fun `parses claude line-format list, skipping the health banner`() {
        val output = """
            Checking MCP server health…

            playwright: npx @playwright/mcp@latest - ✓ Connected
            mcp-steroid: /usr/bin/env JAVA_HOME=/opt/jdk /opt/devrig/bin/devrig mpc - ✓ Connected
        """.trimIndent()

        val servers = parseMcpServerList(AiAgentCli.CLAUDE, output)
        assertEquals(listOf("playwright", "mcp-steroid"), servers.map { it.name })
        assertEquals("npx @playwright/mcp@latest", servers[0].commandLine)
        assertTrue(servers[1].commandLine.contains("/opt/devrig/bin/devrig mpc"), servers[1].commandLine)
    }

    @Test
    fun `parses codex json list`() {
        val json = """
            [
              {"name":"mcp-steroid","transport":{"type":"stdio","command":"/usr/bin/env",
                "args":["JAVA_HOME=/opt/jdk","/opt/devrig/bin/devrig","mpc"]}},
              {"name":"playwright","transport":{"type":"stdio","command":"npx","args":["@playwright/mcp@latest"]}}
            ]
        """.trimIndent()

        val servers = parseMcpServerList(AiAgentCli.CODEX, json)
        assertEquals(listOf("mcp-steroid", "playwright"), servers.map { it.name })
        assertEquals("/usr/bin/env JAVA_HOME=/opt/jdk /opt/devrig/bin/devrig mpc", servers[0].commandLine)
    }

    @Test
    fun `unreadable output yields an empty list rather than throwing`() {
        assertTrue(parseMcpServerList(AiAgentCli.CODEX, "not json at all").isEmpty())
        assertTrue(parseMcpServerList(AiAgentCli.CODEX, "[ broken json").isEmpty())
        assertTrue(parseMcpServerList(AiAgentCli.CLAUDE, "").isEmpty())
    }

    @Test
    fun `detects devrig ownership by name or by command`() {
        // by canonical / legacy name
        assertTrue(McpServerRef("mcp-steroid", "anything").isDevrigOwned())
        assertTrue(McpServerRef("devrig", "anything").isDevrigOwned())
        assertTrue(McpServerRef("MCP-STEROID", "anything").isDevrigOwned())
        // by command, even under a custom name
        assertTrue(McpServerRef("my-ide", "/usr/bin/env /custom/devrig mcp").isDevrigOwned())
        // unrelated servers are left alone
        assertFalse(McpServerRef("playwright", "npx @playwright/mcp@latest").isDevrigOwned())
        assertFalse(McpServerRef("github", "docker run ghcr.io/github/github-mcp-server").isDevrigOwned())
    }

    @Test
    fun `match reason names which signals matched — name, config, or both`() {
        // both the name and the configuration point at devrig
        assertEquals("name + config", McpServerRef("mcp-steroid", "/opt/devrig/bin/devrig mcp").devrigMatchReason())
        // canonical name but a stale/foreign command
        assertEquals("name", McpServerRef("mcp-steroid", "npx some-other-server").devrigMatchReason())
        // custom name but the command runs devrig
        assertEquals("config", McpServerRef("legacy-ide", "/usr/bin/env /custom/devrig mpc").devrigMatchReason())
    }
}
