/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.aiAgents

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpServerConfigTest {

    @Test
    fun `stdioMcpServersJson emits a pretty-printed mcpServers entry with command and args`() {
        val json = stdioMcpServersJson(StdioMcpCommand("/home/user/.mcp-steroid/bin/devrig", listOf("mcp")))

        assertTrue(json.lines().size > 1, "must be pretty-printed (multi-line), got: $json")
        val server = Json.parseToJsonElement(json)
            .jsonObject.getValue("mcpServers")
            .jsonObject.getValue(DEFAULT_SERVER_NAME)
            .jsonObject
        assertEquals("/home/user/.mcp-steroid/bin/devrig", server.getValue("command").jsonPrimitive.content)
        assertEquals(listOf("mcp"), server.getValue("args").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `stdioMcpServersJson escapes Windows backslash paths`() {
        // The Windows launcher invocation: cmd.exe /d /c "<quoted .cmd path> mcp". Both the backslashes
        // and the embedded quotes MUST be JSON-escaped — hand-concatenation would emit invalid JSON.
        val line = "\"C:\\Users\\First Last\\.mcp-steroid\\bin\\devrig.cmd\" mcp"
        val json = stdioMcpServersJson(StdioMcpCommand("cmd.exe", listOf("/d", "/c", line)))

        assertTrue(
            json.contains("C:\\\\Users\\\\First Last"),
            "backslashes must appear JSON-escaped in the output, got: $json",
        )
        // Round-trip: the emitted JSON must decode back to the exact input command line.
        val server = Json.parseToJsonElement(json)
            .jsonObject.getValue("mcpServers")
            .jsonObject.getValue(DEFAULT_SERVER_NAME)
            .jsonObject
        assertEquals("cmd.exe", server.getValue("command").jsonPrimitive.content)
        assertEquals(listOf("/d", "/c", line), server.getValue("args").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `stdioMcpServersJson honors a custom server name`() {
        val json = stdioMcpServersJson(StdioMcpCommand("/opt/devrig", listOf("mcp")), serverName = "custom-name")
        val servers = Json.parseToJsonElement(json).jsonObject.getValue("mcpServers").jsonObject
        assertEquals(setOf("custom-name"), servers.keys)
    }
}
