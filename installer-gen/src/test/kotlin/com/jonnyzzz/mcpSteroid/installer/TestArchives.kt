/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.ByteArrayOutputStream

/** Build an in-memory `.tar.gz` whose entries are [files] (path -> contents). */
fun tarGz(files: Map<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    TarArchiveOutputStream(GzipCompressorOutputStream(out)).use { tar ->
        for ((name, bytes) in files) {
            tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
            tar.write(bytes)
            tar.closeArchiveEntry()
        }
    }
    return out.toByteArray()
}

/** Build an in-memory `.zip` whose entries are [files] (path -> contents). */
fun zip(files: Map<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipArchiveOutputStream(out).use { zip ->
        for ((name, bytes) in files) {
            zip.putArchiveEntry(ZipArchiveEntry(name))
            zip.write(bytes)
            zip.closeArchiveEntry()
        }
    }
    return out.toByteArray()
}
