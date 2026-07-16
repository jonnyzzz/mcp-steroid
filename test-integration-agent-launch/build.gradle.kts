import org.gradle.internal.os.OperatingSystem

plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    // Shared process-runner util (RunProcessRequest / ProcessRunner) — the same pipeline every other
    // process-driving test in the repo uses: consistent [prefix] logging, timeout + destroyForcibly,
    // captured stdout/stderr in the returned ProcessResult.
    implementation(project(":test-helper"))
    // The Windows execution test drives writeInstallerScripts + DevrigEntry directly to render a
    // real, executable install.ps1 into a temp dir (the syntax-check test uses placeholder string
    // replacement, which is fine for a Parser::ParseFile check but not for an end-to-end run).
    testImplementation(project(":installer-gen"))

    implementation(platform("org.junit:junit-bom:5.11.4"))
    implementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.slf4j:slf4j-simple:2.0.17")
}

kotlin {
    jvmToolchain(25)
}

// This suite downloads the real Claude Code build for the host OS and drives its plugin MCP/hook
// launch resolution — behaviour observable only on a real Windows or Linux agent (macOS has a build too
// but is not a CI target here). It is structurally incompatible with any other host, so per the repo
// rule (root CLAUDE.md → "The only acceptable skip is at the Gradle task level (enabled = !condition)
// when an entire suite is structurally incompatible with the platform") we gate the whole test task on
// the host OS rather than using runtime assumeTrue/@EnabledOnOs skips. Tests that DO run assert the
// per-OS-correct outcome (an OS-conditional assertion is not a skip).
val os = OperatingSystem.current()
val runsHere = os.isWindows || os.isLinux

// Generated installer templates from :installer-gen. Read from that module's SOURCE tree (never its
// build/ output — cross-subproject build/ access is banned). Resolved at configuration time.
val installerGenDir = project(":installer-gen").projectDir
val installPs1Template = installerGenDir.resolve("src/main/resources/templates/install.ps1.tmpl")
val installShTemplate = installerGenDir.resolve("src/main/resources/templates/install.sh.tmpl")

// Shared config for every Test task in this module (main cross-OS `test` + the Windows-only PS lane).
fun Test.commonAgentLaunchTestConfig() {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
    systemProperty("junit.jupiter.execution.timeout.default", "15m")
    doFirst {
        systemProperty(
            "agent.launch.cache.dir",
            layout.buildDirectory.dir("agent-launch-cache").get().asFile.also { it.mkdirs() }.absolutePath,
        )
        systemProperty("installer.ps1.template", installPs1Template.absolutePath)
        systemProperty("installer.sh.template", installShTemplate.absolutePath)
    }
}

// `InstallerPs1ExecutionTest` drives NATIVE powershell.exe, so it is structurally Windows-only. It shares
// this module with the cross-OS `ClaudeAgentLaunchTest`, so rather than a banned runtime skip
// (`assumeTrue(isWindows)`), it is gated at the TASK level — the only skip the root CLAUDE.md allows. The
// main `test` task EXCLUDES it (it still runs + reports under `windowsPs1Test`, so it is not hidden), and
// `windowsPs1Test` runs ONLY it, `enabled = isWindows`.
val installerPs1TestClass = "com.jonnyzzz.mcpSteroid.agentlaunch.InstallerPs1ExecutionTest"

tasks.test {
    commonAgentLaunchTestConfig()

    // Windows||Linux gate (see note above). On macOS this task is skipped, so `./gradlew test`,
    // `ciAgentLaunchTests`, and IDE runs on macOS are no-ops for this module.
    enabled = runsHere
    onlyIf("test-integration-agent-launch runs only on Windows/Linux agents") { runsHere }

    // The Windows-native PS test runs under `windowsPs1Test` instead (see below).
    filter { excludeTestsMatching(installerPs1TestClass) }
}

val windowsPs1Test = tasks.register<Test>("windowsPs1Test") {
    group = "verification"
    description = "Runs InstallerPs1ExecutionTest end-to-end under native Windows powershell.exe (+ pwsh). Windows-only."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    commonAgentLaunchTestConfig()

    // Task-level Windows gate — the compliant place for a structural OS skip (no runtime assumeTrue).
    enabled = os.isWindows
    onlyIf("InstallerPs1ExecutionTest needs native powershell.exe (Windows only)") { os.isWindows }

    filter { includeTestsMatching(installerPs1TestClass) }
}

// Keep `check` (and thus CI aggregators that depend on it) running the Windows PS lane on Windows agents.
tasks.named("check") { dependsOn(windowsPs1Test) }
