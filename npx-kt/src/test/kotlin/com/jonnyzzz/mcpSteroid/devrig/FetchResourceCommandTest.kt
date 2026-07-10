/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.server.canonicalResourceEntryPoints
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * End-to-end coverage for `devrig fetch_resource` / `devrig prompt` WITHOUT a running IDE: bundled
 * `mcp-steroid://` articles resolve from the devrig binary using [com.jonnyzzz.mcpSteroid.prompts.PromptsContext.Generic].
 *
 * Pins the CLI contract: data → stdout, errors → stderr, `--json` → a stable envelope, unknown URI →
 * non-zero exit + entry-point hints on stderr.
 */
class FetchResourceCommandTest {

    private lateinit var originalErr: PrintStream
    private lateinit var errBuf: ByteArrayOutputStream
    private lateinit var homePaths: HomePaths

    @TempDir
    lateinit var testHome: Path

    /** A URI guaranteed to exist in the bundled article index (as a plain string — no prompts import). */
    private val knownUri: String get() = canonicalResourceEntryPoints().first()

    @BeforeEach
    fun setUp() {
        homePaths = HomePaths(testHome).also { it.mkdirsAll() }
        originalErr = System.err
        errBuf = ByteArrayOutputStream()
        System.setErr(PrintStream(errBuf, true, Charsets.UTF_8))
    }

    @AfterEach
    fun tearDown() {
        System.setErr(originalErr)
    }

    private fun stderr(): String = errBuf.toString(Charsets.UTF_8).replace("\r\n", "\n")

    private data class Run(val exit: Int, val stdout: String)

    private fun run(command: DevrigCommand): Run {
        val outBuf = ByteArrayOutputStream()
        val lifetime = CloseableStackHost()
        val exit = try {
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
        return Run(exit, outBuf.toString(Charsets.UTF_8).replace("\r\n", "\n"))
    }

    @Test
    fun `known uri prints markdown to stdout, nothing to stderr, exit 0`() {
        val result = run(DevrigCommand.DevrigCommandFetchResource(uri = knownUri))
        assertEquals(0, result.exit)
        assertTrue(result.stdout.isNotBlank(), "expected markdown payload on stdout")
        assertEquals("", stderr(), "stdout-only for success; stderr must stay clean")
    }

    @Test
    fun `known uri with --json emits a parseable envelope`() {
        val result = run(DevrigCommand.DevrigCommandFetchResource(uri = knownUri, json = true))
        assertEquals(0, result.exit)
        val obj = Json.parseToJsonElement(result.stdout).jsonObject
        assertEquals("devrig", obj["tool"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("fetch_resource", obj["command"]!!.jsonPrimitive.content)
        assertEquals(false, obj["isError"]!!.jsonPrimitive.booleanOrNull)
        val content = obj["data"]!!.jsonObject["content"]!!.jsonArray
        assertTrue(content.isNotEmpty(), "envelope must carry the resolved content")
        assertEquals("text", content.first().jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `prompt alias reports command prompt in the --json envelope, not fetch_resource`() {
        val result = run(DevrigCommand.DevrigCommandFetchResource(uri = knownUri, commandName = "prompt", json = true))
        assertEquals(0, result.exit)
        val obj = Json.parseToJsonElement(result.stdout).jsonObject
        assertEquals("prompt", obj["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `fetch_resource alias reports command fetch_resource in the --json envelope`() {
        val result = run(DevrigCommand.DevrigCommandFetchResource(uri = knownUri, commandName = "fetch_resource", json = true))
        assertEquals(0, result.exit)
        val obj = Json.parseToJsonElement(result.stdout).jsonObject
        assertEquals("fetch_resource", obj["command"]!!.jsonPrimitive.content)
    }

    @Test
    fun `unknown uri fails with entry-point hints on stderr, clean stdout`() {
        val result = run(DevrigCommand.DevrigCommandFetchResource(uri = "mcp-steroid://does/not/exist"))
        assertEquals(CliExit.TOOL_ERROR, result.exit)
        assertEquals("", result.stdout, "stdout must stay clean on error")
        val err = stderr()
        assertTrue(err.contains("Resource not found"), "error must name the failure: $err")
        assertTrue(err.contains("devrig prompt "), "error must suggest runnable entry points: $err")
    }

    @Test
    fun `unknown uri with --json still routes exit code and JSON envelope`() {
        val result = run(DevrigCommand.DevrigCommandFetchResource(uri = "mcp-steroid://nope", json = true))
        assertEquals(CliExit.TOOL_ERROR, result.exit)
        val obj = Json.parseToJsonElement(result.stdout).jsonObject
        assertTrue(obj["isError"]!!.jsonPrimitive.booleanOrNull == true)
    }

    @Test
    fun `blank uri reaching the handler yields a usage exit`() {
        val result = run(DevrigCommand.DevrigCommandFetchResource(uri = ""))
        assertEquals(CliExit.USAGE, result.exit)
        assertEquals("", result.stdout)
        assertTrue(stderr().contains("missing <uri>"))
    }

    @Test
    fun `unknown project_name is a usage error pointing at list_projects`() {
        val result = run(DevrigCommand.DevrigCommandFetchResource(uri = knownUri, projectName = "no-such-project"))
        assertEquals(CliExit.USAGE, result.exit)
        assertFalse(result.stdout.contains("```"), "must not print a payload when routing failed")
        assertTrue(stderr().contains("list_projects"), "should point the agent at devrig list_projects")
    }
}
