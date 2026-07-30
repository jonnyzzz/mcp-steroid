/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Process-level contract of [superviseInstallerProcess] with a real `/bin/sh`: stdin closed
 * (immediate EOF), stdout+stderr into the log behind a per-attempt separator + host-record line,
 * retries appending to the same per-pid log, exit-code propagation, and the timeout kill of the
 * started process (and ONLY it — grandchildren deliberately survive).
 *
 * Real time, real processes — runBlocking, NOT runTest (virtual time cannot supervise a real child).
 */
@DisabledOnOs(OS.WINDOWS)
class InstallerProcessTest {

    private fun writeScript(tmp: Path, body: String): Path {
        val script = tmp.resolve("install-test.sh")
        Files.writeString(script, "#!/bin/sh\n$body")
        val perms = Files.getPosixFilePermissions(script).toMutableSet()
        perms += PosixFilePermission.OWNER_EXECUTE
        Files.setPosixFilePermissions(script, perms)
        return script
    }

    @Test
    fun `stdin is EOF, output lands in the log, exit code propagates`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) {
        val script = writeScript(
            tmp,
            """
            if read line; then echo "stdin-had-data"; else echo "stdin-eof"; fi
            echo "a stderr line" >&2
            exit 3
            """.trimIndent(),
        )
        val log = tmp.resolve("logs/update-1-0.102.log")

        val exit = runBlocking { superviseInstallerProcess(script, log, isWin = false) }

        assertEquals(3, exit)
        val logged = log.readText()
        val lines = logged.lines().filter { it.isNotBlank() }
        assertTrue(lines[0].startsWith(INSTALLER_ATTEMPT_SEPARATOR_PREFIX), "the attempt separator opens the log: $logged")
        assertTrue(lines[1].startsWith("[mcp-steroid] installer host: /bin/sh"), "the host record follows the separator: $logged")
        assertTrue(logged.contains("stdin-eof"), "the installer must see an immediate EOF on stdin: $logged")
        assertTrue(logged.contains("a stderr line"), "stderr is merged into the log: $logged")
    }

    @Test
    fun `a retry of the same version appends to the same log, one separator per attempt`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) {
        val script = writeScript(tmp, "echo \"attempt-output\"")
        // the SAME per-pid log file across two ticks retrying the same version
        val log = tmp.resolve("logs/update-1-0.102.log")

        val first = runBlocking { superviseInstallerProcess(script, log, isWin = false) }
        val second = runBlocking { superviseInstallerProcess(script, log, isWin = false) }

        assertEquals(0, first)
        assertEquals(0, second)
        val lines = log.readText().lines()
        assertEquals(2, lines.count { it.startsWith(INSTALLER_ATTEMPT_SEPARATOR_PREFIX) }, "one separator per attempt: $lines")
        assertEquals(2, lines.count { it.startsWith("[mcp-steroid] installer host: /bin/sh") }, "one host record per attempt: $lines")
        assertEquals(2, lines.count { it == "attempt-output" }, "both attempts' output is preserved: $lines")
    }

    @Test
    fun `timeout kills the started process (and ONLY it) before returning`(@org.junit.jupiter.api.io.TempDir tmp: Path) {
        val mainPidFile = tmp.resolve("mainpid")
        val childPidFile = tmp.resolve("childpid")
        val script = writeScript(
            tmp,
            """
            echo ${'$'}${'$'} > "$mainPidFile"
            sleep 300 &
            echo ${'$'}! > "$childPidFile"
            wait
            """.trimIndent(),
        )
        val log = tmp.resolve("logs/update-1-timeout.log")

        val startedAtMs = System.currentTimeMillis()
        val exit = runBlocking { superviseInstallerProcess(script, log, isWin = false, timeout = 2.seconds) }
        val elapsedMs = System.currentTimeMillis() - startedAtMs

        assertNull(exit, "a timeout reports null (started installer process killed)")
        assertTrue(elapsedMs < 30_000, "the timeout path must return promptly, took ${elapsedMs}ms")

        // The process we started (the /bin/sh host) is dead; allow the OS a brief settle for the
        // pid-table entry itself.
        val mainPid = mainPidFile.readText().trim().toLong()
        runBlocking {
            var alive = ProcessHandle.of(mainPid).isPresent
            var patience = 50
            while (alive && patience-- > 0) {
                delay(100)
                alive = ProcessHandle.of(mainPid).isPresent
            }
            assertTrue(!alive, "the started /bin/sh installer host must be killed on timeout")
        }

        // Accepted, documented behavior: grandchildren are NOT killed — the detached 'sleep 300'
        // survives the timeout kill of its shell. Clean it up so the test leaves nothing behind.
        val childPid = childPidFile.readText().trim().toLong()
        val grandchild = ProcessHandle.of(childPid)
        assertTrue(grandchild.isPresent, "the grandchild survives by design (only the started process is killed)")
        grandchild.get().destroyForcibly()
    }
}
