/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.NoOpProgressReporter
import com.jonnyzzz.mcpSteroid.testExecParams
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

/**
 * #213 / #179: the script timeout must reach a blocking `runInspectionsDirectly` sweep.
 *
 * `InspectionEngine.inspectEx` installs the indicator it is handed on its worker threads, so
 * every `ProgressManager.checkCanceled()` inside the inspections consults it. Before #213 that
 * indicator was a throwaway `EmptyProgressIndicator()` nobody ever cancelled — a slow/hanging
 * inspection ran to the end no matter what the script timeout said (the rest-24/service-45
 * wedge from the 2026-06-29 eval). Now the sweep runs under the execution's cancellable
 * [McpScriptContext.progressIndicator], cancelled by the ScriptExecutor watcher the moment
 * the execution job is cancelled.
 *
 * The test registers an inspection that spins in a `checkCanceled()` polling loop for up to
 * [SpinningCheckCanceledInspection.SPIN_CAP_NANOS] (45 s), runs `runInspectionsDirectly` in a
 * script with `timeout = 2`, and asserts the run reports the timeout error while the
 * inspection observed cancellation early (spin duration well under the cap). With a broken
 * bridge the sweep spins the full 45 s and the spin-duration assertion fails.
 */
class RunInspectionsDirectlyTimeoutTest : BasePlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    private lateinit var testFilePath: String

    override fun setUp() {
        super.setUp()
        SpinningCheckCanceledInspection.reset()
        WriteAction.runAndWait<RuntimeException> {
            myFixture.enableInspections(SpinningCheckCanceledInspection())
        }

        // A real on-disk file (not a fixture-only PSI file), so the script's findFile()
        // path resolution works — same setup as RunInspectionsDirectlyTest.
        val testCode = """
            package test

            class SlowInspectedClass {
                fun method() {
                    println("hello")
                }
            }
        """.trimIndent()

        val basePath = project.basePath ?: error("Project base path is not available")
        val file = WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            val filePath = Paths.get(basePath, "src/test/SlowInspectedClass.kt")
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

    fun testScriptTimeoutCancelsBlockingInspectionSweep(): Unit = timeoutRunBlocking(120.seconds) {
        val manager = project.service<ExecutionManager>()

        val code = $$"""
            val file = findFile("$$testFilePath") ?: error("File not found")
            val problems = runInspectionsDirectly(file)
            println("UNREACHABLE: sweep completed with ${problems.size} entries")
        """.trimIndent()

        val result = manager.executeWithProgress(
            testExecParams(
                code,
                taskId = "run-inspections-timeout-test",
                reason = "test #213: timeout must cancel the inspection sweep",
                timeout = 2,
            ),
            NoOpProgressReporter,
        )

        val text = getTextContent(result)
        println("Test output:\n$text")

        assertTrue("Execution must fail with the script timeout. Output:\n$text", result.isError)
        assertTrue("The failure must be the timeout error. Output:\n$text", text.contains("timed out"))
        assertFalse(
            "The sweep must be cancelled, not run to completion. Output:\n$text",
            text.contains("UNREACHABLE")
        )

        val spinNanos = SpinningCheckCanceledInspection.lastSpinNanos.get()
        assertTrue("The spinning inspection must have run (was it enabled?)", spinNanos >= 0)
        assertTrue(
            "The sweep must observe cancellation shortly after the 2s timeout — " +
                "it spun for ${spinNanos / 1_000_000} ms (cap is 45000 ms; a spin near the cap " +
                "means the execution's indicator never reached InspectionEngine.inspectEx)",
            spinNanos < 30_000_000_000L
        )
    }
}

/**
 * A [LocalInspectionTool] that blocks its first visited element in a
 * `ProgressManager.checkCanceled()` polling loop — the misbehaving-inspection shape from
 * #177/#179. The loop can ONLY be ended early through the progress indicator installed by
 * `InspectionEngine.inspectEx` on its worker thread; a job-only cancellation never reaches it.
 */
class SpinningCheckCanceledInspection : LocalInspectionTool() {

    companion object {
        /** Hard cap so a broken cancellation bridge fails the test instead of wedging the JVM. */
        const val SPIN_CAP_NANOS: Long = 45_000_000_000L

        /** Nanoseconds the first visited element actually spun; -1 until the inspection ran. */
        val lastSpinNanos = AtomicLong(-1L)

        private val spinArmed = AtomicBoolean(false)

        fun reset() {
            lastSpinNanos.set(-1L)
            spinArmed.set(true)
        }
    }

    override fun getDisplayName(): String = "MCP spinning checkCanceled inspection (test)"

    override fun getGroupDisplayName(): String = "MCP Steroid tests"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                // Spin once per test: the first visited element blocks; everything after the
                // cancellation (or in an unrelated later sweep) passes through instantly.
                if (!spinArmed.compareAndSet(true, false)) return
                val start = System.nanoTime()
                try {
                    val deadline = start + SPIN_CAP_NANOS
                    while (System.nanoTime() < deadline) {
                        ProgressManager.checkCanceled()
                    }
                } finally {
                    lastSpinNanos.set(System.nanoTime() - start)
                }
            }
        }
    }
}
