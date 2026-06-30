/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IdeChannel
import com.jonnyzzz.mcpSteroid.integration.infra.IdeDistribution
import com.jonnyzzz.mcpSteroid.integration.infra.IdeProduct
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Runtime compatibility against **Android Studio** on the 261 platform baseline (2026.1.x, "Quail").
 *
 * Android Studio is built on the **same** IntelliJ 261 platform as IntelliJ IDEA 2026.1, but Google
 * ships it with a different runtime: Android Studio 2026.1 bundles **JBR 21** (class-file v65), whereas
 * IntelliJ IDEA 2026.1 bundles **JBR 25** (class-file v69). A plugin compiled to class-file v69 (a
 * JDK-25 toolchain with no `jvm-target` override) therefore loads fine in IntelliJ IDEA but throws
 * `UnsupportedClassVersionError` in Android Studio. Sibling [PluginRuntimeCompatibilityTest] covers the
 * IntelliJ / PyCharm side; this is the Android Studio gate.
 *
 * Strategy: install the production plugin .zip into Android Studio 261 and exercise the core MCP tools.
 * If the plugin cannot load under Android Studio's JBR, `create` fails fast inside `waitForMcpReady`
 * with the class-file / JBR version mismatch read straight out of idea.log.
 *
 * Host requirement: Android Studio publishes only a Linux **x86_64** archive (no Linux arm64 build), and
 * the IDE container runs the host architecture — so this test executes on an x86_64 Linux host (CI) and
 * cannot run on an Apple-Silicon dev box (it fails fast at download resolution there). The architecture-
 * independent regression — class-file version too high for Android Studio's JBR — is covered locally on
 * any host by the `verifyClassFileVersions` build guard.
 *
 * Run (x86_64 Linux):
 *   ./gradlew :test-integration:test --tests '*AndroidStudioRuntimeCompatTest*'
 */
class AndroidStudioRuntimeCompatTest {

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    fun `runtime compat android studio 261`() = runWithCloseableStack { lifetime ->
        val session = IntelliJContainer.create(
            lifetime,
            IntelliJContainerOpts(
                dockerFileBase = "ide-agent",
                consoleTitle = "runtime-compat-android-studio",
                // A bare project keeps the test focused on plugin load + core MCP tools, with no
                // Android/Gradle sync to stall on — the bytecode/JBR compatibility is what we measure.
                project = IntelliJProject.EmptyProject,
                // Android Studio (Google), current stable. As of 2026 H1 that is Quail / 2026.1.x,
                // which is built on the IntelliJ 261 platform and ships JBR 21. Only the STABLE
                // channel is wired into the downloader (canary/beta live on a separate Google page);
                // version is left unpinned so we always resolve the current stable archive.
                distribution = IdeDistribution.Latest(
                    product = IdeProduct.AndroidStudio,
                    channel = IdeChannel.STABLE,
                ),
            ),
        )

        // 1. list_projects — plugin loaded, MCP server started. Fails fast with the class-file / JBR
        //    mismatch from idea.log if the plugin cannot load under Android Studio's JBR 21.
        val projects = session.mcpSteroid.mcpListProjects()
        Assertions.assertTrue(projects.isNotEmpty(), "Should have at least one project")

        // 2. list_windows — core window/state enumeration works.
        val windows = session.mcpSteroid.mcpListWindows()
        Assertions.assertTrue(windows.isNotEmpty(), "Should have at least one window")

        // 3. execute_code — kotlinc compilation + in-IDE execution works in Android Studio.
        session.mcpSteroid.mcpExecuteCode(
            code = """
                val version = com.intellij.openapi.application.ApplicationInfo.getInstance().fullVersion
                println("ANDROID_STUDIO_COMPAT_OK: ${'$'}version")
            """.trimIndent(),
            taskId = "runtime-compat-android-studio",
            reason = "Android Studio runtime compatibility check",
        ).assertExitCode(0, "execute_code should succeed")
            .assertOutputContains("ANDROID_STUDIO_COMPAT_OK", message = "should print IDE version")
    }
}
