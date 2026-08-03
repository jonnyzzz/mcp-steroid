/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.ideDownloader.IdeProduct
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class BackendCommandDownloadListTest {

    @Test
    fun `text lists downloadable IDEs with aligned columns license tiers and lookup errors`() = runBlocking {
        val rows = collectAvailableBackendDownloads(
            versionResolver = FakeVersionResolver(
                versions = mapOf(
                    "idea-community" to Result.success(AvailableBackendRelease("2025.3.3", "2025-12-08")),
                    "pycharm-community" to Result.success(AvailableBackendRelease("2025.3.3", "2025-12-08")),
                    "android-studio" to Result.failure(IllegalStateException("offline products API")),
                    "goland" to Result.success(AvailableBackendRelease("2025.3.0", "2025-12-09")),
                    "webstorm" to Result.success(AvailableBackendRelease("2025.3.0", "2025-12-09")),
                    "rider" to Result.success(AvailableBackendRelease("2025.3.0", "2025-12-09")),
                    "clion" to Result.success(AvailableBackendRelease("2025.3.0", "2025-12-09")),
                ),
            ),
        )
        val text = renderText(rows)

        assertTrue(text.startsWith("Available IDEs (defaults to latest stable):"), text)
        assertTrue(text.contains("Available IDEs (defaults to latest stable):"), text)
        assertFalse(text.contains("free-for-non-commercial"), text)
        assertTrue(text.contains("  *  Includes the Community feature set; free in Community mode, a paid license unlocks the full edition."), text)
        assertTrue(text.contains("  ** Free for non-commercial use; JetBrains license required for commercial use."), text)
        assertTrue(text.contains("(version lookup failed: offline products API)"), text)
        assertFalse(text.contains("2025-12-08"), text)
        assertFalse(text.contains("2025-12-09"), text)
        assertTrue(text.contains("Run:  devrig backend download <id> [--version <v>]"), text)

        val lines = productLines(text)
        val ideaCommunity = lines.single { it.contains("idea-community") }
        val pyCharmCommunity = lines.single { it.contains("pycharm-community") }
        val androidStudio = lines.single { it.contains("android-studio") }
        val goLand = lines.single { it.contains("goland") }
        val ideaUltimate = lines.single { it.contains("idea-ultimate") && it.contains("IntelliJ IDEA Ultimate") }
        val pyCharmPro = lines.single { it.contains("pycharm-pro") && it.contains("PyCharm Professional") }

        assertFalse(ideaCommunity.trimEnd().endsWith("*"), ideaCommunity)
        assertTrue(goLand.trimEnd().endsWith("**"), goLand)
        assertTrue(ideaUltimate.trimEnd().endsWith("*"), ideaUltimate)
        assertTrue(pyCharmPro.trimEnd().endsWith("*"), pyCharmPro)

        assertTrue(lines.indexOf(ideaCommunity) < lines.indexOf(goLand), text)
        assertTrue(lines.indexOf(pyCharmCommunity) < lines.indexOf(goLand), text)
        assertTrue(lines.indexOf(androidStudio) < lines.indexOf(goLand), text)
        assertTrue(lines.indexOf(goLand) < lines.indexOf(ideaUltimate), text)
        assertTrue(lines.indexOf(ideaUltimate) < lines.indexOf(pyCharmPro), text)

        val versionColumns = mapOf(
            ideaCommunity to "2025.3.3",
            pyCharmCommunity to "2025.3.3",
            androidStudio to "(version lookup failed: offline products API)",
            goLand to "2025.3.0",
        ).map { (line, version) -> line.indexOf(version) }.toSet()
        assertEquals(1, versionColumns.size, "version column must align in:\n$text")

    }

    @Test
    fun `json exposes available schema with license annotations and failed lookups`() = runBlocking {
        val rows = collectAvailableBackendDownloads(
            versionResolver = FakeVersionResolver(
                versions = mapOf(
                    "android-studio" to Result.failure(IllegalStateException("network down")),
                ),
            ),
        )
        val root = renderJson(rows)

        assertEquals(setOf("tool", "available"), root.keys)
        assertEquals("devrig", root["tool"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val available = root["available"]!!.jsonArray.map { it.jsonObject }
        // Grouped by license tier (free, then free-for-non-commercial, then paid), catalog order
        // within each group.
        assertEquals(
            listOf(
                "idea-community",
                "pycharm-community",
                "mps",
                "android-studio",
                "goland",
                "webstorm",
                "rider",
                "clion",
                "rustrover",
                "idea-ultimate",
                "pycharm-pro",
                "phpstorm",
                "rubymine",
                "datagrip",
            ),
            available.map { it["id"]!!.jsonPrimitive.content },
        )

        val ideaCommunity = available.single { it["id"]!!.jsonPrimitive.content == "idea-community" }
        assertEquals(setOf("id", "code", "displayName", "licenseTier", "licenseSymbol", "licenseNote", "version", "build", "releaseDate"), ideaCommunity.keys)
        assertEquals("IIC", ideaCommunity["code"]!!.jsonPrimitive.content)
        assertEquals("IntelliJ IDEA Community", ideaCommunity["displayName"]!!.jsonPrimitive.content)
        assertEquals("free", ideaCommunity["licenseTier"]!!.jsonPrimitive.content)
        assertEquals("", ideaCommunity["licenseSymbol"]!!.jsonPrimitive.content)
        assertEquals("", ideaCommunity["licenseNote"]!!.jsonPrimitive.content)
        assertEquals("2025.3.test", ideaCommunity["version"]!!.jsonPrimitive.content)
        assertEquals("2025-12-08", ideaCommunity["releaseDate"]!!.jsonPrimitive.content)

        val rider = available.single { it["id"]!!.jsonPrimitive.content == "rider" }
        assertEquals(setOf("id", "code", "displayName", "licenseTier", "licenseSymbol", "licenseNote", "version", "build", "releaseDate"), rider.keys)
        assertEquals("free-for-non-commercial", rider["licenseTier"]!!.jsonPrimitive.content)
        assertEquals("**", rider["licenseSymbol"]!!.jsonPrimitive.content)
        assertEquals("Free for non-commercial use; JetBrains license required for commercial use.", rider["licenseNote"]!!.jsonPrimitive.content)
        assertEquals("2025.3.test", rider["version"]!!.jsonPrimitive.content)

        val android = available.single { it["id"]!!.jsonPrimitive.content == "android-studio" }
        assertEquals(setOf("id", "code", "displayName", "licenseTier", "licenseSymbol", "licenseNote", "version", "build", "releaseDate", "versionLookupError"), android.keys)
        assertNull(android["version"]!!.jsonPrimitive.contentOrNull)
        assertNull(android["releaseDate"]!!.jsonPrimitive.contentOrNull)
        assertEquals("network down", android["versionLookupError"]!!.jsonPrimitive.content)

        val paid = available.single { it["id"]!!.jsonPrimitive.content == "idea-ultimate" }
        assertEquals(setOf("id", "code", "displayName", "licenseTier", "licenseSymbol", "licenseNote", "version", "build", "releaseDate"), paid.keys)
        assertEquals("paid", paid["licenseTier"]!!.jsonPrimitive.content)
        assertEquals("*", paid["licenseSymbol"]!!.jsonPrimitive.content)
        assertEquals("Includes the Community feature set; free in Community mode, a paid license unlocks the full edition.", paid["licenseNote"]!!.jsonPrimitive.content)
        assertEquals("2025.3.test", paid["version"]!!.jsonPrimitive.content)
        assertEquals("2025-12-08", paid["releaseDate"]!!.jsonPrimitive.content)
        assertFalse("requiresAllowPaid" in paid)
    }

    @Test
    fun `json marks incompatible lines only and never emits compatible=true`() {
        val rows = listOf(
            AvailableBackendDownload(product = IdeProduct.IntelliJIdea, version = "2026.1.2", build = "261.1", compatible = true),
            AvailableBackendDownload(product = IdeProduct.IntelliJIdeaCommunity, version = "2025.3", build = "253.1", compatible = false),
        )
        val available = renderJson(rows)["available"]!!.jsonArray.map { it.jsonObject }
        val compatibleRow = available.single { it["id"]!!.jsonPrimitive.content == "idea-ultimate" }
        val incompatibleRow = available.single { it["id"]!!.jsonPrimitive.content == "idea-community" }

        assertFalse("compatible" in compatibleRow, "must not emit compatible=true noise")
        assertFalse("incompatible" in compatibleRow)
        assertFalse("compatible" in incompatibleRow)
        assertEquals("true", incompatibleRow["incompatible"]!!.jsonPrimitive.content)
    }

    @Test
    fun `text license legend omits unused symbol tiers`() {
        val text = renderText(
            listOf(
                AvailableBackendDownload(
                    product = IdeProduct.IntelliJIdeaCommunity,
                    version = "2025.3",
                    releaseDate = "2025-12-08",
                ),
                AvailableBackendDownload(
                    product = IdeProduct.PyCharmCommunity,
                    version = "2025.3",
                    releaseDate = "2025-12-08",
                ),
            ),
        )

        assertFalse(text.contains("Includes the Community feature set; free in Community mode, a paid license unlocks the full edition."), text)
        assertFalse(text.contains("Free for non-commercial use; JetBrains license required for commercial use."), text)
    }

    @Test
    fun `paid products are resolved without consent flags`() = runBlocking {
        val resolver = CountingResolver()

        collectAvailableBackendDownloads(versionResolver = resolver)

        assertEquals(
            setOf(
                "idea-community", "pycharm-community", "mps", "android-studio",
                "goland", "webstorm", "rider", "clion", "rustrover",
                "idea-ultimate", "pycharm-pro", "phpstorm", "rubymine", "datagrip",
            ),
            resolver.calls.toSet(),
        )
    }

    @Test
    fun `text command renders rows after resolver work`() {
        val buf = ByteArrayOutputStream()
        val out = PrintStream(buf, true, Charsets.UTF_8)
        var resolverEntered = false

        runBackendDownloadListCommand(
            out = out,
            json = false,
            availableDownloads = {
                resolverEntered = true
                listOf(
                    AvailableBackendDownload(
                        product = IdeProduct.IntelliJIdeaCommunity,
                        version = "2025.3",
                    ),
                )
            },
        )

        val text = buf.toString(Charsets.UTF_8)
        assertTrue(resolverEntered)
        assertTrue(text.startsWith("Available IDEs (defaults to latest stable):"), text)
        assertTrue(text.contains("idea-community"), text)
        assertTrue(text.contains("2025.3"), text)
    }

    private fun renderText(rows: List<AvailableBackendDownload>): String {
        val buf = ByteArrayOutputStream()
        renderBackendDownloadListText(rows, PrintStream(buf, true, Charsets.UTF_8))
        return buf.toString(Charsets.UTF_8)
    }

    private fun renderJson(rows: List<AvailableBackendDownload>) = Json
        .parseToJsonElement(
            ByteArrayOutputStream().also { buf ->
                renderBackendDownloadListJson(rows, PrintStream(buf, true, Charsets.UTF_8))
            }.toString(Charsets.UTF_8),
        ).jsonObject

    private fun productLines(text: String): List<String> = text.lines()
        .filter { it.trimStart().startsWith("[") }

    private class FakeVersionResolver(
        private val versions: Map<String, Result<AvailableBackendRelease>>,
    ) : AvailableBackendVersionResolver {
        override suspend fun resolveLatestStableRelease(product: IdeProduct): AvailableBackendRelease =
            versions[product.id]?.getOrThrow() ?: AvailableBackendRelease("2025.3.test", "2025-12-08")
    }

    private class CountingResolver : AvailableBackendVersionResolver {
        val calls = mutableListOf<String>()

        override suspend fun resolveLatestStableRelease(product: IdeProduct): AvailableBackendRelease {
            calls += product.id
            return AvailableBackendRelease("2025.3.test", "2025-12-08")
        }
    }
}
