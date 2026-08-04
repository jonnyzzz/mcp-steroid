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

    /**
     * The heartbeat must not outlive the service, and the marker must go when the service goes. The
     * deletion is the heartbeat coroutine's own `finally` — dispose() only cancels — so an in-flight
     * tick can never land its write after the delete: cancellation lets the tick finish, then runs the
     * finally. From the outside that makes the delete asynchronous (hence the deadline loop) but
     * race-free: once the marker is gone it must STAY gone.
     */
    fun testDisposeStopsTheHeartbeat() = withTemporaryUserHome { userHome ->
        val originalInterval = ServerUrlWriter.markerHeartbeatMs
        ServerUrlWriter.markerHeartbeatMs = 30
        val scope = CoroutineScope(SupervisorJob())
        val writer = ServerUrlWriter(scope)
        try {
            writer.writeServerUrlToUserHome("http://localhost:6315/mcp")
            val markerFile = PidMarker.markerDirectory(userHome)
                .resolve(PidMarker.markerFileNameFor(ProcessHandle.current().pid()))
            assertTrue("marker should exist after initial write", Files.isRegularFile(markerFile))

            Disposer.dispose(writer)

            // The heartbeat's finally deletes the marker; give the cancelled coroutine a moment.
            val deadline = System.currentTimeMillis() + 5_000
            while (Files.exists(markerFile) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertFalse("disposing the writer must remove this pid's marker", Files.exists(markerFile))

            // No resurrection: nothing may bring the marker back after dispose — not an in-flight
            // tick whose write raced the delete, and not a survivor of the cancelled loop.
            Thread.sleep(300) // ten heartbeat intervals
            assertFalse("a disposed writer must not re-create the marker", Files.exists(markerFile))
        } finally {
            scope.cancel()
            ServerUrlWriter.markerHeartbeatMs = originalInterval
        }
    }

    /**
     * Cancelling the *injected* scope must stop the heartbeat, because that is how the platform shuts an
     * app-level service's coroutines down. `parentScope.coroutineContext + SupervisorJob()` silently broke
     * this: `+` swapped in a parentless Job, so the loop ran on past every cancellation.
     */
    fun testCancellingTheInjectedScopeStopsTheHeartbeat() = withTemporaryUserHome { userHome ->
        val originalInterval = ServerUrlWriter.markerHeartbeatMs
        ServerUrlWriter.markerHeartbeatMs = 30
        val scope = CoroutineScope(SupervisorJob())
        val writer = ServerUrlWriter(scope)
        try {
            writer.writeServerUrlToUserHome("http://localhost:6315/mcp")
            val markerFile = PidMarker.markerDirectory(userHome)
                .resolve(PidMarker.markerFileNameFor(ProcessHandle.current().pid()))
            assertTrue("marker should exist after initial write", Files.isRegularFile(markerFile))

            scope.cancel()
            // The cancelled heartbeat's finally deletes the marker on its own; deleteIfExists keeps
            // this test correct on either side of that race.
            Files.deleteIfExists(markerFile)

            Thread.sleep(300) // ten heartbeat intervals
            assertFalse(
                "the heartbeat must die with the injected scope, not outlive it",
                Files.exists(markerFile),
            )
        } finally {
            Disposer.dispose(writer)
            ServerUrlWriter.markerHeartbeatMs = originalInterval
        }
    }

    /**
     * A second call with a new URL has to move the marker for good. With the URL captured in the
     * heartbeat's lambda, the next tick restored the first one — so the marker silently pointed at a port
     * the server no longer listened on.
     */
    fun testHeartbeatFollowsTheLatestServerUrl() = withTemporaryUserHome { userHome ->
        val originalInterval = ServerUrlWriter.markerHeartbeatMs
        ServerUrlWriter.markerHeartbeatMs = 30
        val scope = CoroutineScope(SupervisorJob())
        val writer = ServerUrlWriter(scope)
        try {
            writer.writeServerUrlToUserHome("http://localhost:6315/mcp")
            writer.writeServerUrlToUserHome("http://localhost:7777/mcp")
            val markerFile = PidMarker.markerDirectory(userHome)
                .resolve(PidMarker.markerFileNameFor(ProcessHandle.current().pid()))

            // Survive several ticks, not just the direct write the second call performed.
            Thread.sleep(300)
            val url = PidMarkerJson.decode(Files.readString(markerFile)).mcpSteroidServer?.mcpUrl
            assertEquals("the heartbeat must re-write the latest URL", "http://localhost:7777/mcp", url)
        } finally {
            Disposer.dispose(writer)
            scope.cancel()
            ServerUrlWriter.markerHeartbeatMs = originalInterval
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
