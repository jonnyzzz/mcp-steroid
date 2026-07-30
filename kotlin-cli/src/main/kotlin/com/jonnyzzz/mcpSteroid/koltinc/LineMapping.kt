/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

/**
 * Maps wrapped-file line numbers back to original user-code line numbers.
 *
 * When user code is wrapped by [CodeWrapperForCompilation.wrap], imports and boilerplate
 * are prepended, shifting all line numbers. This class remaps compiler error references
 * (e.g., `input.kt:23:5: error: ...`) so agents see user-relative line numbers.
 */
class LineMapping(private val wrappedToOriginal: Map<Int, Int>) {

    /**
     * Remap a single wrapped-file line number to the user-code line, or null when
     * the line belongs to wrapper boilerplate rather than user code. Used for
     * structured compiler message locations (BTA [org.jetbrains.kotlin.buildtools.api.CompilerMessageRenderer.SourceLocation])
     * where no textual `file:line:col:` pattern exists to run [remapCompilerOutput] on.
     */
    fun remapLine(wrappedLine: Int): Int? = wrappedToOriginal[wrappedLine]

    /**
     * Remap line references in compiler output from wrapped-file lines to user-code lines.
     * Matches patterns like `input.kt:LINE:COL:` and replaces LINE with the user-relative number.
     * Lines not in the mapping (wrapper boilerplate) are left as-is.
     */
    fun remapCompilerOutput(output: String, fileName: String = "input.kt"): String {
        val pattern = Regex("""${Regex.escape(fileName)}:(\d+):(\d+):""")
        return output.replace(pattern) { match ->
            val wrappedLine = match.groupValues[1].toInt()
            val col = match.groupValues[2]
            val originalLine = wrappedToOriginal[wrappedLine]
            if (originalLine != null) {
                "$fileName:$originalLine:$col:"
            } else {
                match.value
            }
        }
    }

    /**
     * Remap line references in JVM stack traces from wrapped-file lines to user-code lines.
     * Matches patterns like `(input.kt:LINE)` and `input.kt:LINE` that appear in
     * `Throwable.stackTraceToString()` output.
     * Lines not in the mapping (wrapper boilerplate) are left as-is.
     */
    fun remapStackTrace(stackTrace: String, fileName: String = "input.kt"): String {
        val pattern = Regex("""${Regex.escape(fileName)}:(\d+)""")
        return stackTrace.replace(pattern) { match ->
            val wrappedLine = match.groupValues[1].toInt()
            val originalLine = wrappedToOriginal[wrappedLine]
            if (originalLine != null) {
                "$fileName:$originalLine"
            } else {
                match.value
            }
        }
    }

    /**
     * Produce a clean stack trace for agents: keep only the exception message and
     * stack frames from the user's script file that map to user code lines.
     * Wrapper boilerplate frames and framework internals are stripped.
     */
    fun cleanStackTrace(stackTrace: String, fileName: String = "input.kt"): String {
        val fileRef = Regex("""${Regex.escape(fileName)}:(\d+)""")
        val lines = stackTrace.lines()
        return buildString {
            for (line in lines) {
                val trimmed = line.trimStart()
                // Always keep the exception message line(s) — not starting with "at "
                if (!trimmed.startsWith("at ") && !trimmed.startsWith("...")) {
                    appendLine(line)
                    continue
                }
                // For "at ..." frames, keep only those referencing the user's file with a mapped line
                val match = fileRef.find(line) ?: continue
                val wrappedLine = match.groupValues[1].toIntOrNull() ?: continue
                val originalLine = wrappedToOriginal[wrappedLine] ?: continue
                // Emit the frame with remapped line number
                appendLine(line.replace(match.value, "$fileName:$originalLine"))
            }
        }.trimEnd()
    }

    companion object {
        /** A no-op mapping that leaves all line numbers unchanged. */
        val IDENTITY = LineMapping(emptyMap())
    }
}
