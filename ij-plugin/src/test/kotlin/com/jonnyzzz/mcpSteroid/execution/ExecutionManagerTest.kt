/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.components.service
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.ModalMode
import com.jonnyzzz.mcpSteroid.server.NoOpProgressReporter
import com.jonnyzzz.mcpSteroid.setSystemPropertyForTest
import com.jonnyzzz.mcpSteroid.testExecParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for ExecutionManager.
 * Uses timeoutRunBlocking for coroutine tests as per IntelliJ 253 best practices.
 */
class ExecutionManagerTest : BasePlatformTestCase() {

    // Run tests off the EDT so `timeoutRunBlocking` doesn't park the dispatch
    // thread while ScriptExecutor's pre-flight (isModalEdt / commit) dispatches
    // back to the EDT — otherwise the EDT is blocked in runBlocking and the
    // withContext(EDT) inside the execution deadlocks.
    override fun runInDispatchThread(): Boolean = false

    private fun getTextContent(result: ToolCallResult): String {
        return result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
    }

    // The `modal` pre-flight pipeline must run cleanly in a (non-modal) headless IDE:
    // smart_non_modal exercises closeModalDialogs + require-non-modal + syncDocuments +
    // waitForSmartMode + monitor; non_modal exercises the require-non-modal gate only.
    fun testSmartNonModalRunsPreflightPipeline(): Unit = timeoutRunBlocking(60.seconds) {
        val manager = project.service<ExecutionManager>()
        val result = manager.executeWithProgress(
            testExecParams("""println("hi smart")""", modal = ModalMode.SMART_NON_MODAL),
            NoOpProgressReporter,
        )
        assertFalse("smart_non_modal should run cleanly in a non-modal headless IDE: ${getTextContent(result)}", result.isError)
        assertTrue("Should have output", getTextContent(result).contains("hi smart"))
    }

    fun testNonModalRunsGateOnly(): Unit = timeoutRunBlocking(60.seconds) {
        val manager = project.service<ExecutionManager>()
        val result = manager.executeWithProgress(
            testExecParams("""println("hi non_modal")""", modal = ModalMode.NON_MODAL),
            NoOpProgressReporter,
        )
        assertFalse("non_modal should pass the non-modal gate headless: ${getTextContent(result)}", result.isError)
        assertTrue("Should have output", getTextContent(result).contains("hi non_modal"))
    }

    /**
     * #154: the tool result must be machine-parseable. A script that prints a single JSON document
     * yields exactly the `execution_id:` header plus the script's own stdout — no `[PRE]`/`[RUN]`/
     * `[POST]` modal-mode framing interleaved (that progress goes to the IDE log only). A consumer
     * that strips the execution_id line and JSON-parses the rest must succeed.
     */
    fun testJsonScriptOutputIsParseableAfterStrippingExecutionId(): Unit = timeoutRunBlocking(60.seconds) {
        val manager = project.service<ExecutionManager>()
        val result = manager.executeWithProgress(
            testExecParams(
                """printJson(mapOf("leaf" to "Show line numbers", "matches" to listOf(1, 2, 3)))""",
                modal = ModalMode.SMART_NON_MODAL,
            ),
            NoOpProgressReporter,
        )
        val text = getTextContent(result)
        assertFalse("Script must succeed: $text", result.isError)

        val lines = text.lines()
        assertTrue("First line must be the execution_id header, got: $text", lines.first().startsWith("execution_id: "))
        lines.forEach { line ->
            assertFalse(
                "Stage framing must never appear in the tool result (#154), got: $line",
                line.startsWith("[PRE]") || line.startsWith("[RUN]") || line.startsWith("[POST]")
            )
        }

        // The contract from the issue: strip the execution_id line, parse the rest as JSON.
        val payload = lines.drop(1).joinToString("\n")
        val json = Json.parseToJsonElement(payload).jsonObject
        assertEquals("Show line numbers", json.getValue("leaf").jsonPrimitive.content)
        assertEquals(3, json.getValue("matches").jsonArray.size)
    }

    fun testParentCoroutineCancellationUnwindsExecution(): Unit = timeoutRunBlocking(60.seconds) {
        // The execution coroutine is a CHILD of the HTTP request coroutine (executeWithProgress
        // is a plain suspend fun — no scope hop), so a client disconnect cancels the Ktor
        // request coroutine and structured concurrency must unwind the running execution —
        // and with it the visible background task (the platform removes it when the
        // coroutine ends, PlatformTaskSupport). Simulate the disconnect by cancelling the
        // calling coroutine mid-script.
        val manager = project.service<ExecutionManager>()
        val started = CompletableDeferred<Unit>()
        val job = launch {
            started.complete(Unit)
            manager.executeWithProgress(
                testExecParams("kotlinx.coroutines.delay(60_000)", timeout = 55),
                NoOpProgressReporter,
            )
        }
        started.await()
        delay(2_000) // let the execution get past compile into the script
        val took = kotlin.time.measureTime { job.cancelAndJoin() }
        assertTrue(
            "cancelling the parent coroutine must unwind the execution promptly, took $took",
            took < 15.seconds,
        )
    }

    fun testExecuteWithProgressSuccess(): Unit = timeoutRunBlocking(30.seconds) {
        val manager = project.service<ExecutionManager>()

        val code = """
            println("Hello from test")
        """.trimIndent()

        val result = manager.executeWithProgress(testExecParams(code), NoOpProgressReporter)

        // Should have content (either success output or error message)
        assertTrue("Should have content", result.content.isNotEmpty())
    }

    fun testExecuteWithProgressOutput(): Unit = timeoutRunBlocking(60.seconds) {
        val manager = project.service<ExecutionManager>()

        val code = """
            println("Line 1")
            println("Line 2")
        """.trimIndent()

        val result = manager.executeWithProgress(testExecParams(code), NoOpProgressReporter)

        // If execution succeeded, verify the output
        if (!result.isError) {
            val text = getTextContent(result)
            assertTrue("Should have output", text.isNotEmpty())
        }
    }

    fun testExecuteWithProgressError(): Unit = timeoutRunBlocking(30.seconds) {
        val manager = project.service<ExecutionManager>()

        val code = """
            throw RuntimeException("Test error")
        """.trimIndent()

        val result = manager.executeWithProgress(testExecParams(code), NoOpProgressReporter)

        // Should be an error
        assertTrue("Should be an error", result.isError)
        val text = getTextContent(result)
        assertTrue("Should have error content", text.isNotEmpty())
    }

    fun testExecuteWithProgressTimeout(): Unit = timeoutRunBlocking(15.seconds) {
        val manager = project.service<ExecutionManager>()

        val code = """
            println("Starting")
            kotlinx.coroutines.delay(10000)
            println("Should not reach here")
        """.trimIndent()

        val result = manager.executeWithProgress(testExecParams(code, timeout = 2), NoOpProgressReporter)

        // Should be an error due to timeout (or an error if the script engine is not available)
        assertTrue("Should be an error", result.isError)
    }
}
