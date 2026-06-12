/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Single literal-text edit applied by [executeApplyPatch] / the
 * `applyPatch { hunk(...) }` DSL on [McpScriptContext].
 */
@Serializable
data class ApplyPatchHunk(val filePath: String, val oldString: String, val newString: String)

/**
 * Atomic multi-site literal-text patch for MCP Steroid agents.
 *
 * Exposed on [McpScriptContext] as a single `applyPatch { hunk(...); hunk(...) }`
 * call so the agent ships **data only** — no readAction / writeAction /
 * CommandProcessor boilerplate. The script source for a 3-hunk patch shrinks
 * from ~90 lines of Kotlin to ~5 lines, with identical atomicity guarantees.
 *
 * Semantics:
 * - Pre-flight (read action): every `oldString` must be present **exactly once**
 *   in its file. Missing or non-unique hunks throw [ApplyPatchException] with
 *   hunk index, path, and offset info — no edits land.
 * - Apply (write action + single CommandProcessor command): every
 *   `Document.replaceString` call combines into one undoable step. Multi-hunk
 *   edits in the same file are applied in **descending offset order** so earlier
 *   edits don't shift later ones.
 * - Commit + save: `PsiDocumentManager.commitAllDocuments()` flushes into PSI
 *   and every touched document is saved so native tools and build processes see
 *   the updated file bytes immediately after the tool returns.
 * - Tail-of-exec VFS refresh (non-blocking) is already scheduled by
 *   `ExecutionManager`; callers never need to force one.
 */
class ApplyPatchException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@Serializable
data class AppliedHunk(
    val index: Int,
    val path: String,
    val line: Int,      // 1-based
    val column: Int,    // 1-based
    val oldLen: Int,
    val newLen: Int,
)

@Serializable
data class ApplyPatchResult(val applied: List<AppliedHunk>, val isDryRun: Boolean = false) {
    val hunkCount: Int get() = applied.size
    val fileCount: Int get() = applied.map { it.path }.distinct().size

    /**
     * Multi-line audit trail suitable for `println(result)`. Example (live):
     *
     * ```
     * apply-patch: 3 hunks across 2 file(s) applied atomically.
     *   [#0] /abs/A.java:17:5  (12→18 chars)
     *   [#1] /abs/A.java:42:9  (5→9 chars)
     *   [#2] /abs/B.java:88:13 (7→5 chars)
     * ```
     *
     * Dry-run swaps "applied" for "would apply" so consumers parsing the
     * audit trail can distinguish the two without inspecting `isDryRun`.
     */
    override fun toString(): String = buildString {
        append("apply-patch")
        if (isDryRun) append(" (dry-run)")
        append(": ")
        append(hunkCount)
        append(" hunk")
        if (hunkCount != 1) append("s")
        append(" across ")
        append(fileCount)
        append(" file(s) ")
        append(if (isDryRun) "would apply" else "applied")
        append(" atomically.")
        applied.forEach { h ->
            append("\n  [#").append(h.index).append("] ").append(h.path)
            append(':').append(h.line).append(':').append(h.column)
            append("  (").append(h.oldLen).append("→").append(h.newLen).append(" chars)")
        }
    }
}

/**
 * Builder for the `applyPatch { … }` DSL. Instances are single-use — one call
 * per `applyPatch { }` invocation.
 */
class ApplyPatchBuilder internal constructor() {
    internal val hunks = mutableListOf<ApplyPatchHunk>()

    /**
     * Register one literal-text substitution. `oldString` must occur exactly
     * once in the file at [filePath]; if it doesn't, the whole patch aborts
     * before any edit lands. `filePath` is an absolute filesystem path.
     */
    fun hunk(filePath: String, oldString: String, newString: String) {
        hunks += ApplyPatchHunk(filePath, oldString, newString)
    }
}

internal suspend fun executeApplyPatch(
    project: Project,
    hunks: List<ApplyPatchHunk>,
    dryRun: Boolean = false,
    resolveFile: (String) -> VirtualFile?,
): ApplyPatchResult {
    require(hunks.isNotEmpty()) { "applyPatch { … } called with zero hunks" }

    // Pre-flight: resolve path -> Document, validate single-occurrence per hunk,
    // capture line/column now while we hold the read action.
    data class ResolvedHunk(
        val index: Int,
        val filePath: String,
        val document: Document,
        val offset: Int,
        val oldLen: Int,
        val newString: String,
        val line: Int,
        val column: Int,
    )

    val resolved: List<ResolvedHunk> = readAction {
        hunks.mapIndexed { index, hunk ->
            // Caller (McpScriptContextImpl) supplies a resolver that handles
            // LocalFileSystem, light-test temp://, and mock VFS consistently.
            val vf: VirtualFile = resolveFile(hunk.filePath)
                ?: throw ApplyPatchException(
                    fileNotFoundMessage(project, index, hunk.filePath)
                )
            if (!vf.isWritable) {
                throw ApplyPatchException(
                    "Hunk #$index: file is read-only: ${hunk.filePath}"
                )
            }
            val document = FileDocumentManager.getInstance().getDocument(vf)
                ?: throw ApplyPatchException(
                    "Hunk #$index: no Document available for ${hunk.filePath}"
                )
            val text = document.text
            val first = text.indexOf(hunk.oldString)
            if (first < 0) {
                throw ApplyPatchException(
                    anchorNotFoundMessage(index, hunk.filePath, hunk.oldString, document, text)
                )
            }
            val second = text.indexOf(hunk.oldString, first + 1)
            if (second >= 0) {
                throw ApplyPatchException(
                    "Hunk #$index: old_string occurs more than once in ${hunk.filePath} " +
                        "(first at offset $first, next at $second) — " +
                        "expand old_string with surrounding context to make it unique"
                )
            }
            val lineIdx = document.getLineNumber(first)
            val column = first - document.getLineStartOffset(lineIdx)
            ResolvedHunk(
                index = index,
                filePath = hunk.filePath,
                document = document,
                offset = first,
                oldLen = hunk.oldString.length,
                newString = hunk.newString,
                line = lineIdx + 1,
                column = column + 1,
            )
        }
    }

    // Group by Document and apply in DESCENDING offset order so earlier
    // replacements don't shift later offsets. Edits from different files can
    // run in any order; the descending rule applies per file.
    val groupedDescending = resolved.groupBy { it.document }
        .mapValues { (_, hs) -> hs.sortedByDescending { it.offset } }

    val commandName = "MCP Steroid: apply-patch (${resolved.size} hunk${if (resolved.size == 1) "" else "s"})"

    // The write phase runs on the EDT with write-lock + CommandProcessor undo group.
    //
    // Modality: `ModalityState.any()` is explicitly NOT safe here (per JavaDoc at
    // platform/core-api/src/com/intellij/openapi/application/ModalityState.java —
    // `.any()` is for purely UI helpers and must not touch PSI/VFS/model). We use
    // `nonModal()` so dispatch waits until any modal dialog dismisses.
    //
    // `withContext(Dispatchers.EDT + …)` detects the already-on-EDT case and runs
    // inline. Tests that want to drive this path must NOT block EDT in the caller
    // — see `ApplyPatchTest`, which overrides `runInDispatchThread() = false` so
    // `timeoutRunBlocking` runs on the JUnit worker thread and leaves EDT free
    // to dispatch the write phase (an EDT-parked-in-runBlocking caller would
    // deadlock here).
    // Dry-run: preflight ran above, every anchor resolved exactly once, every
    // file is writable. Skip the write phase — caller wants to know whether the
    // patch *would* apply, not to apply it. `isDryRun` flips the audit-trail
    // wording so a consumer parsing the output sees "would apply" instead of
    // "applied".
    if (!dryRun) {
        withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
            WriteCommandAction.runWriteCommandAction(project, commandName, null, Runnable {
                val fileDocumentManager = FileDocumentManager.getInstance()
                for ((_, hunksInFile) in groupedDescending) {
                    for (h in hunksInFile) {
                        h.document.replaceString(h.offset, h.offset + h.oldLen, h.newString)
                    }
                }
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                for ((document, hunksInFile) in groupedDescending) {
                    val filePath = hunksInFile.first().filePath
                    try {
                        fileDocumentManager.saveDocument(document)
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (e: RuntimeException) {
                        throw ApplyPatchException("Failed to save patched document to disk: $filePath", e)
                    }
                    if (fileDocumentManager.isDocumentUnsaved(document)) {
                        throw ApplyPatchException(
                            "Failed to save patched document to disk: $filePath"
                        )
                    }
                }
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            })
        }
    }

    return ApplyPatchResult(
        applied = resolved.map { h ->
            AppliedHunk(
                index = h.index,
                path = h.filePath,
                line = h.line,
                column = h.column,
                oldLen = h.oldLen,
                newLen = h.newString.length,
            )
        },
        isDryRun = dryRun,
    )
}

/**
 * Build a structured "file not found" message including up to 5 candidate paths
 * from the project index by basename. Runs inside the caller's read action.
 * Diagnostics must never themselves throw — any unexpected error falls back
 * to the plain message.
 */
private fun fileNotFoundMessage(project: Project, index: Int, filePath: String): String {
    val plain = "Hunk #$index: file not found: $filePath"
    val basename = filePath.substringAfterLast('/').ifEmpty { return plain }
    val candidates = try {
        FilenameIndex.getVirtualFilesByName(basename, GlobalSearchScope.projectScope(project))
            .asSequence()
            .map { it.path }
            .filter { it != filePath }
            .take(5)
            .toList()
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: RuntimeException) {
        return plain
    }
    return buildString {
        append(plain)
        if (candidates.isEmpty()) {
            append("\n(no candidates by basename '")
            append(basename)
            append("' in project index — check the path)")
        } else {
            append("\nNearby candidates by basename '")
            append(basename)
            append("':")
            candidates.forEach { append("\n  ").append(it) }
        }
    }
}

/**
 * Build a structured "old_string not found" message. Includes the file's line
 * count and byte length plus up to 3 candidate lines that share the longest
 * stable token (>= 4 chars) from `oldString`. The leading sentence preserves
 * the original wording so consumers matching on substrings keep working —
 * "old_string not found" stays verbatim. Diagnostics must never themselves
 * throw, so the structured tail is built in a guarded block that falls back
 * to the lead on any RuntimeException.
 */
private fun anchorNotFoundMessage(
    index: Int,
    filePath: String,
    oldString: String,
    document: Document,
    text: String,
): String {
    val lead = "Hunk #$index: old_string not found in $filePath — verify with steroid_execute_code first"
    return try {
        buildAnchorNotFoundDetail(lead, oldString, document, text)
    } catch (e: ProcessCanceledException) {
        throw e
    } catch (e: RuntimeException) {
        lead
    }
}

private fun buildAnchorNotFoundDetail(
    lead: String,
    oldString: String,
    document: Document,
    text: String,
): String {
    val tokens = Regex("[A-Za-z0-9_]{4,}").findAll(oldString).map { it.value }.toList()
    val token = tokens.sortedByDescending { it.length }
        .firstOrNull { text.contains(it) }
    val candidates = if (token != null) {
        val results = mutableListOf<Pair<Int, String>>()
        var from = 0
        while (results.size < 3) {
            val found = text.indexOf(token, from)
            if (found < 0) break
            val lineIdx = document.getLineNumber(found)
            // Skip duplicate hits on the same line.
            if (results.none { it.first == lineIdx + 1 }) {
                val lineStart = document.getLineStartOffset(lineIdx)
                val lineEnd = document.getLineEndOffset(lineIdx)
                results += (lineIdx + 1) to text.substring(lineStart, lineEnd).take(200)
            }
            from = found + token.length
        }
        results
    } else emptyList()
    val totalHits = if (token != null) countOccurrences(text, token) else 0
    return buildString {
        append(lead)
        append("\nFile: ").append(document.lineCount).append(" lines, ").append(text.length).append(" bytes")
        when {
            token == null -> append("\n(no stable token of length >= 4 in old_string for fuzzy lookup)")
            candidates.isEmpty() ->
                append("\nToken '").append(token).append("' from old_string not present in file — anchor is likely stale")
            else -> {
                append("\nFuzzy candidates (lines containing '").append(token)
                    .append("', showing ").append(candidates.size).append(" of ").append(totalHits).append("):")
                candidates.forEach { (line, contents) ->
                    append("\n  L").append(line).append(": ").append(contents)
                }
            }
        }
    }
}

private fun countOccurrences(text: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var idx = text.indexOf(needle)
    while (idx >= 0) {
        count++
        idx = text.indexOf(needle, idx + needle.length)
    }
    return count
}
