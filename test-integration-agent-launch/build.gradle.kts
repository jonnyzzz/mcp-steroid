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

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
    systemProperty("junit.jupiter.execution.timeout.default", "15m")

    // Windows||Linux gate (see note above). On macOS this task is skipped, so `./gradlew test`,
    // `ciAgentLaunchTests`, and IDE runs on macOS are no-ops for this module.
    enabled = runsHere
    onlyIf("test-integration-agent-launch runs only on Windows/Linux agents") { runsHere }

    doFirst {
        systemProperty(
            "agent.launch.cache.dir",
            layout.buildDirectory.dir("agent-launch-cache").get().asFile.also { it.mkdirs() }.absolutePath,
        )
        systemProperty("installer.ps1.template", installPs1Template.absolutePath)
        systemProperty("installer.sh.template", installShTemplate.absolutePath)
    }
}
