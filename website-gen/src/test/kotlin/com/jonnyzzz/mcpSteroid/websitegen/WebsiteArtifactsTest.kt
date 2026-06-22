/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.websitegen

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Each behaviour is its own declared test (no parameterization); the network is an injected seam, so
 *  these run offline against in-memory fixtures. */
class WebsiteArtifactsTest {
    // ── release detection ──

    private fun releaseJson(vararg assetNames: String): String {
        val assets = assetNames.joinToString(",") {
            """{"name":"$it","browser_download_url":"https://github.com/jonnyzzz/mcp-steroid/releases/download/v1.0.0/$it"}"""
        }
        return """{"tag_name":"v1.0.0","assets":[$assets]}"""
    }

    @Test
    fun `resolveReleaseZipUrl picks the mcp-steroid zip, not the devrig zip`() {
        val url = resolveReleaseZipUrl("1.0.0") { tag ->
            require(tag.endsWith("/v1.0.0")) { "expected the v-tag first, got $tag" }
            releaseJson("devrig-1.0.0.zip", "mcp-steroid-1.0.0.zip")
        }
        assertTrue(url.endsWith("/mcp-steroid-1.0.0.zip"), url)
    }

    @Test
    fun `resolveReleaseZipUrl falls back from the v-tag to the bare tag`() {
        val url = resolveReleaseZipUrl("1.0.0") { tag ->
            if (tag.endsWith("/v1.0.0")) throw RuntimeException("404 no v-tag")
            releaseJson("mcp-steroid-1.0.0.zip")
        }
        assertTrue(url.endsWith("/mcp-steroid-1.0.0.zip"), url)
    }

    @Test
    fun `resolveReleaseZipUrl fails when no mcp-steroid asset exists`() {
        assertFailsWith<IllegalStateException> {
            resolveReleaseZipUrl("1.0.0") { releaseJson("devrig-1.0.0.zip", "notes.txt") }
        }
    }

    // ── plugin.xml extraction from the (nested) release ZIP ──

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val pluginXml = """
        <idea-plugin>
          <id>com.jonnyzzz.mcp-steroid</id>
          <version>0.101.0-abc1234</version>
          <idea-version since-build="253" until-build="999.*"/>
        </idea-plugin>
    """.trimIndent().toByteArray()

    private fun releaseZipWithPlugin(): ByteArray {
        val jar = zip(mapOf("META-INF/plugin.xml" to pluginXml))
        return zip(mapOf("mcp-steroid/lib/ij-plugin-0.101.0.jar" to jar, "mcp-steroid/readme.txt" to "x".toByteArray()))
    }

    @Test
    fun `extractPluginCoordinates reads id, version and since-build from the artifact`() {
        val coords = extractPluginCoordinates(releaseZipWithPlugin())
        assertEquals("com.jonnyzzz.mcp-steroid", coords.id)
        assertEquals("0.101.0-abc1234", coords.version)
        assertEquals("253", coords.sinceBuild)
    }

    @Test
    fun `extractPluginCoordinates fails when the ij-plugin jar is missing`() {
        val noJar = zip(mapOf("mcp-steroid/readme.txt" to "x".toByteArray()))
        assertFailsWith<IllegalStateException> { extractPluginCoordinates(noJar) }
    }

    @Test
    fun `extractPluginCoordinates fails when plugin xml is missing from the jar`() {
        val jar = zip(mapOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray()))
        val release = zip(mapOf("mcp-steroid/lib/ij-plugin-x.jar" to jar))
        assertFailsWith<IllegalStateException> { extractPluginCoordinates(release) }
    }

    @Test
    fun `extractPluginCoordinates rejects a foreign plugin id`() {
        val foreign = "<idea-plugin><id>com.example.other</id><version>1</version><idea-version since-build=\"253\"/></idea-plugin>"
        val jar = zip(mapOf("META-INF/plugin.xml" to foreign.toByteArray()))
        val release = zip(mapOf("mcp-steroid/lib/ij-plugin-x.jar" to jar))
        assertFailsWith<IllegalArgumentException> { extractPluginCoordinates(release) }
    }

    @Test
    fun `extractPluginCoordinates fails on a blank version`() {
        val blank = "<idea-plugin><id>com.jonnyzzz.mcp-steroid</id><version></version><idea-version since-build=\"253\"/></idea-plugin>"
        val jar = zip(mapOf("META-INF/plugin.xml" to blank.toByteArray()))
        val release = zip(mapOf("mcp-steroid/lib/ij-plugin-x.jar" to jar))
        assertFailsWith<IllegalStateException> { extractPluginCoordinates(release) }
    }

    // ── updatePlugins.xml render ──

    @Test
    fun `renderUpdatePluginsXml carries id, url, version, since-build and CDATA sections`() {
        val xml = renderUpdatePluginsXml(
            PluginCoordinates("com.jonnyzzz.mcp-steroid", "0.101.0-abc1234", "253"),
            "https://github.com/jonnyzzz/mcp-steroid/releases/download/v1.0.0/mcp-steroid-1.0.0.zip",
            "<h2>What's New in v1.0.0</h2>",
        )
        assertContains(xml, """id="com.jonnyzzz.mcp-steroid"""")
        assertContains(xml, """version="0.101.0-abc1234"""")
        assertContains(xml, "mcp-steroid-1.0.0.zip")
        assertContains(xml, """since-build="253"""")
        assertContains(xml, "<![CDATA[")
        assertContains(xml, "What's New in v1.0.0")
        assertContains(xml, "MCP Steroid")
    }

    // ── markdown → change-notes HTML ──

    @Test
    fun `markdownToHtml renders headings, bullets, bold and the footer link`() {
        val md = """
            Title line ignored before the first heading

            ## Highlights
            - A **bold** item
            - Plain item
        """.trimIndent()
        val html = markdownToHtml(md, "1.0.0")
        assertContains(html, "<h2>What's New in v1.0.0</h2>")
        assertContains(html, "<h3>Highlights</h3>")
        assertContains(html, "<li>A <b>bold</b> item</li>")
        assertContains(html, "<li>Plain item</li>")
        assertContains(html, "releases/1.0.0/")
    }

    @Test
    fun `markdownToHtml emits every heading as h3, including the first section heading`() {
        val md = "## First\n- a\n## Second\n- b"
        val html = markdownToHtml(md, "2.0.0")
        assertContains(html, "<h3>First</h3>")
        assertContains(html, "<h3>Second</h3>")
    }

    @Test
    fun `markdownToHtml falls back when notes are absent`() {
        assertContains(markdownToHtml(null, "1.0.0"), "not available for version 1.0.0")
    }
}
