/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallOverviewCommandTest {
    @Test
    fun `overview lists every install target and one runnable example`() {
        val text = renderInstallOverview(AiAgentCli.entries.associateWith { null })
        listOf("claude", "codex", "gemini", "plugin", "devrig").forEach {
            assertTrue(text.contains("  $it"), "overview must list target '$it':\n$text")
        }
        assertTrue(text.contains("devrig install claude"), "overview must show a runnable example:\n$text")
    }

    @Test
    fun `overview marks detected CLIs with their path and missing ones as not found`() {
        val claudePath = Path.of("/opt/homebrew/bin/claude")
        val text = renderInstallOverview(
            mapOf(AiAgentCli.CLAUDE to claudePath, AiAgentCli.CODEX to null, AiAgentCli.GEMINI to null),
        )
        assertTrue(text.contains(claudePath.toString()), "detected CLI path must be shown:\n$text")
        assertTrue(text.contains("not found on PATH"), "missing CLIs must be called out:\n$text")
    }

    @Test
    fun `findCliOnPath finds an executable in a PATH directory and returns null when absent`(@TempDir dir: Path) {
        val bin = dir.resolve("bin").createDirectories()
        val cli = bin.resolve("claude")
        cli.writeText("#!/bin/sh\n")
        cli.toFile().setExecutable(true)

        val pathEnv = listOf(dir.toString(), bin.toString()).joinToString(java.io.File.pathSeparator)
        assertEquals(cli, findCliOnPath("claude", pathEnv))
        assertNull(findCliOnPath("codex", pathEnv))
        assertNull(findCliOnPath("claude", null))
    }

    @Test
    fun `findCliOnPath resolves Windows launcher extensions`(@TempDir dir: Path) {
        val cmd = dir.resolve("codex.cmd")
        cmd.writeText("@echo off\n")
        cmd.toFile().setExecutable(true)
        assertEquals(cmd, findCliOnPath("codex", dir.toString(), windowsExtensions = true))
    }
}
