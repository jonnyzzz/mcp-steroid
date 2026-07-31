/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class InstalledBackendsTest {

    private fun installed(id: String, home: String): InstalledBackend = InstalledBackend(
        id = id,
        ide = IdeInfo(name = "Test IDE", version = "2026.1", build = "TEST-1"),
        ideHome = home,
        launcher = Path.of(home, "bin", "idea.sh"),
    )

    private fun discoveredIde(ideHome: String?): DiscoveredIde = DiscoveredIde(
        backendName = "test-backend",
        pid = 12345L,
        rpcBaseUrl = "http://localhost:9999",
        bridgeHeaders = emptyMap(),
        ide = IdeInfo(name = "Test IDE", version = "2026.1", build = "TEST-1"),
        plugin = PluginInfo(id = "com.test", name = "Test", version = "1.0"),
        ideHome = ideHome,
    )

    @Test
    fun `startable excludes installed backends already running by ideHome`() {
        val a = installed(id = "idea-community-2026.1", home = "/b/idea")
        val b = installed(id = "goland-2026.1", home = "/b/goland")
        val runningGoland = discoveredIde(ideHome = "/b/goland")
        val startable = startableBackends(listOf(a, b), listOf(runningGoland))
        assertEquals(listOf("idea-community-2026.1"), startable.map { it.id })
    }

    @Test
    fun `startable returns all installed when no running IDEs`() {
        val a = installed(id = "idea-community-2026.1", home = "/b/idea")
        val b = installed(id = "goland-2026.1", home = "/b/goland")
        val startable = startableBackends(listOf(a, b), emptyList())
        assertEquals(listOf("idea-community-2026.1", "goland-2026.1"), startable.map { it.id })
    }

    @Test
    fun `startable ignores running IDEs with null ideHome`() {
        val a = installed(id = "idea-community-2026.1", home = "/b/idea")
        val runningNoHome = discoveredIde(ideHome = null)
        val startable = startableBackends(listOf(a), listOf(runningNoHome))
        assertEquals(listOf("idea-community-2026.1"), startable.map { it.id })
    }

    @Test
    fun `startable returns empty when all installed are running`() {
        val a = installed(id = "idea-community-2026.1", home = "/b/idea")
        val runningIdea = discoveredIde(ideHome = "/b/idea")
        val startable = startableBackends(listOf(a), listOf(runningIdea))
        assertEquals(emptyList<InstalledBackend>(), startable)
    }

    @Test
    fun `installedBackends returns backend with ideHome equal to bundleDir realpath`(@TempDir tempDir: Path) {
        // Build the on-disk layout that installedBackends() expects:
        //   <backendsDir>/<id>/backend.json
        //   <backendsDir>/<id>/<bundleDirName>/
        val id = "idea-community-2025.3.3"
        val bundleDirName = "idea-community"
        val backendDir = tempDir.resolve("backends").resolve(id)
        val bundleDir = backendDir.resolve(bundleDirName)
        val launcherPath = "bin/idea.sh"
        val launcherFile = bundleDir.resolve(launcherPath)
        Files.createDirectories(launcherFile.parent)
        Files.createFile(launcherFile)
        val descriptor = BackendDescriptor(
            id = id,
            productKey = "idea-community",
            productCode = "IIC",
            version = "2025.3.3",
            buildNumber = "IC-261.1",
            bundleDirName = bundleDirName,
            launcherPath = launcherPath,
            downloadedAt = "2026-01-01T00:00:00Z",
        )
        writeDescriptor(descriptorPath(backendDir), descriptor)

        val homePaths = HomePaths(tempDir)
        val services = DevrigServices(
            com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost(),
            homePaths = homePaths,
            mcpStdin = System.`in`,
            mcpStdout = System.out,
        )
        val backends = services.installedBackends()

        assertEquals(1, backends.size, "expected one installed backend")
        val backend = backends.single()
        assertEquals(id, backend.id)
        // ideHome must equal normalizeHome(resolveIdeHome(bundleDir)) — no Contents/bin exists, so
        // resolveIdeHome returns bundleDir itself. toRealPath() resolves symlinks (e.g. /var → /private/var on macOS).
        assertEquals(normalizeHome(bundleDir.toString()), backend.ideHome)
    }

    @Test
    fun `installedBackends ideHome points to Contents on macOS-style bundle layout`(@TempDir tempDir: Path) {
        // macOS layout: <id>/X.app/Contents/bin/<launcher>
        val id = "idea-community-2025.3.3"
        val bundleDirName = "idea-IU-261.1.app"
        val backendDir = tempDir.resolve("backends").resolve(id)
        val bundleDir = backendDir.resolve(bundleDirName)
        // Contents/bin must exist for resolveIdeHome to detect macOS layout
        val contentsBin = bundleDir.resolve("Contents/bin")
        Files.createDirectories(contentsBin)
        // Create a launcher file accessible from bundleDir; use a simple relative path for the descriptor
        val simpleLauncherPath = "Contents/bin/idea"
        val launcherFile = bundleDir.resolve(simpleLauncherPath)
        Files.createFile(launcherFile)
        val descriptor = BackendDescriptor(
            id = id,
            productKey = "idea-community",
            productCode = "IC",
            version = "2025.3.3",
            buildNumber = "IC-261.1",
            bundleDirName = bundleDirName,
            launcherPath = simpleLauncherPath,
            downloadedAt = "2026-01-01T00:00:00Z",
        )
        writeDescriptor(descriptorPath(backendDir), descriptor)

        val homePaths = HomePaths(tempDir)
        val services = DevrigServices(
            com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost(),
            homePaths = homePaths,
            mcpStdin = System.`in`,
            mcpStdout = System.out,
        )
        val backends = services.installedBackends()

        assertEquals(1, backends.size, "expected one installed backend")
        val backend = backends.single()
        // On macOS, ideHome must be bundleDir/Contents, not bundleDir itself
        val expectedIdeHome = normalizeHome(bundleDir.resolve("Contents").toString())
        assertEquals(expectedIdeHome, backend.ideHome,
            "macOS bundle: ideHome must point to Contents (the dir containing bin/), not the .app root")
        assertTrue(Path.of(backend.ideHome).endsWith(Path.of(bundleDirName, "Contents")),
            "ideHome should end with ${bundleDirName}/Contents but was: ${backend.ideHome}")
    }

    @Test
    fun `installedBackends ideHome points to bundleDir on Linux-style layout`(@TempDir tempDir: Path) {
        // Linux layout: <id>/idea/bin/<launcher>
        val id = "idea-community-2025.3.3"
        val bundleDirName = "idea"
        val backendDir = tempDir.resolve("backends").resolve(id)
        val bundleDir = backendDir.resolve(bundleDirName)
        val launcherPath = "bin/idea.sh"
        val launcherFile = bundleDir.resolve(launcherPath)
        Files.createDirectories(launcherFile.parent)
        Files.createFile(launcherFile)
        val descriptor = BackendDescriptor(
            id = id,
            productKey = "idea-community",
            productCode = "IC",
            version = "2025.3.3",
            buildNumber = "IC-261.1",
            bundleDirName = bundleDirName,
            launcherPath = launcherPath,
            downloadedAt = "2026-01-01T00:00:00Z",
        )
        writeDescriptor(descriptorPath(backendDir), descriptor)

        val homePaths = HomePaths(tempDir)
        val services = DevrigServices(
            com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost(),
            homePaths = homePaths,
            mcpStdin = System.`in`,
            mcpStdout = System.out,
        )
        val backends = services.installedBackends()

        assertEquals(1, backends.size, "expected one installed backend")
        val backend = backends.single()
        // Linux: ideHome must be bundleDir itself (no Contents/bin subdirectory)
        val expectedIdeHome = normalizeHome(bundleDir.toString())
        assertEquals(expectedIdeHome, backend.ideHome,
            "Linux bundle: ideHome must point to bundleDir itself")
        assertTrue(backend.ideHome.endsWith(bundleDirName),
            "ideHome should end with '$bundleDirName' but was: ${backend.ideHome}")
    }

    @Test
    fun `installedBackends skips backend with missing launcher file`(@TempDir tempDir: Path) {
        // A backend with a descriptor but no actual launcher file is an incomplete install —
        // it must be skipped so it never appears as a startable candidate that fails at start.
        val id = "idea-community-2025.3.3"
        val bundleDirName = "idea-community"
        val backendDir = tempDir.resolve("backends").resolve(id)
        val bundleDir = backendDir.resolve(bundleDirName)
        Files.createDirectories(bundleDir)
        // Deliberately do NOT create the launcher file
        val descriptor = BackendDescriptor(
            id = id,
            productKey = "idea-community",
            productCode = "IC",
            version = "2025.3.3",
            buildNumber = "IC-261.1",
            bundleDirName = bundleDirName,
            launcherPath = "bin/idea.sh",
            downloadedAt = "2026-01-01T00:00:00Z",
        )
        writeDescriptor(descriptorPath(backendDir), descriptor)

        val homePaths = HomePaths(tempDir)
        val services = DevrigServices(
            com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost(),
            homePaths = homePaths,
            mcpStdin = System.`in`,
            mcpStdout = System.out,
        )
        val backends = services.installedBackends()

        assertEquals(0, backends.size, "incomplete install (missing launcher) must be skipped")
    }

    @Test
    fun `installedBackends accepts IU 262 with remote development assets`(@TempDir tempDir: Path) {
        val id = "idea-ultimate-2026.2.0.1"
        val bundleDirName = "idea-ultimate"
        val backendDir = tempDir.resolve("backends").resolve(id)
        val bundleDir = backendDir.resolve(bundleDirName)
        Files.createDirectories(bundleDir.resolve("bin"))
        Files.createFile(bundleDir.resolve("bin/idea.sh"))
        installRemoteDevelopmentAssets(bundleDir)
        writeDescriptor(
            descriptorPath(backendDir),
            BackendDescriptor(
                id = id,
                productKey = "idea-ultimate",
                productCode = "IU",
                version = "2026.2.0.1",
                buildNumber = "IU-262.8665.337",
                bundleDirName = bundleDirName,
                launcherPath = "bin/idea.sh",
                downloadedAt = "2026-01-01T00:00:00Z",
            ),
        )

        val services = DevrigServices(
            com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost(),
            homePaths = HomePaths(tempDir),
            mcpStdin = System.`in`,
            mcpStdout = System.out,
        )

        val backend = services.installedBackends().single()

        assertEquals(id, backend.id)
        assertTrue(
            backend.launcher.fileName.toString() in setOf("remote-dev-server", "remote-dev-server.exe"),
            "IU 262 must use the native Remote Development launcher, actual: ${backend.launcher}",
        )
    }

    @Test
    fun `installedBackends skips IU 262 with missing remote development launcher`(@TempDir tempDir: Path) {
        val id = "idea-ultimate-2026.2.0.1"
        val bundleDirName = "idea-ultimate"
        val backendDir = tempDir.resolve("backends").resolve(id)
        val bundleDir = backendDir.resolve(bundleDirName)
        Files.createDirectories(bundleDir.resolve("bin"))
        Files.createFile(bundleDir.resolve("bin/idea.sh"))
        Files.createDirectories(bundleDir.resolve("plugins/remote-dev-server"))
        writeDescriptor(
            descriptorPath(backendDir),
            BackendDescriptor(
                id = id,
                productKey = "idea-ultimate",
                productCode = "IU",
                version = "2026.2.0.1",
                buildNumber = "IU-262.8665.337",
                bundleDirName = bundleDirName,
                launcherPath = "bin/idea.sh",
                downloadedAt = "2026-01-01T00:00:00Z",
            ),
        )

        val services = DevrigServices(
            com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost(),
            homePaths = HomePaths(tempDir),
            mcpStdin = System.`in`,
            mcpStdout = System.out,
        )

        val backends = services.installedBackends()

        assertTrue(backends.isEmpty(), "IU 262 without a native Remote Development launcher must be skipped")
    }

    @Test
    fun `startable excludes installed backend with a running managed pid`() {
        val a = installed(id = "idea-community-2026.1", home = "/b/idea")
        val b = installed(id = "goland-2026.1", home = "/b/goland")
        // "goland-2026.1" has a live pid file → exclude it via runningManagedIds
        val startable = startableBackends(listOf(a, b), emptyList(), runningManagedIds = setOf("goland-2026.1"))
        assertEquals(listOf("idea-community-2026.1"), startable.map { it.id },
            "a managed backend with a live pid must be excluded from startable")
    }

    @Test
    fun `startable with runningManagedIds and ideHome both in effect`() {
        val a = installed(id = "idea-community-2026.1", home = "/b/idea")
        val b = installed(id = "goland-2026.1", home = "/b/goland")
        // "idea-community" excluded by ideHome match, "goland" excluded by managed pid
        val runningIdea = discoveredIde(ideHome = "/b/idea")
        val startable = startableBackends(listOf(a, b), listOf(runningIdea), runningManagedIds = setOf("goland-2026.1"))
        assertTrue(startable.isEmpty(), "both exclusion paths must work together")
    }

    private fun installRemoteDevelopmentAssets(bundleDir: Path) {
        val ideHome = resolveIdeHome(bundleDir)
        Files.createDirectories(ideHome.resolve("bin"))
        Files.writeString(ideHome.resolve("bin/remote-dev-server"), "remote development launcher")
            .toFile()
            .setExecutable(true)
        Files.writeString(ideHome.resolve("bin/remote-dev-server.exe"), "remote development launcher")
        val pluginJar = ideHome.resolve("plugins/remote-dev-server/lib/remote-dev-server.jar")
        Files.createDirectories(pluginJar.parent)
        Files.writeString(pluginJar, "remote development plugin")
    }


}
