/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

private val prettyJson = Json { prettyPrint = true }

/**
 * Resolve the full JDK data model ([resolveAllJdks]) and write it as pretty JSON.
 *
 * Flags:
 *  - `--cache-dir <dir>` (required) — the on-disk download cache root. Gradle passes a path OUTSIDE the
 *    `build/` folder so JDK archives survive `clean` and are shared across runs.
 *  - `--out <file>` (required) — where to write the JDK model JSON.
 */
fun main(argv: Array<String>) {
    val args = parseFlags(argv)
    val cacheDir = Path.of(requireNotNull(args["--cache-dir"]) { "--cache-dir is required" })
    val out = Path.of(requireNotNull(args["--out"]) { "--out is required" })

    val cache = Cache.onDisk(cacheDir)
    val model = KtorHttpFetcher.use { resolveAllJdks(cache, it) }

    out.parent?.let { Files.createDirectories(it) }
    Files.writeString(out, prettyJson.encodeToString(model))
    System.err.println("[installer-gen] wrote ${model.jdks.size} JDK entries to $out (cache=$cacheDir)")
}

private fun parseFlags(argv: Array<String>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    var i = 0
    while (i < argv.size) {
        val key = argv[i]
        require(key.startsWith("--")) { "Unexpected argument '$key'; expected --flag value pairs" }
        require(i + 1 < argv.size) { "Missing value for $key" }
        map[key] = argv[i + 1]
        i += 2
    }
    return map
}
