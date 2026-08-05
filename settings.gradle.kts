// Foojay disco-api resolver so Gradle can auto-download a matching JDK when
// the daemon toolchain criteria in gradle/gradle-daemon-jvm.properties can't
// be satisfied from discovered local JDKs. Required by `updateDaemonJvm` in
// Gradle 9.4+, which fails with "Toolchain download repositories have not
// been configured" without a resolver plugin on the settings classpath.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "mcp-steroid"

// Remote Gradle build cache — https://buildfetch.com/ — shared by GitHub Actions,
// TeamCity, and developer machines. `org.gradle.caching=true` (gradle.properties)
// switches caching on; this block only adds the remote node on top of the local one.
//
// Tokens, in precedence order:
//  1. BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN env var or gradle property — the
//     read-write secret on CI, or a personal token in ~/.gradle/gradle.properties.
//  2. Local builds without a token fall back to the hardcoded PUBLIC READ-ONLY
//     token below: contributors get cache hits with zero setup. Verified truly
//     read-only (2026-07-30): the server 200-ACKs its pushes but discards them.
// On CI (CI / TEAMCITY_VERSION env present) there is NO public fallback — CI
// either authenticates with its read-write secret or runs without the remote node.
//
// RELEASE BUILDS USE NO CACHE AT ALL. `-Pmcp.release.build=true` (see
// release/release-instructions.md, Stage 6) disables both the local directory
// cache and the remote node, so every artifact in a shipped release is compiled
// from source in that very invocation — never assembled from cache entries.
// Same strict value parsing as parseBooleanProperty in the root build script.
val isReleaseBuild = when (val raw = providers.gradleProperty("mcp.release.build").orNull?.trim()?.lowercase()) {
    null, "0", "false", "no", "off" -> false
    "1", "true", "yes", "on" -> true
    else -> error("Unsupported mcp.release.build value '$raw' (expected true/false or 1/0)")
}

buildCache {
    local {
        isEnabled = !isReleaseBuild
    }
    remote<HttpBuildCache> {
        url = uri("https://cache.eu-central-a.buildfetch.com/pOImKP/gradle/")

        // GitHub Actions sets CI=true; TeamCity sets TEAMCITY_VERSION but not CI.
        val isCi = providers.environmentVariable("CI").isPresent ||
                providers.environmentVariable("TEAMCITY_VERSION").isPresent

        credentials {
            username = "token-auth"
            // `takeIf isNotBlank`: on GH Actions, `${{ secrets.X }}` in a fork PR
            // resolves to an EMPTY string (not unset) — a blank value must mean
            // "no explicit token", not "authenticate with empty credentials".
            val explicitToken = "BUILDFETCH_GRADLE_REMOTE_CACHE_TOKEN".let {
                providers.environmentVariable(it).orElse(providers.gradleProperty(it)).orNull
            }?.takeIf { it.isNotBlank() }
            password = explicitToken ?: "6f191bd0-1474-4d4e-9fd1-debee45dc35f".takeUnless { isCi }
        }

        // BuildFetch recommends cache writes from CI only (reproducible environment).
        isPush = isCi

        isEnabled = !isReleaseBuild && credentials.password != null
    }
}

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

include(":devrig-common")

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
