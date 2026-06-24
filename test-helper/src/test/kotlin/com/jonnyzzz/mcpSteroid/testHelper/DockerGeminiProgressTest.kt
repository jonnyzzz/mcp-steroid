/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.testHelper

import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.docker.DockerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.StartContainerRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.buildDockerImage
import com.jonnyzzz.mcpSteroid.testHelper.docker.startDockerContainerAndDispose
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DockerGeminiProgressTest {

    @Test
    fun `test Gemini shows progress events with stream-json`() = runWithCloseableStack { stack ->
        val geminiSession = DockerGeminiSession.create(stack)
        val result = geminiSession.runPrompt(
            prompt = """
                List files in current directory.
                You must use a tool call (for example, a shell command) to get the result.
            """.trimIndent(),
            timeoutSeconds = 30
        ).awaitForProcessFinish()
            .assertExitCode(0) { "Gemini command should succeed" }

        val events = parseNdjsonEvents(result.rawStdout)
        assertTrue(events.isNotEmpty(), "Raw output should contain stream-json NDJSON events")
        assertTrue(events.size > 1, "Expected multiple stream-json events, found ${events.size}")

        val eventTypes = events.mapNotNull { it.stringField("type") }
        assertTrue(eventTypes.contains("tool_use"), "Raw output should contain tool_use events: $eventTypes")
        assertTrue(eventTypes.contains("tool_result"), "Raw output should contain tool_result events: $eventTypes")
        assertTrue(eventTypes.contains("result"), "Raw output should contain result events: $eventTypes")
    }

    private fun parseNdjsonEvents(rawOutput: String): List<JsonObject> {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        return buildList {
            for (line in rawOutput.lineSequence()) {
                val trimmed = line.trim()
                if (!trimmed.startsWith("{")) continue

                val event = try {
                    json.parseToJsonElement(trimmed) as? JsonObject
                } catch (_: Exception) {
                    null
                }

                if (event != null) {
                    add(event)
                }
            }
        }
    }

    private fun JsonObject.stringField(fieldName: String): String? {
        val primitive = this[fieldName] as? JsonPrimitive ?: return null
        return primitive.contentOrNull
    }
}
