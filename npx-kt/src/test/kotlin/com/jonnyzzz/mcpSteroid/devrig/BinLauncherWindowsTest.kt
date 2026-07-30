/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real-Windows validation of the [replaceLauncherFile] sequence against genuine NTFS file contention —
 * no seam injection, real `Files.move` semantics. The Windows-only holders:
 *
 *  - `cmd.exe /d /c <batch>` actually EXECUTING the target (the production contention case);
 *  - a PowerShell process holding a memory-mapped section of the target — the running/mapped-file
 *    semantics (delete and overwrite blocked, rename allowed), deterministic;
 *  - a PowerShell process holding the target open with `FileShare.Read` only (no delete sharing) —
 *    the strict-scanner case where NOTHING works, not even the rename-aside.
 */
@EnabledOnOs(OS.WINDOWS)
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class BinLauncherWindowsTest {

    private val old = "@echo off\r\nrem OLD launcher\r\nping -n 2 127.0.0.1 >nul\r\n"
    private val new = "@echo off\r\nrem NEW launcher\r\n"

    @Test
    fun `no contention - creates a missing launcher and replaces an existing one on real NTFS`(@TempDir dir: Path) {
        val target = dir.resolve("devrig.cmd")

        replaceLauncherFile(target, old, executable = false)
        assertEquals(old, target.readText(), "the first write must create the launcher")

        replaceLauncherFile(target, new, executable = false)
        assertEquals(new, target.readText(), "the second write must replace it")
        val leftovers = Files.list(dir).use { s -> s.filter { it != target }.toList() }
        assertTrue(leftovers.isEmpty(), "no staging or old-pid file may remain: $leftovers")
    }

    @Test
    fun `replaces the launcher while cmd exe is executing it`(@TempDir dir: Path) {
        val target = dir.resolve("devrig.cmd")
        // The production contention: an agent launched devrig via this very .cmd and the rewrite happens
        // while cmd.exe still executes it. The batch loops so cmd keeps coming back to read more lines.
        Files.writeString(target, "@echo off\r\n:loop\r\nping -n 2 127.0.0.1 >nul\r\ngoto loop\r\n")
        val runner = ProcessBuilder("cmd.exe", "/d", "/c", target.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        try {
            Thread.sleep(700) // let cmd read the batch and enter the loop
            assertTrue(runner.isAlive, "precondition: cmd.exe must still be executing the batch")

            val (failure, stderr) = runCaptured { replaceLauncherFile(target, new, executable = false) }
            println("[x220] cmd-runner scenario stderr:\n$stderr")
            println("[x220] cmd-runner scenario leftovers: ${Files.list(dir).use { s -> s.filter { it != target }.toList() }}")

            // Finding on real Windows (eugene-x220): cmd.exe holds the executing batch with FULL sharing
            // (read+write+delete), so even the direct MoveFileEx replace succeeds — the sequence converges
            // on its very first move without needing the rename-aside.
            assertNull(failure, "the sequence must converge while cmd.exe executes the launcher: $failure\n$stderr")
            assertEquals(new, target.readText(), "the launcher must carry the NEW content")
        } finally {
            killTree(runner)
        }
    }

    @Test
    fun `mapped-section holder (running-file semantics) - rename-aside replaces content, locked old-pid leftover is tolerated`(@TempDir dir: Path) {
        val target = dir.resolve("devrig.cmd")
        Files.writeString(target, old)
        // A memory-mapped section blocks DELETE and overwrite of the data but NOT rename — exactly the
        // semantics of a running executable/mapped file. The file handle itself shares read/write/delete,
        // so the section is the only blocker (isolates the NTFS rule under test).
        val path = target.toString().replace("'", "''")
        val holder = startHolder(
            "\$fs=[System.IO.File]::Open('$path',[System.IO.FileMode]::Open,[System.IO.FileAccess]::Read,[System.IO.FileShare]'ReadWrite, Delete'); " +
                "\$mmf=[System.IO.MemoryMappedFiles.MemoryMappedFile]::CreateFromFile(\$fs,\$null,0,[System.IO.MemoryMappedFiles.MemoryMappedFileAccess]::Read,\$null,[System.IO.HandleInheritability]::None,\$true); " +
                "[Console]::Out.WriteLine('LOCKED'); Start-Sleep -Seconds 150",
        )
        val aside = dir.resolve("devrig.cmd.old${ProcessHandle.current().pid()}")
        try {
            val (failure, stderr) = runCaptured { replaceLauncherFile(target, new, executable = false) }
            println("[x220] mapped-holder scenario stderr:\n$stderr")

            // The direct replace is blocked, the rename-aside succeeds, the new content lands — but the
            // final delete of .old<pid> keeps failing while the mapping lives, so after 5 rounds the
            // sequence gives up loudly with the CORRECT content in place and the leftover staying behind.
            assertEquals(new, target.readText(), "the rename-aside must have delivered the NEW content\n$stderr")
            assertTrue(Files.exists(aside), "the parked original must still exist (locked by the mapping)\n$stderr")
            assertEquals(old, aside.readText(), "the parked original must keep its bytes")
            assertNotNull(failure, "the undeletable .old<pid> must surface as the give-up failure")
        } finally {
            killTree(holder)
        }
        // Once the holder is gone the leftover is deletable — proof the mapping was the only blocker.
        Files.deleteIfExists(aside)
        assertFalse(Files.exists(aside))
    }

    @Test
    fun `share-read-only holder (strict scanner) - everything is blocked, original untouched, recovers once released`(@TempDir dir: Path) {
        val target = dir.resolve("devrig.cmd")
        Files.writeString(target, old)
        // FileShare.Read denies write AND delete sharing: the direct replace fails and so does the
        // rename-aside (a rename needs delete access on the file). No rename dance can beat this holder.
        val path = target.toString().replace("'", "''")
        val holder = startHolder(
            "\$fs=[System.IO.File]::Open('$path',[System.IO.FileMode]::Open,[System.IO.FileAccess]::Read,[System.IO.FileShare]::Read); " +
                "[Console]::Out.WriteLine('LOCKED'); Start-Sleep -Seconds 150",
        )
        try {
            val (failure, stderr) = runCaptured { replaceLauncherFile(target, new, executable = false) }
            println("[x220] read-only-holder scenario stderr:\n$stderr")

            assertNotNull(failure, "all 5 attempts must fail against a no-delete-sharing holder")
            assertTrue(failure is IOException, "a real filesystem failure is expected, got $failure")
            assertEquals(old, target.readText(), "the original launcher must be left untouched and usable")
            assertFalse(Files.exists(dir.resolve("devrig.cmd.old${ProcessHandle.current().pid()}")),
                "the rename-aside cannot have parked anything")
        } finally {
            killTree(holder)
        }
        // The transient-lock story: once the holder is gone, the next (self-heal) write succeeds.
        replaceLauncherFile(target, new, executable = false)
        assertEquals(new, target.readText(), "the write must succeed once the lock is released")
    }

    /** What a captured run observed: the failure thrown by the sequence (null if none) and its stderr log. */
    private data class Captured(val failure: Exception?, val stderr: String)

    /** Runs [block] with System.err captured; the observations are asserted by each scenario. */
    private fun runCaptured(block: () -> Unit): Captured {
        val buffer = ByteArrayOutputStream()
        val original = System.err
        System.setErr(PrintStream(buffer, true))
        val failure = try {
            block()
            null
        } catch (e: Exception) {
            e // returned to the caller, which asserts on it — never swallowed
        } finally {
            System.setErr(original)
        }
        return Captured(failure, buffer.toString())
    }

    /** Spawns a PowerShell holder and blocks until it confirms the lock is in place. */
    private fun startHolder(script: String): Process {
        val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
            .redirectErrorStream(true)
            .start()
        val reader = process.inputStream.bufferedReader()
        val line = reader.readLine() // bounded by the class-level @Timeout
        if (line != "LOCKED") {
            val rest = try {
                reader.readText()
            } catch (e: Exception) {
                "(output unreadable: $e)"
            }
            killTree(process)
            throw IllegalStateException("the PowerShell holder failed to acquire the lock: $line\n$rest")
        }
        return process
    }

    private fun killTree(process: Process) {
        process.toHandle().descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor(10, TimeUnit.SECONDS)
    }
}
