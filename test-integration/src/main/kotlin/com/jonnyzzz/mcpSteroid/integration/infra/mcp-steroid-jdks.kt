package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode

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
fun McpSteroidDriver.mcpRegisterJdks() {
    val discovered = driver.startProcessInContainer {
        this
            .args("bash", "-c", "ls -1d /usr/lib/jvm/temurin-*-jdk-* 2>/dev/null || true")
            .timeoutSeconds(10)
            .quietly()
            .description("list Temurin JDKs in /usr/lib/jvm")
    }.assertExitCode(0) { "Listing Temurin JDKs failed: $stderr" }

    val temurinDirs = discovered.stdout.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSortedSet()

    println("[JDK-REGISTER] Discovered Temurin dirs: $temurinDirs")

    var code = $$"""
            import com.intellij.openapi.application.writeAction
            import com.intellij.openapi.projectRoots.JavaSdk
            import com.intellij.openapi.projectRoots.ProjectJdkTable

            val javaSdkType = JavaSdk.getInstance()
            val table = ProjectJdkTable.getInstance()

        """
    for (homePath in temurinDirs) {
        val version = homePath.substringAfter("temurin-").substringBefore("-jdk")
            .toIntOrNull() ?: error("Failed to parse version in $homePath")
        // Match the generator (jdk-table-xml.kt): JDK <= 8 is named "1.8", modern JDKs use the bare major.
        val majorAlias = if (version <= 8) "1.$version" else "$version"

        for (name in listOf(majorAlias, "corretto-$majorAlias", "temurin-$majorAlias")) {
            code += $$"""

                    if (table.findJdk("$$name") != null) {
                        println("[JDK-ADD]\talready-registered\tname=$$name")
                    } else {
                        val homeFile = java.io.File("$$homePath")
                        require(java.io.File(homeFile, "bin/java").isFile) { "Not a JDK home: $$homePath" }
                        val sdk = javaSdkType.createJdk("$$name", "$$homePath", false)
                        writeAction { table.addJdk(sdk) }
                        println("[JDK-ADD]\tregistered\tname=${sdk.name}\thome=${sdk.homePath}\tversion=${sdk.versionString}")
                    }

        """
        }
    }

    mcpExecuteCode(
        code = code,
        reason = "Register JDKs for test setup",
        timeout = 60,
    ).assertExitCode(0) { "failed to register JDKs: $stderr" }
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
fun McpSteroidDriver.mcpResolveUnknownSdks() {
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
            reason = "Resolve unknown SDKs to prevent false-positive build errors",
            timeout = 30,
        )
    } catch (e: Exception) {
        throw Error("[SDK-RESOLVE] Warning: SDK resolution failed: ${e.message}", e)
    }
}


/**
 * Set the project SDK to a registered JDK by version name (e.g. "21", "17").
 * If the project already has a different SDK, replace it with the requested version.
 * JDKs must have been registered first via [mcpRegisterJdks].
 */
fun McpSteroidDriver.mcpSetProjectSdk(jdkVersion: String) {
    val code = $$"""
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.ex.JavaSdkUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.application.edtWriteAction

val currentSdk = ProjectRootManager.getInstance(project).projectSdk
val requestedVersion = "$$jdkVersion"
fun matchesRequestedVersion(sdkName: String): Boolean =
    sdkName == requestedVersion ||
            sdkName == "temurin-$requestedVersion" ||
            sdkName == "corretto-$requestedVersion"

if (currentSdk != null && matchesRequestedVersion(currentSdk.name)) {
    println("[SDK] Project SDK already set: ${currentSdk.name}")
} else {
    val javaSdks = ProjectJdkTable.getInstance().getSdksOfType(JavaSdk.getInstance())
    println("[SDK] Available SDKs: ${javaSdks.map { it.name }}")
    val sdk = javaSdks.firstOrNull { matchesRequestedVersion(it.name) }
    if (sdk != null) {
        if (currentSdk == null) {
            println("[SDK] Applying SDK: ${sdk.name} at ${sdk.homePath}")
        } else {
            println("[SDK] Replacing project SDK ${currentSdk.name} with ${sdk.name} at ${sdk.homePath}")
        }
        edtWriteAction { JavaSdkUtil.applyJdkToProject(project, sdk) }
    } else {
        error("[SDK] No SDK matching version $$jdkVersion found")
    }
}
"done"
""".trimIndent()

    try {
        mcpExecuteCode(
            code = code,
            reason = "Set project SDK to JDK $jdkVersion",
            timeout = 30,
        )
    } catch (e: Exception) {
        throw Error("[SDK] Project SDK setup failed: ${e.message}", e)
    }
}
