/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration test validating that AI agents read MCP Steroid resources
 * via either the MCP protocol (ReadMcpResourceTool / resources/read) or
 * the dedicated steroid_fetch_resource tool.
 *
 * Analysis of 196 arena runs (April 2026) showed 0% of agents ever read
 * any MCP resource. This test verifies that explicit prompting works.
 */
class ResourceReadingTest {

    companion object {
        val lifetime by lazy { CloseableStackHost(ResourceReadingTest::class.java.simpleName) }
        val session by lazy {
            IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "Resource Reading — Claude",
            )).waitForProjectReady()
        }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            lifetime.closeAllStacks()
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `claude reads MCP resources when given a coding task`() {
        val console = session.console
        val agent = session.aiAgents.claude

        console.writeStep(text = "Running Claude with resource-reading prompt")

        val prompt = buildString {
            appendLine("# Task: Explore MCP Steroid resources")
            appendLine()
            appendLine("You have access to MCP Steroid. Before writing any code, read the available MCP resources")
            appendLine("to understand what IDE capabilities are available.")
            appendLine()
            appendLine("1. Use ListMcpResourcesTool to see what resources exist")
            appendLine("2. Use ReadMcpResourceTool to read at least one guide (pick one that interests you)")
            appendLine("3. After reading, report what you found")
            appendLine()
            appendLine("## Required Output")
            appendLine()
            appendLine("Print these markers on separate lines:")
            appendLine("RESOURCES_LISTED: yes")
            appendLine("RESOURCES_READ: <number of resources you read with ReadMcpResourceTool>")
        }

        val result = agent.runPrompt(prompt, timeoutSeconds = 600).awaitForProcessFinish()
        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        console.writeStep(text = "Validating resource tool usage")

        // Check for ListMcpResourcesTool usage in decoded log
        val usedListResources = combined.contains("ListMcpResourcesTool", ignoreCase = false)
                || combined.contains("resources/list", ignoreCase = false)
                || combined.contains("list_mcp_resources", ignoreCase = false)
        console.writeInfo("ListMcpResourcesTool used: $usedListResources")

        // Check for resource reading via any tool (ReadMcpResourceTool, resources/read, or steroid_fetch_resource)
        val usedReadResources = combined.contains("ReadMcpResourceTool", ignoreCase = false)
                || combined.contains("resources/read", ignoreCase = false)
                || combined.contains("read_mcp_resource", ignoreCase = false)
                || combined.contains("steroid_fetch_resource", ignoreCase = false)
        console.writeInfo("Resource read tool used: $usedReadResources")

        // Count resource read calls (any tool)
        val readResourceCalls = combined.lines().count { line ->
            line.contains("ReadMcpResourceTool", ignoreCase = false)
                    || line.contains("read_mcp_resource", ignoreCase = false)
                    || line.contains("steroid_fetch_resource", ignoreCase = false)
        }
        console.writeInfo("Resource read call count: $readResourceCalls")

        // Check for RESOURCES_READ marker in output
        val resourcesReadMarker = findMarkerValue(output, "RESOURCES_READ", "Resources read")
        console.writeInfo("RESOURCES_READ marker value: $resourcesReadMarker")

        console.writeStep(text = "Asserting resource reading behavior")

        // Allow non-zero exit codes if the agent still produced the expected output
        val hasResourcesListedMarker = hasAnyMarkerLine(output, "RESOURCES_LISTED", "Resources listed")
        if (result.exitCode != 0 && !hasResourcesListedMarker) {
            console.writeError("Agent exited with code ${result.exitCode}")
            result.assertExitCode(0, message = "Resource reading test")
        }

        check(usedListResources) {
            buildString {
                appendLine("Agent must call ListMcpResourcesTool (or resources/list) to discover available resources.")
                appendLine()
                appendLine("The MCP server instructions in mcp-steroid-info.md tell the agent to")
                appendLine("use ListMcpResourcesTool to browse available resources. If this fails,")
                appendLine("the server instructions are not effectively guiding the agent.")
                appendLine()
                appendLine("Output:\n${combined.take(3000)}")
            }
        }
        console.writeSuccess("ListMcpResourcesTool usage confirmed")

        check(usedReadResources) {
            buildString {
                appendLine("Agent must read resources via ReadMcpResourceTool, resources/read, or steroid_fetch_resource.")
                appendLine()
                appendLine("The prompt explicitly asked the agent to read resources, but no")
                appendLine("resource read calls were found in the decoded log.")
                appendLine()
                appendLine("Output:\n${combined.take(3000)}")
            }
        }
        console.writeSuccess("ReadMcpResourceTool usage confirmed ($readResourceCalls calls)")

        println("=== Resource Reading Metrics ===")
        println("ListMcpResourcesTool used: $usedListResources")
        println("ReadMcpResourceTool calls: $readResourceCalls")
        println("RESOURCES_READ marker: $resourcesReadMarker")
        println("================================")

        console.writeSuccess("Agent reads MCP resources when prompted")
        console.writeHeader("PASSED")
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `claude reads test-skill resource before running tests`() {
        val console = session.console
        val agent = session.aiAgents.claude

        console.writeStep(text = "Running Claude with test-skill resource prompt")

        val prompt = buildString {
            appendLine("# Task: Run the tests in this project")
            appendLine()
            appendLine("The MCP server has guides at mcp-steroid://prompt/test-skill — read it first")
            appendLine("using steroid_fetch_resource or ReadMcpResourceTool to learn the best approach for running tests via the IDE.")
            appendLine()
            appendLine("After reading the resource, use steroid_execute_code to list the test classes")
            appendLine("in the project. You do not need to actually run the tests.")
            appendLine()
            appendLine("## Required Output")
            appendLine()
            appendLine("Print these markers on separate lines:")
            appendLine("RESOURCE_READ: <the URI you read, e.g. mcp-steroid://prompt/test-skill>")
            appendLine("TEST_CLASSES_FOUND: <number of test classes found>")
        }

        val result = agent.runPrompt(prompt, timeoutSeconds = 600).awaitForProcessFinish()
        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        console.writeStep(text = "Validating test-skill resource read")

        // Check the agent read a resource containing "test-skill" (via any resource tool)
        val readTestSkill = combined.contains("test-skill", ignoreCase = false)
                && (combined.contains("ReadMcpResourceTool", ignoreCase = false)
                || combined.contains("resources/read", ignoreCase = false)
                || combined.contains("read_mcp_resource", ignoreCase = false)
                || combined.contains("steroid_fetch_resource", ignoreCase = false))
        console.writeInfo("Read test-skill resource: $readTestSkill")

        // Check for RESOURCE_READ marker
        val resourceReadMarker = findMarkerValue(output, "RESOURCE_READ", "Resource read")
        console.writeInfo("RESOURCE_READ marker: $resourceReadMarker")

        console.writeStep(text = "Asserting test-skill resource was read")

        // Allow non-zero exit codes if the agent still produced the expected output
        val hasResourceMarker = hasAnyMarkerLine(output, "RESOURCE_READ", "Resource read")
        if (result.exitCode != 0 && !hasResourceMarker) {
            console.writeError("Agent exited with code ${result.exitCode}")
            result.assertExitCode(0, message = "Test-skill resource reading test")
        }

        check(readTestSkill) {
            buildString {
                appendLine("Agent must read mcp-steroid://prompt/test-skill via ReadMcpResourceTool or steroid_fetch_resource.")
                appendLine()
                appendLine("The prompt explicitly pointed to this resource URI, but no evidence")
                appendLine("of reading it was found in the decoded log.")
                appendLine()
                appendLine("Output:\n${combined.take(3000)}")
            }
        }
        console.writeSuccess("test-skill resource read confirmed")

        // Verify the agent also used steroid_execute_code (not just read resources)
        assertUsedExecuteCodeEvidence(combined)
        console.writeSuccess("execute_code evidence found")

        println("=== Test-Skill Resource Reading Metrics ===")
        println("test-skill resource read: $readTestSkill")
        println("RESOURCE_READ marker: $resourceReadMarker")
        println("============================================")

        console.writeSuccess("Agent reads test-skill resource before running tests")
        console.writeHeader("PASSED")
    }
}
