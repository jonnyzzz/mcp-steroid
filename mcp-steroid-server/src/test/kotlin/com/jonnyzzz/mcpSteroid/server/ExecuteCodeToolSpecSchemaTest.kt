/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Test

class ExecuteCodeToolSpecSchemaTest {
    @Test
    fun `inputSchema`() {
        val spec = ExecuteCodeToolSpec { unreachableHandler() }
        val schema = spec.inputSchema
        assertToolSpecHasValidJsonSchema(spec)
        assertToolIdentity(spec, "steroid_execute_code")
        assertRequiredExactly(schema, "project_name", "code", "reason", "task_id")
        assertStringProperty(schema, "project_name")
        assertStringProperty(schema, "code")
        assertStringProperty(schema, "task_id")
        assertStringProperty(schema, "reason")
        assertIntegerProperty(schema, "timeout")
        assertEnumProperty(schema, "modal", "smart_non_modal", "non_modal", "unleashed")
    }

    @Test
    fun `project_name is optional when not required`() {
        val spec = ExecuteCodeToolSpec(projectNameRequired = false) { unreachableHandler() }
        val schema = spec.inputSchema
        assertRequiredExactly(schema, "code", "reason", "task_id") // project_name absent
        assertStringProperty(schema, "project_name")               // still advertised
    }
}
