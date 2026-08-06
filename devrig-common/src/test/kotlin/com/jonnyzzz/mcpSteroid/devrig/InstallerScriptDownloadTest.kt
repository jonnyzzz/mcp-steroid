/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Pins the contract of [downloadInstallerScript] — the ONE download implementation both halves of
 * the product run (devrig's auto-updater and the IDE plugin's install): the cache-buster query, the
 * identifying headers, write-to-file with parent creation, and false-not-throw on every failure.
 */
class InstallerScriptDownloadTest {

    private class RecordedRequest(val query: String?, val userAgent: String?, val cacheControl: String?)

    private fun withServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit, block: (baseUrl: String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange -> exchange.use(handler) }
        server.start()
        try {
            block("http://${server.address.hostString}:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `downloads the script to the target, creating parents, with cache-buster and headers`(@TempDir tmp: Path) {
        val body = "#!/bin/sh\necho devrig installer\n"
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        withServer({ exchange ->
            requests += RecordedRequest(
                query = exchange.requestURI.query,
                userAgent = exchange.requestHeaders.getFirst("User-Agent"),
                cacheControl = exchange.requestHeaders.getFirst("Cache-Control"),
            )
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }) { baseUrl ->
            val target = tmp.resolve("update/nested/install.sh")

            assertTrue(downloadInstallerScript("$baseUrl/install.sh", target, userAgent = "devrig/1.2.3"))

            assertEquals(body, Files.readString(target))
            val request = requests.single()
            // The cache-buster: a retry must reach the origin, not an edge cache of the first attempt.
            assertTrue(request.query.orEmpty().matches(Regex("_=\\d+")), "cache-buster query, got: ${request.query}")
            assertEquals("devrig/1.2.3", request.userAgent)
            assertEquals("no-cache", request.cacheControl)
        }
    }

    @Test
    fun `follows a redirect, as the published URLs may move`(@TempDir tmp: Path) {
        val body = "Write-Host devrig installer\n"
        withServer({ exchange ->
            if (exchange.requestURI.path == "/install.ps1") {
                val host = exchange.requestHeaders.getFirst("Host")
                exchange.responseHeaders.add("Location", "http://$host/moved.ps1")
                exchange.sendResponseHeaders(302, -1)
            } else {
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            }
        }) { baseUrl ->
            val target = tmp.resolve("install.ps1")
            assertTrue(downloadInstallerScript("$baseUrl/install.ps1", target, userAgent = "devrig/test"))
            assertEquals(body, Files.readString(target))
        }
    }

    @Test
    fun `a non-2xx response is false and writes nothing`(@TempDir tmp: Path) {
        withServer({ exchange ->
            exchange.sendResponseHeaders(404, -1)
        }) { baseUrl ->
            val target = tmp.resolve("install.sh")
            assertFalse(downloadInstallerScript("$baseUrl/install.sh", target, userAgent = "devrig/test"))
            assertFalse(target.exists(), "a failed download must not leave a script behind")
        }
    }

    @Test
    fun `a connection failure is false, never a thrown exception`(@TempDir tmp: Path) {
        // Bind-then-close: the port existed a moment ago and now refuses connections.
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val target = tmp.resolve("install.sh")
        assertFalse(downloadInstallerScript("http://127.0.0.1:$port/install.sh", target, userAgent = "devrig/test"))
        assertFalse(target.exists())
    }
}
