import org.gradle.internal.os.OperatingSystem

plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.junit:junit-bom:5.11.4"))
    implementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.slf4j:slf4j-simple:2.0.17")
}

kotlin {
    jvmToolchain(25)
}

// This whole suite is STRUCTURALLY Windows-only: it downloads the Windows build of Claude Code and
// drives Windows PowerShell / cmd.exe command resolution — behaviour that cannot be reproduced on
// macOS/Linux. Per the repo rule (root CLAUDE.md → "The only acceptable skip is at the Gradle task
// level (enabled = !condition) when an entire suite is structurally incompatible with the platform"),
// we gate the whole test task on the host OS instead of using runtime assumeTrue/@EnabledOnOs skips.
val isWindows = OperatingSystem.current().isWindows

// The generated install.ps1 the InstallerScriptWindowsTest validates comes from :installer-gen. Read
// the TEMPLATE from that module's SOURCE tree (never its build/ output — cross-subproject build/ access
// is banned). Resolved at configuration time; passed to the test as a system property.
val installPs1Template =
    project(":installer-gen").projectDir.resolve("src/main/resources/templates/install.ps1.tmpl")

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
    systemProperty("junit.jupiter.execution.timeout.default", "15m")

    // Windows-only gate (see note above). Off Windows this task is skipped, so `./gradlew test`,
    // `ciWindowsTests`, and IDE runs on macOS/Linux are all no-ops for this module.
    enabled = isWindows
    onlyIf("test-integration-windows is a Windows-only suite (Claude Windows build + PowerShell)") { isWindows }

    doFirst {
        systemProperty(
            "windows.test.cache.dir",
            layout.buildDirectory.dir("windows-test-cache").get().asFile.also { it.mkdirs() }.absolutePath,
        )
        systemProperty("windows.installer.ps1.template", installPs1Template.absolutePath)
    }
}
