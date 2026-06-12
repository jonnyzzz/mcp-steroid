/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.devrig.BackendInventory
import com.jonnyzzz.mcpSteroid.devrig.BackendRow
import com.jonnyzzz.mcpSteroid.devrig.DevrigBeacon
import com.jonnyzzz.mcpSteroid.devrig.HomePaths
import com.jonnyzzz.mcpSteroid.devrig.ManagedBackendInfo
import com.jonnyzzz.mcpSteroid.devrig.ManagedBackendState
import com.jonnyzzz.mcpSteroid.server.backendNameForMarker
import com.jonnyzzz.mcpSteroid.server.hasMcpSteroid
import com.jonnyzzz.mcpSteroid.devrig.backendNameForPort
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.testDevrigEndpoint
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorState
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeMonitorStatus
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.ModalMode
import com.jonnyzzz.mcpSteroid.server.FeedbackParams
import com.jonnyzzz.mcpSteroid.server.InputParams
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.server.OpenProjectParams
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
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
            routing = DevrigProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route, "steroid_execute_code") {
            put("project_name", route.originalProjectName)
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
                ide = discoveredIde(pid = 7, projectHome = projectHome),
                status = IdeMonitorStatus.CONNECTED,
                lastSnapshot = listOf(ProjectInfo("original-project", projectHome.toString())),
            )
        )
        val route = routing.routes().values.single()
        val handler = DevrigExecuteCodeToolHandler(DevrigToolBridgeClient(routing, httpClient), testBeacon(tempDir))

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
                ide = discoveredIde(pid = 42, projectHome = projectHome),
                status = IdeMonitorStatus.CONNECTED,
                lastSnapshot = listOf(ProjectInfo("original-project", projectHome.toString())),
            )
        )
        val route = routing.routes().values.single()
        val handler = DevrigExecuteFeedbackToolHandler(DevrigToolBridgeClient(routing, httpClient), testBeacon(tempDir))

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
                ide = discoveredIde(pid = 42, projectHome = projectHome),
                status = IdeMonitorStatus.CONNECTED,
                lastSnapshot = listOf(ProjectInfo("original-project", projectHome.toString())),
            )
        )
        val route = routing.routes().values.single()
        val handler = DevrigVisionScreenshotToolHandler(DevrigToolBridgeClient(routing, httpClient))

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
                ide = discoveredIde(pid = 42, projectHome = projectA),
                status = IdeMonitorStatus.CONNECTED,
                lastSnapshot = listOf(ProjectInfo("project-a", projectA.toString())),
            ),
            IdeMonitorState(
                ide = discoveredIde(pid = 43, projectHome = projectB),
                status = IdeMonitorStatus.CONNECTED,
                lastSnapshot = listOf(ProjectInfo("project-b", projectB.toString())),
            ),
        )
        val route = routing.routes().values.single { it.idePid == 43L }
        val handler = DevrigVisionInputToolHandler(DevrigToolBridgeClient(routing, httpClient))

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
        val handler = DevrigOpenProjectToolHandler(DevrigToolBridgeClient(routing, httpClient))

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
        val olderHome = Files.createDirectories(tempDir.resolve("older"))
        val newerHome = Files.createDirectories(tempDir.resolve("newer"))
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 42, projectHome = olderHome, build = "IU-253.999", token = "secret-older"),
                status = IdeMonitorStatus.CONNECTED,
            ),
            IdeMonitorState(
                ide = discoveredIde(pid = 43, projectHome = newerHome, build = "IU-261.1", token = "secret-newer"),
                status = IdeMonitorStatus.CONNECTED,
            ),
        )
        val handler = DevrigOpenProjectToolHandler(DevrigToolBridgeClient(routing, httpClient))

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

    @Test
    fun `open project bridge handler prefers the running managed backend over a newer ide`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val userHome = Files.createDirectories(tempDir.resolve("user"))
        val managedHome = Files.createDirectories(tempDir.resolve("managed"))
        val routing = routingService(
            managedPids = setOf(43L),
            IdeMonitorState(
                ide = discoveredIde(pid = 42, projectHome = userHome, build = "IU-261.9", token = "secret-user"),
                status = IdeMonitorStatus.CONNECTED,
            ),
            IdeMonitorState(
                ide = discoveredIde(pid = 43, projectHome = managedHome, build = "IU-253.1", token = "secret-managed"),
                status = IdeMonitorStatus.CONNECTED,
            ),
        )
        val handler = DevrigOpenProjectToolHandler(DevrigToolBridgeClient(routing, httpClient))

        val result = handler.handleOpenProject(
            OpenProjectParams(
                projectPath = tempDir.resolve("target").toString(),
                trustProject = true,
            )
        )

        assertEquals(false, result.isError)
        // pid 42 is the newer build, but pid 43 is the devrig-managed backend — its token must be used.
        assertEquals("Bearer secret-managed", receivedAuth)
    }

    @Test
    fun `open project bridge handler forwards request when exactly one ide is discovered`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val projectHome = Files.createDirectories(tempDir.resolve("project"))
        val targetProject = Files.createDirectories(tempDir.resolve("target"))
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 42, projectHome = projectHome),
                status = IdeMonitorStatus.CONNECTED,
            )
        )
        val handler = DevrigOpenProjectToolHandler(DevrigToolBridgeClient(routing, httpClient))

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
        val homeA = Files.createDirectories(tempDir.resolve("a"))
        val homeB = Files.createDirectories(tempDir.resolve("b"))
        val targetProject = Files.createDirectories(tempDir.resolve("target"))
        val routing = routingService(
            // pid 43 is the newer build (auto-pick would choose it); the backend_name for pid 42 must override.
            IdeMonitorState(
                ide = discoveredIde(pid = 42, projectHome = homeA, build = "IU-253.1", token = "secret-42"),
                status = IdeMonitorStatus.CONNECTED,
            ),
            IdeMonitorState(
                ide = discoveredIde(pid = 43, projectHome = homeB, build = "IU-261.1", token = "secret-43"),
                status = IdeMonitorStatus.CONNECTED,
            ),
        )
        val handler = DevrigOpenProjectToolHandler(DevrigToolBridgeClient(routing, httpClient))

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
        val homeA = Files.createDirectories(tempDir.resolve("a"))
        val homeB = Files.createDirectories(tempDir.resolve("b"))
        val targetProject = Files.createDirectories(tempDir.resolve("target"))
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 42, projectHome = homeA, token = "secret-42"),
                status = IdeMonitorStatus.CONNECTED,
            ),
            IdeMonitorState(
                ide = discoveredIde(pid = 43, projectHome = homeB, token = "secret-43"),
                status = IdeMonitorStatus.CONNECTED,
            ),
        )
        val handler = DevrigOpenProjectToolHandler(DevrigToolBridgeClient(routing, httpClient))

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
        val homeA = Files.createDirectories(tempDir.resolve("a"))
        val targetProject = Files.createDirectories(tempDir.resolve("target"))
        val routing = routingService(
            IdeMonitorState(
                ide = discoveredIde(pid = 42, projectHome = homeA, token = "secret-42"),
                status = IdeMonitorStatus.CONNECTED,
            ),
        )
        val handler = DevrigOpenProjectToolHandler(DevrigToolBridgeClient(routing, httpClient))

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
            ide = discoveredIde(pid = 42, projectHome = homeA, build = "IU-261.1"),
            status = IdeMonitorStatus.CONNECTED,
            lastSnapshot = listOf(ProjectInfo("alpha", homeA.toString())),
        )
        val stateB = IdeMonitorState(
            ide = discoveredIde(pid = 43, projectHome = homeB, build = "IU-253.9"),
            status = IdeMonitorStatus.CONNECTED,
            lastSnapshot = listOf(ProjectInfo("beta", homeB.toString())),
        )
        val routing = routingService(managedPids = setOf(43L), stateA, stateB)
        // The managed flag on backends[] comes from the inventory's managed list correlating by pid.
        val handler = DevrigListProjectsToolHandler(
            routing,
            testInventory(
                states = listOf(stateA, stateB),
                managed = listOf(managedBackendInfo(tempDir, runningPid = 43L, state = ManagedBackendState.RUNNING)),
            ),
        )

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
        for (backend in response.backends) {
            assertTrue(backend.locator.isNotBlank(), "locator must disambiguate: $backend")
            assertEquals("IU", backend.ideProductCode)
            assertEquals(true, backend.routable)
            assertTrue(backend.hasMcpSteroid())
            // openProjects matches exactly the routes for that backend (paths included for worktree matching).
            val expectedPaths = response.projects
                .filter { it.backendName == backend.backendName }
                .map { it.path }
                .toSet()
            assertEquals(expectedPaths, backend.openProjects.map { it.path }.toSet())
        }
        // The managed pid (43) is flagged so the agent can prefer it.
        assertEquals(true, response.backends.single { it.backendName == name43 }.managed)
        assertEquals(false, response.backends.single { it.backendName == name42 }.managed)
    }

    @Test
    fun `CLI project --json and MCP list_projects expose the same project_name for one marker project`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        // A real directory so toRealPath() (used by both code paths to salt the hash) succeeds identically.
        val projectHome = Files.createDirectories(tempDir.resolve("my-app"))
        val ide = discoveredIde(pid = 4242L, projectHome = projectHome, build = "IU-261.1")
        val state = IdeMonitorState(
            ide = ide,
            status = IdeMonitorStatus.CONNECTED,
            lastSnapshot = listOf(ProjectInfo("my-app", projectHome.toString())),
        )
        val routing = DevrigProjectRoutingService { mapOf(4242L to state) }

        // MCP surface.
        val mcpResponse = DevrigListProjectsToolHandler(routing, testInventory(listOf(state)))
            .collectListProjectsResponse()
        val mcpProjectName = mcpResponse.projects.single().projectName

        // CLI surface — the same marker + project rendered by `devrig project --json`.
        val rows = listOf(
            com.jonnyzzz.mcpSteroid.devrig.BackendRow.FromMarker(
                ide = ide,
                projects = listOf(ProjectInfo("my-app", projectHome.toString())),
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
        assertEquals(backendNameForMarker(4242L, "IU-261.1"), cliBackendName)
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
                ide = discoveredIde(pid = 42, projectHome = projectHome),
                status = IdeMonitorStatus.CONNECTED,
                lastSnapshot = listOf(ProjectInfo("original-project", projectHome.toString())),
            )
        )
        val route = routing.routes().values.single()
        val progressMessages = mutableListOf<String>()
        val handler = DevrigExecuteCodeToolHandler(DevrigToolBridgeClient(routing, httpClient), testBeacon(tempDir))

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
                routing = DevrigProjectRoutingService { emptyMap() },
                httpClient = httpClient,
            )
            val result = async {
                bridge.callTool(
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
            routing = DevrigProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir), "steroid_execute_code") {
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
            routing = DevrigProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir), "steroid_execute_code") {
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
            routing = DevrigProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir), "steroid_execute_code") {
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
            routing = DevrigProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir), "steroid_execute_code") {
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
            routing = DevrigProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir), "steroid_execute_code") {
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
            routing = DevrigProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir), "steroid_execute_code") {
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
            routing = DevrigProjectRoutingService { emptyMap() },
            httpClient = httpClient,
        )

        val result = bridge.callTool(route(tempDir), "steroid_execute_code") {
            put("project_name", "original-project")
        }

        assertEquals(false, result.isError)
        assertEquals("ndjson ok", (result.content.single() as ContentItem.Text).text)
    }

    /** Inventory over the same monitor states the routing service sees — the MCP-mode wiring in miniature. */
    private fun testInventory(
        states: List<IdeMonitorState>,
        managed: List<ManagedBackendInfo> = emptyList(),
    ): BackendInventory = BackendInventory(
        markerRows = { states.map { BackendRow.FromMarker(ide = it.ide, projects = it.lastSnapshot) } },
        portIdes = { emptySet() },
        managedBackends = { managed },
        isProcessAlive = { true },
    )

    private fun managedBackendInfo(
        tempDir: Path,
        runningPid: Long?,
        state: ManagedBackendState,
    ): ManagedBackendInfo = ManagedBackendInfo(
        id = "ideaIC-2026.1",
        productKey = "ideaIC",
        productCode = "IC",
        version = "2026.1",
        buildNumber = "261.1",
        installPath = tempDir.resolve("backends/ideaIC-2026.1"),
        cachePath = tempDir.resolve("caches/ideaIC-2026.1"),
        runningPid = runningPid,
        state = state,
    )

    private fun routingService(vararg states: IdeMonitorState): DevrigProjectRoutingService =
        DevrigProjectRoutingService { states.associateBy { it.ide.pid } }

    private fun routingService(
        managedPids: Set<Long>,
        vararg states: IdeMonitorState,
    ): DevrigProjectRoutingService =
        DevrigProjectRoutingService({ states.associateBy { it.ide.pid } }, { managedPids })

    private fun discoveredIde(
        pid: Long,
        projectHome: Path,
        build: String = "IU-261.1",
        token: String = "secret-token",
    ): DiscoveredIde =
        DiscoveredIde(
            pid = pid,
            rpcBaseUrl = testDevrigEndpoint("http://127.0.0.1:$port/mcp").rpcBaseUrl,
            bridgeHeaders = mapOf("Authorization" to "Bearer $token"),
            markerPath = projectHome.resolve("$pid.mcp-steroid").toString(),
            marker = PidMarker(
                schema = PidMarker.SCHEMA_VERSION,
                pid = pid,
                mcpSteroidServer = McpSteroidServerInfo(
                    mcpUrl = "http://127.0.0.1:$port/mcp",
                    headers = mapOf("Authorization" to "Bearer $token"),
                ),
                devrigEndpoint = testDevrigEndpoint("http://127.0.0.1:$port/mcp", mapOf("Authorization" to "Bearer $token")),
                ide = IdeInfo("IntelliJ IDEA", "2026.1", build),
                plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
                createdAt = "2026-05-17T00:00:00Z",
                intellijWebServer = null,
                intellijMcpServer = null,
            ),
        )

    private fun route(tempDir: Path, token: String = "secret-token"): ProjectRoute =
        ProjectRoute(
            idePid = 42,
            bridgeBaseUrl = "http://127.0.0.1:$port/api/jonnyzzz/mcp-steroid/v1",
            headers = mapOf("Authorization" to "Bearer $token"),
            originalProjectName = "original-project",
            exposedProjectName = "original-project-abcdefgh",
            projectPath = tempDir.toString(),
            realProjectHome = tempDir.toRealPath(),
            projectHash = "abcdefgh",
            ide = IdeInfo("IntelliJ IDEA", "2026.1", "IU-261.1"),
            plugin = PluginInfo("com.jonnyzzz.mcp-steroid", "MCP Steroid", "0.0.0-test"),
        )
}

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

private fun ToolCallResult.errorText(): String =
    (content.single() as ContentItem.Text).text
