/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

/**
 * Pins how [runCli] routes its output. CLI convention:
 *  - Help / version go to **stdout** (so `--help | less`, `--version | awk` work).
 *  - Error variants go to **stderr** (so machine-readable stdout never sees usage spam).
 *
 * Mixing these up has bitten plenty of CLIs in the past — we lock the routing in.
 *
 * The test temporarily replaces `System.out` / `System.err` with byte buffers,
 * runs the unit, then restores the originals. JUnit's `@AfterEach` guarantees
 * restoration even on assertion failure so a single failing case doesn't poison
 * unrelated tests in the suite.
 */
class DevrigCommandOutputTest {

    private lateinit var originalOut: PrintStream
    private lateinit var originalErr: PrintStream
    private lateinit var outBuf: ByteArrayOutputStream
    private lateinit var errBuf: ByteArrayOutputStream
    private lateinit var homePaths: HomePaths

    @TempDir
    lateinit var testHome: Path

    @BeforeEach
    fun captureStreams() {
        homePaths = HomePaths(testHome).also { it.mkdirsAll() }
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

    private fun stdout(): String = outBuf.toString(Charsets.UTF_8).replace("\r\n", "\n")
    private fun stderr(): String = errBuf.toString(Charsets.UTF_8).replace("\r\n", "\n")
    private fun runCliForTest(command: DevrigCommand, vararg rawArgs: String): Int {
        val lifetime = CloseableStackHost()
        return try {
            runBlocking {
                DevrigServices(
                    lifetime = lifetime,
                    homePaths = homePaths,
                    mcpStdin = ByteArrayInputStream(ByteArray(0)),
                    mcpStdout = PrintStream(outBuf, true, Charsets.UTF_8),
                ).runCli(command)
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }

    // ------------------------------- Help ----------------------------------

    @Test
    fun `Help writes the usage banner to stdout, nothing to stderr`() {
        val exit = runCliForTest(DevrigCommand.DevrigCommandHelp())
        assertEquals(0, exit)
        assertEquals("", stderr(), "stderr must stay clean for --help; got: ${stderr()}")
        val out = stdout()
        assertTrue(out.contains("Usage:"), "help output should mention 'Usage:'; got:\n$out")
        assertTrue(out.contains("devrig mcp"), "help should advertise the canonical mcp subcommand; got:\n$out")
        assertFalse(out.contains("devrig mpc"), "help must NOT advertise the hidden mpc alias; got:\n$out")
        assertTrue(out.contains("--version"), "help should advertise --version; got:\n$out")
        assertTrue(out.contains("--help"), "help should advertise --help itself; got:\n$out")
        // ONE merged install entry (PR #397 review): the bare and agent-qualified forms share it.
        assertTrue(out.contains("devrig install [claude|codex|gemini] [--check]"), "help should advertise agent install; got:\n$out")
        assertTrue(out.contains("devrig install config"), "help should advertise the manual-config printer; got:\n$out")
        assertTrue(out.contains("backend download [<id>] [--version <v>] [--json]"), "help should advertise download version override; got:\n$out")
        assertTrue(out.contains("no id → list IDEs available for download"), "help should explain download without id; got:\n$out")
        assertTrue(out.contains("backend start    [<id>] [--version <v>] [--json]"), "help should advertise start version override; got:\n$out")
        assertTrue(out.contains("backend stop     [<id>] [--version <v>] [--json]"), "help should advertise stop version override; got:\n$out")
        assertTrue(out.contains("backend provision [<id>] [--json]"), "help should advertise provision default-listing form; got:\n$out")
        assertTrue(out.contains("Product-only id prefers the highest"), "help should explain product-only local backend resolution; got:\n$out")
    }

    @Test
    fun `Help output is line-terminated`() {
        // `command --help | tail -n1` should not see a partial line; the launcher
        // must finish its banner with a newline so shells / piped consumers
        // behave predictably.
        runCliForTest(DevrigCommand.DevrigCommandHelp())
        assertTrue(stdout().endsWith("\n"), "help output must end with a newline; got: '${stdout().takeLast(20)}'")
    }

    // --------------------------- Install config -----------------------------

    @Test
    fun `Install config writes the manual MCP configuration to stdout, nothing to stderr`() {
        // Pasteable output contract: the stdio mcpServers JSON goes to stdout (so
        // `devrig install config | pbcopy` works); stderr stays clean.
        val exit = runCliForTest(DevrigCommand.DevrigCommandInstallConfig())
        assertEquals(0, exit)
        assertEquals("", stderr(), "stderr must stay clean for install config; got: ${stderr()}")
        val out = stdout()
        assertTrue(out.contains("\"mcpServers\""), "config must print the mcpServers JSON snippet; got:\n$out")
        assertTrue(out.contains("mcp-steroid"), "config must use the canonical server name; got:\n$out")
        assertTrue(out.contains("mcp add"), "config must list the per-agent add commands; got:\n$out")
    }

    // ------------------------------ Version --------------------------------

    @Test
    fun `Version writes getDevrigVersion value to stdout`() {
        val exit = runCliForTest(DevrigCommand.DevrigCommandVersion())
        assertEquals(0, exit)
        assertEquals("", stderr(), "stderr must stay clean for --version; got: ${stderr()}")
        val expectedVersion = DevrigVersionMetadata.getDevrigVersion()
        assertEquals("$expectedVersion\n", stdout(),
            "stdout must be exactly the version + newline for `--version`")
    }

    @Test
    fun `Version output is a single line`() {
        // Some monitoring scripts grep `--version | head -1`. Pinning single-line
        // output prevents an accidental multi-line banner sneaking in.
        runCliForTest(DevrigCommand.DevrigCommandVersion())
        val lines = stdout().trimEnd().lines()
        assertEquals(1, lines.size, "version must be a single line; got: ${stdout()}")
    }

    // ------------------------------ Unknown --------------------------------

    @Test
    fun `Unknown writes an error and the usage banner, both to stderr`() {
        val exit = runCliForTest(parseDevrigCommand(arrayOf("--no-such", "thing")), "--no-such", "thing")
        assertEquals(64, exit)
        assertEquals("", stdout(), "stdout must stay clean for unknown-arg errors; got: ${stdout()}")
        val err = stderr()
        assertTrue(err.contains("Error:"), "stderr should announce the bad input; got:\n$err")
        assertTrue(err.contains("Usage:"), "stderr should include parser usage for orientation; got:\n$err")
    }

    @Test
    fun `Unknown with multiple tokens joins them with a single space`() {
        runCliForTest(parseDevrigCommand(arrayOf("a", "b", "c")), "a", "b", "c")
        val err = stderr()
        assertTrue(err.contains("a"), "stderr should identify the offending input; got:\n$err")
    }

    @Test
    fun `Unknown with a single token still produces a coherent error`() {
        runCliForTest(parseDevrigCommand(arrayOf("--what")), "--what")
        val err = stderr()
        assertTrue(err.contains("--what"), "got: $err")
    }
}
