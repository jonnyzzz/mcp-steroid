/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
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

fun DevrigServices.runInstallPluginCommand(
    command: DevrigCommand.DevrigCommandInstallPlugin,
): Int = runInstallPluginCommand(
    out = mcpStdout,
    err = System.err,
    backendId = command.id,
    homePaths = homePaths,
)

fun runInstallPluginCommand(
    out: PrintStream,
    err: PrintStream,
    backendId: String?,
    homePaths: HomePaths = resolveHomePaths(),
): Int {
    val ides = scanMarkersOnce(homePaths)

    if (backendId == null) {
        return runInstallPluginListCommand(out, ides)
    }

    val target = resolveMarkerIde(ides, backendId)
        ?: return runInstallPluginErrorMessage(out, "Unknown IDE target '$backendId'. Run 'devrig install plugin' with no id to list available.")

    return runInstallPluginInstall(out, err, target)
}

private fun resolveMarkerIde(
    ides: Set<DiscoveredIde>,
    backendId: String,
): DiscoveredIde? {
    val pid = backendId.removePrefix("pid-").toLongOrNull() ?: return null
    return ides.find { it.pid == pid }
}

fun runInstallPluginListCommand(
    out: PrintStream,
    ides: Set<DiscoveredIde>,
): Int {
    if (ides.isEmpty()) {
        out.println("No running IDEs discovered.")
        out.println()
        return 0
    }

    out.println("Discovered IDEs for plugin installation:")
    out.println()
    for ((index, ide) in ides.sortedBy { it.pid }.withIndex()) {
        out.println("  [${index + 1}] ${ide.label}")
        out.println("        run: devrig install plugin pid-${ide.pid}")
    }
    out.println()
    return 0
}

private fun runInstallPluginErrorMessage(out: PrintStream, message: String): Int {
    out.println(message)
    out.println()
    return 64
}

private fun runInstallPluginInstall(
    out: PrintStream,
    err: PrintStream,
    ide: DiscoveredIde,
): Int {
    val pluginsDir = resolvePluginsDirForMarker(ide)
        ?: return runInstallPluginErrorMessage(out, "Could not resolve plugins directory for ${ide.label}.")

    val pluginZip = DevrigRoot.ijPluginZip()
    if (!Files.isRegularFile(pluginZip)) {
        err.println("Plugin zip not found: $pluginZip")
        return 64
    }

    val pluginDestDir = pluginsDir.resolve("mcp-steroid")

    if (pluginDestDir.isDirectory() && (pluginDestDir.resolve("lib").isDirectory() || pluginDestDir.resolve("EULA").isRegularFile())) {
        out.println("MCP Steroid plugin already installed in ${ide.label}.")
        out.println("  Location: $pluginDestDir")
        out.println()
        return 0
    }

    out.println("Installing MCP Steroid plugin into ${ide.label}...")
    out.println("  Plugin source: $pluginZip")
    out.println("  Target: $pluginDestDir")
    out.println()

    try {
        unpackPluginZip(pluginZip, pluginDestDir)
        out.println("Plugin installed successfully.")
        out.println()
        out.println("Restart the IDE to load the plugin.")
        out.println("Then run: devrig backend provision pid-${ide.pid}")
        return 0
    } catch (e: Exception) {
        err.println("Failed to install plugin: ${e.message}")
        return 64
    }
}

private fun resolvePluginsDirForMarker(ide: DiscoveredIde): Path? {
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
