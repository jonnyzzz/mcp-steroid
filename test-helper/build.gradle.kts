import java.util.concurrent.TimeUnit

plugins {
    `java-library`
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":closeable-stack"))
    implementation(project(":ai-agents"))
    implementation(project(":agent-output-filter"))
    implementation(project(":prompts"))
    // PidMarker / IdeInfo / PluginInfo for the test-only fake marker file.
    // :mcp-steroid-server is plain JVM Kotlin (no com.intellij imports), so it
    // is safe to pull into test-helper's main classpath.
    implementation(project(":mcp-steroid-server"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // opentest4j + JUnit 4 on the main classpath so AIAgentCompanion.create() can
    // skip tests when an API key is missing on CI. JUnit 4's AssumptionViolatedException
    // is needed because CliClaudeIntegrationTest uses BasePlatformTestCase (JUnit 4 runner)
    // which doesn't recognize opentest4j's TestAbortedException as a skip signal.
    //
    // Test-helper deliberately does NOT pull in JUnit Jupiter on the main
    // classpath — consumers of test-helper use multiple JUnit versions, and
    // forcing one would break some of them. Helpers that need to fail a test
    // throw plain `AssertionError` (which every JUnit version surfaces as a
    // failure).
    implementation("org.opentest4j:opentest4j:1.3.0")
    implementation("junit:junit:4.13.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

/**
 * `:test-helper:test` is structurally tied to the canonical Linux/macOS dev box.
 * It spins up Docker containers (DockerReaperTest, Docker*ProgressTest) and drives
 * real OS processes through a POSIX shell (bash/echo/pwd round-trips, JUnit @TempDir
 * cleanup that assumes Unix file-handle semantics).
 *
 * On a host without a running Docker daemon — e.g. a Windows dev machine where Docker
 * Desktop isn't started — every Docker-backed case fails at setup and the shell cases
 * hit Windows path / temp-dir-locking quirks. Rather than fail `./gradlew build` with a
 * cryptic stack, gate the whole suite on a live Docker daemon and tell the user exactly
 * what to do.
 *
 * This is the CLAUDE.md-sanctioned exception: a Gradle-task-level `onlyIf` guard for a
 * suite that is structurally incompatible with the current platform — never a runtime
 * try/catch-and-skip inside a test body.
 */
fun isDockerRunning(): Boolean {
    return try {
        val process = ProcessBuilder("docker", "info")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            logger.lifecycle("Docker daemon check timed out — `docker info` did not respond within 20s")
            false
        } else {
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                logger.lifecycle("Docker daemon is not running — `docker info` exited with $exitCode")
            }
            exitCode == 0
        }
    } catch (e: Exception) {
        // docker CLI not on PATH at all
        logger.lifecycle("Docker CLI is not available: ${e.message}")
        false
    }
}

private val isWindowsHost: Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("win")

tasks.test {
    useJUnitPlatform()

    onlyIf("Requires a POSIX host (Linux/macOS) with a running Docker daemon") {
        // The suite is structurally a Linux/macOS + Docker suite (see the
        // isDockerRunning KDoc above). Two independent requirements; report
        // whichever is missing so the user knows exactly what to do.
        if (isWindowsHost) {
            logger.warn(
                """
                |
                |======================================================================================
                |  :test-helper:test SKIPPED — not supported on Windows.
                |
                |  These tests round-trip arguments through a POSIX shell (bash/echo/pwd), rely on
                |  Unix @TempDir file-handle semantics, and bind-mount /var/run/docker.sock. On
                |  Windows the shell mangles globs/quoting, temp dirs can't be deleted while a child
                |  process holds them, and Git Bash rewrites the socket path to D:\var\run\... .
                |
                |  -> Run this suite on Linux/macOS, inside WSL2, or on CI. It is intentionally gated
                |     out of `./gradlew build` on Windows.
                |======================================================================================
                """.trimMargin()
            )
            return@onlyIf false
        }

        val dockerUp = isDockerRunning()
        if (!dockerUp) {
            logger.warn(
                """
                |
                |======================================================================================
                |  :test-helper:test SKIPPED — Docker is not running.
                |
                |  These tests start Docker containers (DockerReaperTest, *ProgressTest). They need a
                |  live Docker daemon.
                |
                |  -> Start Docker Desktop (or `dockerd`), wait until `docker info` succeeds, then
                |     re-run:   ./gradlew :test-helper:test
                |======================================================================================
                """.trimMargin()
            )
        }
        dockerUp
    }
}
