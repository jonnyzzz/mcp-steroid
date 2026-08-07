/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin tests for the [formatCsv] and [formatToon] helpers. No IDE
 * infrastructure is needed because the formatters are top-level functions
 * with no IntelliJ dependencies — the `McpScriptContextImpl` overrides are
 * thin shells that delegate here.
 *
 * Coverage targets:
 * - RFC 4180 CSV escaping (commas, quotes, newlines, CRs).
 * - `dictColumns` preamble + ID substitution from issue #35.
 * - TOON shapes from issue #34: primitives, lists of primitives,
 *   array-of-uniform-records (the find-references case), maps, mixed lists,
 *   and null handling.
 * - Contract violations (mismatched row width, empty headers).
 */
class OutputFormattersTest {

    // ── CSV ─────────────────────────────────────────────────────────────

    @Test
    fun `formatCsv emits header and rows separated by newlines`() {
        val out = formatCsv(
            headers = listOf("idx", "path", "line"),
            rows = listOf(
                listOf(1, "/a/A.kt", 10),
                listOf(2, "/b/B.kt", 20),
            ),
        )
        assertEquals(
            """
            idx,path,line
            1,/a/A.kt,10
            2,/b/B.kt,20
            """.trimIndent() + "\n",
            out,
        )
    }

    @Test
    fun `formatCsv quotes cells containing comma quote newline or CR per RFC 4180`() {
        val out = formatCsv(
            headers = listOf("plain", "with_comma", "with_quote", "with_newline", "with_cr"),
            rows = listOf(
                listOf("hello", "a,b", "say \"hi\"", "line1\nline2", "carriage\rreturn"),
            ),
        )
        // Plain stays unquoted; the four special cells are quoted; embedded " becomes "".
        // The expected CSV is built from regular strings because the row's quoted-quote
        // cell would contain `"""` which terminates a Kotlin raw-string literal.
        val expected = "plain,with_comma,with_quote,with_newline,with_cr\n" +
            "hello," +
            "\"a,b\"," +
            "\"say \"\"hi\"\"\"," +
            "\"line1\nline2\"," +
            "\"carriage\rreturn\"\n"
        assertEquals(expected, out)
    }

    @Test
    fun `formatCsv handles null cells as empty strings without quoting`() {
        val out = formatCsv(
            headers = listOf("k", "v"),
            rows = listOf(listOf("a", null), listOf(null, "b")),
        )
        assertEquals("k,v\na,\n,b\n", out)
    }

    @Test
    fun `formatCsv rejects empty headers`() {
        assertThrows(IllegalArgumentException::class.java) {
            formatCsv(headers = emptyList(), rows = listOf(listOf("x")))
        }
    }

    @Test
    fun `formatCsv rejects row with wrong cell count`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            formatCsv(
                headers = listOf("a", "b"),
                rows = listOf(listOf("x", "y"), listOf("only-one")),
            )
        }
        assertTrue("Error names the offending row index: ${e.message}", e.message!!.contains("row[1]"))
    }

    @Test
    fun `formatCsv produces only the header row when given no data rows`() {
        val out = formatCsv(headers = listOf("a", "b"), rows = emptyList())
        assertEquals("a,b\n", out)
    }

    @Test
    fun `formatCsv with dictColumns emits preamble and substitutes IDs`() {
        // Example straight out of issue #35.
        val out = formatCsv(
            headers = listOf("idx", "path", "line", "col", "snippet"),
            rows = listOf(
                listOf(1, "/abs/path/to/Foo.java", 120, 15, "bar.process(req);"),
                listOf(2, "/abs/path/to/Bar.java", 55, 8, "this.process(buf);"),
                listOf(3, "/abs/path/to/Foo.java", 180, 22, "bar.process(other);"),
            ),
            dictColumns = setOf("path"),
        )
        assertEquals(
            """
            @path:
              p1=/abs/path/to/Foo.java
              p2=/abs/path/to/Bar.java
            idx,path,line,col,snippet
            1,p1,120,15,bar.process(req);
            2,p2,55,8,this.process(buf);
            3,p1,180,22,bar.process(other);
            """.trimIndent() + "\n",
            out,
        )
    }

    @Test
    fun `formatCsv dictColumns skips a header that is not used in any row`() {
        val out = formatCsv(
            headers = listOf("idx", "path"),
            rows = listOf(listOf(1, "/a.kt"), listOf(2, "/b.kt")),
            dictColumns = setOf("path", "no-such-column"),
        )
        // "no-such-column" is silently ignored — no extra preamble block.
        assertTrue("Output must start with the @path preamble: $out", out.startsWith("@path:\n"))
        assertTrue("Unknown dictColumns must not surface: $out", !out.contains("no-such-column"))
    }

    @Test
    fun `formatCsv dictColumns first-seen order is preserved across rows`() {
        val out = formatCsv(
            headers = listOf("idx", "path"),
            rows = listOf(
                listOf(1, "/Z.kt"),  // p1
                listOf(2, "/A.kt"),  // p2
                listOf(3, "/Z.kt"),  // re-uses p1
                listOf(4, "/M.kt"),  // p3
            ),
            dictColumns = setOf("path"),
        )
        assertEquals(
            """
            @path:
              p1=/Z.kt
              p2=/A.kt
              p3=/M.kt
            idx,path
            1,p1
            2,p2
            3,p1
            4,p3
            """.trimIndent() + "\n",
            out,
        )
    }

    @Test
    fun `formatCsv dictColumns leaves null cells empty rather than assigning an ID`() {
        val out = formatCsv(
            headers = listOf("idx", "path"),
            rows = listOf(listOf(1, "/a.kt"), listOf(2, null), listOf(3, "/a.kt")),
            dictColumns = setOf("path"),
        )
        // Null is not put in the dictionary; the cell stays empty.
        assertEquals(
            """
            @path:
              p1=/a.kt
            idx,path
            1,p1
            2,
            3,p1
            """.trimIndent() + "\n",
            out,
        )
    }

    @Test
    fun `formatCsv with non-string dict cells stringifies via toString`() {
        val out = formatCsv(
            headers = listOf("v"),
            rows = listOf(listOf(42), listOf(42), listOf(7)),
            dictColumns = setOf("v"),
        )
        assertEquals(
            """
            @v:
              v1=42
              v2=7
            v
            v1
            v1
            v2
            """.trimIndent() + "\n",
            out,
        )
    }

    // ── TOON ────────────────────────────────────────────────────────────

    @Test
    fun `formatToon emits null for null`() {
        assertEquals("null", formatToon(null))
    }

    @Test
    fun `formatToon emits primitives as their toString`() {
        assertEquals("42", formatToon(42))
        assertEquals("3.14", formatToon(3.14))
        assertEquals("true", formatToon(true))
        assertEquals("hello", formatToon("hello"))
    }

    @Test
    fun `formatToon quotes a string that looks like a keyword`() {
        // Without quoting, the consumer can't tell value "null" from null.
        assertEquals("\"null\"", formatToon("null"))
        assertEquals("\"true\"", formatToon("true"))
        assertEquals("\"false\"", formatToon("false"))
    }

    @Test
    fun `formatToon quotes strings containing separators or newlines`() {
        assertEquals("\"a,b\"", formatToon("a,b"))
        assertEquals("\"a:b\"", formatToon("a:b"))
        assertEquals("\"a\\\"b\"", formatToon("a\"b"))
    }

    @Test
    fun `formatToon emits list of uniform records as array-with-header`() {
        // The headline use case from issue #34: find-references / call-hierarchy.
        val out = formatToon(listOf(
            mapOf("id" to 1, "name" to "Alice"),
            mapOf("id" to 2, "name" to "Bob"),
        ))
        assertEquals(
            """
            [2]{id,name}:
              1,Alice
              2,Bob
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `formatToon array-with-header preserves key insertion order from the first map`() {
        // LinkedHashMap iteration order matters for TOON — the consumer
        // reads keys once from the header line.
        val out = formatToon(listOf(
            linkedMapOf("path" to "/a.kt", "line" to 1),
            linkedMapOf("path" to "/b.kt", "line" to 2),
        ))
        assertTrue("Header columns must follow insertion order: $out", out.startsWith("[2]{path,line}:\n"))
    }

    @Test
    fun `formatToon falls back to per-element block when list maps have different keysets`() {
        val out = formatToon(listOf(
            mapOf("a" to 1),
            mapOf("b" to 2),
        ))
        // Not uniform → indented per-element rendering (NOT the [N]{cols}: form).
        assertTrue("Mixed maps must not be rendered with the array-header form: $out", !out.contains("{"))
        assertTrue("Mixed list must be tagged with its length: $out", out.contains("[2]:"))
    }

    @Test
    fun `formatToon renders heterogeneous keysets as the exact documented fallback`() {
        // Issue #459: the prompt corpus used to claim heterogeneous maps are
        // rejected. They are accepted; this pins the exact shape the corpus
        // now documents, so a formatter change forces a docs update.
        val out = formatToon(listOf(
            mapOf("a" to 1),
            mapOf("b" to 2),
        ))
        assertEquals(
            """
            [2]:
              a: 1
              b: 2
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `formatToon accepts a widened record that only some elements carry`() {
        // The realistic heterogeneous case: inspection / search records where
        // an optional field is present on some rows only. No exception, no
        // silent data loss — every key of every element is emitted.
        val out = formatToon(listOf(
            linkedMapOf("path" to "/a.kt", "line" to 1),
            linkedMapOf("path" to "/b.kt", "line" to 2, "quickFix" to "RemoveCast"),
        ))
        assertEquals(
            """
            [2]:
              path: /a.kt
              line: 1
              path: /b.kt
              line: 2
              quickFix: RemoveCast
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `formatToon emits list of primitives as single comma-joined line`() {
        assertEquals("[3]: 1,2,3", formatToon(listOf(1, 2, 3)))
        assertEquals("[2]: alpha,beta", formatToon(listOf("alpha", "beta")))
    }

    @Test
    fun `formatToon emits empty list as length tag only`() {
        assertEquals("[0]:", formatToon(emptyList<Any>()))
    }

    @Test
    fun `formatToon emits a top-level map as key colon value lines`() {
        val out = formatToon(linkedMapOf("id" to 1, "name" to "Alice"))
        assertEquals(
            """
            id: 1
            name: Alice
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `formatToon nests a map value under an indented block`() {
        val out = formatToon(linkedMapOf(
            "owner" to linkedMapOf("id" to 1, "name" to "Alice"),
            "active" to true,
        ))
        assertEquals(
            """
            owner:
              id: 1
              name: Alice
            active: true
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `formatToon handles a map whose value is a uniform array of records`() {
        // Realistic shape returned by ReferencesSearch wrapped in metadata.
        val out = formatToon(linkedMapOf(
            "symbol" to "process",
            "refs" to listOf(
                linkedMapOf("path" to "/Foo.kt", "line" to 10),
                linkedMapOf("path" to "/Bar.kt", "line" to 20),
            ),
        ))
        assertEquals(
            """
            symbol: process
            refs:
              [2]{path,line}:
                /Foo.kt,10
                /Bar.kt,20
            """.trimIndent(),
            out,
        )
    }

    @Test
    fun `formatToon serialises arrays and iterables the same way as List`() {
        // Defensive: callers may pass arrays or sequences out of Kotlin collections.
        val asArray: Array<Any?> = arrayOf(1, 2, 3)
        val asSet: Set<Int> = linkedSetOf(1, 2, 3)
        assertEquals("[3]: 1,2,3", formatToon(asArray))
        assertEquals("[3]: 1,2,3", formatToon(asSet))
    }
}
