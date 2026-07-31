/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveHostOs
import java.nio.file.Files
import java.nio.file.Path

data class RemoteDevelopmentLaunchSpec(
    val ideHome: Path,
    val executable: Path,
    val arguments: List<String>,
)

data class ManagedBackendLaunchSpec(
    val executable: Path,
    val arguments: List<String>,
    val workingDirectory: Path,
    val environment: Map<String, String>,
)

class ManagedBackendLauncherResolver(
    private val hostOs: HostOs = resolveHostOs(),
) {
    fun resolve(descriptor: BackendDescriptor, bundleDir: Path): ManagedBackendLaunchSpec {
        if (usesRemoteDevelopment(descriptor.productKey, descriptor.buildNumber)) {
            val remote = RemoteDevelopmentLauncherResolver(hostOs).resolve(bundleDir)
            return ManagedBackendLaunchSpec(
                executable = remote.executable,
                arguments = remote.arguments,
                workingDirectory = remote.ideHome,
                environment = mapOf(
                    // The native Remote Development launcher otherwise enables
                    // `jdk.configure.existing`. IU 262 then tries to create an SDK from inside a
                    // background write action and logs a deadlock SEVERE on a clean profile.
                    // The backend image/project already supplies its JDK; do not run the broken
                    // workstation-style auto-detection path in this unattended process.
                    "REMOTE_DEV_JDK_DETECTION" to "false",
                    "REMOTE_DEV_NON_INTERACTIVE" to "1",
                    "REMOTE_DEV_TRUST_PROJECTS" to "1",
                ),
            )
        }

        val executable = bundleDir.resolve(descriptor.launcherPath)
        if (!Files.isRegularFile(executable)) {
            throw ManagedBackendValidationException("The product launcher is missing: $executable")
        }
        return ManagedBackendLaunchSpec(
            executable = executable,
            arguments = emptyList(),
            workingDirectory = bundleDir,
            environment = emptyMap(),
        )
    }

    fun validateRequiredAssets(productKey: String, buildNumber: String?, bundleDir: Path) {
        if (usesRemoteDevelopment(productKey, buildNumber)) {
            RemoteDevelopmentLauncherResolver(hostOs).resolve(bundleDir)
        }
    }

    fun usesRemoteDevelopment(descriptor: BackendDescriptor): Boolean =
        usesRemoteDevelopment(descriptor.productKey, descriptor.buildNumber)

    fun usesRemoteDevelopment(productKey: String, buildNumber: String?): Boolean {
        if (productKey != "idea-ultimate") return false
        val baseline = buildNumber
            ?.substringAfterLast('-')
            ?.substringBefore('.')
            ?.toIntOrNull()
        return baseline == 262
    }
}

class RemoteDevelopmentLauncherResolver(
    private val hostOs: HostOs = resolveHostOs(),
) {
    fun resolve(bundleDir: Path): RemoteDevelopmentLaunchSpec {
        val ideHome = resolveIdeHome(bundleDir).normalize()
        val launcherName = if (hostOs == HostOs.WINDOWS) {
            "remote-dev-server.exe"
        } else {
            "remote-dev-server"
        }
        val executable = ideHome.resolve("bin").resolve(launcherName)
        if (!Files.isRegularFile(executable)) {
            throw ManagedBackendValidationException(
                "The native Remote Development launcher is missing: $executable",
            )
        }
        if (Files.size(executable) == 0L) {
            throw ManagedBackendValidationException(
                "The native Remote Development launcher is empty: $executable",
            )
        }
        if (hostOs != HostOs.WINDOWS && !Files.isExecutable(executable)) {
            throw ManagedBackendValidationException(
                "The native Remote Development launcher is not executable: $executable",
            )
        }

        val remoteDevelopmentPlugin = ideHome.resolve("plugins/remote-dev-server")
        if (!Files.isDirectory(remoteDevelopmentPlugin)) {
            throw ManagedBackendValidationException(
                "The Remote Development plugin is missing: $remoteDevelopmentPlugin",
            )
        }
        val remoteDevelopmentPluginJar = remoteDevelopmentPlugin.resolve("lib/remote-dev-server.jar")
        if (!Files.isRegularFile(remoteDevelopmentPluginJar)) {
            throw ManagedBackendValidationException(
                "The Remote Development plugin jar is missing: $remoteDevelopmentPluginJar",
            )
        }
        if (Files.size(remoteDevelopmentPluginJar) == 0L) {
            throw ManagedBackendValidationException(
                "The Remote Development plugin jar is empty: $remoteDevelopmentPluginJar",
            )
        }

        return RemoteDevelopmentLaunchSpec(
            ideHome = ideHome,
            executable = executable,
            arguments = listOf("run"),
        )
    }
}
