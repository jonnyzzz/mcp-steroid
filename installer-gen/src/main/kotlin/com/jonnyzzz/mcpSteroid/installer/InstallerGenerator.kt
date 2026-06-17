/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Generates the self-contained `install.sh` (macOS + Linux) and `install.ps1` (Windows) by merging
 * the committed coordinate files (`jdk-coordinates.json` + `devrig-coordinates.json`) with the
 * devrig `version`, baking every URL/sha256/format/subpath into the scripts as a per-platform table.
 *
 * Pure data-merge: it reads files only, builds no artifacts, and depends on neither the devrig nor
 * the plugin build. The shipped scripts parse no manifest at runtime.
 */

/** The five supported platforms, keyed `<os>-<cpu>`. The script split is by OS. */
val POSIX_PLATFORMS = listOf("macos-arm64", "linux-arm64", "linux-x64")
val WINDOWS_PLATFORMS = listOf("windows-x64", "windows-arm64")
val ALL_PLATFORMS = POSIX_PLATFORMS + WINDOWS_PLATFORMS

@Serializable
data class JdkCoordinates(val schema: Int = 1, val platforms: Map<String, JdkEntry>)

@Serializable
data class JdkEntry(
    val vendor: String,
    val version: String,
    val url: String,
    val sha256: String,
    val format: String,
    val javaHomeSubpath: String,
)

@Serializable
data class DevrigCoordinates(val schema: Int = 1, val devrig: DevrigEntry)

@Serializable
data class DevrigEntry(
    val url: String,
    val sha256: String,
    val size: Long = 0,
    val format: String = "zip",
)

private val json = Json { ignoreUnknownKeys = true }

private fun loadResource(name: String): String =
    InstallerGenerator::class.java.getResource(name)?.readText()
        ?: error("missing template resource on classpath: $name")

/** Marker object so the resource loader has a stable class to anchor `getResource` on. */
private object InstallerGenerator

internal fun validate(jdk: JdkCoordinates) {
    val missing = ALL_PLATFORMS.filterNot { it in jdk.platforms.keys }
    require(missing.isEmpty()) { "jdk-coordinates.json is missing platforms: $missing" }
    jdk.platforms.forEach { (key, e) ->
        require(key in ALL_PLATFORMS) { "jdk-coordinates.json has unknown platform key: $key" }
        require(e.sha256.matches(Regex("[0-9a-f]{64}"))) { "$key: sha256 must be 64 lowercase hex, got '${e.sha256}'" }
        require(e.format in setOf("zip", "tar.gz", "tar.xz")) { "$key: unknown format '${e.format}'" }
    }
}

/** Reject placeholder/malformed devrig coordinates so the generator never bakes a broken download URL. */
internal fun validateDevrig(devrig: DevrigCoordinates) {
    val e = devrig.devrig
    // Absolute http(s) URL, never the placeholder. http is allowed so the integration tests' nginx
    // side-car URLs pass; production records https release URLs.
    require((e.url.startsWith("https://") || e.url.startsWith("http://")) && "PLACEHOLDER" !in e.url) {
        "devrig-coordinates.json: url must be an absolute http(s) URL without PLACEHOLDER (run resolveDevrigCoordinates at release), got '${e.url}'"
    }
    require(e.sha256.matches(Regex("[0-9a-f]{64}"))) {
        "devrig-coordinates.json: sha256 must be 64 lowercase hex, got '${e.sha256}'"
    }
    require(e.format in setOf("zip", "tar.gz", "tar.xz")) { "devrig-coordinates.json: unknown format '${e.format}'" }
}

/** POSIX `case` arms for the install.sh baked table (single-quoted values; sha256/url carry no quotes). */
private fun renderShCase(jdk: JdkCoordinates, version: String): String = buildString {
    for (key in POSIX_PLATFORMS) {
        val j = jdk.platforms.getValue(key)
        appendLine("  $key)")
        appendLine("    devrig_binsub='devrig-$version/bin/devrig'")
        appendLine("    jdk_url='${j.url}'")
        appendLine("    jdk_sha256='${j.sha256}'")
        appendLine("    jdk_format='${j.format}'")
        appendLine("    jdk_javahome='${j.javaHomeSubpath}'")
        appendLine("    ;;")
    }
}.trimEnd('\n')

/** PowerShell hashtable literal for the install.ps1 baked table. */
private fun renderPsTable(jdk: JdkCoordinates, version: String): String = buildString {
    for (key in WINDOWS_PLATFORMS) {
        val j = jdk.platforms.getValue(key)
        appendLine("  '$key' = @{")
        appendLine("    DevrigBinSub = 'devrig-$version/bin/devrig.bat'")
        appendLine("    JdkUrl = '${j.url}'")
        appendLine("    JdkSha256 = '${j.sha256}'")
        appendLine("    JdkFormat = '${j.format}'")
        appendLine("    JdkJavaHome = '${j.javaHomeSubpath}'")
        appendLine("  }")
    }
}.trimEnd('\n')

private fun render(template: String, subs: Map<String, String>): String {
    var out = template
    for ((k, v) in subs) out = out.replace("@@$k@@", v)
    val leftover = Regex("@@[A-Z0-9_]+@@").find(out)
    require(leftover == null) { "unresolved placeholder ${leftover!!.value} in template" }
    return out
}

data class Args(val outDir: Path, val jdkFile: Path, val devrigFile: Path, val version: String)

private fun parseArgs(argv: Array<String>): Args {
    val m = mutableMapOf<String, String>()
    var i = 0
    while (i < argv.size) {
        val a = argv[i]
        require(a.startsWith("--")) { "unexpected argument '$a'" }
        val key = a.removePrefix("--")
        require(i + 1 < argv.size) { "missing value for --$key" }
        m[key] = argv[i + 1]; i += 2
    }
    fun req(k: String) = m[k] ?: error("required --$k not provided")
    return Args(Path.of(req("out-dir")), Path.of(req("jdk-coordinates")), Path.of(req("devrig-coordinates")), req("version"))
}

fun main(argv: Array<String>) {
    val args = parseArgs(argv)
    val jdk = json.decodeFromString<JdkCoordinates>(args.jdkFile.readText())
    val devrig = json.decodeFromString<DevrigCoordinates>(args.devrigFile.readText())
    validate(jdk)
    validateDevrig(devrig)

    // devrig artifact is universal across platforms today; binSubpath is derived per-OS from version.
    val devrigSubs = mapOf(
        "VERSION" to args.version,
        "DEVRIG_URL" to devrig.devrig.url,
        "DEVRIG_SHA256" to devrig.devrig.sha256,
        "DEVRIG_FORMAT" to devrig.devrig.format,
    )

    Files.createDirectories(args.outDir)

    val sh = render(
        loadResource("/templates/install.sh.tmpl"),
        devrigSubs + ("PLATFORM_CASE_SH" to renderShCase(jdk, args.version)),
    )
    val ps = render(
        loadResource("/templates/install.ps1.tmpl"),
        devrigSubs + ("PLATFORM_TABLE_PS" to renderPsTable(jdk, args.version)),
    )

    args.outDir.resolve("install.sh").writeText(if (sh.endsWith("\n")) sh else sh + "\n")
    args.outDir.resolve("install.ps1").writeText(if (ps.endsWith("\n")) ps else ps + "\n")
    System.err.println("[installer-gen] wrote install.sh + install.ps1 to ${args.outDir} (version ${args.version})")
}
