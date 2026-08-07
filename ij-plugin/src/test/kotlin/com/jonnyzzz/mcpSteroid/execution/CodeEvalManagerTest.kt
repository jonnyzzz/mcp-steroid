/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.TestResultBuilder
import com.jonnyzzz.mcpSteroid.storage.executionStorage
import com.jonnyzzz.mcpSteroid.testExecParams
import org.junit.Assert
import kotlin.time.Duration.Companion.minutes

class CodeEvalManagerTest: BasePlatformTestCase()  {
    override fun runInDispatchThread(): Boolean = false

    fun testSmoke(): Unit = timeoutRunBlocking(5.minutes) {
        val code = """
            println("it works")
        """.trimIndent()
        val testExecParams = testExecParams(code)
        val execId = project.executionStorage.writeNewExecution(testExecParams)
        val result = TestResultBuilder()
        val data = project.codeEvalManager.evalCode(execId, code, result)
        Assert.assertNotNull(data)
    }

    fun testKotlincParametersRegistryReachesCompiler(): Unit = timeoutRunBlocking(5.minutes) {
        // End-to-end pin that mcp.steroid.kotlinc.parameters actually reaches the
        // compiler: -Werror turns the unused-variable warning into a compilation
        // failure. Guards both the registry tokenization ("-flag value" pairs must
        // become argv tokens for applyArgumentStrings) and the wiring itself —
        // with the extras silently dropped, this compile would wrongly succeed.
        Registry.get("mcp.steroid.kotlinc.parameters")
            .setValue("-language-version 2.3 -api-version 2.3 -Werror", testRootDisposable)
        // Unchecked cast — a warning this pipeline provably surfaces (see
        // McpServerIntegrationTest's compiler-warning scenario); -Werror must
        // turn it into a compilation failure.
        val code = """
            val items: List<Any> = listOf("hello", "world")
            val strings: List<String> = items as List<String>
            println(strings.joinToString(","))
        """.trimIndent()
        val execId = project.executionStorage.writeNewExecution(testExecParams(code))
        val result = TestResultBuilder()
        val data = project.codeEvalManager.evalCode(execId, code, result)
        Assert.assertNull("-Werror from the registry must fail a warning-carrying script. Result: $result", data)
        // Pin the SPECIFIC failure: the warnings-as-errors diagnostic — not an
        // argument-parse error, daemon failure, or service init crash.
        val resultText = result.toString()
        Assert.assertTrue(
            "Failure must be the warnings-as-errors diagnostic, got: $resultText",
            resultText.contains("-Werror") || resultText.contains("Unchecked cast", ignoreCase = true),
        )
    }

    fun testDefaultCompileEmitsNoScriptingPluginProbeNoise(): Unit = timeoutRunBlocking(5.minutes) {
        // #463: kotlinc's default scripting-plugin auto-probe fails on every
        // compilation (the plugin bundles no kotlin-scripting-* jars) and used to
        // spam DEBUG-severity "Exception on loading scripting plugin:
        // ClassNotFoundException" + "Scripting plugin will not be loaded" lines
        // into the forwarded compiler progress. The registry default carries
        // -Xdisable-default-scripting-plugin to suppress the probe; this pins the
        // end-to-end effect on a plain compile with default settings.
        val code = """
            println("no scripting probe expected")
        """.trimIndent()
        val execId = project.executionStorage.writeNewExecution(testExecParams(code))
        val result = TestResultBuilder()
        val data = project.codeEvalManager.evalCode(execId, code, result)
        Assert.assertNotNull("Trivial script must compile. Result: $result", data)
        val probeLines = (result.progressMessages + result.messages)
            .filter { it.contains("scripting plugin", ignoreCase = true) }
        Assert.assertTrue(
            "Compiler output must not carry the scripting-plugin probe noise, got: $probeLines",
            probeLines.isEmpty(),
        )
    }

    fun testWarningCarryingScriptCompilesWithoutWerror(): Unit = timeoutRunBlocking(5.minutes) {
        // Companion to the -Werror canary: the SAME source must compile fine with
        // the default registry value — proving the canary fails on -Werror alone.
        val code = """
            val items: List<Any> = listOf("hello", "world")
            val strings: List<String> = items as List<String>
            println(strings.joinToString(","))
        """.trimIndent()
        val execId = project.executionStorage.writeNewExecution(testExecParams(code))
        val result = TestResultBuilder()
        val data = project.codeEvalManager.evalCode(execId, code, result)
        Assert.assertNotNull("Warning-carrying script must compile without -Werror. Result: $result", data)
    }

    fun testCompileAnnotatedElementsSearch(): Unit = timeoutRunBlocking(5.minutes) {
        // AnnotatedElementsSearch is in the intellij.java.indexing content module.
        // In production IDEs (2025.3+), this module has its own classloader whose JARs
        // may be missing from the kotlinc compile classpath. This test verifies that
        // scripts importing content module classes compile successfully.
        // See https://github.com/jonnyzzz/mcp-steroid/issues/16
        //
        // NOTE: In the test sandbox, content modules share the main plugin classloader,
        // so this test passes. In a production IDE, this import fails with
        // "unresolved reference 'AnnotatedElementsSearch'" unless the fix is applied.
        // This test guards against regressions and will catch the bug if the sandbox
        // starts supporting content module splitting.
        val code = """
            import com.intellij.psi.search.searches.AnnotatedElementsSearch
            import com.intellij.psi.search.GlobalSearchScope
            import com.intellij.psi.JavaPsiFacade

            val scope = GlobalSearchScope.projectScope(project)
            val facade = JavaPsiFacade.getInstance(project)
            val cls = facade.findClass("java.lang.Deprecated", scope)
            if (cls != null) {
                val methods = AnnotatedElementsSearch.searchPsiMethods(cls, scope).findAll()
                println("Found ${'$'}{methods.size} deprecated methods")
            } else {
                println("No Deprecated class found (expected in non-Java projects)")
            }
        """.trimIndent()
        val testExecParams = testExecParams(code)
        val execId = project.executionStorage.writeNewExecution(testExecParams)
        val result = TestResultBuilder()
        val data = project.codeEvalManager.evalCode(execId, code, result)
        Assert.assertNotNull("Script using AnnotatedElementsSearch should compile. Result: $result", data)
        Assert.assertFalse("Compilation should not fail. Result: $result", result.isFailed)
    }

}