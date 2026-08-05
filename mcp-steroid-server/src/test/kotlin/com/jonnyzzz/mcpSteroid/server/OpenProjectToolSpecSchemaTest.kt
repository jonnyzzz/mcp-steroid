/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenProjectToolSpecSchemaTest {
    @Test
    fun `inputSchema default omits backend_name`() {
        val spec = OpenProjectToolSpec { unreachableHandler() }
        val schema = spec.inputSchema
        assertToolSpecHasValidJsonSchema(spec)
        assertToolIdentity(spec, "steroid_open_project")
        assertRequiredExactly(schema, "project_path", "task_id", "reason")
        assertStringProperty(schema, "project_path")
        assertStringProperty(schema, "task_id")
        assertStringProperty(schema, "reason")
        assertBooleanProperty(schema, "trust_project")
        assertPropertyAbsent(schema, "backend_name")
    }

    @Test
    fun `inputSchema with backend name exposes optional backend_name`() {
        val spec = OpenProjectToolSpec(includeBackendName = true) { unreachableHandler() }
        val schema = spec.inputSchema
        assertToolSpecHasValidJsonSchema(spec)
        assertToolIdentity(spec, "steroid_open_project")
        // backend_name is optional: omit when there is exactly one candidate; the handler picks it
        // automatically. The required set does NOT include backend_name.
        assertRequiredExactly(schema, "project_path", "task_id", "reason")
        assertStringProperty(schema, "backend_name")
    }

    @Test
    fun `devrig description explains clean install auto-start and frontendless readiness`() {
        val description = OpenProjectToolSpec(includeBackendName = true) { unreachableHandler() }.description

        for (fact in listOf(
            "devrig backend download --json",
            "starts it automatically",
            "Remote Development",
            "no frontend",
            "steroid_list_projects",
            "Maven",
            "Gradle",
            "Observation.awaitConfiguration",
            "smartReadAction",
            "mcp-steroid://skill/execute-code-maven",
            "mcp-steroid://skill/execute-code-gradle",
            "latest stable",
        )) {
            assertTrue(fact in description, "open-project description must mention '$fact': $description")
        }
        assertFalse(
            description.contains("MUST poll") && description.contains("steroid_list_windows"),
            "frontend window polling cannot be mandatory for a Remote Development backend: $description",
        )
    }

    @Test
    fun `post-open result guidance works with or without a frontend`() {
        val guidance = OPEN_PROJECT_VERIFICATION_WORKFLOW

        for (fact in listOf(
            "Poll steroid_list_projects",
            "opaque project_name",
            "no frontend window",
            "Maven/Gradle",
            "Observation.awaitConfiguration",
            "smartReadAction",
            "when modalDialogShowing is true",
        )) {
            assertTrue(fact in guidance, "post-open result guidance must mention '$fact': $guidance")
        }
        assertFalse(
            guidance.contains("MUST poll") && guidance.contains("steroid_list_windows"),
            "frontend window polling cannot be mandatory in the tool result: $guidance",
        )
        assertFalse(
            guidance.contains("Use steroid_take_screenshot to visually confirm"),
            "a frontendless Remote Development backend has nothing to screenshot: $guidance",
        )
        assertFalse(
            "Only then use" in guidance,
            "modal handling must happen while polling, not after modalDialogShowing becomes false: $guidance",
        )
    }
}
