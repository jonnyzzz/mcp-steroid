/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer

import com.jonnyzzz.mcpSteroid.installer.site.HttpBytesFetcher
import com.jonnyzzz.mcpSteroid.installer.site.HttpTextFetcher
import com.jonnyzzz.mcpSteroid.installer.site.UrlBytesFetcher
import com.jonnyzzz.mcpSteroid.installer.site.resolveLatestDevrigZipUrl
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * The installer tool: generates the self-contained `install.sh` (macOS + Linux) and `install.ps1`
 * (Windows). Gradle downloads the 5 pinned JDK 25 archives and passes their paths + metadata in (one
 * `--jdk` arg per platform, `|`-delimited); the tool INSPECTS each file ad-hoc — computes its sha256
 * (cross-checked against the pinned value, fail-fast) and INFERS `javaHomeSubpath` from the real archive
 * (fail-fast) — and bakes every URL/sha256/format/subpath into the scripts as a per-platform table.
 * No intermediate `jdk-coordinates.json`.
 *
 * devrig: a local zip override (`--devrig-zip` + `--devrig-url`, used by tests / a pre-built artifact),
 * a pinned `--devrig-version`, or — by default — the latest published GitHub release.
 */

/** The five supported platforms, keyed `<os>-<cpu>`. The script split is by OS. */
val POSIX_PLATFORMS = listOf("macos-arm64", "linux-arm64", "linux-x64")
val WINDOWS_PLATFORMS = listOf("windows-x64", "windows-arm64")
val ALL_PLATFORMS = POSIX_PLATFORMS + WINDOWS_PLATFORMS

/** In-memory coordinate model (no longer serialized — built ad-hoc from the downloaded files). */
data class JdkCoordinates(val platforms: Map<String, JdkEntry>)

data class JdkEntry(
    val vendor: String,
    val version: String,
    val url: String,
    val sha256: String,
    val format: String,
    /** Inferred from the real archive (never empty) — the dir inside the verbatim tree that is JAVA_HOME. */
    val javaHomeSubpath: String,
)

data class DevrigCoordinates(val devrig: DevrigEntry)

data class DevrigEntry(
    val url: String,
    val sha256: String,
    val size: Long = 0,
    val format: String = "zip",
)

private fun loadResource(name: String): String =
    InstallerGenerator::class.java.getResource(name)?.readText()
        ?: error("missing template resource on classpath: $name")

/** Marker object so the resource loader has a stable class to anchor `getResource` on. */
private object InstallerGenerator

internal fun validate(jdk: JdkCoordinates) {
    val missing = ALL_PLATFORMS.filterNot { it in jdk.platforms.keys }
    require(missing.isEmpty()) { "JDK coordinates are missing platforms: $missing" }
    jdk.platforms.forEach { (key, e) ->
        require(key in ALL_PLATFORMS) { "unknown JDK platform key: $key" }
        require(e.sha256.matches(Regex("[0-9a-f]{64}"))) { "$key: sha256 must be 64 lowercase hex, got '${e.sha256}'" }
        require(e.format in setOf("zip", "tar.gz", "tar.xz")) { "$key: unknown format '${e.format}'" }
        require(e.javaHomeSubpath.isNotBlank()) { "$key: javaHomeSubpath must be inferred (was blank)" }
    }
}

/** Reject placeholder/malformed devrig coordinates so the generator never bakes a broken download URL. */
internal fun validateDevrig(devrig: DevrigCoordinates) {
    val e = devrig.devrig
    // Absolute http(s) URL, never the placeholder. http is allowed so the integration tests' nginx
    // side-car URLs pass; production records https release URLs.
    require((e.url.startsWith("https://") || e.url.startsWith("http://")) && "PLACEHOLDER" !in e.url) {
        "devrig url must be an absolute http(s) URL without PLACEHOLDER, got '${e.url}'"
    }
    require(e.sha256.matches(Regex("[0-9a-f]{64}"))) { "devrig sha256 must be 64 lowercase hex, got '${e.sha256}'" }
    require(e.format in setOf("zip", "tar.gz", "tar.xz")) { "devrig: unknown format '${e.format}'" }
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

/** One `--jdk` value: `platform|vendor|version|format|sha256|url|file` (`|` never appears in a URL). */
fun parseJdkArg(value: String): LocalJdkArtifact {
    val f = value.split('|')
    require(f.size == 7) { "--jdk expects 7 '|'-delimited fields (platform|vendor|version|format|sha256|url|file), got: $value" }
    return LocalJdkArtifact(
        platformKey = f[0], vendor = f[1], version = f[2], format = f[3],
        expectedSha256 = f[4], publicUrl = f[5], file = Path.of(f[6]),
    )
}

private fun parseFlags(argv: Array<String>): Map<String, MutableList<String>> {
    val m = LinkedHashMap<String, MutableList<String>>()
    var i = 0
    while (i < argv.size) {
        require(argv[i].startsWith("--")) { "unexpected argument '${argv[i]}'" }
        require(i + 1 < argv.size) { "missing value for ${argv[i]}" }
        m.getOrPut(argv[i].removePrefix("--")) { mutableListOf() }.add(argv[i + 1]); i += 2
    }
    return m
}

/**
 * Resolve devrig coordinates. Local override (a pre-built / fixture zip): `--devrig-zip <file>` +
 * `--devrig-url <public url>`. Otherwise download a published release — pinned `--devrig-version <v>` or,
 * by default, the latest GitHub release — and compute sha/size from the bytes. [bytes] is injectable for tests.
 */
internal fun resolveDevrig(
    flags: Map<String, List<String>>,
    bytes: UrlBytesFetcher = HttpBytesFetcher,
): DevrigCoordinates {
    flags["devrig-zip"]?.firstOrNull()?.let { zip ->
        val url = flags["devrig-url"]?.firstOrNull() ?: error("--devrig-url is required with --devrig-zip")
        return DevrigCoordinateResolver.resolve(Path.of(zip), url)
    }
    val version = flags["devrig-version"]?.firstOrNull()
    val url = if (version != null) {
        "https://github.com/jonnyzzz/mcp-steroid/releases/download/v$version/devrig-$version.zip"
    } else {
        resolveLatestDevrigZipUrl(HttpTextFetcher)
    }
    val tmp = Files.createTempFile("devrig", ".zip")
    Files.write(tmp, bytes.fetch(url))
    return DevrigCoordinateResolver.resolve(tmp, url)
}

fun main(argv: Array<String>) {
    val flags = parseFlags(argv)
    fun req(k: String) = flags[k]?.firstOrNull() ?: error("required --$k not provided")
    val outDir = Path.of(req("out-dir"))
    val version = req("version")

    val artifacts = (flags["jdk"] ?: error("at least one --jdk is required")).map { parseJdkArg(it) }
    val jdk = JdkCoordinateResolver.resolve(artifacts) // computes sha (verify) + infers javaHomeSubpath
    validate(jdk)
    val devrig = resolveDevrig(flags)
    validateDevrig(devrig)

    val devrigSubs = mapOf(
        "VERSION" to version,
        "DEVRIG_URL" to devrig.devrig.url,
        "DEVRIG_SHA256" to devrig.devrig.sha256,
        "DEVRIG_FORMAT" to devrig.devrig.format,
    )

    Files.createDirectories(outDir)
    val sh = render(loadResource("/templates/install.sh.tmpl"), devrigSubs + ("PLATFORM_CASE_SH" to renderShCase(jdk, version)))
    val ps = render(loadResource("/templates/install.ps1.tmpl"), devrigSubs + ("PLATFORM_TABLE_PS" to renderPsTable(jdk, version)))
    outDir.resolve("install.sh").writeText(if (sh.endsWith("\n")) sh else sh + "\n")
    outDir.resolve("install.ps1").writeText(if (ps.endsWith("\n")) ps else ps + "\n")
    System.err.println("[site-gen] wrote install.sh + install.ps1 to $outDir (version $version, devrig ${devrig.devrig.url})")
}
