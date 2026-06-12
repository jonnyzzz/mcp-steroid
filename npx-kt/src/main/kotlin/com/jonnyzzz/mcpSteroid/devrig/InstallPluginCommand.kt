/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.devrig.monitor.AboutResponse
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.devrig.monitor.IntelliJPortDiscovery
import com.jonnyzzz.mcpSteroid.server.productCodeFromBuild
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.apache.commons.compress.archivers.zip.ZipFile

private val log = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.devrig.InstallPluginCommand")

private const val MARKETPLACE_PLUGIN_ID = "com.jonnyzzz.mcp-steroid"
private const val INSTALL_PLUGIN_PATH = "/api/installPlugin"

fun DevrigServices.runInstallPluginCommand(
    command: DevrigCommand.DevrigCommandInstallPlugin,
): Int = runInstallPluginCommand(
    out = mcpStdout,
    err = System.err,
    backendId = command.id,
    homePaths = homePaths,
    httpClient = commandHttpClient,
    portDiscovery = portDiscovery,
)

fun runInstallPluginCommand(
    out: PrintStream,
    err: PrintStream,
    backendId: String?,
    homePaths: HomePaths = resolveHomePaths(),
    httpClient: HttpClient? = null,
    portDiscovery: IntelliJPortDiscovery? = null,
): Int {
    val ides = runBlocking(Dispatchers.IO) { scanMarkersOnce(homePaths) }

    if (backendId == null) {
        val portIdes = runBlocking(Dispatchers.IO) { collectPortDiscoveredIdesCompat(portDiscovery) }
        return runInstallPluginListCommand(out, ides, portIdes)
    }

    val markerTarget = resolveMarkerIde(ides, backendId)
    if (markerTarget != null) {
        return runInstallPluginInstall(out, err, markerTarget, httpClient)
    }

    val portTarget = runBlocking(Dispatchers.IO) { resolvePortIde(portDiscovery, backendId) }
    if (portTarget != null) {
        return runInstallPluginInstallPort(out, err, portTarget)
    }

    return runInstallPluginErrorMessage(out, "Unknown IDE target '$backendId'. Run 'devrig install plugin' with no id to list available.")
}

internal suspend fun collectPortDiscoveredIdesCompat(
    portDiscovery: IntelliJPortDiscovery?,
): Set<DiscoveredIdeByPort> {
    if (portDiscovery == null) return emptySet()
    portDiscovery.scanOnce()
    return portDiscovery.detected.value
}

internal fun resolveMarkerIde(
    ides: Set<DiscoveredIde>,
    backendId: String,
): DiscoveredIde? {
    val pid = backendId.removePrefix("pid-").toLongOrNull() ?: return null
    return ides.find { it.pid == pid }
}

internal suspend fun resolvePortIde(
    portDiscovery: IntelliJPortDiscovery?,
    backendId: String,
): DiscoveredIdeByPort? {
    val port = backendId.removePrefix("port-").toIntOrNull() ?: return null
    if (portDiscovery == null) return null
    portDiscovery.scanOnce()
    return portDiscovery.detected.value.find { it.port == port }
}

fun runInstallPluginListCommand(
    out: PrintStream,
    ides: Set<DiscoveredIde>,
    portIdes: Set<DiscoveredIdeByPort> = emptySet(),
): Int {
    val combined = buildCombinedList(ides, portIdes)

    if (combined.isEmpty()) {
        out.println("No running IDEs discovered.")
        out.println()
        return 0
    }

    out.println("Discovered IDEs for plugin installation:")
    out.println()
    for ((index, entry) in combined.withIndex()) {
        out.println("  [${index + 1}] ${entry.label}")
        out.println("        run: ${entry.command}")
    }
    out.println()
    return 0
}

private data class CombinedInstallEntry(
    val label: String,
    val command: String,
)

private fun buildCombinedList(
    ides: Set<DiscoveredIde>,
    portIdes: Set<DiscoveredIdeByPort>,
): List<CombinedInstallEntry> {
    val markerEntries = ides.map { ide ->
        CombinedInstallEntry(
            label = ide.label,
            command = "devrig install plugin pid-${ide.pid}",
        )
    }
    val portEntries = portIdes.map { ide ->
        CombinedInstallEntry(
            label = ide.label,
            command = "devrig install plugin port-${ide.port}",
        )
    }
    return (markerEntries + portEntries).sortedBy { it.label }
}

private fun runInstallPluginErrorMessage(out: PrintStream, message: String): Int {
    out.println(message)
    out.println()
    return 64
}

private fun deriveIdeBaseUrl(rpcBaseUrl: String): String {
    val url = rpcBaseUrl.trimEnd('/')
    return when {
        url.endsWith("/api/jonnyzzz/mcp-steroid/v1") ->
            url.removeSuffix("/api/jonnyzzz/mcp-steroid/v1")
        url.contains("/api/") -> url.substringBeforeLast("/api/")
        else -> url
    }
}

internal suspend fun tryInstallViaRestApi(
    httpClient: HttpClient?,
    baseUrl: String,
): Boolean {
    if (httpClient == null) return false
    return try {
        val response = httpClient.post("$baseUrl$INSTALL_PLUGIN_PATH") {
            setBody(buildJsonObject {
                put("pluginId", MARKETPLACE_PLUGIN_ID)
                put("action", "install")
            }.toString())
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        }
        response.status.isSuccess()
    } catch (e: Exception) {
        log.debug("REST API install failed for $baseUrl: ${e.message}")
        false
    }
}

private fun runInstallPluginInstall(
    out: PrintStream,
    err: PrintStream,
    ide: DiscoveredIde,
    httpClient: HttpClient?,
): Int {
    val baseUrl = deriveIdeBaseUrl(ide.rpcBaseUrl)

    val installed = runBlocking(Dispatchers.IO) {
        tryInstallViaRestApi(httpClient, baseUrl)
    }
    if (installed) {
        out.println("Plugin installation requested via ${ide.label}'s REST API.")
        out.println("  The IDE will download and install MCP Steroid from the marketplace.")
        out.println("  Restart the IDE to load the plugin.")
        out.println()
        return 0
    }

    return runInstallPluginDirect(out, err, ide)
}

private fun runInstallPluginInstallPort(
    out: PrintStream,
    err: PrintStream,
    ide: DiscoveredIdeByPort,
): Int {
    val baseUrl = ide.baseUrl.trimEnd('/')

    val installed = runBlocking(Dispatchers.IO) {
        tryInstallViaRestApi(null, baseUrl)
    }
    if (installed) {
        out.println("Plugin installation requested via ${ide.label}'s REST API.")
        out.println("  The IDE will download and install MCP Steroid from the marketplace.")
        out.println("  Restart the IDE to load the plugin.")
        out.println()
        return 0
    }

    return runInstallPluginDirectPort(out, err, ide)
}

private fun runInstallPluginDirect(
    out: PrintStream,
    err: PrintStream,
    ide: DiscoveredIde,
): Int {
    val pluginsDir = resolvePluginsDirForMarker(ide)
        ?: return runInstallPluginErrorMessage(out, "Could not resolve plugins directory for ${ide.label}.")
    return unpackPluginIntoDir(out, err, ide.label, pluginsDir, ide.pid)
}

private fun runInstallPluginDirectPort(
    out: PrintStream,
    err: PrintStream,
    ide: DiscoveredIdeByPort,
): Int {
    val pluginsDir = resolvePluginsDirForPort(ide)
        ?: return runInstallPluginErrorMessage(out, "Could not resolve plugins directory for ${ide.label}.")
    return unpackPluginIntoDir(out, err, ide.label, pluginsDir, null)
}

internal fun resolvePluginsDirForMarker(ide: DiscoveredIde): Path? {
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

internal fun resolvePluginsDirForPort(ide: DiscoveredIdeByPort): Path? {
    val buildNumber = ide.buildNumber ?: return null
    val about = AboutResponse(
        name = ide.productFullName,
        productName = ide.productName,
        edition = ide.edition,
        baselineVersion = ide.baselineVersion,
        buildNumber = buildNumber,
    )
    val productCode = productCodeFromBuild(buildNumber)
    val selectorInfo = deriveIdePathSelector(about, productCode)
    return defaultIdePluginsDir(
        selector = selectorInfo.selector,
        vendor = selectorInfo.vendor,
    )
}

private fun unpackPluginIntoDir(
    out: PrintStream,
    err: PrintStream,
    label: String,
    pluginsDir: Path,
    pid: Long?,
): Int {
    val pluginZip = DevrigRoot.ijPluginZip()
    if (!Files.isRegularFile(pluginZip)) {
        err.println("Plugin zip not found: $pluginZip")
        return 64
    }

    val pluginDestDir = pluginsDir.resolve("mcp-steroid")

    if (pluginDestDir.isDirectory() && (pluginDestDir.resolve("lib").isDirectory() || pluginDestDir.resolve("EULA").isRegularFile())) {
        out.println("MCP Steroid plugin already installed in $label.")
        out.println("  Location: $pluginDestDir")
        out.println()
        return 0
    }

    out.println("Installing MCP Steroid plugin into $label...")
    out.println("  Plugin source: $pluginZip")
    out.println("  Target: $pluginDestDir")
    out.println()

    try {
        unpackPluginZip(pluginZip, pluginDestDir)
        out.println("Plugin installed successfully.")
        out.println()
        out.println("Restart the IDE to load the plugin.")
        pid?.let { out.println("Then run: devrig backend provision pid-$it") }
        return 0
    } catch (e: Exception) {
        err.println("Failed to install plugin: ${e.message}")
        return 64
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
