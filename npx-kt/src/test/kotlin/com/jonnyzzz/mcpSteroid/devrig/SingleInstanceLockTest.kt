/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

@DisabledOnOs(OS.WINDOWS)
class SingleInstanceLockTest {

    @Test
    fun `start refuses when another managed backend pid file is alive and exits 64`(
        @TempDir tempDir: Path,
    ) {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, id = "idea-community-2025.3.3")
        installStubBackend(homePaths, id = "idea-community-2025.3.2")
        val requestedProcess = startManagedStub(homePaths, "idea-community-2025.3.3")
        val otherProcess = startManagedStub(homePaths, "idea-community-2025.3.2")
        try {
            Files.createDirectories(homePaths.stateDir)
            writeProcessState(homePaths.pidFile("idea-community-2025.3.3"), requestedProcess)
            writeProcessState(homePaths.pidFile("idea-community-2025.3.2"), otherProcess)

            val (exit, stdout, stderr) = captureCli { stdoutStream ->
                runStartCli(homePaths, "idea-community-2025.3.3", stdoutStream)
            }

            assertEquals(64, exit)
            assertEquals("", stdout)
            assertTrue(
                stderr.contains(
                    """
                    error: another managed backend is already running: idea-community-2025.3.2 (pid ${otherProcess.pid()})
                    stop it first:  devrig backend stop idea-community-2025.3.2
                    """.trimIndent(),
                ),
                stderr,
            )
        } finally {
            stopProcess(requestedProcess)
            stopProcess(otherProcess)
        }
    }

    @Test
    fun `start rejects a live unrelated pid file and launches the requested backend`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val id = "idea-community-2025.3.3"
        installStubBackend(homePaths, id = id)
        val pid = ProcessHandle.current().pid()
        Files.createDirectories(homePaths.stateDir)
        Files.writeString(homePaths.pidFile(id), "$pid\n")
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = tempDir.resolve("user-home"),
        )

        val started = manager.start(parseBackendId(id))
        try {
            assertFalse(started.alreadyRunning)
            assertTrue(started.pid > 0)
            assertTrue(started.pid != pid, "the unrelated current JVM pid must not be trusted as the backend")
            val processState = requireNotNull(readManagedBackendProcessState(homePaths.pidFile(id)))
            assertEquals(started.pid, processState.pid)
            assertTrue(processState.startInstant != null)
        } finally {
            manager.stop(parseBackendId(id))
        }
    }

    @Test
    fun `start deletes stale pid files and proceeds`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, id = "idea-community-2025.3.3")
        val dead = ProcessBuilder("sh", "-c", "exit 0").start()
        val deadPid = dead.pid()
        assertTrue(dead.waitFor(5, TimeUnit.SECONDS), "short-lived helper process should exit")
        Files.createDirectories(homePaths.stateDir)
        Files.writeString(homePaths.pidFile("idea-community-2025.3.2"), "$deadPid\n")

        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = tempDir.resolve("user-home"),
        )
        val started = manager.start(parseBackendId("idea-community-2025.3.3"))
        try {
            assertTrue(started.pid > 0)
            assertFalse(homePaths.pidFile("idea-community-2025.3.2").exists(),
                "stale pid file for a different backend must be cleaned during start")
            assertTrue(ProcessHandle.of(started.pid).orElseThrow().isAlive)
        } finally {
            manager.stop(parseBackendId("idea-community-2025.3.3"))
        }
    }

    @Test
    fun `process list fallback refuses untracked process from managed install folder`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, id = "idea-community-2025.3.3")
        installStubBackend(homePaths, id = "idea-community-2025.3.2")
        val orphanCommand = homePaths.backendsDir
            .resolve("idea-community-2025.3.2/idea-IC-253.1/bin/idea.sh")
            .toString()
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = tempDir.resolve("user-home"),
            processInspector = FakeProcessInspector(
                snapshots = listOf(ProcessSnapshot(pid = 4242L, command = orphanCommand)),
            ),
        )

        val error = try {
            manager.start(parseBackendId("idea-community-2025.3.3"))
            fail("start was expected to refuse the untracked managed process")
        } catch (e: ManagedBackendLockException) {
            e.message ?: ""
        }

        assertTrue(error.contains("error: another managed backend is already running: idea-community-2025.3.2 (pid 4242)"), error)
        assertTrue(error.contains("stop it first:  devrig backend stop idea-community-2025.3.2"), error)
        assertTrue(error.contains("cleanup stale state under ${homePaths.stateDir}"), error)
    }

    @Test
    fun `process list fallback repairs pid state for an untracked matching backend`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val id = "idea-community-2025.3.3"
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, id = id)
        val managedCommand = homePaths.backendsDir
            .resolve("$id/idea-IC-253.1/bin/idea.sh")
            .toString()
        val processStartedAt = Instant.parse("2026-07-31T12:00:00Z")
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = tempDir.resolve("user-home"),
            processInspector = FakeProcessInspector(
                snapshots = listOf(
                    ProcessSnapshot(pid = 4242L, command = managedCommand, startInstant = processStartedAt),
                ),
            ),
        )

        val started = manager.start(parseBackendId(id))

        assertTrue(started.alreadyRunning)
        assertEquals(4242L, started.pid)
        val processState = requireNotNull(readManagedBackendProcessState(homePaths.pidFile(id)))
        assertEquals(4242L, processState.pid)
        assertEquals(processStartedAt.toString(), processState.startInstant)
    }

    @Test
    fun `process list ignores an unrelated argument that merely contains a backend path`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val id = "idea-community-2025.3.3"
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, id = id)
        val mentionedPath = homePaths.backendDir(id).resolve("idea-IC-253.1/bin/idea.sh")
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = tempDir.resolve("user-home"),
            processInspector = FakeProcessInspector(
                snapshots = listOf(
                    ProcessSnapshot(
                        pid = 4242L,
                        command = "/bin/echo",
                        arguments = listOf("--message=$mentionedPath", "${0.toChar()}"),
                    ),
                ),
            ),
        )

        val started = manager.start(parseBackendId(id))
        try {
            assertFalse(started.alreadyRunning)
            assertTrue(started.pid > 0)
        } finally {
            manager.stop(parseBackendId(id))
        }
    }

    @Test
    fun `process list ignores an unrelated exact file argument under a managed bundle`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val id = "idea-community-2025.3.3"
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, id = id)
        val unrelatedFile = homePaths.backendDir(id).resolve("idea-IC-253.1/product-info.json")
        Files.writeString(unrelatedFile, "{}")
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = tempDir.resolve("user-home"),
            processInspector = FakeProcessInspector(
                snapshots = listOf(
                    ProcessSnapshot(
                        pid = 4242L,
                        command = "/usr/bin/tail",
                        arguments = listOf("-f", unrelatedFile.toString()),
                    ),
                ),
            ),
        )

        val started = manager.start(parseBackendId(id))
        try {
            assertFalse(started.alreadyRunning)
            assertTrue(started.pid > 0)
        } finally {
            manager.stop(parseBackendId(id))
        }
    }

    @Test
    fun `concurrent starts fail one caller while the global operation lock is held`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, id = "idea-community-2025.3.3")
        val firstScanEntered = CompletableDeferred<Unit>()
        val releaseFirstScan = CompletableDeferred<Unit>()
        val processInspector = BlockingFirstScanInspector(firstScanEntered, releaseFirstScan)
        val firstManager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = tempDir.resolve("user-home"),
            processInspector = processInspector,
        )
        val secondManager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = tempDir.resolve("user-home"),
            processInspector = processInspector,
        )

        val first = async(Dispatchers.Default) {
            firstManager.start(parseBackendId("idea-community-2025.3.3"))
        }
        withTimeout(5.seconds) { firstScanEntered.await() }

        val second = async(Dispatchers.Default) {
            try {
                secondManager.start(parseBackendId("idea-community-2025.3.3"))
                "unexpected success"
            } catch (e: ManagedBackendLockException) {
                e.message ?: ""
            }
        }

        assertEquals("another devrig backend operation is in progress; retry shortly", withTimeout(5.seconds) { second.await() })
        releaseFirstScan.complete(Unit)
        val started = withTimeout(5.seconds) { first.await() }
        try {
            assertTrue(ProcessHandle.of(started.pid).orElseThrow().isAlive)
        } finally {
            firstManager.stop(parseBackendId("idea-community-2025.3.3"))
        }
    }

    @Test
    fun `download and stop refuse while start holds the global operation lock`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val id = "idea-community-2025.3.3"
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, id = id)
        val firstScanEntered = CompletableDeferred<Unit>()
        val releaseFirstScan = CompletableDeferred<Unit>()
        val firstManager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(
                bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test"),
            ),
            ideUserHome = tempDir.resolve("user-home"),
            processInspector = BlockingFirstScanInspector(firstScanEntered, releaseFirstScan),
        )
        val secondManager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(
                bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test"),
            ),
            ideUserHome = tempDir.resolve("user-home"),
        )

        val starting = async(Dispatchers.Default) { firstManager.start(parseBackendId(id)) }
        withTimeout(5.seconds) { firstScanEntered.await() }
        val downloadAttempt = runCatching { secondManager.download(parseBackendId(id)) }
        val stopAttempt = runCatching { secondManager.stop(parseBackendId(id)) }
        releaseFirstScan.complete(Unit)
        val started = withTimeout(5.seconds) { starting.await() }
        try {
            assertEquals(
                "another devrig backend operation is in progress; retry shortly",
                (downloadAttempt.exceptionOrNull() as? ManagedBackendLockException)?.message,
            )
            assertEquals(
                "another devrig backend operation is in progress; retry shortly",
                (stopAttempt.exceptionOrNull() as? ManagedBackendLockException)?.message,
            )
        } finally {
            firstManager.stop(parseBackendId(id))
        }
        assertFalse(ProcessHandle.of(started.pid).map { it.isAlive }.orElse(false))
    }

    private fun captureCli(block: (PrintStream) -> Int): CliCapture {
        val originalOut = System.out
        val originalErr = System.err
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        return try {
            val stdout = PrintStream(out, true, Charsets.UTF_8)
            System.setOut(stdout)
            System.setErr(PrintStream(err, true, Charsets.UTF_8))
            CliCapture(
                exit = block(stdout),
                stdout = out.toString(Charsets.UTF_8),
                stderr = err.toString(Charsets.UTF_8),
            )
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    private data class CliCapture(
        val exit: Int,
        val stdout: String,
        val stderr: String,
    )

    private fun installStubBackend(homePaths: HomePaths, id: String) {
        val backendDir = homePaths.backendDir(id)
        val bundleDir = backendDir.resolve("idea-IC-253.1")
        val launcher = bundleDir.resolve("bin/idea.sh")
        Files.createDirectories(launcher.parent)
        Files.writeString(launcher, gracefulLauncher())
        launcher.toFile().setExecutable(true, false)
        writeDescriptor(
            descriptorPath(backendDir),
            BackendDescriptor(
                id = id,
                productKey = "idea-community",
                productCode = "IC",
                version = id.removePrefix("idea-community-"),
                buildNumber = "IC-253.1",
                bundleDirName = bundleDir.fileName.toString(),
                launcherPath = "bin/idea.sh",
                downloadedAt = "2026-05-14T21:00:00Z",
            ),
        )
    }

    private fun runStartCli(homePaths: HomePaths, id: String, stdout: PrintStream): Int {
        val lifetime = CloseableStackHost()
        return try {
            runBlocking {
                DevrigServices(
                    homePaths = homePaths,
                    lifetime = lifetime,
                    mcpStdin = ByteArrayInputStream(ByteArray(0)),
                    mcpStdout = stdout,
                ).runCli(DevrigCommand.DevrigCommandBackendStart(id = id))
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }

    private fun gracefulLauncher(): String =
        """
        #!/usr/bin/env sh
        trap 'exit 0' TERM
        while true; do sleep 1; done
        """.trimIndent() + "\n"

    private fun startManagedStub(homePaths: HomePaths, id: String): Process =
        ProcessBuilder(
            homePaths.backendDir(id).resolve("idea-IC-253.1/bin/idea.sh").toString(),
        ).start()

    private fun writeProcessState(path: Path, process: Process) {
        val state = ManagedBackendProcessState(
            pid = process.pid(),
            startInstant = process.toHandle().info().startInstant().orElseThrow().toString(),
        )
        Files.writeString(path, Json.encodeToString(state) + "\n")
    }

    private fun stopProcess(process: Process) {
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private class FakeProcessInspector(
        private val snapshots: List<ProcessSnapshot>,
    ) : ManagedProcessInspector {
        override fun isAlive(pid: Long): Boolean =
            snapshots.any { it.pid == pid } || DefaultManagedProcessInspector.isAlive(pid)

        override fun allProcesses(): List<ProcessSnapshot> = snapshots

        override fun snapshot(pid: Long): ProcessSnapshot? =
            snapshots.firstOrNull { it.pid == pid } ?: DefaultManagedProcessInspector.snapshot(pid)
    }

    private class BlockingFirstScanInspector(
        private val firstScanEntered: CompletableDeferred<Unit>,
        private val releaseFirstScan: CompletableDeferred<Unit>,
    ) : ManagedProcessInspector {
        private val firstScan = AtomicBoolean(true)

        override fun isAlive(pid: Long): Boolean =
            ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

        override fun allProcesses(): List<ProcessSnapshot> {
            if (firstScan.compareAndSet(true, false)) {
                firstScanEntered.complete(Unit)
                runBlocking {
                    withTimeout(5.seconds) { releaseFirstScan.await() }
                }
            }
            return emptyList()
        }

        override fun snapshot(pid: Long): ProcessSnapshot? = DefaultManagedProcessInspector.snapshot(pid)
    }

    private object StaticDownloader : ManagedBackendDownloader {
        override suspend fun resolve(id: BackendId): BackendDownloadResolution =
            BackendDownloadResolution(
                product = IdeProduct.IntelliJIdeaCommunity,
                version = id.version ?: "2025.3.3",
                build = "IC-253.1",
                url = "file:///unused",
            )

        override suspend fun downloadAndUnpack(
            resolution: BackendDownloadResolution,
            targetDir: Path,
        ): BackendDownloadArtifact = error("downloadAndUnpack should not be called by single-instance tests")
    }
}
