/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FetchResourceToolHandlerSchemaTest {
    @Test
    fun `inputSchema`() {
        val spec = FetchResourceToolHandler { unreachableHandler() }
        val schema = spec.inputSchema
        assertToolSpecHasValidJsonSchema(spec)
        assertToolIdentity(spec, "steroid_fetch_resource")
        assertRequiredExactly(schema, "uri", "project_name")
        assertStringProperty(schema, "uri")
        assertStringProperty(schema, "project_name")
    }

    @Test
    fun `description routes exhaustive implementor work to type hierarchy`() {
        val description = FetchResourceToolHandler { unreachableHandler() }.description

        assertTrue(
            "Find every direct + transitive subtype / implementor" in description,
            "fetch-resource routing must make exhaustive hierarchy discovery explicit: $description",
        )
        assertTrue("mcp-steroid://ide/type-hierarchy" in description, description)
    }
}
