/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerPort
import com.jonnyzzz.mcpSteroid.testHelper.docker.mapGuestPortToHostPort
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResult
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResultValue
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import kotlinx.serialization.json.*

/** Build system type for project setup. Must be specified explicitly per test. */
enum class BuildSystem {
    MAVEN,
    GRADLE,
    NONE,
}

data class McpProjectInfo(
    val name: String,
    val path: String,
)

data class McpWindowInfo(
    val projectName: String?,
    val projectPath: String?,
    val modalDialogShowing: Boolean,
    val indexingInProgress: Boolean?,
    val projectInitialized: Boolean?,
)

/** One entry in IntelliJ's global [ProjectJdkTable]. `homePath` may be null if the JDK is a stub. */
data class JdkInfo(
    val name: String,
    val homePath: String?,
    val versionString: String?,
)

internal fun ProcessResult.resolveJavaHomeLookup(jdkVersion: String): String {
    val javaHome = stdout.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("/") }
    if (javaHome != null) return javaHome

    require(exitCode == 0) {
        "[COMPILE] JDK $jdkVersion not found under /usr/lib/jvm; stdout=${stdout.take(500)} stderr=${stderr.take(500)}"
    }
    error("[COMPILE] JDK $jdkVersion lookup returned no path; stdout=${stdout.take(500)} stderr=${stderr.take(500)}")
}

class McpSteroidDriver(
    private val driver: ContainerDriver,
    private val ijDriver: IntelliJDriver,
) {
    companion object {
        val MCP_STEROID_PORT = ContainerPort(6754)
        private const val SESSION_HEADER = "Mcp-Session-Id"
    }

    private val json = Json { prettyPrint = true }
    @Volatile
    private var mcpSessionId: String? = null

    val guestMcpUrl = "http://localhost:${MCP_STEROID_PORT.containerPort}/mcp"
    val hostMcpUrl get() = "http://localhost:${driver.mapGuestPortToHostPort(MCP_STEROID_PORT)}/mcp"



    fun waitForMcpReady() {
        //TODO: reuse code with code in this file

        // First wait for the server to be reachable via a simple GET health check.
        // This avoids creating orphan sessions from repeated initialize requests.
        waitFor(300_000, "Wait for MCP Steroid ready") {
            val result = driver.startProcessInContainer {
                this
                    .args("curl", "-s", "-f", guestMcpUrl, "-H", "Accept: application/json")
                    .timeoutSeconds(5)
                    .quietly()
                    .description("curl health check $guestMcpUrl")
            }.awaitForProcessFinish()
            result.exitCode == 0
        }

        // Verify the MCP protocol works with a proper initialize handshake
        val mcpInit = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"integration-test","version":"1.0"}}}"""
        driver.startProcessInContainer {
            this
                .args(
                    "curl", "-s", "-f", "-X", "POST",
                    guestMcpUrl,
                    "-H", "Content-Type: application/json",
                    "-H", "Accept: application/json",
                    "-d", mcpInit,
                )
                .quietly()
                .timeoutSeconds(10)
                .description("curl MCP initialize handshake")
        }.assertExitCode(0) {
            "MCP initialize handshake failed: $stdout"
        }

        println("[IDE-AGENT] MCP Steroid is ready in the container at $guestMcpUrl")
        println("[IDE-AGENT] MCP Steroid is ready in the host at $hostMcpUrl")
    }


    /**
     * List all open projects in the IDE via steroid_list_projects tool.
     */
    fun mcpListProjects(): List<McpProjectInfo> {
        val sessionId = mcpInitialize()

        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_list_projects")
                putJsonObject("arguments") { }
            }
        }.toString()

        val run = executeMcpRequest(sessionId, request)
        val data = json.parseToJsonElement(run)

        val text = data.jsonObject["result"]
            ?.jsonObject?.get("content")
            ?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")
            ?.jsonPrimitive?.contentOrNull
            ?: error("steroid_list_projects returned no content: $run")

        val response = json.parseToJsonElement(text)
        return response.jsonObject["projects"]
            ?.jsonArray
            ?.map {
                McpProjectInfo(
                    name = it.jsonObject["name"]!!.jsonPrimitive.content,
                    path = it.jsonObject["path"]!!.jsonPrimitive.content,
                )
            }
            ?: error("steroid_list_projects returned no projects: $text")
    }

    /**
     * Find the project name for the guest project directory.
     */
    fun resolveProjectName(): String {
        val guestProjectDir = ijDriver.getGuestProjectDir()
        val projects = mcpListProjects()
        return projects.singleOrNull { it.path == guestProjectDir }?.name
            ?: error("No project found for path $guestProjectDir. Available: ${projects.map { "${it.name} -> ${it.path}" }}")
    }

    /**
     * List all open IDE windows with project/indexing/modal status.
     */
    fun mcpListWindows(timeoutSeconds: Long = 120): List<McpWindowInfo> {
        val sessionId = mcpInitialize()

        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_list_windows")
                putJsonObject("arguments") { }
            }
        }.toString()

        val run = executeMcpRequest(sessionId, request, timeoutSeconds = timeoutSeconds)
        val data = json.parseToJsonElement(run)

        val text = data.jsonObject["result"]
            ?.jsonObject?.get("content")
            ?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("text")
            ?.jsonPrimitive?.contentOrNull
            ?: error("steroid_list_windows returned no content: $run")

        val response = json.parseToJsonElement(text)
        return response.jsonObject["windows"]
            ?.jsonArray
            ?.map {
                val window = it.jsonObject
                McpWindowInfo(
                    projectName = window["projectName"]?.jsonPrimitive?.contentOrNull,
                    projectPath = window["projectPath"]?.jsonPrimitive?.contentOrNull,
                    modalDialogShowing = window["modalDialogShowing"]?.jsonPrimitive?.booleanOrNull ?: false,
                    indexingInProgress = window["indexingInProgress"]?.jsonPrimitive?.booleanOrNull,
                    projectInitialized = window["projectInitialized"]?.jsonPrimitive?.booleanOrNull,
                )
            }
            ?: error("steroid_list_windows returned no windows payload: $text")
    }

    /**
     * Open a project directory in IntelliJ IDEA via steroid_open_project.
     * Call this during the pre-warm phase (before the measured agent run).
     */
    fun mcpOpenProject(projectPath: String, trustProject: Boolean? = true) {
        val sessionId = mcpInitialize()
        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "steroid_open_project")
                putJsonObject("arguments") {
                    put("task_id", "prewarm-open-project")
                    put("project_path", projectPath)
                    put("reason", "Pre-warm: open arena project before measured agent run")
                    if (trustProject != null) {
                        put("trust_project", trustProject)
                    }
                }
            }
        }.toString()
        val response = executeMcpRequest(sessionId, request, timeoutSeconds = 60)
        val responseJson = json.parseToJsonElement(response).jsonObject
        val isError = responseJson["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.booleanOrNull == true
        if (isError) {
            val errorText = responseJson["result"]?.jsonObject?.get("content")?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: "unknown error"
            error("steroid_open_project failed: $errorText")
        }
    }

    /**
     * Wait for a specific project path to finish indexing.
     * Poll steroid_list_windows until the project at [projectPath] is initialized and not indexing.
     * Actively kills blocking dialogs. Timeout: 10 minutes.
     */
    fun waitForArenaProjectIndexed(projectPath: String) {
        var lastDialogKillMs = 0L
        waitFor(600_000, "Arena project indexing at $projectPath") {
            val windows = mcpListWindows()
            val projectWindows = windows.filter { it.projectPath == projectPath }
            if (projectWindows.isEmpty()) return@waitFor false

            val modalDialogPresent = projectWindows.any { it.modalDialogShowing }
            if (modalDialogPresent) {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastDialogKillMs > 5_000) {
                    lastDialogKillMs = nowMs
                    killStartupDialogs(projectPath)
                }
                return@waitFor false
            }

            projectWindows.any { it.projectInitialized == true && it.indexingInProgress == false }
        }
    }

    /**
     * Detect required IDE plugins from project dependencies and install any that are missing.
     *
     * Scans `pom.xml` / `build.gradle` for known dependency keywords and maps them to
     * JetBrains Marketplace plugin IDs. Missing plugins are installed via
     * [PluginsAdvertiser.installAndEnable], which downloads and dynamically loads them without
     * requiring an IDE restart (when the plugin supports dynamic loading).
     *
     * Call this after initial indexing and before JDK/Maven setup so that Maven re-sync can
     * already benefit from freshly installed framework support plugins.
     *
     * @param projectPath Guest project directory path.
     */
    fun mcpInstallRequiredPlugins(projectPath: String) {
        val projectName = try {
            mcpListProjects().firstOrNull { it.path == projectPath }?.name
        } catch (e: Exception) {
            println("[PLUGIN-INSTALL] Could not list projects: ${e.message}")
            null
        }
        if (projectName == null) {
            println("[PLUGIN-INSTALL] Project not found for path $projectPath — skipping plugin install")
            return
        }

        val code = """
import com.intellij.openapi.extensions.PluginId
import com.intellij.ide.plugins.PluginManagerCore
import java.io.File

// Dependency keyword → Marketplace plugin ID
val detectionRules = mapOf(
    "spring-kafka"   to "com.intellij.bigdatatools.kafka",
    "kafka-clients"  to "com.intellij.bigdatatools.kafka",
    "kafka-streams"  to "com.intellij.bigdatatools.kafka",
)

val basePath = project.basePath ?: ""
val buildContent = sequenceOf("pom.xml", "build.gradle", "build.gradle.kts")
    .map { File(basePath, it) }
    .firstOrNull { it.exists() }
    ?.readText() ?: ""

val toInstall = detectionRules
    .filter { (keyword, _) -> buildContent.contains(keyword, ignoreCase = true) }
    .values.toSet()
    .filter { PluginManagerCore.getPlugin(PluginId.getId(it)) == null }

if (toInstall.isEmpty()) {
    println("[PLUGIN-INSTALL] All required plugins already installed (or no matching dependencies)")
} else {
    println("[PLUGIN-INSTALL] Installing plugins: ${'$'}toInstall")
    // Use reflection to avoid compile error on IDE builds where PluginsAdvertiser was removed/moved.
    // In IU-253+ the class may not exist; we skip dynamic install gracefully in that case.
    val advertiserClass = try {
        Class.forName("com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.PluginsAdvertiser")
    } catch (e: ClassNotFoundException) {
        null
    }
    if (advertiserClass == null) {
        println("[PLUGIN-INSTALL] PluginsAdvertiser not available in this IDE build — skipping dynamic install")
    } else {
        val installMethod = advertiserClass.declaredMethods
            .firstOrNull { it.name == "installAndEnable" }
        if (installMethod == null) {
            println("[PLUGIN-INSTALL] installAndEnable method not found — skipping dynamic install")
        } else {
            installMethod.invoke(
                null, project,
                toInstall.map { PluginId.getId(it) }.toSet(),
                Runnable { println("[PLUGIN-INSTALL] installAndEnable callback fired") },
            )
            // Plugin installation triggers re-indexing (dumb mode). Wait for smart mode —
            // this is the canonical way to wait for all IDE background work to complete.
            println("[PLUGIN-INSTALL] Waiting for smart mode after plugin installation...")
            waitForSmartMode()
            println("[PLUGIN-INSTALL] Smart mode reached — plugins ready: ${'$'}toInstall")
        }
    }
}
"done"
""".trimIndent()

        try {
            mcpExecuteCode(
                code = code,
                projectName = projectName,
                reason = "Install required IDE plugins for project dependencies",
                timeout = 200,
            )
        } catch (e: Exception) {
            println("[PLUGIN-INSTALL] Warning: plugin installation failed: ${e.message}")
        }
    }

    /**
     * Apply the project JDK (if not already set) and wait for Maven/Gradle import to complete.
     *
     * JDKs are registered via [mcpRegisterJdks] from `IntelliJ_factoryKt.create` right after
     * `waitForMcpReady`, so by the time this function runs the `ProjectJdkTable` is populated.
     * This function:
     * 1. Finds the registered SDK matching JAVA_HOME (or any valid one)
     * 2. Sets it as the project SDK if not already configured
     * 3. Triggers Maven re-sync if JDK was just applied (initial import may have failed without JDK)
     * 4. Waits for Maven/Gradle configuration to complete via Observation.awaitConfiguration
     *
     * Safe to call when JDK is already configured or when no import is pending — both are no-ops.
     *
     * @param projectPath Guest project directory (used to resolve the project name from [mcpListProjects]).
     */
    /**
     * Return every entry currently in IntelliJ's global [ProjectJdkTable] that has
     * `SdkType == JavaSdk`.
     *
     * The server-side script emits one `[JDK-LIST] name=… home=… version=…` line per
     * entry and a final `[JDK-LIST] count=N`. Parsing is line-anchored (not regex-heavy)
     * because JDK names never contain spaces in our fixtures.
     */
    fun mcpListJdks(projectName: String = resolveProjectName()): List<JdkInfo> {
        val code = $$"""
            import com.intellij.openapi.projectRoots.JavaSdk
            import com.intellij.openapi.projectRoots.ProjectJdkTable

            val sdks = ProjectJdkTable.getInstance().getSdksOfType(JavaSdk.getInstance())
            for (sdk in sdks.sortedBy { it.name }) {
                val home = sdk.homePath ?: ""
                val ver = sdk.versionString ?: ""
                println("[JDK-LIST]\tname=${sdk.name}\thome=$home\tversion=$ver")
            }
            println("[JDK-LIST]\tcount=${sdks.size}")
        """.trimIndent()

        val result = mcpExecuteCode(
            code = code,
            projectName = projectName,
            reason = "List registered JDKs",
            timeout = 30,
        ).assertExitCode(0) { "mcpListJdks failed: $stdout" }

        return result.stdout.lineSequence()
            .mapNotNull { line ->
                val fields = line.substringAfter("[JDK-LIST]\t", missingDelimiterValue = "").split("\t")
                if (fields.isEmpty() || !fields[0].startsWith("name=")) return@mapNotNull null
                val map = fields.associate { it.substringBefore("=") to it.substringAfter("=", "") }
                JdkInfo(
                    name = map["name"].orEmpty(),
                    homePath = map["home"]?.takeIf { it.isNotEmpty() },
                    versionString = map["version"]?.takeIf { it.isNotEmpty() },
                )
            }
            .toList()
    }

    /**
     * Register one JDK under the requested [name] pointing at [homePath].
     *
     * Uses the shortest IntelliJ-native registration path that still lets us pick the
     * exact `ProjectJdkTable` entry name:
     *   1. `JavaSdk.createJdk(name, homePath, isJre=false)` — builds the [Sdk] and runs
     *      `JavaSdkImpl.setupSdkPaths` for classpath/jrt wiring. No UI involvement.
     *   2. `ProjectJdkTable.addJdk(sdk)` inside [writeAction] — the one write IntelliJ
     *      requires for a global SDK-table mutation.
     *
     * `SdkConfigurationUtil.createAndAddSDK(path, sdkType)` is a one-liner alternative
     * but auto-generates a unique name (e.g. `21-ea-1758`). We need exact names like
     * `corretto-21` so `.idea/misc.xml` with `project-jdk-name="corretto-21"` resolves
     * without firing `SdkLookup`'s downloader modal.
     *
     * Idempotent — if [name] already exists in the table, this is a no-op.
     */
    fun mcpAddJdk(projectName: String = resolveProjectName(), name: String, homePath: String) {
        val code = $$"""
            import com.intellij.openapi.application.writeAction
            import com.intellij.openapi.projectRoots.JavaSdk
            import com.intellij.openapi.projectRoots.ProjectJdkTable

            val javaSdkType = JavaSdk.getInstance()
            val table = ProjectJdkTable.getInstance()
            if (table.findJdk("$$name") != null) {
                println("[JDK-ADD]\talready-registered\tname=$$name")
            } else {
                val homeFile = java.io.File("$$homePath")
                require(java.io.File(homeFile, "bin/java").isFile) { "Not a JDK home: $$homePath" }
                val sdk = javaSdkType.createJdk("$$name", "$$homePath", false)
                writeAction { table.addJdk(sdk) }
                println("[JDK-ADD]\tregistered\tname=${sdk.name}\thome=${sdk.homePath}\tversion=${sdk.versionString}")
            }
        """.trimIndent()

        mcpExecuteCode(
            code = code,
            projectName = projectName,
            reason = "Register JDK name=$name home=$homePath",
            timeout = 60,
        ).assertExitCode(0) { "mcpAddJdk(name=$name, home=$homePath) failed: $stdout" }
    }

    /**
     * Discover every Temurin JDK under `/usr/lib/jvm/` in the container and register it
     * under three aliases each: bare version (`"21"`), `"corretto-21"`, `"temurin-21"`.
     *
     * Why three aliases: projects checked into VCS often pin `project-jdk-name="corretto-21"`
     * or `gradleJvm="corretto-25"` in their `.idea` XML files. If no `ProjectJdkTable`
     * entry with that exact name exists when the project opens, IntelliJ's `SdkLookup`
     * proposes a download and blocks the EDT on a YesNo consent modal. Pre-registering
     * the vendor aliases (all pointing at the same Temurin install) short-circuits it.
     *
     * Discovery lives here (Gradle-side shell) rather than inside the script so that the
     * script has nothing to scan — each `mcpAddJdk` call targets a known path.
     */
    fun mcpRegisterJdks(projectPath: String) {
        val projectName = resolveProjectName(projectPath) ?: return
        val existingNames = mcpListJdks(projectName).map { it.name }.toSet()
        println("[JDK-REGISTER] Existing JDKs in ProjectJdkTable: $existingNames")

        val discovered = driver.startProcessInContainer {
            this
                .args("bash", "-c", "ls -1d /usr/lib/jvm/temurin-*-jdk-* 2>/dev/null || true")
                .timeoutSeconds(10)
                .quietly()
                .description("list Temurin JDKs in /usr/lib/jvm")
        }.awaitForProcessFinish()

        val temurinDirs = discovered.stdout.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sorted()
        println("[JDK-REGISTER] Discovered Temurin dirs: $temurinDirs")

        for (dir in temurinDirs) {
            // "/usr/lib/jvm/temurin-21-jdk-arm64" -> "21"
            val version = dir.substringAfter("temurin-").substringBefore("-jdk")
            for (alias in listOf(version, "corretto-$version", "temurin-$version")) {
                if (alias in existingNames) {
                    println("[JDK-REGISTER] Skip $alias — already in table")
                    continue
                }
                try {
                    mcpAddJdk(projectName, alias, dir)
                } catch (e: Exception) {
                    println("[JDK-REGISTER] Failed to add $alias at $dir: ${e.message}")
                }
            }
        }
    }

    /**
     * Resolve unknown module SDK references to already-registered `ProjectJdkTable`
     * entries WITHOUT ever offering to download a new JDK.
     *
     * Why not `UnknownSdkTracker.updateUnknownSdks()`: that API runs every fixer
     * extension point, including ones whose `UnknownSdkFixActionDownloadBase.
     * collectConsent` shows a modal "Download Amazon Corretto?" dialog via
     * `MessageDialogBuilder.YesNo.ask`. In headless Docker tests there is no user
     * to click "Yes" and the EDT deadlocks indefinitely (observed in
     * DialogKillerIntegrationTest at >10 min hang, April 2026).
     *
     * Instead: collect unknown SDK names via `UnknownSdkCollector`, then run
     * `SdkLookup.newLookupBuilder()` per SDK with `onDownloadableSdkSuggested {
     * SdkLookupDecision.STOP }` so the download path is never taken. If no local
     * `ProjectJdkTable` entry matches, the SDK stays unresolved — preferred over
     * a UI deadlock.
     */
    fun mcpResolveUnknownSdks(projectPath: String) {
        val projectName = resolveProjectName(projectPath) ?: return

        val code = $$"""
import com.intellij.openapi.projectRoots.impl.UnknownSdkCollector
import com.intellij.openapi.roots.ui.configuration.SdkLookup
import com.intellij.openapi.roots.ui.configuration.SdkLookupDecision
import kotlinx.coroutines.delay

println("[SDK-RESOLVE] Collecting unknown SDKs (download fixes REJECTED)...")
val snapshot = readAction { UnknownSdkCollector(project).collectSdksBlocking() }
val unknowns = snapshot.resolvableSdks
println("[SDK-RESOLVE] Unknown SDKs: ${unknowns.joinToString { it.sdkName ?: "<null>" }}")

for (unknown in unknowns) {
    val sdkName = unknown.sdkName ?: continue
    println("[SDK-RESOLVE] Looking up '$sdkName' (local only)...")
    SdkLookup.newLookupBuilder()
        .withProject(project)
        .withSdkName(sdkName)
        .onDownloadableSdkSuggested { SdkLookupDecision.STOP }
        .onLocalSdkSuggested { SdkLookupDecision.CONTINUE }
        .executeLookup()
}
delay(500L)
println("[SDK-RESOLVE] Wait complete — resolved via local ProjectJdkTable only")
"done"
""".trimIndent()

        try {
            mcpExecuteCode(
                code = code,
                projectName = projectName,
                reason = "Resolve unknown SDKs to prevent false-positive build errors",
                timeout = 30,
            )
        } catch (e: Exception) {
            println("[SDK-RESOLVE] Warning: SDK resolution failed: ${e.message}")
        }
    }

    /**
     * Set the project SDK to a registered JDK by version name (e.g. "21", "17").
     * If the project already has a different SDK, replace it with the requested version.
     * JDKs must have been registered first via [mcpRegisterJdks].
     */
    fun mcpSetProjectSdk(projectPath: String, jdkVersion: String) {
        val projectName = resolveProjectName(projectPath) ?: return

        val code = """
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.ex.JavaSdkUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.application.edtWriteAction

val currentSdk = ProjectRootManager.getInstance(project).projectSdk
val requestedVersion = "$jdkVersion"
fun matchesRequestedVersion(sdkName: String): Boolean =
    sdkName == requestedVersion ||
            sdkName == "temurin-${'$'}requestedVersion" ||
            sdkName == "corretto-${'$'}requestedVersion"

if (currentSdk != null && matchesRequestedVersion(currentSdk.name)) {
    println("[SDK] Project SDK already set: ${'$'}{currentSdk.name}")
} else {
    val javaSdks = ProjectJdkTable.getInstance().getSdksOfType(JavaSdk.getInstance())
    println("[SDK] Available SDKs: ${'$'}{javaSdks.map { it.name }}")
    val sdk = javaSdks.firstOrNull { matchesRequestedVersion(it.name) }
    if (sdk != null) {
        if (currentSdk == null) {
            println("[SDK] Applying SDK: ${'$'}{sdk.name} at ${'$'}{sdk.homePath}")
        } else {
            println("[SDK] Replacing project SDK ${'$'}{currentSdk.name} with ${'$'}{sdk.name} at ${'$'}{sdk.homePath}")
        }
        edtWriteAction { JavaSdkUtil.applyJdkToProject(project, sdk) }
    } else {
        error("[SDK] No SDK matching version $jdkVersion found")
    }
}
"done"
""".trimIndent()

        try {
            mcpExecuteCode(
                code = code,
                projectName = projectName,
                reason = "Set project SDK to JDK $jdkVersion",
                timeout = 30,
            )
        } catch (e: Exception) {
            println("[SDK] Project SDK setup failed: ${e.message}")
            throw e
        }
    }

    @Deprecated("Use mcpRegisterJdks + mcpSetProjectSdk + mcpTriggerImportAndWait separately")
    fun mcpSetupJdkAndWaitForImport(projectPath: String) {
        val projectName = resolveProjectName(projectPath) ?: return

        // First, ensure JDKs are registered via the IntelliJ API
        mcpRegisterJdks(projectPath)

        val code = """
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ex.JavaSdkUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.application.edtWriteAction
import com.intellij.platform.backend.observation.Observation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

val javaHome = System.getenv("JAVA_HOME") ?: ""
val allRegisteredSdks = com.intellij.openapi.projectRoots.ProjectJdkTable.getInstance()
    .getSdksOfType(JavaSdk.getInstance())
println("[JDK-SETUP] JAVA_HOME=${'$'}javaHome, registered SDKs: ${'$'}{allRegisteredSdks.map { it.name }}")

// Prefer JDK 21, then match JAVA_HOME, then any valid JDK
val registeredJavaSdk = allRegisteredSdks.firstOrNull { it.name == "21" }
    ?: allRegisteredSdks.firstOrNull { sdk -> sdk.homePath != null && sdk.homePath == javaHome }
    ?: allRegisteredSdks.firstOrNull { sdk ->
        val home = sdk.homePath ?: return@firstOrNull false
        java.io.File(home, "bin/java").exists()
    }

var jdkWasSet = false
val currentSdk = ProjectRootManager.getInstance(project).projectSdk
if (currentSdk != null) {
    println("[JDK-SETUP] Project SDK already set: ${'$'}{currentSdk.name}")
} else if (registeredJavaSdk != null) {
    println("[JDK-SETUP] Applying SDK: ${'$'}{registeredJavaSdk.name} at ${'$'}{registeredJavaSdk.homePath}")
    edtWriteAction { JavaSdkUtil.applyJdkToProject(project, registeredJavaSdk) }
    jdkWasSet = true
} else {
    println("[JDK-SETUP] WARNING: No valid registered Java SDK found")
}

// Trigger Maven re-sync if JDK was just set (first Maven import may have run without a JDK)
val pomFile = java.io.File(project.basePath ?: "", "pom.xml")
if (jdkWasSet && pomFile.exists()) {
    try {
        println("[JDK-SETUP] Triggering Maven re-sync after JDK setup...")
        org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(project)
            .forceUpdateAllProjectsOrFindAllAvailablePomFiles()
        delay(2_000L)
    } catch (e: Exception) {
        println("[JDK-SETUP] Maven re-sync failed: ${'$'}{e.message}")
    }
}

// Wait for all pending configuration (Maven/Gradle sync) to complete
println("[JDK-SETUP] Waiting for project configuration (Maven/Gradle sync)...")
val configured = withTimeoutOrNull(8 * 60 * 1000L) {
    Observation.awaitConfiguration(project)
}
println(if (configured == null) "[JDK-SETUP] WARNING: Configuration timed out after 8 minutes"
        else "[JDK-SETUP] Project configuration complete")
"done"
""".trimIndent()

        try {
            mcpExecuteCode(
                code = code,
                projectName = projectName,
                reason = "Setup JDK and wait for Maven/Gradle import",
                timeout = 600,
            )
        } catch (e: Exception) {
            println("[JDK-SETUP] Warning: JDK/import setup failed: ${e.message}")
        }
    }

    private fun resolveProjectName(projectPath: String): String? {
        val projectName = try {
            mcpListProjects().firstOrNull { it.path == projectPath }?.name
        } catch (e: Exception) {
            println("[MCP] Could not list projects: ${e.message}")
            null
        }
        if (projectName == null) {
            println("[MCP] Project not found for path $projectPath — skipping")
        }
        return projectName
    }

    /**
     * Trigger Maven or Gradle import and wait for it to complete.
     *
     * For Maven: calls `forceUpdateAllProjectsOrFindAllAvailablePomFiles()`
     * For Gradle: relies on IntelliJ auto-import (triggered by project open)
     * For NONE: only waits for `Observation.awaitConfiguration`
     *
     * Waits via `Observation.awaitConfiguration(project)` + `waitForSmartMode()`.
     */
    fun mcpTriggerImportAndWait(projectPath: String, buildSystem: BuildSystem) {
        val projectName = resolveProjectName(projectPath) ?: return

        val triggerCode = when (buildSystem) {
            BuildSystem.MAVEN -> """
                try {
                    println("[IMPORT] Triggering Maven import...")
                    val mavenManager = org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(project)
                    // Enable source + javadoc downloading so agents have full API docs in the IDE
                    val importSettings = mavenManager.importingSettings
                    importSettings.isDownloadSourcesAutomatically = true
                    importSettings.isDownloadDocsAutomatically = true
                    println("[IMPORT] Maven source/doc download: sources=${'$'}{importSettings.isDownloadSourcesAutomatically} docs=${'$'}{importSettings.isDownloadDocsAutomatically}")
                    mavenManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles()
                    kotlinx.coroutines.delay(2_000L)
                } catch (e: Exception) {
                    println("[IMPORT] Maven trigger failed: ${'$'}{e.message}")
                }
            """.trimIndent()
            BuildSystem.GRADLE -> """
                println("[IMPORT] Gradle auto-import active from project open")
                // Enable source downloading for Gradle projects
                try {
                    val gradleSettings = org.jetbrains.plugins.gradle.settings.GradleSystemSettings.getInstance()
                    gradleSettings.isDownloadSources = true
                    println("[IMPORT] Gradle source download: enabled")
                } catch (e: Exception) {
                    println("[IMPORT] Gradle source download setting failed: ${'$'}{e.message}")
                }
                // Trigger Gradle refresh so source download setting takes effect
                try {
                    println("[IMPORT] Triggering Gradle refresh...")
                    val gradleProjectPath = project.basePath!!
                    com.intellij.openapi.externalSystem.util.ExternalSystemUtil.refreshProject(
                        gradleProjectPath,
                        com.intellij.openapi.externalSystem.importing.ImportSpecBuilder(
                            project,
                            org.jetbrains.plugins.gradle.util.GradleConstants.SYSTEM_ID
                        ).build()
                    )
                    kotlinx.coroutines.delay(2_000L)
                } catch (e: Exception) {
                    println("[IMPORT] Gradle refresh failed: ${'$'}{e.message}")
                }
            """.trimIndent()
            BuildSystem.NONE -> """
                println("[IMPORT] No build system — skipping import trigger")
            """.trimIndent()
        }

        val code = """
import com.intellij.platform.backend.observation.Observation
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay

println("[IMPORT] Build system: $buildSystem")
$triggerCode

println("[IMPORT] Waiting for project configuration...")
val configured = withTimeoutOrNull(8 * 60 * 1000L) {
    Observation.awaitConfiguration(project)
}
println(if (configured == null) "[IMPORT] WARNING: Configuration timed out after 8 minutes"
        else "[IMPORT] Configuration complete")

waitForSmartMode()
println("[IMPORT] Smart mode reached — import + indexing complete")
"done"
""".trimIndent()

        try {
            mcpExecuteCode(
                code = code,
                projectName = projectName,
                reason = "Trigger $buildSystem import and wait for completion",
                timeout = 600,
            )
        } catch (e: Exception) {
            println("[IMPORT] Warning: import trigger failed: ${e.message}")
        }
    }

    /**
     * Compile the project via bash (not IntelliJ build).
     *
     * For Maven: `./mvnw test-compile -Dspotless.check.skip=true`
     * For Gradle: `./gradlew testClasses`
     * For NONE: skip
     *
     * Runs inside the container with the configured JAVA_HOME when [javaHomeVersion] is set.
     * This is a pre-agent warmup step — ensures all sources compile and deps are downloaded.
     */
    fun mcpCompileProject(projectPath: String, buildSystem: BuildSystem, javaHomeVersion: String? = "21") {
        if (buildSystem == BuildSystem.NONE) {
            println("[COMPILE] Build system is NONE — skipping compilation")
            return
        }

        val javaHome = javaHomeVersion?.let { jdkVersion ->
            driver.startProcessInContainer {
                this.args(
                    "bash",
                    "-c",
                    """
                    for dir in /usr/lib/jvm/temurin-$jdkVersion-* /usr/lib/jvm/java-$jdkVersion-* /usr/lib/jvm/corretto-$jdkVersion-*; do
                      if [ -x "${'$'}dir/bin/javac" ]; then
                        printf '%s\n' "${'$'}dir"
                        exit 0
                      fi
                    done
                    printf 'JDK $jdkVersion not found under /usr/lib/jvm\n' >&2
                    exit 1
                    """.trimIndent(),
                )
                    .timeoutSeconds(5)
                    .description("Find JDK $jdkVersion path")
            }.awaitForProcessFinish().resolveJavaHomeLookup(jdkVersion)
        }
        println("[COMPILE] JAVA_HOME=$javaHome, buildSystem=$buildSystem")

        val command = when (buildSystem) {
            BuildSystem.MAVEN -> "./mvnw test-compile -DskipTests -Dspotless.check.skip=true -B -q"
            BuildSystem.GRADLE -> "./gradlew testClasses --console=plain -q"
            BuildSystem.NONE -> return
        }
        println("[COMPILE] Running: $command")

        val compileResult = driver.startProcessInContainer {
            this
                .args("bash", "-c", "${if (javaHome == null) "" else "export JAVA_HOME=$javaHome && "}$command")
                .workingDirInContainer(projectPath)
                .timeoutSeconds(600)
                .description("Compile project ($buildSystem)")
        }.awaitForProcessFinish()

        if (compileResult.exitCode == 0) {
            println("[COMPILE] Compilation complete")
        } else {
            println("[COMPILE] WARNING: Compilation failed (exit=${compileResult.exitCode}) — continuing anyway")
            println("[COMPILE] stderr: ${compileResult.stderr.take(500)}")
            println("[COMPILE] stdout: ${compileResult.stdout.take(500)}")
        }
    }

    /**
     * Open README.md (or fallback source file) in the editor and show the Maven/Gradle tool window.
     *
     * Helps AI agents orient themselves from the IDE view immediately after project import.
     * All operations are best-effort — failures are logged but do not propagate.
     *
     * @param projectPath Guest project directory path.
     */
    fun mcpOpenFileAndBuildToolWindow(projectPath: String, openFileOnStart: String? = null) {
        val projectName = try {
            mcpListProjects().firstOrNull { it.path == projectPath }?.name
        } catch (e: Exception) {
            println("[UX-SETUP] Could not list projects: ${e.message}")
            null
        }
        if (projectName == null) {
            println("[UX-SETUP] Project not found for path $projectPath — skipping UX setup")
            return
        }

        // Escape the openFileOnStart path for embedding in Kotlin string template
        val filePathLiteral = if (openFileOnStart != null) {
            "\"$openFileOnStart\""
        } else {
            "null"
        }

        val code = """
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.withContext

// 1. Open a file for agent orientation.
// Use refreshAndFindFileByPath so VFS content is loaded from disk —
// git clone happened outside IntelliJ's file watcher, so findFileByPath
// may return a VirtualFile whose content cache is empty (black editor).
// Skip files > 10 KB — large README.md files (e.g. JHipster) cause the
// Markdown preview renderer to hang the IDE during startup.
val basePath = project.basePath ?: ""
val openFileRelPath: String? = $filePathLiteral
val maxFileSize = 10_000L

val fileToOpen = if (openFileRelPath != null) {
    val targetPath = "${'$'}basePath/${'$'}openFileRelPath"
    LocalFileSystem.getInstance().refreshAndFindFileByPath(targetPath)
} else {
    // Fallback chain: README.md (if small), then first small source file
    val baseDir = java.io.File(basePath)
    val readme = java.io.File(basePath, "README.md")
    if (readme.exists() && readme.length() <= maxFileSize) {
        LocalFileSystem.getInstance().refreshAndFindFileByPath(readme.absolutePath)
    } else {
        val sourceFile = baseDir.walkTopDown()
            .filter { it.isFile && it.length() <= maxFileSize }
            .filter { it.extension in listOf("java", "kt", "ts", "js") }
            .firstOrNull()
        if (sourceFile != null) {
            LocalFileSystem.getInstance().refreshAndFindFileByPath(sourceFile.absolutePath)
        } else {
            null
        }
    }
}

if (fileToOpen != null) {
    withContext(Dispatchers.EDT) {
        FileEditorManager.getInstance(project).openFile(fileToOpen, true)
        println("[UX-SETUP] Opened file: ${'$'}{fileToOpen.path}")
    }
} else {
    println("[UX-SETUP] No file found to open (configured=${'$'}openFileRelPath)")
}

// 2. Show the Commit tool window (local changes) — more useful for agents than
// the build tool window, and avoids the Markdown preview hang issue.
withContext(Dispatchers.EDT) {
    try {
        ToolWindowManager.getInstance(project).getToolWindow("Commit")?.show()
        println("[UX-SETUP] Commit tool window shown")
    } catch (e: Exception) {
        println("[UX-SETUP] Could not show Commit tool window: ${'$'}{e.message}")
    }
}

// 3. Show Maven or Gradle tool window depending on what build file exists
val pomFile = java.io.File(basePath, "pom.xml")
val gradleFile = java.io.File(basePath, "build.gradle")
val gradleKtsFile = java.io.File(basePath, "build.gradle.kts")

withContext(Dispatchers.EDT) {
    try {
        when {
            pomFile.exists() -> {
                ToolWindowManager.getInstance(project).getToolWindow("Maven")?.show()
                println("[UX-SETUP] Maven tool window shown")
            }
            gradleFile.exists() || gradleKtsFile.exists() -> {
                ToolWindowManager.getInstance(project).getToolWindow("Gradle")?.show()
                println("[UX-SETUP] Gradle tool window shown")
            }
            else -> println("[UX-SETUP] No pom.xml or build.gradle found — skipping build tool window")
        }
    } catch (e: Exception) {
        println("[UX-SETUP] Could not show build tool window: ${'$'}{e.message}")
    }
}

// 3. Expand project tree root node (best-effort)
try {
    withContext(Dispatchers.EDT) {
        ProjectView.getInstance(project).currentProjectViewPane?.tree?.expandRow(0)
        println("[UX-SETUP] Project tree root expanded")
    }
} catch (e: Exception) {
    println("[UX-SETUP] Could not expand project tree: ${'$'}{e.message}")
}

"done"
""".trimIndent()

        try {
            mcpExecuteCode(
                code = code,
                projectName = projectName,
                reason = "Open project file and build tool window for agent orientation",
                timeout = 30,
            )
        } catch (e: Exception) {
            println("[UX-SETUP] Warning: UX setup failed: ${e.message}")
        }
    }

    /**
     * Kill any blocking modal dialogs via steroid_execute_code.
     *
     * IntelliJ 2025.3.3+ shows a "NewUI Onboarding" dialog on first startup that blocks
     * the EDT via WriteIntentReadAction, preventing Gradle import. This method calls the
     * plugin's DialogKiller with ModalityState.any() to dismiss it from within the modal loop.
     *
     * @param guestProjectDir The project path to resolve the project name from.
     */
    fun killStartupDialogs(guestProjectDir: String) {
        try {
            val projects = mcpListProjects()
            // The "Open or Import Project" dialog can appear before the project at guestProjectDir
            // is registered in IntelliJ's project list (the dialog blocks project initialization).
            // Fall back to any available project so the AWT window scan still runs.
            val projectName = (projects.firstOrNull { it.path == guestProjectDir }
                ?: projects.firstOrNull())?.name ?: return

            val code = """
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DialogWrapperDialog
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import kotlinx.coroutines.withContext
import java.awt.Container
import java.awt.Dialog
import java.awt.Window
import javax.swing.JButton

fun findButtons(container: Container, depth: Int = 20): List<JButton> {
    if (depth <= 0) return emptyList()
    val result = mutableListOf<JButton>()
    for (component in container.components) {
        if (component is JButton) result += component
        if (component is Container) result += findButtons(component, depth - 1)
    }
    return result
}

withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
    val closed = mutableListOf<String>()
    for (window in Window.getWindows()) {
        if (!window.isShowing) continue
        val title = (window as? Dialog)?.title ?: (window as? java.awt.Frame)?.title ?: "(no title)"
        // "Open or Import Project" dialog: canceling aborts the Maven import.
        // Instead, click the first affirmative button (e.g. "Open as Maven Project").
        val isOpenImportDialog = title.contains("Open", ignoreCase = true) &&
            (title.contains("Import", ignoreCase = true) || title.contains("or", ignoreCase = true))
        if (isOpenImportDialog) {
            val buttons = findButtons(window as Container)
            val affirmativeBtn = buttons.firstOrNull { btn ->
                val text = btn.text ?: ""
                (text.contains("Open", ignoreCase = true) || text.contains("Maven", ignoreCase = true) ||
                    text.contains("Gradle", ignoreCase = true) || text.contains("Import", ignoreCase = true)) &&
                    !text.contains("Cancel", ignoreCase = true)
            }
            if (affirmativeBtn != null) {
                affirmativeBtn.doClick()
                closed += "ClickedOpen[${'$'}{affirmativeBtn.text}]:${'$'}title"
                continue
            }
        }
        if (window is DialogWrapperDialog) {
            val dw = window.dialogWrapper
            if (dw != null && dw.isModal) {
                dw.close(DialogWrapper.CANCEL_EXIT_CODE)
                closed += "DialogWrapper:${'$'}title"
                continue
            }
        }
        if (window is Dialog && window.isModal) {
            window.dispose()
            closed += "AwtDialog:${'$'}title"
        }
    }
    if (closed.isNotEmpty()) {
        println("[startup-dialog-killer] Closed dialogs: ${'$'}closed")
    }
}
""".trimIndent()

            mcpExecuteCode(
                code = code,
                projectName = projectName,
                reason = "Kill startup blocking dialogs",
                timeout = 15,
                dialogKiller = false,
            )
        } catch (e: Exception) {
            driver.log("[startup-dialog-killer] Failed to kill startup blocking dialogs: ${e.message}")
        }
    }

    /**
     * Execute Kotlin code via steroid_execute_code tool.
     *
     * This makes a direct HTTP call to the MCP server, bypassing AI agents.
     * Useful for integration tests that need reliable, deterministic behavior.
     *
     * @param code Kotlin code to execute (suspend function body)
     * @param taskId Task identifier (default: "integration-test")
     * @param reason Human-readable reason for execution
     * @param timeout Timeout in seconds (default: 600)
     * @param projectName Project name (defaults to the project at [guestProjectDir])
     * @return MCP tool result as JSON string
     */
    fun mcpExecuteCode(
        code: String,
        taskId: String = "integration-test",
        reason: String = "Integration test execution",
        timeout: Int = 600,
        projectName: String = resolveProjectName(),
        dialogKiller: Boolean? = false,
    ): ProcessResult {
        // First, initialize MCP session
        val sessionId = mcpInitialize()

        // Build the tool call request using kotlinx.serialization
        val toolCallRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 2)
            putJsonObject("params") {
                put("name", "steroid_execute_code")
                putJsonObject("arguments") {
                    put("project_name", projectName)
                    put("code", code)
                    put("task_id", taskId)
                    put("reason", reason)
                    put("timeout", timeout)
                    if (dialogKiller != null) {
                        put("dialog_killer", dialogKiller)
                    }
                }
            }
            put("method", "tools/call")
        }.toString()

        // Execute the tool call (curl timeout must exceed the server-side execution timeout)
        val run = executeMcpRequest(sessionId, toolCallRequest, timeoutSeconds = timeout.toLong() + 30)
        val data = json.parseToJsonElement(run)

        val messages = buildString {
            data.jsonObject["result"]?.jsonObject["content"]?.jsonArray?.forEach {
                it.jsonObject["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                    println("[MCP LOG]: $text ")
                    appendLine(text)
                }
            }
        }

        val isError = data.jsonObject["result"]?.jsonObject["isError"]?.jsonPrimitive?.booleanOrNull ?: true

        return ProcessResultValue(
            exitCode = if (isError) 1 else 0,
            stdout = messages,
            stderr = "",
        )
    }

    /**
     * Initialize MCP session and return session ID.
     */
    private fun mcpInitialize(): String {
        mcpSessionId?.let { return it }

        val initRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "initialize")
            putJsonObject("params") {
                put("protocolVersion", "2025-11-25")
                putJsonObject("capabilities") { }
                putJsonObject("clientInfo") {
                    put("name", "integration-test")
                    put("version", "1.0")
                }
            }
        }.toString()

        val (responseBody, responseHeaders) = executeMcpRequestRaw(
            sessionId = null,
            requestBody = initRequest,
        )
        json.parseToJsonElement(responseBody)

        val sessionId = responseHeaders[SESSION_HEADER]
            ?.takeIf { it.isNotBlank() }
            ?: error("MCP initialize response missing $SESSION_HEADER header")

        mcpSessionId = sessionId
        return sessionId
    }

    /**
     * Execute an MCP request via curl in the container.
     */
    private fun executeMcpRequest(
        sessionId: String?,
        requestBody: String,
        timeoutSeconds: Long = 30,
    ): String {
        val (responseBody, responseHeaders) = executeMcpRequestRaw(
            sessionId = sessionId ?: mcpSessionId,
            requestBody = requestBody,
            timeoutSeconds = timeoutSeconds,
        )
        responseHeaders[SESSION_HEADER]?.takeIf { it.isNotBlank() }?.let { mcpSessionId = it }

        return json.encodeToString(json.parseToJsonElement(responseBody.trim()))
    }

    private fun executeMcpRequestRaw(
        sessionId: String?,
        requestBody: String,
        timeoutSeconds: Long = 30,
    ): Pair<String, Map<String, String>> {
        // Create curl command
        val curlCommand = buildList {
            add("curl")
            add("-s")  // Silent
            add("-D")  // Dump response headers to stdout
            add("-")
            add("-X")
            add("POST")
            add(guestMcpUrl)
            add("-H")
            add("Content-Type: application/json")
            add("-H")
            add("Accept: application/json")

            // Add MCP session header when available.
            if (sessionId != null) {
                add("-H")
                add("$SESSION_HEADER: $sessionId")
            }

            add("-d")
            add(requestBody)
        }

        val result = driver.startProcessInContainer {
            this
                .args(curlCommand)
                .timeoutSeconds(timeoutSeconds)
                .description("curl MCP request")
        }.assertExitCode(0) { "MCP request failed: ${stdout}" }

        val raw = result.stdout.replace("\r\n", "\n")
        val splitIndex = raw.indexOf("\n\n")
        require(splitIndex >= 0) { "Invalid HTTP response from MCP server: missing headers/body separator" }

        val headerLines = raw.substring(0, splitIndex)
            .lineSequence()
            .drop(1) // Skip HTTP status line.
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains(":") }
            .toList()

        val headers = buildMap {
            for (line in headerLines) {
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                put(name, value)
            }
        }

        val body = raw.substring(splitIndex + 2)
        return body to headers
    }

}
