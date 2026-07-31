/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RemoteDevelopmentLauncherResolverTest {

    @Test
    fun `IU 262 selects trusted non-interactive remote development launch`(
        @TempDir tempDir: Path,
    ) {
        val bundle = tempDir.resolve("iu-262-launch")
        writeProductLauncher(bundle)
        writeRemoteDevelopmentBundle(bundle, HostOs.LINUX)
        val descriptor = descriptor(
            productKey = "idea-ultimate",
            productCode = "IU",
            version = "2026.2.0.1",
            buildNumber = "IU-262.8665.337",
        )

        val resolved = ManagedBackendLauncherResolver(HostOs.LINUX).resolve(descriptor, bundle)

        assertEquals(bundle.resolve("bin/remote-dev-server"), resolved.executable)
        assertEquals(listOf("run"), resolved.arguments)
        assertEquals(bundle, resolved.workingDirectory)
        assertEquals(
            mapOf(
                "REMOTE_DEV_JDK_DETECTION" to "false",
                "REMOTE_DEV_NON_INTERACTIVE" to "1",
                "REMOTE_DEV_TRUST_PROJECTS" to "1",
            ),
            resolved.environment,
        )
    }

    @Test
    fun `Community 262 retains standard product launcher`(
        @TempDir tempDir: Path,
    ) {
        val bundle = tempDir.resolve("ic-262-launch")
        writeProductLauncher(bundle)
        writeRemoteDevelopmentBundle(bundle, HostOs.LINUX)
        val descriptor = descriptor(
            productKey = "idea-community",
            productCode = "IC",
            version = "2026.2.0.1",
            buildNumber = "IC-262.8665.337",
        )

        val resolved = ManagedBackendLauncherResolver(HostOs.LINUX).resolve(descriptor, bundle)

        assertEquals(bundle.resolve("bin/idea.sh"), resolved.executable)
        assertEquals(emptyList(), resolved.arguments)
        assertEquals(emptyMap(), resolved.environment)
    }

    @Test
    fun `Ultimate before 262 retains standard product launcher`(
        @TempDir tempDir: Path,
    ) {
        val bundle = tempDir.resolve("iu-261-launch")
        writeProductLauncher(bundle)
        val descriptor = descriptor(
            productKey = "idea-ultimate",
            productCode = "IU",
            version = "2026.1.4",
            buildNumber = "IU-261.26222.65",
        )

        val resolved = ManagedBackendLauncherResolver(HostOs.LINUX).resolve(descriptor, bundle)

        assertEquals(bundle.resolve("bin/idea.sh"), resolved.executable)
        assertEquals(emptyList(), resolved.arguments)
        assertEquals(emptyMap(), resolved.environment)
    }

    @Test
    fun `Ultimate after 262 retains standard product launcher`(
        @TempDir tempDir: Path,
    ) {
        val bundle = tempDir.resolve("iu-263-launch")
        writeProductLauncher(bundle)
        val descriptor = descriptor(
            productKey = "idea-ultimate",
            productCode = "IU",
            version = "2026.3",
            buildNumber = "IU-263.1",
        )

        val resolved = ManagedBackendLauncherResolver(HostOs.LINUX).resolve(descriptor, bundle)

        assertEquals(bundle.resolve("bin/idea.sh"), resolved.executable)
        assertEquals(emptyList(), resolved.arguments)
        assertEquals(emptyMap(), resolved.environment)
    }

    @Test
    fun `resolves linux native remote development launcher`(
        @TempDir tempDir: Path,
    ) {
        val bundle = writeRemoteDevelopmentBundle(tempDir.resolve("idea"), HostOs.LINUX)

        val resolved = RemoteDevelopmentLauncherResolver(HostOs.LINUX).resolve(bundle)

        assertEquals(bundle, resolved.ideHome)
        assertEquals(bundle.resolve("bin/remote-dev-server"), resolved.executable)
        assertEquals(listOf("run"), resolved.arguments)
    }

    @Test
    fun `resolves macOS native launcher inside app Contents`(
        @TempDir tempDir: Path,
    ) {
        val bundle = writeRemoteDevelopmentBundle(tempDir.resolve("IntelliJ IDEA.app"), HostOs.MAC)

        val resolved = RemoteDevelopmentLauncherResolver(HostOs.MAC).resolve(bundle)

        assertEquals(bundle.resolve("Contents"), resolved.ideHome)
        assertEquals(bundle.resolve("Contents/bin/remote-dev-server"), resolved.executable)
        assertEquals(listOf("run"), resolved.arguments)
    }

    @Test
    fun `resolves windows native remote development launcher`(
        @TempDir tempDir: Path,
    ) {
        val bundle = writeRemoteDevelopmentBundle(tempDir.resolve("idea"), HostOs.WINDOWS)

        val resolved = RemoteDevelopmentLauncherResolver(HostOs.WINDOWS).resolve(bundle)

        assertEquals(bundle.resolve("bin/remote-dev-server.exe"), resolved.executable)
        assertEquals(listOf("run"), resolved.arguments)
    }

    @Test
    fun `missing native launcher fails clearly`(
        @TempDir tempDir: Path,
    ) {
        val bundle = tempDir.resolve("idea")
        Files.createDirectories(bundle.resolve("plugins/remote-dev-server"))

        val error = assertFailsWith<ManagedBackendValidationException> {
            RemoteDevelopmentLauncherResolver(HostOs.LINUX).resolve(bundle)
        }

        assertTrue(error.message!!.contains("native Remote Development launcher is missing"), error.message)
        assertTrue(error.message!!.contains("bin/remote-dev-server"), error.message)
    }

    @Test
    fun `empty native launcher fails clearly`(
        @TempDir tempDir: Path,
    ) {
        val bundle = writeRemoteDevelopmentBundle(tempDir.resolve("idea"), HostOs.LINUX)
        Files.writeString(bundle.resolve("bin/remote-dev-server"), "")

        val error = assertFailsWith<ManagedBackendValidationException> {
            RemoteDevelopmentLauncherResolver(HostOs.LINUX).resolve(bundle)
        }

        assertTrue(error.message!!.contains("native Remote Development launcher is empty"), error.message)
    }

    @Test
    fun `non executable Unix native launcher fails clearly`(
        @TempDir tempDir: Path,
    ) {
        val bundle = writeRemoteDevelopmentBundle(tempDir.resolve("idea"), HostOs.LINUX)
        bundle.resolve("bin/remote-dev-server").toFile().setExecutable(false)

        val error = assertFailsWith<ManagedBackendValidationException> {
            RemoteDevelopmentLauncherResolver(HostOs.LINUX).resolve(bundle)
        }

        assertTrue(error.message!!.contains("native Remote Development launcher is not executable"), error.message)
    }

    @Test
    fun `missing remote development plugin fails clearly`(
        @TempDir tempDir: Path,
    ) {
        val bundle = tempDir.resolve("idea")
        val launcher = bundle.resolve("bin/remote-dev-server")
        Files.createDirectories(launcher.parent)
        Files.writeString(launcher, "#!/usr/bin/env sh\n")
        launcher.toFile().setExecutable(true)

        val error = assertFailsWith<ManagedBackendValidationException> {
            RemoteDevelopmentLauncherResolver(HostOs.LINUX).resolve(bundle)
        }

        assertTrue(error.message!!.contains("Remote Development plugin is missing"), error.message)
        assertTrue(error.message!!.contains("plugins/remote-dev-server"), error.message)
    }

    @Test
    fun `missing remote development plugin jar fails clearly`(
        @TempDir tempDir: Path,
    ) {
        val bundle = writeRemoteDevelopmentBundle(tempDir.resolve("idea"), HostOs.LINUX)
        Files.delete(bundle.resolve("plugins/remote-dev-server/lib/remote-dev-server.jar"))

        val error = assertFailsWith<ManagedBackendValidationException> {
            RemoteDevelopmentLauncherResolver(HostOs.LINUX).resolve(bundle)
        }

        assertTrue(error.message!!.contains("Remote Development plugin jar is missing"), error.message)
    }

    @Test
    fun `empty remote development plugin jar fails clearly`(
        @TempDir tempDir: Path,
    ) {
        val bundle = writeRemoteDevelopmentBundle(tempDir.resolve("idea"), HostOs.LINUX)
        Files.writeString(bundle.resolve("plugins/remote-dev-server/lib/remote-dev-server.jar"), "")

        val error = assertFailsWith<ManagedBackendValidationException> {
            RemoteDevelopmentLauncherResolver(HostOs.LINUX).resolve(bundle)
        }

        assertTrue(error.message!!.contains("Remote Development plugin jar is empty"), error.message)
    }

    private fun writeRemoteDevelopmentBundle(bundle: Path, hostOs: HostOs): Path {
        val ideHome = if (hostOs == HostOs.MAC) bundle.resolve("Contents") else bundle
        val launcherName = if (hostOs == HostOs.WINDOWS) "remote-dev-server.exe" else "remote-dev-server"
        val launcher = ideHome.resolve("bin").resolve(launcherName)
        Files.createDirectories(launcher.parent)
        Files.writeString(launcher, "remote development launcher")
        if (hostOs != HostOs.WINDOWS) launcher.toFile().setExecutable(true)
        val pluginJar = ideHome.resolve("plugins/remote-dev-server/lib/remote-dev-server.jar")
        Files.createDirectories(pluginJar.parent)
        Files.writeString(pluginJar, "remote development plugin")
        return bundle
    }

    private fun writeProductLauncher(bundle: Path) {
        val launcher = bundle.resolve("bin/idea.sh")
        Files.createDirectories(launcher.parent)
        Files.writeString(launcher, "#!/usr/bin/env sh\n")
    }

    private fun descriptor(
        productKey: String,
        productCode: String,
        version: String,
        buildNumber: String,
    ): BackendDescriptor = BackendDescriptor(
        id = "$productKey-$version",
        productKey = productKey,
        productCode = productCode,
        version = version,
        buildNumber = buildNumber,
        bundleDirName = "idea",
        launcherPath = "bin/idea.sh",
        downloadedAt = "2026-07-31T00:00:00Z",
    )
}
