/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes a minimal bundled-plugin zip with entries:
 *  - `mcp-steroid/lib/plugin.txt` = [version]
 *  - `mcp-steroid/kotlinc/bin/kotlinc` (executable)
 *
 * Returns [zip] for chaining.
 */
internal fun bundledPluginZipFixture(zip: Path, version: String): Path {
    Files.createDirectories(zip.parent)
    ZipArchiveOutputStream(Files.newOutputStream(zip)).use { out ->
        out.addZipFile("mcp-steroid/lib/plugin.txt", version)
        out.addZipFile("mcp-steroid/kotlinc/bin/kotlinc", "#!/usr/bin/env sh\n", mode = 0b111_101_101)
    }
    return zip
}

private fun ZipArchiveOutputStream.addZipFile(name: String, text: String, mode: Int = 0b110_100_100) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    val entry = ZipArchiveEntry(name).apply {
        size = bytes.size.toLong()
        unixMode = mode
    }
    putArchiveEntry(entry)
    write(bytes)
    closeArchiveEntry()
}

/** A [BundledPluginResolver] that always returns the given [zip]. */
internal class FixedBundledPluginResolver(private val zip: Path) : BundledPluginResolver {
    override fun resolveBundledPluginZip(): Path = zip
}
