/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.filter.GeminiOutputFilter
import com.jonnyzzz.mcpSteroid.filter.filterText
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerVolume
import com.jonnyzzz.mcpSteroid.testHelper.docker.RunningContainerProcess
import com.jonnyzzz.mcpSteroid.process.ProcessResult
import com.jonnyzzz.mcpSteroid.process.ProcessResultValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests that [GeminiOutputFilter] produces readable output from NDJSON.
 * Detailed filter behavior is covered by GeminiOutputFilterTest in agent-output-filter.
 */
class DockerGeminiSessionTest {
    private val filter = GeminiOutputFilter()

    @Test
    fun extractsAssistantTextAndProgressFromStreamJson() {
        val raw = """
            YOLO mode is enabled. All tool calls will be automatically approved.
            {"type":"init","session_id":"s1"}
            {"type":"message","role":"user","content":"Read README"}
            {"type":"message","role":"assistant","content":"I will read the README file.\n","delta":true}
            {"type":"tool_use","tool_name":"read_file","tool_id":"tool-1","parameters":{"file_path":"README.md"}}
            {"type":"tool_result","tool_id":"tool-1","status":"success","output":""}
            {"type":"message","role":"assistant","content":"done","delta":true}
            {"type":"result","status":"success","stats":{"input_tokens":12,"output_tokens":34,"tool_calls":1,"duration_ms":1500}}
        """.trimIndent()

        val result = filter.filterText(raw)

        assertEquals(
            """
                YOLO mode is enabled. All tool calls will be automatically approved.
                I will read the README file.
                >> read_file (README.md)
                << read_file
                done
                [done] in=12 out=34 tools=1 time=1.5s
            """.trimIndent(),
            result
        )
    }

    @Test
    fun fallsBackToEmptyOutputWhenNoRelevantEventsFound() {
        val raw = """
            {"type":"init","session_id":"s1"}
            {"type":"message","role":"user","content":"ping"}
        """.trimIndent()

        val result = filter.filterText(raw)

        // Filter skips init and user messages, filterText trims to empty
        assertTrue(result.isEmpty(), "Expected empty output for non-relevant events, got: $result")
    }

}
