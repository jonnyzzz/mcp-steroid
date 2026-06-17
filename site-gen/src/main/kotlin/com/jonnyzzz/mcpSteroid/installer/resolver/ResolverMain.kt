/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer.resolver

import com.jonnyzzz.mcpSteroid.installer.DevrigCoordinateResolver
import com.jonnyzzz.mcpSteroid.installer.JdkCoordinateResolver
import com.jonnyzzz.mcpSteroid.installer.LocalJdkArtifact
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * CLI entrypoints for the coordinate RESOLVERS, invoked by Gradle (`:site-gen:generateJdkCoordinates`
 * / `:resolveDevrigCoordinates`). Gradle downloads the real artifacts to disk; these mains hand the local
 * files to [JdkCoordinateResolver] / [DevrigCoordinateResolver], which do the inspection + asserts.
 *
 *   jdk    --source <jdk25-pinned.json> --download-dir <dir> --out <out.json> [--url-base <sidecar>]
 *   devrig --dist-zip <devrig.zip> --url <public-url> --out <out.json>
 */
// encodeDefaults keeps schema/format/size in the emitted JSON (they equal their model defaults) so the
// regenerated coordinate files stay stable + readable instead of silently dropping those fields.
private val prettyJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

fun main(argv: Array<String>) {
    when (argv.firstOrNull()) {
        "jdk" -> resolveJdk(argv.drop(1))
        "devrig" -> resolveDevrig(argv.drop(1))
        else -> error("usage: <jdk|devrig> --flags…  (got: ${argv.joinToString(" ")})")
    }
}

private fun flags(args: List<String>): Map<String, String> {
    val m = LinkedHashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        require(a.startsWith("--")) { "unexpected argument '$a'" }
        require(i + 1 < args.size) { "missing value for $a" }
        m[a.removePrefix("--")] = args[i + 1]
        i += 2
    }
    return m
}

private fun resolveJdk(args: List<String>) {
    val m = flags(args)
    fun req(k: String) = m[k] ?: error("required --$k not provided")
    val source = Path.of(req("source"))
    val downloadDir = Path.of(req("download-dir"))
    val out = Path.of(req("out"))
    val urlBase = m["url-base"]?.trimEnd('/') // present in test mode → record side-car URLs

    resolveJdk(source, downloadDir, out, urlBase, HttpShaFetcher)
}

/**
 * Testable core: the only non-pure step is [fetcher] (HTTP to the vendor) — injected so tests stay
 * hermetic. The sha is FETCHED from the vendor (per [PinnedJdkEntry.sha256Url]) and cross-checked against
 * the real downloaded bytes; a mismatch means the download is corrupt OR the vendor shipped a newer build
 * — either way generation fails and the maintainer fixes `jdk25-pinned.json` (version + url) by hand.
 */
internal fun resolveJdk(source: Path, downloadDir: Path, out: Path, urlBase: String?, fetcher: ShaFetcher) {
    val src = prettyJson.decodeFromString<PinnedJdkCoordinates>(source.readText())
    val artifacts = src.platforms.entries.map { (key, e) ->
        val fileName = e.url.substringAfterLast('/')
        val vendorSha = parseVendorSha(e.vendor, fetcher.fetch(e.sha256Url))
        System.err.println("[resolver] $key: verifying against vendor sha ${vendorSha.take(12)}… (${e.sha256Url})")
        LocalJdkArtifact(
            platformKey = key,
            file = downloadDir.resolve(fileName),
            publicUrl = if (urlBase != null) "$urlBase/$fileName" else e.url,
            vendor = e.vendor,
            version = e.version,
            format = e.format,
            expectedSha256 = vendorSha,
        )
    }
    val resolved = JdkCoordinateResolver.resolve(artifacts)
    Files.createDirectories(out.parent)
    out.writeText(prettyJson.encodeToString(resolved) + "\n")
    System.err.println("[resolver] wrote ${resolved.platforms.size}-platform jdk-coordinates to $out")
}

private fun resolveDevrig(args: List<String>) {
    val m = flags(args)
    fun req(k: String) = m[k] ?: error("required --$k not provided")
    val distZip = Path.of(req("dist-zip"))
    val out = Path.of(req("out"))
    val resolved = DevrigCoordinateResolver.resolve(distZip, publicUrl = req("url"))
    Files.createDirectories(out.parent)
    out.writeText(prettyJson.encodeToString(resolved) + "\n")
    System.err.println("[resolver] wrote devrig-coordinates (sha ${resolved.devrig.sha256.take(12)}…) to $out")
}
