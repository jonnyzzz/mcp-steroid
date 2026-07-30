/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.mcp.McpJson
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Drift gate for the #155 `backends[]` identity surface. [IntelliJInfo] is *identity-minimal* by
 * design — exactly `{name, version, build}`; any extra serialized key on a `backends[]` element or
 * its nested `intellij` object fails here. Growth pressure on the MCP identity is answered with
 * "add it to #151's `devrig backend --json`", never with a new [IntelliJInfo] field, unless a
 * concrete consumer message grounds it.
 */
class BackendRefSerializationTest {
    private val ide = IdeInfo(name = "IntelliJ IDEA 2026.1.3", version = "2026.1.3", build = "IU-261.25134.95")

    @Test
    fun `BackendRef element serializes to exactly backend_name + intellij`() {
        val json = McpJson.encodeToString(BackendRef.serializer(), BackendRef("iu-47qi79c1", ide.toIntelliJInfo()))
        val root = McpJson.parseToJsonElement(json).jsonObject
        assertEquals(setOf("backend_name", "intellij"), root.keys, json)
        assertEquals(setOf("name", "version", "build"), root["intellij"]!!.jsonObject.keys, json)
    }

    @Test
    fun `BackendRef round-trips through JSON unchanged`() {
        val ref = BackendRef("iu-47qi79c1", ide.toIntelliJInfo())
        val json = McpJson.encodeToString(BackendRef.serializer(), ref)
        assertEquals(ref, McpJson.decodeFromString(BackendRef.serializer(), json))
    }

    @Test
    fun `toIntelliJInfo projects the three identity fields 1-to-1`() {
        val projected = ide.toIntelliJInfo()
        assertEquals(ide.name, projected.name)
        assertEquals(ide.version, projected.version)
        assertEquals(ide.build, projected.build)
    }

    @Test
    fun `backendsTable de-dups by backend_name and sorts deterministically`() {
        val goland = IdeInfo(name = "GoLand 2026.1.2", version = "2026.1.2", build = "GO-261.24374.154")
        val unsorted = listOf(
            BackendRef("iu-47qi79c1", ide.toIntelliJInfo()),
            BackendRef("go-3f9dk21a", goland.toIntelliJInfo()),
            // Duplicate key (two projects on the same IDE): keep-first, appears once.
            BackendRef("iu-47qi79c1", ide.toIntelliJInfo()),
        )
        assertEquals(
            listOf(
                BackendRef("go-3f9dk21a", goland.toIntelliJInfo()),
                BackendRef("iu-47qi79c1", ide.toIntelliJInfo()),
            ),
            backendsTable(unsorted),
        )
    }
}
