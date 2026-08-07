/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

/**
 * Token-efficient output formatters for `printCsv` and `printToon` on
 * [McpScriptContext].
 *
 * Kept as pure top-level functions on purpose: they have no IDE / coroutine
 * dependencies and are unit-tested in `OutputFormattersTest` without standing
 * up an `McpScriptContextImpl`. The context methods are thin shells that
 * delegate here and write the result through `ExecutionResultBuilder`.
 *
 * Background: GitHub #34 + #35. The minimal implementation lives here; the
 * agent-facing documentation lives in the prompt corpus.
 */

/**
 * Format `rows` as CSV with a single header row.
 *
 * Escaping follows RFC 4180:
 * - Cells containing `,`, `"`, `\n`, or `\r` are wrapped in double quotes.
 * - Embedded `"` is doubled to `""`.
 * - Newlines use `\n` (not CRLF) — most LLM-side consumers accept both and
 *   `\n` is one token cheaper per line.
 *
 * When [dictColumns] is non-empty, the named columns are emitted as a
 * preamble dictionary block before the header row, and the corresponding
 * cells in every data row are replaced with short dictionary IDs
 * (`p1`, `p2`, …). IDs use the first character of the column name lowercased
 * as the prefix; callers must ensure `dictColumns` entries have unique first
 * characters (e.g. `path` and `package` both prefix as `p` — that's a caller
 * error and we don't try to disambiguate beyond noting it in the docs).
 *
 * Unknown columns in [dictColumns] are silently ignored — passing
 * `dictColumns = setOf("path")` against headers `[idx, line, snippet]` emits
 * a plain CSV with no preamble.
 */
internal fun formatCsv(
    headers: List<String>,
    rows: Iterable<List<Any?>>,
    dictColumns: Set<String> = emptySet(),
): String {
    require(headers.isNotEmpty()) { "formatCsv: headers must not be empty" }
    val rowList = rows.toList()
    rowList.forEachIndexed { idx, row ->
        require(row.size == headers.size) {
            "formatCsv: row[$idx] has ${row.size} cell(s) but headers have ${headers.size}"
        }
    }

    // For each dict column: column index → (ordered list of distinct values, value→id map).
    val dictColumnIndices: Map<Int, Pair<MutableList<String>, MutableMap<String, String>>> =
        headers.mapIndexedNotNull { idx, name ->
            if (name in dictColumns) idx to (mutableListOf<String>() to mutableMapOf<String, String>()) else null
        }.toMap()

    // First-seen order — walk rows and assign IDs.
    for (row in rowList) {
        for ((colIdx, state) in dictColumnIndices) {
            val (values, lookup) = state
            val raw = row[colIdx]?.toString() ?: continue
            if (raw !in lookup) {
                val prefix = headers[colIdx].lowercase().firstOrNull { it.isLetterOrDigit() }?.toString() ?: "d"
                lookup[raw] = "$prefix${values.size + 1}"
                values += raw
            }
        }
    }

    return buildString {
        for ((colIdx, state) in dictColumnIndices) {
            val (values, lookup) = state
            if (values.isEmpty()) continue
            append('@').append(headers[colIdx]).append(":\n")
            for (raw in values) {
                append("  ").append(lookup.getValue(raw)).append('=').append(raw).append('\n')
            }
        }
        append(headers.joinToString(",") { csvCell(it) }).append('\n')
        for (row in rowList) {
            val cells = row.mapIndexed { idx, cell ->
                val str = cell?.toString() ?: ""
                val state = dictColumnIndices[idx]
                if (state != null && cell != null) state.second.getValue(str) else csvCell(str)
            }
            append(cells.joinToString(",")).append('\n')
        }
    }
}

private fun csvCell(value: String): String {
    val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!needsQuoting) return value
    return "\"${value.replace("\"", "\"\"")}\""
}

/**
 * Format [value] as TOON — Token-Oriented Object Notation. Minimal
 * implementation covering the shapes that actually show up in the
 * find-references / call-hierarchy / project-search recipes: primitives,
 * lists of primitives, lists of uniform-shape maps, single maps. The
 * dominant case is uniform array-of-records (the whole reason TOON exists)
 * and it gets the compact `[N]{cols}:` encoding.
 *
 * Mixed / non-uniform lists are a **supported input**, not a rejected one:
 * they fall back to one indented item per line, preserving every key of
 * every element. That fallback is part of the documented contract (issue
 * #459) — the agent-facing prompts tell callers not to normalize ragged
 * records to reach the compact form, so this branch must keep working.
 *
 * Spec reference: [toon-format/toon on GitHub](https://github.com/toon-format/toon).
 */
internal fun formatToon(value: Any?): String = buildString {
    appendToon(this, value, depth = 0)
}.trimEnd('\n')

private fun appendToon(out: StringBuilder, value: Any?, depth: Int) {
    when (value) {
        null -> out.append("null").append('\n')
        is Boolean, is Number -> out.append(value.toString()).append('\n')
        is String -> out.append(toonScalar(value)).append('\n')
        is Map<*, *> -> appendToonMap(out, value, depth)
        is List<*> -> appendToonList(out, value, depth)
        is Iterable<*> -> appendToonList(out, value.toList(), depth)
        is Array<*> -> appendToonList(out, value.toList(), depth)
        else -> out.append(toonScalar(value.toString())).append('\n')
    }
}

private fun appendToonMap(out: StringBuilder, map: Map<*, *>, depth: Int) {
    val pad = "  ".repeat(depth)
    for ((k, v) in map) {
        out.append(pad).append(k.toString())
        when (v) {
            null, is Boolean, is Number, is String -> {
                out.append(": ")
                appendToon(out, v, depth)
            }
            else -> {
                // Nested structure goes on its own indented block — `key:\n`
                // without a trailing space before the newline.
                out.append(":\n")
                val nested = StringBuilder().also { appendToon(it, v, depth + 1) }
                out.append(nested)
            }
        }
    }
}

private fun appendToonList(out: StringBuilder, list: List<*>, depth: Int) {
    val pad = "  ".repeat(depth)
    if (list.isEmpty()) {
        out.append(pad).append("[0]:").append('\n')
        return
    }

    // Uniform array-of-records: every element is a Map and every map has the
    // same key set in the same order. This is the find-references / call-
    // hierarchy / project-search shape — the case TOON optimises for.
    val maps = list.filterIsInstance<Map<*, *>>()
    if (maps.size == list.size && maps.isNotEmpty()) {
        val firstKeys = maps[0].keys.map { it.toString() }
        val uniform = maps.all { it.keys.map { k -> k.toString() } == firstKeys }
        if (uniform) {
            out.append(pad).append('[').append(list.size).append("]{")
                .append(firstKeys.joinToString(",")).append("}:\n")
            for (m in maps) {
                val rowPad = "  ".repeat(depth + 1)
                val cells = firstKeys.map { key -> toonScalar(m[key]?.toString() ?: "null") }
                out.append(rowPad).append(cells.joinToString(",")).append('\n')
            }
            return
        }
    }

    // Uniform array of primitives: comma-joined on a single line.
    val primitives = list.all { it == null || it is Boolean || it is Number || it is String }
    if (primitives) {
        val cells = list.map { it?.let { toonScalar(it.toString()) } ?: "null" }
        out.append(pad).append('[').append(list.size).append("]: ")
            .append(cells.joinToString(",")).append('\n')
        return
    }

    // Mixed or nested — one element per indented block.
    out.append(pad).append('[').append(list.size).append("]:\n")
    for (item in list) {
        val sub = StringBuilder().also { appendToon(it, item, depth + 1) }
        out.append(sub)
    }
}

private fun toonScalar(value: String): String {
    // Quote when the value contains a separator, a quote, or a newline; or
    // when it would otherwise be ambiguous with a literal keyword.
    val needsQuoting = value.isEmpty()
        || value.any { it == ',' || it == '"' || it == '\n' || it == '\r' || it == ':' }
        || value == "null" || value == "true" || value == "false"
    if (!needsQuoting) return value
    return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
