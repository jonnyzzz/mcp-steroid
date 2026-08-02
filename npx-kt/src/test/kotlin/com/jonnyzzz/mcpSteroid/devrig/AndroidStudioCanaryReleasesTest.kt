/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.HostArchitecture
import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
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
}
