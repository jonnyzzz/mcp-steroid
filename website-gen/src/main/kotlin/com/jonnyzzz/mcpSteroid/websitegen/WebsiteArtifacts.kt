/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.websitegen

import com.jonnyzzz.mcpSteroid.installer.KtorHttpFetcher
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import java.io.StringWriter

/**
 * Generates the website's release-derived static artifacts — `version.json` + `updatePlugins.xml` (the
 * IntelliJ custom-plugin-repository XML) — in Kotlin, replacing the former `website/Makefile` +
 * `scripts/generate-update-plugins-xml.{sh,py}` + curl/jq/xmllint pipeline. One tested place for release
 * detection + plugin.xml parsing + XML rendering.
 *
 * The plugin version + `since-build` come from the ACTUAL published artifact (the release ZIP's
 * `ij-plugin-*.jar` `META-INF/plugin.xml`), never from a literal — so the XML can never claim a version
 * the bytes don't carry. Release detection is a plain GitHub REST API lookup (no token needed for the
 * public repo).
 */
private const val PLUGIN_ID = "com.jonnyzzz.mcp-steroid"
private val GITHUB_RELEASE_ZIP = Regex("""^https://github\.com/jonnyzzz/mcp-steroid/releases/download/[^/]+/.+\.zip$""")

/** Fetches a URL body as text (GitHub REST API). Injectable seam — the parsing/render logic is the tested unit. */
fun interface UrlTextFetcher {
    fun fetch(url: String): String
}

/** Fetches a URL body as bytes (the release ZIP). Injectable seam. */
fun interface UrlBytesFetcher {
    fun fetch(url: String): ByteArray
}

// HTTP is delegated to :installer-gen's shared KtorHttpFetcher (the repo's standard Ktor CIO stack —
// followRedirects for the GitHub release → S3 hop, long timeout for the plugin ZIP). The GitHub REST API
// returns v3 JSON by default, so no Accept header is needed.
val HttpTextFetcher = UrlTextFetcher { url -> KtorHttpFetcher.getBytes(url).decodeToString() }

val HttpBytesFetcher = UrlBytesFetcher { url -> KtorHttpFetcher.getBytes(url) }

@Serializable
private data class GhRelease(val assets: List<GhAsset> = emptyList())

@Serializable
private data class GhAsset(val name: String, val browser_download_url: String)

@Serializable
private data class VersionJson(@SerialName("version-base") val versionBase: String)

private val ghJson = Json { ignoreUnknownKeys = true }

/** Plugin coordinates read from the artifact's `META-INF/plugin.xml`. */
data class PluginCoordinates(val id: String, val version: String, val sinceBuild: String)

/**
 * Resolve the published plugin release ZIP URL for [version] via the GitHub REST API: the
 * `mcp-steroid-*.zip` asset on the `v<version>` tag (falling back to the bare `<version>` tag). The
 * `startsWith("mcp-steroid")` match is deliberate — the release also carries `devrig-*.zip`, and a bare
 * `.zip` match would wrongly pick it (see website/CLAUDE.md).
 */
fun resolveReleaseZipUrl(version: String, fetcher: UrlTextFetcher = HttpTextFetcher): String {
    for (tag in listOf("v$version", version)) {
        val body = try {
            fetcher.fetch("https://api.github.com/repos/jonnyzzz/mcp-steroid/releases/tags/$tag")
        } catch (e: Exception) {
            System.err.println("[website-gen] release lookup for tag '$tag' failed: ${e.message}")
            continue
        }
        val asset = ghJson.decodeFromString<GhRelease>(body).assets
            .firstOrNull { it.name.startsWith("mcp-steroid") && it.name.endsWith(".zip") }
        if (asset != null) return asset.browser_download_url
    }
    error("no mcp-steroid-*.zip asset found for release v$version (or $version) on jonnyzzz/mcp-steroid")
}

/** Download + open the release ZIP, find the `ij-plugin-*.jar`, and read id/version/since-build from plugin.xml. */
fun extractPluginCoordinates(zipBytes: ByteArray): PluginCoordinates {
    val jarBytes = readZipEntry(zipBytes) { it.contains("/lib/ij-plugin-") && it.endsWith(".jar") }
        ?: error("no */lib/ij-plugin-*.jar found in the release ZIP")
    val pluginXml = readZipEntry(jarBytes) { it == "META-INF/plugin.xml" }
        ?: error("no META-INF/plugin.xml in the ij-plugin jar")

    val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        .newDocumentBuilder().parse(ByteArrayInputStream(pluginXml))
    val root = doc.documentElement
    val id = root.childText("id") ?: error("plugin.xml has no <id>")
    val version = root.childText("version") ?: error("plugin.xml has no <version>")
    // Direct-child <idea-version> only (mirrors <id>/<version> and the Python's root.find), so a nested
    // idea-version inside e.g. a <depends> sub-config is never picked up.
    val sinceBuild = root.childElement("idea-version")
        ?.getAttribute("since-build")?.takeIf { it.isNotBlank() } ?: error("plugin.xml has no idea-version/@since-build")
    require(id == PLUGIN_ID) { "expected plugin id $PLUGIN_ID, got $id" }
    return PluginCoordinates(id, version, sinceBuild)
}

/** First DIRECT-child element with [tag]. */
private fun org.w3c.dom.Element.childElement(tag: String): org.w3c.dom.Element? {
    val nodes = getElementsByTagName(tag)
    return (0 until nodes.length).map { nodes.item(it) }
        .firstOrNull { it.parentNode === this } as? org.w3c.dom.Element
}

private fun org.w3c.dom.Element.childText(tag: String): String? =
    childElement(tag)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

/** First entry whose name matches [predicate], as bytes; null if none. */
private fun readZipEntry(zipBytes: ByteArray, predicate: (String) -> Boolean): ByteArray? {
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
        while (true) {
            val e = zip.nextEntry ?: break
            if (!e.isDirectory && predicate(e.name)) return zip.readBytes()
        }
    }
    return null
}

private const val DESCRIPTION_HTML =
    "<p><b>MCP Steroid</b> brings the full power of the IntelliJ Platform to AI agents through the Model " +
        "Context Protocol (MCP).</p>\n" +
        "<p>IntelliJ platform works for AI agents as great as for human developers.</p>\n" +
        "<ul>\n" +
        "<li><b>MCP Tools:</b> Control IntelliJ IDEA programmatically — execute code, take screenshots, debug, and more</li>\n" +
        "<li><b>MCP Resources:</b> Comprehensive guides covering LSP, IDE operations, debugger, tests, VCS, and more</li>\n" +
        "<li><b>Vision Capabilities:</b> AI agents can see your IDE with screenshots and OCR</li>\n" +
        "<li><b>Deep Integration:</b> Access PSI, inspections, refactorings, and full IntelliJ Platform API</li>\n" +
        "</ul>\n" +
        "<p>Compatible with all IntelliJ Platform-based IDEs: IntelliJ IDEA, PyCharm, WebStorm, GoLand, CLion, Rider, and more.</p>\n" +
        "<p>Requirements: IntelliJ IDEA 2025.3 or newer (build 253 or later).</p>\n" +
        "<p>Visit <a href=\"https://mcp-steroid.jonnyzzz.com\">mcp-steroid.jonnyzzz.com</a> for documentation and examples.</p>"

/** Convert release-notes markdown (`release/notes/<version>.md`) to the change-notes HTML fragment. */
fun markdownToHtml(notes: String?, version: String): String {
    if (notes.isNullOrBlank()) return "<p>Release notes not available for version $version.</p>"
    val parts = StringBuilder("<h2>What's New in v$version</h2>")
    var inSection = false
    for (line in notes.lines()) {
        if (!inSection) {
            if (line.startsWith("## ")) inSection = true else continue
        }
        when {
            line.startsWith("## ") -> parts.append("\n<h3>${line.removePrefix("## ")}</h3>")
            line.startsWith("- ") -> parts.append("\n<li>${bold(line.removePrefix("- "))}</li>")
            line.isNotBlank() -> parts.append("\n<p>$line</p>")
        }
    }
    parts.append(
        "\n<p>Full release notes: <a href=\"https://mcp-steroid.jonnyzzz.com/releases/$version/\">" +
            "mcp-steroid.jonnyzzz.com/releases/$version/</a></p>",
    )
    return parts.toString()
}

private fun bold(s: String) = Regex("""\*\*(.+?)\*\*""").replace(s) { "<b>${it.groupValues[1]}</b>" }

/** Render the IntelliJ custom-plugin-repository `updatePlugins.xml` (description + change-notes as CDATA). */
fun renderUpdatePluginsXml(coords: PluginCoordinates, zipUrl: String, changeNotesHtml: String): String {
    val doc: Document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
    val plugins = doc.createElement("plugins").also { doc.appendChild(it) }
    val plugin = doc.createElement("plugin").also {
        it.setAttribute("id", coords.id)
        it.setAttribute("url", zipUrl)
        it.setAttribute("version", coords.version)
        plugins.appendChild(it)
    }
    plugin.appendChild(doc.createElement("idea-version").also { it.setAttribute("since-build", coords.sinceBuild) })
    plugin.appendChild(doc.createElement("name").also { it.appendChild(doc.createTextNode("MCP Steroid")) })
    plugin.appendChild(doc.createElement("vendor").also { it.appendChild(doc.createTextNode("jonnyzzz.com")) })
    plugin.appendChild(doc.createElement("description").also { it.appendChild(doc.createCDATASection(DESCRIPTION_HTML)) })
    plugin.appendChild(doc.createElement("change-notes").also { it.appendChild(doc.createCDATASection(changeNotesHtml)) })

    val writer = StringWriter()
    TransformerFactory.newInstance().newTransformer().apply {
        // Omit the transformer's own <?xml …?> — it always adds standalone="no". We write our own decl
        // and a trailing blank line so the output matches the previously published file byte-for-byte.
        setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        setOutputProperty(OutputKeys.INDENT, "yes")
        setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
    }.transform(DOMSource(doc), StreamResult(writer))
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + writer.toString().trim('\n') + "\n\n"
}

private fun flags(argv: Array<String>): Map<String, String> {
    val m = LinkedHashMap<String, String>()
    var i = 0
    while (i < argv.size) {
        require(argv[i].startsWith("--")) { "unexpected argument '${argv[i]}'" }
        require(i + 1 < argv.size) { "missing value for ${argv[i]}" }
        m[argv[i].removePrefix("--")] = argv[i + 1]; i += 2
    }
    return m
}

/**
 * Generate `version.json` + `updatePlugins.xml` into `--out-dir` for `--version`. `--notes` overrides the
 * default release-notes path; `--zip-url` overrides release detection (CI may pass the exact asset URL).
 */
fun main(argv: Array<String>) {
    val m = flags(argv)
    fun req(k: String) = m[k] ?: error("required --$k not provided")
    val version = req("version")
    val outDir = Path.of(req("out-dir"))
    Files.createDirectories(outDir)

    // version.json — the only published field is version-base. Encoded via the serializer (not a hand-
    // interpolated string) so a version with a quote/backslash can't emit invalid JSON.
    Files.writeString(outDir.resolve("version.json"), ghJson.encodeToString(VersionJson.serializer(), VersionJson(version)) + "\n")

    val zipUrl = m["zip-url"] ?: resolveReleaseZipUrl(version)
    // github-release .zip AND it must carry the version (guards a wrong --zip-url override; the
    // version-in-plugin-version check below backstops via the real bytes).
    require(GITHUB_RELEASE_ZIP.matches(zipUrl) && version in zipUrl) {
        "release ZIP URL must be a github release .zip containing version '$version', got: $zipUrl"
    }
    val coords = extractPluginCoordinates(HttpBytesFetcher.fetch(zipUrl))
    require(version in coords.version) { "release version '$version' not in plugin version '${coords.version}'" }

    val notesFile = m["notes"]?.let { Path.of(it) }
    val notes = notesFile?.takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }
    val xml = renderUpdatePluginsXml(coords, zipUrl, markdownToHtml(notes, version))
    outDir.resolve("updatePlugins.xml").let { Files.writeString(it, xml) }

    System.err.println("[website-gen] wrote version.json + updatePlugins.xml (plugin ${coords.version}, since ${coords.sinceBuild}) to $outDir")
}
