/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Platform-neutral checks of the WMI spawn PowerShell script TEXT (design doc v6, migration table).
 * The Windows runtime path keeps its integration coverage; here we pin the script's contract:
 * UTF-8 prologue, pid printed as the only stdout expression (captured by the process runner's log
 * file — no temp files), batch wrapping, and quoting.
 */
class WmiSpawnScriptTest {
    private val workDir = Path.of("C:\\work dir")

    @Test
    fun `script sets utf8 output encoding before anything else`() {
        val script = buildWmiSpawnScript(Path.of("C:\\ide\\bin\\idea64.exe"), workDir, emptyMap())
        assertTrue(
            script.startsWith("\$OutputEncoding = [Console]::OutputEncoding = [System.Text.Encoding]::UTF8; "),
            script,
        )
    }

    @Test
    fun `script emits the pid as its final stdout expression and uses no files`() {
        val script = buildWmiSpawnScript(Path.of("C:\\ide\\bin\\idea64.exe"), workDir, emptyMap())
        assertTrue(script.trimEnd().endsWith("\$r.ProcessId"), script)
        assertFalse(script.contains("Out-File"), "pid must go to stdout, not a temp file: $script")
    }

    @Test
    fun `batch launchers are wrapped with cmd exe so the script path stays visible`() {
        val script = buildWmiSpawnScript(Path.of("C:\\ide\\bin\\idea.bat"), workDir, emptyMap())
        assertContains(script, "cmd.exe /c")
        assertContains(script, "idea.bat")
    }

    @Test
    fun `binary launchers are not wrapped`() {
        val script = buildWmiSpawnScript(Path.of("C:\\ide\\bin\\idea64.exe"), workDir, emptyMap())
        assertFalse(script.contains("cmd.exe /c"), script)
    }

    @Test
    fun `environment variables are embedded via Win32_ProcessStartup`() {
        val script = buildWmiSpawnScript(
            Path.of("C:\\ide\\bin\\idea64.exe"),
            workDir,
            mapOf("DEVRIG_TEST" to "value"),
        )
        assertContains(script, "Win32_ProcessStartup")
        assertContains(script, "DEVRIG_TEST=value")
    }

    @Test
    fun `single quotes in paths are doubled for powershell literals`() {
        val script = buildWmiSpawnScript(Path.of("C:\\it's here\\idea64.exe"), workDir, emptyMap())
        assertContains(script, "it''s here")
    }

    @Test
    fun `pid parse accepts exactly one non-blank numeric line`() {
        assertEquals(4242L, parseWmiSpawnPid("4242"))
        assertEquals(4242L, parseWmiSpawnPid("\n  4242  \r\n\n"))
    }

    @Test
    fun `pid parse rejects chatter, multiple lines, and non-numeric output`() {
        assertNull(parseWmiSpawnPid(""), "empty output")
        assertNull(parseWmiSpawnPid("Loading personal profile...\n4242"), "chatter before the pid")
        assertNull(parseWmiSpawnPid("4242\n4243"), "two numeric lines are ambiguous")
        assertNull(parseWmiSpawnPid("PID: 4242"), "prefixed line is not a bare pid")
        assertNull(parseWmiSpawnPid("4242 …[truncated by ProcessRunner]"), "a truncated line must not parse")
    }
}
