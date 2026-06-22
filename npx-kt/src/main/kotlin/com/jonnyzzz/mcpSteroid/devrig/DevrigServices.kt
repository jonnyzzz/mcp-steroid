package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.IdePidDiscoveryService
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeProjectMonitorService
import com.jonnyzzz.mcpSteroid.devrig.monitor.IntelliJPortDiscovery
import com.jonnyzzz.mcpSteroid.devrig.server.DevrigProjectRoutingService
import com.jonnyzzz.mcpSteroid.server.NPX_STREAM_IDLE_TIMEOUT_MILLIS
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import java.io.InputStream
import java.io.PrintStream
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DevrigServices(
    private val lifetime: CloseableStack,

    val homePaths: HomePaths,
    val mcpStdin: InputStream,
    val mcpStdout: PrintStream,
) {
    fun lifetime(name: String): CloseableStack = lifetime.nestedStack(name)

    private val backgroundJob = SupervisorJob().also { job ->
        lifetime.registerCleanupAction { job.cancel() }
    }

    val backgroundScope: CoroutineScope =
        CoroutineScope(CoroutineName("devrig-background") + Dispatchers.IO + backgroundJob)

    val backendManager: BackendManager by lazy {
        // Gate download/start on the bundled plugin's plugin.xml build range so we never install or
        // launch an IDE the plugin can't load (it would never become reachable).
        BackendManager(homePaths, pluginBuildRange = bundledPluginBuildRange)
    }

    val backendProvisioner: BackendProvisioner by lazy {
        BackendProvisioner()
    }

    val commandHttpClient: HttpClient by lazy {
        val httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 3_000
                requestTimeoutMillis = 10_000
                socketTimeoutMillis = 10_000
            }
            expectSuccess = false
        }
        lifetime.registerCleanupAction { httpClient.close() }
        httpClient
    }

    val mcpHttpClient: HttpClient by lazy {
        val httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = NPX_STREAM_IDLE_TIMEOUT_MILLIS
                connectTimeoutMillis = 10_000
            }
            expectSuccess = false
        }
        lifetime.registerCleanupAction { httpClient.close() }
        httpClient
    }

    val ideDiscovery: IdePidDiscoveryService by lazy {
        IdePidDiscoveryService(
            markersDir = homePaths.markersDir,
            allowHosts = listOf("localhost", "127.0.0.1", "host.docker.internal"),
        )
    }

    val ideMonitor: IdeProjectMonitorService by lazy {
        IdeProjectMonitorService(
            httpClient = mcpHttpClient,
            discovery = ideDiscovery,
        )
    }

    val projectRouting: DevrigProjectRoutingService by lazy {
        DevrigProjectRoutingService(
            stateProvider = { ideMonitor.stateSnapshot() },
        )
    }

    /**
     * MCP-mode [BackendInventory] for the devrig tool handlers: it consumes [projectRouting] (marker
     * rows + their project routes) and [portDiscovery] (bounded port scan) directly, plus managed rows
     * from [backendManager]. The CLI path reuses the same inventory via [collectBackendRows].
     */
    val backendInventory: BackendInventory by lazy {
        BackendInventory(
            routing = projectRouting,
            portDiscovery = portDiscovery,
            managedBackends = { backendManager.list() },
        )
    }

    val portDiscovery: IntelliJPortDiscovery by lazy {
        IntelliJPortDiscovery(httpClient = commandHttpClient)
    }

    val beacon by lazy {
        DevrigBeacon(homePaths, lifetime)
    }

}
