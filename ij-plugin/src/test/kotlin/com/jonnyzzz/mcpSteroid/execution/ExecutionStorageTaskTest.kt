/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.service
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.getExecutionIdFromResult
import com.jonnyzzz.mcpSteroid.setServerPortProperties
import com.jonnyzzz.mcpSteroid.testExecParams
import com.jonnyzzz.mcpSteroid.server.NoOpProgressReporter
import com.jonnyzzz.mcpSteroid.server.backendNameForMarker
import com.jonnyzzz.mcpSteroid.storage.storagePaths
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

class ExecutionStorageTaskTest : BasePlatformTestCase() {
    private val manager: ExecutionManager get() = project.service()

    // Run tests off the EDT so `timeoutRunBlocking` doesn't park the dispatch
    // thread while ScriptExecutor's pre-flight (isModalEdt / commit) dispatches
    // back to the EDT — otherwise the withContext(EDT) inside the execution
    // deadlocks against the EDT blocked in runBlocking.
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        setServerPortProperties()
        super.setUp()
    }

    /**
     * Gets the execution directory path for a given execution ID.
     */
    private fun getExecutionDir(executionId: String): Path {
        return project.storagePaths.getGetMcpRunDir().resolve(executionId)
    }

    fun testDefaultStorageLivesUnderMcpSteroidHome() {
        // The Gradle test JVM isolates execution storage via the
        // -Dmcp.steroid.storage.path system property (see ij-plugin/build.gradle.kts),
        // and the registry falls back to that property whenever no user value is
        // stored. Clear it for this test so the true production default is observable.
        val isolationProperty = System.clearProperty("mcp.steroid.storage.path")
        try {
            // setValue busts the RegistryValue string cache left by earlier tests; an
            // empty value equals the bundled default, so the platform drops the stored
            // entry and resolution falls through to the (cleared) system property and
            // then to the bundled default "".
            Registry.get("mcp.steroid.storage.path").setValue("", testRootDisposable)
            val expected = Path.of(System.getProperty("user.home"), ".mcp-steroid", "runs")
            assertEquals(expected, project.storagePaths.getGetMcpRunDir())
        } finally {
            if (isolationProperty != null) {
                System.setProperty("mcp.steroid.storage.path", isolationProperty)
            }
        }
    }

    /**
     * Pins the JVM-wide test isolation contract: ij-plugin's Gradle Test tasks pass
     * `-Dmcp.steroid.storage.path=<build-dir temp>` (see `ij-plugin/build.gradle.kts`),
     * and the registry resolves that system property whenever no user value is stored.
     * Without it, EVERY execution-running test class in this module would persist real
     * execution folders into the developer's actual `~/.mcp-steroid/runs`.
     */
    fun testExecutionStorageIsIsolatedFromRealUserHome() {
        val realRuns = Path.of(System.getProperty("user.home"), ".mcp-steroid", "runs")
        val actual = project.storagePaths.getGetMcpRunDir()
        assertFalse(
            "Test-run execution storage must not point at the real $realRuns — " +
                "the mcp.steroid.storage.path isolation property is missing (run via Gradle, " +
                "or check ij-plugin/build.gradle.kts tasks.withType<Test> block)",
            actual.startsWith(realRuns),
        )
    }

    fun testSuccessFileAndWrappedScriptCreated(): Unit = timeoutRunBlocking(30.seconds) {
        val code = "println(\"Success Test\")"
        val result = manager.executeWithProgress(testExecParams(code), NoOpProgressReporter)

        assertFalse("Execution should not fail", result.isError)

        val executionId = getExecutionIdFromResult(result)
        val execDir = getExecutionDir(executionId)

        assertTrue("Execution directory should exist: $execDir", execDir.exists())
        assertTrue("Execution ID should include the project name and direct-plugin backend kind: $executionId",
            executionId.contains("-${project.name}-s-"))

        val backendName = backendNameForMarker(
            pid = ProcessHandle.current().pid(),
            build = ApplicationInfo.getInstance().build.asString(),
        )
        assertEquals(backendName, execDir.resolve("backend_name.txt").readText().trim())

        val successFile = execDir.resolve("success.txt")
        assertTrue("success.txt should exist", successFile.exists())
        assertEquals("Execution successful", successFile.readText())

        val wrappedScript = execDir.resolve("script-wrapped.kts")
        assertTrue("script-wrapped.kts should exist", wrappedScript.exists())
        assertTrue("wrapped script should contain imports", wrappedScript.readText().contains("import com.intellij.openapi.project.*"))
    }

    fun testCompilationErrorInOutputJsonl(): Unit = timeoutRunBlocking(30.seconds) {
        val invalidCode = "invalid kotlin code here"
        val result = manager.executeWithProgress(testExecParams(invalidCode), NoOpProgressReporter)

        assertTrue("Execution should fail", result.isError)

        val executionId = getExecutionIdFromResult(result)
        val execDir = getExecutionDir(executionId)

        assertTrue("Execution directory should exist: $execDir", execDir.exists())

        val outputJsonl = execDir.resolve("output.jsonl")
        assertTrue("output.jsonl should exist", outputJsonl.exists())

        val outputContent = outputJsonl.readText()
        assertTrue("output.jsonl should contain FAILED message", outputContent.contains("FAILED"))
        assertTrue(
            "output.jsonl should contain compilation error info:\n$outputContent",
            outputContent.contains("Script compilation/evaluation failed") || outputContent.contains("Compiler Errors/Warnings"))
    }

    fun testCompilationErrorIncludesExecuteWrapperMigrationHintForExecuteSteroidCode(): Unit = timeoutRunBlocking(30.seconds) {
        assertMigrationHintForLegacyExecuteLabel("executeSteroidCode")
    }

    fun testCompilationErrorIncludesExecuteWrapperMigrationHintForExecuteSuspend(): Unit = timeoutRunBlocking(30.seconds) {
        assertMigrationHintForLegacyExecuteLabel("executeSuspend")
    }

    private suspend fun assertMigrationHintForLegacyExecuteLabel(labelName: String) {
        val invalidCode = """
            println("before")
            return@$labelName
        """.trimIndent()

        val result = manager.executeWithProgress(testExecParams(invalidCode), NoOpProgressReporter)
        assertTrue("Execution should fail", result.isError)

        val output = result.content
            .filterIsInstance<ContentItem.Text>()
            .joinToString("\n") { it.text }

        assertTrue(
            "Output should include a migration hint for legacy execute wrapper labels:\n$output",
            output.contains("Do not use return@executeSteroidCode or return@executeSuspend")
        )
    }
}
