/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure tests for [isIndexingInProgress] — the predicate `mcpExecuteCode` uses to decide whether the IDE
 * is still indexing (so the harness should call again, exactly as an agent would) versus a real
 * success/error. See jonnyzzz/mcp-steroid#169.
 */
class IndexingInProgressDetectionTest {

    @Test
    fun `detects the still-indexing message the server emits while indexing`() {
        // #154: the result carries no [PRE]/[RUN]/[POST] framing; a pre-flight failure is a single
        // FAILED line that names the step and modality profile, with the marker inside it.
        val text = """
            execution_id: eid_x-integration-test
            FAILED: pre-flight 'wait for smart mode' (modal=smart_non_modal): $INDEXING_IN_PROGRESS_MARKER: the IDE is
            still indexing this project, so it is not ready yet. This is normal and expected … just keep
            polling: call this tool again to continue waiting.
        """.trimIndent()
        assertTrue(isIndexingInProgress(text))
    }

    @Test
    fun `a normal successful result is not still-indexing`() {
        // #154: a successful result is just the execution_id header plus the script's own output.
        assertFalse(isIndexingInProgress("execution_id: eid_x\nJDKs registered\ndone"))
    }

    @Test
    fun `a genuine failure is not still-indexing`() {
        assertFalse(isIndexingInProgress("execution_id: eid_x\nFAILED: compilation error: unresolved reference"))
    }
}
