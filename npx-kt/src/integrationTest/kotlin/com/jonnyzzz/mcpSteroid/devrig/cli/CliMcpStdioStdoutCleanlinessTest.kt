/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.cli

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.StdoutCleanlinessHarness
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Asserts that the devrig launcher writes ONLY MCP NDJSON frames to its stdout. Any banner from the shell
 * launcher script, any logger output that leaked past the stderr-only logback config, any `println` that
 * bypassed the `System.setOut(System.err)` redirect in `main()` would corrupt the JSON-RPC stream for a
 * real client.
 *
 * The launcher runs INSIDE the shared `mcp-cli` container ([DevrigCliContainer]), never on the host — a
 * host run would create the developer's real `~/.mcp-steroid` (devrig's home is hardcoded). Each test gets
 * its own container so the startup-failure test can corrupt the home without affecting the happy-path test.
 */
@Suppress("FunctionName")
class CliMcpStdioStdoutCleanlinessTest {

    private val lifetime = CloseableStackHost()

    @AfterEach
    fun tearDown() {
        lifetime.closeAllStacks()
    }

    @Test
    fun `launcher writes only JSON-RPC frames to stdout`() {
        val cli = lifetime.startDevrigCliContainer()

        val result = cli.runMpcWithStdin(StdoutCleanlinessHarness.handshakeBytes)

        check(result.exitCode == 0) {
            "[docker:linux] launcher exited with ${result.exitCode}\nstdout=\n${result.stdout}\nstderr=\n${result.stderr}"
        }
        StdoutCleanlinessHarness.assertStdoutClean(
            stdout = result.stdout,
            variantLabel = "docker:linux",
            stderrForDiagnostics = result.stderr,
        )
    }

    @Test
    fun `startup failure before handshake is visible on stderr`() {
        val cli = lifetime.startDevrigCliContainer()

        // devrig's home is hardcoded to ~/.mcp-steroid (= /home/agent/.mcp-steroid in the container). Make
        // that path a regular FILE so devrig's mkdirsAll() can't create the home tree and dies with exit 64
        // BEFORE the MCP handshake — a standard in-container path, no $HOME/-Duser.home tricks.
        cli.container.startProcessInContainer {
            args("sh", "-c", "rm -rf /home/agent/.mcp-steroid && touch /home/agent/.mcp-steroid")
                .description("corrupt devrig home to a file")
                .quietly()
        }.awaitForProcessFinish().assertExitCode(0) { "failed to corrupt devrig home: $stderr" }

        val result = cli.runMpcWithStdin(StdoutCleanlinessHarness.handshakeBytes)

        check(result.exitCode == 64) {
            "launcher exited with ${result.exitCode}\nstdout=\n${result.stdout}\nstderr=\n${result.stderr}"
        }
        check(result.stdout.isBlank()) {
            "startup failure must not emit partial MCP frames on stdout; got:\n${result.stdout}"
        }
        check(result.stderr.contains("Startup failure:")) {
            "startup failure must be visible on stderr; got:\n${result.stderr}"
        }
    }
}
