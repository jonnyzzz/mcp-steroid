/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClassFileVersionScannerTest {
    // Java 21 => class-file major 65, Java 25 => major 69, Java 17 => major 61.
    private val major21 = 21 + ClassFileVersionScanner.CLASS_MAJOR_OFFSET
    private val major25 = 25 + ClassFileVersionScanner.CLASS_MAJOR_OFFSET
    private val major17 = 17 + ClassFileVersionScanner.CLASS_MAJOR_OFFSET

    /** Minimal valid `.class`: 0xCAFEBABE magic, 2-byte minor (0), 2-byte big-endian [major]. */
    private fun classBytes(major: Int): ByteArray = byteArrayOf(
        0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
        0, 0,
        ((major ushr 8) and 0xFF).toByte(), (major and 0xFF).toByte(),
    )

    private fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun writeArchive(name: String, entries: Map<String, ByteArray>): File {
        val dir = Files.createTempDirectory("cfv").toFile()
        val file = File(dir, name)
        file.writeBytes(zipBytes(entries))
        return file
    }

    @Test
    fun classFileMajorReadsHeaderAndRejectsNonClasses() {
        assertEquals(major25, ClassFileVersionScanner.classFileMajor(classBytes(major25)))
        assertEquals(major21, ClassFileVersionScanner.classFileMajor(classBytes(major21)))
        assertNull(ClassFileVersionScanner.classFileMajor(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)), "wrong magic")
        assertNull(ClassFileVersionScanner.classFileMajor(byteArrayOf(0xCA.toByte(), 0xFE.toByte())), "too short")
    }

    @Test
    fun multiReleaseFeatureParsesVersionDirectory() {
        assertEquals(21, ClassFileVersionScanner.multiReleaseFeature("META-INF/versions/21/com/foo/Bar.class"))
        assertEquals(9, ClassFileVersionScanner.multiReleaseFeature("META-INF/versions/9/x/Y.class"))
        assertNull(ClassFileVersionScanner.multiReleaseFeature("com/foo/Bar.class"))
    }

    @Test
    fun scanArchiveFlagsOnlyClassesLoadedOnTheFloorRuntimeAndReportsBroken() {
        val jar = writeArchive(
            "sample.jar",
            mapOf(
                "com/ok/Below.class" to classBytes(major21),       // 65 <= 65  -> OK
                "com/bad/Above.class" to classBytes(major25),      // 69 > 65   -> VIOLATION
                "module-info.class" to classBytes(major25),        // module descriptor -> skipped
                "META-INF/versions/25/com/mr/NewerOverlay.class" to classBytes(major25), // N>floor -> skipped
                "META-INF/versions/17/com/mr/OlderBad.class" to classBytes(major25),     // N<=floor, 69>65 -> VIOLATION
                "META-INF/versions/17/com/mr/OlderOk.class" to classBytes(major17),      // 61 <= 65 -> OK
                "com/x/Corrupt.class" to byteArrayOf(1, 2, 3, 4),  // named .class but not a class -> BROKEN
                "com/ok/resource.txt" to "hello".toByteArray(),    // not a class -> ignored
            ),
        )

        val result = ClassFileVersionScanner.scanArchive(jar, maxFeature = 21)

        // checked = 2 base + 2 applicable overlays (versions/17). NOT module-info, versions/25, broken, .txt
        assertEquals(4, result.checked)
        assertEquals(
            listOf(
                "sample.jar!META-INF/versions/17/com/mr/OlderBad.class",
                "sample.jar!com/bad/Above.class",
            ),
            result.violations.map { it.location }.sorted(),
        )
        assertTrue(result.violations.all { it.major == major25 && it.javaFeature == 25 })
        assertEquals(listOf("sample.jar!com/x/Corrupt.class"), result.brokenClasses)
    }

    @Test
    fun scanArchiveRecursesIntoNestedJarsAndZipsAtAnyFolder() {
        // A nested jar OUTSIDE any lib/ folder (kotlinc-style) must still be scanned now.
        val toolJar = zipBytes(mapOf("com/tool/Compiler.class" to classBytes(major25)))
        // A nested ZIP (devrig dist bundles ij-plugin.zip) whose own nested jar must be reached recursively.
        val innerPluginJar = zipBytes(mapOf("com/plugin/Service.class" to classBytes(major25)))
        val nestedPluginZip = zipBytes(mapOf("mcp-steroid/lib/ij-plugin.jar" to innerPluginJar))

        val dist = writeArchive(
            "dist.zip",
            mapOf(
                "dist/kotlinc/lib/tool.jar" to toolJar,   // not under a top-level lib/ — still scanned
                "dist/ij-plugin.zip" to nestedPluginZip,  // nested zip — recursed
                "dist/bin/7zz" to byteArrayOf(0, 1, 2),   // binary — ignored
            ),
        )

        val result = ClassFileVersionScanner.scanArchive(dist, maxFeature = 21)

        assertEquals(2, result.checked)
        assertEquals(
            listOf(
                "dist.zip!dist/ij-plugin.zip!mcp-steroid/lib/ij-plugin.jar!com/plugin/Service.class",
                "dist.zip!dist/kotlinc/lib/tool.jar!com/tool/Compiler.class",
            ),
            result.violations.map { it.location }.sorted(),
        )
    }
}
