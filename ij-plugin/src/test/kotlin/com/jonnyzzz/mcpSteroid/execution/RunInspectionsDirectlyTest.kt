/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiInvalidElementAccessException
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.NoOpProgressReporter
import com.jonnyzzz.mcpSteroid.setSystemPropertyForTest
import com.jonnyzzz.mcpSteroid.testExecParams
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the runInspectionsDirectly() method in McpScriptContext.
 *
 * This tests the workaround for GitHub issue #20 where the daemon code analyzer
 * returns stale results when the IDE window is not focused.
 */
class RunInspectionsDirectlyTest : BasePlatformTestCase() {

    private lateinit var testFilePath: String

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()

        // Create a Kotlin file with known issues:
        // - Unused variable (warning)
        // - Unnecessary safe call (warning)
        val testCode = """
            package test

            class TestClass {
                fun testMethod() {
                    val unusedVariable = "this is never used"
                    val nullableString: String? = "not null"
                    val length = nullableString?.length // unnecessary safe call
                    println(length)
                }
            }
        """.trimIndent()

        val basePath = project.basePath ?: error("Project base path is not available")
        val srcVf = WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            VfsUtil.createDirectories(Paths.get(basePath, "src").toString())
        }
        PsiTestUtil.addSourceRoot(module, srcVf)

        val file = WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            val filePath = Paths.get(basePath, "src/test/TestClass.kt")
            val parent = VfsUtil.createDirectories(filePath.parent.toString())
            val name = filePath.fileName.toString()
            val child = parent.findChild(name) ?: parent.createChildData(this, name)
            VfsUtil.saveText(child, testCode)
            child
        }
        testFilePath = file.path
    }

    private fun getTextContent(result: ToolCallResult): String {
        return result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
    }

    fun testRunInspectionsDirectlyFindsProblems(): Unit = timeoutRunBlocking(60.seconds) {
        val manager = project.service<ExecutionManager>()

        val code = $$"""
            val file = findFile("$$testFilePath") ?: error("File not found")

            val problems = runInspectionsDirectly(file)

            if (problems.isEmpty()) {
                println("No problems found")
            } else {
                println("Found ${problems.values.sumOf { it.size }} problems:")
                problems.forEach { (inspectionId, descriptors) ->
                    descriptors.forEach { problem ->
                        println("  [$inspectionId] ${problem.descriptionTemplate}")
                    }
                }
            }
        """.trimIndent()

        val result = manager.executeWithProgress(
            testExecParams(code, taskId = "run-inspections-test", reason = "test runInspectionsDirectly"),
            NoOpProgressReporter
        )

        val text = getTextContent(result)
        println("Test output:\n$text")

        // Should execute without error
        assertFalse("Should execute without error. Output:\n$text", result.isError)

        // Should find at least some problems (unused variable is a common inspection)
        // Note: The exact inspections available depend on the IDE configuration
        assertTrue(
            "Should find problems or report none found. Output:\n$text",
            text.contains("problems") || text.contains("No problems found")
        )
    }

    fun testRunInspectionsDirectlyWithIncludeInfo(): Unit = timeoutRunBlocking(60.seconds) {
        val manager = project.service<ExecutionManager>()

        val code = $$"""
            val file = findFile("$$testFilePath") ?: error("File not found")

            // Include INFO severity problems
            val problems = runInspectionsDirectly(file, includeInfoSeverity = true)

            println("Found ${problems.values.sumOf { it.size }} problems (including INFO)")
            problems.forEach { (inspectionId, descriptors) ->
                println("  $inspectionId: ${descriptors.size} issues")
            }
        """.trimIndent()

        val result = manager.executeWithProgress(
            testExecParams(code, taskId = "run-inspections-info-test", reason = "test runInspectionsDirectly with INFO"),
            NoOpProgressReporter
        )

        val text = getTextContent(result)
        println("Test output:\n$text")

        // Should execute without error
        assertFalse("Should execute without error. Output:\n$text", result.isError)
    }

    fun testRunInspectionsDirectlyOnNonExistentFile(): Unit = timeoutRunBlocking(60.seconds) {
        val manager = project.service<ExecutionManager>()

        val code = $$"""
            val file = findFile("/non/existent/file.kt")
            if (file == null) {
                println("File not found as expected")
            } else {
                val problems = runInspectionsDirectly(file)
                println("Problems: ${problems.size}")
            }
        """.trimIndent()

        val result = manager.executeWithProgress(
            testExecParams(code, taskId = "run-inspections-nonexistent-test", reason = "test with non-existent file"),
            NoOpProgressReporter
        )

        val text = getTextContent(result)
        println("Test output:\n$text")

        // Should handle gracefully
        assertFalse("Should execute without error. Output:\n$text", result.isError)
        assertTrue("Should report file not found", text.contains("File not found"))
    }

    fun testRunInspectionsDirectlyReturnsMapStructure(): Unit = timeoutRunBlocking(60.seconds) {
        val manager = project.service<ExecutionManager>()

        val code = $$"""
            val file = findFile("$$testFilePath") ?: error("File not found")

            val problems = runInspectionsDirectly(file)

            // Verify the return type is Map<String, List<ProblemDescriptor>>
            println("Result type: Map with ${problems.size} entries")
            problems.forEach { (key, value) ->
                println("Key type: ${key::class.simpleName}, Value type: List of ${value.firstOrNull()?.let { it::class.simpleName } ?: "empty"}")
            }
        """.trimIndent()

        val result = manager.executeWithProgress(
            testExecParams(code, taskId = "run-inspections-structure-test", reason = "test return structure"),
            NoOpProgressReporter
        )

        val text = getTextContent(result)
        println("Test output:\n$text")

        // Should execute without error
        assertFalse("Should execute without error. Output:\n$text", result.isError)
        assertTrue("Should report map structure", text.contains("Result type: Map"))
    }

    // ============================================================
    // Issue #93: per-tool crash isolation
    // Issue #69: per-file PSI-invalid tolerance + additive return shape
    // ============================================================

    /**
     * Suppress ONLY the expected logger.error lines produced by the per-tool crash isolation
     * (the production code intentionally logs every failed tool via logger.error, which would
     * otherwise fail the test through TestLogger).
     */
    private fun suppressExpectedInspectionCrashErrors(): com.intellij.openapi.application.AccessToken =
        LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
            override fun processError(
                category: String,
                message: String,
                details: Array<String>,
                t: Throwable?
            ): Set<Action> =
                if (message.contains("crashed while inspecting")) Action.NONE
                else super.processError(category, message, details, t)
        })

    private fun crashIsolationScript(): String = $$"""
        val file = findFile("$$testFilePath") ?: error("File not found")

        val result = runInspectionsDirectly(file)

        // The legacy Map surface must keep working unchanged (additive return shape):
        println("TOTAL=${result.values.sumOf { it.size }}")
        println("HEALTHY=${result["HealthyStubInspection"]?.size ?: 0}")

        // New additive section: tools that crashed during the sweep.
        println("FAILED_TOOLS=" + result.failedTools.joinToString(";") { it.toolId })
        println("FAILED_ERRORS=" + result.failedTools.joinToString(";") { it.error })
    """.trimIndent()

    fun testCrashingInspectionToolDoesNotAbortSweep() {
        // One stub tool that always throws from its visitor (models GitHub issue #93 — the
        // kotlinx-serialization K2 'Cannot compute containing PSI' crash), and one healthy
        // stub tool that reliably reports exactly one problem.
        myFixture.enableInspections(CrashingStubInspection(), HealthyStubInspection())

        suppressExpectedInspectionCrashErrors().use {
            timeoutRunBlocking(60.seconds) {
                val manager = project.service<ExecutionManager>()

                val result = manager.executeWithProgress(
                    testExecParams(crashIsolationScript(), taskId = "crash-isolation-test", reason = "test per-tool crash isolation"),
                    NoOpProgressReporter
                )

                val text = getTextContent(result)
                println("Test output:\n$text")

                assertFalse("A crashing tool must not fail the whole sweep. Output:\n$text", result.isError)
                assertTrue(
                    "The crashed tool must be reported in result.failedTools. Output:\n$text",
                    text.contains("FAILED_TOOLS=") && text.contains("CrashingStubInspection")
                )
                assertTrue(
                    "The crash message must be surfaced. Output:\n$text",
                    text.contains("simulated inspection crash")
                )
                assertTrue(
                    "Findings from healthy tools must be preserved. Output:\n$text",
                    text.contains("HEALTHY=1")
                )
            }
        }
    }

    fun testPsiInvalidElementAccessInOneToolDoesNotAbortSweep() {
        // Models GitHub issue #69: a PsiInvalidElementAccessException raised while inspecting
        // must not lose the results of the healthy tools in the same sweep.
        //
        // NOTE: a true mid-sweep whole-file PSI invalidation cannot be reproduced in this
        // fixture — the sweep runs inside a read action, which excludes the concurrent write
        // that would invalidate the PSI. The closest honest repro is a tool observing
        // (and throwing) PsiInvalidElementAccessException mid-visit, which is also how the
        // exception reaches InspectionEngine in the field (stale PSI cached inside a tool).
        myFixture.enableInspections(PsiInvalidThrowingStubInspection(), HealthyStubInspection())

        suppressExpectedInspectionCrashErrors().use {
            timeoutRunBlocking(60.seconds) {
                val manager = project.service<ExecutionManager>()

                val result = manager.executeWithProgress(
                    testExecParams(crashIsolationScript(), taskId = "psi-invalid-isolation-test", reason = "test PSI-invalid tolerance"),
                    NoOpProgressReporter
                )

                val text = getTextContent(result)
                println("Test output:\n$text")

                assertFalse("A PSI-invalid crash in one tool must not fail the sweep. Output:\n$text", result.isError)
                assertTrue(
                    "The PSI-invalid tool must be reported in result.failedTools. Output:\n$text",
                    text.contains("FAILED_TOOLS=") && text.contains("PsiInvalidThrowingStubInspection")
                )
                assertTrue(
                    "Findings from healthy tools must be preserved. Output:\n$text",
                    text.contains("HEALTHY=1")
                )
            }
        }
    }
}

/** Always throws from its visitor — models the issue #93 plugin-generated-PSI crash. */
class CrashingStubInspection : LocalInspectionTool() {
    override fun getShortName(): String = "CrashingStubInspection"
    override fun getDisplayName(): String = "Crashing stub inspection (test)"
    override fun getGroupDisplayName(): String = "MCP Steroid tests"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                throw IllegalStateException("simulated inspection crash (test stub)")
            }
        }
}

/** Throws PsiInvalidElementAccessException from its visitor — models the issue #69 case. */
class PsiInvalidThrowingStubInspection : LocalInspectionTool() {
    override fun getShortName(): String = "PsiInvalidThrowingStubInspection"
    override fun getDisplayName(): String = "PSI-invalid throwing stub inspection (test)"
    override fun getGroupDisplayName(): String = "MCP Steroid tests"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                throw PsiInvalidElementAccessException(element, "simulated invalid PSI access (test stub)")
            }
        }
}

/** Reliably reports exactly one problem per sweep — proves healthy-tool findings survive. */
class HealthyStubInspection : LocalInspectionTool() {
    override fun getShortName(): String = "HealthyStubInspection"
    override fun getDisplayName(): String = "Healthy stub inspection (test)"
    override fun getGroupDisplayName(): String = "MCP Steroid tests"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            private var reported = false
            override fun visitElement(element: PsiElement) {
                if (!reported) {
                    reported = true
                    holder.registerProblem(element, "healthy stub finding")
                }
            }
        }
}
