/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.util.text.DevrigVersion
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Stdout purity of the auto-update path. `devrig mcp` speaks JSON-RPC on stdout, so NOTHING a full
 * [AutoUpdater.tick] does may write there — a single stray byte corrupts the protocol. Every
 * human-facing line goes to stderr (and the [AutoUpdater.notify] callback for the MCP broadcast).
 *
 * Each test swaps `System.out` / `System.err` for byte buffers, runs a full `tick()`, and asserts
 * the stdout capture is EMPTY while stderr carries the expected line. `@AfterEach` restores the
 * original streams even when an assertion fails (the same pattern as [DevrigCommandOutputTest]).
 */
class AutoUpdaterStdoutPurityTest {

    private var now = 1_000_000_000_000L
    private val livePids = mutableSetOf(4242L)

    private lateinit var originalOut: PrintStream
    private lateinit var originalErr: PrintStream
    private lateinit var outBuf: ByteArrayOutputStream
    private lateinit var errBuf: ByteArrayOutputStream

    @BeforeEach
    fun captureStreams() {
        originalOut = System.out
        originalErr = System.err
        outBuf = ByteArrayOutputStream()
        errBuf = ByteArrayOutputStream()
        System.setOut(PrintStream(outBuf, true, Charsets.UTF_8))
        System.setErr(PrintStream(errBuf, true, Charsets.UTF_8))
    }

    @AfterEach
    fun restoreStreams() {
        System.setOut(originalOut)
        System.setErr(originalErr)
    }

    private fun stdout(): String = outBuf.toString(Charsets.UTF_8)
    private fun stderr(): String = errBuf.toString(Charsets.UTF_8).replace("\r\n", "\n")

    private fun assertStdoutEmpty() =
        assertEquals("", stdout(), "stdout is the MCP JSON-RPC channel — the update tick must never write to it")

    private class Fixture(val updater: AutoUpdater, val home: HomePaths, val notices: MutableList<String>)

    private fun fixture(tmp: Path, downloadSucceeds: Boolean = true, installerExit: Int? = 0): Fixture {
        val home = HomePaths(tmp.resolve("home/.mcp-steroid"))
        home.mkdirsAll()
        val notices = mutableListOf<String>()
        val updater = AutoUpdater(
            homePaths = home,
            currentVersion = DevrigVersion.parse("0.101"),
            isWin = false,
            coordination = UpdateCoordination(home.updateDir, ownPid = 4242L, clock = { now }, isPidAlive = { it in livePids }),
            notify = { notices += it },
            fetchPromoted = { DevrigVersion.parse("0.102") },
            downloadScript = { _, target ->
                if (downloadSucceeds) {
                    Files.createDirectories(target.parent)
                    Files.writeString(target, "#!/bin/sh\necho fake install script\n")
                }
                downloadSucceeds
            },
            runInstaller = { _, logFile ->
                Files.createDirectories(logFile.parent)
                Files.writeString(logFile, "fake installer ran\n")
                installerExit
            },
            noAutoUpdateEnv = null,
            binRegisterOptOutEnv = null,
        )
        return Fixture(updater, home, notices)
    }

    @Test
    fun `happy path - the restart notice goes to stderr and notify, stdout stays empty`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp)

        f.updater.tick()

        assertStdoutEmpty()
        assertTrue(stderr().contains("restart your agent session"), stderr())
        assertTrue(stderr().contains("devrig 0.102 is installed"), stderr())
        assertEquals(1, f.notices.size, "the notice also reaches the notify() callback (the MCP broadcast)")
    }

    @Test
    fun `failing installer - the warning with the log path goes to stderr, stdout stays empty`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp, installerExit = 7)

        f.updater.tick()

        assertStdoutEmpty()
        assertTrue(stderr().contains("devrig auto-update to 0.102 failed"), stderr())
        assertTrue(stderr().contains("(exit 7)"), stderr())
        assertTrue(stderr().contains(f.updater.logFileFor("0.102").toString()), "the warning must point at the log file: ${stderr()}")
        assertEquals(0, f.notices.size, "failures never reach notify()")
    }

    @Test
    fun `installer timeout - the timed-out warning with the log path goes to stderr, stdout stays empty`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp, installerExit = null)

        f.updater.tick()

        assertStdoutEmpty()
        assertTrue(stderr().contains("timed out"), stderr())
        assertTrue(stderr().contains(f.updater.logFileFor("0.102").toString()), "the warning must point at the log file: ${stderr()}")
        assertEquals(0, f.notices.size)
    }

    @Test
    fun `download failure - the retry line goes to stderr, stdout stays empty`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp, downloadSucceeds = false)

        f.updater.tick()

        assertStdoutEmpty()
        assertTrue(stderr().contains("could not download"), stderr())
        assertTrue(stderr().contains("https://devrig.dev/install.sh"), stderr())
        assertEquals(0, f.notices.size)
    }

    @Test
    fun `yield to a live in-progress marker - fully silent on both streams`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp)
        livePids += 100L
        UpdateCoordination(f.home.updateDir, ownPid = 100L, clock = { now }, isPidAlive = { it in livePids })
            .writeInProgressMarker("0.102", UpdateStateInfo(pid = 100L, currentVersion = "0.101", targetVersion = "0.102", startedAt = now))

        f.updater.tick()

        assertStdoutEmpty()
        assertEquals("", stderr(), "the yield is silent — nothing on stderr either")
        assertEquals(0, f.notices.size)
    }
}
