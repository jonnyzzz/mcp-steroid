/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.filter.ClaudeOutputFilter
import com.jonnyzzz.mcpSteroid.filter.filterText
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Integration test for Claude progress visibility using lightweight Docker container.
 *
 * This test validates that Claude's stream-json output format properly shows
 * tool calls and progress events in real time, not just the final answer.
 *
 * The lightweight container (claude-cli) contains only Node.js and @anthropic-ai/claude-code,
 * without a full IDE setup.
 *
 * **Prerequisites**:
 * - Docker must be running
 * - ANTHROPIC_API_KEY environment variable must be set (or ~/.anthropic file must exist)
 * - Dockerfile must exist at test-helper/src/test/docker/claude-cli/Dockerfile
 *
 * **Note**: Integration tests that require Docker and API keys are disabled by default.
 * They run only when ANTHROPIC_API_KEY is present.
 */
class DockerClaudeProgressTest {

    /**
     * Integration test requiring Docker and ANTHROPIC_API_KEY.
     * This test is disabled by default and only runs when the API key is available.
     *
     * To run: Set ANTHROPIC_API_KEY environment variable and ensure Docker is running.
     */
    @Test
    fun `test Claude shows progress events for tool calls`() = runWithCloseableStack { stack ->
        // Create Claude session with lightweight container
        val claudeSession = DockerClaudeSession.create(stack)

        // Run a simple prompt that will trigger tool usage.
        // awaitForProcessFinish() returns AiProcessResult with both filtered and raw output.
        // We use rawStdout to assert on the protocol-level NDJSON events.
        val result = claudeSession.runPrompt(
            prompt = "List all files in the current directory using bash commands. Show me the full output.",
            timeoutSeconds = 60
        ).awaitForProcessFinish()
            .assertExitCode(0) { "Claude command should succeed: $stderr" }

        // Verify raw output contains NDJSON progress events
        assertTrue(
            result.rawStdout.contains("\"type\""),
            "Raw output should contain NDJSON events with 'type' field"
        )

        // Verify tool_use events are present in raw output
        // Claude stream-json emits events like {"type":"content_block_start","content_block":{"type":"tool_use",...}}
        val hasToolUse = result.rawStdout.contains("tool_use")
        assertTrue(
            hasToolUse,
            "Raw output should contain tool_use events showing tool calls in progress"
        )

        // Verify we get actual progress events, not just the final result
        // The raw output should have multiple JSON lines (NDJSON)
        val jsonLineCount = result.rawStdout.lines().count { line ->
            line.trim().startsWith("{") && line.trim().endsWith("}")
        }
        assertTrue(
            jsonLineCount > 1,
            "Raw output should contain multiple NDJSON events (found $jsonLineCount), not just final result"
        )

        // Verify the filtered output is human-readable text (not raw NDJSON)
        val filteredOutput = result.stdout
        assertTrue(
            filteredOutput.isNotBlank(),
            "Filtered output should contain the final answer text"
        )

        println("✓ Claude progress test passed")
        println("  - Raw output had $jsonLineCount NDJSON events")
        println("  - Tool use events detected: $hasToolUse")
        println("  - Filtered output length: ${filteredOutput.length} chars")
    }

    @Test
    fun `test Claude filter handles tool_use in content blocks`() {
        // Test the stream-json filter with real tool_use event structure
        val raw = """
            {"type":"message_start","message":{"id":"msg_123","type":"message"}}
            {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_456","name":"bash","input":{}}}
            {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"command\":\""}}
            {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"ls -la\"}"}}
            {"type":"content_block_stop","index":0}
            {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}
            {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"The files "}}
            {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"are listed above."}}
            {"type":"content_block_stop","index":1}
            {"type":"message_delta","delta":{"stop_reason":"end_turn"}}
            {"type":"message_stop"}
        """.trimIndent()

        val filter = ClaudeOutputFilter()
        val result = filter.filterText(raw)

        // Should show tool call and text content
        assertTrue(
            result.contains(">> bash"),
            "Should show tool_use block for bash: $result"
        )
        assertTrue(
            result.contains("files are listed"),
            "Should extract text_delta content: $result"
        )
    }
}
