# Adversarial Review: fix/213-progress-indicator

Independent static and platform review of `git diff main...HEAD`. No Gradle or IDE tasks run per project constraints.

## REQUIRED CHANGES

1. **Fix Line Mapping Drift in `CodeWrapperForCompilation`**
   - **File:** `kotlin-cli/src/main/kotlin/com/jonnyzzz/mcpSteroid/koltinc/CodeWrapperForCompilation.kt:150-189`
   - **Issue:** Adding the default import `"import com.intellij.openapi.progress.ProgressIndicator"` increases the size of `defaultImports` from 15 to 16. However, the line mapping offsets are still hardcoded: `val wrappedLine = 15 + i` for imports, and `val wrappedLine = 23 + n + i` for user code, assuming `defaultImports` is exactly 12 lines (as stated in comments). On `main`, this discrepancy already caused a drift of 3 lines (pre-existing bug #221). With this PR, the drift worsens to 4 lines, making compiler error reporting and stacktrace line mapping even more inaccurate for agents.
   - **Resolution:** Replace the hardcoded offsets with dynamically calculated start lines based on `defaultImports.size`:
     ```kotlin
     val k = defaultImports.size
     
     // Map user import lines
     for (i in extracted.importLineNumbers.indices) {
         val wrappedLine = k + 3 + i
         mapping[wrappedLine] = extracted.importLineNumbers[i]
     }

     // Map user code lines (non-import)
     for (i in extracted.otherLineNumbers.indices) {
         val wrappedLine = k + 11 + n + i
         mapping[wrappedLine] = extracted.otherLineNumbers[i]
     }
     ```
     Also update the KDoc comments on lines 152-168 to reflect the dynamic layout.

2. **Sync `FindDuplicatesRecipeTest` with Prompts Update**
   - **File:** `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/execution/FindDuplicatesRecipeTest.kt:106,144`
   - **Issue:** The prompt `prompts/src/main/prompts/ide/find-duplicates.md` was correctly updated to use `progressIndicator` and remove `EmptyProgressIndicator` imports. However, `FindDuplicatesRecipeTest.kt`, which replicates this recipe verbatim to verify its correctness, was not updated. It still imports and instantiates `EmptyProgressIndicator()`. This means the test does not actually run the new recipe as published.
   - **Resolution:** Remove `import com.intellij.openapi.progress.EmptyProgressIndicator` from the test's mock recipe string, and replace `EmptyProgressIndicator()` with `progressIndicator` inside the `inspectEx` call to keep recipe tests perfectly aligned with the prompt.

---

## Dimension Verdicts

### 1. McpExecutionProgressIndicator: PASS
- **Sticky-Cancel Correctness:** Confirmed. Unlike `EmptyProgressIndicator.start()`, which clears the cancellation flag (`EmptyProgressIndicator.java:33-37`), `EmptyProgressIndicatorBase.start()` only transitions the run state without clearing cancellation (`EmptyProgressIndicatorBase.java:47-53`). `McpExecutionProgressIndicator` overrides both `cancel()` and `isCanceled()`, ensuring that once `cancel()` is called, the cancellation status is sticky and survives any subsequences of `start()` or being passed to `ProgressManager.runProcess(...)`.
- **Public-Stable APIs:** Both `EmptyProgressIndicatorBase` and `StandardProgressIndicator` are public and stable APIs in `com.intellij.openapi.progress`.
- **Constructor Obsolescence:** The `@Obsolete` annotation on the `EmptyProgressIndicatorBase` constructor is accepted. As noted in JetBrains docs and the source file, there is no public non-obsolete way to instantiate a progress indicator. Constructing one is the standard, documented approach when bridging coroutine cancellation to blocking, indicator-polling APIs.
- **Comparison to `BridgeJobIndicatorBase`:** It mirrors `BridgeJobIndicatorBase` perfectly. The only difference is that `McpExecutionProgressIndicator` does not override `getCancellationCause()` (it returns `null` via the superclass). This is correct and harmless because `EmptyProgressIndicatorBase.checkCanceled()` operates correctly when `isCanceled()` is true regardless of the presence of a cancellation cause (`EmptyProgressIndicatorBase.java:73-83`).

### 2. Watcher Wiring in ScriptExecutor: PASS
- **Race-Free Setup:** The watcher coroutine is launched with `CoroutineStart.UNDISPATCHED`, meaning it runs immediately on the executing thread and suspends at `awaitCancellation()` before any script block executes. This guarantees that the watcher is armed prior to the execution of any block.
- **Completed Normally Flag:** The `completedNormally` local variable is initialized to `false` and is updated to `true` only upon successful execution of all blocks. The watcher's `finally` block cancels the progress indicator only if `completedNormally` is `false`. Since `watcher.cancel()` triggers a volatile/CAS state change inside the Job implementation, there is a strict happens-before boundary that ensures thread-safety.
- **Cancellation Semantics:**
  - **Success:** `completedNormally` is `true`. `watcher.cancel()` runs, and the progress indicator stays uncancelled.
  - **Timeout / External Cancel:** `withTimeout` cancels the parent coroutine scope, which cancels the `watcher`. The watcher's `finally` block runs with `completedNormally = false`, cancelling the progress indicator.
  - **Script Failure:** Non-CE script failures abort the try block. The `finally` block executes `watcher.cancel()`. Because `completedNormally` is still `false`, the progress indicator is cancelled. Cancelling on non-CE script failure is the correct and superior semantic: it ensures that any background asynchronous tasks or worker threads polling the indicator are terminated immediately once the script fails.
- **Watcher Cleanup:** `watcher.cancel()` is called inside the `finally` block of the execution path, guaranteeing that the coroutine is cleaned up and never leaked.

### 3. PCE Handling: PASS
- **Exception Hierarchy:** Platform sources confirm that `ProcessCanceledException` extends Kotlin's `java.util.concurrent.CancellationException` (`ProcessCanceledException.kt:24`).
- **Dead Code as Documentation:** Because PCE extends `CancellationException`, the preceding `catch (e: CancellationException)` block already catches and rethrows it (`ScriptExecutor.kt:280-281`). The explicit `catch (e: ProcessCanceledException)` block (`ScriptExecutor.kt:282-289`) is indeed dead code under this SDK, but it is harmless and serves as crucial, self-documenting enforcement of the "never swallow PCE" rule. It also acts as a safeguard against any future platform SDK exception hierarchy modifications.

### 4. runInspectionsDirectly Integration: PASS
- **Wiring & Imports:** The import and instantiation of `EmptyProgressIndicator` are completely removed from `McpScriptContextImpl.kt`. The helper `runInspectionsDirectly` passes `context.progressIndicator` (an instance of `McpExecutionProgressIndicator`) to `InspectionEngine.inspectEx`.
- **API Unwinding:** Analysis of `InspectionEngine.java` sources confirms that the passed `ProgressIndicator` is propagated to `inspectElements` and passed to `JobLauncher.invokeConcurrentlyUnderProgress` (`InspectionEngine.java:150, 403`). The concurrency launcher runs worker threads which periodically poll `ProgressManager.checkCanceled()`. When our watcher cancels the indicator, all worker threads throw `ProcessCanceledException` immediately, successfully unwinding the inspection sweep.
- **Read Action Retained:** The suspend `readAction` wrapper is correctly retained around `inspectEx`.

### 5. Prompt Corpus & KtBlock Fences: PASS (With LineMapping required change)
- **Fences Verification:** Verified that all markdown prompts (`find-duplicates.md`, `inspect-and-fix.md`) have been updated to replace `EmptyProgressIndicator()` with `progressIndicator` and have removed their imports. No stray imports of `EmptyProgressIndicator` remain.
- **Surface Consistency:** The surface listings are perfectly consistent across `prompt/skill.md`, `coding-with-intellij-context-api.md`, and `execute-code-tool-description.md`.
- **Import Clash Risk:** The default import is added as a single class import (`"import com.intellij.openapi.progress.ProgressIndicator"`) rather than a wildcard, minimizing simple-name clash risks.

### 6. Tests Analysis: PASS
- **Tests Coverage:** The tests are exceptionally comprehensive and beautifully written.
  - `McpExecutionProgressIndicatorTest` pins sticky-cancel behavior across `start()`, `stop()`, and `runProcess()`.
  - `McpScriptContextTest` verifies that the progress indicator is not cancelled for a live context and uses the same instance.
  - `ScriptExecutorTest` verifies that success does not cancel the indicator, and that a 2s timeout successfully unwinds a blocking `checkCanceled()` loop.
- **RunInspectionsDirectlyTimeoutTest:**
  - This is an outstanding test. It registers a custom spinning inspection (`SpinningCheckCanceledInspection`) that blocks the first visited element in a polling loop for up to 45s.
  - The script executes `runInspectionsDirectly` under a 2s timeout.
  - It asserts that the script fails with a timeout error and that the inspection's actual spin duration was `< 30s` (it unwound immediately on cancellation).
  - It carries an assertion checking if the inspection ran (`assertTrue(..., spinNanos >= 0)`), preventing false passes due to tool-enablement failures.
  - This test would fail on `main` because `inspectEx` would receive a throwaway `EmptyProgressIndicator` that ignores the timeout, causing the inspection to spin for the full 45s cap and violating the `< 30s` assertion. It perfectly pins the fix.
- **NoEmptyProgressIndicatorUsageTest:**
  - The static guard test correctly skips comment/KDoc lines, allowing documentation warnings while blocking code usages.
  - The Markdown parser correctly identifies ` ```kotlin ` openers and ensures no regression in prompt recipes.

### 7. Other Findings & Closure Completeness: PASS (With notes)
- **Tenet-3 Compliance:** The KDoc comments in `McpScriptContext.kt:57-88` explicitly address the compliance of this new API member with PHILOSOPHY.md Tenet 3, justifying it by the fact that execution cancellation state is plugin-owned and cannot be implemented within a script.
- **Other Severed Cancellation Sites:** A search of the codebase shows that the only other progress indicator usage is `DumbProgressIndicator.INSTANCE` inside `Diff.kt:26`. This is a short, synchronous diff computation and does not pose a cancellation-severing risk. There are no other occurrences of `EmptyProgressIndicator()` in production Kotlin.

---

## Final Verdict

**VERDICT: APPROVE-WITH-CHANGES**

The branch `fix/213-progress-indicator` introduces a highly elegant, correct, and robust fix for issues #213 and #179. The required changes do not block approval of the core cancellation architecture but should be resolved before merging to prevent worsening the pre-existing LineMapping offset bug and to keep mock recipe tests fully aligned with the published markdown prompt recipes.
