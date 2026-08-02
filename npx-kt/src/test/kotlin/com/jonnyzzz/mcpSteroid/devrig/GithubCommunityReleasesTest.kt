/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.HostArchitecture
import com.jonnyzzz.mcpSteroid.ideDownloader.HostOs
import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class GithubCommunityReleasesTest {

    private val payload = """
        [
          {"tag_name":"pycharm/2026.1.2","prerelease":false,"published_at":"2026-05-15T11:37:01Z","assets":[
            {"name":"pycharm-2026.1.2-aarch64.dmg","browser_download_url":"https://gh/pycharm-2026.1.2-aarch64.dmg"},
            {"name":"pycharm-2026.1.2.dmg","browser_download_url":"https://gh/pycharm-2026.1.2.dmg"}]},
          {"tag_name":"idea/2026.1.2","prerelease":false,"published_at":"2026-05-15T10:00:00Z","assets":[
            {"name":"idea-2026.1.2-aarch64.dmg","browser_download_url":"https://gh/idea-2026.1.2-aarch64.dmg"},
            {"name":"idea-2026.1.2.dmg","browser_download_url":"https://gh/idea-2026.1.2.dmg"},
            {"name":"idea-2026.1.2-aarch64.tar.gz","browser_download_url":"https://gh/idea-2026.1.2-aarch64.tar.gz"}]},
          {"tag_name":"idea/2025.3.5","prerelease":false,"published_at":"2026-04-01T00:00:00Z","assets":[
            {"name":"idea-2025.3.5-aarch64.dmg","browser_download_url":"https://gh/idea-2025.3.5-aarch64.dmg"}]},
          {"tag_name":"idea/2026.2","prerelease":true,"published_at":"2026-06-01T00:00:00Z","assets":[
            {"name":"idea-2026.2-aarch64.dmg","browser_download_url":"https://gh/idea-2026.2-aarch64.dmg"}]}
        ]
    """.trimIndent()

    @Test
    fun `community products are routed to github`() {
        assertTrue(isGithubCommunityProduct(IdeProduct.IntelliJIdeaCommunity))
        assertTrue(isGithubCommunityProduct(IdeProduct.PyCharmCommunity))
        assertFalse(isGithubCommunityProduct(IdeProduct.IntelliJIdea))
        assertFalse(isGithubCommunityProduct(IdeProduct.GoLand))
    }

    @Test
    fun `communityBuildBaseline maps marketing version to platform baseline`() {
        assertEquals(261, communityBuildBaseline("2026.1.2"))
        assertEquals(253, communityBuildBaseline("2025.3"))
        assertEquals(252, communityBuildBaseline("2025.2.6.2"))
    }

    @Test
    fun `resolves the newest non-prerelease idea community release for mac arm`() {
        val archive = resolveGithubCommunityArchiveFromReleasesJson(
            payload, IdeProduct.IntelliJIdeaCommunity, HostOs.MAC, HostArchitecture.ARM64,
        )
        // 2026.2 is a prerelease and 2025.3.5 is older, so 2026.1.2 wins.
        assertEquals("2026.1.2", archive.version)
        assertEquals("261", archive.build)
        // GitHub releases publish no full build number, so the resolution must say so — the artifact
        // reports 261.x and an exact comparison would reject every Community download.
        assertTrue(archive.buildIsBaseline)
        assertEquals("2026-05-15", archive.releaseDate)
        assertEquals("https://gh/idea-2026.1.2-aarch64.dmg", archive.url)
    }

    @Test
    fun `selects the x64 asset and not the aarch64 one for intel mac`() {
        val archive = resolveGithubCommunityArchiveFromReleasesJson(
            payload, IdeProduct.IntelliJIdeaCommunity, HostOs.MAC, HostArchitecture.X86_64,
        )
        assertEquals("https://gh/idea-2026.1.2.dmg", archive.url)
    }

    @Test
    fun `selects the linux arm tarball`() {
        val archive = resolveGithubCommunityArchiveFromReleasesJson(
            payload, IdeProduct.IntelliJIdeaCommunity, HostOs.LINUX, HostArchitecture.ARM64,
        )
        assertEquals("https://gh/idea-2026.1.2-aarch64.tar.gz", archive.url)
    }

    @Test
    fun `pycharm community resolves from its own tag prefix`() {
        val archive = resolveGithubCommunityArchiveFromReleasesJson(
            payload, IdeProduct.PyCharmCommunity, HostOs.MAC, HostArchitecture.ARM64,
        )
        assertEquals("2026.1.2", archive.version)
        assertEquals("https://gh/pycharm-2026.1.2-aarch64.dmg", archive.url)
    }

    @Test
    fun `pins an explicitly requested version`() {
        val archive = resolveGithubCommunityArchiveFromReleasesJson(
            payload, IdeProduct.IntelliJIdeaCommunity, HostOs.MAC, HostArchitecture.ARM64, version = "2025.3.5",
        )
        assertEquals("2025.3.5", archive.version)
        assertEquals("253", archive.build)
        assertEquals("https://gh/idea-2025.3.5-aarch64.dmg", archive.url)
    }
}
