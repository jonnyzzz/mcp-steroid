/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectClaudeCommandTest {
    private fun capture(): Pair<PrintStream, ByteArrayOutputStream> {
        val buf = ByteArrayOutputStream()
        return PrintStream(buf, true, "UTF-8") to buf
    }

    @Test
    fun `writes settings when file is absent`() {
        val dir = Files.createTempDirectory("claude-home")
        val settings = dir.resolve(".claude").resolve("settings.json")
        val (out, outBuf) = capture()
        val (err, _) = capture()

        val code = runConnectClaude(settings, out, err)

        assertEquals(0, code)
        assertTrue(isClaudePluginEnabled(settings.readText()))
        assertContains(outBuf.toString("UTF-8"), "enabled")
    }

    @Test
    fun `is idempotent and does not error when already enabled`() {
        val dir = Files.createTempDirectory("claude-home")
        val settings = dir.resolve(".claude").resolve("settings.json")
        Files.createDirectories(settings.parent)
        settings.writeText(enableClaudePluginInSettings(null))
        val (out, outBuf) = capture()
        val (err, _) = capture()

        val code = runConnectClaude(settings, out, err)

        assertEquals(0, code)
        assertTrue(isClaudePluginEnabled(settings.readText()))
        assertContains(outBuf.toString("UTF-8"), "already")
    }

    @Test
    fun `preserves existing unrelated settings`() {
        val dir = Files.createTempDirectory("claude-home")
        val settings = dir.resolve(".claude").resolve("settings.json")
        Files.createDirectories(settings.parent)
        settings.writeText("""{"model":"opus"}""")
        val (out, _) = capture()
        val (err, _) = capture()

        runConnectClaude(settings, out, err)

        assertContains(settings.readText(), "\"model\": \"opus\"")
        assertTrue(isClaudePluginEnabled(settings.readText()))
    }
}
