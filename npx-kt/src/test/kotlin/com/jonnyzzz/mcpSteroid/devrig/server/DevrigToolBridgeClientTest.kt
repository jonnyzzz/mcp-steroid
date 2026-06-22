/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.devrig.BackendRow
import com.jonnyzzz.mcpSteroid.devrig.DevrigBeacon
import com.jonnyzzz.mcpSteroid.devrig.HomePaths
import com.jonnyzzz.mcpSteroid.server.backendNameForMarker
import com.jonnyzzz.mcpSteroid.devrig.backendNameForPort
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.testDevrigEndpoint
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.ModalMode
import com.jonnyzzz.mcpSteroid.server.FeedbackParams
import com.jonnyzzz.mcpSteroid.server.InputParams
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.OpenProjectParams
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeProjectState
import com.jonnyzzz.mcpSteroid.server.ScreenshotParams
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

class DevrigToolBridgeClientTest {
    // A no-op beacon for handler construction: :npx-kt:test sets devrig.beacon.disabled=true so PostHog
    // never initializes and capture/captureScore are inert (no network).
    private fun testBeacon(tempDir: Path) = DevrigBeacon(HomePaths(tempDir.resolve("beacon-home")), CloseableStackHost())

    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var httpClient: HttpClient
    private var port: Int = 0
    private var receivedAuth: String? = null
    private var receivedBody: String? = null
    private val beforeResultEvents = mutableListOf<String>()
    private var streamResponse: String? = null
    private var holdStreamEntered: CompletableDeferred<Unit>? = null
    private var holdStreamRelease: CompletableDeferred<Unit>? = null
    private var httpStatus = HttpStatusCode.OK
    private var httpBody = ""

    @BeforeEach
    fun setUp() {
        port = freePort()
        server = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            routing {
                post("/api/jonnyzzz/mcp-steroid/v1/tools/call/stream") {
                    receivedAuth = call.request.headers["Authorization"]
                    receivedBody = call.receiveText()
                    if (httpStatus != HttpStatusCode.OK) {
                        call.respondText(httpBody, ContentType.Text.Plain, httpStatus)
                        return@post
                    }
                    call.respondTextWriter(ContentType.parse("application/x-ndjson")) {
                        val custom = streamResponse
                        if (custom != null) {
                            write(custom)
                            flush()
                            return@respondTextWriter
                        }
                        val hold = holdStreamRelease
                        if (hold != null) {
                            write("""{"type":"progress","message":"started"}""" + "\n")
                            flush()
                            holdStreamEntered?.complete(Unit)
                            hold.await()
                            return@respondTextWriter
                        }
                        beforeResultEvents.forEach { write(it) }
                        val result = ToolCallResult(
                            content = listOf(ContentItem.Text("ok")),
                            isError = false,
                        )
                        write(
                            buildJsonObject {
                                put("type", "result")
                                put("result", McpJson.encodeToJsonElement(ToolCallResult.serializer(), result))
                            }.toString() + "\n"
                        )
                        flush()
                    }
                }
            }
        }.also { it.start(wait = false) }
        runBlocking { server.monitor.subscribe(ApplicationStarted) {} }

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
        beforeResultEvents.clear()
        streamResponse = null
        holdStreamEntered = null
        holdStreamRelease = null
        httpStatus = HttpStatusCode.OK
        httpBody = ""
        httpClient.close()
        server.stop(0L, 0L)
    }

    @Test
    fun `bridge client sends bearer token and rewritten original project name`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val route = route(tempDir)
        val bridge = DevrigToolBridgeClient(
            httpClient = httpClient,
        )

        val result = bridge.callProjectTool(route, "steroid_execute_code") {
            put("task_id", "task")
            put("reason", "test")
            put("code", "println(1)")
        }

        assertEquals(false, result.isError)
        assertEquals("Bearer secret-token", receivedAuth)
        val json = McpJson.parseToJsonElement(receivedBody ?: error("missing request body")).jsonObject
        assertEquals("steroid_execute_code", json["name"]?.jsonPrimitive?.content)
        assertEquals(
            "original-project",
            json["arguments"]?.jsonObject?.get("project_name")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `execute_code handler pins the full devrig to plugin param contract`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 7),
                projects = listOf(IdeProjectState("original-project", projectHome.toString())),
            )
        )
        val route = routing.routes().single()
        val handler = DevrigExecuteCodeToolHandler(DevrigToolBridgeClient(httpClient), routing, testBeacon(tempDir))

        val result = handler.executeCode(
            projectName = route.exposedProjectName,
            execCodeParams = ExecCodeParams(
                taskId = "ec-task",
                code = "println(1)",
                reason = "verify contract",
                timeout = 42,
                modal = ModalMode.UNLEASHED,
            ),
            callProgress = object : McpProgressReporter { override fun report(message: String) = Unit },
        )

        assertEquals(false, result.isError)
        // Frozen, additive-only wire contract — these exact param names/types are sent to EVERY plugin
        // version. Never remove/rename/retype; new params must be optional with safe defaults.
        val arguments = McpJson.parseToJsonElement(receivedBody ?: error("missing request body"))
            .jsonObject["arguments"]?.jsonObject ?: error("missing arguments")
        assertEquals("steroid_execute_code", McpJson.parseToJsonElement(receivedBody!!).jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("original-project", arguments["project_name"]?.jsonPrimitive?.content)
        assertEquals("println(1)", arguments["code"]?.jsonPrimitive?.content)
        assertEquals("ec-task", arguments["task_id"]?.jsonPrimitive?.content)
        assertEquals("verify contract", arguments["reason"]?.jsonPrimitive?.content)
        assertEquals(42, arguments["timeout"]?.jsonPrimitive?.content?.toInt())
        assertEquals("unleashed", arguments["modal"]?.jsonPrimitive?.content)
    }

    @Test
    fun `execute feedback bridge handler forwards rating explanation and code`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 42),
                projects = listOf(IdeProjectState("original-project", projectHome.toString())),
            )
        )
        val route = routing.routes().single()
        val handler = DevrigExecuteFeedbackToolHandler(DevrigToolBridgeClient(httpClient),routing, testBeacon(tempDir))

        val result = handler.handleFeedback(
            projectName = route.exposedProjectName,
            params = FeedbackParams(
                taskId = "feedback-task",
                successRating = 0.75,
                explanation = "worked",
                code = "println(1)",
            ),
        )

        assertEquals(false, result.isError)
        val json = McpJson.parseToJsonElement(receivedBody ?: error("missing request body")).jsonObject
        assertEquals("steroid_execute_feedback", json["name"]?.jsonPrimitive?.content)
        val arguments = json["arguments"]?.jsonObject ?: error("missing arguments: $json")
        assertEquals("original-project", arguments["project_name"]?.jsonPrimitive?.content)
        assertEquals("feedback-task", arguments["task_id"]?.jsonPrimitive?.content)
        assertEquals("0.75", arguments["success_rating"]?.jsonPrimitive?.content)
        assertEquals("worked", arguments["explanation"]?.jsonPrimitive?.content)
        assertEquals("println(1)", arguments["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `screenshot bridge handler forwards request`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 42),
                projects = listOf(IdeProjectState("original-project", projectHome.toString())),
            )
        )
        val route = routing.routes().single()
        val handler = DevrigVisionScreenshotToolHandler(DevrigToolBridgeClient(httpClient), routing)

        val result = handler.screenshotWindow(
            projectName = route.exposedProjectName,
            screenshotParams = ScreenshotParams(
                taskId = "screenshot-task",
                reason = "capture state",
            ),
            mcpProgressReporter = object : McpProgressReporter {
                override fun report(message: String) = Unit
            },
        )

        assertEquals(false, result.isError)
        val json = McpJson.parseToJsonElement(receivedBody ?: error("missing request body")).jsonObject
        assertEquals("steroid_take_screenshot", json["name"]?.jsonPrimitive?.content)
        val arguments = json["arguments"]?.jsonObject ?: error("missing arguments: $json")
        assertEquals("original-project", arguments["project_name"]?.jsonPrimitive?.content)
        assertEquals("screenshot-task", arguments["task_id"]?.jsonPrimitive?.content)
        assertEquals("capture state", arguments["reason"]?.jsonPrimitive?.content)
        assertEquals(null, arguments["window_id"])
    }

    @Test
    fun `input bridge handler resolves ide by project name and forwards window id unchanged`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val projectA = Files.createDirectories(tempDir.resolve("project-a"))
        val projectB = Files.createDirectories(tempDir.resolve("project-b"))
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 42),
                projects = listOf(IdeProjectState("project-a", projectA.toString())),
            ),
            IdeMonitorState(
                ide = discoveredIde(pid = 43),
                projects = listOf(IdeProjectState("project-b", projectB.toString())),
            ),
        )
        val route = routing.routes().single { it.route.pid == 43L }
        val handler = DevrigVisionInputToolHandler(DevrigToolBridgeClient(httpClient), routing)

        val result = handler.handleInputSequence(
            projectName = route.exposedProjectName,
            inputParams = InputParams(
                taskId = "input-task",
                reason = "press key",
                windowId = "frame-b",
                sequence = emptyList(),
                rawSequence = "press:ENTER",
            ),
        )

        assertEquals(false, result.isError)
        val json = McpJson.parseToJsonElement(receivedBody ?: error("missing request body")).jsonObject
        assertEquals("steroid_input", json["name"]?.jsonPrimitive?.content)
        val arguments = json["arguments"]?.jsonObject ?: error("missing arguments: $json")
        assertEquals("project-b", arguments["project_name"]?.jsonPrimitive?.content)
        assertEquals("input-task", arguments["task_id"]?.jsonPrimitive?.content)
        assertEquals("press key", arguments["reason"]?.jsonPrimitive?.content)
        assertEquals("frame-b", arguments["window_id"]?.jsonPrimitive?.content)
        assertEquals("press:ENTER", arguments["sequence"]?.jsonPrimitive?.content)
    }

    @Test
    fun `open project bridge handler rejects zero discovered ides`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val routing = routingService()
        val handler = DevrigOpenProjectToolHandler(DevrigToolBridgeClient(httpClient), routing)

        val result = handler.handleOpenProject(
            OpenProjectParams(
                projectPath = tempDir.resolve("target").toString(),
                trustProject = true,
            )
        )

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("requires at least one discovered IDE"))
        assertEquals(null, receivedAuth)
        assertEquals(null, receivedBody)
    }

    @Test
    fun `open project bridge handler forwards to the newest ide when several are discovered`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 42, build = "IU-253.999", token = "secret-older"),
            ),
            IdeMonitorState(
                ide = discoveredIde(pid = 43, build = "IU-261.1", token = "secret-newer"),
            ),
        )
        val handler = DevrigOpenProjectToolHandler(DevrigToolBridgeClient(httpClient), routing)

        val result = handler.handleOpenProject(
            OpenProjectParams(
                projectPath = tempDir.resolve("target").toString(),
                trustProject = true,
            )
        )

        assertEquals(false, result.isError)
        // The newest build (IU-261.1) wins, so its bearer token is the one forwarded.
        assertEquals("Bearer secret-newer", receivedAuth)
        val json = McpJson.parseToJsonElement(receivedBody ?: error("missing request body")).jsonObject
        assertEquals("steroid_open_project", json["name"]?.jsonPrimitive?.content)
    }

    // NOTE: the former `prefers the running managed backend over a newer ide` test was removed with the
    // managed-pid open_project preference (DevrigProjectRoutingService.openProjectTargetIde / managedRunningPids).
    // open_project now picks the newest discovered IDE, or the IDE named by an explicit backend_name.

    @Test
    fun `open project bridge handler forwards request when exactly one ide is discovered`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val targetProject = Files.createDirectories(tempDir.resolve("target"))
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 42),
            )
        )
        val handler = DevrigOpenProjectToolHandler(DevrigToolBridgeClient(httpClient), routing)

        val result = handler.handleOpenProject(
            OpenProjectParams(
                projectPath = targetProject.toString(),
                trustProject = false,
            )
        )

        assertEquals(false, result.isError)
        assertEquals("Bearer secret-token", receivedAuth)
        val json = McpJson.parseToJsonElement(receivedBody ?: error("missing request body")).jsonObject
        assertEquals("steroid_open_project", json["name"]?.jsonPrimitive?.content)
        val arguments = json["arguments"]?.jsonObject ?: error("missing arguments: $json")
        assertEquals(targetProject.toString(), arguments["project_path"]?.jsonPrimitive?.content)
        assertEquals("false", arguments["trust_project"]?.jsonPrimitive?.content)
        assertEquals("open-project", arguments["task_id"]?.jsonPrimitive?.content)
        assertEquals("Open project through devrig", arguments["reason"]?.jsonPrimitive?.content)
    }

    @Test
    fun `open project routes to the backend named by backend_name and does not forward it`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val targetProject = Files.createDirectories(tempDir.resolve("target"))
        val routing = routingService(
            // pid 43 is the newer build (auto-pick would choose it); the backend_name for pid 42 must override.
            IdeMonitorState(
                ide = discoveredIde(pid = 42, build = "IU-253.1", token = "secret-42"),
            ),
            IdeMonitorState(
                ide = discoveredIde(pid = 43, build = "IU-261.1", token = "secret-43"),
            ),
        )
        val handler = DevrigOpenProjectToolHandler(DevrigToolBridgeClient(httpClient), routing)

        val result = handler.handleOpenProject(
            OpenProjectParams(
                projectPath = targetProject.toString(),
                trustProject = true,
                backendName = backendNameForMarker(42L, "IU-253.1"),
            )
        )

        assertEquals(false, result.isError)
        // backend_name overrode the auto-pick: the POST hit pid 42's bridge (its token), not the newer pid 43.
        assertEquals("Bearer secret-42", receivedAuth)
        val json = McpJson.parseToJsonElement(receivedBody ?: error("missing request body")).jsonObject
        assertEquals("steroid_open_project", json["name"]?.jsonPrimitive?.content)
        val arguments = json["arguments"]?.jsonObject ?: error("missing arguments: $json")
        // The forwarded args are byte-identical to today: backend_name is resolved locally, never forwarded.
        assertEquals(targetProject.toString(), arguments["project_path"]?.jsonPrimitive?.content)
        assertEquals("true", arguments["trust_project"]?.jsonPrimitive?.content)
        assertEquals("open-project", arguments["task_id"]?.jsonPrimitive?.content)
        assertEquals("Open project through devrig", arguments["reason"]?.jsonPrimitive?.content)
        assertEquals(null, arguments["backend_name"])
    }

    @Test
    fun `open project with unknown backend_name returns an error listing routable backends`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val targetProject = Files.createDirectories(tempDir.resolve("target"))
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 42, token = "secret-42"),
            ),
            IdeMonitorState(
                ide = discoveredIde(pid = 43, token = "secret-43"),
            ),
        )
        val handler = DevrigOpenProjectToolHandler(DevrigToolBridgeClient(httpClient), routing)

        val unknown = backendNameForMarker(999L, "IU-261.1")
        val result = handler.handleOpenProject(
            OpenProjectParams(
                projectPath = targetProject.toString(),
                trustProject = true,
                backendName = unknown,
            )
        )

        assertEquals(true, result.isError)
        val message = result.errorText()
        assertTrue(message.contains("Unknown backend_name '$unknown'"), message)
        // Self-correcting: the agent can read the routable backend_names and retry.
        assertTrue(message.contains(backendNameForMarker(42L, "IU-261.1")), message)
        assertTrue(message.contains(backendNameForMarker(43L, "IU-261.1")), message)
        // No bridge call was made.
        assertEquals(null, receivedAuth)
        assertEquals(null, receivedBody)
    }

    @Test
    fun `open project with a non-routable backend_name explains only running plugin IDEs are routable`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val targetProject = Files.createDirectories(tempDir.resolve("target"))
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 42, token = "secret-42"),
            ),
        )
        val handler = DevrigOpenProjectToolHandler(DevrigToolBridgeClient(httpClient), routing)

        // A backend_name the agent might have copied from `devrig backend --json` for a port-only / managed
        // backend: it is not a routable marker, so resolveBackend misses and the error self-corrects.
        val portishName = backendNameForPort(63342, "IU-253.21581.142")
        val result = handler.handleOpenProject(
            OpenProjectParams(
                projectPath = targetProject.toString(),
                trustProject = true,
                backendName = portishName,
            )
        )

        assertEquals(true, result.isError)
        val message = result.errorText()
        assertTrue(message.contains("Unknown backend_name '$portishName'"), message)
        assertTrue(message.contains("Only running IDEs with the MCP Steroid plugin"), message)
        // The routable marker is listed so the agent can retry.
        assertTrue(message.contains(backendNameForMarker(42L, "IU-261.1")), message)
        assertEquals(null, receivedBody)
    }

    @Test
    fun `devrig list_projects tags each project with its backend and lists routable backends`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homeA = Files.createDirectories(tempDir.resolve("a"))
        val homeB = Files.createDirectories(tempDir.resolve("b"))
        val stateA = IdeMonitorState(
            ide = discoveredIde(pid = 42, build = "IU-261.1"),
            projects = listOf(IdeProjectState("alpha", homeA.toString())),
        )
        val stateB = IdeMonitorState(
            ide = discoveredIde(pid = 43, build = "IU-253.9"),
            projects = listOf(IdeProjectState("beta", homeB.toString())),
        )
        val routing = routingService(stateA, stateB)
        val handler = DevrigListProjectsToolHandler(routing)

        val response = handler.collectListProjectsResponse()

        val name42 = backendNameForMarker(42L, "IU-261.1")
        val name43 = backendNameForMarker(43L, "IU-253.9")

        // Every project carries its owning backend_name, and that id is one of the listed backends.
        assertTrue(response.projects.all { it.backendName != null })
        assertEquals(
            response.backends.map { it.backendName }.toSet(),
            response.projects.mapNotNull { it.backendName }.toSet(),
        )
        assertEquals(setOf(name42, name43), response.backends.map { it.backendName }.toSet())
        // Each project also carries the devrig-exposed project_name (and the raw name for jq consumers).
        assertEquals(setOf("alpha", "beta"), response.projects.map { it.name }.toSet())
        assertTrue(response.projects.all { it.projectName.isNotBlank() })
        // Each listed backend carries the routable IDE's identity (display name + build/version).
        for (backend in response.backends) {
            assertEquals("IntelliJ IDEA", backend.displayName, "display name identifies the IDE: $backend")
            assertTrue(!backend.version.isNullOrBlank(), "version must be populated: $backend")
            assertTrue(!backend.build.isNullOrBlank(), "build must be populated: $backend")
        }
        assertEquals("IU-261.1", response.backends.single { it.backendName == name42 }.build)
        assertEquals("IU-253.9", response.backends.single { it.backendName == name43 }.build)
    }

    @Test
    fun `CLI project --json and MCP list_projects expose the same project_name for one marker project`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        // A real directory so toRealPath() (used by both code paths to salt the hash) succeeds identically.
        val projectHome = Files.createDirectories(tempDir.resolve("my-app"))
        val pid = 4242L
        val ide = discoveredIde(pid = pid, build = "IU-261.1")
        val state = IdeMonitorState(
            ide = ide,
            projects = listOf(IdeProjectState("my-app", projectHome.toString())),
        )
        val routing = DevrigProjectRoutingService { listOf(state) }

        // MCP surface.
        val mcpResponse = DevrigListProjectsToolHandler(routing)
            .collectListProjectsResponse()
        val mcpProjectName = mcpResponse.projects.single().projectName

        // CLI surface — the same marker + project rendered by `devrig project --json`. Build the row from
        // the SAME routing the MCP surface used, so both sides carry identical ProjectRoutes by construction.
        val rows = listOf(
            BackendRow.FromMarker(
                ide = ide,
                projects = routing.routes(),
            ),
        )
        val cliJson = java.io.ByteArrayOutputStream().let { buf ->
            com.jonnyzzz.mcpSteroid.devrig.renderProjectJson(
                com.jonnyzzz.mcpSteroid.devrig.projectListingFromRows(rows),
                java.io.PrintStream(buf, true, Charsets.UTF_8),
            )
            buf.toString(Charsets.UTF_8)
        }
        val cliProject = McpJson.parseToJsonElement(cliJson).jsonObject["projects"]!!.jsonArray.single().jsonObject
        val cliProjectName = cliProject["project_name"]!!.jsonPrimitive.content
        val cliBackendName = cliProject["backend_name"]!!.jsonPrimitive.content

        assertEquals(mcpProjectName, cliProjectName, "CLI and MCP must expose the same project_name")
        assertTrue(mcpProjectName.startsWith("my-app-"), mcpProjectName)
        // ...and the same owning backend_name.
        assertEquals(mcpResponse.projects.single().backendName, cliBackendName)
        assertEquals(backendNameForMarker(pid, "IU-261.1"), cliBackendName)
    }

    @Test
    fun `execute code bridge handler forwards timeout dialog killer and progress events`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        beforeResultEvents += """{"type":"heartbeat","message":"ignored"}""" + "\n"
        beforeResultEvents += """{"type":"future","message":"ignored"}""" + "\n"
        beforeResultEvents += """{"type":"progress","message":"compile started"}""" + "\n"
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 42),
                projects = listOf(IdeProjectState("original-project", projectHome.toString())),
            )
        )
        val route = routing.routes().single()
        val progressMessages = mutableListOf<String>()
        val handler = DevrigExecuteCodeToolHandler(DevrigToolBridgeClient(httpClient), routing, testBeacon(tempDir))

        val result = handler.executeCode(
            projectName = route.exposedProjectName,
            execCodeParams = ExecCodeParams(
                taskId = "exec-task",
                code = "println(1)",
                reason = "verify execute-code argument and progress forwarding",
                timeout = 17,
                modal = ModalMode.SMART_NON_MODAL,
            ),
            callProgress = object : McpProgressReporter {
                override fun report(message: String) {
                    progressMessages += message
                }
            },
        )

        assertEquals(false, result.isError)
        assertEquals(listOf("compile started"), progressMessages)
        val json = McpJson.parseToJsonElement(receivedBody ?: error("missing request body")).jsonObject
        assertEquals("steroid_execute_code", json["name"]?.jsonPrimitive?.content)
        val arguments = json["arguments"]?.jsonObject ?: error("missing arguments: $json")
        assertEquals("original-project", arguments["project_name"]?.jsonPrimitive?.content)
        assertEquals("exec-task", arguments["task_id"]?.jsonPrimitive?.content)
        assertEquals("17", arguments["timeout"]?.jsonPrimitive?.content)
        assertEquals("smart_non_modal", arguments["modal"]?.jsonPrimitive?.content)
    }

    @Test
    fun `bridge client propagates cancellation while waiting for NDJSON result`(
        @TempDir tempDir: Path,
    ) {
        runBlocking {
            holdStreamEntered = CompletableDeferred()
            holdStreamRelease = CompletableDeferred()
            val bridge = DevrigToolBridgeClient(
                httpClient = httpClient,
            )
            val result = async {
                bridge.callProjectTool(
                    route(tempDir),
                    "steroid_execute_code",
                    object : McpProgressReporter {
                        override fun report(message: String) = Unit
                    },
                ) {
                    put("project_name", "original-project")
                }
            }

            withTimeout(5.seconds) {
                holdStreamEntered?.await()
            }
            result.cancel()
            try {
                assertFailsWith<CancellationException> {
                    result.await()
                }
            } finally {
                holdStreamRelease?.complete(Unit)
            }
        }
    }

    @Test
    fun `bridge client returns error result for malformed NDJSON data`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        streamResponse = "{not json}\n"
        val bridge = DevrigToolBridgeClient(
            httpClient = httpClient,
        )

        val result = bridge.callProjectTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("Malformed NDJSON data"))
    }

    @Test
    fun `bridge client returns error result for NDJSON error message`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        streamResponse = """{"type":"error","message":"upstream failed"}""" + "\n"
        val bridge = DevrigToolBridgeClient(
            httpClient = httpClient,
        )

        val result = bridge.callProjectTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("upstream failed"))
    }

    @Test
    fun `bridge client returns error result for upstream 401`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        httpStatus = HttpStatusCode.Unauthorized
        httpBody = "bad token"
        val bridge = DevrigToolBridgeClient(
            httpClient = httpClient,
        )

        val result = bridge.callProjectTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("HTTP 401"))
        assertTrue(result.errorText().contains("/api/jonnyzzz/mcp-steroid/v1/tools/call/stream"))
        assertTrue(result.errorText().contains("bad token"))
    }

    @Test
    fun `bridge client returns error result for upstream 500`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        httpStatus = HttpStatusCode.InternalServerError
        httpBody = "bridge exploded"
        val bridge = DevrigToolBridgeClient(
            httpClient = httpClient,
        )

        val result = bridge.callProjectTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("HTTP 500"))
        assertTrue(result.errorText().contains("bridge exploded"))
    }

    @Test
    fun `bridge client returns error result when result message has no result field`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        streamResponse = """{"type":"result"}""" + "\n"
        val bridge = DevrigToolBridgeClient(
            httpClient = httpClient,
        )

        val result = bridge.callProjectTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("result message did not include result"))
    }

    @Test
    fun `bridge client returns error result when NDJSON stream closes without result`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        streamResponse = """{"type":"progress","message":"still running"}""" + "\n"
        val bridge = DevrigToolBridgeClient(
            httpClient = httpClient,
        )

        val result = bridge.callProjectTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(true, result.isError)
        assertTrue(result.errorText().contains("No result received"))
    }

    @Test
    fun `bridge client ignores heartbeat and unknown NDJSON messages before result`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val resultBody = ToolCallResult(
            content = listOf(ContentItem.Text("ndjson ok")),
            isError = false,
        )
        streamResponse = """{"type":"heartbeat"}""" + "\n" +
                """{"type":"future","message":"ignored"}""" + "\n" +
                """{"type":"result","result": ${McpJson.encodeToJsonElement(ToolCallResult.serializer(), resultBody)}}""" + "\n"
        val bridge = DevrigToolBridgeClient(
            httpClient = httpClient,
        )

        val result = bridge.callProjectTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(false, result.isError)
        assertEquals("ndjson ok", (result.content.single() as ContentItem.Text).text)
    }

    private fun routingService(vararg states: IdeMonitorState): DevrigProjectRoutingService =
        DevrigProjectRoutingService { states.toList() }

    private fun discoveredIde(
        pid: Long,
        build: String = "IU-261.1",
        token: String = "secret-token",
    ): DiscoveredIde =
        DiscoveredIde(
            backendName = backendNameForMarker(pid, build),
            pid = pid,
            rpcBaseUrl = testDevrigEndpoint("http://127.0.0.1:$port/mcp").rpcBaseUrl,
            bridgeHeaders = mapOf("Authorization" to "Bearer $token"),
            ide = IdeInfo("IntelliJ IDEA", "2026.1", build),
            plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
        )

    private fun route(tempDir: Path, token: String = "secret-token"): ProjectRoute =
        ProjectRoute(
            route = DiscoveredIde(
                backendName = backendNameForMarker(42L, "IU-261.1"),
                pid = 42,
                rpcBaseUrl = "http://127.0.0.1:$port/api/jonnyzzz/mcp-steroid/v1",
                bridgeHeaders = mapOf("Authorization" to "Bearer $token"),
                ide = IdeInfo("IntelliJ IDEA", "2026.1", "IU-261.1"),
                plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
            ),
            projectInfo = IdeProjectState("original-project", tempDir.toString()),
            exposedProjectName = "original-project-abcdefgh",
            projectPath = tempDir.toString(),
        )
}

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

private fun ToolCallResult.errorText(): String =
    (content.single() as ContentItem.Text).text
