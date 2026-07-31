/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.arena

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArenaAgentToolCallParserTest {

    @Test
    fun `claude calls retain shell commands and normalized MCP arguments and results`() {
        val transcript = decodeAgentToolCalls(
            listOf(
                claudeCall(
                    id = "download",
                    name = "Bash",
                    input = buildJsonObject {
                        put("command", "devrig backend download idea-ultimate --version 2026.2.0.1")
                    },
                ),
                claudeResult("download", "downloaded IU 262", isError = false),
                claudeCall(
                    id = "start",
                    name = "Bash",
                    input = buildJsonObject {
                        put("command", "devrig backend start idea-ultimate --version 2026.2.0.1")
                    },
                ),
                claudeResult("start", "backend started", isError = false),
                claudeCall(
                    id = "open",
                    name = "mcp__mcp-steroid__steroid_open_project",
                    input = buildJsonObject { put("project_path", "/workspace/keycloak") },
                ),
                claudeResult("open", "opened /workspace/keycloak", isError = false, contentAsArray = true),
                claudeCall(
                    id = "execute",
                    name = "mcp__mcp-steroid__steroid_execute_code",
                    input = buildJsonObject {
                        put("code", "ClassInheritorsSearch.search(authenticator, scope, true).findAll()")
                    },
                ),
                claudeResult("execute", "UsernamePasswordForm\nOTPFormAuthenticator", isError = false),
            ).joinToString("\n"),
        )

        assertEquals(listOf("Bash", "Bash", "steroid_open_project", "steroid_execute_code"), transcript.map { it.toolName })
        assertEquals(
            "devrig backend download idea-ultimate --version 2026.2.0.1",
            transcript[0].arguments["command"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "devrig backend start idea-ultimate --version 2026.2.0.1",
            transcript[1].arguments["command"]?.jsonPrimitive?.content,
        )
        assertEquals("/workspace/keycloak", transcript[2].arguments["project_path"]?.jsonPrimitive?.content)
        assertEquals("opened /workspace/keycloak", transcript[2].result?.text)
        assertEquals(
            "ClassInheritorsSearch.search(authenticator, scope, true).findAll()",
            transcript[3].arguments["code"]?.jsonPrimitive?.content,
        )
        assertEquals("UsernamePasswordForm\nOTPFormAuthenticator", transcript[3].result?.text)
        assertFalse(transcript.any { it.result?.isError == true })
    }

    @Test
    fun `codex de-duplicates lifecycle events and retains command and MCP results`() {
        val transcript = decodeAgentToolCalls(
            listOf(
                codexCommandStarted(
                    id = "download",
                    command = "devrig backend download idea-ultimate --version 2026.2.0.1",
                ),
                codexCommandCompleted(
                    id = "download",
                    command = "devrig backend download idea-ultimate --version 2026.2.0.1",
                    output = "downloaded IU 262",
                ),
                codexCommandCompleted(
                    id = "start",
                    command = "devrig backend start idea-ultimate --version 2026.2.0.1",
                    output = "backend started",
                ),
                codexMcpStarted(
                    id = "open",
                    tool = "steroid_open_project",
                    arguments = buildJsonObject { put("project_path", "/workspace/keycloak") },
                ),
                codexMcpCompleted(
                    id = "open",
                    tool = "steroid_open_project",
                    arguments = buildJsonObject { put("project_path", "/workspace/keycloak") },
                    resultText = "opened /workspace/keycloak",
                ),
                codexMcpCompletedWithStringArguments(
                    id = "execute",
                    tool = "mcp__mcp-steroid__steroid_execute_code",
                    arguments = buildJsonObject {
                        put("code", "ClassInheritorsSearch.search(authenticator, scope, true).findAll()")
                    },
                    resultText = "OTPFormAuthenticator\nIdpConfirmLinkAuthenticator",
                ),
            ).joinToString("\n"),
        )

        assertEquals(
            listOf("command_execution", "command_execution", "steroid_open_project", "steroid_execute_code"),
            transcript.map { it.toolName },
        )
        assertEquals(4, transcript.size)
        assertTrue(transcript[0].arguments["command"]?.jsonPrimitive?.content?.contains("backend download") == true)
        assertEquals("downloaded IU 262", transcript[0].result?.text)
        assertTrue(transcript[1].arguments["command"]?.jsonPrimitive?.content?.contains("backend start") == true)
        assertEquals("backend started", transcript[1].result?.text)
        assertEquals("/workspace/keycloak", transcript[2].arguments["project_path"]?.jsonPrimitive?.content)
        assertEquals("opened /workspace/keycloak", transcript[2].result?.text)
        assertTrue(transcript[3].arguments["code"]?.jsonPrimitive?.content?.contains("ClassInheritorsSearch") == true)
        assertEquals("OTPFormAuthenticator\nIdpConfirmLinkAuthenticator", transcript[3].result?.text)
    }

    @Test
    fun `claude final response comes from the successful result event`() {
        val raw = listOf(
            claudeResult("execute", "tool output must not become the final answer", isError = false),
            buildJsonObject {
                put("type", "assistant")
                putJsonObject("message") {
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", "intermediate assistant text")
                        }
                    }
                }
            }.toString(),
            buildJsonObject {
                put("type", "result")
                put("subtype", "success")
                put("result", "SUBTYPES_FOUND: 72\nSUBTYPE: org.example.FinalClaudeAnswer")
            }.toString(),
        ).joinToString("\n")

        assertEquals(
            "SUBTYPES_FOUND: 72\nSUBTYPE: org.example.FinalClaudeAnswer",
            decodeAgentFinalResponse(raw),
        )
    }

    @Test
    fun `claude final response falls back to the last assistant text when result is empty`() {
        val raw = listOf(
            buildJsonObject {
                put("type", "assistant")
                putJsonObject("message") {
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", "intermediate assistant text")
                        }
                    }
                }
            }.toString(),
            buildJsonObject {
                put("type", "assistant")
                putJsonObject("message") {
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", "SUBTYPES_FOUND: 72\nSUBTYPE: org.example.FinalClaudeAnswer")
                        }
                    }
                }
            }.toString(),
            buildJsonObject {
                put("type", "result")
                put("subtype", "success")
                put("result", "")
            }.toString(),
        ).joinToString("\n")

        assertEquals(
            "SUBTYPES_FOUND: 72\nSUBTYPE: org.example.FinalClaudeAnswer",
            decodeAgentFinalResponse(raw),
        )
    }

    @Test
    fun `codex final response is the last completed agent message`() {
        val raw = listOf(
            buildJsonObject {
                put("type", "item.completed")
                putJsonObject("item") {
                    put("id", "progress")
                    put("type", "agent_message")
                    put("text", "Still indexing")
                }
            }.toString(),
            codexMcpCompleted(
                id = "execute",
                tool = "steroid_execute_code",
                arguments = buildJsonObject { put("code", "ClassInheritorsSearch") },
                resultText = "tool output must not become the final answer",
            ),
            buildJsonObject {
                put("type", "item.completed")
                putJsonObject("item") {
                    put("id", "final")
                    put("type", "agent_message")
                    put("text", "SUBTYPES_FOUND: 70\nSUBTYPE: org.example.FinalCodexAnswer")
                }
            }.toString(),
            buildJsonObject { put("type", "turn.completed") }.toString(),
        ).joinToString("\n")

        assertEquals(
            "SUBTYPES_FOUND: 70\nSUBTYPE: org.example.FinalCodexAnswer",
            decodeAgentFinalResponse(raw),
        )
    }

    @Test
    fun `failed calls and missing results remain distinguishable`() {
        val transcript = decodeAgentToolCalls(
            listOf(
                "not json",
                claudeCall("pending", "custom__tool", buildJsonObject { put("value", 1) }),
                codexMcpCompleted(
                    id = "failed",
                    tool = "steroid_open_project",
                    arguments = buildJsonObject { put("project_path", "/workspace/missing") },
                    resultText = "Project not found",
                    status = "failed",
                ),
            ).joinToString("\n"),
        )

        assertEquals("custom__tool", transcript[0].toolName)
        assertNull(transcript[0].result)
        assertEquals("steroid_open_project", transcript[1].toolName)
        assertTrue(transcript[1].result?.isError == true)
        assertEquals("Project not found", transcript[1].result?.text)
    }

    private fun claudeCall(id: String, name: String, input: JsonObject): String = buildJsonObject {
        put("type", "assistant")
        putJsonObject("message") {
            putJsonArray("content") {
                addJsonObject {
                    put("type", "tool_use")
                    put("id", id)
                    put("name", name)
                    put("input", input)
                }
            }
        }
    }.toString()

    private fun claudeResult(id: String, text: String, isError: Boolean, contentAsArray: Boolean = false): String =
        buildJsonObject {
            put("type", "user")
            putJsonObject("message") {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", id)
                        put("is_error", isError)
                        if (contentAsArray) {
                            putJsonArray("content") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", text)
                                }
                            }
                        } else {
                            put("content", text)
                        }
                    }
                }
            }
        }.toString()

    private fun codexCommandStarted(id: String, command: String): String = buildJsonObject {
        put("type", "item.started")
        putJsonObject("item") {
            put("id", id)
            put("type", "command_execution")
            put("command", command)
        }
    }.toString()

    private fun codexCommandCompleted(id: String, command: String, output: String): String = buildJsonObject {
        put("type", "item.completed")
        putJsonObject("item") {
            put("id", id)
            put("type", "command_execution")
            put("command", command)
            put("status", "completed")
            put("exit_code", 0)
            put("aggregated_output", output)
        }
    }.toString()

    private fun codexMcpStarted(id: String, tool: String, arguments: JsonObject): String = buildJsonObject {
        put("type", "item.started")
        putJsonObject("item") {
            put("id", id)
            put("type", "mcp_tool_call")
            put("tool", tool)
            put("arguments", arguments)
        }
    }.toString()

    private fun codexMcpCompleted(
        id: String,
        tool: String,
        arguments: JsonObject,
        resultText: String,
        status: String = "completed",
    ): String = buildJsonObject {
        put("type", "item.completed")
        putJsonObject("item") {
            put("id", id)
            put("type", "mcp_tool_call")
            put("tool", tool)
            put("arguments", arguments)
            put("status", status)
            putJsonObject("result") {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", resultText)
                    }
                }
            }
        }
    }.toString()

    private fun codexMcpCompletedWithStringArguments(
        id: String,
        tool: String,
        arguments: JsonObject,
        resultText: String,
    ): String = buildJsonObject {
        put("type", "item.completed")
        putJsonObject("item") {
            put("id", id)
            put("type", "mcp_tool_call")
            put("tool", tool)
            put("arguments", arguments.toString())
            put("status", "completed")
            putJsonObject("result") {
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", resultText)
                    }
                }
            }
        }
    }.toString()
}
