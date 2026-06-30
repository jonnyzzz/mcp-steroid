/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure tests for the two "IDE is still busy — call again" signals `mcpExecuteCode` retries on while a
 * big-project import/indexing saturates the IDE (see jonnyzzz/mcp-steroid#169):
 *  - [isIndexingInProgress]: a clean result carrying the INDEXING IN PROGRESS marker, and
 *  - [isTransientMcpRequestFailure]: the request was killed (exit -1) because the IDE could not even answer.
 *
 * A script-level error comes back as an isError result with a real exit code (1), never the killed-process
 * -1, so it must NOT be treated as transient — it surfaces immediately.
 */
class McpBusyRetryTest {

    @Test
    fun `isIndexingInProgress detects the busy marker`() {
        assertTrue(isIndexingInProgress("...\n$INDEXING_IN_PROGRESS_MARKER\n..."))
        assertFalse(isIndexingInProgress("done"))
        assertFalse(isIndexingInProgress(""))
    }

    @Test
    fun `isTransientMcpRequestFailure matches a killed-curl timeout but not a script error`() {
        assertTrue(
            isTransientMcpRequestFailure(
                IllegalStateException("Process MCP request failed: <no body> exit code is -1 != 0"),
            ),
        )
        // A script-level error has a real exit code (1), not the killed-process -1 — must NOT be retried.
        assertFalse(
            isTransientMcpRequestFailure(
                IllegalStateException("Process MCP request failed: compile error exit code is 1 != 0"),
            ),
        )
        assertFalse(isTransientMcpRequestFailure(RuntimeException("some unrelated failure")))
        assertFalse(isTransientMcpRequestFailure(IllegalStateException(null as String?)))
    }
}
