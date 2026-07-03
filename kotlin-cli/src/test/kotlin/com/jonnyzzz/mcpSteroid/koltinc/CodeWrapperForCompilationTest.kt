/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

import org.junit.Assert.assertEquals
import org.junit.Test

class CodeWrapperForCompilationTest {

    // Layout-derived offsets (#221): computed from defaultImports.size so a change to the
    // default-import list can never silently skew these tests again.
    private val k = CodeWrapperForCompilation.defaultImports.size

    /** Wrapped line of the i-th (0-based) extracted user import. */
    private fun importLine(i: Int) = k + 3 + i

    /** Wrapped line of the j-th (0-based) non-import user line, with n extracted imports. */
    private fun codeLine(n: Int, j: Int) = k + 11 + n + j

    @Test
    fun `line mapping maps import lines back to original positions`() {
        val code = """
            import foo.Bar

            val x: String = 123
            println(x)
        """.trimIndent()

        val result = CodeWrapperForCompilation.wrap("Test", code)
        val mapping = result.lineMapping

        // "import foo.Bar" is on original line 1 -> wrapped importLine(0).
        val remapped = mapping.remapCompilerOutput("input.kt:${importLine(0)}:1: error: unresolved")
        assertEquals("input.kt:1:1: error: unresolved", remapped)
    }

    @Test
    fun `line mapping maps code lines back to original positions`() {
        val code = """
            import foo.Bar

            val x: String = 123
            println(x)
        """.trimIndent()

        val result = CodeWrapperForCompilation.wrap("Test", code)
        val mapping = result.lineMapping

        // 1 import (n=1). Non-import lines: "" (orig 2, idx 0), "val x..." (orig 3, idx 1),
        // "println(x)" (orig 4, idx 2).
        val remapped3 = mapping.remapCompilerOutput("input.kt:${codeLine(1, 1)}:21: error: type mismatch")
        assertEquals("input.kt:3:21: error: type mismatch", remapped3)

        val remapped4 = mapping.remapCompilerOutput("input.kt:${codeLine(1, 2)}:1: error: unresolved reference")
        assertEquals("input.kt:4:1: error: unresolved reference", remapped4)
    }

    @Test
    fun `line mapping with no imports`() {
        val code = """
            val x: String = 123
            println(x)
        """.trimIndent()

        val result = CodeWrapperForCompilation.wrap("Test", code)
        val mapping = result.lineMapping

        // No user imports (n=0): code idx 0 = orig 1, idx 1 = orig 2.
        val remapped1 = mapping.remapCompilerOutput("input.kt:${codeLine(0, 0)}:21: error: type mismatch")
        assertEquals("input.kt:1:21: error: type mismatch", remapped1)

        val remapped2 = mapping.remapCompilerOutput("input.kt:${codeLine(0, 1)}:1: error: unresolved reference")
        assertEquals("input.kt:2:1: error: unresolved reference", remapped2)
    }

    @Test
    fun `line mapping with multiple imports`() {
        val code = """
            import foo.Bar
            import baz.Qux

            val x = 42
        """.trimIndent()

        val result = CodeWrapperForCompilation.wrap("Test", code)
        val mapping = result.lineMapping

        // 2 import lines (n=2), then "" (orig 3, idx 0) and "val x = 42" (orig 4, idx 1).
        val remappedImport1 = mapping.remapCompilerOutput("input.kt:${importLine(0)}:8: error: unresolved")
        assertEquals("input.kt:1:8: error: unresolved", remappedImport1)

        val remappedImport2 = mapping.remapCompilerOutput("input.kt:${importLine(1)}:8: error: unresolved")
        assertEquals("input.kt:2:8: error: unresolved", remappedImport2)

        val remappedCode = mapping.remapCompilerOutput("input.kt:${codeLine(2, 1)}:5: error: something")
        assertEquals("input.kt:4:5: error: something", remappedCode)
    }

    @Test
    fun `line mapping with single line of code`() {
        val code = "val x: String = 123"
        val result = CodeWrapperForCompilation.wrap("Test", code)
        val mapping = result.lineMapping

        // No imports (n=0), one code line at original line 1.
        val remapped = mapping.remapCompilerOutput("input.kt:${codeLine(0, 0)}:21: error: type mismatch")
        assertEquals("input.kt:1:21: error: type mismatch", remapped)
    }

    @Test
    fun `wrapper boilerplate lines are not remapped`() {
        val code = "val x = 1"
        val result = CodeWrapperForCompilation.wrap("Test", code)
        val mapping = result.lineMapping

        // Line k+4 (n=0) is "class Test {" — a wrapper boilerplate line, absent from the map.
        val input = "input.kt:${k + 4}:1: error: some weird error"
        assertEquals(input, mapping.remapCompilerOutput(input))
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
        // 3 imports (n=3); otherLine[1]="val y = x + 1" (orig 4).
        val remapped = result.lineMapping.remapCompilerOutput("input.kt:${codeLine(3, 1)}:5: error: something on y")
        assertEquals("input.kt:4:5: error: something on y", remapped)

        // import idx 1 -> orig 3
        val remappedImport = result.lineMapping.remapCompilerOutput("input.kt:${importLine(1)}:8: error: unresolved")
        assertEquals("input.kt:3:8: error: unresolved", remappedImport)
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
        // No imports (n=0); "val bad: String = result.length" is orig 7 = idx 6.
        val remapped = result.lineMapping.remapCompilerOutput("input.kt:${codeLine(0, 6)}:23: error: type mismatch")
        assertEquals("input.kt:7:23: error: type mismatch", remapped)
    }

    @Test
    fun `multi-line string literal preserves line mapping`() {
        val code = "val text = \"\"\"\n    line 1\n    line 2\n    line 3\n\"\"\".trimIndent()\nval bad: Int = text\nprintln(text)"

        val result = CodeWrapperForCompilation.wrap("Test", code)
        // No imports (n=0); "val bad: Int = text" is orig 6 = idx 5.
        val remapped = result.lineMapping.remapCompilerOutput("input.kt:${codeLine(0, 5)}:20: error: type mismatch")
        assertEquals("input.kt:6:20: error: type mismatch", remapped)
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
        // 1 import (n=1); otherLine[4]="val f: Int = File(\"x\")" (orig 6).
        val remapped = result.lineMapping.remapCompilerOutput("input.kt:${codeLine(1, 4)}:18: error: type mismatch")
        assertEquals("input.kt:6:18: error: type mismatch", remapped)
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
        // 1 import (n=1); otherLine[6] = "val result: String..." (orig 8);
        // otherLine[8] = "val joined: Int..." (orig 10).
        val compilerOutput = """
            input.kt:${codeLine(1, 6)}:26: error: type mismatch: expected 'String', actual 'Int'
            input.kt:${codeLine(1, 8)}:24: error: type mismatch: expected 'Int', actual 'String'
        """.trimIndent()
        val remapped = result.lineMapping.remapCompilerOutput(compilerOutput)
        val expected = """
            input.kt:8:26: error: type mismatch: expected 'String', actual 'Int'
            input.kt:10:24: error: type mismatch: expected 'Int', actual 'String'
        """.trimIndent()
        assertEquals(expected, remapped)
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
        // 5 imports (n=5); otherLine[2]="val c: String = a + b" (orig 8).
        val remapped = result.lineMapping.remapCompilerOutput("input.kt:${codeLine(5, 2)}:21: error: type mismatch")
        assertEquals("input.kt:8:21: error: type mismatch", remapped)
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
        // No imports (n=0); "val x: Int = msg" is orig 2 = idx 1.
        val remapped = result.lineMapping.remapCompilerOutput("input.kt:${codeLine(0, 1)}:18: error: type mismatch")
        assertEquals("input.kt:2:18: error: type mismatch", remapped)
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
        // 1 import (n=1); "val f: Int = File(\"x\")" is orig 3 = idx 1.
        val remapped = result.lineMapping.remapCompilerOutput("input.kt:${codeLine(1, 1)}:18: error: type mismatch")
        assertEquals("input.kt:3:18: error: type mismatch", remapped)
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
        // 1 import (n=1); otherLine[4] = "val x: Int = \"hello\"" (orig 6).
        val remapped = result.lineMapping.remapCompilerOutput("input.kt:${codeLine(1, 4)}:18: error: type mismatch")
        assertEquals("input.kt:6:18: error: type mismatch", remapped)
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
        // 1 import (n=1); otherLine[1] = "val x: Int = s" (orig 3).
        val remapped = result.lineMapping.remapCompilerOutput("input.kt:${codeLine(1, 1)}:18: error: type mismatch")
        assertEquals("input.kt:3:18: error: type mismatch", remapped)
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
        // 2 imports (n=2); otherLine[4] = "val d: Int = Date()" (orig 7).
        val remapped = result.lineMapping.remapCompilerOutput("input.kt:${codeLine(2, 4)}:18: error: type mismatch")
        assertEquals("input.kt:7:18: error: type mismatch", remapped)
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

        // 1 import (n=1): orig 3 = idx 1, orig 4 = idx 2.
        val compilerOutput = """
            e: input.kt:${codeLine(1, 1)}:21: error: The integer literal does not conform to the expected type 'String'
            e: input.kt:${codeLine(1, 2)}:18: error: The literal does not conform to the expected type 'Int'
        """.trimIndent()

        val remapped = result.lineMapping.remapCompilerOutput(compilerOutput)
        val expected = """
            e: input.kt:3:21: error: The integer literal does not conform to the expected type 'String'
            e: input.kt:4:18: error: The literal does not conform to the expected type 'Int'
        """.trimIndent()
        assertEquals(expected, remapped)
    }
}
