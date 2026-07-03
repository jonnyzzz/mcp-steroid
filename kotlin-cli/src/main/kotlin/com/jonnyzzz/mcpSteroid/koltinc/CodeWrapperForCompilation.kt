/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.koltinc

/**
 * Wraps Kotlin code into a compilable class with imports and execution binding.
 *
 * This is the shared implementation used by both:
 * - `CodeButcher` in ij-plugin (for runtime script execution)
 * - `KtBlockCompilationTestBase` in prompts (for compilation-only testing)
 *
 * The caller supplies the FQNs for McpScriptContext and McpScriptBuilder since
 * kotlin-cli doesn't depend on ij-plugin and can't resolve them via reflection.
 */
object CodeWrapperForCompilation {
    val defaultImports = listOf(
        "import com.intellij.openapi.project.*",
        "import com.intellij.openapi.application.*",
        "import com.intellij.openapi.application.readAction",
        "import com.intellij.openapi.application.writeAction",
        "import com.intellij.openapi.vfs.*",
        "import com.intellij.openapi.editor.*",
        "import com.intellij.openapi.fileEditor.*",
        "import com.intellij.openapi.command.*",
        "import com.intellij.psi.*",
        "import com.intellij.psi.search.*",
        "import com.intellij.psi.search.searches.*",
        "import com.intellij.psi.util.*",
        "import kotlinx.coroutines.*",
        "import kotlin.time.Duration.Companion.seconds",
        "import kotlin.time.Duration.Companion.minutes",
    )

    const val DEFAULT_SCRIPT_CONTEXT_FQN = "com.jonnyzzz.mcpSteroid.execution.McpScriptContext"
    const val DEFAULT_SCRIPT_BUILDER_FQN = "com.jonnyzzz.mcpSteroid.execution.McpScriptBuilder"
    const val DEFAULT_ADD_BLOCK_NAME = "addBlock"
    const val DEFAULT_METHOD_NAME = "jonnyzzz_execute_all_script_content_77"

    data class WrapResult(
        val classFqn: String,
        val methodName: String,
        val code: String,
        val lineMapping: LineMapping,
    )

    /**
     * Result of extracting import lines from user code, with original line number tracking.
     */
    data class ExtractedCode(
        val importLines: List<String>,
        val otherLines: List<String>,
        /** For each otherLines[i], its 1-based line number in the original user code */
        val otherLineNumbers: List<Int>,
        /** For each importLines[i], its 1-based line number in the original user code */
        val importLineNumbers: List<Int>,
    )

    /**
     * Extracts import lines from code while respecting triple-quoted strings,
     * and returns (importLines, otherLines).
     */
    fun extractImports(code: String): Pair<List<String>, List<String>> {
        val result = extractImportsWithLineNumbers(code)
        return result.importLines to result.otherLines
    }

    /**
     * Extracts import lines from code while respecting triple-quoted strings,
     * tracking original line numbers for each extracted line.
     */
    fun extractImportsWithLineNumbers(code: String): ExtractedCode {
        val importLines = mutableListOf<String>()
        val otherLines = mutableListOf<String>()
        val importLineNumbers = mutableListOf<Int>()
        val otherLineNumbers = mutableListOf<Int>()
        var tripleQuoteCount = 0
        var lineNumber = 0
        for (line in code.lineSequence()) {
            lineNumber++
            val inTripleQuotedString = tripleQuoteCount % 2 != 0
            var idx = 0
            while (idx <= line.length - 3) {
                if (line[idx] == '"' && line[idx + 1] == '"' && line[idx + 2] == '"') {
                    tripleQuoteCount++
                    idx += 3
                } else {
                    idx++
                }
            }
            if (!inTripleQuotedString && line.trim().trimStart(';').trim().startsWith("import ")) {
                importLines.add(line)
                importLineNumbers.add(lineNumber)
            } else {
                otherLines.add(line)
                otherLineNumbers.add(lineNumber)
            }
        }
        return ExtractedCode(
            importLines = importLines,
            otherLines = otherLines,
            otherLineNumbers = otherLineNumbers,
            importLineNumbers = importLineNumbers,
        )
    }

    /**
     * Wraps user code into a compilable Kotlin class.
     *
     * @param className base name for the generated class (sanitized internally)
     * @param code the user code to wrap
     * @param scriptContextFqn FQN of the McpScriptContext class
     * @param scriptBuilderFqn FQN of the McpScriptBuilder class
     * @param addBlockName name of the addBlock method on the builder
     * @param methodName name of the generated entry-point method
     */
    fun wrap(
        className: String,
        code: String,
        scriptContextFqn: String = DEFAULT_SCRIPT_CONTEXT_FQN,
        scriptBuilderFqn: String = DEFAULT_SCRIPT_BUILDER_FQN,
        addBlockName: String = DEFAULT_ADD_BLOCK_NAME,
        methodName: String = DEFAULT_METHOD_NAME,
    ): WrapResult {
        val clazzName = className.replace("[^a-z0-9_]+".toRegex(RegexOption.IGNORE_CASE), "_")
        val extracted = extractImportsWithLineNumbers(code)
        val importLines = extracted.importLines
        val otherLines = extracted.otherLines

        val wrappedCode = buildString {
            append(defaultImports.joinToString(separator = "\n", postfix = "\n"))
            appendLine()
            appendLine("//imports from the submitted code")
            importLines.forEach { appendLine(it) }
            appendLine()
            appendLine("class $clazzName {")
            appendLine("  inline fun $scriptContextFqn.execute(ƒ: $scriptContextFqn.() -> Unit) = ƒ()")
            appendLine("  fun $methodName(builder : $scriptBuilderFqn) { ")
            appendLine("    builder.$addBlockName { ${methodName}_code() }")
            appendLine("  }")
            appendLine("  suspend fun $scriptContextFqn.${methodName}_code() {")
            appendLine("    //the rest of submitted code")
            otherLines.forEach { append("    ").appendLine(it) }
            appendLine("  }")
            appendLine("}")
            append("\n")
        }

        // Build the line mapping from wrapped line numbers to original user line numbers.
        //
        // The wrapped code layout (1-based line numbers):
        //   Lines 1..12:          defaultImports (12 lines via joinToString with \n separator + \n postfix)
        //   Line 13:              empty (appendLine())
        //   Line 14:              "//imports from the submitted code"
        //   Lines 15..14+N:       user imports (N = importLines.size)
        //   Line 15+N:            empty (appendLine())
        //   Line 16+N:            "class $clazzName {"
        //   Line 17+N:            "  inline fun ..."
        //   Line 18+N:            "  fun $methodName..."
        //   Line 19+N:            "    builder.$addBlockName..."
        //   Line 20+N:            "  }"
        //   Line 21+N:            "  suspend fun ..."
        //   Line 22+N:            "    //the rest of submitted code"
        //   Lines 23+N..22+N+M:   user code lines (M = otherLines.size)
        //   Line 23+N+M:          "  }"
        //   Line 24+N+M:          "}"
        //   Line 25+N+M:          empty (trailing \n)
        val n = importLines.size
        val mapping = mutableMapOf<Int, Int>()

        // Map user import lines
        for (i in extracted.importLineNumbers.indices) {
            val wrappedLine = 15 + i
            mapping[wrappedLine] = extracted.importLineNumbers[i]
        }

        // Map user code lines (non-import)
        for (i in extracted.otherLineNumbers.indices) {
            val wrappedLine = 23 + n + i
            mapping[wrappedLine] = extracted.otherLineNumbers[i]
        }

        val lineMapping = LineMapping(mapping)

        return WrapResult(classFqn = clazzName, methodName = methodName, code = wrappedCode, lineMapping = lineMapping)
    }
}
