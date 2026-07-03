/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.registry.Registry
import com.jonnyzzz.mcpSteroid.aiAgents.claudeMcpAddCommand
import com.jonnyzzz.mcpSteroid.mcp.*
import com.jonnyzzz.mcpSteroid.prompts.generated.McpSteroidInfoPrompt
import com.jonnyzzz.mcpSteroid.prompts.generated.prompt.SkillPromptArticle
import com.jonnyzzz.mcpSteroid.updates.analyticsBeacon
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import kotlinx.coroutines.*
import org.jetbrains.annotations.TestOnly
import java.net.BindException
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * MCP Server application service.
 * Manages the Ktor-based MCP server lifecycle with custom MCP implementation.
 */
@Service(Service.Level.APP)
class SteroidsMcpServer(
    parentScope: CoroutineScope,
) : Disposable {
    private val log = thisLogger()

    private val serverRef = AtomicReference<EmbeddedServer<*, *>?>(null)
    private val portRef = AtomicReference(0)
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob() + Dispatchers.IO)
    private val startupLock = ReentrantLock()
    private val pluginVersion = com.jonnyzzz.mcpSteroid.PluginDescriptorProvider.getInstance().version

    val port: Int get() = portRef.get()
    val mcpUrl: String get() = "http://localhost:$port/mcp"

    /**
     * Get the underlying MCP server core for testing or tool registration.
     */
    fun getServer(): McpServerCore = mcpServer

    private val mcpServer = McpServerCore(
        serverInfo = ServerInfo(
            name = "mcp-steroid",
            version = pluginVersion,
        ),
        instructions = buildServerInstructions(),
        capabilities = ServerCapabilities(
            tools = ToolsCapability(listChanged = true),
            // No Prompts/Resources capability is advertised: mcp-steroid:// articles
            // are reachable only via the steroid_fetch_resource MCP tool (it requires
            // project_name for correct IDE-conditional rendering, which the generic
            // resources/prompts protocol methods can't carry). McpServerCore still
            // routes prompts/list + resources/list to empty registries if a client
            // calls them anyway, so misbehaving clients get a clean empty result
            // rather than a method-not-found error.
        ),
        // Fire a once-per-session analytics beacon recording client (agent) + server versions.
        onSessionInitialized = { session, serverInfo, clientProtocolVersion ->
            analyticsBeacon.capture(
                event = "mcp_session_initialized",
                properties = mapOf(
                    "client_name" to (session.clientInfo?.name ?: "unknown"),
                    "client_version" to (session.clientInfo?.version ?: "unknown"),
                    "client_protocol_version" to clientProtocolVersion,
                    "server_name" to serverInfo.name,
                    "server_version" to serverInfo.version,
                    "mcp_protocol_version" to MCP_PROTOCOL_VERSION,
                )
            )
        }
    )

    fun startServerIfNeeded() {
        // Fast check without lock
        if (port > 0) return

        // Synchronize startup to handle concurrent calls from multiple projects
        startupLock.withLock {
            // Double-check after acquiring lock
            if (port > 0) return

            // Register all MCP tools explicitly (no extension point).
            // mcp-steroid:// articles are NOT exposed via resources/list or
            // prompts/list — the steroid_fetch_resource tool is the only path
            // because it requires project_name for correct IDE-conditional rendering.
            val tools = service<McpSteroidToolsIJ>()
            tools.registerAll(mcpServer)
            // The in-IDE plugin is a single backend, so its steroid_open_project advertises
            // NO `backend_name` routing param (includeBackendName = false). devrig registers its
            // own backend_name-carrying spec. registerAll() no longer registers open_project.
            mcpServer.toolRegistry.registerTool(
                OpenProjectToolSpec(includeBackendName = false) { tools.handler<OpenProjectToolHandler>() }
            )

            val configuredPort = Registry.intValue("mcp.steroid.server.port")

            // By default, bind to localhost only per MCP security requirements.
            // For Docker testing, set mcp.steroid.server.host to "0.0.0.0"
            val bindHost = Registry.stringValue("mcp.steroid.server.host").takeIf { it.isNotBlank() } ?: "127.0.0.1"

            // Record the IDE run mode before the bind attempt, so it lands in idea.log
            // even if all ports are busy. Headless is unsupported (best-effort) — see #177.
            logIdeRunMode()

            // Try to start on configured port, fall back to next ports if busy
            val actualPort = startServerOnAvailablePort(bindHost, configuredPort)
            if (actualPort > 0) {
                log.info("MCP Steroid server started on $mcpUrl")
                log.info("Note: If you restart IntelliJ, connected MCP clients (Claude CLI, etc.) will need to reconnect.")
                log.info("      Client should re-run: ${claudeMcpAddCommand(mcpUrl)}")
            }
        }
    }

    /**
     * Logs the detected IDE run mode (INFO, always) plus a WARN when the IDE is plain headless —
     * an unsupported (best-effort) environment for MCP Steroid (see mcp-steroid#177).
     * Detection and logging only; the server still starts in every mode.
     */
    private fun logIdeRunMode() {
        val mode = detectIdeRunMode()
        log.info(ideRunModeLogLine(mode))
        if (mode == IdeRunMode.HEADLESS) {
            log.warn(HEADLESS_UNSUPPORTED_WARNING)
        }
    }

    /**
     * Builds the MCP server instructions; when the IDE is plain headless, a one-line notice is
     * appended so the connected agent knows the environment is unsupported (see mcp-steroid#177).
     */
    private fun buildServerInstructions(): String {
        val instructions = McpSteroidInfoPrompt().readPrompt()
        if (detectIdeRunMode() != IdeRunMode.HEADLESS) return instructions
        return instructions + "\n\n" + HEADLESS_MCP_CLIENT_NOTICE
    }

    /**
     * Tries to start the server on the given port. If the port is busy,
     * tries subsequent ports up to MAX_PORT_RETRIES times.
     * If configuredPort is 0, finds a free port automatically.
     *
     * @return the actual port the server started on, or 0 if failed
     */
    private fun startServerOnAvailablePort(bindHost: String, configuredPort: Int): Int {
        if (configuredPort == 0) {
            // Dynamic port allocation requested
            val freePort = findFreePort()
            return tryStartServer(bindHost, freePort)
        }

        val portRetries = 10
        // Try configured port and subsequent ports
        for (attempt in 0 until portRetries) {
            val portToTry = configuredPort + attempt
            val result = tryStartServer(bindHost, portToTry)
            if (result > 0) {
                if (attempt > 0) {
                    log.info("Port $configuredPort was busy, started on port $portToTry instead")
                }
                return result
            }
        }

        log.warn("Failed to start MCP server: all ports from $configuredPort to ${configuredPort + portRetries - 1} are busy")
        return 0
    }

    /**
     * Attempts to start the server on the specified port.
     *
     * @return the port if successful, 0 if the port is busy, throws on other errors
     */
    private fun tryStartServer(bindHost: String, portToTry: Int): Int {
        // Pre-check if port is available to avoid async BindException from Ktor CIO
        if (!isPortAvailable(bindHost, portToTry)) {
            log.info("Port $portToTry is busy, will try next port")
            return 0
        }

        try {
            val server = createKtorServer(bindHost, portToTry)

            val startupMutex = ReentrantLock()
            startupMutex.lock()
            server.application.monitor.subscribe(ApplicationStarted) {
                startupMutex.unlock()
            }

            log.info("Starting MCP Steroid server on $bindHost:$portToTry")
            server.start(wait = false)

            // Wait for Ktor to be ready
            startupMutex.lock()

            // Server started successfully
            serverRef.set(server)
            portRef.set(portToTry)
            return portToTry
        } catch (e: CancellationException) {
            // Control-flow exception - rethrow, do not log
            throw e
        } catch (e: Exception) {
            // Check if the root cause is BindException (port busy)
            if (isPortBusyException(e)) {
                log.info("Port $portToTry is busy, will try next port")
                return 0
            }
            // Other exception - log and rethrow
            log.error("Failed to start MCP server on port $portToTry: ${e.message}", e)
            throw e
        }
    }

    /**
     * Creates a Ktor embedded server with all MCP routes and plugins configured.
     */
    private fun createKtorServer(bindHost: String, port: Int): EmbeddedServer<*, *> {
        // Ktor CIO defaults `connectionIdleTimeoutSeconds = 45`, which the writer loop
        // also uses as the per-response wait — long-running tool calls (Maven test runs,
        // gradle builds, indexing-dependent searches) can easily exceed that, causing
        // the request scope to be cancelled mid-script with `StandaloneCoroutine was
        // cancelled`. We raise it to 600s to match `mcp.steroid.execution.timeout`.
        val applicationProperties = serverConfig(applicationEnvironment {}) {
            this.parentCoroutineContext = scope.coroutineContext
            module { mcpModule() }
        }
        return embeddedServer(
            factory = CIO,
            rootConfig = applicationProperties,
            configure = {
                connectionIdleTimeoutSeconds = 600
                connectors.add(
                    EngineConnectorBuilder().apply {
                        this.host = bindHost
                        this.port = port
                    }
                )
            },
        )
    }

    private fun Application.mcpModule() {
        install(requestLoggingPlugin)
        install(SSE)
        routing {
                with(McpHttpTransport) {
                    installMcp("/mcp", mcpServer)
                }
                installNpxBridgeRoutes(
                    serverCoreProvider = { mcpServer },
                    mcpUrlProvider = { mcpUrl }
                )
                get("/.well-known/mcp.json") {
                    // Use request local info to build correct URL for the client
                    val local = call.request.local
                    call.respondText(
                        contentType = ContentType.Application.Json,
                        text = buildWellKnownMcpJson(local.scheme, local.serverHost, local.serverPort)
                    )
                }
                // Agent Skills endpoints - serve SKILL.md at root and /skill.md
                get("/") {
                    call.respondText(
                        contentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8),
                        text = SkillPromptArticle().readPayload(idePromptsContext())
                    )
                }
                get("/skill.md") {
                    call.respondText(
                        contentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8),
                        text = SkillPromptArticle().readPayload(idePromptsContext())
                    )
                }
                get("/SKILL.md") {
                    call.respondText(
                        contentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8),
                        text = SkillPromptArticle().readPayload(idePromptsContext())
                    )
                }
        }
    }

    /**
     * Checks if a port is available for binding.
     */
    private fun isPortAvailable(host: String, port: Int): Boolean {
        return try {
            val address = if (host == "0.0.0.0") null else java.net.InetAddress.getByName(host)
            ServerSocket(port, 1, address).use { true }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks if the exception indicates the port is already in use.
     */
    private fun isPortBusyException(e: Throwable): Boolean {
        var current: Throwable? = e
        while (current != null) {
            if (current is BindException) {
                return true
            }
            // Also check for common messages
            val msg = current.message?.lowercase() ?: ""
            if (msg.contains("address already in use") || msg.contains("address in use")) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun findFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private fun buildWellKnownMcpJson(scheme: String, host: String, port: Int): String {
        // Use the request's origin to build the correct URL for the client
        return """
            {
                "mcpServers": {
                    "mcp-steroid": {
                        "url": "$scheme://$host:$port/mcp"
                    }
                }
            }
        """.trimIndent()
    }

    @TestOnly
    private fun restartServerOnSamePortForTest(bindHost: String, port: Int): Int {
        require(port > 0) { "Cannot restart test server on non-positive port: $port" }

        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadlineNanos) {
            val started = tryStartServer(bindHost, port)
            if (started == port) return started
            Thread.sleep(100)
        }

        throw IllegalStateException("Failed to restart MCP server on original port $port during test reset")
    }

    override fun dispose() {
        scope.cancel()
        serverRef.get()?.stop(1000, 2000)
        log.info("MCP Steroid server stopped")
    }

    /**
     * Stops the current Ktor server, closes all MCP sessions, and starts a fresh server.
     * This truly breaks all existing HTTP connections and invalidates all sessions.
     */
    @TestOnly
    fun forgetAllForTest() {
        startupLock.withLock {
            // 1. Close all MCP sessions
            mcpServer.sessionManager.forgetAllSessionsForTest()

            // 2. Stop the current Ktor server (breaks all HTTP connections)
            val server = serverRef.getAndSet(null) ?: return
            val previousPort = portRef.getAndSet(0)
            server.stop(1000, 2000)
            log.info("Stopped MCP server on port $previousPort for test reset")

            // 3. Restart on the same port
            val bindHost = Registry.stringValue("mcp.steroid.server.host").takeIf { it.isNotBlank() } ?: "127.0.0.1"
            val actualPort = restartServerOnSamePortForTest(bindHost, previousPort)
            log.info("Restarted MCP server on port $actualPort for test reset")
        }
    }

    private val requestLoggingPlugin get() = createApplicationPlugin("SteroidsMcpRequestLogger") {
        onCall { call ->
            val startedAt = System.nanoTime()
            val method = call.request.httpMethod.value
            val uri = call.request.uri
            val remoteHost = call.request.local.remoteHost
            log.info("[MCP-HTTP] <- $method $uri from $remoteHost")

            call.response.pipeline.intercept(ApplicationSendPipeline.After) {
                val status = call.response.status() ?: HttpStatusCode.OK
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
                log.info("[MCP-HTTP] -> ${status.value} ${status.description} for $method $uri in ${elapsedMs}ms")
            }
        }
    }

    companion object {
        fun getInstance(): SteroidsMcpServer = ApplicationManager.getApplication().service()
    }
}
