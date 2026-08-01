/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every agent can keep an MCP server registered and switched off, and none of them reports that in
 * `mcp list` — verified by hand against a real `~/.claude.json`, where a server disabled for a project is
 * still listed as `✔ Connected`. These tests pin reading that state out of each agent's own config, and
 * clearing it.
 */
class AgentMcpEnablementTest {

    // ---------- Claude Code: projects.<path>.disabledMcpServers ----------

    private val claudeJson = """
        {
          "installMethod": "native",
          "mcpServers": { "mcp-steroid": { "command": "/home/u/.mcp-steroid/bin/devrig", "args": ["mcp"] } },
          "projects": {
            "/home/u/work/a": { "disabledMcpServers": ["mcp-steroid"], "allowedTools": [] },
            "/home/u/work/b": { "disabledMcpServers": [] },
            "/home/u/work/c": { "disabledMcpServers": ["someone-else", "devrig"] },
            "/home/u/work/d": { "allowedTools": [] }
          }
        }
    """.trimIndent()

    @Test
    fun `claude disabled projects list every project that switched a devrig name off`() {
        // Both the canonical name and the legacy 'devrig' spelling count; unrelated names do not.
        assertEquals(listOf("/home/u/work/a", "/home/u/work/c"), claudeDisabledProjects(claudeJson))
    }

    @Test
    fun `claude reports nothing disabled when it cannot tell`() {
        assertTrue(claudeDisabledProjects(null).isEmpty())
        assertTrue(claudeDisabledProjects("").isEmpty())
        assertTrue(claudeDisabledProjects("{ not json").isEmpty())
        assertTrue(claudeDisabledProjects("""{"projects":{}}""").isEmpty())
        // A project with no such key is not a disabled project.
        assertTrue(claudeDisabledProjects("""{"projects":{"/x":{"allowedTools":[]}}}""").isEmpty())
    }

    @Test
    fun `enabling claude removes only devrig names, and only from that list`() {
        val patched = enableClaudeMcp(claudeJson)
        assertTrue(claudeDisabledProjects(patched).isEmpty())
        // Someone else's disabled server is left alone.
        assertTrue(patched.contains("someone-else"))
        // Unrelated content survives: the registration itself and other per-project keys.
        assertTrue(patched.contains("mcpServers"))
        assertTrue(patched.contains("installMethod"))
        assertTrue(patched.contains("allowedTools"))
    }

    @Test
    fun `enabling claude twice changes nothing the second time`() {
        val once = enableClaudeMcp(claudeJson)
        assertEquals(once, enableClaudeMcp(once))
    }

    // ---------- Codex: [mcp_servers.<name>] enabled = false ----------

    @Test
    fun `codex reads enabled false only inside our own table`() {
        assertTrue(
            codexMcpDisabled(
                """
                [mcp_servers.mcp-steroid]
                command = "/home/u/.mcp-steroid/bin/devrig"
                args = ["mcp"]
                enabled = false
                """.trimIndent(),
            ),
        )
        // Someone else's table being off says nothing about ours.
        assertFalse(
            codexMcpDisabled(
                """
                [mcp_servers.notion]
                url = "https://mcp.notion.com/mcp"
                enabled = false

                [mcp_servers.mcp-steroid]
                command = "devrig"
                """.trimIndent(),
            ),
        )
        assertFalse(codexMcpDisabled(null))
        assertFalse(codexMcpDisabled(""))
        assertFalse(codexMcpDisabled("""[mcp_servers.mcp-steroid]${'\n'}enabled = true"""))
        // A quoted table name is the same table.
        assertTrue(codexMcpDisabled("""[mcp_servers."devrig"]${'\n'}enabled = false"""))
    }

    @Test
    fun `enabling codex rewrites one line and leaves the rest byte for byte`() {
        val before = """
            model = "gpt-5"

            [mcp_servers.notion]
            url = "https://mcp.notion.com/mcp"
            enabled = false

            [mcp_servers.mcp-steroid]
            command = "/home/u/.mcp-steroid/bin/devrig"
            args = ["mcp"]
            enabled = false
            tool_timeout_sec = 120
        """.trimIndent()

        val after = enableCodexMcp(before)
        assertFalse(codexMcpDisabled(after))
        // Only our line changed: the other server is still switched off…
        val notionTable = after.substringAfter("[mcp_servers.notion]").substringBefore("[mcp_servers.mcp-steroid]")
        assertTrue(notionTable.lines().any { it.trim() == "enabled = false" })
        // …and every other line is untouched.
        assertEquals(before.lines().size, after.lines().size)
        assertEquals(
            before.lines().filterNot { it.trim() == "enabled = false" },
            after.lines().filterNot { it.trim() in setOf("enabled = false", "enabled = true") },
        )
        assertTrue(after.contains("tool_timeout_sec = 120"))
    }

    @Test
    fun `enabling codex is a no-op when nothing is switched off`() {
        val text = """
            [mcp_servers.mcp-steroid]
            command = "devrig"
        """.trimIndent()
        assertEquals(text, enableCodexMcp(text))
    }

    // ---------- Gemini: mcp.excluded / mcp.allowed ----------

    @Test
    fun `gemini is disabled by exclusion or by an allow-list that omits us`() {
        assertTrue(geminiMcpDisabled("""{"mcp":{"excluded":["mcp-steroid"]}}"""))
        assertTrue(geminiMcpDisabled("""{"mcp":{"allowed":["git"]}}"""))
        // An allow-nothing list is a real setting, and it excludes us too.
        assertTrue(geminiMcpDisabled("""{"mcp":{"allowed":[]}}"""))

        assertFalse(geminiMcpDisabled("""{"mcp":{"allowed":["git","mcp-steroid"]}}"""))
        assertFalse(geminiMcpDisabled("""{"mcp":{"excluded":["github"]}}"""))
        // No `mcp` block at all means no global rules — nothing is excluded.
        assertFalse(geminiMcpDisabled("""{"mcpServers":{"mcp-steroid":{"command":"devrig"}}}"""))
        assertFalse(geminiMcpDisabled(null))
        assertFalse(geminiMcpDisabled("{ not json"))
    }

    @Test
    fun `enabling gemini clears the exclusion and joins an existing allow-list`() {
        val excluded = enableGeminiMcp("""{"mcp":{"excluded":["mcp-steroid","github"]}}""")
        assertFalse(geminiMcpDisabled(excluded))
        // Someone else's exclusion is not ours to remove.
        assertTrue(excluded.contains("github"))

        val allowed = enableGeminiMcp("""{"mcp":{"allowed":["git"]}}""")
        assertFalse(geminiMcpDisabled(allowed))
        assertTrue(allowed.contains("git"))
        assertTrue(allowed.contains(CANONICAL_DEVRIG_SERVER_NAME))
    }

    @Test
    fun `enabling gemini twice changes nothing the second time`() {
        val once = enableGeminiMcp("""{"mcp":{"excluded":["mcp-steroid"]}}""")
        assertEquals(once, enableGeminiMcp(once))
    }
}
