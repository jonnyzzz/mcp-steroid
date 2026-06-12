/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.devrig.monitor.IntelliJPortDiscovery
import io.ktor.client.HttpClient
import org.slf4j.LoggerFactory
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.zip.ZipFile

private val log = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.devrig.InstallPluginCommand")

const val INSTALL_PLUGIN_ACTION_ID = "install-plugin"

fun DevrigServices.runInstallPluginCommand(
    command: DevrigCommand.DevrigCommandInstallPlugin,
): Int = runInstallPluginCommand(
    out = mcpStdout,
    err = System.err,
    httpClient = commandHttpClient,
    backendId = command.id,
    homePaths = homePaths,
)

fun runInstallPluginCommand(
    out: PrintStream,
    err: PrintStream,
    httpClient: HttpClient,
    backendId: String?,
    homePaths: HomePaths = resolveHomePaths(),
): Int {
    if (backendId == null) {
        val ides = scanMarkersOnce(homePaths)
        val provisionTargets = runBlocking {
            detectProvisionTargets(httpClient, IntelliJPortDiscovery.DEFAULT_PORT_RANGES)
        }
        return runInstallPluginListCommand(out, ides, provisionTargets)
    }

    val ides = scanMarkersOnce(homePaths)
    val provisionTargets = runBlocking {
        detectProvisionTargets(httpClient, IntelliJPortDiscovery.DEFAULT_PORT_RANGES)
    }

    val targetIde = resolveIdeForInstall(ides, provisionTargets, backendId)
        ?: return runInstallPluginErrorMessage(out, "Unknown IDE target '$backendId'. Run 'devrig install plugin' with no id to list available.")

    return runInstallPluginInstall(out, err, targetIde)
}

private fun resolveIdeForInstall(
    ides: Set<DiscoveredIde>,
    provisionTargets: List<ProvisionTarget>,
    backendId: String,
): InstallTarget? {
    // Try pid-based id format: "pid-12345"
    val pidSuffix = backendId.removePrefix("pid-")
    if (pidSuffix != backendId) {
        val targetPid = pidSuffix.toLongOrNull() ?: return null
        val ide = ides.find { it.pid == targetPid }
        if (ide != null) {
            return InstallTarget.Marker(ide)
        }
    }

    // Try port-based id format: "port-63342"
    val portSuffix = backendId.removePrefix("port-")
    if (portSuffix != backendId) {
        val port = portSuffix.toIntOrNull() ?: return null
        val target = provisionTargets.find { it.id == backendId }
        if (target != null) {
            return InstallTarget.Port(target.ide)
        }
    }

    return null
}

fun runInstallPluginListCommand(
    out: PrintStream,
    ides: Set<DiscoveredIde>,
    provisionTargets: List<ProvisionTarget>,
): Int {
    val targets = buildList {
        ides.forEach { ide ->
            add(InstallTarget.Marker(ide))
        }
        provisionTargets.forEach { target ->
            add(InstallTarget.Port(target.ide))
        }
    }

    if (targets.isEmpty()) {
        out.println("No running IDEs discovered.")
        out.println()
        return 64
    }

    out.println("Discovered IDEs for plugin installation:")
    out.println()
    for ((index, target) in targets.withIndex()) {
        val label = when (target) {
            is InstallTarget.Marker -> "${target.ide.label}"
            is InstallTarget.Port -> "${target.ide.label} (port-discovered)"
        }
        out.println("  [${index + 1}] ${label}")
        out.println("        run: devrig install plugin ${backendIdForTarget(target)}")
    }
    out.println()
    return 0
}

private fun runInstallPluginErrorMessage(out: PrintStream, message: String): Int {
    out.println(message)
    out.println()
    return 64
}

private sealed interface InstallTarget {
    val pid: Long
    val ideLabel: String

    data class Marker(val ide: DiscoveredIde) : InstallTarget {
        override val pid: Long get() = ide.pid
        override val ideLabel: String get() = ide.label
    }

    data class Port(val ide: DiscoveredIdeByPort) : InstallTarget {
        override val pid: Long get() = ide.port.toLong()
        override val ideLabel: String get() = ide.label
    }
}

private fun runInstallPluginInstall(
    out: PrintStream,
    err: PrintStream,
    target: InstallTarget,
): Int {
    val pluginsDir = when (target) {
        is InstallTarget.Marker -> resolvePluginsDirForMarker(target.ide)
        is InstallTarget.Port -> null // Port-discovered IDEs need the plugin to already be running
    } ?: return 64

    val pluginZip = DevrigRoot.ijPluginZip()
    if (!Files.isRegularFile(pluginZip)) {
        err.println("Plugin zip not found: $pluginZip")
        return 64
    }

    val pluginDestDir = pluginsDir.resolve("mcp-steroid")

    // Check if already installed
    if (pluginDestDir.isDirectory() && (pluginDestDir.resolve("lib").isDirectory() || pluginDestDir.resolve("EULA").isRegularFile())) {
        out.println("MCP Steroid plugin already installed in ${target.ideLabel}.")
        out.println("  Location: ${pluginDestDir}")
        out.println()
        return 0
    }

    out.println("Installing MCP Steroid plugin into ${target.ideLabel}...")
    out.println("  Plugin source: $pluginZip")
    out.println("  Target: ${pluginDestDir}")
    out.println()

    try {
        unpackPluginZip(pluginZip, pluginDestDir)
        out.println("Plugin installed successfully.")
        out.println()
        out.println("Restart the IDE to load the plugin.")
        out.println("Then run: devrig backend provision ${backendIdForTarget(target)} to register it.")
        return 0
    } catch (e: Exception) {
        err.println("Failed to install plugin: ${e.message}")
        return 64
    }
}

private fun resolvePluginsDirForMarker(ide: DiscoveredIde): Path? {
    // Read the discovery JSON file for marker-discovered IDEs
    val markerPath = Path.of(ide.markerPath)
    val discoveryDir = markerPath.parent.parent.resolve("discovery")
    val discoveryFile = discoveryDir.resolve("${ide.pid}-ide-instance.json")

    if (!Files.isRegularFile(discoveryFile)) {
        log.debug("No discovery file for marker ide pid={}, path={}", ide.pid, discoveryFile)
        return null
    }

    return try {
        val text = Files.readString(discoveryFile)
        val info = IdeInstanceDiscoveryJson.decode(text)
        info.pluginsDir
    } catch (e: Exception) {
        log.warn("Failed to parse discovery file {}: {}", discoveryFile, e.message)
        null
    }
}

private fun unpackPluginZip(source: Path, target: Path) {
    val normalizedTarget = target.toAbsolutePath().normalize()
    Files.createDirectories(normalizedTarget)
    ZipFile.builder().setPath(source).get().use { zip ->
        val entries = zip.entries
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val destination = normalizedTarget.resolve(entry.name).normalize()
            require(destination.startsWith(normalizedTarget)) {
                "Plugin ZIP entry escapes target directory: ${entry.name}"
            }
            if (entry.isDirectory) {
                Files.createDirectories(destination)
            } else {
                Files.createDirectories(destination.parent)
                zip.getInputStream(entry).use { input ->
                    Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}

private fun backendIdForTarget(target: InstallTarget): String = when (target) {
    is InstallTarget.Marker -> "pid-${target.ide.pid}"
    is InstallTarget.Port -> "port-${target.ide.port}"
}