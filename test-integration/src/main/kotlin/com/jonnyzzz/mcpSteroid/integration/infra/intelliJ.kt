/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.integration.infra.McpSteroidDriver.Companion.MCP_STEROID_PORT
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ExecContainerProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import com.jonnyzzz.mcpSteroid.testHelper.docker.copyToContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.mapGuestPathToHostPath
import com.jonnyzzz.mcpSteroid.testHelper.docker.mkdirs
import com.jonnyzzz.mcpSteroid.testHelper.docker.runInContainerDetached
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.writeFileInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import java.io.File
import kotlin.concurrent.thread

class IntelliJDriver(
    private val lifetime: CloseableStack,
    private val driver: ContainerDriver,
    private val guestDir: String,
    val ideProduct: IdeProduct,
    private val skipChangedFilesScanOnStartup: Boolean = false,
    private val disableProjectTrustChecks: Boolean = true,
    private val trustAllProjectPaths: Boolean = true,
) {
    private val intelliJGuestHomeDir = "/opt/idea"
    // Keep project sources on container-local filesystem (not host-mounted volume)
    // so Docker snapshots can capture fully indexed project state consistently.
    private val projectGuestDir = "/home/agent/project-home"
    private val configGuestDir = "$guestDir/ide-config"
    private val systemGuestDir = "/home/agent/ide-system"
    private val logsGuestDir = "$guestDir/ide-log"
    // Plugins live OFF the /mcp-run-dir bind mount — same rationale as
    // systemGuestDir. The unpacked plugin tree (~185 MB, dominated by
    // kotlin-compiler.jar and the bundled prompts) would otherwise be
    // dragged into every TC artifact and run-*.zip. Keeping it container-
    // local costs nothing (fresh unzip per run) and keeps CI artifacts slim.
    private val pluginsGuestDir = "/home/agent/ide-plugins"
    private val steroidGuestDir = "$guestDir/mcp-steroid"

    fun getGuestProjectDir() = projectGuestDir
    fun getGuestSystemDir() = systemGuestDir
    fun getGuestConfigDir() = configGuestDir
    fun getGuestPluginsDir() = pluginsGuestDir

    fun readLogs(): List<String> {
        val file = ideaLogsFile()
        if (!file.exists()) return emptyList()
        return file.readLines()
    }

    private fun ideaLogsFile(): File = driver.mapGuestPathToHostPath(logsGuestDir).resolve("idea.log")

    fun startIde(): RunningContainerProcess {
        driver.mkdirs(intelliJGuestHomeDir)
        driver.mkdirs(projectGuestDir)
        driver.mkdirs(configGuestDir)
        driver.mkdirs(systemGuestDir)
        driver.mkdirs(logsGuestDir)
        driver.mkdirs(pluginsGuestDir)

        driver.startProcessInContainer {
            this
                .args("ls", "-la", intelliJGuestHomeDir)
                .description("ls -la $intelliJGuestHomeDir")
        }.awaitForProcessFinish()

        writeEulaAcceptance()
        writeConsentOptions()
        writeTrustedPaths()
        writeStartupProperties()
        writeEarlyAccessRegistry()
        writeAiPromoState()
        generateVmOptions()
        // JDK registration is NOT done via jdk.table.xml — that path is too
        // fragile (any malformed attribute silently empties the table, triggering
        // the `UnknownSdkStartupChecker` consent modal). Instead:
        //   1. VM options `-Dunknown.sdk.auto=false` / `-Dunknown.sdk=false`
        //      short-circuit `UnknownSdkTrackerQueue` before it instantiates any
        //      `UnknownSdkFixActionDownloadBase` — no modal path ever taken.
        //      See `generateVmOptions()` + `UnknownSdkTracker.java:57,76`.
        //   2. `McpSteroidDriver.mcpRegisterJdks` runs after the IDE is up and
        //      calls `SdkConfigurationUtil.createAndAddSDK` / `JavaSdk.createJdk`
        //      via `steroid_execute_code` — canonical IntelliJ API.

        driver.log("Starting ${ideProduct.displayName}...")
        val launcherPath = "$intelliJGuestHomeDir/bin/${ideProduct.launcherExecutable}"
        driver.startProcessInContainer {
            this
                .args("ls", "-la", "$intelliJGuestHomeDir/bin")
                .description("ls -la $intelliJGuestHomeDir/bin")
        }.awaitForProcessFinish()

        // Rider requires the .sln file path (not the directory) to skip the
        // "Select a Solution to Open" dialog that blocks the main window from appearing.
        val launchTarget = if (ideProduct == IdeProduct.Rider) {
            val slnFile = findSlnFile(projectGuestDir)
            if (slnFile != null) {
                driver.log("Rider: opening solution file $slnFile")
                slnFile
            } else {
                driver.log("Rider: no .sln file found, falling back to directory")
                projectGuestDir
            }
        } else {
            projectGuestDir
        }

        val idea = driver.runInContainerDetached(
            listOf(launcherPath, launchTarget),
        )

        val logFile = ideaLogsFile()
        val logsReady = try {
            waitFor(60_000L, "for ${ideProduct.displayName} log file") {
                !idea.isRunning() || (logFile.exists() && logFile.length() > 0L)
            }
            logFile.exists() && logFile.length() > 0L
        } catch (e: Exception) {
            driver.log("Timed out waiting for ${ideProduct.displayName} log file: ${e.message}")
            false
        }

        if (!idea.isRunning()) {
            idea.printProcessInfo()
            throw RuntimeException("${ideProduct.displayName} exited unexpectedly with code ${idea.exitCode}. See logs above for details.")
        }

        if (!logsReady) {
            println(
                "[IDE-AGENT] ${ideProduct.displayName} log file is not available yet at ${logFile.absolutePath}. " +
                        "Continuing startup and relying on window/MCP readiness checks."
            )
        } else {
            val ijLogsStream = thread(isDaemon = true, name = "ijLogsStream") {
                runCatching {
                    logFile.bufferedReader().use { reader ->
                        while (true) {
                            val line = reader.readLine()
                            if (line == null) {
                                Thread.sleep(100)
                                continue
                            }
                            println("[IntelliJ LOG] $line")
                        }
                    }
                }
            }

            lifetime.registerCleanupAction {
                ijLogsStream.interrupt()
            }
        }

        return idea
    }

    private fun generateVmOptions() {
        val vmXmx = System.getProperty("test.integration.ide.vm.xmx")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "6g"
        val vmXms = System.getProperty("test.integration.ide.vm.xms")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "1g"

        val opts = buildString {
            appendLine("-Xmx$vmXmx")
            appendLine("-Xms$vmXms")

            appendLine("# Redirect IDE directories to explicit paths")
            appendLine("-Didea.config.path=$configGuestDir")
            appendLine("-Didea.system.path=$systemGuestDir")
            appendLine("-Didea.log.path=$logsGuestDir")
            appendLine("-Didea.plugins.path=$pluginsGuestDir")
            if (skipChangedFilesScanOnStartup) {
                appendLine("# Warm snapshot startup: skip changed-files scan to avoid re-indexing")
                appendLine("-Didea.indexes.pretendNoFiles=true")
            }

            appendLine("# MCP Steroid plugin configuration")
            appendLine("-Dmcp.steroid.server.host=0.0.0.0")
            appendLine("-Dmcp.steroid.server.port=${MCP_STEROID_PORT.containerPort}")
            appendLine("-Dmcp.steroid.review.mode=NEVER")
            appendLine("-Dmcp.steroid.dialog.killer.enabled=true")
            appendLine("-Dmcp.steroid.updates.enabled=false")
            appendLine("-Dmcp.steroid.analytics.enabled=false")
            appendLine("-Dmcp.steroid.idea.description.enabled=false")
            appendLine("-Dmcp.steroid.storage.path=$steroidGuestDir")

            appendLine("# Suppress AI promo window (prevents 8-minute startup deadlock in Docker)")
            appendLine("# AIPromoWindowAdvisor fetches a remote URL on first run; in Docker this")
            appendLine("# times out after 480s and blocks VfsData via fleet.kernel.Transactor.")
            appendLine("-Dllm.show.ai.promotion.window.on.start=false")
            appendLine("# Reduce network connection timeout (safety net: cuts any remaining")
            appendLine("# timeout-based delay from ~480s down to ~15s: 5 retries x 3s).")
            appendLine("-Didea.connection.timeout=3000")
            appendLine()
            appendLine("# License server for commercial IDEs (Rider, etc.)")
            appendLine("-DJETBRAINS_LICENSE_SERVER=https://flsv1.labs.jb.gg")
            appendLine()
            appendLine("# Skip EULA, consent dialogs, trust prompts, and onboarding")
            if (disableProjectTrustChecks) {
                appendLine("-Didea.trust.disabled=true")
            }
            appendLine("-Djb.consents.confirmation.enabled=false")
            appendLine("-Djb.privacy.policy.text=<!--999.999-->")
            appendLine("-Djb.privacy.policy.ai.assistant.text=<!--999.999-->")
            appendLine("-Dmarketplace.eula.reviewed.and.accepted=true")
            appendLine("-Dwriterside.eula.reviewed.and.accepted=true")
            appendLine("-Didea.initially.ask.config=never")
            appendLine("-Dide.newUsersOnboarding=false")
            appendLine("-Dnosplash=true")
            appendLine()
            appendLine("# Belt-and-suspenders vs the Corretto-download consent modal:")
            appendLine("# * UnknownSdkTracker.isEnabled() (UnknownSdkTracker.java:57) short-circuits when")
            appendLine("#   `unknown.sdk` is false — no tracker activity at all, no download fixes ever created.")
            appendLine("# * UnknownSdkTracker.createCollector() (UnknownSdkTracker.java:76) returns null when")
            appendLine("#   `unknown.sdk.auto` is false — tracker exists but never runs the lookup, so")
            appendLine("#   UnknownSdkTrackerQueue.queue skips onLookupCompleted (UnknownSdkTrackerQueue.kt:44)")
            appendLine("#   and never instantiates UnknownSdkFixActionDownloadBase → no collectConsent modal.")
            appendLine("# Default for both is true; we force-disable so the Docker IDE cannot prompt.")
            appendLine("# * CompilerDriverUnknownSdkTracker.fixSdkSettings (CompilerDriverUnknownSdkTracker.kt:41)")
            appendLine("#   short-circuits when `unknown.sdk.modal.jps` is false — without this flag,")
            appendLine("#   ProjectTaskManager.build(...) fires a `Task.WithResult` with title")
            appendLine("#   'Resolving SDKs…' (modal), which our ModalityStateMonitor cancels the exec on.")
            appendLine("# `mcpRegisterJdks` (invoked from IntelliJ_factoryKt.create after waitForMcpReady)")
            appendLine("# is the primary mechanism — this trio of flags is the safety net.")
            appendLine("-Dunknown.sdk=false")
            appendLine("-Dunknown.sdk.auto=false")
            appendLine("-Dunknown.sdk.modal.jps=false")
            appendLine()
            appendLine("# Suppress telemetry, update checks, and async network startup activities")
            appendLine("-Didea.suppress.statistics.report=true")
            appendLine("-Didea.local.statistics.without.report=true")
            appendLine("-Dfeature.usage.event.log.send.on.ide.close=false")
            appendLine("-Dide.enable.notification.trace.data.sharing=false")
            appendLine("-Didea.updates.url=http://127.0.0.1")
            appendLine("-Dide.no.platform.update=true")
            appendLine("-Dide.browser.disabled=true")
            appendLine()
        }

        driver.writeFileInContainer("$intelliJGuestHomeDir.vmoptions", opts)
    }

    private fun writeEulaAcceptance() {
        val prefsXml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8" standalone="no"?>""")
            appendLine("""<!DOCTYPE map SYSTEM "http://java.sun.com/dtd/preferences.dtd">""")
            appendLine("""<map MAP_XML_VERSION="1.0">""")
            appendLine("""  <entry key="accepted_version" value="999.999"/>""")
            appendLine("""  <entry key="privacy_policy_accepted_version" value="999.999"/>""")
            appendLine("""  <entry key="eua_accepted_version" value="999.999"/>""")
            appendLine("""  <entry key="euaCommunity_accepted_version" value="999.999"/>""")
            appendLine("""  <entry key="ij_euaEap_accepted_version" value="999.999"/>""")
            appendLine("""</map>""")
        }

        driver.writeFileInContainer(
            "/home/agent/.java/.userPrefs/jetbrains/privacy_policy/prefs.xml",
            prefsXml,
        )
    }

    private fun writeConsentOptions() {
        val timestamp = System.currentTimeMillis() - 1000
        driver.writeFileInContainer(
            "/home/agent/.config/JetBrains/consentOptions/accepted",
            "rsch.send.usage.stat:1.1:0:${timestamp}",
        )
    }

    /**
     * Pre-write IDE startup properties to suppress first-startup onboarding dialogs.
     *
     * IntelliJ 2025.3.3+ (build 253.31033+) shows a "Meet the Islands Theme" modal dialog
     * on first startup via NewUiOnboardingStartupActivity, which blocks the EDT and prevents
     * background Gradle import from executing. We suppress the dialog via the proposeOnboarding
     * code path by marking onboarding as already proposed.
     */
    private fun writeStartupProperties() {
        // "RunOnceActivity.llm.onboarding.window.launcher.v7" = "true" makes
        // AIPromoWindowLauncher skip the promo window on first project open
        // (checked via PropertiesComponent.getBoolean before any verdict calculation).
        val otherXml = """<application>
  <component name="PropertyService"><![CDATA[{"keyToString":{"experimental.ui.on.first.startup":"true","experimental.ui.onboarding.proposed.version":"suppressed","RunOnceActivity.llm.onboarding.window.launcher.v7":"true"}}]]></component>
</application>
"""
        driver.writeFileInContainer(
            "$configGuestDir/options/other.xml",
            otherXml,
        )
    }

    /**
     * Pre-write the early-access registry to suppress the ClassicUiToIslandsMigration.
     *
     * IntelliJ 2025.3.3+ runs ClassicUiToIslandsMigration.enableNewUiWithIslands() at bootstrap.
     * If the IDE detects a Classic→Islands theme migration, it sets SHOW_NEW_UI_ONBOARDING_ON_START=true,
     * which then causes the "Meet the Islands Theme" blocking modal dialog to appear.
     *
     * By pre-setting "switched.from.classic.to.islands=false" in the early-access registry file,
     * the migration code sees a non-null value and returns early (processed-once guard),
     * preventing SHOW_NEW_UI_ONBOARDING_ON_START from ever being set to true.
     */
    private fun writeEarlyAccessRegistry() {
        // Format: alternating lines of key and value (one entry per two lines)
        val content = "switched.from.classic.to.islands\nfalse\n"
        driver.writeFileInContainer(
            "$configGuestDir/early-access-registry.txt",
            content,
        )
    }

    /**
     * Pre-write the AI promo window state to prevent AIPromoWindowAdvisor from
     * performing a remote network call that can block VfsData initialization for up
     * to 8 minutes in Docker containers (socket connection timeout = 480s).
     *
     * Root cause: AIPromoWindowAdvisorPreheat starts verdict calculation immediately
     * after app init. In a fresh container, the verdict is UNSURE → it fetches
     * https://frameworks.jetbrains.com/llm-config/v2/products-promo.txt which may
     * time out. During this time, fleet.kernel.Transactor is blocked, which in turn
     * prevents VfsData from initializing, so the main IntelliJ window never appears.
     *
     * Fix: Pre-write "wasShown=true" so getVerdictFromStored() returns false immediately,
     * and "shouldShowNextTime=NO" as an additional guard.
     */
    private fun writeAiPromoState() {
        val xml = """<application>
  <component name="AIOnboardingPromoWindowAdvisor">
    <option name="shouldShowNextTime" value="NO" />
    <option name="wasShown" value="true" />
    <option name="attempts" value="1" />
  </component>
</application>
"""
        driver.writeFileInContainer(
            "$configGuestDir/options/AIOnboardingPromoWindowAdvisor.xml",
            xml,
        )
    }

    private fun writeTrustedPaths() {
        val trustedPathsXml = buildString {
            appendLine("""<application>""")
            appendLine("""  <component name="Trusted.Paths">""")
            appendLine("""    <option name="TRUSTED_PROJECT_PATHS">""")
            appendLine("""      <map>""")
            appendLine("""        <entry key="$projectGuestDir" value="true" />""")
            appendLine("""      </map>""")
            appendLine("""    </option>""")
            appendLine("""  </component>""")
            if (trustAllProjectPaths) {
                appendLine("""  <component name="Trusted.Paths.Settings">""")
                appendLine("""    <option name="TRUSTED_PATHS">""")
                appendLine("""      <list>""")
                appendLine("""        <option value="/" />""")
                appendLine("""      </list>""")
                appendLine("""    </option>""")
                appendLine("""  </component>""")
            }
            appendLine("""</application>""")
        }

        driver.writeFileInContainer(
            "$configGuestDir/options/trusted-paths.xml",
            trustedPathsXml,
        )
    }

    /**
     * Find a .sln file in the guest project directory.
     * Lists files in the container and returns the first .sln path found, or null.
     */
    private fun findSlnFile(guestProjectDir: String): String? {
        val result = driver.startProcessInContainer {
            this
                .args("bash", "-c", "ls $guestProjectDir/*.sln 2>/dev/null")
                .timeoutSeconds(5)
                .quietly()
                .description("find .sln in $guestProjectDir")
        }.awaitForProcessFinish()

        if (result.exitCode != 0) return null
        return result.stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.endsWith(".sln") }
    }

    fun deployPluginToContainer(pluginZipPath: File) {
        // Staging location is container-local — NOT on the /mcp-run-dir bind
        // mount. Keeps plugin.zip (~185 MB) out of the TC artifact zip.
        val containerTempDir = "/home/agent/ide-plugin-staging"
        val containerTempZip = "$containerTempDir/plugin.zip"
        println("[IDE-AGENT] Deploying plugin to container: $pluginZipPath")

        require(pluginZipPath.isFile()) { "Plugin zip does not exist: $pluginZipPath" }

        driver.mkdirs(pluginsGuestDir)
        driver.mkdirs(containerTempDir)
        // Clear any previous plugin tree before unzipping. When we reuse a
        // warmed snapshot image, the previous plugin version is baked into
        // /home/agent/ide-plugins — unzipping over it leaves a mixture of
        // old + new plugin files which IDEA happily picks up and crashes on.
        driver.startProcessInContainer {
            this
                .args("bash", "-c", "rm -rf '$pluginsGuestDir'/* '$containerTempDir'/*")
                .timeoutSeconds(30)
                .quietly()
                .description("clear $pluginsGuestDir before plugin deploy")
        }.assertExitCode(0) { "Failed to clear $pluginsGuestDir" }
        driver.copyToContainer(pluginZipPath, containerTempZip)
        driver.startProcessInContainer {
            this
                .args("unzip", "-o", containerTempZip)
                .workingDirInContainer(pluginsGuestDir)
                .timeoutSeconds(60)
                .quietly()
                .description("unzip plugin to $pluginsGuestDir")
        }.assertExitCode(0) { "$containerTempZip failed to unpack: $pluginZipPath" }
        // Drop the staged zip — the unpacked tree is the only thing IDEA needs.
        driver.startProcessInContainer {
            this
                .args("rm", "-f", containerTempZip)
                .timeoutSeconds(10)
                .quietly()
                .description("remove staged $containerTempZip")
        }.assertExitCode(0) { "Failed to remove $containerTempZip" }
    }

}
