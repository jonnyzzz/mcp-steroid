/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.HostArchitecture
import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.androidStudioPlatformBaseline
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AndroidStudioCanaryReleasesTest {

    // Mirrors the Quail canary download links on developer.android.com/studio/preview.
    private val html = """
        <table>
          <a href="https://edgedl.me.gvt1.com/android/studio/install/2026.1.2.3/android-studio-quail2-canary3-windows.exe">win</a>
          <a href="https://edgedl.me.gvt1.com/android/studio/install/2026.1.2.3/android-studio-quail2-canary3-mac_arm.dmg">mac arm</a>
          <a href="https://edgedl.me.gvt1.com/android/studio/install/2026.1.2.3/android-studio-quail2-canary3-mac.dmg">mac intel</a>
          <a href="https://edgedl.me.gvt1.com/android/studio/ide-zips/2026.1.2.3/android-studio-quail2-canary3-linux.tar.gz">linux</a>
        </table>
    """.trimIndent()

    /** developer.android.com/studio/preview as recorded on 2026-08-03: canary 2026.1.4.3 listed first. */
    private val recordedPreviewPage = fixture("android-studio-preview.html")

    /** The same two generations with the blocks swapped: the OLDER RC comes first in the HTML. */
    private val recordedPreviewPageRcFirst = fixture("android-studio-preview-rc-first.html")

    @Test
    fun `platform baseline derives from the YYYY-N marketing version`() {
        assertEquals(261, androidStudioPlatformBaseline("2026.1.2.3"))
        assertEquals(253, androidStudioPlatformBaseline("2025.3.4.7"))
        assertNull(androidStudioPlatformBaseline("canary"))
    }

    @Test
    fun `resolves the quail canary for mac apple silicon as a 261 build`() {
        val archive = resolveAndroidStudioCanaryArchiveFromHtml(html, HostOs.MAC, HostArchitecture.ARM64)
        assertEquals("2026.1.2.3", archive.version)
        assertEquals("261", archive.build)
        // The preview page gives no full build, so the install reports 261.x — baseline comparison only.
        assertTrue(archive.buildIsBaseline)
        assertEquals(
            "https://edgedl.me.gvt1.com/android/studio/install/2026.1.2.3/android-studio-quail2-canary3-mac_arm.dmg",
            archive.url,
        )
    }

    @Test
    fun `selects the intel mac dmg and not the arm one`() {
        val archive = resolveAndroidStudioCanaryArchiveFromHtml(html, HostOs.MAC, HostArchitecture.X86_64)
        assertTrue(archive.url.endsWith("-mac.dmg"), archive.url)
    }

    @Test
    fun `selects the linux tarball from the ide-zips path`() {
        val archive = resolveAndroidStudioCanaryArchiveFromHtml(html, HostOs.LINUX, HostArchitecture.X86_64)
        assertTrue(archive.url.endsWith("-linux.tar.gz"), archive.url)
    }

    @Test
    fun `rejects unsupported linux arm64`() {
        assertThrows<IllegalArgumentException> {
            resolveAndroidStudioCanaryArchiveFromHtml(html, HostOs.LINUX, HostArchitecture.ARM64)
        }
    }

    @Test
    fun `recorded preview page resolves the newest of the two generations it lists`() {
        val archive = resolveAndroidStudioCanaryArchiveFromHtml(
            recordedPreviewPage, HostOs.MAC, HostArchitecture.ARM64,
        )
        assertEquals("2026.1.4.3", archive.version)
        assertTrue(archive.url.endsWith("android-studio-quail4-canary3-mac_arm.dmg"), archive.url)
    }

    @Test
    fun `the html order of the release blocks does not decide which build is picked`() {
        // Google is free to reorder the sections; the older RC first must NOT downgrade the resolution.
        assertTrue(
            recordedPreviewPageRcFirst.indexOf("/2026.1.3.6/") < recordedPreviewPageRcFirst.indexOf("/2026.1.4.3/"),
            "fixture must list the older RC first, otherwise it does not guard anything",
        )

        for (os in listOf(HostOs.MAC, HostOs.LINUX, HostOs.WINDOWS)) {
            val archive = resolveAndroidStudioCanaryArchiveFromHtml(
                recordedPreviewPageRcFirst, os, HostArchitecture.X86_64,
            )
            assertEquals("2026.1.4.3", archive.version, "resolved the first block in the HTML on $os")
            assertEquals("261", archive.build)
            assertTrue(archive.url.contains("quail4-canary3"), archive.url)
        }
    }

    @Test
    fun `an exact version request picks that release and not the newest`() {
        val archive = resolveAndroidStudioCanaryArchiveFromHtml(
            recordedPreviewPage, HostOs.MAC, HostArchitecture.ARM64, version = "2026.1.3.6",
        )
        assertEquals("2026.1.3.6", archive.version)
        assertTrue(archive.url.endsWith("android-studio-quail3-rc2-mac_arm.dmg"), archive.url)
    }

    @Test
    fun `a version prefix request picks the newest patch of that generation`() {
        val archive = resolveAndroidStudioCanaryArchiveFromHtml(
            recordedPreviewPage, HostOs.MAC, HostArchitecture.ARM64, version = "2026.1.3",
        )
        assertEquals("2026.1.3.6", archive.version)
    }

    @Test
    fun `a platform baseline request resolves to the newest build on that baseline`() {
        val archive = resolveAndroidStudioCanaryArchiveFromHtml(
            recordedPreviewPageRcFirst, HostOs.MAC, HostArchitecture.ARM64, version = "261",
        )
        assertEquals("2026.1.4.3", archive.version)
        assertEquals("261", archive.build)
        assertTrue(archive.buildIsBaseline)
    }

    @Test
    fun `a channel request picks the newest build of that channel`() {
        val rc = resolveAndroidStudioCanaryArchiveFromHtml(
            recordedPreviewPage, HostOs.MAC, HostArchitecture.ARM64, version = "rc",
        )
        assertEquals("2026.1.3.6", rc.version)

        val canary = resolveAndroidStudioCanaryArchiveFromHtml(
            recordedPreviewPage, HostOs.MAC, HostArchitecture.ARM64, version = "canary",
        )
        assertEquals("2026.1.4.3", canary.version)
    }

    @Test
    fun `an unavailable version fails listing what the page does offer`() {
        val error = assertThrows<IllegalStateException> {
            resolveAndroidStudioCanaryArchiveFromHtml(
                recordedPreviewPage, HostOs.MAC, HostArchitecture.ARM64, version = "2025.3.4.7",
            )
        }
        assertTrue(error.message!!.contains("2026.1.4.3 (canary)"), error.message!!)
        assertTrue(error.message!!.contains("2026.1.3.6 (rc)"), error.message!!)
    }

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")?.readText()
            ?: error("Missing test fixture: fixtures/$name")
}
