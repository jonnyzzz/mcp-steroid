/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.monitor

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tests for [IntelliJPortDiscovery]. The IDE side of the probe is
 * impersonated by a tiny Ktor server bound to a known port; the
 * discovery service points at that port range and reports what it
 * finds. Includes:
 *  - a "garbage" port (server returns 200 but non-IDE JSON)
 *  - a "never bound" port (connect refused)
 * so we verify the scanner doesn't crash on non-IDE responses or on
 * absent servers.
 */
class IntelliJPortDiscoveryTest {

    private lateinit var httpClient: HttpClient
    private var idePort: Int = 0
    private var garbagePort: Int = 0
    private var refusedPort: Int = 0
    private lateinit var ideServer: EmbeddedServer<*, *>
    private lateinit var garbageServer: EmbeddedServer<*, *>

    @BeforeEach
    fun setUp() {
        // Bind the fake servers with port=0 so Ktor asks the OS for a free port and keeps it bound.
        // The refused probe uses a fixed reserved port that should never expose an IntelliJ /api/about.
        refusedPort = 1

        ideServer = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/api/about") {
                    call.respondText(
                        """
                        {
                          "name": "IntelliJ IDEA 2025.3 Ultimate",
                          "productName": "IDEA",
                          "edition": "Ultimate",
                          "baselineVersion": 253,
                          "buildNumber": "253.28294.334"
                        }
                        """.trimIndent(),
                        io.ktor.http.ContentType.Application.Json
                    )
                }
            }
        }.start(wait = false)
        idePort = ideServer.resolvedPort()

        garbageServer = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/api/about") {
                    // Looks like JSON but doesn't carry IDE-identifying
                    // fields — must NOT be classified as an IntelliJ.
                    call.respondText(
                        """{"hello":"world"}""",
                        io.ktor.http.ContentType.Application.Json,
                    )
                }
                get("/other") {
                    call.respond(HttpStatusCode.OK, "not relevant")
                }
            }
        }.start(wait = false)
        garbagePort = garbageServer.resolvedPort()

        httpClient = HttpClient(CIO) {
            install(HttpTimeout) { requestTimeoutMillis = 5_000 }
            expectSuccess = false
        }
    }

    @AfterEach
    fun tearDown() {
        httpClient.close()
        ideServer.stop(0L, 0L)
        garbageServer.stop(0L, 0L)
    }

    @Test
    fun `scanOnce detects an IDE on the impersonated port`() = runBlocking {
        val discovery = IntelliJPortDiscovery(
            httpClient = httpClient,
            portRanges = listOf(idePort..idePort, garbagePort..garbagePort, refusedPort..refusedPort),
            probeTimeout = 800.milliseconds,
        )
        val detected = discovery.stateSnapshot()
        assertEquals(1, detected.size, "expected only the IDE port to surface, got: $detected")
        val ide = detected.single()
        assertEquals(idePort, ide.port)
        assertEquals("IDEA", ide.productName)
        assertEquals("IntelliJ IDEA 2025.3 Ultimate", ide.productFullName)
        assertEquals("Ultimate", ide.edition)
        assertEquals(253, ide.baselineVersion)
        assertEquals("253.28294.334", ide.buildNumber)
        assertEquals("http://127.0.0.1:$idePort", ide.baseUrl)
    }

    @Test
    fun `scanOnce rejects non-IDE responses (no productName, no name)`() = runBlocking {
        val discovery = IntelliJPortDiscovery(
            httpClient = httpClient,
            portRanges = listOf(garbagePort..garbagePort),
            probeTimeout = 800.milliseconds,
        )
        val snapshot = discovery.stateSnapshot()
        assertTrue(snapshot.isEmpty(), "garbage port must not appear: $snapshot")
    }

    @Test
    fun `scanOnce tolerates connection-refused without crashing`() = runBlocking {
        val discovery = IntelliJPortDiscovery(
            httpClient = httpClient,
            portRanges = listOf(refusedPort..refusedPort),
            probeTimeout = 800.milliseconds,
        )
        val snapshot = discovery.stateSnapshot()
        assertTrue(snapshot.isEmpty(), "expected empty but was $snapshot")
    }

    @Test
    fun `a port that hangs past the probe timeout does not discard results from fast ports`() = runBlocking {
        // A port whose /api/about never answers within probeTimeout must not
        // cancel the whole scan: the IDE detected on a fast port must still surface.
        val slowServer = embeddedServer(ServerCIO, port = 0, host = "127.0.0.1") {
            routing {
                get("/api/about") {
                    kotlinx.coroutines.delay(10_000)
                    call.respondText("""{"productName":"IDEA"}""", io.ktor.http.ContentType.Application.Json)
                }
            }
        }.start(wait = false)
        val slowPort = slowServer.resolvedPort()
        try {
            val discovery = IntelliJPortDiscovery(
                httpClient = httpClient,
                portRanges = listOf(idePort..idePort, slowPort..slowPort),
                probeTimeout = 400.milliseconds,
            )
            val detected = discovery.stateSnapshot()
            assertEquals(
                setOf(idePort),
                detected.map { it.port }.toSet(),
                "the fast IDE port must surface even though $slowPort hangs; got: $detected",
            )
        } finally {
            slowServer.stop(0L, 0L)
        }
    }

    private fun EmbeddedServer<*, *>.resolvedPort(): Int = runBlocking {
        engine.resolvedConnectors().first().port
    }
}
