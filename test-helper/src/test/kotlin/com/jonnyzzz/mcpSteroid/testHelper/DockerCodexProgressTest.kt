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
import java.io.File

class DockerCodexProgressTest {

    @Test
    fun `test Codex shows progress events`() = runWithCloseableStack { stack ->
        val codexSession = DockerCodexSession.create(stack)
        val result = codexSession.runPrompt(
            prompt = "List files in current directory. You must use a tool call (for example, run a shell command) to get the result.",
            timeoutSeconds = 30
        ).awaitForProcessFinish()
            .assertExitCode(0) { "Codex command should succeed" }

        val events = parseNdjsonEvents(result.rawStdout)
        assertTrue(events.isNotEmpty(), "Raw output should contain NDJSON events")
        assertTrue(events.size > 1, "Expected multiple NDJSON events, found ${events.size}")

        val eventTypes = events.mapNotNull { it.stringField("type") }
        assertTrue(eventTypes.contains("item.started"), "Raw output should contain item.started events: $eventTypes")
        assertTrue(eventTypes.contains("item.completed"), "Raw output should contain item.completed events: $eventTypes")
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
