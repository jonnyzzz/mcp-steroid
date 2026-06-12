/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorStatus
import com.jonnyzzz.mcpSteroid.devrig.server.DevrigExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.devrig.server.DevrigProjectRoutingService
import com.jonnyzzz.mcpSteroid.devrig.server.DevrigToolBridgeClient
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.ModalMode
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.http.ContentType
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * `devrig exec-code` (issue #100): the CLI front-end over the existing `steroid_execute_code`
 * tool surface. The "fake bridge" here is an embedded NDJSON server capturing the request body,
 * driven through the REAL [DevrigExecuteCodeToolHandler] + [DevrigToolBridgeClient] — the same
 * objects the MCP path uses — so the pinned JSON below IS the devrig→plugin wire contract
 * (mirrors `DevrigToolBridgeClientTest`).
 */
class ExecCodeCommandTest {

    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var httpClient: HttpClient
    private var port: Int = 0
    private var receivedBody: String? = null
    private val beforeResultEvents = mutableListOf<String>()
    private var resultToServe: ToolCallResult = ToolCallResult(
        content = listOf(ContentItem.Text("ok")),
        isError = false,
    )

    @BeforeEach
    fun setUp() {
        port = freePort()
        server = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            routing {
                post("/api/jonnyzzz/mcp-steroid/v1/tools/call/stream") {
                    receivedBody = call.receiveText()
                    call.respondTextWriter(ContentType.parse("application/x-ndjson")) {
                        beforeResultEvents.forEach { write(it) }
                        write(
                            buildJsonObject {
                                put("type", "result")
                                put("result", McpJson.encodeToJsonElement(ToolCallResult.serializer(), resultToServe))
                            }.toString() + "\n"
                        )
                        flush()
                    }
                }
            }
        }.also { it.start(wait = false) }

        httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                connectTimeoutMillis = 2_000
            }
            expectSuccess = false
        }
    }

    @AfterEach
    fun tearDown() {
        receivedBody = null
        beforeResultEvents.clear()
        resultToServe = ToolCallResult(content = listOf(ContentItem.Text("ok")), isError = false)
        httpClient.close()
        server.stop(0L, 0L)
    }

    // ------------------------------ parsing ---------------------------------

    @Test
    fun `exec-code parses required options and forwards every optional flag`() {
        val minimal = assertIs<DevrigCommand.DevrigCommandExecCode>(
            parseDevrigCommand(arrayOf("exec-code", "--project", "my-app-abcdefgh", "--file", "script.kt")),
        )
        assertEquals("my-app-abcdefgh", minimal.project)
        assertEquals("script.kt", minimal.file)
        assertNull(minimal.taskId)
        assertNull(minimal.reason)
        assertNull(minimal.modal)
        assertNull(minimal.timeout)

        val full = assertIs<DevrigCommand.DevrigCommandExecCode>(
            parseDevrigCommand(
                arrayOf(
                    "--debug", "exec-code",
                    "--project", "p", "--file", "f.kt",
                    "--task-id", "t1", "--reason", "r1",
                    "--modal", "unleashed", "--timeout", "42",
                ),
            ),
        )
        assertTrue(full.debug)
        assertEquals("t1", full.taskId)
        assertEquals("r1", full.reason)
        assertEquals(ModalMode.UNLEASHED, full.modal)
        assertEquals(42, full.timeout)

        // Missing required options, a bad modal value, and a bad timeout are parse errors.
        assertIs<DevrigCommand.DevrigCommandParseError>(parseDevrigCommand(arrayOf("exec-code", "--file", "f.kt")))
        assertIs<DevrigCommand.DevrigCommandParseError>(parseDevrigCommand(arrayOf("exec-code", "--project", "p")))
        assertIs<DevrigCommand.DevrigCommandParseError>(
            parseDevrigCommand(arrayOf("exec-code", "--project", "p", "--file", "f.kt", "--modal", "bogus")),
        )
        assertIs<DevrigCommand.DevrigCommandParseError>(
            parseDevrigCommand(arrayOf("exec-code", "--project", "p", "--file", "f.kt", "--timeout", "soon")),
        )
    }

    // ------------------------- wire contract pinned --------------------------

    @Test
    fun `unset options send the exact tool-schema defaults over the bridge`(
        @TempDir tempDir: Path,
    ) {
        val script = Files.writeString(tempDir.resolve("smoke-check.kt"), "println(project.name)")
        val routing = routingService(stateWithProject(tempDir, pid = 42, name = "my-app"))
        val route = routing.routes().values.single()

        val run = runExec(
            DevrigCommand.DevrigCommandExecCode(project = route.exposedProjectName, file = script.toString()),
            routing,
            tempDir,
        )

        assertEquals(0, run.exitCode, run.stderr)
        // Frozen wire contract — the same param names/values DevrigExecuteCodeToolHandler sends for
        // an MCP call where the agent passed no optional params (the schema fills the defaults).
        val json = McpJson.parseToJsonElement(receivedBody ?: error("missing request body")).jsonObject
        assertEquals("steroid_execute_code", json["name"]?.jsonPrimitive?.content)
        val arguments = json["arguments"]?.jsonObject ?: error("missing arguments: $json")
        assertEquals("my-app", arguments["project_name"]?.jsonPrimitive?.content)
        assertEquals("println(project.name)", arguments["code"]?.jsonPrimitive?.content)
        assertEquals("cli-smoke-check", arguments["task_id"]?.jsonPrimitive?.content)
        assertEquals(EXEC_CODE_DEFAULT_REASON, arguments["reason"]?.jsonPrimitive?.content)
        assertEquals("600", arguments["timeout"]?.jsonPrimitive?.content)
        assertEquals("smart_non_modal", arguments["modal"]?.jsonPrimitive?.content)
        assertEquals(setOf("project_name", "code", "task_id", "reason", "timeout", "modal"), arguments.keys)
    }

    @Test
    fun `explicit options forward verbatim over the bridge`(
        @TempDir tempDir: Path,
    ) {
        val script = Files.writeString(tempDir.resolve("repro.kt"), "error(\"boom?\")")
        val routing = routingService(stateWithProject(tempDir, pid = 42, name = "my-app"))
        val route = routing.routes().values.single()

        val run = runExec(
            DevrigCommand.DevrigCommandExecCode(
                project = route.exposedProjectName,
                file = script.toString(),
                taskId = "issue-100",
                reason = "verify the CLI forwards everything",
                modal = ModalMode.UNLEASHED,
                timeout = 42,
            ),
            routing,
            tempDir,
        )

        assertEquals(0, run.exitCode, run.stderr)
        val arguments = McpJson.parseToJsonElement(receivedBody ?: error("missing request body"))
            .jsonObject["arguments"]?.jsonObject ?: error("missing arguments")
        assertEquals("issue-100", arguments["task_id"]?.jsonPrimitive?.content)
        assertEquals("verify the CLI forwards everything", arguments["reason"]?.jsonPrimitive?.content)
        assertEquals("42", arguments["timeout"]?.jsonPrimitive?.content)
        assertEquals("unleashed", arguments["modal"]?.jsonPrimitive?.content)
        assertEquals("error(\"boom?\")", arguments["code"]?.jsonPrimitive?.content)
    }

    // --------------------------- output routing ------------------------------

    @Test
    fun `progress lines stream to stderr and the result text prints to stdout`(
        @TempDir tempDir: Path,
    ) {
        beforeResultEvents += """{"type":"progress","message":"compile started"}""" + "\n"
        beforeResultEvents += """{"type":"progress","message":"executing"}""" + "\n"
        resultToServe = ToolCallResult(content = listOf(ContentItem.Text("script result")), isError = false)
        val script = Files.writeString(tempDir.resolve("script.kt"), "println(1)")
        val routing = routingService(stateWithProject(tempDir, pid = 42, name = "my-app"))
        val route = routing.routes().values.single()

        val run = runExec(
            DevrigCommand.DevrigCommandExecCode(project = route.exposedProjectName, file = script.toString()),
            routing,
            tempDir,
        )

        assertEquals(0, run.exitCode)
        assertContains(run.stderr, "compile started")
        assertContains(run.stderr, "executing")
        assertEquals("script result", run.stdout.trim(), "stdout must carry ONLY the tool result")
        assertFalse(run.stdout.contains("compile started"), "progress must never leak to stdout")
    }

    @Test
    fun `isError result prints the error and exits with the tool-error code`(
        @TempDir tempDir: Path,
    ) {
        resultToServe = ToolCallResult(content = listOf(ContentItem.Text("ERROR: script exploded")), isError = true)
        val script = Files.writeString(tempDir.resolve("script.kt"), "println(1)")
        val routing = routingService(stateWithProject(tempDir, pid = 42, name = "my-app"))
        val route = routing.routes().values.single()

        val run = runExec(
            DevrigCommand.DevrigCommandExecCode(project = route.exposedProjectName, file = script.toString()),
            routing,
            tempDir,
        )

        assertEquals(EXEC_CODE_TOOL_ERROR_EXIT_CODE, run.exitCode)
        assertContains(run.stdout, "ERROR: script exploded")
    }

    // ------------------------------ failures ---------------------------------

    @Test
    fun `unreadable file errors clearly without any bridge call`(
        @TempDir tempDir: Path,
    ) {
        val routing = routingService(stateWithProject(tempDir, pid = 42, name = "my-app"))
        val missing = tempDir.resolve("does-not-exist.kt")

        val run = runExec(
            DevrigCommand.DevrigCommandExecCode(project = "my-app", file = missing.toString()),
            routing,
            tempDir,
        )

        assertEquals(EXEC_CODE_FILE_ERROR_EXIT_CODE, run.exitCode)
        assertContains(run.stderr, "cannot read --file")
        assertContains(run.stderr, missing.toString())
        assertEquals("", run.stdout, "stdout must stay clean on errors")
        assertNull(receivedBody, "no bridge call may be made when the script cannot be read")
    }

    @Test
    fun `unknown project errors listing the available exposed project names`(
        @TempDir tempDir: Path,
    ) {
        val script = Files.writeString(tempDir.resolve("script.kt"), "println(1)")
        val routing = routingService(
            stateWithProject(tempDir, pid = 42, name = "alpha"),
            stateWithProject(tempDir, pid = 43, name = "beta"),
        )
        val exposedNames = routing.routes().keys

        val run = runExec(
            DevrigCommand.DevrigCommandExecCode(project = "definitely-not-open", file = script.toString()),
            routing,
            tempDir,
        )

        assertEquals(EXEC_CODE_NO_PROJECT_EXIT_CODE, run.exitCode)
        assertContains(run.stderr, "unknown project 'definitely-not-open'")
        for (name in exposedNames) {
            assertContains(run.stderr, name)
        }
        assertEquals("", run.stdout, "stdout must stay clean on errors")
        assertNull(receivedBody, "no bridge call may be made for an unknown project")
    }

    @Test
    fun `no reachable backend exits with the distinct no-project code`(
        @TempDir tempDir: Path,
    ) {
        val script = Files.writeString(tempDir.resolve("script.kt"), "println(1)")
        val routing = routingService() // nothing discovered

        val run = runExec(
            DevrigCommand.DevrigCommandExecCode(project = "my-app", file = script.toString()),
            routing,
            tempDir,
        )

        assertEquals(EXEC_CODE_NO_PROJECT_EXIT_CODE, run.exitCode)
        assertContains(run.stderr, "no IDE backends")
        assertNull(receivedBody)
    }

    // --------------------------- name resolution -----------------------------

    @Test
    fun `raw project name resolves when it identifies exactly one route`(
        @TempDir tempDir: Path,
    ) {
        val script = Files.writeString(tempDir.resolve("script.kt"), "println(1)")
        val routing = routingService(stateWithProject(tempDir, pid = 42, name = "my-app"))

        val run = runExec(
            DevrigCommand.DevrigCommandExecCode(project = "my-app", file = script.toString()),
            routing,
            tempDir,
        )

        assertEquals(0, run.exitCode, run.stderr)
        // The bridge call carries the ORIGINAL name — exactly what the MCP path sends.
        val arguments = McpJson.parseToJsonElement(receivedBody ?: error("missing request body"))
            .jsonObject["arguments"]?.jsonObject ?: error("missing arguments")
        assertEquals("my-app", arguments["project_name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `ambiguous raw project name errors listing the exposed candidates`(
        @TempDir tempDir: Path,
    ) {
        // The SAME project home open in two IDEs — the raw name cannot pick one.
        val home = Files.createDirectories(tempDir.resolve("my-app"))
        val script = Files.writeString(tempDir.resolve("script.kt"), "println(1)")
        val routing = routingService(
            stateForHome(home, pid = 42, name = "my-app"),
            stateForHome(home, pid = 43, name = "my-app"),
        )
        val exposedNames = routing.routes().keys
        assertEquals(2, exposedNames.size)

        val run = runExec(
            DevrigCommand.DevrigCommandExecCode(project = "my-app", file = script.toString()),
            routing,
            tempDir,
        )

        assertEquals(EXEC_CODE_NO_PROJECT_EXIT_CODE, run.exitCode)
        assertContains(run.stderr, "matches")
        for (name in exposedNames) {
            assertContains(run.stderr, name)
        }
        assertNull(receivedBody, "no bridge call may be made for an ambiguous project name")
    }

    @Test
    fun `exposed project name always wins over the raw-name fallback`(
        @TempDir tempDir: Path,
    ) {
        val routing = routingService(
            stateWithProject(tempDir, pid = 42, name = "alpha"),
            stateWithProject(tempDir, pid = 43, name = "beta"),
        )
        val alphaRoute = routing.routes().values.single { it.originalProjectName == "alpha" }

        val resolution = resolveExecCodeProject(alphaRoute.exposedProjectName, routing)

        assertEquals(alphaRoute, assertIs<ExecCodeProjectResolution.Found>(resolution).route)
    }

    // ------------------------------- help ------------------------------------

    @Test
    fun `help text advertises devrig exec-code`() {
        val buf = ByteArrayOutputStream()
        printHelp(PrintStream(buf, true, Charsets.UTF_8))
        val help = buf.toString(Charsets.UTF_8)

        assertContains(help, "devrig exec-code")
        assertContains(help, "--project")
        assertContains(help, "--file")
    }

    // ------------------------------ fixtures ---------------------------------

    private class Run(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runExec(
        command: DevrigCommand.DevrigCommandExecCode,
        routing: DevrigProjectRoutingService,
        tempDir: Path,
    ): Run {
        // The REAL MCP-path handler — :npx-kt:test sets devrig.beacon.disabled=true, so the beacon is inert.
        val handler = DevrigExecuteCodeToolHandler(
            DevrigToolBridgeClient(routing, httpClient),
            DevrigBeacon(HomePaths(tempDir.resolve("beacon-home")), CloseableStackHost()),
        )
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val exitCode = runExecCodeCommand(
            command = command,
            routing = routing,
            handler = handler,
            out = PrintStream(outBuf, true, Charsets.UTF_8),
            err = PrintStream(errBuf, true, Charsets.UTF_8),
        )
        return Run(exitCode, outBuf.toString(Charsets.UTF_8), errBuf.toString(Charsets.UTF_8))
    }

    private fun routingService(vararg states: IdeMonitorState): DevrigProjectRoutingService =
        DevrigProjectRoutingService { states.associateBy { it.ide.pid } }

    private fun stateWithProject(tempDir: Path, pid: Long, name: String): IdeMonitorState =
        stateForHome(Files.createDirectories(tempDir.resolve(name)), pid, name)

    private fun stateForHome(projectHome: Path, pid: Long, name: String): IdeMonitorState =
        IdeMonitorState(
            ide = discoveredIde(pid, projectHome),
            status = IdeMonitorStatus.CONNECTED,
            lastSnapshot = listOf(ProjectInfo(name, projectHome.toString())),
        )

    private fun discoveredIde(pid: Long, projectHome: Path): DiscoveredIde =
        DiscoveredIde(
            pid = pid,
            rpcBaseUrl = testDevrigEndpoint("http://127.0.0.1:$port/mcp").rpcBaseUrl,
            bridgeHeaders = mapOf("Authorization" to "Bearer secret-token"),
            markerPath = projectHome.resolve("$pid.mcp-steroid").toString(),
            marker = PidMarker(
                schema = PidMarker.SCHEMA_VERSION,
                pid = pid,
                mcpSteroidServer = McpSteroidServerInfo(
                    mcpUrl = "http://127.0.0.1:$port/mcp",
                    headers = mapOf("Authorization" to "Bearer secret-token"),
                ),
                devrigEndpoint = testDevrigEndpoint("http://127.0.0.1:$port/mcp", mapOf("Authorization" to "Bearer secret-token")),
                ide = IdeInfo("IntelliJ IDEA", "2026.1", "IU-261.1"),
                plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
                createdAt = "2026-06-12T00:00:00Z",
                intellijWebServer = null,
                intellijMcpServer = null,
            ),
        )
}

private fun freePort(): Int = ServerSocket(0).use { it.localPort }
