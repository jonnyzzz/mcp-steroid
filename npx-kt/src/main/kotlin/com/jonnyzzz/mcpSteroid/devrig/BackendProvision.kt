/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.resolveHostOs
import com.jonnyzzz.mcpSteroid.server.productCodeFromBuild
import com.jonnyzzz.mcpSteroid.devrig.monitor.AboutResponse
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.devrig.monitor.IntelliJPortDiscovery
import com.jonnyzzz.mcpSteroid.devrig.monitor.aboutJson
import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import java.nio.file.Path

const val PROVISION_ACTION_ID = "provision"
const val MCP_STEROID_PLUGIN_DIR_NAME = "mcp-steroid"

data class ProvisionResult(
    val id: String,
    val ide: DiscoveredIdeByPort,
    val about: AboutResponse,
    val productCode: String?,
    val selector: String,
    val pluginsDir: Path,
    val pluginSource: Path,
    val suggestedDestination: Path,
)

data class ProvisionTarget(
    val id: String,
    val ide: DiscoveredIdeByPort,
) {
    val command: String get() = provisionCommand(id)
}

suspend fun provisionBackend(
    id: String,
    httpClient: HttpClient,
): ProvisionResult = BackendProvisioner().provision(id, httpClient)

class BackendProvisioner(
    private val bundledPluginResolver: BundledPluginResolver = ClasspathBundledPluginResolver(),
    private val os: HostOs = resolveHostOs(),
    private val userHome: Path = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize(),
    private val env: Map<String, String> = System.getenv(),
    private val portRanges: List<IntRange> = IntelliJPortDiscovery.DEFAULT_PORT_RANGES,
) {
    suspend fun provision(id: String, httpClient: HttpClient): ProvisionResult {
        return provision(id, httpClient) {
            detectProvisionTargets(httpClient, portRanges)
        }
    }

    suspend fun provision(
        id: String,
        httpClient: HttpClient,
        targets: suspend () -> List<ProvisionTarget>,
    ): ProvisionResult {
        val targets = targets()
        val target = targets.singleOrNull { it.id == id }
            ?: throw ManagedBackendValidationException(unknownProvisionTargetMessage(id, targets))

        val about = fetchAbout(httpClient, target.ide.baseUrl)
        val productCode = productCodeFromBuild(about.buildNumber)
        val selectorInfo = deriveIdePathSelector(about, productCode)
        val pluginsDir = defaultIdePluginsDir(
            selector = selectorInfo.selector,
            vendor = selectorInfo.vendor,
            os = os,
            userHome = userHome,
            env = env,
        )
        val suggestedDestination = pluginsDir.resolve(MCP_STEROID_PLUGIN_DIR_NAME)
        val pluginSource = bundledPluginResolver.resolveBundledPluginZip()
        return ProvisionResult(
            id = id,
            ide = target.ide,
            about = about,
            productCode = productCode,
            selector = selectorInfo.selector,
            pluginsDir = pluginsDir,
            pluginSource = pluginSource,
            suggestedDestination = suggestedDestination,
        )
    }
}

suspend fun detectProvisionTargets(
    httpClient: HttpClient,
    portRanges: List<IntRange> = IntelliJPortDiscovery.DEFAULT_PORT_RANGES,
): List<ProvisionTarget> {
    return detectProvisionTargets(IntelliJPortDiscovery(httpClient = httpClient, portRanges = portRanges))
}

suspend fun detectProvisionTargets(portDiscovery: IntelliJPortDiscovery): List<ProvisionTarget> {
    return portDiscovery.stateSnapshot()
        .sortedBy { it.port }
        .map { ProvisionTarget(id = provisionTargetId(it.port), ide = it) }
}

fun provisionTargetId(port: Int): String = "port-$port"

fun provisionCommand(id: String): String = "devrig backend provision $id"

private fun unknownProvisionTargetMessage(id: String, targets: List<ProvisionTarget>): String = buildString {
    appendLine("Unknown backend provision target '$id'.")
    if (targets.isEmpty()) {
        append("No port-discovered IDEs are available. Run `devrig backend provision` to scan again.")
    } else {
        appendLine("Available provision targets:")
        for (target in targets) {
            appendLine("  ${target.id}  ${portBackendDisplayName(target.ide)} (${portBackendLocatorLabel(target.ide)})")
        }
        append("Run `devrig backend provision <id>` with one of the ids above.")
    }
}

private suspend fun fetchAbout(httpClient: HttpClient, baseUrl: String): AboutResponse {
    val response = httpClient.get("${baseUrl.trimEnd('/')}/api/about") {
        accept(ContentType.Application.Json)
    }
    if (!response.status.isSuccess()) {
        error("$baseUrl/api/about returned HTTP ${response.status.value}")
    }
    return aboutJson.decodeFromString(AboutResponse.serializer(), response.bodyAsText())
}

data class IdePathSelector(
    val selector: String,
    val vendor: String,
)

fun deriveIdePathSelector(about: AboutResponse, productCode: String? = productCodeFromBuild(about.buildNumber)): IdePathSelector {
    val version = deriveSelectorVersion(about)
    val product = resolvePathProduct(about, productCode)
    return IdePathSelector(selector = product.selectorPrefix + version, vendor = product.vendor)
}

fun deriveSelectorVersion(about: AboutResponse): String {
    val fromName = about.name?.let { Regex("""\b(20\d{2}\.\d+)\b""").find(it)?.groupValues?.get(1) }
    if (fromName != null) return fromName

    val baseline = about.baselineVersion ?: baselineFromBuild(about.buildNumber)
    if (baseline != null && baseline >= 100) {
        return "20${baseline / 10}.${baseline % 10}"
    }

    error("Cannot derive IntelliJ config selector version from /api/about: $about")
}

fun defaultIdePluginsDir(
    selector: String,
    vendor: String = "JetBrains",
    os: HostOs = resolveHostOs(),
    userHome: Path = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize(),
    env: Map<String, String> = System.getenv(),
): Path = when (os) {
    HostOs.MAC -> userHome.resolve("Library/Application Support").resolve(vendor).resolve(selector).resolve("plugins")
    HostOs.LINUX -> {
        val root = env["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
            ?: userHome.resolve(".local/share")
        root.resolve(vendor).resolve(selector)
    }
    HostOs.WINDOWS -> {
        val root = env["APPDATA"]?.takeIf { it.isNotBlank() }
            ?: windowsJoin(userHome.toString(), "AppData", "Roaming")
        Path.of(windowsJoin(root, vendor, selector, "plugins"))
    }
}

fun provisionTargetProductName(about: AboutResponse, selector: String): String {
    if (selector.startsWith("IntelliJIdea")) return "IntelliJ IDEA Ultimate"
    if (selector.startsWith("IdeaIC")) return "IntelliJ IDEA Community"
    return about.name?.let { stripVersionSuffix(it) }
        ?: about.productName
        ?: "JetBrains IDE"
}

fun provisionTargetVersion(about: AboutResponse): String {
    val fromName = about.name?.let { Regex("""\b(20\d{2}\.\d+(?:\.\d+)*)\b""").find(it)?.groupValues?.get(1) }
    return fromName ?: deriveSelectorVersion(about)
}

private fun stripVersionSuffix(name: String): String = name
    .replace(Regex("""\s+20\d{2}\.\d+(?:\.\d+)*\b.*$"""), "")
    .trim()
    .ifBlank { name }

private data class PathProduct(
    val selectorPrefix: String,
    val vendor: String = "JetBrains",
    val codes: Set<String> = emptySet(),
    val productNames: Set<String> = emptySet(),
    val fullNameMarkers: Set<String> = emptySet(),
)

private val pathProducts = listOf(
    PathProduct("IntelliJIdea", codes = setOf("IU"), productNames = setOf("IDEA"), fullNameMarkers = setOf("IntelliJ IDEA")),
    PathProduct("IdeaIC", codes = setOf("IC"), fullNameMarkers = setOf("IntelliJ IDEA Community")),
    PathProduct("PyCharm", codes = setOf("PY"), productNames = setOf("PyCharm"), fullNameMarkers = setOf("PyCharm")),
    PathProduct("PyCharmCE", codes = setOf("PC"), fullNameMarkers = setOf("PyCharm Community")),
    PathProduct("GoLand", codes = setOf("GO"), productNames = setOf("GoLand"), fullNameMarkers = setOf("GoLand")),
    PathProduct("WebStorm", codes = setOf("WS"), productNames = setOf("WebStorm"), fullNameMarkers = setOf("WebStorm")),
    PathProduct("CLion", codes = setOf("CL"), productNames = setOf("CLion"), fullNameMarkers = setOf("CLion")),
    PathProduct("Rider", codes = setOf("RD", "RDCPPP"), productNames = setOf("Rider"), fullNameMarkers = setOf("Rider")),
    PathProduct("DataGrip", codes = setOf("DB"), productNames = setOf("DataGrip"), fullNameMarkers = setOf("DataGrip")),
    PathProduct("PhpStorm", codes = setOf("PS"), productNames = setOf("PhpStorm"), fullNameMarkers = setOf("PhpStorm")),
    PathProduct("RubyMine", codes = setOf("RM"), productNames = setOf("RubyMine"), fullNameMarkers = setOf("RubyMine")),
    PathProduct("DataSpell", codes = setOf("DS"), productNames = setOf("DataSpell"), fullNameMarkers = setOf("DataSpell")),
    PathProduct("RustRover", codes = setOf("RR"), productNames = setOf("RustRover"), fullNameMarkers = setOf("RustRover")),
    PathProduct("MPS", codes = setOf("MPS"), productNames = setOf("MPS"), fullNameMarkers = setOf("MPS")),
    PathProduct("AppCode", codes = setOf("OC"), productNames = setOf("AppCode"), fullNameMarkers = setOf("AppCode")),
    PathProduct("JetBrainsGateway", codes = setOf("GW"), productNames = setOf("Gateway"), fullNameMarkers = setOf("JetBrains Gateway")),
    PathProduct("JetBrainsClient", codes = setOf("JBC"), productNames = setOf("JetBrainsClient"), fullNameMarkers = setOf("JetBrains Client")),
    PathProduct("Aqua", codes = setOf("QA"), productNames = setOf("Aqua"), fullNameMarkers = setOf("Aqua")),
    PathProduct("AndroidStudio", vendor = "Google", codes = setOf("AI"), productNames = setOf("Android Studio"), fullNameMarkers = setOf("Android Studio")),
)

private fun resolvePathProduct(about: AboutResponse, productCode: String?): PathProduct {
    val code = productCode?.uppercase()
    if (code != null) {
        pathProducts.firstOrNull { code in it.codes }?.let { return it }
    }

    val fullName = about.name.orEmpty()
    val productName = about.productName.orEmpty()

    if (fullName.contains("IntelliJ IDEA Community", ignoreCase = true)) {
        return pathProducts.single { "IC" in it.codes }
    }
    if (fullName.contains("PyCharm Community", ignoreCase = true)) {
        return pathProducts.single { "PC" in it.codes }
    }

    pathProducts.firstOrNull { productName in it.productNames }?.let { return it }
    pathProducts.firstOrNull { product -> product.fullNameMarkers.any { fullName.contains(it, ignoreCase = true) } }?.let { return it }

    error("Cannot map IDE product to a config selector: productCode=${productCode ?: "<none>"}, productName=${about.productName}, name=${about.name}")
}

private fun baselineFromBuild(buildNumber: String?): Int? =
    buildNumber?.let { Regex("""^(?:[A-Z]+-)?(\d{3})\.""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

private fun windowsJoin(vararg parts: String): String = parts
    .filter { it.isNotBlank() }
    .joinToString("\\") { it.trimEnd('\\', '/') }
