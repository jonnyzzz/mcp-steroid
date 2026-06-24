/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IdeDistribution
import com.jonnyzzz.mcpSteroid.integration.infra.IdeProduct
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

class PyCharmMcpExecutionIntegrationTest {
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `kotlin execute_code works in PyCharm lane`() = runWithCloseableStack { lifetime ->
        val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
            "pycharm-agent",
            consoleTitle = "pycharm-mcp-execution",
            distribution = IdeDistribution.Latest(IdeProduct.PyCharm),
        ))

        session.mcpSteroid.mcpExecuteCode(
            code = """
                val doubled = listOf(1, 2, 3).map { it * 2 }
                println("PYCHARM_KOTLIN_OK:${'$'}{doubled.joinToString(",")}")
            """.trimIndent(),
            taskId = "pycharm-kotlin-smoke",
            reason = "Verify Kotlin execution through MCP in PyCharm integration lane",
            timeout = 1_200,
        )
            .assertExitCode(0)
            .assertOutputContains("PYCHARM_KOTLIN_OK:2,4,6")
    }
}
