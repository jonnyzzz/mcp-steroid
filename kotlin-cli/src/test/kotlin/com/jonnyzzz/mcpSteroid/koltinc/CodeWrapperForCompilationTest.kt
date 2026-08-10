/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeWrapperForCompilationTest {

    /**
     * Drift-proof remap assertion: locate where [snippet] physically lands in the generated
     * code and verify that a compiler error on that wrapped line remaps back to [originalLine].
     *
     * Deriving the wrapped line from the emitted code — instead of hardcoding an offset like
     * "user code starts at wrapped line 23+N" — is the whole point: those hardcoded offsets
     * silently drifted as [CodeWrapperForCompilation.defaultImports] grew over time, so real
     * compiler errors (at the true line) were no longer remapped while these tests, which fed
     * the *stale* line numbers, kept passing. Searching the actual output makes the test track
     * reality and catch any future drift.
     *
     * [snippet] must match exactly one generated line. If it matched several (a too-short or
     * boilerplate-colliding snippet), picking the first would let the test measure — and then
     * "confirm" — the wrong line while still looking meaningful. Demanding a unique match turns
     * that ambiguity into a loud failure instead of a silent false pass.
     */
    private fun assertRemaps(
        result: CodeWrapperForCompilation.WrapResult,
        snippet: String,
        originalLine: Int,
        col: Int = 5,
    ) {
        val matches = result.code.lines().withIndex().filter { it.value.contains(snippet) }
        assertEquals(
            "snippet <$snippet> must match exactly one generated line so the test can't silently " +
                "measure the wrong one; matched ${matches.size}:\n${result.code}",
            1, matches.size,
        )
        val wrappedLine = matches.single().index + 1
        assertEquals(
            "input.kt:$originalLine:$col: error: boom",
            result.lineMapping.remapCompilerOutput("input.kt:$wrappedLine:$col: error: boom"),
        )
    }

    @Test
    fun `a multi-line raw string keeps its exact content`() {
        // The wrapper used to indent every body line by four spaces for cosmetics, which rewrote the
        // CONTENT of any multi-line raw string: continuation lines gained four leading spaces. An agent
        // that built an anchor out of real file text then searched for text that no longer existed, and
        // `content.split(anchor)` found nothing. In the arena that discarded whole 36 KB edit batches on
        // a `check(occurrences == 1)` whose anchor was, in the submitted code, perfectly correct.
        val tripleQuote = "\"\"\""
        val code = listOf(
            "val anchor = $tripleQuote",
            "    @Column(name = \"released_at\")",
            "    private Instant releasedAt;",
            tripleQuote,
            "println(anchor.length)",
        ).joinToString("\n")

        val wrapped = CodeWrapperForCompilation.wrap(className = "Test", code = code).code
        val lines = wrapped.lines()

        assertTrue(
            "the raw string's continuation line must stay at its original indentation:\n$wrapped",
            lines.any { it == "    private Instant releasedAt;" },
        )
        assertTrue(
            "no line inside the raw string may gain indentation:\n$wrapped",
            lines.none { it == "        private Instant releasedAt;" },
        )
        assertTrue(
            "code outside the raw string is still indented for readability:\n$wrapped",
            lines.any { it == "    println(anchor.length)" },
        )
    }

    @Test
    fun `line mapping maps import lines back to original positions`() {
        val code = """
            import foo.Bar

            val x: String = 123
            println(x)
        """.trimIndent()

        val result = CodeWrapperForCompilation.wrap("Test", code)
        assertRemaps(result, "import foo.Bar", originalLine = 1, col = 1)
    }

    @Test
    fun `line mapping maps code lines back to original positions`() {
        val code = """
            import foo.Bar

            val x: String = 123
            println(x)
        """.trimIndent()

        val result = CodeWrapperForCompilation.wrap("Test", code)
        assertRemaps(result, "val x: String = 123", originalLine = 3, col = 21)
        assertRemaps(result, "println(x)", originalLine = 4, col = 1)
    }

    @Test
    fun `line mapping with no imports`() {
        val code = """
            val x: String = 123
            println(x)
        """.trimIndent()

        val result = CodeWrapperForCompilation.wrap("Test", code)
        assertRemaps(result, "val x: String = 123", originalLine = 1, col = 21)
        assertRemaps(result, "println(x)", originalLine = 2, col = 1)
    }

    @Test
    fun `line mapping with multiple imports`() {
        val code = """
            import foo.Bar
            import baz.Qux

            val x = 42
        """.trimIndent()

        val result = CodeWrapperForCompilation.wrap("Test", code)
        assertRemaps(result, "import foo.Bar", originalLine = 1, col = 8)
        assertRemaps(result, "import baz.Qux", originalLine = 2, col = 8)
        assertRemaps(result, "val x = 42", originalLine = 4)
    }

    @Test
    fun `line mapping with single line of code`() {
        val code = "val x: String = 123"
        val result = CodeWrapperForCompilation.wrap("Test", code)
        assertRemaps(result, "val x: String = 123", originalLine = 1, col = 21)
    }

    @Test
    fun `wrapper boilerplate lines are not remapped`() {
        val code = "val x = 1"
        val result = CodeWrapperForCompilation.wrap("Test", code)

        // The generated "class Test {" line is wrapper boilerplate — it must pass through unchanged.
        val classLine = result.code.lines().indexOfFirst { it.contains("class Test {") } + 1
        assertTrue(classLine > 0)
        val input = "input.kt:$classLine:1: error: some weird error"
        assertEquals(input, result.lineMapping.remapCompilerOutput(input))
    }

    @Test
    fun `compiler error on a wrapper-added default import passes through unmapped`() {
        // A default import is boilerplate the wrapper injects, not code the agent wrote, so it
        // is deliberately absent from the mapping. If kotlinc ever flags one of those lines
        // (e.g. an import stops resolving), remap has nothing to translate it to and must leave
        // the reference untouched rather than point at some unrelated user line. This pins that
        // documented behaviour — see review question "what if the error is in the import line
        // which we just added". Changing how such errors are reported must update this test.
        val code = "val x = 1"
        val result = CodeWrapperForCompilation.wrap("Test", code)

        val firstDefaultImport = CodeWrapperForCompilation.defaultImports.first()
        val importLine = result.code.lines().indexOfFirst { it == firstDefaultImport } + 1
        assertTrue("default import <$firstDefaultImport> not found in:\n${result.code}", importLine > 0)

        val input = "input.kt:$importLine:8: error: unresolved reference"
        assertEquals(input, result.lineMapping.remapCompilerOutput(input))
    }

    @Test
    fun `user code line remaps correctly regardless of default-import count`() {
        // The original bug was drift with the number of preamble lines: offsets computed for 12
        // default imports broke once the list grew. Prepend a varying number of *user* imports —
        // which shifts the user code down by exactly that many lines — and assert the error still
        // remaps to the true source line for each count. A future offset-based regression would
        // pass for one count and fail for the others.
        for (extraImports in listOf(0, 1, 3, 12, 25)) {
            val importBlock = (1..extraImports).joinToString("") { "import pkg.Type$it\n" }
            val code = importBlock + "val bad: String = 123"

            val result = CodeWrapperForCompilation.wrap("Test", code)
            assertRemaps(result, "val bad: String = 123", originalLine = extraImports + 1, col = 19)
        }
    }

    @Test
    fun `extractImportsWithLineNumbers tracks line numbers correctly`() {
        val code = """
            import foo.Bar

            val x = 1
            import baz.Qux
            println(x)
        """.trimIndent()

        val extracted = CodeWrapperForCompilation.extractImportsWithLineNumbers(code)

        assertEquals(listOf("import foo.Bar", "import baz.Qux"), extracted.importLines)
        assertEquals(listOf(1, 4), extracted.importLineNumbers)

        assertEquals(listOf("", "val x = 1", "println(x)"), extracted.otherLines)
        assertEquals(listOf(2, 3, 5), extracted.otherLineNumbers)
    }

    @Test
    fun `imports intermixed with code are extracted and lines remapped`() {
        val code = """
            import java.io.File
            val x = 1
            import java.util.Date
            val y = x + 1
            import java.net.URL
            println(y)
        """.trimIndent()

        val extracted = CodeWrapperForCompilation.extractImportsWithLineNumbers(code)
        assertEquals(listOf("import java.io.File", "import java.util.Date", "import java.net.URL"), extracted.importLines)
        assertEquals(listOf(1, 3, 5), extracted.importLineNumbers)
        assertEquals(listOf("val x = 1", "val y = x + 1", "println(y)"), extracted.otherLines)
        assertEquals(listOf(2, 4, 6), extracted.otherLineNumbers)

        val result = CodeWrapperForCompilation.wrap("Test", code)
        assertRemaps(result, "val y = x + 1", originalLine = 4)
        assertRemaps(result, "import java.util.Date", originalLine = 3, col = 8)
    }

    @Test
    fun `inline functions and lambdas preserve line mapping`() {
        val code = """
            val items = listOf(1, 2, 3)
            val mapped = items.map { it * 2 }
            val filtered = mapped.filter {
                it > 3
            }
            val result = filtered.joinToString(",")
            val bad: String = result.length
            println(result)
        """.trimIndent()

        val result = CodeWrapperForCompilation.wrap("Test", code)
        assertRemaps(result, "val bad: String = result.length", originalLine = 7, col = 23)
    }

    @Test
    fun `multi-line string literal preserves line mapping`() {
        val code = "val text = \"\"\"\n    line 1\n    line 2\n    line 3\n\"\"\".trimIndent()\nval bad: Int = text\nprintln(text)"

        val result = CodeWrapperForCompilation.wrap("Test", code)
        assertRemaps(result, "val bad: Int = text", originalLine = 6, col = 20)
    }

    @Test
    fun `imports inside triple-quoted strings are not extracted`() {
        val code = "val sql = \"\"\"\n    import something\n    SELECT * FROM table\n\"\"\".trimIndent()\nimport java.io.File\nval f: Int = File(\"x\")\nprintln(f)"

        val extracted = CodeWrapperForCompilation.extractImportsWithLineNumbers(code)
        // "import something" inside triple-quoted string is NOT an import
        // "import java.io.File" on line 5 IS an import
        assertEquals(listOf("import java.io.File"), extracted.importLines)
        assertEquals(listOf(5), extracted.importLineNumbers)

        // Other lines: lines 1-4 (the triple-quoted string) + line 6 + line 7
        assertEquals(6, extracted.otherLines.size)
        assertEquals(listOf(1, 2, 3, 4, 6, 7), extracted.otherLineNumbers)

        val result = CodeWrapperForCompilation.wrap("Test", code)
        assertRemaps(result, "val f: Int = File(\"x\")", originalLine = 6, col = 18)
    }

    @Test
    fun `complex code with closures and multiple errors`() {
        val code = """
            import java.util.concurrent.atomic.AtomicInteger

            val counter = AtomicInteger(0)
            val incrementer: () -> Unit = {
                counter.incrementAndGet()
            }

            val result: String = counter.get()
            val items = listOf("a", "b", "c")
            val joined: Int = items.joinToString()

            incrementer()
            println(counter.get())
        """.trimIndent()

        val result = CodeWrapperForCompilation.wrap("Test", code)
        assertRemaps(result, "val result: String = counter.get()", originalLine = 8, col = 26)
        assertRemaps(result, "val joined: Int = items.joinToString()", originalLine = 10, col = 24)
    }

    @Test
    fun `many imports scattered through code`() {
        val code = """
            import java.io.File
            import java.util.Date
            val a = 1
            import java.net.URL
            import java.util.UUID
            val b = 2
            import kotlin.math.sqrt
            val c: String = a + b
        """.trimIndent()

        val extracted = CodeWrapperForCompilation.extractImportsWithLineNumbers(code)
        assertEquals(5, extracted.importLines.size)
        assertEquals(listOf(1, 2, 4, 5, 7), extracted.importLineNumbers)
        assertEquals(listOf("val a = 1", "val b = 2", "val c: String = a + b"), extracted.otherLines)
        assertEquals(listOf(3, 6, 8), extracted.otherLineNumbers)

        val result = CodeWrapperForCompilation.wrap("Test", code)
        assertRemaps(result, "val c: String = a + b", originalLine = 8, col = 21)
    }

    @Test
    fun `import keyword inside regular double-quoted string is not extracted`() {
        val code = """
            val msg = "import java.io.File"
            val x: Int = msg
        """.trimIndent()

        val extracted = CodeWrapperForCompilation.extractImportsWithLineNumbers(code)
        // "import java.io.File" is inside a double-quoted string on a line that starts with val
        // so it should NOT be extracted as an import
        assertEquals(emptyList<String>(), extracted.importLines)
        assertEquals(listOf("val msg = \"import java.io.File\"", "val x: Int = msg"), extracted.otherLines)
        assertEquals(listOf(1, 2), extracted.otherLineNumbers)

        val result = CodeWrapperForCompilation.wrap("Test", code)
        assertRemaps(result, "val x: Int = msg", originalLine = 2, col = 18)
    }

    @Test
    fun `import keyword inside single-quoted char is not extracted`() {
        // This is contrived but tests that 'i' char literal doesn't confuse the parser
        val code = """
            val c = 'i'
            import java.io.File
            val f: Int = File("x")
        """.trimIndent()

        val extracted = CodeWrapperForCompilation.extractImportsWithLineNumbers(code)
        assertEquals(listOf("import java.io.File"), extracted.importLines)
        assertEquals(listOf(2), extracted.importLineNumbers)
        assertEquals(listOf("val c = 'i'", "val f: Int = File(\"x\")"), extracted.otherLines)
        assertEquals(listOf(1, 3), extracted.otherLineNumbers)

        val result = CodeWrapperForCompilation.wrap("Test", code)
        assertRemaps(result, "val f: Int = File(\"x\")", originalLine = 3, col = 18)
    }

    @Test
    fun `import inside triple-quoted string spanning multiple lines is not extracted`() {
        val code = "val sql = \"\"\"\n    import users\n    import orders\n    SELECT * FROM users\n\"\"\".trimIndent()\nimport java.io.File\nval f: Int = File(\"x\")"

        val extracted = CodeWrapperForCompilation.extractImportsWithLineNumbers(code)
        // "import users" on line 2 and "import orders" on line 3 are inside """
        // "import java.io.File" on line 6 is a real import
        assertEquals(listOf("import java.io.File"), extracted.importLines)
        assertEquals(listOf(6), extracted.importLineNumbers)
        assertEquals(6, extracted.otherLines.size)
        assertEquals(listOf(1, 2, 3, 4, 5, 7), extracted.otherLineNumbers)
    }

    @Test
    fun `dollar triple-quoted string does not confuse parser`() {
        // Kotlin $""" is just $ followed by """ — the triple quotes still count
        val code = "val s = \$\"\"\"\n    import fake\n    real content\n\"\"\"\nimport java.io.File\nval x: Int = \"hello\""

        val extracted = CodeWrapperForCompilation.extractImportsWithLineNumbers(code)
        // $""" opens a triple-quoted string, """ on line 4 closes it
        // "import fake" on line 2 is inside the string — not extracted
        // "import java.io.File" on line 5 is real
        assertEquals(listOf("import java.io.File"), extracted.importLines)
        assertEquals(listOf(5), extracted.importLineNumbers)

        val result = CodeWrapperForCompilation.wrap("Test", code)
        assertRemaps(result, "val x: Int = \"hello\"", originalLine = 6, col = 18)
    }

    @Test
    fun `string interpolation with import keyword is not extracted`() {
        val code = """
            val pkg = "java.io"
            val msg = "need to import ${'$'}{pkg}.File"
            import java.io.File
            val f: Int = File("x")
        """.trimIndent()

        val extracted = CodeWrapperForCompilation.extractImportsWithLineNumbers(code)
        // Line 2 contains "import" but inside a string, and the line starts with "val"
        // Line 3 is a real import
        assertEquals(listOf("import java.io.File"), extracted.importLines)
        assertEquals(listOf(3), extracted.importLineNumbers)
        assertEquals(listOf("val pkg = \"java.io\"", "val msg = \"need to import \${pkg}.File\"", "val f: Int = File(\"x\")"), extracted.otherLines)
        assertEquals(listOf(1, 2, 4), extracted.otherLineNumbers)
    }

    @Test
    fun `triple-quoted string opened and closed on same line does not affect subsequent imports`() {
        val code = "val s = \"\"\"import fake\"\"\"\nimport java.io.File\nval x: Int = s"

        val extracted = CodeWrapperForCompilation.extractImportsWithLineNumbers(code)
        // Line 1: """import fake""" — opens AND closes triple-quote on same line (count goes 0->1->2, even)
        // Line 2: import java.io.File — NOT inside triple-quote, real import
        assertEquals(listOf("import java.io.File"), extracted.importLines)
        assertEquals(listOf(2), extracted.importLineNumbers)
        assertEquals(listOf("val s = \"\"\"import fake\"\"\"", "val x: Int = s"), extracted.otherLines)
        assertEquals(listOf(1, 3), extracted.otherLineNumbers)

        val result = CodeWrapperForCompilation.wrap("Test", code)
        assertRemaps(result, "val x: Int = s", originalLine = 3, col = 18)
    }

    @Test
    fun `nested triple-quoted strings track state correctly`() {
        // Two triple-quoted strings in sequence
        val code = "val a = \"\"\"first\"\"\"\nval b = \"\"\"second\"\"\"\nimport java.io.File\nval x: Int = a"

        val extracted = CodeWrapperForCompilation.extractImportsWithLineNumbers(code)
        // Line 1: """first""" — triple-quote count: 0->1->2 (even, not inside)
        // Line 2: """second""" — triple-quote count: 2->3->4 (even, not inside)
        // Line 3: real import
        assertEquals(listOf("import java.io.File"), extracted.importLines)
        assertEquals(listOf(3), extracted.importLineNumbers)
        assertEquals(listOf("val a = \"\"\"first\"\"\"", "val b = \"\"\"second\"\"\"", "val x: Int = a"), extracted.otherLines)
        assertEquals(listOf(1, 2, 4), extracted.otherLineNumbers)
    }

    @Test
    fun `multiline triple-quoted string with import on boundary lines`() {
        val code = "import java.util.Date\nval s = \"\"\"\nimport fake.one\nimport fake.two\n\"\"\"\nimport java.io.File\nval d: Int = Date()"

        val extracted = CodeWrapperForCompilation.extractImportsWithLineNumbers(code)
        // Line 1: real import
        // Line 2: val s = """ — opens triple-quote (count 0->1, odd = inside)
        // Line 3: import fake.one — INSIDE triple-quote, NOT extracted
        // Line 4: import fake.two — INSIDE triple-quote, NOT extracted
        // Line 5: """ — closes triple-quote (count 1->2, even = outside)
        // Line 6: real import
        // Line 7: code with error
        assertEquals(listOf("import java.util.Date", "import java.io.File"), extracted.importLines)
        assertEquals(listOf(1, 6), extracted.importLineNumbers)
        assertEquals(5, extracted.otherLines.size)
        assertEquals(listOf(2, 3, 4, 5, 7), extracted.otherLineNumbers)

        val result = CodeWrapperForCompilation.wrap("Test", code)
        assertRemaps(result, "val d: Int = Date()", originalLine = 7, col = 18)
    }

    @Test
    fun `extractImports backward compatibility`() {
        val code = """
            import foo.Bar
            val x = 1
        """.trimIndent()

        val (imports, other) = CodeWrapperForCompilation.extractImports(code)
        assertEquals(listOf("import foo.Bar"), imports)
        assertEquals(listOf("val x = 1"), other)
    }

    @Test
    fun `line mapping end-to-end with realistic compiler output`() {
        val code = """
            import kotlin.math.sqrt

            val x: String = 123
            val y: Int = "hello"
        """.trimIndent()

        val result = CodeWrapperForCompilation.wrap("Script", code)
        assertRemaps(result, "val x: String = 123", originalLine = 3, col = 21)
        assertRemaps(result, "val y: Int = \"hello\"", originalLine = 4, col = 18)
    }
}
