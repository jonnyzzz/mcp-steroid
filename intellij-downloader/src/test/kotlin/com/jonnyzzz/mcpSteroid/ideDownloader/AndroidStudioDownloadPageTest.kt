/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.ideDownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A download page can advertise several generations at once, in whatever order Google types them.
 * These tests keep the resolution a function of the page content only.
 */
class AndroidStudioDownloadPageTest {

    /** Two generations, the OLDER one first — the layout that used to resolve to the older build. */
    private val twoGenerationsOlderFirst = """
        <html><body>
          <a href="https://edgedl.me.gvt1.com/android/studio/install/2026.1.3.6/android-studio-quail3-rc2-windows.exe">win</a>
          <a href="https://edgedl.me.gvt1.com/android/studio/install/2026.1.3.6/android-studio-quail3-rc2-mac_arm.dmg">mac arm</a>
          <a href="https://edgedl.me.gvt1.com/android/studio/ide-zips/2026.1.3.6/android-studio-quail3-rc2-linux.tar.gz">linux</a>
          <a href="https://edgedl.me.gvt1.com/android/studio/install/2026.1.4.3/android-studio-quail4-canary3-windows.exe">win</a>
          <a href="https://edgedl.me.gvt1.com/android/studio/install/2026.1.4.3/android-studio-quail4-canary3-mac_arm.dmg">mac arm</a>
          <a href="https://edgedl.me.gvt1.com/android/studio/ide-zips/2026.1.4.3/android-studio-quail4-canary3-linux.tar.gz">linux</a>
        </body></html>
    """.trimIndent()

    @Test
    fun `parses every version on the page with its channel and links`() {
        val releases = parseAndroidStudioDownloadPage(twoGenerationsOlderFirst)

        assertEquals(setOf("2026.1.3.6", "2026.1.4.3"), releases.map { it.version }.toSet())
        val canary = releases.single { it.version == "2026.1.4.3" }
        assertEquals(AndroidStudioPageChannel.CANARY, canary.channel)
        assertEquals(261, canary.platformBaseline)
        assertEquals(3, canary.urls.size)
        assertEquals(AndroidStudioPageChannel.RC, releases.single { it.version == "2026.1.3.6" }.channel)
    }

    @Test
    fun `a stable page release carries no channel token`() {
        val releases = parseAndroidStudioDownloadPage(
            """<a href="https://edgedl.me.gvt1.com/android/studio/ide-zips/2025.3.4.7/android-studio-panda4-patch1-linux.tar.gz">x</a>"""
        )

        assertEquals(AndroidStudioPageChannel.RELEASE, releases.single().channel)
        assertEquals(253, releases.single().platformBaseline)
    }

    @Test
    fun `the newest release wins regardless of where it sits in the html`() {
        val download = resolveAndroidStudioPageDownload(
            html = twoGenerationsOlderFirst,
            pageUrl = "fixture://android-studio/preview",
            assetSuffix = "-linux.tar.gz",
            selector = null,
        )

        assertEquals("2026.1.4.3", download.release.version)
        assertTrue(download.url, download.url.endsWith("android-studio-quail4-canary3-linux.tar.gz"))
    }

    @Test
    fun `an exact version selector pins that release`() {
        val download = resolveAndroidStudioPageDownload(
            twoGenerationsOlderFirst, "fixture://android-studio/preview", "-linux.tar.gz", "2026.1.3.6",
        )

        assertEquals("2026.1.3.6", download.release.version)
    }

    @Test
    fun `a platform baseline selector resolves to the newest build of that baseline`() {
        val nextGeneration =
            """<a href="https://edgedl.me.gvt1.com/android/studio/ide-zips/2026.2.1.1/android-studio-rabbit1-canary1-linux.tar.gz">x</a>"""
        val html = twoGenerationsOlderFirst.replace("</body>", "$nextGeneration</body>")

        assertEquals(
            "2026.1.4.3",
            resolveAndroidStudioPageDownload(html, "fixture://p", "-linux.tar.gz", "261").release.version,
        )
        assertEquals(
            "2026.2.1.1",
            resolveAndroidStudioPageDownload(html, "fixture://p", "-linux.tar.gz", "262").release.version,
        )
        assertEquals(
            "2026.2.1.1",
            resolveAndroidStudioPageDownload(html, "fixture://p", "-linux.tar.gz", null).release.version,
        )
    }

    @Test
    fun `a generation that lacks the wanted artifact falls back to the next newest`() {
        val html = twoGenerationsOlderFirst.replace(
            """<a href="https://edgedl.me.gvt1.com/android/studio/ide-zips/2026.1.4.3/android-studio-quail4-canary3-linux.tar.gz">linux</a>""",
            "",
        )

        val download = resolveAndroidStudioPageDownload(html, "fixture://p", "-linux.tar.gz", null)

        assertEquals("2026.1.3.6", download.release.version)
    }

    @Test
    fun `an unknown selector fails naming the versions the page offers`() {
        val message = errorMessageOf {
            resolveAndroidStudioPageDownload(twoGenerationsOlderFirst, "fixture://p", "-linux.tar.gz", "2025.3")
        }

        assertTrue(message, message.contains("2026.1.4.3 (canary)"))
        assertTrue(message, message.contains("2026.1.3.6 (rc)"))
    }

    @Test
    fun `an empty page fails with the page format hint`() {
        val message = errorMessageOf {
            resolveAndroidStudioPageDownload("<html></html>", "fixture://p", "-linux.tar.gz", null)
        }

        assertTrue(message, message.contains("Page format may have changed"))
    }

    @Test
    fun `versions compare segment-wise and not as text`() {
        assertTrue(compareAndroidStudioVersions("2026.1.4.3", "2026.1.3.6") > 0)
        assertTrue(compareAndroidStudioVersions("2026.1.10.0", "2026.1.9.9") > 0)
        assertTrue(compareAndroidStudioVersions("2026.1", "2026.1.1") < 0)
        assertEquals(0, compareAndroidStudioVersions("2026.1.4.3", "2026.1.4.3"))
    }

    private inline fun errorMessageOf(block: () -> Unit): String {
        try { block() } catch (e: Throwable) { return e.message.orEmpty() }
        fail("Expected an exception; none thrown")
        @Suppress("UNREACHABLE_CODE") throw AssertionError()
    }
}
