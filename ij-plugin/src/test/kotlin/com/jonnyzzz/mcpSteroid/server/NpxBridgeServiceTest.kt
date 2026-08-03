/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.mcp.McpSession
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class NpxBridgeServiceTest : BasePlatformTestCase() {
    fun testBridgeAddsAuthoritativeProvenanceWhenOldDevrigSendsNone() {
        assertAuthoritativeBridgeProvenance(buildJsonObject { })
    }

    fun testBridgeOverridesSpoofedProvenanceArguments() {
        assertAuthoritativeBridgeProvenance(buildJsonObject {
            put(EXECUTION_BACKEND_KIND_ARGUMENT, "s")
            put(EXECUTION_BACKEND_NAME_ARGUMENT, "spoofed-backend")
        })
    }

    private fun assertAuthoritativeBridgeProvenance(arguments: kotlinx.serialization.json.JsonObject) {
        val params = NpxBridgeService.getInstance().buildToolCallParams(
            request = NpxBridgeToolCallRequest(
                name = "steroid_execute_code",
                arguments = arguments,
            ),
            progressToken = "test-progress",
        )
        val context = ToolCallContext(
            params = params,
            session = McpSession(),
            mcpProgressReporter = NoOpProgressReporter,
        )
        val expectedBackendName = backendNameForMarker(
            ProcessHandle.current().pid(),
            ApplicationInfo.getInstance().build.asString(),
        )

        assertEquals(
            ExecutionBackendProvenance(kind = 'd', name = expectedBackendName),
            context.executionBackendProvenance(),
        )
    }
}
