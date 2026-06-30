/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Pure, Gradle-independent class-file version scanning used by [VerifyClassFileVersionTask]. Reads the
 * `.class` header bytes directly — no ASM / bytecode library — so the multi-release-JAR and header-parsing
 * rules can be unit-tested without building real artifacts.
 *
 * The constraint it enforces: every class an artifact ships must fit the *oldest* JBR the artifact targets,
 * or it throws `UnsupportedClassVersionError` at load time. (Android Studio on platform 261 bundles JBR 21
 * / class-file major 65; IntelliJ IDEA 2026.1 bundles JBR 25 / major 69.)
 *
 * The scan is **fully recursive over every bundled archive at any folder depth** — nested jars *and* nested
 * zips (e.g. the devrig dist bundles `ij-plugin.zip`, which bundles the kotlinc jars) — not just a `lib/`
 * directory. Every `.class` found anywhere is subject to the rule.
 */
object ClassFileVersionScanner {
    /** class-file major = Java feature + 44 (Java 1 = 45, so feature N => N + 44; 21 => 65, 25 => 69). */
    const val CLASS_MAJOR_OFFSET = 44

    private val MR_JAR_ENTRY = Regex("""^META-INF/versions/(\d+)/""")

    data class Violation(val location: String, val major: Int) {
        val javaFeature: Int get() = major - CLASS_MAJOR_OFFSET
    }

    /**
     * @param checked number of `.class` entries whose version was verified (loadable on the floor runtime).
     * @param violations classes whose major version exceeds the limit.
     * @param brokenClasses entries named `*.class` that do not parse as a class file (bad magic / truncated)
     *   — surfaced rather than silently skipped, so a corrupt or mis-shaded artifact is visible.
     */
    data class ScanResult(
        val checked: Int,
        val violations: List<Violation>,
        val brokenClasses: List<String>,
    )

    /** Big-endian u16 major at offset 6 of a `.class` (after the 0xCAFEBABE magic + 2-byte minor). Null if not a class. */
    fun classFileMajor(bytes: ByteArray): Int? {
        if (bytes.size < 8) return null
        if (bytes[0].toInt() and 0xFF != 0xCA || bytes[1].toInt() and 0xFF != 0xFE ||
            bytes[2].toInt() and 0xFF != 0xBA || bytes[3].toInt() and 0xFF != 0xBE
        ) {
            return null
        }
        return ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
    }

    /** The multi-release feature version N for an entry under `META-INF/versions/<N>/`, else null (a base entry). */
    fun multiReleaseFeature(entryName: String): Int? =
        MR_JAR_ENTRY.find(entryName)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Scans [archive] (a `.jar` or `.zip`) and every nested jar/zip it contains, recursively, against
     * [maxFeature]. The top-level archive is read via random access; nested archives are streamed.
     */
    fun scanArchive(archive: File, maxFeature: Int): ScanResult {
        require(archive.isFile) { "Archive to scan does not exist: $archive" }
        val acc = Accumulator()
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name
                when {
                    name.endsWith(".class") ->
                        zip.getInputStream(entry).use { checkClass("${archive.name}!$name", it.readBytes(), maxFeature, acc) }
                    isNestedArchive(name) ->
                        zip.getInputStream(entry).use { scanStream("${archive.name}!$name", it, maxFeature, acc) }
                }
            }
        }
        return acc.toResult()
    }

    /** Streams one nested archive, recursing into the jars/zips it in turn contains. */
    private fun scanStream(label: String, input: InputStream, maxFeature: Int, acc: Accumulator) {
        ZipInputStream(input).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                when {
                    name.endsWith(".class") -> checkClass("$label!$name", zin.readBytes(), maxFeature, acc)
                    isNestedArchive(name) -> {
                        val bytes = zin.readBytes()
                        scanStream("$label!$name", ByteArrayInputStream(bytes), maxFeature, acc)
                    }
                }
            }
        }
    }

    private fun isNestedArchive(name: String): Boolean = name.endsWith(".jar") || name.endsWith(".zip")

    private fun checkClass(location: String, bytes: ByteArray, maxFeature: Int, acc: Accumulator) {
        val entryName = location.substringAfterLast('!')
        // module-info is a module descriptor, not a classpath-loaded class — skip it.
        if (entryName == "module-info.class" || entryName.endsWith("/module-info.class")) return
        // Multi-release overlay for a feature newer than the floor: never loaded on the floor runtime, so a
        // library may legitimately ship it for newer JDKs — skip rather than flag.
        val mr = multiReleaseFeature(entryName)
        if (mr != null && mr > maxFeature) return

        val major = classFileMajor(bytes)
        if (major == null) {
            acc.broken += location
            return
        }
        acc.checked++
        if (major > maxFeature + CLASS_MAJOR_OFFSET) acc.violations += Violation(location, major)
    }

    private class Accumulator {
        var checked = 0
        val violations = mutableListOf<Violation>()
        val broken = mutableListOf<String>()
        fun toResult() = ScanResult(checked, violations, broken)
    }
}
