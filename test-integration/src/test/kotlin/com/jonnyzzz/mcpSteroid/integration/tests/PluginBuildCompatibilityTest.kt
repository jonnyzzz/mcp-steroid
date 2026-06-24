package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerVolume
import com.jonnyzzz.mcpSteroid.testHelper.docker.ImageDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.buildDockerImage
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerContainerAndDispose
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Build compatibility: validates the plugin compiles against multiple IntelliJ Platform versions.
 *
 * Run:
 *   ./gradlew :test-integration:test --tests '*PluginBuildCompatibilityTest*'
 */
class PluginBuildCompatibilityTest {

    companion object {
        @JvmStatic @BeforeAll
        fun setUp() = BuildCompatInfra.setUp()
    }

    // Clean-Docker sanity: build the plugin against 2026.1 (the primary build
    // target after commit 2 of the 262 EAP plan) using the project's default
    // Kotlin / IPGP / kotlinx pins. No patches — the default state must build.
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `build plugin with IntelliJ 2026_1`() =
        BuildCompatInfra.buildPlugin("2026.1")

    // Clean-Docker sanity: build the plugin against 262 EAP, exercising the
    // intellij-downloader local() path through products-API resolution of the
    // 262-EAP-SNAPSHOT matrix tag. Reproduces mcp-steroid#18 (the 262
    // StatusBarEx.getBackgroundProcessModels Pair-direction change) if that
    // regression ever resurfaces.
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `build plugin with IntelliJ 262 EAP`() =
        BuildCompatInfra.buildPlugin("262-EAP-SNAPSHOT")
}

/**
 * Verification compatibility: the plugin is built against 253 (default), then the
 * Plugin Verifier checks the same binary against 253, 261, and 262.
 *
 * Strategy: build once against 253, verify the same binary against newer IDEs.
 * Catches API removals, signature changes (like mcp-steroid#18), and other
 * binary incompatibilities before they reach users.
 *
 * Run:
 *   ./gradlew :test-integration:test --tests '*PluginVerificationTest*'
 */
class PluginVerificationTest {

    companion object {
        @JvmStatic @BeforeAll
        fun setUp() = BuildCompatInfra.setUp()
    }

    // The default :ij-plugin:verifyPlugin (driven by McpSteroidIdeTargets.verifierTargets)
    // already covers 261 + 262 via local() routing in-process. These Docker-based
    // cases additionally validate that the verifier runs from a clean checkout in a
    // container — belt-and-braces for the release pipeline.
    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `verify plugin against IntelliJ 2026_1`() =
        BuildCompatInfra.verifyPlugin("""ide(IntelliJPlatformType.IntellijIdeaUltimate, "2026.1")""")

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    fun `verify plugin against IntelliJ 262 EAP`() =
        BuildCompatInfra.verifyPlugin(
            ideEntry = """ide(IntelliJPlatformType.IntellijIdeaUltimate, "262-EAP-SNAPSHOT")""",
        )
}

private object BuildCompatInfra {
    private lateinit var buildImage: ImageDriver

    private val projectHome: File by lazy {
        ProjectHomeDirectory.requireProjectHomeDirectory().toFile()
    }
    private val gradleHomeDir: File by lazy {
        File(
            System.getProperty("test.integration.build.compat.gradle.home")
                ?: error("test.integration.build.compat.gradle.home not set")
        )
    }
    private val ijPlatformCacheDir: File by lazy {
        File(
            System.getProperty("test.integration.build.compat.ij.platform")
                ?: error("test.integration.build.compat.ij.platform not set")
        )
    }

    fun setUp() {
        if (::buildImage.isInitialized) return
        buildImage = buildDockerImage(
            logPrefix = "build-compat",
            dockerfilePath = File(projectHome, "docker/build/Dockerfile"),
            timeoutSeconds = 600,
            quietly = true,
        )
    }

    fun buildPlugin(platformVersion: String, patches: List<SedPatch> = emptyList()) =
        runWithCloseableStack { lifetime ->
            val container = prepareContainer(lifetime, "build-${platformVersion.replace(".", "")}")
            applyPatches(container, patches)

            container.startProcessInContainer {
                this
                    .workingDirInContainer(BUILD_GUEST)
                    .args(
                        "./gradlew", ":ij-plugin:buildPlugin",
                        "-Pmcp.platform.version=$platformVersion",
                        "--no-daemon", "--stacktrace",
                    )
                    .addEnv("GRADLE_USER_HOME", GRADLE_HOME_GUEST)
                    .description("Build plugin for IntelliJ $platformVersion")
                    .timeoutSeconds(1800)
            }.assertExitCode(0) { "buildPlugin failed for IntelliJ $platformVersion: $stderr" }

            container.startProcessInContainer {
                this
                    .workingDirInContainer(BUILD_GUEST)
                    .args("find", "ij-plugin/build/distributions", "-name", "*.zip", "-type", "f")
                    .description("Verify plugin ZIP exists")
                    .timeoutSeconds(10)
            }.assertExitCode(0) { "Plugin distributions dir missing: $stderr" }
                .assertOutputContains(".zip", message = "No plugin ZIP found for IntelliJ $platformVersion")
        }

    fun verifyPlugin(ideEntry: String, extraPatches: List<SedPatch> = emptyList()) =
        runWithCloseableStack { lifetime ->
            val container = prepareContainer(lifetime, "verify")
            applyPatches(container, extraPatches)

            // Replace the pluginVerification ides block with the target IDE
            container.startProcessInContainer {
                this
                    .args(
                        "perl", "-i", "-0pe",
                        """s/pluginVerification \{.*?ides \{.*?\}\s*\}/pluginVerification { ides { $ideEntry } }/s""",
                        "$BUILD_GUEST/ij-plugin/build.gradle.kts",
                    )
                    .description("Set verifier IDE to $ideEntry")
                    .timeoutSeconds(5)
            }.assertExitCode(0) { "Failed to patch pluginVerification: $stderr" }

            // Build against 253 (default), then verify against target IDE
            container.startProcessInContainer {
                this
                    .workingDirInContainer(BUILD_GUEST)
                    .args(
                        "./gradlew", ":ij-plugin:buildPlugin", ":ij-plugin:verifyPlugin",
                        "--no-daemon", "--stacktrace",
                    )
                    .addEnv("GRADLE_USER_HOME", GRADLE_HOME_GUEST)
                    .description("Build + verify plugin")
                    .timeoutSeconds(1800)
            }.assertExitCode(0) { "Build+verify failed: $stderr" }
        }

    private fun prepareContainer(lifetime: CloseableStack, logPrefix: String): ContainerDriver {
        val container = startDockerContainerAndDispose(
            lifetime,
            StartContainerRequest.Companion()
                .image(buildImage)
                .logPrefix(logPrefix)
                .entryPoint("sleep", "infinity")
                .volumes(
                    ContainerVolume(projectHome, SRC_GUEST, "ro"),
                    ContainerVolume(gradleHomeDir, GRADLE_HOME_GUEST),
                    ContainerVolume(ijPlatformCacheDir, IJ_PLATFORM_GUEST),
                ),
        )
        container.startProcessInContainer {
            this
                .args(
                    "bash", "-c",
                    """
                    mkdir -p $PREBUILD_GUEST &&
                    rsync -a --delete
                      --exclude=build/
                      --exclude=.gradle/
                      --exclude=.intellijPlatform/
                      --exclude=node_modules/
                      --exclude=out/
                      --exclude=build-compat/
                      --exclude=.idea/workspace.xml
                      --exclude=*.iml
                      $SRC_GUEST/ $PREBUILD_GUEST/ &&
                    git config --global --add safe.directory '*' &&
                    cd $PREBUILD_GUEST &&
                    git clean -fdx &&
                    cp -a $PREBUILD_GUEST $BUILD_GUEST &&
                    ln -sfn $IJ_PLATFORM_GUEST $BUILD_GUEST/.intellijPlatform
                    """.trimIndent().replace('\n', ' '),
                )
                .description("Prepare clean build tree")
                .timeoutSeconds(300)
        }.assertExitCode(0) { "Failed to prepare build tree: $stderr" }
        return container
    }

    private fun applyPatches(container: ContainerDriver, patches: List<SedPatch>) {
        for (patch in patches) {
            container.startProcessInContainer {
                this
                    .args("sed", "-i", patch.expression, "$BUILD_GUEST/${patch.file}")
                    .description(patch.description)
                    .timeoutSeconds(5)
            }.assertExitCode(0) { "Failed to apply patch '${patch.description}': $stderr" }
        }
    }
}

private data class SedPatch(val file: String, val expression: String, val description: String)

private const val SRC_GUEST = "/mnt/project"
private const val PREBUILD_GUEST = "/tmp/prebuild"
private const val BUILD_GUEST = "/build"
private const val GRADLE_HOME_GUEST = "/cache/gradle-home"
private const val IJ_PLATFORM_GUEST = "/cache/ij-platform"

// Note: the per-version sed patches that lived here previously (KOTLIN_2_4_PATCHES,
// SNAPSHOT_262_PATCHES, NIGHTLY_REPO_PATCHES) are gone as of commit 8 of the 262 EAP
// plan. They were workarounds for the pre-262 state where the project defaulted to
// 253 + Kotlin 2.2.20 + IPGP useInstaller. After commits 2 + 4, the default state
// is 261 + Kotlin 2.3.20 + ktor 3.3.2 + local()-routed IDE resolution via
// intellij-downloader, so no patches are needed to build against 261 or 262 EAP.
