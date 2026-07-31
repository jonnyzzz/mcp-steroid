/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WindowsProcessCommandTest {

    @Test
    fun `remote overrides retain required Windows launcher environment without secrets`() {
        val merged = windowsProcessEnvironment(
            inherited = mapOf(
                "Path" to "C:\\Windows\\System32",
                "USERPROFILE" to "C:\\Users\\agent",
                "ANTHROPIC_API_KEY" to "must-not-leak",
                "remote_dev_non_interactive" to "old",
            ),
            overrides = mapOf(
                "REMOTE_DEV_NON_INTERACTIVE" to "1",
                "REMOTE_DEV_TRUST_PROJECTS" to "1",
            ),
        )

        assertEquals("C:\\Windows\\System32", merged["Path"])
        assertEquals("C:\\Users\\agent", merged["USERPROFILE"])
        assertEquals("1", merged["REMOTE_DEV_NON_INTERACTIVE"])
        assertEquals("1", merged["REMOTE_DEV_TRUST_PROJECTS"])
        assertEquals(false, merged.containsKey("remote_dev_non_interactive"))
        assertEquals(false, merged.containsKey("ANTHROPIC_API_KEY"))
    }

    @Test
    fun `remote overrides retain required Unix session environment without agent secrets`() {
        val merged = managedProcessEnvironment(
            inherited = mapOf(
                "HOME" to "/home/agent",
                "PATH" to "/usr/local/bin:/usr/bin",
                "DISPLAY" to ":99",
                "LC_MESSAGES" to "en_US.UTF-8",
                "OPENAI_API_KEY" to "must-not-leak",
                "CODEX_API_KEY" to "must-not-leak",
            ),
            overrides = mapOf(
                "REMOTE_DEV_NON_INTERACTIVE" to "1",
                "REMOTE_DEV_TRUST_PROJECTS" to "1",
            ),
            hostOs = HostOs.LINUX,
        )

        assertEquals("/home/agent", merged["HOME"])
        assertEquals("/usr/local/bin:/usr/bin", merged["PATH"])
        assertEquals(":99", merged["DISPLAY"])
        assertEquals("en_US.UTF-8", merged["LC_MESSAGES"])
        assertEquals("1", merged["REMOTE_DEV_NON_INTERACTIVE"])
        assertEquals("1", merged["REMOTE_DEV_TRUST_PROJECTS"])
        assertEquals(false, merged.containsKey("OPENAI_API_KEY"))
        assertEquals(false, merged.containsKey("CODEX_API_KEY"))
    }

    @Test
    fun `quotes executable path and appends remote development run argument`() {
        val command = buildWindowsProcessCommand(
            launcher = Path.of("C:\\Program Files\\JetBrains\\IntelliJ IDEA\\bin\\remote-dev-server.exe"),
            arguments = listOf("run"),
        )

        assertEquals(
            "\"C:\\Program Files\\JetBrains\\IntelliJ IDEA\\bin\\remote-dev-server.exe\" run",
            command,
        )
    }

    @Test
    fun `quotes arguments with whitespace quotes and trailing backslashes`() {
        val command = buildWindowsProcessCommand(
            launcher = Path.of("C:\\idea\\bin\\remote-dev-server.exe"),
            arguments = listOf("run", "two words", "quoted\"value", "C:\\project path\\"),
        )

        assertEquals(
            "\"C:\\idea\\bin\\remote-dev-server.exe\" run \"two words\" \"quoted\\\"value\" \"C:\\project path\\\\\"",
            command,
        )
    }

    @Test
    fun `macOS detachment keeps every dynamic path out of the shell program`() {
        val launcher = Path.of("/Applications/IntelliJ IDEA.app/Contents/bin/remote-dev-server")
        val stdout = Path.of("/tmp/devrig logs/managed out.log")
        val stderr = Path.of("/tmp/devrig logs/managed err.log")

        val command = buildMacOsDetachedProcessCommand(
            launcher = launcher,
            arguments = listOf("run", "/tmp/project with spaces"),
            stdoutLog = stdout,
            stderrLog = stderr,
        )

        assertEquals("/bin/sh", command[0])
        assertEquals("-c", command[1])
        val shellProgram = command[2]
        assertContains(shellProgram, "set -m")
        assertContains(shellProgram, "/usr/bin/nohup")
        assertContains(shellProgram, "\"$@\"")
        assertContains(shellProgram, "$!")
        assertFalse(shellProgram.contains(launcher.toString()))
        assertFalse(shellProgram.contains(stdout.toString()))
        assertFalse(shellProgram.contains(stderr.toString()))
        assertEquals(
            listOf(
                "devrig-detach",
                stdout.toString(),
                stderr.toString(),
                launcher.toString(),
                "run",
                "/tmp/project with spaces",
            ),
            command.drop(3),
        )
    }
}
