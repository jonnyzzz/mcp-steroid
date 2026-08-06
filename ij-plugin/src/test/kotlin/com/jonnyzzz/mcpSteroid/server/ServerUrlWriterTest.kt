/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PidMarkerJson
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit

class ServerUrlWriterTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testWriteCreatesMarkerUnderManagedMarkersDirectory() = withTemporaryUserHome { userHome ->
        val writer = ServerUrlWriter(remoteDevelopmentBackend = true)
        try {
            writer.writeServerUrlToUserHome("http://localhost:6315/mcp")

            val pid = ProcessHandle.current().pid()
            val markerFile = PidMarker.markerDirectory(userHome).resolve(PidMarker.markerFileNameFor(pid))
            assertTrue("marker should be written to $markerFile", Files.isRegularFile(markerFile))
            val marker = PidMarkerJson.decode(Files.readString(markerFile))
            assertEquals(pid, marker.pid)
            assertTrue("marker should identify a Remote Development backend", marker.remoteDevelopmentBackend)
            assertEquals("http://localhost:6315/mcp", marker.mcpSteroidServer!!.mcpUrl)
            // The devrig bridge endpoint is advertised separately from the MCP endpoint: same Ktor base,
            // the /api/jonnyzzz/mcp-steroid/v1 prefix, and the bearer headers devrig must send.
            assertNotNull("devrigEndpoint should be present", marker.devrigEndpoint)
            assertEquals(
                "http://localhost:6315/api/jonnyzzz/mcp-steroid/v1",
                marker.devrigEndpoint!!.rpcBaseUrl,
            )
            assertTrue(
                "devrigEndpoint headers should carry the bearer token",
                marker.devrigEndpoint!!.headers["Authorization"]?.startsWith("Bearer ") == true,
            )
            assertNotNull("IntelliJ built-in web server info should be present", marker.intellijWebServer)
            assertTrue("web server port should be known", marker.intellijWebServer!!.port > 0)
            assertTrue(
                "web server headers should carry the x-ijt token",
                marker.intellijWebServer!!.headers["x-ijt"]?.isNotBlank() == true,
            )
        } finally {
            Disposer.dispose(writer)
        }
    }

    fun testWriteCleansStaleMarkersInManagedDirectory() = withTemporaryUserHome { userHome ->
        val writer = ServerUrlWriter()
        try {
            val deadPid = deadPid()
            val markerDir = PidMarker.markerDirectory(userHome)
            Files.createDirectories(markerDir)
            val staleMarker = markerDir.resolve(PidMarker.markerFileNameFor(deadPid))
            Files.writeString(staleMarker, "stale")

            writer.writeServerUrlToUserHome("http://localhost:6317/mcp")

            assertFalse("stale marker for dead pid should be removed", Files.exists(staleMarker))
            val currentMarker = markerDir.resolve(PidMarker.markerFileNameFor(ProcessHandle.current().pid()))
            assertTrue("current marker should remain", Files.isRegularFile(currentMarker))
        } finally {
            Disposer.dispose(writer)
        }
    }

    private fun withTemporaryUserHome(block: (Path) -> Unit) {
        val originalUserHome = System.getProperty("user.home")
        val userHome = Files.createTempDirectory("server-url-writer-home")
        try {
            System.setProperty("user.home", userHome.toString())
            block(userHome)
        } finally {
            System.setProperty("user.home", originalUserHome)
            deleteRecursively(userHome)
        }
    }

    private fun deadPid(): Long {
        // Spawn a short-lived process to get a PID that's reliably dead by
        // the time the cleanup code runs. `/bin/echo` is fine on Unix; on
        // Windows it doesn't exist — use the always-present `cmd.exe /c rem`
        // (rem is a no-op so the process exits immediately).
        val isWindows = System.getProperty("os.name").lowercase().startsWith("windows")
        val command = if (isWindows) {
            listOf(System.getenv("ComSpec") ?: "cmd.exe", "/c", "rem")
        } else {
            listOf("/bin/echo", "server-url-writer-test")
        }
        val process = ProcessBuilder(command).start()
        check(process.waitFor(5, TimeUnit.SECONDS)) { "short-lived helper process should exit" }
        return process.pid()
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { path ->
                Files.deleteIfExists(path)
            }
        }
    }
}
