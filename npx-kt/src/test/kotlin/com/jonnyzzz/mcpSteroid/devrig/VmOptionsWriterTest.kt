/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VmOptionsWriterTest {

    @Test
    fun `writes exact vmoptions content as sibling of bundle directory`(
        @TempDir tempDir: Path,
    ) {
        val homePaths = HomePaths(tempDir.resolve("home"))
        val id = "idea-community-2025.3.3"

        val written = writeBackendVmOptions(homePaths, id, "IntelliJ IDEA CE.app")

        val expectedPath = homePaths.backendDir(id).resolve("IntelliJ IDEA CE.app.vmoptions")
        assertEquals(expectedPath, written)

        val cacheDir = homePaths.cacheDir(id).toAbsolutePath().normalize()
        val expected = listOf(
            "-Didea.config.path=${cacheDir.resolve("config")}",
            "-Didea.system.path=${cacheDir.resolve("system")}",
            "-Didea.log.path=${cacheDir.resolve("logs")}",
            "-Didea.plugins.path=${cacheDir.resolve("plugins")}",
            "-Didea.vendor.name=devrig (managed)",
            "-Xms256m",
            "-Xmx2048m",
            "-Dmcp.steroid.idea.description.enabled=false",
            "-Dmcp.steroid.dialog.killer.enabled=true",
            "-Djb.consents.confirmation.enabled=false",
            "-Djb.privacy.policy.text=<!--999.999-->",
            "-Djb.privacy.policy.ai.assistant.text=<!--999.999-->",
            "-Dmarketplace.eula.reviewed.and.accepted=true",
            "-Dwriterside.eula.reviewed.and.accepted=true",
            "-Didea.initially.ask.config=never",
            "-Dide.newUsersOnboarding=false",
            "-Dnosplash=true",
            "",
        ).joinToString("\n")

        val content = Files.readString(written)
        assertEquals(expected, content)
        assertFalse(content.contains("\r"), "vmoptions must use LF line endings only")
        // The managed IDE should behave like a normal install: report analytics and check for updates.
        assertFalse(content.contains("mcp.steroid.updates.enabled"), "must not disable updates in the managed IDE")
        assertFalse(content.contains("mcp.steroid.analytics.enabled"), "must not disable analytics in the managed IDE")
        assertFalse(content.contains("mcp.steroid.backend.kind"),
            "backend kind is per-call provenance, not a managed-IDE launch property")

        listOf("config", "system", "logs", "plugins").forEach { child ->
            assertTrue(homePaths.cacheDir(id).resolve(child).exists(), "$child cache directory should exist")
        }
        assertFalse(homePaths.cacheDir(id).resolve("execution-storage").exists(),
            "managed backends should use the shared ~/.mcp-steroid/runs storage")
        assertFalse(homePaths.backendDir(id).resolve("IntelliJ IDEA CE.app/Contents/bin/idea.vmoptions").exists(),
            "vmoptions must be a sibling to the bundle, not written inside the signed macOS app")
    }
}
