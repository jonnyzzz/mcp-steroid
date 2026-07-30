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
 * (immediate EOF), stdout+stderr into the log behind a host-record first line, the
 * DEVRIG_AUTO_UPDATE=1 env, exit-code propagation, and the timeout tree-kill.
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
    fun `stdin is EOF, output lands in the log, env carries the auto-update flag, exit code propagates`(
        @org.junit.jupiter.api.io.TempDir tmp: Path,
    ) {
        val script = writeScript(
            tmp,
            """
            if read line; then echo "stdin-had-data"; else echo "stdin-eof"; fi
            echo "auto-update-env=${'$'}DEVRIG_AUTO_UPDATE"
            echo "a stderr line" >&2
            exit 3
            """.trimIndent(),
        )
        val log = tmp.resolve("logs/update-1-0.102.0.log")

        val exit = runBlocking { superviseInstallerProcess(script, log, isWin = false) }

        assertEquals(3, exit)
        val logged = log.readText()
        assertTrue(logged.startsWith("[mcp-steroid] installer host: /bin/sh"), logged)
        assertTrue(logged.contains("stdin-eof"), "the installer must see an immediate EOF on stdin: $logged")
        assertTrue(logged.contains("auto-update-env=1"), "DEVRIG_AUTO_UPDATE=1 must reach the script: $logged")
        assertTrue(logged.contains("a stderr line"), "stderr is merged into the log: $logged")
    }

    @Test
    fun `timeout kills the WHOLE process tree before returning`(@org.junit.jupiter.api.io.TempDir tmp: Path) {
        val childPidFile = tmp.resolve("childpid")
        val script = writeScript(
            tmp,
            """
            sleep 300 &
            echo ${'$'}! > "$childPidFile"
            wait
            """.trimIndent(),
        )
        val log = tmp.resolve("logs/update-1-timeout.log")

        val exit = runBlocking { superviseInstallerProcess(script, log, isWin = false, timeout = 2.seconds) }

        assertNull(exit, "a timeout reports null (installer tree killed)")
        val childPid = childPidFile.readText().trim().toLong()
        // superviseInstallerProcess only returns after the tree is confirmed dead; allow the OS a
        // brief settle for the pid-table entry itself.
        runBlocking {
            var alive = ProcessHandle.of(childPid).isPresent
            var patience = 50
            while (alive && patience-- > 0) {
                delay(100)
                alive = ProcessHandle.of(childPid).isPresent
            }
            assertTrue(!alive, "the detached 'sleep 300' grandchild must be killed with the tree")
        }
    }
}
