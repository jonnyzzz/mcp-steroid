/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig.server

import com.jonnyzzz.mcpSteroid.mcp.McpServerCore
import com.jonnyzzz.mcpSteroid.mcp.Prompt
import com.jonnyzzz.mcpSteroid.mcp.Resource
import com.jonnyzzz.mcpSteroid.mcp.ServerCapabilities
import com.jonnyzzz.mcpSteroid.mcp.ServerInfo
import com.jonnyzzz.mcpSteroid.mcp.Tool
import com.jonnyzzz.mcpSteroid.prompts.PromptsContext
import com.jonnyzzz.mcpSteroid.devrig.HomePaths
import com.jonnyzzz.mcpSteroid.devrig.DevrigServices
import com.jonnyzzz.mcpSteroid.server.McpSteroidTools
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolHandler
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolSpec
import com.jonnyzzz.mcpSteroid.server.PromptsContextHandler
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.io.TempDir

class DevrigDescriptorParityTest {
    // The one intentional divergence: the devrig steroid_open_project surface advertises the optional
    // `backend_name` routing parameter (REQUIRED on devrig), letting the agent pick which discovered IDE
    // a project opens in. The direct in-IDE surface (one connection == one IDE) does not expose it. Every
    // other tool — and the existence of steroid_open_project on both sides — must stay identical.
    private val backendOnlyTool = "steroid_open_project"

    @Test
    fun `devrig descriptors match direct IDE supported surface`(
        @TempDir tempDir: Path,
    ) {
        val direct = directIdeServer(PromptsContext(productCode = "IU", baselineVersion = 261))
        val devrig = devrigServer(tempDir)

        val directTools = direct.toolRegistry.listTools().associateBy(Tool::name)
        val devrigTools = devrig.toolRegistry.listTools().associateBy(Tool::name)

        // The set of advertised tools is identical on both surfaces.
        assertEquals(directTools.keys, devrigTools.keys)

        // Every tool except steroid_open_project is byte-identical across the two surfaces.
        for ((name, directTool) in directTools) {
            if (name == backendOnlyTool) continue
            assertEquals(directTool, devrigTools.getValue(name), "tool descriptor mismatch for $name")
        }

        // steroid_open_project: present on both, and the devrig variant is a deliberate superset that
        // adds the REQUIRED backend_name parameter; the direct surface must NOT carry it.
        val directOpen = directTools.getValue(backendOnlyTool)
        val devrigOpen = devrigTools.getValue(backendOnlyTool)
        val directProps = directOpen.inputSchema["properties"]?.jsonObject ?: error("missing properties")
        val devrigProps = devrigOpen.inputSchema["properties"]?.jsonObject ?: error("missing properties")
        assertFalse(directProps.containsKey("backend_name"), "direct IDE surface must not expose backend_name")
        assertTrue(devrigProps.containsKey("backend_name"), "devrig surface must expose backend_name")

        assertDescriptorSubset(
            direct = direct.resourceRegistry.listResources().associateBy(Resource::uri),
            devrig = devrig.resourceRegistry.listResources().associateBy(Resource::uri),
            label = "resources",
        )
        assertDescriptorSubset(
            direct = direct.promptRegistry.listPrompts().associateBy(Prompt::name),
            devrig = devrig.promptRegistry.listPrompts().associateBy(Prompt::name),
            label = "prompts",
        )
    }

    private fun directIdeServer(context: PromptsContext): McpServerCore =
        newServer().also { server ->
            val tools = DirectDescriptorTools(context)
            tools.registerAll(server)
            // Mirror the in-IDE SteroidsMcpServer callsite: registerAll() no longer registers
            // open_project, so the single-backend surface registers its own spec (no backend_name).
            server.toolRegistry.registerTool(
                OpenProjectToolSpec(includeBackendName = false) { tools.handler<OpenProjectToolHandler>() }
            )
        }

    private fun devrigServer(tempDir: Path): McpServerCore {
        val lifetime = CloseableStackHost()
        return try {
            newServer().also { server ->
                val tools = StubMcpSteroidTools(
                    DevrigServices(
                        lifetime = lifetime,
                        homePaths = HomePaths(tempDir.resolve("devrig-home")).also { it.mkdirsAll() },
                        mcpStdin = ByteArrayInputStream(ByteArray(0)),
                        mcpStdout = PrintStream(ByteArrayOutputStream(), true, Charsets.UTF_8),
                    )
                )
                // Mirror the devrig StubStdioMcpServer callsite: the canonical devrigToolSpecs() list,
                // whose open_project advertises the required backend_name routing param.
                tools.devrigToolSpecs().forEach { server.toolRegistry.registerTool(it) }
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }

    private fun newServer(): McpServerCore =
        McpServerCore(
            serverInfo = ServerInfo(name = "descriptor-parity", version = "0"),
            capabilities = ServerCapabilities(),
        )

    private fun <T> assertDescriptorSubset(
        direct: Map<String, T>,
        devrig: Map<String, T>,
        label: String,
    ) {
        val missing = direct.keys - devrig.keys
        assertEquals(emptySet(), missing, "devrig is missing direct IDE $label: $missing")
        for ((key, directDescriptor) in direct) {
            assertEquals(
                directDescriptor,
                assertNotNull(devrig[key], "devrig is missing direct IDE $label descriptor: $key"),
                "descriptor mismatch for $label $key",
            )
        }
    }

    private class DirectDescriptorTools(
        private val context: PromptsContext,
    ) : McpSteroidTools() {
        val promptsContextHandler = object : PromptsContextHandler {
            override suspend fun buildPromptsContext(projectName: String): PromptsContext = context
        }

        override fun <T> handler(type: Class<T>): T {
            if (type == PromptsContextHandler::class.java) {
                return type.cast(promptsContextHandler)
            }
            error("handler ${type.name} must not be resolved while listing descriptors")
        }
    }
}
