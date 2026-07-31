/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PidMarkerJson
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@DisabledOnOs(OS.WINDOWS)
class BackendManagerStartStopTest {

    @Test
    fun `start launches native remote development server with run argument`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val argumentsFile = tempDir.resolve("remote-dev-arguments.txt")
        val userHome = tempDir.resolve("user-home")
        val bundleDir = homePaths.backendDir("idea-ultimate-2026.2.0.1").resolve("idea-IU-262.8665.337")
        installStubBackend(
            homePaths = homePaths,
            id = "idea-ultimate-2026.2.0.1",
            productKey = "idea-ultimate",
            productCode = "IU",
            buildNumber = "IU-262.8665.337",
            launcherBody = gracefulLauncher(),
            remoteDevelopmentLauncherBody = remoteArgumentRecordingLauncher(
                argumentsFile = argumentsFile,
                markerDirectory = PidMarker.markerDirectory(userHome),
                ideHome = bundleDir,
                pluginHome = homePaths.cacheDir("idea-ultimate-2026.2.0.1").resolve("plugins/mcp-steroid"),
            ),
        )
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = userHome,
        )

        val started = manager.start(parseBackendId("idea-ultimate-2026.2.0.1"))
        try {
            withTimeout(5.seconds) {
                while (!argumentsFile.exists()) delay(50.milliseconds)
            }
            assertEquals("1\nrun\nfalse\n1\n1\n", Files.readString(argumentsFile))
        } finally {
            manager.stop(parseBackendId("idea-ultimate-2026.2.0.1"))
        }
        assertFalse(ProcessHandle.of(started.pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `remote development start waits for marker and persists handed-off backend pid`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val id = "idea-ultimate-2026.2.0.1"
        val build = "IU-262.8665.337"
        val homePaths = HomePaths(tempDir.resolve("home"))
        val userHome = tempDir.resolve("user-home")
        val bundleDir = homePaths.backendDir(id).resolve("idea-$build")
        val launcherPidFile = tempDir.resolve("launcher.pid")
        installStubBackend(
            homePaths = homePaths,
            id = id,
            productKey = "idea-ultimate",
            productCode = "IU",
            buildNumber = build,
            launcherBody = gracefulLauncher(),
            remoteDevelopmentLauncherBody = handoffRemoteDevelopmentLauncher(
                launcherPidFile = launcherPidFile,
                markerDirectory = PidMarker.markerDirectory(userHome),
                ideHome = bundleDir,
                pluginHome = homePaths.cacheDir(id).resolve("plugins/mcp-steroid"),
            ),
        )
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = userHome,
            remoteDevelopmentStartupTimeoutMillis = 5_000,
        )

        val started = manager.start(parseBackendId(id))
        try {
            val launcherPid = Files.readString(launcherPidFile).trim().toLong()
            assertNotEquals(launcherPid, started.pid, "start must return the marker/backend pid, not the exited launcher pid")
            val processState = requireNotNull(readManagedBackendProcessState(homePaths.pidFile(id)))
            assertEquals(started.pid, processState.pid)
            assertTrue(processState.startInstant != null)
            assertTrue(ProcessHandle.of(started.pid).orElseThrow().isAlive)
            val markerPath = PidMarker.markerDirectory(userHome).resolve(PidMarker.markerFileNameFor(started.pid))
            assertEquals(started.pid, PidMarkerJson.decode(Files.readString(markerPath)).pid)
        } finally {
            manager.stop(parseBackendId(id))
        }
        assertFalse(ProcessHandle.of(started.pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `remote development handoff terminates a surviving launcher`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val id = "idea-ultimate-2026.2.0.1"
        val build = "IU-262.8665.337"
        val homePaths = HomePaths(tempDir.resolve("home"))
        val userHome = tempDir.resolve("user-home")
        val bundleDir = homePaths.backendDir(id).resolve("idea-$build")
        val launcherPidFile = tempDir.resolve("launcher.pid")
        installStubBackend(
            homePaths = homePaths,
            id = id,
            productKey = "idea-ultimate",
            productCode = "IU",
            buildNumber = build,
            launcherBody = gracefulLauncher(),
            remoteDevelopmentLauncherBody = persistentHandoffRemoteDevelopmentLauncher(
                launcherPidFile = launcherPidFile,
                markerDirectory = PidMarker.markerDirectory(userHome),
                ideHome = bundleDir,
                pluginHome = homePaths.cacheDir(id).resolve("plugins/mcp-steroid"),
            ),
        )
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(
                bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test"),
            ),
            ideUserHome = userHome,
            remoteDevelopmentStartupTimeoutMillis = 5_000,
        )

        val started = manager.start(parseBackendId(id))
        val launcherPid = Files.readString(launcherPidFile).trim().toLong()
        val launcher = ProcessHandle.of(launcherPid)
        try {
            assertNotEquals(launcherPid, started.pid)
            assertFalse(
                launcher.map { it.isAlive }.orElse(false),
                "the launcher must not survive after readiness hands ownership to a different backend pid",
            )
            assertTrue(ProcessHandle.of(started.pid).orElseThrow().isAlive)
        } finally {
            manager.stop(parseBackendId(id))
            launcher.filter { it.isAlive }.ifPresent { handle ->
                handle.destroyForcibly()
                handle.onExit().get(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `remote development start fails clearly when no ready marker appears`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val id = "idea-ultimate-2026.2.0.1"
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(
            homePaths = homePaths,
            id = id,
            productKey = "idea-ultimate",
            productCode = "IU",
            buildNumber = "IU-262.8665.337",
            launcherBody = gracefulLauncher(),
            remoteDevelopmentLauncherBody = gracefulLauncher(),
        )
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = tempDir.resolve("user-home"),
            remoteDevelopmentStartupTimeoutMillis = 300,
        )

        val error = assertFailsWith<ManagedBackendValidationException> {
            manager.start(parseBackendId(id))
        }

        assertTrue(error.message!!.contains("Timed out waiting for the MCP Steroid readiness marker"), error.message)
        assertTrue(error.message!!.contains(id), error.message)
        assertFalse(homePaths.pidFile(id).exists(), "an unready launcher pid must never be persisted")
    }

    @Test
    fun `failed remote development start retries process discovery before leaving cleanup`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val id = "idea-ultimate-2026.2.0.1"
        val homePaths = HomePaths(tempDir.resolve("home"))
        val launcherPidFile = tempDir.resolve("launcher.pid")
        installStubBackend(
            homePaths = homePaths,
            id = id,
            productKey = "idea-ultimate",
            productCode = "IU",
            buildNumber = "IU-262.8665.337",
            launcherBody = gracefulLauncher(),
            remoteDevelopmentLauncherBody = pidRecordingLauncher(launcherPidFile),
        )
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(
                bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test"),
            ),
            processInspector = HideFirstVisibleLauncherInspector(launcherPidFile),
            ideUserHome = tempDir.resolve("user-home"),
            remoteDevelopmentStartupTimeoutMillis = 100,
        )

        assertFailsWith<ManagedBackendValidationException> {
            manager.start(parseBackendId(id))
        }
        val launcherPid = Files.readString(launcherPidFile).trim().toLong()
        val launcher = ProcessHandle.of(launcherPid)
        try {
            assertFalse(
                launcher.map { it.isAlive }.orElse(false),
                "cleanup must rescan when the first process snapshot misses the launcher",
            )
        } finally {
            launcher.filter { it.isAlive }.ifPresent { handle ->
                handle.destroyForcibly()
                handle.onExit().get(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `failed remote development cleanup refuses a pid whose start identity changed after discovery`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val id = "idea-ultimate-2026.2.0.1"
        val homePaths = HomePaths(tempDir.resolve("home"))
        val launcherPidFile = tempDir.resolve("launcher.pid")
        installStubBackend(
            homePaths = homePaths,
            id = id,
            productKey = "idea-ultimate",
            productCode = "IU",
            buildNumber = "IU-262.8665.337",
            launcherBody = gracefulLauncher(),
            remoteDevelopmentLauncherBody = pidRecordingLauncher(launcherPidFile),
        )
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(
                bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test"),
            ),
            processInspector = ChangedStartIdentityInspector(launcherPidFile),
            ideUserHome = tempDir.resolve("user-home"),
            remoteDevelopmentStartupTimeoutMillis = 100,
        )

        assertFailsWith<ManagedBackendValidationException> {
            manager.start(parseBackendId(id))
        }
        val launcherPid = Files.readString(launcherPidFile).trim().toLong()
        val launcher = ProcessHandle.of(launcherPid)
        try {
            assertTrue(
                launcher.map { it.isAlive }.orElse(false),
                "cleanup must revalidate process start identity immediately before signalling a discovered pid",
            )
        } finally {
            launcher.filter { it.isAlive }.ifPresent { handle ->
                handle.destroyForcibly()
                handle.onExit().get(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `remote development marker decode warning never logs marker contents`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val id = "idea-ultimate-2026.2.0.1"
        val secretSentinel = "SECRET_SENTINEL"
        val homePaths = HomePaths(tempDir.resolve("home"))
        val userHome = tempDir.resolve("user-home")
        installStubBackend(
            homePaths = homePaths,
            id = id,
            productKey = "idea-ultimate",
            productCode = "IU",
            buildNumber = "IU-262.8665.337",
            launcherBody = gracefulLauncher(),
            remoteDevelopmentLauncherBody = gracefulLauncher(),
        )
        val markerDirectory = PidMarker.markerDirectory(userHome)
        Files.createDirectories(markerDirectory)
        val markerPath = markerDirectory.resolve(PidMarker.markerFileNameFor(999_999L))
        Files.writeString(markerPath, "{\"authorization\":\"Bearer $secretSentinel\",")
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = userHome,
            remoteDevelopmentStartupTimeoutMillis = 100,
        )

        val stderr = captureStderr {
            assertFailsWith<ManagedBackendValidationException> {
                manager.start(parseBackendId(id))
            }
        }

        assertTrue(stderr.contains(markerPath.toString()), stderr)
        assertTrue(stderr.contains("JsonDecodingException"), stderr)
        assertFalse(stderr.contains(secretSentinel), stderr)
    }

    @Test
    fun `remote development start rejects marker from an unmanaged plugin path`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val id = "idea-ultimate-2026.2.0.1"
        val homePaths = HomePaths(tempDir.resolve("home"))
        val userHome = tempDir.resolve("user-home")
        val bundleDir = homePaths.backendDir(id).resolve("idea-IU-262.8665.337")
        installStubBackend(
            homePaths = homePaths,
            id = id,
            productKey = "idea-ultimate",
            productCode = "IU",
            buildNumber = "IU-262.8665.337",
            launcherBody = gracefulLauncher(),
            remoteDevelopmentLauncherBody = remoteArgumentRecordingLauncher(
                argumentsFile = tempDir.resolve("remote-dev-arguments.txt"),
                markerDirectory = PidMarker.markerDirectory(userHome),
                ideHome = bundleDir,
                pluginHome = tempDir.resolve("unmanaged-plugin"),
            ),
        )
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(
                bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test"),
            ),
            ideUserHome = userHome,
            remoteDevelopmentStartupTimeoutMillis = 300,
        )

        val error = assertFailsWith<ManagedBackendValidationException> {
            manager.start(parseBackendId(id))
        }

        assertTrue(error.message!!.contains("Timed out waiting for the MCP Steroid readiness marker"), error.message)
        assertFalse(homePaths.pidFile(id).exists(), "a marker for a different plugin install must not be persisted")
    }

    @Test
    fun `remote development start ignores a live matching marker that predates the launch`(
        @TempDir tempDir: Path,
    ): Unit = kotlinx.coroutines.runBlocking {
        val id = "idea-ultimate-2026.2.0.1"
        val build = "IU-262.8665.337"
        val homePaths = HomePaths(tempDir.resolve("home"))
        val userHome = tempDir.resolve("user-home")
        val markerDirectory = PidMarker.markerDirectory(userHome)
        val bundleDir = homePaths.backendDir(id).resolve("idea-$build")
        val pluginHome = homePaths.cacheDir(id).resolve("plugins/mcp-steroid")
        val argumentsFile = tempDir.resolve("remote-args.txt")
        installStubBackend(
            homePaths = homePaths,
            id = id,
            productKey = "idea-ultimate",
            productCode = "IU",
            buildNumber = build,
            launcherBody = gracefulLauncher(),
            remoteDevelopmentLauncherBody = remoteArgumentRecordingLauncher(
                argumentsFile = argumentsFile,
                markerDirectory = markerDirectory,
                ideHome = bundleDir,
                pluginHome = pluginHome,
            ),
        )
        val unrelated = startUnrelatedSleeper()
        Files.createDirectories(markerDirectory)
        val oldMarker = markerDirectory.resolve(PidMarker.markerFileNameFor(unrelated.pid()))
        Files.writeString(oldMarker, readyMarkerJson(unrelated.pid(), bundleDir, pluginHome))
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = userHome,
            remoteDevelopmentStartupTimeoutMillis = 5_000,
        )

        try {
            val started = manager.start(parseBackendId(id))
            assertNotEquals(unrelated.pid(), started.pid, "a pre-launch marker must not capture this start")
            assertTrue(unrelated.isAlive, "the pre-existing process must remain untouched")
            manager.stop(parseBackendId(id))
        } finally {
            Files.deleteIfExists(oldMarker)
            stopProcess(unrelated)
        }
    }

    @Test
    fun `remote development start rejects a stale marker written for the current launcher pid`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val id = "idea-ultimate-2026.2.0.1"
        val build = "IU-262.8665.337"
        val homePaths = HomePaths(tempDir.resolve("home"))
        val userHome = tempDir.resolve("user-home")
        val markerDirectory = PidMarker.markerDirectory(userHome)
        val bundleDir = homePaths.backendDir(id).resolve("idea-$build")
        val pluginHome = homePaths.cacheDir(id).resolve("plugins/mcp-steroid")
        installStubBackend(
            homePaths = homePaths,
            id = id,
            productKey = "idea-ultimate",
            productCode = "IU",
            buildNumber = build,
            launcherBody = gracefulLauncher(),
            remoteDevelopmentLauncherBody = remoteArgumentRecordingLauncher(
                argumentsFile = tempDir.resolve("remote-args.txt"),
                markerDirectory = markerDirectory,
                ideHome = bundleDir,
                pluginHome = pluginHome,
                markerCreatedAt = "2000-01-01T00:00:00Z",
            ),
        )
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = userHome,
            remoteDevelopmentStartupTimeoutMillis = 300,
        )

        val error = assertFailsWith<ManagedBackendValidationException> {
            manager.start(parseBackendId(id))
        }

        assertTrue(error.message!!.contains("Timed out waiting for the MCP Steroid readiness marker"), error.message)
        assertFalse(homePaths.pidFile(id).exists())
    }

    @Test
    fun `remote development timeout terminates an unmarked handed-off backend process`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val id = "idea-ultimate-2026.2.0.1"
        val homePaths = HomePaths(tempDir.resolve("home"))
        val handedOffPidFile = tempDir.resolve("handed-off.pid")
        installStubBackend(
            homePaths = homePaths,
            id = id,
            productKey = "idea-ultimate",
            productCode = "IU",
            buildNumber = "IU-262.8665.337",
            launcherBody = gracefulLauncher(),
            remoteDevelopmentLauncherBody = unmarkedHandoffRemoteDevelopmentLauncher(handedOffPidFile),
        )
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = tempDir.resolve("user-home"),
            remoteDevelopmentStartupTimeoutMillis = 300,
        )

        assertFailsWith<ManagedBackendValidationException> {
            manager.start(parseBackendId(id))
        }
        val handedOffPid = Files.readString(handedOffPidFile).trim().toLong()
        try {
            withTimeout(5.seconds) {
                while (ProcessHandle.of(handedOffPid).map { it.isAlive }.orElse(false)) delay(50.milliseconds)
            }
            assertFalse(homePaths.pidFile(id).exists())
        } finally {
            ProcessHandle.of(handedOffPid).ifPresent { it.destroyForcibly() }
        }
    }

    @Test
    fun `cancelling remote development start terminates an unmarked handed-off backend process`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val id = "idea-ultimate-2026.2.0.1"
        val homePaths = HomePaths(tempDir.resolve("home"))
        val handedOffPidFile = tempDir.resolve("handed-off.pid")
        installStubBackend(
            homePaths = homePaths,
            id = id,
            productKey = "idea-ultimate",
            productCode = "IU",
            buildNumber = "IU-262.8665.337",
            launcherBody = gracefulLauncher(),
            remoteDevelopmentLauncherBody = unmarkedHandoffRemoteDevelopmentLauncher(handedOffPidFile),
        )
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = tempDir.resolve("user-home"),
            remoteDevelopmentStartupTimeoutMillis = 30_000,
        )

        val starting = async { manager.start(parseBackendId(id)) }
        withTimeout(5.seconds) {
            while (!handedOffPidFile.exists()) delay(50.milliseconds)
        }
        val handedOffPid = Files.readString(handedOffPidFile).trim().toLong()
        try {
            assertTrue(ProcessHandle.of(handedOffPid).orElseThrow().isAlive)
            starting.cancelAndJoin()
            withTimeout(5.seconds) {
                while (ProcessHandle.of(handedOffPid).map { it.isAlive }.orElse(false)) delay(50.milliseconds)
            }
            assertFalse(homePaths.pidFile(id).exists())
        } finally {
            starting.cancel()
            ProcessHandle.of(handedOffPid).ifPresent { it.destroyForcibly() }
        }
    }

    @Test
    fun `pid persistence failure terminates a ready handed-off backend process`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val id = "idea-ultimate-2026.2.0.1"
        val build = "IU-262.8665.337"
        val homePaths = HomePaths(tempDir.resolve("home"))
        val userHome = tempDir.resolve("user-home")
        val bundleDir = homePaths.backendDir(id).resolve("idea-$build")
        val handedOffPidFile = tempDir.resolve("handed-off.pid")
        installStubBackend(
            homePaths = homePaths,
            id = id,
            productKey = "idea-ultimate",
            productCode = "IU",
            buildNumber = build,
            launcherBody = gracefulLauncher(),
            remoteDevelopmentLauncherBody = handoffWithPidPersistenceFailureLauncher(
                handedOffPidFile = handedOffPidFile,
                markerDirectory = PidMarker.markerDirectory(userHome),
                ideHome = bundleDir,
                pluginHome = homePaths.cacheDir(id).resolve("plugins/mcp-steroid"),
                pidFile = homePaths.pidFile(id),
            ),
        )
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = userHome,
            remoteDevelopmentStartupTimeoutMillis = 5_000,
        )

        assertFailsWith<Exception> {
            manager.start(parseBackendId(id))
        }
        val handedOffPid = Files.readString(handedOffPidFile).trim().toLong()
        try {
            withTimeout(5.seconds) {
                while (ProcessHandle.of(handedOffPid).map { it.isAlive }.orElse(false)) delay(50.milliseconds)
            }
            assertFalse(homePaths.pidFile(id).exists(), "failed PID persistence must leave no state entry")
            assertFalse(
                PidMarker.markerDirectory(userHome).resolve(PidMarker.markerFileNameFor(handedOffPid)).exists(),
                "failed PID persistence must remove the handed-off backend marker",
            )
        } finally {
            ProcessHandle.of(handedOffPid).ifPresent { it.destroyForcibly() }
        }
    }

    @Test
    fun `start preserves standard launcher for Community`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val argumentsFile = tempDir.resolve("standard-arguments.txt")
        installStubBackend(homePaths, launcherBody = argumentRecordingLauncher(argumentsFile))
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = tempDir.resolve("user-home"),
        )

        manager.start(parseBackendId("idea-community-2025.3.3"))
        try {
            withTimeout(5.seconds) {
                while (!argumentsFile.exists()) delay(50.milliseconds)
            }
            assertEquals("0\n", Files.readString(argumentsFile))
        } finally {
            manager.stop(parseBackendId("idea-community-2025.3.3"))
        }
    }

    @Test
    fun `start writes pid file and stop terminates gracefully`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher())
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = tempDir.resolve("user-home"),
        )

        val started = manager.start(parseBackendId("idea-community-2025.3.3"))

        assertTrue(started.pid > 0)
        val processState = requireNotNull(
            readManagedBackendProcessState(homePaths.pidFile("idea-community-2025.3.3")),
        )
        assertEquals(started.pid, processState.pid)
        assertTrue(processState.startInstant != null)
        val liveHandle = ProcessHandle.of(started.pid).orElseThrow()
        assertTrue(liveHandle.isAlive)
        assertEquals(
            Instant.parse(processState.startInstant).toEpochMilli(),
            liveHandle.info().startInstant().orElseThrow().toEpochMilli(),
        )
        assertEquals(
            homePaths.backendDir("idea-community-2025.3.3")
                .resolve("idea-IC-253.1/bin/idea.sh")
                .toAbsolutePath()
                .normalize()
                .toString(),
            liveHandle.info().arguments().orElseThrow().first(),
        )
        val marker = PidMarker.markerDirectory(tempDir.resolve("user-home"))
            .resolve(PidMarker.markerFileNameFor(started.pid))
        Files.createDirectories(marker.parent)
        Files.writeString(marker, "marker")

        val stopped = manager.stop(parseBackendId("idea-community-2025.3.3"))

        assertEquals("stopped", stopped.outcome)
        assertFalse(homePaths.pidFile("idea-community-2025.3.3").exists())
        assertFalse(marker.exists())
        assertFalse(ProcessHandle.of(started.pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `start ignores unrelated executable inside another managed IDE bundle`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val unrelatedId = "idea-community-2025.3.2"
        val requestedId = "idea-community-2025.3.3"
        installStubBackend(homePaths, launcherBody = gracefulLauncher(), id = unrelatedId)
        installStubBackend(homePaths, launcherBody = gracefulLauncher(), id = requestedId)
        val fakePid = 987_654L
        val unrelatedJava = homePaths.backendDir(unrelatedId)
            .resolve("idea-IC-253.1/jbr/bin/java")
            .toAbsolutePath()
            .normalize()
        val fakeSnapshot = ProcessSnapshot(
            pid = fakePid,
            command = unrelatedJava.toString(),
            arguments = listOf("-version"),
            startInstant = Instant.parse("2026-07-31T08:00:00Z"),
        )
        val processInspector = object : ManagedProcessInspector {
            override fun isAlive(pid: Long): Boolean =
                if (pid == fakePid) true else DefaultManagedProcessInspector.isAlive(pid)

            override fun allProcesses(): List<ProcessSnapshot> =
                DefaultManagedProcessInspector.allProcesses() + fakeSnapshot

            override fun snapshot(pid: Long): ProcessSnapshot? =
                if (pid == fakePid) fakeSnapshot else DefaultManagedProcessInspector.snapshot(pid)
        }
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(
                bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test"),
            ),
            processInspector = processInspector,
            ideUserHome = tempDir.resolve("user-home"),
        )

        val started = manager.start(parseBackendId(requestedId))
        try {
            assertFalse(started.alreadyRunning)
            assertNotEquals(fakePid, started.pid)
        } finally {
            manager.stop(parseBackendId(requestedId))
        }
    }

    @Test
    fun `stop force kills a process that does not exit before the grace period`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = sleepyLauncher())
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = tempDir.resolve("user-home"),
            stopGracePeriodMillis = 0L,
        )

        val started = manager.start(parseBackendId("idea-community-2025.3.3"))
        assertTrue(ProcessHandle.of(started.pid).orElseThrow().isAlive)
        val stopped = manager.stop(parseBackendId("idea-community-2025.3.3"))

        assertEquals("killed", stopped.outcome)
        assertFalse(homePaths.pidFile("idea-community-2025.3.3").exists())
        assertFalse(ProcessHandle.of(started.pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `stop treats a missing pid file as successful not running`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val manager = BackendManager(
            homePaths = HomePaths(tempDir.resolve("home")),
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
        )

        val stopped = manager.stop(parseBackendId("idea-community-2025.3.3"))

        assertEquals("not running", stopped.outcome)
    }

    @Test
    fun `stop deletes stale pid file without signalling unrelated process`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher())
        val unrelated = startUnrelatedSleeper()
        try {
            Files.createDirectories(homePaths.stateDir)
            Files.writeString(homePaths.pidFile("idea-community-2025.3.3"), "${unrelated.pid()}\n")
            val manager = BackendManager(
                homePaths = homePaths,
                downloader = StaticDownloader,
                bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
                ideUserHome = tempDir.resolve("user-home"),
            )

            val stopped = manager.stop(parseBackendId("idea-community-2025.3.3"))

            assertEquals("stale", stopped.outcome)
            assertNull(stopped.pid)
            assertEquals("pid ${unrelated.pid()} is no longer the managed backend", stopped.message)
            assertFalse(homePaths.pidFile("idea-community-2025.3.3").exists())
            assertTrue(unrelated.isAlive, "unrelated process must not be signalled")
        } finally {
            stopProcess(unrelated)
        }
    }

    @Test
    fun `stop marker decode warning never logs marker contents`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val id = "idea-community-2025.3.3"
        val secretSentinel = "SECRET_SENTINEL"
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher())
        val unrelated = startUnrelatedSleeper()
        val userHome = tempDir.resolve("user-home")
        try {
            Files.createDirectories(homePaths.stateDir)
            Files.writeString(homePaths.pidFile(id), "${unrelated.pid()}\n")
            val markerDirectory = PidMarker.markerDirectory(userHome)
            Files.createDirectories(markerDirectory)
            val markerPath = markerDirectory.resolve(PidMarker.markerFileNameFor(unrelated.pid()))
            Files.writeString(markerPath, "{\"authorization\":\"Bearer $secretSentinel\",")
            val manager = BackendManager(
                homePaths = homePaths,
                downloader = StaticDownloader,
                bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
                ideUserHome = userHome,
            )

            val stderr = captureStderr {
                val stopped = manager.stop(parseBackendId(id))
                assertEquals("stale", stopped.outcome)
            }

            assertTrue(stderr.contains(markerPath.toString()), stderr)
            assertTrue(stderr.contains("JsonDecodingException"), stderr)
            assertFalse(stderr.contains(secretSentinel), stderr)
            assertTrue(unrelated.isAlive)
        } finally {
            stopProcess(unrelated)
        }
    }

    @Test
    fun `stop does not signal unrelated process with exact file argument under managed bundle`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val id = "idea-community-2025.3.3"
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher())
        val unrelatedFile = homePaths.backendDir(id).resolve("idea-IC-253.1/product-info.json")
        Files.writeString(unrelatedFile, "{}")
        val unrelated = ProcessBuilder("tail", "-f", unrelatedFile.toString()).start()
        try {
            Files.createDirectories(homePaths.stateDir)
            Files.writeString(homePaths.pidFile(id), "${unrelated.pid()}\n")
            val manager = BackendManager(
                homePaths = homePaths,
                downloader = StaticDownloader,
                bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
                ideUserHome = tempDir.resolve("user-home"),
            )

            val stopped = manager.stop(parseBackendId(id))

            assertEquals("stale", stopped.outcome)
            assertTrue(unrelated.isAlive, "an unrelated process must not be signalled")
        } finally {
            stopProcess(unrelated)
        }
    }

    @Test
    fun `stop accepts matching pid marker for process outside backend directory`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher())
        val process = startUnrelatedSleeper()
        val userHome = tempDir.resolve("user-home")
        try {
            val markersDir = PidMarker.markerDirectory(userHome)
            Files.createDirectories(homePaths.stateDir)
            Files.createDirectories(markersDir)
            Files.writeString(homePaths.pidFile("idea-community-2025.3.3"), "${process.pid()}\n")
            Files.writeString(
                markersDir.resolve(PidMarker.markerFileNameFor(process.pid())),
                communityMarkerJson(
                    pid = process.pid(),
                    ideHome = homePaths.backendDir("idea-community-2025.3.3").resolve("idea-IC-253.1"),
                    pluginHome = homePaths.cacheDir("idea-community-2025.3.3").resolve("plugins/mcp-steroid"),
                ),
            )
            val manager = BackendManager(
                homePaths = homePaths,
                downloader = StaticDownloader,
                bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
                ideUserHome = userHome,
            )

            val stopped = manager.stop(parseBackendId("idea-community-2025.3.3"))

            assertEquals("stopped", stopped.outcome)
            assertEquals(process.pid(), stopped.pid)
            assertFalse(ProcessHandle.of(process.pid()).map { it.isAlive }.orElse(false))
        } finally {
            stopProcess(process)
        }
    }

    @Test
    fun `stop rejects a matching stale marker when its pid was reused by a newer process`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val id = "idea-community-2025.3.3"
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher())
        val process = startUnrelatedSleeper()
        val userHome = tempDir.resolve("user-home")
        try {
            Files.createDirectories(homePaths.stateDir)
            val markerDirectory = PidMarker.markerDirectory(userHome)
            Files.createDirectories(markerDirectory)
            Files.writeString(homePaths.pidFile(id), "${process.pid()}\n")
            val processStartedAt = process.toHandle().info().startInstant().orElseThrow()
            Files.writeString(
                markerDirectory.resolve(PidMarker.markerFileNameFor(process.pid())),
                communityMarkerJson(
                    pid = process.pid(),
                    ideHome = homePaths.backendDir(id).resolve("idea-IC-253.1"),
                    pluginHome = homePaths.cacheDir(id).resolve("plugins/mcp-steroid"),
                    createdAt = processStartedAt.minusSeconds(1).toString(),
                ),
            )
            val manager = BackendManager(
                homePaths = homePaths,
                downloader = StaticDownloader,
                bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
                ideUserHome = userHome,
            )

            val stopped = manager.stop(parseBackendId(id))

            assertEquals("stale", stopped.outcome)
            assertTrue(process.isAlive, "a newer process that reused the marker pid must not be signalled")
        } finally {
            stopProcess(process)
        }
    }

    @Test
    fun `stop rejects a matching launcher when persisted process start instant differs`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val id = "idea-community-2025.3.3"
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher())
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = tempDir.resolve("user-home"),
        )
        val started = manager.start(parseBackendId(id))
        val handle = ProcessHandle.of(started.pid).orElseThrow()
        try {
            val statePath = homePaths.pidFile(id)
            val state = requireNotNull(readManagedBackendProcessState(statePath))
            Files.writeString(
                statePath,
                Json.encodeToString(state.copy(startInstant = "2000-01-01T00:00:00Z")) + "\n",
            )

            val stopped = manager.stop(parseBackendId(id))

            assertEquals("stale", stopped.outcome)
            assertTrue(handle.isAlive, "a process whose start instant differs from persisted state must not be signalled")
        } finally {
            handle.destroy()
            runCatching { handle.onExit().get(5, TimeUnit.SECONDS) }.getOrNull()
            if (handle.isAlive) {
                handle.destroyForcibly()
                handle.onExit().get(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `stop rejects a matching build marker from a different IDE home`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val id = "idea-community-2025.3.3"
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher())
        val process = startUnrelatedSleeper()
        val userHome = tempDir.resolve("user-home")
        try {
            Files.createDirectories(homePaths.stateDir)
            Files.createDirectories(PidMarker.markerDirectory(userHome))
            Files.writeString(homePaths.pidFile(id), "${process.pid()}\n")
            Files.writeString(
                PidMarker.markerDirectory(userHome).resolve(PidMarker.markerFileNameFor(process.pid())),
                communityMarkerJson(
                    pid = process.pid(),
                    ideHome = tempDir.resolve("different-ide-home"),
                    pluginHome = homePaths.cacheDir(id).resolve("plugins/mcp-steroid"),
                ),
            )
            val manager = BackendManager(
                homePaths = homePaths,
                downloader = StaticDownloader,
                bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
                ideUserHome = userHome,
            )

            val stopped = manager.stop(parseBackendId(id))

            assertEquals("stale", stopped.outcome)
            assertTrue(process.isAlive, "a process belonging to another IDE home must not be signalled")
        } finally {
            stopProcess(process)
        }
    }

    @Test
    fun `stop rejects a marker with another product prefix and the same numeric build`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val id = "idea-community-2025.3.3"
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher())
        val process = startUnrelatedSleeper()
        val userHome = tempDir.resolve("user-home")
        try {
            Files.createDirectories(homePaths.stateDir)
            Files.createDirectories(PidMarker.markerDirectory(userHome))
            Files.writeString(homePaths.pidFile(id), "${process.pid()}\n")
            Files.writeString(
                PidMarker.markerDirectory(userHome).resolve(PidMarker.markerFileNameFor(process.pid())),
                communityMarkerJson(
                    pid = process.pid(),
                    ideHome = homePaths.backendDir(id).resolve("idea-IC-253.1"),
                    pluginHome = homePaths.cacheDir(id).resolve("plugins/mcp-steroid"),
                    build = "IU-253.1",
                ),
            )
            val manager = BackendManager(
                homePaths = homePaths,
                downloader = StaticDownloader,
                bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
                ideUserHome = userHome,
            )

            val stopped = manager.stop(parseBackendId(id))

            assertEquals("stale", stopped.outcome)
            assertTrue(process.isAlive, "a same-number build from another IDE product must not be signalled")
        } finally {
            stopProcess(process)
        }
    }

    @Test
    fun `stop accepts a matching macOS marker whose IDE home is the app Contents directory`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val id = "idea-ultimate-2026.2.0.1"
        val build = "IU-262.8665.337"
        val homePaths = HomePaths(tempDir.resolve("home"))
        val userHome = tempDir.resolve("user-home")
        val backendDir = homePaths.backendDir(id)
        val bundleDir = backendDir.resolve("IntelliJ IDEA.app")
        val ideHome = bundleDir.resolve("Contents")
        val remoteLauncher = ideHome.resolve("bin/remote-dev-server")
        Files.createDirectories(remoteLauncher.parent)
        Files.writeString(remoteLauncher, gracefulLauncher())
        remoteLauncher.toFile().setExecutable(true)
        val remoteDevelopmentPluginJar = ideHome.resolve("plugins/remote-dev-server/lib/remote-dev-server.jar")
        Files.createDirectories(remoteDevelopmentPluginJar.parent)
        Files.writeString(remoteDevelopmentPluginJar, "remote development plugin")
        writeDescriptor(
            descriptorPath(backendDir),
            BackendDescriptor(
                id = id,
                productKey = "idea-ultimate",
                productCode = "IU",
                version = "2026.2.0.1",
                buildNumber = build,
                bundleDirName = bundleDir.fileName.toString(),
                launcherPath = "Contents/bin/idea.sh",
                downloadedAt = "2026-07-31T00:00:00Z",
            ),
        )
        val pluginHome = homePaths.cacheDir(id).resolve("plugins/mcp-steroid")
        val unrelated = startUnrelatedSleeper()
        Files.createDirectories(homePaths.stateDir)
        Files.writeString(homePaths.pidFile(id), "${unrelated.pid()}\n")
        val markerDirectory = PidMarker.markerDirectory(userHome)
        Files.createDirectories(markerDirectory)
        Files.writeString(
            markerDirectory.resolve(PidMarker.markerFileNameFor(unrelated.pid())),
            readyMarkerJson(unrelated.pid(), ideHome, pluginHome),
        )
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            backendLauncherResolver = ManagedBackendLauncherResolver(HostOs.MAC),
            ideUserHome = userHome,
        )

        try {
            val stopped = manager.stop(parseBackendId(id))
            assertEquals("stopped", stopped.outcome)
            assertEquals(unrelated.pid(), stopped.pid)
        } finally {
            stopProcess(unrelated)
        }
    }

    @Test
    fun `stale pid for one backend does not stop another managed backend`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val firstId = "idea-community-2025.3.2"
        val secondId = "idea-community-2025.3.3"
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher(), id = firstId)
        installStubBackend(homePaths, launcherBody = gracefulLauncher(), id = secondId)
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = tempDir.resolve("user-home"),
        )
        val second = manager.start(parseBackendId(secondId))
        try {
            Files.writeString(homePaths.pidFile(firstId), "${second.pid}\n")

            val stopped = manager.stop(parseBackendId(firstId))

            assertEquals("stale", stopped.outcome)
            assertTrue(
                ProcessHandle.of(second.pid).map { it.isAlive }.orElse(false),
                "a stale PID for backend A must not signal backend B",
            )
        } finally {
            manager.stop(parseBackendId(secondId))
        }
    }

    @Test
    fun `start product-only prefers highest locally installed backend without resolving releases`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher(), id = "idea-community-2025.2.6.1")
        installStubBackend(homePaths, launcherBody = gracefulLauncher(), id = "idea-community-2025.2.6.2")
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = ThrowingDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = tempDir.resolve("user-home"),
        )

        val started = manager.start(parseBackendId("idea-community"))
        try {
            assertEquals("idea-community-2025.2.6.2", started.id)
            assertTrue(ProcessHandle.of(started.pid).orElseThrow().isAlive)
        } finally {
            manager.stop(parseBackendId("idea-community-2025.2.6.2"))
        }
    }

    @Test
    fun `stop product-only prefers highest locally installed backend without resolving releases`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher(), id = "idea-community-2025.2.6.1")
        installStubBackend(homePaths, launcherBody = gracefulLauncher(), id = "idea-community-2025.2.6.2")
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = ThrowingDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = tempDir.resolve("user-home"),
        )
        val started = manager.start(parseBackendId("idea-community-2025.2.6.2"))
        assertTrue(ProcessHandle.of(started.pid).orElseThrow().isAlive)

        val stopped = manager.stop(parseBackendId("idea-community"))

        assertEquals("idea-community-2025.2.6.2", stopped.id)
        assertEquals("stopped", stopped.outcome)
        assertFalse(ProcessHandle.of(started.pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `start captures launcher stdout and stderr to managed log`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = noisyLauncher())
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = tempDir.resolve("user-home"),
        )

        val started = manager.start(parseBackendId("idea-community-2025.3.3"))
        try {
            assertEquals(homePaths.cacheDir("idea-community-2025.3.3").resolve("logs/managed.log"), started.ideaLogPath)
            withTimeout(5.seconds) {
                while (true) {
                    if (started.ideaLogPath.exists()) {
                        val text = Files.readString(started.ideaLogPath)
                        if ("managed stdout" in text && "managed stderr" in text) break
                    }
                    delay(100.milliseconds)
                }
            }
        } finally {
            manager.stop(parseBackendId("idea-community-2025.3.3"))
        }
    }

    @Test
    fun `start seeds first-run startup config before launching IDE`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher())
        val userHome = tempDir.resolve("user-home")
        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "test")),
            ideUserHome = userHome,
        )

        val started = manager.start(parseBackendId("idea-community-2025.3.3"))
        try {
            val configDir = homePaths.cacheDir("idea-community-2025.3.3").resolve("config")
            assertTrue(
                Files.readString(configDir.resolve("options/other.xml"))
                    .contains("experimental.ui.onboarding.proposed.version"),
            )
            assertEquals(
                "switched.from.classic.to.islands\nfalse\n",
                Files.readString(configDir.resolve("early-access-registry.txt")),
            )
            assertTrue(
                Files.readString(configDir.resolve("options/AIOnboardingPromoWindowAdvisor.xml"))
                    .contains("""<option name="wasShown" value="true" />"""),
            )
            assertTrue(
                Files.readString(userHome.resolve(".java/.userPrefs/jetbrains/privacy_policy/prefs.xml"))
                    .contains("""<entry key="euacommunity_accepted_version" value="999.999"/>"""),
            )
            assertTrue(
                Files.readString(userHome.resolve(".config/JetBrains/consentOptions/accepted"))
                    .startsWith("rsch.send.usage.stat:1.1:0:"),
            )
        } finally {
            manager.stop(parseBackendId("idea-community-2025.3.3"))
        }
        assertFalse(ProcessHandle.of(started.pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `start re-provisions the current bundled plugin before launching`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher())

        // Seed a stale old plugin to verify that start replaces it with the current one.
        val pluginDir = homePaths.cacheDir("idea-community-2025.3.3").resolve("plugins/mcp-steroid")
        Files.createDirectories(pluginDir.resolve("lib"))
        Files.writeString(pluginDir.resolve("lib/plugin.txt"), "old")
        Files.writeString(pluginDir.resolve("stale.txt"), "stale")

        val manager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "current")),
            ideUserHome = tempDir.resolve("user-home"),
        )

        val started = manager.start(parseBackendId("idea-community-2025.3.3"))
        assertTrue(started.pid > 0)
        try {
            assertEquals(
                "current",
                Files.readString(pluginDir.resolve("lib/plugin.txt")),
                "start must re-deploy the current bundled plugin, overwriting the stale one",
            )
            assertFalse(pluginDir.resolve("stale.txt").exists(), "start must remove stale plugin files before redeploying")
        } finally {
            manager.stop(parseBackendId("idea-community-2025.3.3"))
        }
    }

    @Test
    fun `start on an already-running backend does not re-provision the plugin`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher())
        val firstManager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "v1")),
            ideUserHome = tempDir.resolve("user-home"),
        )

        val firstStart = firstManager.start(parseBackendId("idea-community-2025.3.3"))
        assertTrue(firstStart.pid > 0)
        assertFalse(firstStart.alreadyRunning)
        try {
            // Build a second manager with a resolver that must NOT be called:
            // if deployMcpSteroidPlugin runs for an already-running backend the test will fail.
            val secondManager = BackendManager(
                homePaths = homePaths,
                downloader = StaticDownloader,
                bundledPluginResolver = ThrowingBundledPluginResolver,
                ideUserHome = tempDir.resolve("user-home"),
            )

            val secondStart = secondManager.start(parseBackendId("idea-community-2025.3.3"))

            assertTrue(secondStart.alreadyRunning, "second start must report alreadyRunning=true")
            assertEquals(firstStart.pid, secondStart.pid, "pid must be the same running process")
        } finally {
            firstManager.stop(parseBackendId("idea-community-2025.3.3"))
        }
    }

    @Test
    fun `durable pid identity does not decode an unrelated marker`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val userHome = tempDir.resolve("user-home")
        installStubBackend(homePaths, launcherBody = gracefulLauncher())
        val firstManager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "v1")),
            ideUserHome = userHome,
        )

        val firstStart = firstManager.start(parseBackendId("idea-community-2025.3.3"))
        try {
            val markerDirectory = PidMarker.markerDirectory(userHome)
            Files.createDirectories(markerDirectory)
            Files.writeString(
                markerDirectory.resolve(PidMarker.markerFileNameFor(firstStart.pid)),
                "not-json Bearer SECRET_SENTINEL",
            )
            val secondManager = BackendManager(
                homePaths = homePaths,
                downloader = StaticDownloader,
                bundledPluginResolver = ThrowingBundledPluginResolver,
                ideUserHome = userHome,
            )

            val stderr = captureStderr {
                val secondStart = secondManager.start(parseBackendId("idea-community-2025.3.3"))
                assertTrue(secondStart.alreadyRunning)
                assertEquals(firstStart.pid, secondStart.pid)
                firstManager.stop(parseBackendId("idea-community-2025.3.3"))
            }

            assertFalse(stderr.contains("failed to decode MCP Steroid marker"), stderr)
        } finally {
            if (DefaultManagedProcessInspector.isAlive(firstStart.pid)) {
                firstManager.stop(parseBackendId("idea-community-2025.3.3"))
            }
        }
    }

    @Test
    fun `start retains tracked backend when one process listing omits its pid`(
        @TempDir tempDir: Path,
    ) = kotlinx.coroutines.runBlocking {
        val homePaths = HomePaths(tempDir.resolve("home"))
        installStubBackend(homePaths, launcherBody = gracefulLauncher())
        val firstManager = BackendManager(
            homePaths = homePaths,
            downloader = StaticDownloader,
            bundledPluginResolver = FixedBundledPluginResolver(bundledPluginZipFixture(tempDir.resolve("dist/ij-plugin.zip"), "v1")),
            ideUserHome = tempDir.resolve("user-home"),
        )

        val firstStart = firstManager.start(parseBackendId("idea-community-2025.3.3"))
        try {
            val secondManager = BackendManager(
                homePaths = homePaths,
                downloader = StaticDownloader,
                bundledPluginResolver = ThrowingBundledPluginResolver,
                processInspector = HideTargetFromFirstProcessListingInspector(firstStart.pid),
                ideUserHome = tempDir.resolve("user-home"),
            )

            val secondStart = secondManager.start(parseBackendId("idea-community-2025.3.3"))

            assertTrue(secondStart.alreadyRunning)
            assertEquals(firstStart.pid, secondStart.pid)
            assertTrue(homePaths.pidFile("idea-community-2025.3.3").exists())
        } finally {
            firstManager.stop(parseBackendId("idea-community-2025.3.3"))
        }
    }

    private fun installStubBackend(
        homePaths: HomePaths,
        launcherBody: String,
        id: String = "idea-community-2025.3.3",
        productKey: String = "idea-community",
        productCode: String = "IC",
        buildNumber: String = "IC-253.1",
        remoteDevelopmentLauncherBody: String? = null,
    ) {
        val backendDir = homePaths.backendDir(id)
        val bundleDir = backendDir.resolve("idea-$buildNumber")
        val productLauncher = bundleDir.resolve("bin/idea.sh")
        Files.createDirectories(productLauncher.parent)
        Files.writeString(productLauncher, launcherBody)
        productLauncher.toFile().setExecutable(true)
        if (remoteDevelopmentLauncherBody != null) {
            val remoteDevelopmentLauncher = bundleDir.resolve("bin/remote-dev-server")
            Files.writeString(remoteDevelopmentLauncher, remoteDevelopmentLauncherBody)
            remoteDevelopmentLauncher.toFile().setExecutable(true)
            val remoteDevelopmentPluginJar = bundleDir.resolve("plugins/remote-dev-server/lib/remote-dev-server.jar")
            Files.createDirectories(remoteDevelopmentPluginJar.parent)
            Files.writeString(remoteDevelopmentPluginJar, "remote development plugin")
        }
        writeDescriptor(
            descriptorPath(backendDir),
            BackendDescriptor(
                id = id,
                productKey = productKey,
                productCode = productCode,
                version = id.removePrefix("$productKey-"),
                buildNumber = buildNumber,
                bundleDirName = bundleDir.fileName.toString(),
                launcherPath = "bin/idea.sh",
                downloadedAt = "2026-05-14T21:00:00Z",
            ),
        )
    }

    private fun gracefulLauncher(): String =
        """
        #!/usr/bin/env sh
        trap 'exit 0' TERM
        while true; do sleep 1; done
        """.trimIndent() + "\n"

    private fun pidRecordingLauncher(launcherPidFile: Path): String =
        $$"""
        #!/usr/bin/env sh
        printf '%s\n' "$$" > '$$launcherPidFile'
        trap 'exit 0' TERM
        while true; do sleep 1; done
        """.trimIndent() + "\n"

    private fun argumentRecordingLauncher(argumentsFile: Path): String =
        $$"""
        #!/usr/bin/env sh
        printf '%s\n' "$#" "$@" > '$$argumentsFile'
        trap 'exit 0' TERM
        while true; do sleep 1; done
        """.trimIndent() + "\n"

    private fun remoteArgumentRecordingLauncher(
        argumentsFile: Path,
        markerDirectory: Path,
        ideHome: Path,
        pluginHome: Path,
        markerCreatedAt: String = Instant.now().plusSeconds(1).toString(),
    ): String {
        val shellPid = '$' + "pid"
        val markerJson = readyMarkerJson(0, ideHome, pluginHome, markerCreatedAt)
            .replace("\"pid\": 0", "\"pid\": $shellPid")
            .replace("\n", "")
        return $$"""
        #!/usr/bin/env sh
        pid=$$
        printf '%s\n' "$#" "$@" "$REMOTE_DEV_JDK_DETECTION" "$REMOTE_DEV_NON_INTERACTIVE" "$REMOTE_DEV_TRUST_PROJECTS" > '$$argumentsFile'
        mkdir -p '$$markerDirectory'
        cat > '$$markerDirectory/'"$pid"'.mcp-steroid' <<EOF
        $$markerJson
        EOF
        trap 'exit 0' TERM
        while true; do sleep 1; done
        """.trimIndent() + "\n"
    }

    private fun handoffRemoteDevelopmentLauncher(
        launcherPidFile: Path,
        markerDirectory: Path,
        ideHome: Path,
        pluginHome: Path,
    ): String {
        val shellBackendPid = '$' + "backend_pid"
        val markerJson = readyMarkerJson(0, ideHome, pluginHome)
            .replace("\"pid\": 0", "\"pid\": $shellBackendPid")
            .replace("\n", "")
        return $$"""
            #!/usr/bin/env sh
            printf '%s\n' "$$" > '$$launcherPidFile'
            (
              trap 'exit 0' TERM
              while true; do sleep 1; done
            ) &
            backend_pid=$!
            mkdir -p '$$markerDirectory'
            cat > '$$markerDirectory/'"$backend_pid"'.mcp-steroid' <<EOF
            $$markerJson
            EOF
            exit 0
        """.trimIndent() + "\n"
    }

    private fun persistentHandoffRemoteDevelopmentLauncher(
        launcherPidFile: Path,
        markerDirectory: Path,
        ideHome: Path,
        pluginHome: Path,
    ): String {
        val shellBackendPid = '$' + "backend_pid"
        val markerJson = readyMarkerJson(0, ideHome, pluginHome)
            .replace("\"pid\": 0", "\"pid\": $shellBackendPid")
            .replace("\n", "")
        return $$"""
            #!/usr/bin/env sh
            printf '%s\n' "$$" > '$$launcherPidFile'
            (
              trap 'exit 0' TERM
              while true; do sleep 1; done
            ) &
            backend_pid=$!
            mkdir -p '$$markerDirectory'
            cat > '$$markerDirectory/'"$backend_pid"'.mcp-steroid' <<EOF
            $$markerJson
            EOF
            trap 'exit 0' TERM
            while true; do sleep 1; done
        """.trimIndent() + "\n"
    }

    private fun unmarkedHandoffRemoteDevelopmentLauncher(handedOffPidFile: Path): String =
        $$"""
        #!/usr/bin/env sh
        if [ "${1:-}" = "devrig-test-child" ]; then
          printf '%s\n' "$$" > '$$handedOffPidFile'
          trap 'exit 0' TERM
          while true; do sleep 1; done
        fi
        "$0" devrig-test-child &
        while [ ! -s '$$handedOffPidFile' ]; do sleep 0.01; done
        exit 0
        """.trimIndent() + "\n"

    private fun handoffWithPidPersistenceFailureLauncher(
        handedOffPidFile: Path,
        markerDirectory: Path,
        ideHome: Path,
        pluginHome: Path,
        pidFile: Path,
    ): String {
        val shellBackendPid = '$' + "backend_pid"
        val markerJson = readyMarkerJson(0, ideHome, pluginHome)
            .replace("\"pid\": 0", "\"pid\": $shellBackendPid")
            .replace("\n", "")
        return $$"""
            #!/usr/bin/env sh
            (
              trap 'exit 0' TERM
              while true; do sleep 1; done
            ) &
            backend_pid=$!
            printf '%s\n' "$backend_pid" > '$$handedOffPidFile'
            mkdir -p '$$markerDirectory'
            cat > '$$markerDirectory/'"$backend_pid"'.mcp-steroid' <<EOF
            $$markerJson
            EOF
            mkdir '$$pidFile'
            exit 0
        """.trimIndent() + "\n"
    }

    private fun communityMarkerJson(
        pid: Long,
        ideHome: Path,
        pluginHome: Path,
        build: String = "IC-253.1",
        createdAt: String = Instant.now().plusSeconds(1).toString(),
    ): String =
        PidMarkerJson.encode(
            PidMarker(
                schema = PidMarker.SCHEMA_VERSION,
                pid = pid,
                mcpSteroidServer = McpSteroidServerInfo(
                    mcpUrl = "http://localhost:63342/mcp",
                    headers = emptyMap(),
                    pluginPath = pluginHome.toAbsolutePath().normalize().toString(),
                ),
                devrigEndpoint = testDevrigEndpoint("http://localhost:63342/mcp"),
                ide = IdeInfo(name = "IntelliJ IDEA Community", version = "2025.3.3", build = build),
                plugin = PluginInfo(id = MCP_STEROID_PLUGIN_ID, name = "MCP Steroid", version = "1.0.0"),
                createdAt = createdAt,
                ideHome = ideHome.toAbsolutePath().normalize().toString(),
                intellijWebServer = null,
                intellijMcpServer = null,
            ),
        )

    private fun readyMarkerJson(
        pid: Long,
        ideHome: Path,
        pluginHome: Path? = null,
        createdAt: String = Instant.now().plusSeconds(1).toString(),
    ): String =
        PidMarkerJson.encode(
            PidMarker(
                schema = PidMarker.SCHEMA_VERSION,
                pid = pid,
                mcpSteroidServer = McpSteroidServerInfo(
                    mcpUrl = "http://localhost:63342/mcp",
                    headers = emptyMap(),
                    pluginPath = pluginHome?.toAbsolutePath()?.normalize()?.toString(),
                ),
                devrigEndpoint = testDevrigEndpoint("http://localhost:63342/mcp"),
                ide = IdeInfo(name = "IntelliJ IDEA Ultimate", version = "2026.2.0.1", build = "IU-262.8665.337"),
                plugin = PluginInfo(id = "com.jonnyzzz.mcp-steroid", name = "MCP Steroid", version = "1.0.0"),
                createdAt = createdAt,
                ideHome = ideHome.toAbsolutePath().normalize().toString(),
                intellijWebServer = null,
                intellijMcpServer = null,
            ),
        )

    private fun sleepyLauncher(): String =
        // A process that deliberately does NOT exit on SIGTERM, so `stop` with a 0ms grace
        // period is forced down the SIGKILL path and reports "killed". A bare `sleep 60`
        // is wrong here: `sleep` terminates on SIGTERM, and Debian's dash exec-optimizes a
        // single trailing command into the `sleep` process, so on Linux the SIGTERM kills
        // it before the force-kill path runs -> outcome "stale" instead of "killed" (flaky
        // across macOS sh vs Linux dash). Ignore TERM/INT and loop so liveness is
        // deterministic on every platform.
        """
        #!/usr/bin/env sh
        trap '' TERM INT
        while true; do sleep 1; done
        """.trimIndent() + "\n"

    private fun noisyLauncher(): String =
        """
        #!/usr/bin/env sh
        echo "managed stdout"
        echo "managed stderr" >&2
        trap 'exit 0' TERM
        while true; do sleep 1; done
        """.trimIndent() + "\n"

    private fun startUnrelatedSleeper(): Process =
        ProcessBuilder("sh", "-c", "trap 'exit 0' TERM; while true; do sleep 1; done").start()

    private fun stopProcess(process: Process) {
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private suspend fun <T> captureStderr(block: suspend () -> T): String {
        val original = System.err
        val buffer = ByteArrayOutputStream()
        System.setErr(PrintStream(buffer, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return buffer.toString(Charsets.UTF_8)
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
        ): BackendDownloadArtifact = error("downloadAndUnpack should not be called by start/stop tests")
    }

    private object ThrowingDownloader : ManagedBackendDownloader {
        override suspend fun resolve(id: BackendId): BackendDownloadResolution =
            error("release resolver should not be called when a local backend is installed")

        override suspend fun downloadAndUnpack(
            resolution: BackendDownloadResolution,
            targetDir: Path,
        ): BackendDownloadArtifact = error("downloadAndUnpack should not be called by start/stop tests")
    }

    private object ThrowingBundledPluginResolver : BundledPluginResolver {
        override fun resolveBundledPluginZip(): Path =
            error("re-provision must not run for an already-running backend")
    }

    private class HideFirstVisibleLauncherInspector(
        private val launcherPidFile: Path,
    ) : ManagedProcessInspector {
        private var launcherWasHidden = false

        override fun isAlive(pid: Long): Boolean = DefaultManagedProcessInspector.isAlive(pid)

        override fun snapshot(pid: Long): ProcessSnapshot? = DefaultManagedProcessInspector.snapshot(pid)

        override fun allProcesses(): List<ProcessSnapshot> {
            val snapshots = DefaultManagedProcessInspector.allProcesses()
            if (!launcherPidFile.exists() || launcherWasHidden) return snapshots
            launcherWasHidden = true
            val launcherPid = Files.readString(launcherPidFile).trim().toLong()
            return snapshots.filterNot { it.pid == launcherPid }
        }
    }

    private class ChangedStartIdentityInspector(
        private val launcherPidFile: Path,
    ) : ManagedProcessInspector {
        override fun isAlive(pid: Long): Boolean = DefaultManagedProcessInspector.isAlive(pid)

        override fun snapshot(pid: Long): ProcessSnapshot? = DefaultManagedProcessInspector.snapshot(pid)

        override fun allProcesses(): List<ProcessSnapshot> {
            val snapshots = DefaultManagedProcessInspector.allProcesses()
            if (!launcherPidFile.exists()) return snapshots
            val launcherPid = Files.readString(launcherPidFile).trim().toLong()
            return snapshots.map { snapshot ->
                if (snapshot.pid != launcherPid || snapshot.startInstant == null) {
                    snapshot
                } else {
                    snapshot.copy(startInstant = snapshot.startInstant.minusSeconds(1))
                }
            }
        }
    }

    private class HideTargetFromFirstProcessListingInspector(
        private val targetPid: Long,
    ) : ManagedProcessInspector {
        private var targetWasHidden = false

        override fun isAlive(pid: Long): Boolean = DefaultManagedProcessInspector.isAlive(pid)

        override fun snapshot(pid: Long): ProcessSnapshot? = DefaultManagedProcessInspector.snapshot(pid)

        override fun allProcesses(): List<ProcessSnapshot> {
            val snapshots = DefaultManagedProcessInspector.allProcesses()
            if (targetWasHidden) return snapshots
            targetWasHidden = true
            return snapshots.filterNot { it.pid == targetPid }
        }
    }
}
