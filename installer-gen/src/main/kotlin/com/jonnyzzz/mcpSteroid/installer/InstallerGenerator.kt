/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * The installer tool: generates the self-contained `install.sh` (macOS + Linux) and `install.ps1`
 * (Windows). The JDK coordinates come from [resolveAllJdks] — the data model already downloaded,
 * PGP-verified, and computed `url`/`sha256`/`javaHome` for every platform, so this tool just ADAPTS the
 * model into the per-platform table each script bakes in. No `--jdk` args, no local-file inspection.
 *
 * devrig coordinates: a local zip override (`--devrig-zip` + `--devrig-url`, used by tests / a pre-built
 * artifact), a pinned `--devrig-version`, or — by default — the latest published GitHub release.
 */

/** The five supported platforms, keyed `<os>-<cpu>`. The script split is by OS. */
val POSIX_PLATFORMS = listOf("macos-arm64", "linux-arm64", "linux-x64")
val WINDOWS_PLATFORMS = listOf("windows-x64", "windows-arm64")
val ALL_PLATFORMS = POSIX_PLATFORMS + WINDOWS_PLATFORMS

/** The per-platform values baked into the scripts (derived from a [JdkArtifact]). */
data class JdkScriptEntry(val url: String, val sha256: String, val format: String, val javaHome: String)

data class DevrigEntry(
    val url: String,
    val sha256: String,
    /**
     * The two devrig launcher subpaths INSIDE the dist zip — COMPUTED from the real archive and ASSERTED
     * to exist, never assumed from the version: the zip's top dir is `devrig-<version>-<hash>`, NOT
     * `devrig-<version>`, so a hardcoded `devrig-<version>/bin/devrig` is wrong (caught on eugene-x220).
     */
    val launcherPosix: String,
    val launcherWindows: String,
    val format: String = "zip",
)

private val ghJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class GhRelease(val assets: List<GhAsset> = emptyList())

@Serializable
private data class GhAsset(val name: String, val browser_download_url: String)

/** `<os>-<cpu>` key for a platform — matches the scripts' `uname` normalization (`AARCH64` -> `arm64`). */
internal fun JdkPlatform.scriptKey(): String {
    val osToken = when (os) { JdkOs.LINUX -> "linux"; JdkOs.MACOS -> "macos"; JdkOs.WINDOWS -> "windows" }
    val cpuToken = when (arch) { JdkArch.X64 -> "x64"; JdkArch.AARCH64 -> "arm64" }
    return "$osToken-$cpuToken"
}

/** Adapt the resolved [JdkModel] into the platform-keyed table the scripts bake in, and validate it. */
internal fun jdkScriptTable(model: JdkModel): Map<String, JdkScriptEntry> {
    val table = model.jdks.associate {
        it.platform.scriptKey() to JdkScriptEntry(it.url, it.sha256, it.archive.extension, it.javaHome)
    }
    validateScriptTable(table)
    return table
}

/** All 5 installer platforms present, no extras, and each entry well-formed (sha/format/javaHome). */
internal fun validateScriptTable(table: Map<String, JdkScriptEntry>) {
    val missing = ALL_PLATFORMS.filterNot { it in table.keys }
    require(missing.isEmpty()) { "JDK model is missing installer platforms: $missing (have ${table.keys})" }
    val extra = table.keys.filterNot { it in ALL_PLATFORMS }
    require(extra.isEmpty()) { "JDK model has platforms the installer does not support: $extra" }
    table.forEach { (key, e) ->
        require(e.sha256.matches(Regex("[0-9a-f]{64}"))) { "$key: sha256 must be 64 lowercase hex, got '${e.sha256}'" }
        require(e.format in setOf("zip", "tar.gz")) { "$key: unexpected archive format '${e.format}'" }
        // javaHome is archive-relative with NO leading OR trailing slash (installer-gen/CLAUDE.md). A
        // trailing slash would yield a double-slash in the install path; enforce the full contract.
        require(e.javaHome.isNotBlank() && !e.javaHome.startsWith("/") && !e.javaHome.endsWith("/")) {
            "$key: bad javaHome (must be non-blank, no leading/trailing slash): '${e.javaHome}'"
        }
        require(e.url.startsWith("https://") || e.url.startsWith("http://")) { "$key: url must be absolute http(s), got '${e.url}'" }
        requireShellSafe("$key url", e.url)
        requireShellSafe("$key javaHome", e.javaHome)
    }
}

// Vendor-controlled values (url, javaHome) are baked VERBATIM into single-quoted shell / PowerShell strings
// in a script users run via `curl … | sh` / `irm … | iex`. A single quote (or other quote/expansion
// metachar, backslash, control char) would break out of that string. Today's Corretto/Azul values are
// clean, but reject them at generation time as defense-in-depth — a vendor URL change or a crafted archive
// entry name can't silently produce a broken/injectable installer.
private val SHELL_UNSAFE_CHARS = charArrayOf('\'', '"', '`', '$', '\\')

private fun requireShellSafe(label: String, value: String) =
    require(value.none { it in SHELL_UNSAFE_CHARS || it.isISOControl() }) {
        "$label contains a shell-unsafe character: '$value'"
    }

/** Reject placeholder/malformed devrig coordinates so the generator never bakes a broken download URL. */
internal fun validateDevrig(e: DevrigEntry) {
    // Absolute http(s) URL, never the placeholder. http is allowed so the integration tests' nginx
    // side-car URLs pass; production records https release URLs.
    require((e.url.startsWith("https://") || e.url.startsWith("http://")) && "PLACEHOLDER" !in e.url) {
        "devrig url must be an absolute http(s) URL without PLACEHOLDER, got '${e.url}'"
    }
    require(e.sha256.matches(Regex("[0-9a-f]{64}"))) { "devrig sha256 must be 64 lowercase hex, got '${e.sha256}'" }
    require(e.format == "zip") { "devrig: unexpected format '${e.format}'" }
    requireShellSafe("devrig url", e.url)
    // The launcher subpaths are derived from devrig-zip ENTRY NAMES (attacker-controlled if the zip is
    // crafted) and baked into the single-quoted DEVRIG_BINSUB assignment in both scripts — validate them
    // the same way as the vendor URLs.
    requireShellSafe("devrig launcherPosix", e.launcherPosix)
    requireShellSafe("devrig launcherWindows", e.launcherWindows)
}

// The devrig launcher subpath is UNIVERSAL (one dist zip for all platforms), so it is baked once as
// DEVRIG_BINSUB (computed + asserted in resolveDevrig), not per-platform. The per-platform table carries
// only the JDK coordinates.

/** POSIX `case` arms for the install.sh baked JDK table (single-quoted values; sha256/url carry no quotes). */
private fun renderShCase(table: Map<String, JdkScriptEntry>): String = buildString {
    for (key in POSIX_PLATFORMS) {
        val j = table.getValue(key)
        appendLine("  $key)")
        appendLine("    jdk_url='${j.url}'")
        appendLine("    jdk_sha256='${j.sha256}'")
        appendLine("    jdk_format='${j.format}'")
        appendLine("    jdk_javahome='${j.javaHome}'")
        appendLine("    ;;")
    }
}.trimEnd('\n')

/** PowerShell hashtable literal for the install.ps1 baked JDK table. */
private fun renderPsTable(table: Map<String, JdkScriptEntry>): String = buildString {
    for (key in WINDOWS_PLATFORMS) {
        val j = table.getValue(key)
        appendLine("  '$key' = @{")
        appendLine("    JdkUrl = '${j.url}'")
        appendLine("    JdkSha256 = '${j.sha256}'")
        appendLine("    JdkFormat = '${j.format}'")
        appendLine("    JdkJavaHome = '${j.javaHome}'")
        appendLine("  }")
    }
}.trimEnd('\n')

private fun loadResource(name: String): String =
    InstallerGeneratorAnchor::class.java.getResource(name)?.readText()
        ?: error("missing template resource on classpath: $name")

/** Marker class so the resource loader has a stable anchor for `getResource`. */
private object InstallerGeneratorAnchor

private fun render(template: String, subs: Map<String, String>): String {
    var out = template
    for ((k, v) in subs) out = out.replace("@@$k@@", v)
    val leftover = Regex("@@[A-Z0-9_]+@@").find(out)
    require(leftover == null) { "unresolved placeholder ${leftover!!.value} in template" }
    return out
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

private fun resolveLatestDevrigZipUrl(http: HttpFetcher): String {
    val body = http.getBytes("https://api.github.com/repos/jonnyzzz/mcp-steroid/releases/latest").decodeToString()
    return ghJson.decodeFromString<GhRelease>(body).assets
        .firstOrNull { it.name.startsWith("devrig") && it.name.endsWith(".zip") }
        ?.browser_download_url
        ?: error("no devrig-*.zip asset on the latest jonnyzzz/mcp-steroid release")
}

/**
 * Resolve devrig coordinates. Local override (a pre-built / fixture zip): `--devrig-zip <file>` +
 * `--devrig-url <public url>`. Otherwise download a published release — pinned `--devrig-version <v>` or,
 * by default, the latest GitHub release — and compute sha from the bytes.
 */
internal fun resolveDevrig(flags: Map<String, List<String>>, http: HttpFetcher): DevrigEntry {
    val (url, bytes) = flags["devrig-zip"]?.firstOrNull()?.let { zip ->
        val u = flags["devrig-url"]?.firstOrNull() ?: error("--devrig-url is required with --devrig-zip")
        val file = Path.of(zip)
        require(Files.isRegularFile(file)) { "missing devrig package: $file" }
        u to Files.readAllBytes(file)
    } ?: run {
        val version = flags["devrig-version"]?.firstOrNull()
        val u = if (version != null) {
            // Guard the version token before it shapes a GitHub URL: a `..`/`@`/`%2F` could repoint the
            // download to a different release/path. The bytes are still sha-verified, but against a hash
            // the generator computes from THIS download — so a repointed URL would silently embed the
            // wrong artifact. Restrict to release-tag-safe characters.
            require(version.matches(Regex("[A-Za-z0-9._+-]+"))) { "--devrig-version has unsafe characters: '$version'" }
            "https://github.com/jonnyzzz/mcp-steroid/releases/download/v$version/devrig-$version.zip"
        } else {
            resolveLatestDevrigZipUrl(http)
        }
        u to http.getBytes(u)
    }
    val (posix, win) = devrigLaunchers(bytes)
    return DevrigEntry(url = url, sha256 = sha256Hex(bytes), launcherPosix = posix, launcherWindows = win)
}

/**
 * The two devrig launcher subpaths inside the dist zip — found by scanning the real archive entries and
 * ASSERTED to exist. Returns (POSIX `…/bin/devrig`, Windows `…/bin/devrig.bat`). Fixes the brittle
 * `devrig-<version>` assumption: the actual top dir carries the build hash (`devrig-<version>-<hash>`).
 */
private fun devrigLaunchers(zipBytes: ByteArray): Pair<String, String> {
    val names = archiveEntryNames(zipBytes, ArchiveType.ZIP).map { it.trimEnd('/') }
    fun find(leaf: String) = names.firstOrNull { it == "bin/$leaf" || it.endsWith("/bin/$leaf") }
        ?: error("devrig zip has no */bin/$leaf launcher (entries=${names.take(8)})")
    return find("devrig") to find("devrig.bat")
}

fun main(argv: Array<String>) {
    val flags = parseFlags(argv)
    fun req(k: String) = flags[k]?.firstOrNull() ?: error("required --$k not provided")
    val outDir = Path.of(req("out-dir"))
    val version = req("version")
    val cacheDir = Path.of(req("cache-dir"))

    KtorHttpFetcher.use { http ->
        val model = resolveAllJdks(Cache.onDisk(cacheDir), http)
        val devrig = resolveDevrig(flags, http)
        writeInstallerScripts(outDir, jdkScriptTable(model), devrig, version)
        System.err.println("[installer-gen] wrote install.sh + install.ps1 to $outDir (version $version, devrig ${devrig.url})")
    }
}

/** The rendered installer scripts. */
data class InstallerScripts(val sh: String, val ps: String)

/**
 * Render install.sh + install.ps1 from the per-platform [table] + [devrig] coordinates. Pure: no network,
 * no disk — the seam the integration tests drive with a synthetic model + nginx-side-car URLs (so they
 * never download a real 200 MB JDK). Validates inputs (all 5 platforms present, sha/format/javaHome,
 * devrig URL) before rendering.
 */
internal fun renderInstallerScripts(table: Map<String, JdkScriptEntry>, devrig: DevrigEntry, version: String): InstallerScripts {
    validateScriptTable(table)
    validateDevrig(devrig)
    // version (from --version) is baked into both scripts and used UNQUOTED in install.sh path
    // construction — reject shell metacharacters here too (it is not vendor-sourced, but the same
    // generation-time defense-in-depth applies).
    requireShellSafe("version", version)
    val common = mapOf(
        "VERSION" to version,
        "DEVRIG_URL" to devrig.url,
        "DEVRIG_SHA256" to devrig.sha256,
        "DEVRIG_FORMAT" to devrig.format,
    )
    // DEVRIG_BINSUB differs per OS (POSIX `…/bin/devrig` vs Windows `…/bin/devrig.bat`), so it is injected
    // per-template from the computed+asserted launcher subpaths.
    val sh = render(
        loadResource("/templates/install.sh.tmpl"),
        common + ("DEVRIG_BINSUB" to devrig.launcherPosix) + ("PLATFORM_CASE_SH" to renderShCase(table)),
    )
    val ps = render(
        loadResource("/templates/install.ps1.tmpl"),
        common + ("DEVRIG_BINSUB" to devrig.launcherWindows) + ("PLATFORM_TABLE_PS" to renderPsTable(table)),
    )
    // Windows PowerShell 5.1 reads a BOM-less .ps1 as the ANSI codepage, NOT UTF-8 — a non-ASCII byte
    // (e.g. an em dash in a string) is mojibake'd into characters that break parsing (caught on
    // eugene-x220). Keep install.ps1 strictly ASCII; fail generation if a template/baked value sneaks in.
    val nonAscii = ps.indexOfFirst { it.code >= 128 }
    require(nonAscii < 0) {
        "install.ps1 must be ASCII-only (PowerShell 5.1 misreads UTF-8); first non-ASCII at index $nonAscii: " +
            "'${ps[nonAscii]}' (U+${ps[nonAscii].code.toString(16).padStart(4, '0').uppercase()})"
    }
    return InstallerScripts(sh, ps)
}

/** Render the scripts and write them (newline-terminated) into [outDir]. Public so the integration test
 * can drive it with a synthetic table (nginx-served fixtures) instead of resolving real JDKs. */
fun writeInstallerScripts(outDir: Path, table: Map<String, JdkScriptEntry>, devrig: DevrigEntry, version: String) {
    val scripts = renderInstallerScripts(table, devrig, version)
    Files.createDirectories(outDir)
    outDir.resolve("install.sh").writeText(if (scripts.sh.endsWith("\n")) scripts.sh else scripts.sh + "\n")
    outDir.resolve("install.ps1").writeText(if (scripts.ps.endsWith("\n")) scripts.ps else scripts.ps + "\n")
}
