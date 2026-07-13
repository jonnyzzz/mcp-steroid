// Foojay disco-api resolver so Gradle can auto-download a matching JDK when
// the daemon toolchain criteria in gradle/gradle-daemon-jvm.properties can't
// be satisfied from discovered local JDKs. Required by `updateDaemonJvm` in
// Gradle 9.4+, which fails with "Toolchain download repositories have not
// been configured" without a resolver plugin on the settings classpath.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "mcp-steroid"

// On Windows hosts: pre-materialize the bundled 7-Zip Windows binaries before any
// project is configured, so LocalIdeProvisioner's config-phase .exe unpack has the
// extractor on disk via SevenZipLocator's system-property hook. Mac/Linux config
// phases unpack the IDE via .tar.gz / .dmg and never hit the .exe path.
if (System.getProperty("os.name").lowercase().contains("win")) {
    apply(from = "gradle/seven-zip-bootstrap.settings.gradle.kts")
}

include(":ai-agents")
include(":agent-output-filter")
include(":closeable-stack")

include(":prompt-generator")
include(":kotlin-cli")
include(":prompts-api")
include(":prompts")
include(":intellij-downloader")

include(":ij-plugin")
include(":mcp-core")
include(":mcp-http")
include(":mcp-stdio")
include(":mcp-steroid-server")
include(":execution-storage")

include(":ocr-common")
include(":ocr-tesseract")

include(":test-helper")
include(":test-integration")
include(":test-integration-agent-launch")
include(":test-experiments")

include(":npx")
include(":npx-kt")

include(":installer-gen")
include(":website-gen")

include(":experiments-report")
