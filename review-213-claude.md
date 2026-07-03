# Adversarial review — fix/213-progress-indicator (issue #213, closes #179) — agent: claude

Reviewed: `git diff main...HEAD` (c10d1b5b), issues #213/#179/#221, IntelliJ community sources at
`~/Work/intellij/community` (platform target 2026.1 / 261 per `McpSteroidIdeTargets.kt:45`, verifier 262-EAP).
Static review only; no gradle run (matrix reported green by the implementer).

---

## Dimension 1 — McpExecutionProgressIndicator (sticky cancel)

**Verdict: CORRECT, one REQUIRED CHANGE (deterministic mid-run propagation).**

API stability, verified in community sources:

- `EmptyProgressIndicatorBase` is a public, non-`@Internal` class; only its **constructors** are
  `@ApiStatus.Obsolete` (`EmptyProgressIndicatorBase.java:38-46`). The implementer's claim is exact, and
  accepting the `@Obsolete` ctor is the right call — the platform's own `BridgeJobIndicatorBase.kt:22`
  extends the same base with `@Suppress("UsagesOfObsoleteApi")`, and there is no non-obsolete public way
  to construct an indicator.
- `StandardProgressIndicator` is public, no annotations (`StandardProgressIndicator.java:13`).

Sticky-cancel survives `cancel()`-then-`runProcess`, proven from source:

- `EmptyProgressIndicator.start()` resets `myCancellationRequester = null` (`EmptyProgressIndicator.java:34-37`)
  — the trap is real.
- `EmptyProgressIndicatorBase.start()` only flips `myRunState` (`EmptyProgressIndicatorBase.java:48-53`) and
  never touches cancellation; the subclass's `isCanceled()` reads only its own `@Volatile cancelled`
  (`McpExecutionProgressIndicator.kt:37-45`). `CoreProgressManager.runProcess` calls `start()` only when
  `!isRunning()`, and `STOPPED → start()` is legal, so sequential reuse (two `runInspectionsDirectly` calls
  in one script) is fine. `checkCanceled()` is `final` in the base and consults the overridden `isCanceled()`
  (`EmptyProgressIndicatorBase.java:73-83`). The class is final in Kotlin, satisfying the
  `StandardProgressIndicator` "all methods final" contract.

Missing overrides vs `BridgeJobIndicatorBase` — two, both judged non-defects:

- `getCancellationCause()` (Bridge keeps a cause `Throwable` and suppresses it into the PCE) — cosmetic;
  base returns `null` and `checkCanceled()` handles that.
- explicit `ModalityState` ctor — ours uses `defaultModalityState()`, which on the executor BGT is
  `nonModal`, identical to what the replaced `EmptyProgressIndicator()` produced inside `readAction`.

### REQUIRED CHANGE 1 — `cancel()` must call `ProgressManager.canceled(this)`

`McpExecutionProgressIndicator.cancel()` (`McpExecutionProgressIndicator.kt:41-43`) only sets the volatile
flag. Every *real* platform indicator additionally notifies the progress manager:
`EmptyProgressIndicator.cancel()` → `ProgressManager.canceled(this)` (`EmptyProgressIndicator.java:39-44`),
`AbstractProgressIndicatorBase.cancel()` → same (`AbstractProgressIndicatorBase.java:154-159`).
`ProgressManager.canceled(ProgressIndicator)` is public API (`ProgressManager.java:323`).

Why it matters here: the static hot path `ProgressManager.checkCanceled()` is a **no-op** unless the global
`ourCheckCanceledBehavior != NONE` (`CoreProgressManager.java:189-215`). That flag flips to
`INDICATOR_PLUS_HOOKS` only when (a) a thread *enters* `executeProcessUnderProgress` with an
already-cancelled indicator (`registerIndicatorAndRun` entry scan, `CoreProgressManager.java:806-828`), or
(b) `indicatorCanceled()` runs — which is reachable **only** via `ProgressManager.canceled()`
(`CoreProgressManager.java:882-905`). `BridgeJobIndicatorBase` gets away without it because in its
`coroutineToIndicator`/`jobToIndicator` use the coroutine Job sits in the thread context and
`doCheckCanceled` checks `Cancellation.ensureActive(job)` *before* consulting any indicator. Our
`inspectEx` FJP workers have **no such job** — that job-less-ness is the very bug #179 fixes.

Consequence with the current code: when the watcher cancels the indicator mid-sweep, a worker already
inside a subtask that polls only static `ProgressManager.checkCanceled()` (the
`SpinningCheckCanceledInspection` shape, and the #177 `VulnerableCodeUsagesInspection` shape) observes the
cancel only after some *other* thread happens to enter `executeProcessUnderProgress` under the cancelled
indicator (`ApplierCompleter.java:163` does this per subtask, so it usually happens while the sweep still
has queued subtasks). The *return* of `runInspectionsDirectly` is still deterministic — the caller/helper
threads poll the indicator instance directly between items (`JobLauncherImpl.java:176`,
`ApplierCompleter.java:118/130`) and JobLauncher does not wait for stragglers on PCE
(`JobLauncherImpl.java:212-220`) — but the **straggling worker itself may keep spinning**, and since
`inspectEx` workers run under the read lock, a leaked spinning worker blocks every subsequent
`writeAction` until it ends on its own. That is exactly the class of wedge this PR exists to kill.

Fix (one line, keeps every test, makes the `RunInspectionsDirectlyTimeoutTest` spin-bound deterministic):

```kotlin
override fun cancel() {
    cancelled = true
    ProgressManager.canceled(this)   // wake threads currently under this indicator (CoreProgressManager.indicatorCanceled)
}
```

---

## Dimension 2 — Watcher wiring in ScriptExecutor

**Verdict: CORRECT. Cancel-on-failure semantics: agree with the implementer.**

- `launch(start = CoroutineStart.UNDISPATCHED)` (`ScriptExecutor.kt:252`) executes the watcher body
  synchronously up to the `awaitCancellation()` suspension point before any script block runs — no
  cancel-before-watcher-armed window.
- Normal completion: `completedNormally = true` (`ScriptExecutor.kt:269`) is written *before*
  `watcher.cancel()` (`:272`) on the same coroutine; kotlinx establishes a happens-before edge between
  `cancel()` and the cancelled coroutine's `finally`, so the watcher reliably reads `true` and the
  indicator of a successful run stays un-cancelled. Race-free.
- Timeout / external cancel: parent cancellation resumes `awaitCancellation` directly (it does not wait for
  siblings), `completedNormally == false` → `indicator.cancel()`. Correct.
- Script failure (non-CE): the block throws → inner `finally` cancels the watcher with
  `completedNormally == false` → indicator cancelled. **Semantics verdict:** cancelling on failure is the
  right choice of the two the issue allowed. After a failure nothing legitimately consumes the indicator,
  and any blocking machinery the script left behind (a `runProcess` on another thread, a partially unwound
  sweep) should be told to unwind — the platform's own `ApplierCompleter` follows the same convention
  (cancels its indicator on task failure, `ApplierCompleter.java:247-248`). The code matches the chosen
  semantics.
- No leak: the inner `finally` always cancels the watcher, and `withTimeout`'s scope awaits its children
  before returning, so the watcher never outlives the execution.
- Nanosecond nit (no action): if the last block completes at exactly the deadline, kotlinx may deliver the
  timeout cancellation anyway; the watcher then cancels the indicator of a script whose blocks all ran. The
  execution is reported per `withTimeout`'s own resolution of that race, and nothing consumes the indicator
  after the body — harmless.
- KDoc nit: `McpScriptContext.progressIndicator` KDoc lists "disposal" as a cancellation trigger, but
  `Disposer.dispose(executionDisposable)` runs in `executeWithProgress`'s `finally` *after* the blocks
  finished — disposal alone never cancels the indicator; the real triggers are timeout, external cancel,
  and failure. Cosmetic over-claim.

---

## Dimension 3 — PCE handling

**Verdict: CORRECT and honest.**

- Verified: `ProcessCanceledException` extends `CancellationException` on this SDK —
  `platform/util/base/multiplatform/.../ProcessCanceledException.kt:24`
  (`open class ProcessCanceledException : CancellationException, ControlFlowException`). Target platform
  261/262 both have this.
- Therefore the `catch (e: CancellationException) { throw e }` (`ScriptExecutor.kt:280`) already rethrows
  every PCE (including `CeProcessCanceledException`), and the explicit PCE catch (`ScriptExecutor.kt:282`)
  is dead code on this SDK — exactly as its comment says. It is harmless and future-proofs the invariant.
- No path swallows a PCE into `catch (t: Throwable)`: `TimeoutCancellationException` is not a PCE, the CE
  clause precedes the Throwable clause, and PCE *is* CE. A throwable whose *cause* is a PCE (a script
  wrapping one) is reported as a failure — acceptable and unchanged from main.

---

## Dimension 4 — runInspectionsDirectly

**Verdict: CORRECT.**

- `McpScriptContextImpl.kt:524` passes `progressIndicator` to the **public** 9-arg `inspectEx` overload
  (`InspectionEngine.java:116`; the 10-arg one at `:131` is `@ApiStatus.Internal` and is not used). The
  suspend `readAction` wrapper is retained (only the indicator argument changed inside it), and the
  `EmptyProgressIndicator` import is gone (replaced by `ProgressIndicator`).
- Cancel does unwind the sweep: `inspectEx` hands the indicator to
  `JobLauncher.invokeConcurrentlyUnderProgress` (`InspectionEngine.java:126/166`); workers run each subtask
  via `executeProcessUnderProgress(toRun, progressIndicator)` (`ApplierCompleter.java:163`), which installs
  it as the thread's current indicator, and both JobLauncher and the inspections poll it
  (`JobLauncherImpl.java:176`, `wrapper.checkCanceled()` at `ApplierCompleter.java:118/130`, plus every
  in-inspection `ProgressManager.checkCanceled()` once the global behavior is engaged — see REQUIRED
  CHANGE 1 for the determinism caveat on that last path). On PCE, `invokeConcurrentlyUnderProgress`
  returns without waiting for stragglers (`JobLauncherImpl.java:212-220`), so `executeWithProgress`
  returns promptly — the #213 acceptance behavior.

---

## Dimension 5 — Prompt corpus, KtBlock, kotlin-cli import, LineMapping

**Verdict: CORRECT; #221 delta is exactly the filed state.**

- Fences: `find-duplicates.md:221`, `inspect-and-fix.md:68` and `:182` all use `progressIndicator` and the
  three `EmptyProgressIndicator` imports are removed; no stray import remains (grep clean). The second
  inspect-and-fix fence (other-project variant) correctly keeps using *this* execution's indicator while
  parameterizing everything else by `target` — semantically right, since the cancellation being bridged is
  this execution's timeout.
- Surface lists consistent across all four surfaces: `coding-with-intellij-context-api.md:19` (exists-list)
  and `:43` (core properties), `execute-code-tool-description.md:73`, `skill.md:236`.
- kotlin-cli default import (`CodeWrapperForCompilation.kt` — single class
  `com.intellij.openapi.progress.ProgressIndicator`, not a wildcard): no simple-name clash — none of the
  existing wildcard imports (`project.*`, `application.*`, `vfs.*`, `editor.*`, `fileEditor.*`, `command.*`,
  `psi.*`, `psi.search.*`, `psi.search.searches.*`, `psi.util.*`, `kotlinx.coroutines.*`) exports a
  `ProgressIndicator` type. A user script re-importing the same FQN is a redundant duplicate (warning-level
  at most); a user importing a *different* `ProgressIndicator` FQN would conflict, but no such class exists
  on the script classpath.
- LineMapping / #221: confirmed the wrapper hardcodes offsets for **12** default imports
  (`CodeWrapperForCompilation.kt`, mapping comment "Lines 1..12") while the list now has **16**. This PR
  widens the pre-existing skew from 3 to 4 lines for both compile-error and runtime-stack remapping
  (`logRemappedException` uses the same mapping). That +1 is **already the state filed in #221** ("now has
  16 (#213 added ProgressIndicator)") — no unfiled regression, but #221 should be fixed promptly since
  `input.kt:N` references are a primary agent diagnostic (#156).

---

## Dimension 6 — Tests

**Verdict: SOUND; the inspection-timeout test pins the fix; one flakiness risk tied to REQUIRED CHANGE 1.**

- `McpExecutionProgressIndicatorTest`: pins the exact contracts — fresh-not-cancelled, PCE from
  `checkCanceled`, sticky across `start()`/`stop()`, and the full `runProcess`-on-cancelled-indicator path
  (which exercises the `registerIndicatorAndRun` entry scan, so it passes even without REQUIRED CHANGE 1).
- `McpScriptContextTest.testProgressIndicatorNotCancelledForLiveContext` + `assertSame` (per-execution
  instance): good.
- `RunInspectionsDirectlyTimeoutTest` — **this is the test that pins the fix.** Would it fail on main? Yes,
  and it even *runs* on main (its script never references `progressIndicator`, only
  `runInspectionsDirectly`): on main the sweep gets an un-cancellable `EmptyProgressIndicator`, the spinner
  runs its full 45 s cap, `lastSpinNanos ≈ 45 s` fails the `< 30 s` assertion, and the wall-clock stays
  inside `timeoutRunBlocking(120s)` so it fails cleanly rather than wedging. Tool-enablement failure mode
  is also handled: if the inspection weren't enabled, the sweep completes fast → `UNREACHABLE` printed →
  the isError/timeout asserts fail with the printed output, and `spinNanos == -1` fails its dedicated
  "was it enabled?" assert. 45 s cap < 120 s harness bound < the 45 s test-suite cap mentioned in the
  prompt — consistent.
  **Flakiness risk (real, bounded):** the `< 30 s` spin assertion measures the *straggling worker's*
  unwind, which today depends on the global `checkCanceled` behavior being flipped by other subtasks still
  entering `executeProcessUnderProgress` after the 2 s cancel (see Dimension 1). On a fast machine where
  every other inspection finishes before 2 s, the spinner is the last subtask and can spin to the 45 s cap
  → assertion fails. REQUIRED CHANGE 1 (`ProgressManager.canceled(this)`) makes this deterministic via
  `indicatorCanceled`'s thread marking.
- `ScriptExecutorTest.testTimeoutCancelsProgressIndicatorAndUnwindsBlockingLoop`: valid end-to-end, but
  note it may be a weaker pin than its KDoc claims — the loop runs on the script's own coroutine thread,
  and `doCheckCanceled` checks `Cancellation.ensureActive(currentJob)` *before* the indicator
  (`CoreProgressManager.java:196-207`), so if the thread context carries the execution job the loop
  unwinds via the job even with the watcher deleted. Harmless (the inspection test is the strong pin), but
  don't treat this test alone as proof of the bridge.
- `NoEmptyProgressIndicatorUsageTest`: the fence scanner's toggle
  (`insideKotlinFence = !insideKotlinFence && …startsWith("kotlin")`) is sound for this corpus — any ```
  line closes an open kotlin fence, non-kotlin fences never open one, and the corpus contains no
  four-backtick fences (grep clean) that could confuse it. Comment-line skipping
  (`// | * | /*` prefixes) fails safe: a block-comment interior line not starting with `*`, or a trailing
  comment on a code line, would produce a false *violation* (too strict), never a false pass. The
  `EmptyProgressIndicator(?!Base)` regex correctly exempts the base class.

---

## Dimension 7 — Anything missed

- **Tenet-3 gate**: the new `McpScriptContext` member carries the gate justification in its KDoc
  (`McpScriptContext.kt:91-92`) and #213's Decisions delegate the gate to the pre-merge quorum — this
  review is part of that quorum. Satisfied.
- **#179 closure completeness**: the indicator path is fully implemented; #179's "recommended companion"
  (a bounded sub-timeout inside `runInspectionsDirectly`) was explicitly descoped in #213's Decisions.
  Complete as scoped.
- **Other severed-cancellation sites**: grep over `ij-plugin/src/main` finds no remaining
  `EmptyProgressIndicator` constructions, no `DaemonProgressIndicator`, no other
  `invokeConcurrentlyUnderProgress`/`runProcess(ProgressIndicator)` call sites (the two `runProcess` hits
  are unrelated `GeneralCommandLine` process helpers). The regression guard keeps it that way.

---

## REQUIRED CHANGES

1. `McpExecutionProgressIndicator.cancel()` — add `ProgressManager.canceled(this)` after setting the flag
   (`McpExecutionProgressIndicator.kt:41-43`). Rationale in Dimension 1: without it, a worker blocked in a
   static-`checkCanceled` poll loop observes a mid-run cancel only probabilistically
   (`CoreProgressManager.doCheckCanceled` no-ops at `CheckCanceledBehavior.NONE`;
   `indicatorCanceled` is reachable only via `ProgressManager.canceled`). This is the platform convention
   for every concrete indicator (`EmptyProgressIndicator.java:39-44`,
   `AbstractProgressIndicatorBase.java:154-159`); `BridgeJobIndicatorBase` omits it only because its
   callers have the Job in the thread context — ours don't (that's the #179 bug itself). Also
   de-flakes the `RunInspectionsDirectlyTimeoutTest` spin assertion and prevents a cancelled sweep's
   straggler worker from pinning the read lock.

Advisory (no change required): note in `testTimeoutCancelsProgressIndicatorAndUnwindsBlockingLoop`'s KDoc
that the job path may also unwind the loop; fix #221 promptly (this PR widens the agent-visible line skew
3 → 4, exactly as filed).

---

## VERDICT: APPROVE-WITH-CHANGES
