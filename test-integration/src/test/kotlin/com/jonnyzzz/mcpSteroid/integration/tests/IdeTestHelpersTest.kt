package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.WaitAbortedError
import com.jonnyzzz.mcpSteroid.integration.infra.findMcpServerStartupFailure
import com.jonnyzzz.mcpSteroid.integration.infra.parseDockerHostPathMappings
import com.jonnyzzz.mcpSteroid.integration.infra.remapPathForDockerHost
import com.jonnyzzz.mcpSteroid.integration.infra.resolveJavaHomeLookup
import com.jonnyzzz.mcpSteroid.integration.infra.waitFor
import com.jonnyzzz.mcpSteroid.testHelper.process.ProcessResultValue
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.File

class IdeTestHelpersTest {
    @Test
    fun `parseDockerHostPathMappings returns empty for blank input`() {
        Assertions.assertEquals(emptyList<Pair<String, String>>(), parseDockerHostPathMappings(null))
        Assertions.assertEquals(emptyList<Pair<String, String>>(), parseDockerHostPathMappings(""))
        Assertions.assertEquals(emptyList<Pair<String, String>>(), parseDockerHostPathMappings("   "))
    }

    @Test
    fun `remapPathForDockerHost remaps matched prefix`() {
        val remapped = remapPathForDockerHost(
            File("/workspace/test-integration/build/test-logs/test"),
            "/workspace=/host-workspace",
        )

        Assertions.assertEquals(
            File("/host-workspace/test-integration/build/test-logs/test").absolutePath,
            remapped.absolutePath,
        )
    }

    @Test
    fun `remapPathForDockerHost keeps path when no mapping matches`() {
        val original = File("/tmp/somewhere")
        val remapped = remapPathForDockerHost(original, "/workspace=/host-workspace")

        Assertions.assertEquals(original.absolutePath, remapped.absolutePath)
    }

    @Test
    fun `remapPathForDockerHost prefers longer source prefix`() {
        val remapped = remapPathForDockerHost(
            File("/workspace/test-integration/build/test-logs/test"),
            "/workspace=/host-workspace,/workspace/test-integration=/host-workspace-special",
        )

        Assertions.assertEquals(
            File("/host-workspace-special/build/test-logs/test").absolutePath,
            remapped.absolutePath,
        )
    }

    @Test
    fun `parseDockerHostPathMappings rejects invalid entries`() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            parseDockerHostPathMappings("/workspace")
        }
    }

    @Test
    fun `resolveJavaHomeLookup accepts emitted path even when process exits non-zero`() {
        val result = ProcessResultValue(1, "/usr/lib/jvm/temurin-25-jdk-arm64\n", "")

        Assertions.assertEquals("/usr/lib/jvm/temurin-25-jdk-arm64", result.resolveJavaHomeLookup("25"))
    }

    @Test
    fun `resolveJavaHomeLookup fails when no path is emitted`() {
        val result = ProcessResultValue(1, "", "JDK 25 not found")

        val error = Assertions.assertThrows(IllegalArgumentException::class.java) {
            result.resolveJavaHomeLookup("25")
        }
        Assertions.assertTrue(error.message!!.contains("JDK 25 not found under /usr/lib/jvm"))
    }

    @Test
    fun `resolveJavaHomeLookup fails when command succeeds without a path`() {
        val result = ProcessResultValue(0, "lookup finished\n", "")

        val error = Assertions.assertThrows(IllegalStateException::class.java) {
            result.resolveJavaHomeLookup("25")
        }
        Assertions.assertTrue(error.message!!.contains("lookup returned no path"))
    }

    @Test
    fun `findMcpServerStartupFailure matches the plugin web-server failure line`() {
        val logs = listOf(
            "INFO - SteroidsMcpServer - Starting MCP Steroid server on 127.0.0.1:6754",
            "ERROR - SteroidsMcpServer - Failed to start MCP server on port 6754: Address already in use",
            "INFO - more output",
        )
        Assertions.assertEquals(logs[1], findMcpServerStartupFailure(logs))
    }

    @Test
    fun `findMcpServerStartupFailure returns null when the server started fine`() {
        Assertions.assertNull(
            findMcpServerStartupFailure(
                listOf("INFO - SteroidsMcpServer - MCP Steroid server started on http://127.0.0.1:6754/mcp"),
            ),
        )
        Assertions.assertNull(findMcpServerStartupFailure(emptyList()))
    }

    @Test
    fun `waitFor aborts immediately on WaitAbortedError, not waiting out the timeout`() {
        val start = System.currentTimeMillis()
        val error = Assertions.assertThrows(WaitAbortedError::class.java) {
            // Large timeout, but the first poll throws WaitAbortedError — it must stop at once.
            waitFor(60_000, "server ready") {
                throw WaitAbortedError("web server failed to start")
            }
        }
        val elapsed = System.currentTimeMillis() - start
        Assertions.assertTrue(elapsed < 5_000, "must abort fast, not wait the timeout (took ${elapsed}ms)")
        Assertions.assertEquals("web server failed to start", error.message)
    }

    @Test
    fun `waitFor keeps retrying transient exceptions until the action succeeds`() {
        var calls = 0
        waitFor(5_000, "becomes ready") {
            calls++
            if (calls < 3) throw RuntimeException("transient")
            true
        }
        Assertions.assertEquals(3, calls, "transient exceptions must be retried, not abort the loop")
    }
}
