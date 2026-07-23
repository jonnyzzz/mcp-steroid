/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.mcp

import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import com.jonnyzzz.mcpSteroid.thisLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Registry for MCP tools.
 */
class McpToolRegistry : McpToolRegistrar {
    private val log = thisLogger()

    private val jsonToLogMessages = Json {
        prettyPrint = true
    }

    private val tools = mutableMapOf<String, McpTool>()

    /**
     * Register a tool. Metadata (name/description/inputSchema) and the invocation
     * logic live on the [McpTool] instance itself.
     */
    override fun registerTool(tool: McpTool) {
        tools[tool.name] = tool
    }

    /**
     * Get all registered tools as MCP-protocol [Tool] descriptors.
     */
    fun listTools(): List<Tool> = tools.values.map {
        Tool(name = it.name, description = it.description, inputSchema = it.inputSchema)
    }

    /**
     * Get the registered [McpTool] instances themselves (registration order preserved), so callers that
     * need the richer metadata — the CLI [CliToolSpec.cli] descriptor and the tool's schema — can read it
     * without the lossy [Tool] wire projection. Used by the devrig CLI help generator.
     */
    fun listMcpTools(): List<McpTool> = tools.values.toList()

    /**
     * Call a tool by name.
     */
    suspend fun callTool(params: ToolCallParams, session: McpSession): ToolCallResult {
        val tool = tools[params.name]
            ?: return ToolCallResult(
                content = listOf(ContentItem.Text(text = "Tool not found: ${params.name}")),
                isError = true
            )

        val textParams = jsonToLogMessages.encodeToString(params.rawArguments)
        log.info("callTool with parameters: $textParams")

        val progressToken = params.rawArguments["arguments"]
            ?.jsonObject
            ?.get("_meta")
            ?.jsonObject?.get("progressToken")

        val progress = object : McpProgressReporter {
            val counter = AtomicInteger(0)

            override fun report(message: String) {
                if (progressToken == null) return

                val params = ProgressParams(
                    progressToken = progressToken,
                    progress = counter.incrementAndGet().toDouble(),
                    message = message
                )

                val notification = JsonRpcNotification(
                    method = McpMethods.PROGRESS,
                    params = McpJson.encodeToJsonElement(params).jsonObject
                )

                session.sendNotification(notification)
            }
        }

        val toolCallContext = ToolCallContext(params, session, progress)

        return try {
            tool.call(toolCallContext)
        } catch (e: ToolCallErrorException) {
            e.toolCallResult
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Never swallow cancellation — propagate so the surrounding coroutine
            // scope can shut down cleanly. Treating this as a tool error would surface
            // as `isError=true` and let the client think the call merely failed, while
            // the dispatcher would keep running on a cancelled context.
            throw e
        } catch (e: Exception) {
            ToolCallResult.builder()
                .addTextContent("Tool execution error: ${e.message}")
                .addTextContent("Stacktrace: " + e.stackTraceToString())
                .markAsError()
                .build()
        }
    }
}

data class ToolCallContext(
    val params: ToolCallParams,
    val session: McpSession,
    val mcpProgressReporter: McpProgressReporter,
) {
    /**
     * Check if the client supports sampling (LLM completion requests).
     */
    fun supportsSampling(): Boolean = session.supportsSampling()

    /**
     * Request an LLM completion from the client via MCP sampling.
     *
     * This sends a sampling/createMessage request to the client and waits for the response.
     * The client will use its configured LLM to generate a completion.
     *
     * @param messages The conversation messages to send
     * @param systemPrompt Optional system prompt
     * @param modelPreferences Optional model selection hints
     * @param maxTokens Optional maximum tokens for the response
     * @param timeout Maximum time to wait for the response
     * @return The completion result, or null if sampling is not supported or timed out
     * @throws McpRequestException if the client returns an error
     */
    suspend fun requestSampling(
        messages: List<SamplingMessage>,
        systemPrompt: String? = null,
        modelPreferences: ModelPreferences? = null,
        maxTokens: Int? = null,
        timeout: Duration = 60.seconds,
    ): CreateMessageResult? {
        if (!supportsSampling()) {
            return null
        }

        val params = CreateMessageParams(
            messages = messages,
            systemPrompt = systemPrompt,
            modelPreferences = modelPreferences,
            maxTokens = maxTokens,
        )

        val paramsJson = McpJson.encodeToJsonElement(params).jsonObject

        val result = session.sendRequest(
            method = McpMethods.SAMPLING_CREATE_MESSAGE,
            params = paramsJson,
            timeout = timeout,
        ) ?: return null

        return McpJson.decodeFromJsonElement<CreateMessageResult>(result)
    }

    /**
     * Simple helper to request a text completion from the client.
     *
     * @param prompt The user prompt to send
     * @param systemPrompt Optional system prompt
     * @param timeout Maximum time to wait for the response
     * @return The completion text, or null if sampling is not supported or failed
     */
    suspend fun requestCompletion(
        prompt: String,
        systemPrompt: String? = null,
        timeout: Duration = 60.seconds,
    ): String? {
        val result = requestSampling(
            messages = listOf(
                SamplingMessage(
                    role = "user",
                    content = SamplingContent.Text(text = prompt)
                )
            ),
            systemPrompt = systemPrompt,
            timeout = timeout,
        ) ?: return null

        return when (val content = result.content) {
            is SamplingContent.Text -> content.text
            is SamplingContent.Image -> "[Image content]"
        }
    }
}
