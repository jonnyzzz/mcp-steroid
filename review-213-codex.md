# Review: fix/213-progress-indicator

Independent static/platform review of `git diff main...HEAD`; no Gradle or IDE test tasks run per prompt.

## REQUIRED CHANGES

1. Fix or explicitly remove the new `LineMapping` regression delta before merge.

   `kotlin-cli/src/main/kotlin/com/jonnyzzz/mcpSteroid/koltinc/CodeWrapperForCompilation.kt:15-34` now has 16 default imports after adding `ProgressIndicator`, but the mapping logic still hardcodes user imports at wrapped line 15 and user code at `23 + N` (`CodeWrapperForCompilation.kt:150-181`). On `main` the same bug already existed with 15 imports while the comments assumed 12, so #221 is correctly filed. This PR still makes the agent-visible compiler/stacktrace remap one line worse than `main`. Either derive offsets from `defaultImports.size`, or drop the default import and make scripts type `com.intellij.openapi.progress.ProgressIndicator` if they need an explicit type.

## Dimension Verdicts

1. **McpExecutionProgressIndicator: PASS.**

   `McpExecutionProgressIndicator` uses `EmptyProgressIndicatorBase` plus `StandardProgressIndicator` and a sticky volatile flag (`ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/McpExecutionProgressIndicator.kt:31-39`). Platform source confirms `EmptyProgressIndicatorBase.start()` does not clear cancellation while `EmptyProgressIndicator.start()` does (`EmptyProgressIndicatorBase.java:47-53`, `EmptyProgressIndicator.java:33-37`). `StandardProgressIndicator` is public and only documents final standard cancel semantics (`StandardProgressIndicator.java:4-13`). The only obsolete use is the base constructor (`EmptyProgressIndicatorBase.java:37-44`), as the implementation notes say.

   Compared with internal `BridgeJobIndicatorBase`, the plugin copy intentionally omits only rich cancellation-cause tracking (`BridgeJobIndicatorBase.kt:28-46`). That is diagnostic-only for this use; `checkCanceled()` behavior is still correct because the base final method consults `isCanceled()` (`EmptyProgressIndicatorBase.java:73-83`). No missing behavioral override found.

2. **Watcher wiring in ScriptExecutor: PASS.**

   The watcher starts `UNDISPATCHED`, suspends in `awaitCancellation()`, and cancels the per-execution indicator from `finally` unless `completedNormally` is true (`ScriptExecutor.kt:238-273`). Normal completion sets `completedNormally = true` before cleanup, so the indicator remains uncancelled on success. Timeout and external coroutine cancellation cancel the withTimeout job, resume the watcher, and cancel the indicator. Non-CE script failure also cancels it because `completedNormally` remains false; I think that is the right semantic because after a failed script execution, any lingering indicator-polling work should stop rather than continue past the failed tool call.

   Cleanup is bounded: `watcher.cancel()` runs in `finally`, and because the watcher is a child of the timeout scope there is no leaked coroutine. Execution runs on the app executor (`ScriptExecutor.kt:101-105`), not the EDT, so the watcher is not starved by a single blocked UI thread in the reviewed path.

3. **PCE handling: PASS.**

   Platform source confirms `ProcessCanceledException` extends Kotlin `CancellationException` (`ProcessCanceledException.kt:24`) and `CeProcessCanceledException` extends PCE (`CeProcessCanceledException.java:13-17`). Therefore `catch (e: CancellationException)` already rethrows PCE at `ScriptExecutor.kt:280-281`, and the explicit `catch (e: ProcessCanceledException)` at `ScriptExecutor.kt:282-289` is documented dead code on this SDK. It is harmless and preserves the intended rule if the hierarchy ever changes.

4. **runInspectionsDirectly: PASS.**

   The production `EmptyProgressIndicator()` import and call are gone; `runInspectionsDirectly` passes `progressIndicator` into `InspectionEngine.inspectEx` (`McpScriptContextImpl.kt:513-525`) while retaining suspend `readAction` (`McpScriptContextImpl.kt:474`, `538`). Platform `inspectEx` forwards that indicator into `inspectElements` and `JobLauncher.invokeConcurrentlyUnderProgress` (`InspectionEngine.java:116-150`, `304-404`). `JobLauncherImpl` wraps the supplied indicator, checks it while distributing/waiting, and re-checks the original progress on PCE (`JobLauncherImpl.java:60-76`, `115-131`, `171-178`). Inspection visitors that poll `ProgressManager.checkCanceled()` therefore unwind once the script watcher cancels the indicator.

5. **Prompt corpus + KtBlock surface: PASS with the LineMapping required change above.**

   The changed fences now pass `progressIndicator` and drop `EmptyProgressIndicator` imports in `inspect-and-fix.md:15-70`, `inspect-and-fix.md:144-184`, and `find-duplicates.md:213-223`. Surface listings are consistent in `prompt/skill.md:236`, `coding-with-intellij-context-api.md:13-19` and `43-44`, and `execute-code-tool-description.md:73`. `rg` finds no `EmptyProgressIndicator` in production Kotlin or prompt Kotlin fences except KDoc/comment mentions and `EmptyProgressIndicatorBase`.

   The new default import is a single class, not a wildcard (`CodeWrapperForCompilation.kt:24-26`). Simple-name clash risk is low but real if a script imports or declares another `ProgressIndicator`; agents can alias/FQN that case.

6. **Tests: PASS.**

   Sticky-cancel behavior is directly pinned, including `runProcess` after pre-cancel (`McpExecutionProgressIndicatorTest.kt:37-68`). Success does not cancel the indicator (`ScriptExecutorTest.kt:213-233`). Timeout unwinds an indicator-polling loop (`ScriptExecutorTest.kt:248-281`). `RunInspectionsDirectlyTimeoutTest` is meaningful: on `main`, the same test logic would spin to the 45s cap because `inspectEx` receives an uncancelled `EmptyProgressIndicator`, so the `<30s` spin assertion would fail (`RunInspectionsDirectlyTimeoutTest.kt:82-118`, `130-163`).

   Flakiness notes: the 45s cap / 30s assertion leaves reasonable room around a 2s timeout; the `spinNanos >= 0` assertion will catch inspection-enablement failures instead of silently passing. `NoEmptyProgressIndicatorUsageTest` comment-line skipping is sound for production KDoc/comment mentions, and its prompt-fence parser correctly handles ` ```kotlin[...] ` openers and closing fences (`NoEmptyProgressIndicatorUsageTest.kt:41-95`).

7. **Anything missed / closure completeness: PASS with notes.**

   Tenet-4/PHILOSOPHY gating is acknowledged in KDoc (`McpScriptContext.kt:57-88`) and justified by plugin-owned execution cancellation state; this is one of the rare context members that cannot be recovered by a prompt recipe alone. #179 is closed by replacing the throwaway indicator in the exact offending `inspectEx` call. I did not find another production `EmptyProgressIndicator` site. `Diff.kt` uses `DumbProgressIndicator.INSTANCE` (`Diff.kt:21-27`), but that is a pre-existing small diff-computation path, not this inspection timeout sever.

   Stale cleanup note: `FindDuplicatesRecipeTest` still has a copied old recipe using `EmptyProgressIndicator()` (`FindDuplicatesRecipeTest.kt:100-145`). It is not agent-visible and is outside the new guard's stated scope, so I am not blocking on it, but it should be updated to keep recipe tests aligned with the shipped prompt.

## Final Verdict

VERDICT: APPROVE-WITH-CHANGES

The cancellation fix itself is correct and addresses #213/#179. The required change is the branch-local `LineMapping` worsening from the added default import; resolve that or explicitly decide #221 is accepted follow-up debt before merging.
