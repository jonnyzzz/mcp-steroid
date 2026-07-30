/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import org.junit.Assert.assertEquals
import org.junit.Test

class LineMappingTest {

    @Test
    fun `single error line is remapped correctly`() {
        val mapping = LineMapping(mapOf(23 to 1))
        val input = "input.kt:23:21: error: type mismatch"
        val expected = "input.kt:1:21: error: type mismatch"
        assertEquals(expected, mapping.remapCompilerOutput(input))
    }

    @Test
    fun `multiple errors in one output are all remapped`() {
        val mapping = LineMapping(mapOf(23 to 1, 24 to 2, 25 to 3))
        val input = """
            input.kt:23:5: error: type mismatch
            input.kt:24:10: error: unresolved reference
            input.kt:25:1: warning: unused variable
        """.trimIndent()
        val expected = """
            input.kt:1:5: error: type mismatch
            input.kt:2:10: error: unresolved reference
            input.kt:3:1: warning: unused variable
        """.trimIndent()
        assertEquals(expected, mapping.remapCompilerOutput(input))
    }

    @Test
    fun `lines not in mapping are left unchanged`() {
        val mapping = LineMapping(mapOf(23 to 1))
        // Line 10 is a wrapper boilerplate line, not in the mapping
        val input = "input.kt:10:5: error: some wrapper error"
        assertEquals(input, mapping.remapCompilerOutput(input))
    }

    @Test
    fun `remapLine maps user lines and returns null for wrapper boilerplate`() {
        // The structured-location path (BTA CompilerMessageRenderer.SourceLocation)
        // remaps line numbers directly — there is no `file:line:col:` text to match.
        val mapping = LineMapping(mapOf(23 to 1, 24 to 2))
        assertEquals(1, mapping.remapLine(23))
        assertEquals(2, mapping.remapLine(24))
        assertEquals(null, mapping.remapLine(10))
    }

    @Test
    fun `column numbers are preserved`() {
        val mapping = LineMapping(mapOf(23 to 1))
        val input = "input.kt:23:42: error: something wrong"
        val expected = "input.kt:1:42: error: something wrong"
        assertEquals(expected, mapping.remapCompilerOutput(input))
    }

    @Test
    fun `non-matching lines pass through unchanged`() {
        val mapping = LineMapping(mapOf(23 to 1))
        val input = "some random compiler message without file reference"
        assertEquals(input, mapping.remapCompilerOutput(input))
    }

    @Test
    fun `mixed matching and non-matching lines`() {
        val mapping = LineMapping(mapOf(23 to 1, 24 to 2))
        val input = """
            w: input.kt:23:5: warning: unused
            some other message
            e: input.kt:24:10: error: type mismatch
        """.trimIndent()
        val expected = """
            w: input.kt:1:5: warning: unused
            some other message
            e: input.kt:2:10: error: type mismatch
        """.trimIndent()
        assertEquals(expected, mapping.remapCompilerOutput(input))
    }

    @Test
    fun `custom file name is supported`() {
        val mapping = LineMapping(mapOf(23 to 1))
        val input = "script.kt:23:5: error: type mismatch"
        val expected = "script.kt:1:5: error: type mismatch"
        assertEquals(expected, mapping.remapCompilerOutput(input, fileName = "script.kt"))
    }

    @Test
    fun `identity mapping leaves everything unchanged`() {
        val input = "input.kt:23:5: error: type mismatch"
        assertEquals(input, LineMapping.IDENTITY.remapCompilerOutput(input))
    }

    @Test
    fun `empty output is handled`() {
        val mapping = LineMapping(mapOf(23 to 1))
        assertEquals("", mapping.remapCompilerOutput(""))
    }

    @Test
    fun `multiple references on same line are all remapped`() {
        val mapping = LineMapping(mapOf(23 to 1, 25 to 3))
        // This could happen in verbose output that references two locations on one line
        val input = "input.kt:23:5: see also input.kt:25:10:"
        val expected = "input.kt:1:5: see also input.kt:3:10:"
        assertEquals(expected, mapping.remapCompilerOutput(input))
    }

    // --- remapStackTrace tests ---

    @Test
    fun `remapStackTrace remaps line numbers in stack trace format`() {
        val mapping = LineMapping(mapOf(23 to 1, 24 to 2))
        val trace = "java.lang.IllegalStateException: boom\n\tat Script.method(input.kt:23)\n\tat Script.run(input.kt:24)"
        val remapped = mapping.remapStackTrace(trace)
        assertEquals(
            "java.lang.IllegalStateException: boom\n\tat Script.method(input.kt:1)\n\tat Script.run(input.kt:2)",
            remapped
        )
    }

    @Test
    fun `remapStackTrace leaves unmapped lines unchanged`() {
        val mapping = LineMapping(mapOf(23 to 1))
        val trace = "\tat Script.method(input.kt:10)"
        assertEquals(trace, mapping.remapStackTrace(trace))
    }

    @Test
    fun `remapStackTrace handles mixed mapped and unmapped frames`() {
        val mapping = LineMapping(mapOf(23 to 1))
        val trace = """
            java.lang.RuntimeException: test
            	at Script.method(input.kt:23)
            	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)
            	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:108)
        """.trimIndent()
        val expected = """
            java.lang.RuntimeException: test
            	at Script.method(input.kt:1)
            	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)
            	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:108)
        """.trimIndent()
        assertEquals(expected, mapping.remapStackTrace(trace))
    }

    @Test
    fun `remapStackTrace with custom file name`() {
        val mapping = LineMapping(mapOf(23 to 1))
        val trace = "\tat Script.method(script.kt:23)"
        val expected = "\tat Script.method(script.kt:1)"
        assertEquals(expected, mapping.remapStackTrace(trace, fileName = "script.kt"))
    }

    @Test
    fun `remapStackTrace does not match other filenames`() {
        val mapping = LineMapping(mapOf(23 to 1))
        val trace = "\tat Script.method(other.kt:23)"
        // Default fileName is input.kt, so other.kt:23 should NOT be remapped
        assertEquals(trace, mapping.remapStackTrace(trace))
    }

    @Test
    fun `remapStackTrace identity mapping leaves everything unchanged`() {
        val trace = "\tat Script.method(input.kt:23)"
        assertEquals(trace, LineMapping.IDENTITY.remapStackTrace(trace))
    }

    @Test
    fun `remapStackTrace handles empty input`() {
        val mapping = LineMapping(mapOf(23 to 1))
        assertEquals("", mapping.remapStackTrace(""))
    }

    @Test
    fun `remapStackTrace handles Caused by chains`() {
        val mapping = LineMapping(mapOf(23 to 1, 25 to 3))
        val trace = """
            java.lang.RuntimeException: wrapper
            	at Script.run(input.kt:25)
            Caused by: java.lang.IllegalStateException: boom
            	at Script.method(input.kt:23)
        """.trimIndent()
        val expected = """
            java.lang.RuntimeException: wrapper
            	at Script.run(input.kt:3)
            Caused by: java.lang.IllegalStateException: boom
            	at Script.method(input.kt:1)
        """.trimIndent()
        assertEquals(expected, mapping.remapStackTrace(trace))
    }

    // ============================================================
    // cleanStackTrace tests
    // ============================================================

    @Test
    fun `cleanStackTrace keeps only user frames and exception message`() {
        val mapping = LineMapping(mapOf(23 to 1))
        val trace = """
            java.lang.IllegalStateException: boom
            	at Script.method(input.kt:23)
            	at Script.invoke(input.kt:19)
            	at com.jonnyzzz.mcpSteroid.execution.ScriptExecutor.run(ScriptExecutor.kt:115)
            	at kotlinx.coroutines.scheduling.CoroutineScheduler.run(CoroutineScheduler.kt:762)
        """.trimIndent()
        val cleaned = mapping.cleanStackTrace(trace)
        val expected = """
            java.lang.IllegalStateException: boom
            	at Script.method(input.kt:1)
        """.trimIndent()
        assertEquals(expected, cleaned)
    }

    @Test
    fun `cleanStackTrace keeps multiple user frames`() {
        val mapping = LineMapping(mapOf(23 to 1, 24 to 2, 25 to 3))
        val trace = """
            java.lang.NullPointerException
            	at Script.bar(input.kt:25)
            	at Script.foo(input.kt:24)
            	at Script.main(input.kt:23)
            	at Wrapper.invoke(input.kt:19)
            	at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:100)
        """.trimIndent()
        val cleaned = mapping.cleanStackTrace(trace)
        val expected = """
            java.lang.NullPointerException
            	at Script.bar(input.kt:3)
            	at Script.foo(input.kt:2)
            	at Script.main(input.kt:1)
        """.trimIndent()
        assertEquals(expected, cleaned)
    }

    @Test
    fun `cleanStackTrace strips wrapper frames with unmapped input-kt lines`() {
        val mapping = LineMapping(mapOf(23 to 1))
        val trace = """
            java.lang.IllegalStateException: boom
            	at Script.method(input.kt:23)
            	at Script$${'$'}invoke$${'$'}1.invokeSuspend(input.kt:19)
            	at Script$${'$'}invoke$${'$'}1.invoke(input.kt)
        """.trimIndent()
        val cleaned = mapping.cleanStackTrace(trace)
        // Line 19 is wrapper (not mapped), "input.kt" without line number is also filtered
        val expected = """
            java.lang.IllegalStateException: boom
            	at Script.method(input.kt:1)
        """.trimIndent()
        assertEquals(expected, cleaned)
    }

    @Test
    fun `cleanStackTrace preserves Caused-by chain`() {
        val mapping = LineMapping(mapOf(23 to 1, 24 to 2))
        val trace = """
            java.lang.RuntimeException: wrapper
            	at Script.run(input.kt:24)
            	at Wrapper.invoke(input.kt:19)
            Caused by: java.lang.IllegalStateException: root
            	at Script.inner(input.kt:23)
            	at kotlinx.coroutines.stuff(Coroutines.kt:50)
        """.trimIndent()
        val cleaned = mapping.cleanStackTrace(trace)
        val expected = """
            java.lang.RuntimeException: wrapper
            	at Script.run(input.kt:2)
            Caused by: java.lang.IllegalStateException: root
            	at Script.inner(input.kt:1)
        """.trimIndent()
        assertEquals(expected, cleaned)
    }

    @Test
    fun `cleanStackTrace with inline function line numbers outside file range`() {
        // Inline functions in Kotlin can produce line numbers from the call site,
        // which may be very high numbers not in our mapping (e.g., from stdlib or
        // other inlined code). These should be stripped.
        val mapping = LineMapping(mapOf(23 to 1, 24 to 2))
        val trace = """
            java.lang.IllegalStateException: fail
            	at Script.method(input.kt:23)
            	at kotlin.collections.CollectionsKt___CollectionsKt.forEach(input.kt:5678)
            	at Script.caller(input.kt:24)
            	at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:34)
        """.trimIndent()
        val cleaned = mapping.cleanStackTrace(trace)
        // Line 5678 is from an inlined stdlib function — not in mapping, stripped
        val expected = """
            java.lang.IllegalStateException: fail
            	at Script.method(input.kt:1)
            	at Script.caller(input.kt:2)
        """.trimIndent()
        assertEquals(expected, cleaned)
    }

    @Test
    fun `cleanStackTrace with inline require or check`() {
        // require() and check() are inline functions that embed the call-site line
        // in input.kt even though the actual throw is in stdlib
        val mapping = LineMapping(mapOf(23 to 1, 24 to 2, 25 to 3))
        val trace = """
            java.lang.IllegalArgumentException: requirement failed
            	at Script.method(input.kt:24)
            	at Script.main(input.kt:23)
            	at Wrapper.addBlock(input.kt:19)
        """.trimIndent()
        val cleaned = mapping.cleanStackTrace(trace)
        val expected = """
            java.lang.IllegalArgumentException: requirement failed
            	at Script.method(input.kt:2)
            	at Script.main(input.kt:1)
        """.trimIndent()
        assertEquals(expected, cleaned)
    }

    @Test
    fun `cleanStackTrace identity mapping passes through all input-kt frames`() {
        val mapping = LineMapping.IDENTITY
        val trace = """
            java.lang.RuntimeException: test
            	at Script.method(input.kt:23)
            	at Framework.run(Framework.kt:100)
        """.trimIndent()
        val cleaned = mapping.cleanStackTrace(trace)
        // IDENTITY has empty map, so no input.kt frames are mapped → all stripped
        // Only the exception message line survives
        assertEquals("java.lang.RuntimeException: test", cleaned)
    }
}
