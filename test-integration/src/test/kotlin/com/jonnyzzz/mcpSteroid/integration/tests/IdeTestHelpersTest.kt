package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.parseDockerHostPathMappings
import com.jonnyzzz.mcpSteroid.integration.infra.remapPathForDockerHost
import com.jonnyzzz.mcpSteroid.integration.infra.resolveJavaHomeLookup
import com.jonnyzzz.mcpSteroid.process.ProcessResultValue
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
}
