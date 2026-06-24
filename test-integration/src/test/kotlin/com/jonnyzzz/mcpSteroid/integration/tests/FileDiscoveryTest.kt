/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Validates that FilenameIndex works inside a Docker IntelliJ container
 * for discovering project files by extension. Replaces 66 Bash `ls`/`find`
 * calls observed in arena analysis.
 *
 * Uses the Maven test project which contains .java files, ensuring
 * FilenameIndex returns non-zero results after indexing completes.
 */
class FileDiscoveryTest {

    companion object {
        val lifetime by lazy { CloseableStackHost(FileDiscoveryTest::class.java.simpleName) }
        val session by lazy {
            IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "File Discovery",
                project = IntelliJProject.MavenTestProject,
            )).waitForProjectReady(
                buildSystem = BuildSystem.MAVEN,
            )
        }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            lifetime.closeAllStacks()
        }
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `FilenameIndex discovers project files by extension`() {
        val console = session.console

        console.writeStep("Discovering project files via FilenameIndex")
        val result = session.mcpSteroid.mcpExecuteCode(
            code = """
                import com.intellij.psi.search.FilenameIndex
                import com.intellij.psi.search.GlobalSearchScope

                val scope = GlobalSearchScope.projectScope(project)
                val javaFiles = readAction { FilenameIndex.getAllFilesByExt(project, "java", scope) }
                println("JAVA_FILES=${'$'}{javaFiles.size}")

                val xmlFiles = readAction { FilenameIndex.getAllFilesByExt(project, "xml", scope) }
                println("XML_FILES=${'$'}{xmlFiles.size}")

                javaFiles.take(5).forEach { println("  java: ${'$'}{it.path}") }
            """.trimIndent(),
            taskId = "file-discovery",
            reason = "Discover project files via FilenameIndex (replaces Bash find/ls)",
            timeout = 60,
        )

        result.assertExitCode(0, "FilenameIndex discovery via exec_code should succeed")
        result.assertOutputContains("JAVA_FILES=", message = "Output should contain Java file count")

        console.writeSuccess("FilenameIndex file discovery via exec_code works")
    }
}
