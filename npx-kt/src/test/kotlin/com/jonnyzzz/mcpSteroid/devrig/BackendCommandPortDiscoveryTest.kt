/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.IntelliJPortDiscovery
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.time.Duration.Companion.milliseconds

/**
 * Wire-level coverage for the `backend` subcommand's port-scan integration.
 * Pins the bug the user hit: an IDE running with the IntelliJ built-in HTTP
 * server reachable, but no `.mcp-steroid` marker — `backend` MUST still find
 * it.
 *
 * Strategy: pick a free port, stand up an in-process Ktor server on it that
 * answers `/api/about` exactly like IntelliJ does, point a custom
 * [IntelliJPortDiscovery] at that port (via the `portRanges` constructor
 * parameter), then drive [collectPortDiscoveredIdes].
 */
class BackendCommandPortDiscoveryTest {

    private var port: Int = 0
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var httpClient: HttpClient

    /** Body the fake `/api/about` will return — tests vary this per case. */
    private var aboutBody: String =
        """
        {
          "name": "IntelliJ IDEA",
          "productName": "IDEA",
          "edition": "IU",
          "baselineVersion": 253,
          "buildNumber": "IU-253.21581.142"
        }
        """.trimIndent()

    @BeforeEach
    fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        server = embeddedServer(ServerCIO, port = port, host = "127.0.0.1") {
            routing {
                get("/api/about") {
                    call.respondText(aboutBody, ContentType.Application.Json)
                }
            }
        }.also { it.start(wait = false) }
        runBlocking { server.monitor.subscribe(ApplicationStarted) {} }

        httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 2_000
                requestTimeoutMillis = 3_000
                socketTimeoutMillis = 3_000
            }
            expectSuccess = false
        }
    }

    @AfterEach
    fun tearDown() {
        httpClient.close()
        server.stop(0L, 0L)
    }

    private fun discoveryWith(vararg ports: Int): IntelliJPortDiscovery =
        IntelliJPortDiscovery(
            httpClient = httpClient,
            portRanges = ports.map { it..it },
            probeTimeout = 1500.milliseconds,
            parallelism = 4,
        )

    // ----------------------- the user's bug --------------------------------

    @Test
    fun `collectPortDiscoveredIdes finds an IDE with NO marker via its api-about endpoint`() = runBlocking {
        val discovery = discoveryWith(port)
        val found = collectPortDiscoveredIdes(discovery)
        assertEquals(1, found.size, "expected exactly one IDE on port $port; got: $found")
        val ide = found.single()
        assertEquals(port, ide.port)
        assertEquals("IntelliJ IDEA", ide.productFullName)
        assertEquals("IDEA", ide.productName)
        assertEquals("IU", ide.edition)
        assertEquals(253, ide.baselineVersion)
        assertEquals("IU-253.21581.142", ide.buildNumber)
    }

    @Test
    fun `port discovery returns empty when no port in range responds`() = runBlocking {
        // A free port nobody listens on. Discovery must NOT block; the
        // probeTimeout caps the scan.
        val deadPort = ServerSocket(0).use { it.localPort }
        val discovery = discoveryWith(deadPort)
        val found = collectPortDiscoveredIdes(discovery)
        assertEquals(emptySet<Any>(), found, "expected no IDE on a free port; got: $found")
    }

    @Test
    fun `port discovery survives a non-IDE HTTP server returning unrelated JSON`() = runBlocking {
        // Some other localhost service returns "name": null + "productName": null
        // (e.g. a different /api/about-style endpoint). Discovery must reject
        // it, not throw.
        aboutBody = """{"name": null, "productName": null, "buildNumber": "what-1.2"}"""
        val discovery = discoveryWith(port)
        val found = collectPortDiscoveredIdes(discovery)
        assertEquals(emptySet<Any>(), found,
            "ambiguous /api/about (no name fields) must be rejected; got: $found")
    }

    @Test
    fun `port discovery survives api-about returning malformed JSON`() = runBlocking {
        aboutBody = "this is not json"
        val discovery = discoveryWith(port)
        val found = collectPortDiscoveredIdes(discovery)
        assertEquals(emptySet<Any>(), found, "malformed JSON must NOT crash; got: $found")
    }

    @Test
    fun `port discovery accepts a response that only has the short productName`() = runBlocking {
        // Older IDEs return only `productName` (no `name`). Pin that case so a
        // future change to the discriminator (productName == null && name == null)
        // doesn't regress this combo.
        aboutBody = """{"productName": "GoLand", "buildNumber": "GO-253.0.0"}"""
        val discovery = discoveryWith(port)
        val ide = collectPortDiscoveredIdes(discovery).singleOrNull()
        assertNotNull(ide, "short productName must be enough to identify an IDE")
        assertEquals("GoLand", ide!!.productName)
        assertTrue(ide.productFullName == null, "no name => productFullName stays null")
    }
}
