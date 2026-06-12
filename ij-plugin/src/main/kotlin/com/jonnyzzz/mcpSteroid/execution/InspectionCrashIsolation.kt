/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import kotlinx.coroutines.CancellationException

/**
 * One inspection tool that crashed during a [McpScriptContext.runInspectionsDirectly] sweep
 * (GitHub issue #93). The crash was isolated: findings from all other tools are preserved.
 *
 * @property toolId the inspection short name (same key space as the result map), or
 *   [InspectionRunResult.SWEEP_FAILURE_ID] when the failure was file-level rather than tool-level
 *   (GitHub issue #69 — e.g. PsiInvalidElementAccessException raised outside any single tool).
 * @property error the exception class name and message, e.g.
 *   "java.lang.IllegalStateException: Cannot compute containing PSI ..."
 */
data class FailedInspection(
    val toolId: String,
    val error: String,
)

/**
 * Result of [McpScriptContext.runInspectionsDirectly].
 *
 * ADDITIVE shape (GitHub issue #69): this class IS the `Map<inspectionShortName, List<ProblemDescriptor>>`
 * the method has always returned — every existing call site (`result.values`, `result.forEach`,
 * `result["ToolName"]`, `result.isEmpty()`) keeps compiling and behaving identically. On top of the
 * map it carries [failedTools]: the tools whose execution crashed and was isolated (issue #93).
 *
 * A tool listed in [failedTools] may still contribute partial findings to the map — problems it
 * registered before crashing are real and are kept.
 */
class InspectionRunResult(
    private val problems: Map<String, List<ProblemDescriptor>>,
    /** Tools that crashed during the sweep; empty when every tool completed normally. */
    val failedTools: List<FailedInspection>,
) : Map<String, List<ProblemDescriptor>> by problems {

    companion object {
        /** [FailedInspection.toolId] used when the whole-file sweep failed, not one specific tool. */
        const val SWEEP_FAILURE_ID: String = "<inspection-sweep>"
    }

    override fun equals(other: Any?): Boolean = problems == other
    override fun hashCode(): Int = problems.hashCode()
    override fun toString(): String =
        if (failedTools.isEmpty()) problems.toString()
        else "$problems (failedTools=$failedTools)"
}

/**
 * A [LocalInspectionTool] that delegates to [delegate] but isolates every crash (issue #93):
 * a Throwable raised by the delegate's visitor (or lifecycle callbacks) is recorded via
 * [onToolFailure] and swallowed, so `InspectionEngine.inspectEx` keeps running the other tools.
 *
 * Why this shape: `InspectionEngine` only guards `buildVisitor()` — a visit-time exception
 * propagates out of the engine's per-tool processor and aborts the whole sweep, losing the
 * findings of every other tool. The engine offers no public per-tool failure hook
 * (`InspectListener` is `@ApiStatus.Internal`), so the isolation lives in the tool itself, which
 * keeps the sweep a single `inspectEx` pass.
 *
 * The original short name is preserved, so `LocalInspectionToolWrapper(tool)` resolves the
 * ORIGINAL inspection EP by short name — language applicability filtering, IDs and severity
 * mapping behave exactly as for the undecorated tool.
 *
 * Control-flow and environment exceptions are always rethrown and never recorded:
 * ProcessCanceledException / CancellationException (cancellation), IndexNotReadyException
 * (transient dumb mode — a retryable condition of the IDE, not a bug in the tool) and
 * OutOfMemoryError (the VM heap is exhausted; "recording and continuing" is meaningless and
 * may itself fail). StackOverflowError and LinkageError stay ISOLATED on purpose — both are
 * tool-local failure modes (a runaway recursive visitor; a broken plugin classpath) that the
 * rest of the sweep can and should survive. After the first failure the tool stops visiting
 * (only the first exception per tool is recorded).
 *
 * [onToolFailure] receives the delegate's class so the caller can attribute the logged error
 * to the crashing inspection's plugin via `PluginException.createByClass` (the platform
 * convention used by `InspectionEngine.createVisitor`).
 */
internal class CrashIsolatingLocalInspectionTool(
    private val delegate: LocalInspectionTool,
    private val onToolFailure: (toolId: String, toolClass: Class<*>, error: Throwable) -> Unit,
) : LocalInspectionTool() {

    @Volatile
    private var failed = false

    private fun recordFailure(error: Throwable) {
        failed = true
        onToolFailure(delegate.shortName, delegate.javaClass, error)
    }

    /** Run [block]; rethrow control-flow/environment exceptions, record anything else and return [fallback]. */
    private inline fun <T> isolating(fallback: T, block: () -> T): T {
        if (failed) return fallback
        return try {
            block()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: IndexNotReadyException) {
            // Transient dumb mode, not a tool crash — propagate so the caller's smart read
            // action machinery can retry the sweep under smart mode.
            throw e
        } catch (e: OutOfMemoryError) {
            // VM-fatal: the heap is gone; isolation cannot meaningfully continue.
            throw e
        } catch (e: Throwable) {
            recordFailure(e)
            fallback
        }
    }

    // Identity is fully delegated so results, severities and EP lookup match the original tool.
    override fun getShortName(): String = delegate.shortName
    override fun getID(): String = delegate.id
    override fun getAlternativeID(): String? = delegate.alternativeID
    override fun getDisplayName(): String = delegate.displayName
    override fun getGroupDisplayName(): String = delegate.groupDisplayName
    override fun getStaticDescription(): String? = delegate.staticDescription
    override fun getLanguage(): String? = delegate.language
    override fun runForWholeFile(): Boolean = delegate.runForWholeFile()

    override fun isSuppressedFor(element: PsiElement): Boolean =
        // Called by the engine OUTSIDE its per-tool try/catch — a throw here would abort the sweep.
        isolating(fallback = false) { delegate.isSuppressedFor(element) }

    override fun isAvailableForFile(file: PsiFile): Boolean =
        isolating(fallback = false) { delegate.isAvailableForFile(file) }

    override fun inspectionStarted(session: LocalInspectionToolSession, isOnTheFly: Boolean) {
        isolating(Unit) { delegate.inspectionStarted(session, isOnTheFly) }
    }

    override fun inspectionFinished(session: LocalInspectionToolSession, problemsHolder: ProblemsHolder) {
        isolating(Unit) { delegate.inspectionFinished(session, problemsHolder) }
    }

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
        session: LocalInspectionToolSession,
    ): PsiElementVisitor {
        val inner = isolating<PsiElementVisitor>(PsiElementVisitor.EMPTY_VISITOR) {
            delegate.buildVisitor(holder, isOnTheFly, session)
        }
        // Preserve the engine's "skip this tool" semantics for EMPTY_VISITOR.
        if (inner === PsiElementVisitor.EMPTY_VISITOR) return PsiElementVisitor.EMPTY_VISITOR
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                // element.accept(inner) re-dispatches to the delegate's language-specific
                // visit methods — same call shape the engine itself uses.
                isolating(Unit) { element.accept(inner) }
            }
        }
    }
}
