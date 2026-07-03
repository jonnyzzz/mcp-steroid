# Adversarial review — fix/215-dumps-on-timeout (claude)

Scope: `git diff origin/fix/213-progress-indicator...HEAD` only (the #215 delta).
Files: `ExecutionDumpCapture.kt` (new), `McpScriptContextImpl.kt`, `ScriptExecutor.kt`,
`ExecutionDumpCaptureTest.kt` (new), `ScriptExecutorTest.kt`.
Platform sources checked at `~/Work/intellij/community`. No files modified; no gradle run.

## 1. Timeout-branch order and contract — PASS

Order in the `TimeoutCancellationException` branch (`ScriptExecutor.kt:269-284`) is exactly the
decided flow: `log.warn` (idea.log only) → `context.progressIndicator.cancel()`
(`ScriptExecutor.kt:277`) → `captureDiagnosticDumps(project, executionId, "timeout")`
(`ScriptExecutor.kt:282`) → `logRemappedException` → `reportFailed(timeoutFailureMessage(...))`
(`ScriptExecutor.kt:284`).

Every message added on the timeout path, traced:

- `log.warn(...)` — idea.log only, never the tool result.
- `progressIndicator.cancel()` — no message. Idempotent; the #213 watcher normally already
  cancelled it when the timeout cancelled the scope, so this is belt-and-braces as decided.
- `captureDiagnosticDumps` — writes two files via `writeCodeExecutionData` (discrete files, NOT
  `output.jsonl`/`appendExecutionEvent`, so nothing flows into the result), and one `log.info`
  line containing only the folder path (`ExecutionDumpCapture.kt:57-60`). No dump content can
  reach the tool result from here.
- `logRemappedException("Execution timed out", e, ...)` → `logMessage("ERROR: Execution timed
  out\n<remapped TCE stack trace>")` (`ScriptExecutor.kt:335-343`) — pre-existing base behavior,
  carries the TCE stack trace, not dump content.
- `reportFailed(timeoutFailureMessage(...))` — `"Execution timed out after N seconds. Diagnostic
  thread + coroutine dumps stored at <abs path>"` (`ScriptExecutor.kt:315-324`) — path only. The
  real builder (`ExecutionManager.kt:206-215`) prefixes `FAILED: ` and adds the text verbatim.

Catch chain vs base (`git show origin/fix/213-progress-indicator:...ScriptExecutor.kt`): the
CE-rethrow, PCE-rethrow, and `Throwable` branches are byte-identical; the only edits are the two
inserted statements and the message change inside the TCE branch. Dumps fire ONLY on TCE in
`executeCodeBlocks` (the modality-gate/modal-path captures at `ScriptExecutor.kt:193` and the 5
`McpScriptContextImpl` sites are pre-existing capture points, now upgraded — dimension 4).

## 2. NonCancellable correctness — PASS

`ExecutionDumpCapture.kt:54-56`: both strings are captured BEFORE the `withContext` block.

- `ThreadDumper.dumpThreadsToString()` is a plain Java static (ThreadDumper.java:35-39) — no
  suspension, cannot throw CE.
- `dumpCoroutines(stripDump = false)` is a plain (non-suspend) top-level fun
  (`coroutineDumper.kt:50`) that wraps its whole body in `catch (e: Throwable)` and returns an
  error STRING on internal failure, `null` only when probes are not installed — it cannot throw
  at all, let alone CE mid-capture. The `?: COROUTINE_DUMP_UNAVAILABLE_NOTE` fallback handles
  the null.
- The writes run under `withContext(NonCancellable + Dispatchers.IO)`. The Job element of the
  new context is `NonCancellable`, so the block is entered and completed even when the caller's
  job is already cancelled (the documented cleanup pattern). `writeCodeExecutionData`'s internal
  `withContext(Dispatchers.IO)` (`execution-storage ExecutionStorage.kt:132-138`) then parents
  under the NonCancellable scope's active job — its throw-on-entry-when-cancelled hazard (the
  reason NonCancellable is required, correctly stated in the KDoc) is neutralized.

No path found where a cancelled job aborts the write. Only edge: `project.executionStorage` on
an already-disposed project can throw `AlreadyDisposedException` (a PCE, hence CE), which the
helper rethrows — acceptable: the whole execution is dead then, and rethrowing CE is the
documented contract.

## 3. API stability — PASS

- `ThreadDumper.dumpThreadsToString()` — `platform/util/base/src/com/intellij/diagnostic/ThreadDumper.java:35`,
  `public static @NotNull String`, NO `@ApiStatus.Internal`; the class itself is unannotated.
  (The neighbor `dumpForDebug` at :29-33 IS `@Internal`, so the omission is deliberate.)
- `dumpCoroutines(...)` — `platform/util/base/src/com/intellij/diagnostic/coroutineDumper.kt:50`,
  top-level public fun, NO `@Internal` — while `COROUTINE_DUMP_HEADER`, `isCoroutineDumpEnabled`,
  `enableCoroutineDump` in the same file all carry `@Internal`; again clearly deliberate.
- `getThreadDumpInfo` — `ThreadDumper.java:61-62`, `@ApiStatus.Internal` — NOT referenced in the
  diff (only name-checked in the KDoc as off-limits).
- `DebugProbes.install()` — not called anywhere in the diff; mentioned only in the
  `COROUTINE_DUMP_UNAVAILABLE_NOTE` KDoc as explicitly forbidden.

## 4. Shared-helper adoption — PASS (with one regression folded into finding R1)

All 5 modal-path sites delegate through the `captureThreadDump` shim
(`McpScriptContextImpl.kt:350-352`): `closeModalDialogs` (:261), `modal-monitor` (:284),
`syncDocuments-timeout` (:316), `syncDocuments-modal-side-effect` (:325),
`$operation-requires-non-modal` (:336). The modality gate delegates directly
(`ScriptExecutor.kt:193`). File name `thread-dump-<reason>.txt` is preserved verbatim
(`ExecutionDumpCapture.kt:56`), and `coroutine-dump-<reason>.txt` is added at every site.

Inline-log reliance: swept tests, docs, prompts, `CLAUDE.md`/`AGENTS.md`/`IMPROVEMENTS.md` — no
test or doc greps idea.log for the inline dump text; every reference to thread dumps points at
the stored `thread-dump-*.txt` files or at external `jcmd` dumps. **Verdict: nothing relied on
the inline INFO dump; dropping it is a win (idea.log no longer bloated with full dumps).**
One narrow behavioral regression though: the old modal-path code logged the full dump at INFO
*before* attempting the write, so a failed write still left the dump recoverable in idea.log.
The new helper writes first and on failure logs only `e.message` — the captured dump strings are
lost entirely. See R1.

## 5. Failure isolation — CHANGE REQUESTED (minor)

`ExecutionDumpCapture.kt:56-58`: one `try` + one `withContext` block means a failed
`thread-dump-<reason>.txt` write (the exact injection in
`ScriptExecutorTest.testDumpCaptureFailureDoesNotMaskTimeoutError`) also skips the
`coroutine-dump-<reason>.txt` write and the path log line. Directory-squatting is artificial,
but per-file failures are not exhaustive of it (stale read-only file from a previous run,
partial disk-full). The coroutine dump is the novel diagnostic of this issue — losing it to an
unrelated thread-dump write failure undercuts the feature at exactly the moment it's needed.
Fix is two lines (`runCatching` per write, or two try blocks). **Verdict: required change, minor
severity — combined with the R1 idea.log-fallback point.**

## 6. Tests — PASS (with notes)

- **`testTimeoutWritesDumpsAndReportsFolderPathOnly` (`ScriptExecutorTest.kt:300-356`)** —
  timing is sound, not flaky: the stuck thread parks in 100 ms slices until an 8 s deadline; the
  dump is taken ~1 s after the 1 s timeout fires, so the thread is guaranteed alive and named in
  the dump. `delay(10_000)` is cancellation-cooperative so `withTimeout` returns promptly.
  Thread-leak checking cannot fail it either: `ThreadLeakTracker.waitForThread` waits up to 10 s
  (`WAIT_SEC = 10`, ThreadLeakTracker.java:198) and the daemon thread self-terminates at 8 s —
  worst case a few seconds of teardown wall time, never an AssertionError.
- **Engine tolerance** — yes, when the script engine is unavailable the dump assertions are
  silently skipped (`if (failure.contains("timed out"))`, :326); only `isFailed` is asserted.
  That is this class's established pre-existing convention (see :270-281) and CI runs with the
  engine (full `:ij-plugin:test` green exercises the real branch). Acceptable.
- **Would the path-only assertion catch an inlining regression?** Yes for the FAILED line: a
  full `ThreadDumper` dump always contains `at java.lang.` (every pooled thread carries
  `java.lang.Thread.run` frames), so `assertFalse(failure.contains("at java.lang."))` (:352)
  trips on any inlined thread dump; `failure.contains(dir.toString())` (:347) pins the path.
  Gaps (acceptable, noting for the record): a thread dump inlined into `builder.messages` (not
  the failure line) would not be caught — messages are only checked against the coroutine-dump
  header (:355) — and that header check is vacuous when debug probes are off.
- **`testDumpCaptureFailureDoesNotMaskTimeoutError` (:363-390)** — real failure injection (a
  directory squats `thread-dump-timeout.txt`, so `path.writeText` throws), no test-only seam.
  It correctly proves the base timeout message survives; it does NOT assert the "stored at"
  claim is absent — see R2.
- **`ExecutionDumpCaptureTest`** — pins the shared contract both callers rely on: both files
  written with historical names, coroutine file non-blank either way (environment-tolerant on
  probes), and swallow-on-failure. Good.

## 7. Comment path-glob sweep — PASS

Swept every `*/` occurrence in the diff: all are legitimate KDoc terminators or the single-line
copyright headers. The fixed spot reads `.idea/mcp-steroid/eid_<executionId>/`
(`ExecutionDumpCapture.kt:32`) — no glob. No other comment in the diff contains a `*`-ending
path segment before `/`. No recurrence found.

## 8. Everything else

- **`resolveExecutionDir` failure fallback** — present and justified (`ScriptExecutor.kt:317-322`):
  `resolveExecutionDir` is not a pure getter — it does `Files.createDirectories`
  (`execution-storage ExecutionStorage.kt:90-99`) and can throw; on failure the plain timeout
  message is reported. Correct.
- **Storage API use** — correct: the `String` payload resolves to the non-generic
  `writeCodeExecutionData(ExecutionId, String, String)` overload (more specific than the
  `reified T` one), same pattern as existing callers (`compilation-success.txt` etc.).
- **`McpServerIntegrationTest` KDoc staleness — confirmed** (`McpServerIntegrationTest.kt:938`):
  the KDoc quotes the old `reportFailed("Execution timed out after $timeout seconds")`. The
  test's assertions still pass (it checks `contains("after 2 second")`, satisfied by the
  extended message), so this is doc-only. See R3.
- **KDoc nit** — `ExecutionDumpCapture.kt:32` implies the folder is `eid_<executionId>`; in
  reality `eid_` is part of the *generated* id (`ExecutionStorage.kt:179`) and the folder is
  `<runDir>/<executionId>` verbatim (test ids have no prefix). Cosmetic.

## REQUIRED CHANGES

1. **(minor) Isolate the two dump writes and don't lose a captured dump on write failure** —
   `ExecutionDumpCapture.kt:56-58`. Wrap each `writeCodeExecutionData` independently so a failed
   thread-dump write cannot kill the coroutine-dump write; on a write failure, include the
   captured dump (or at least state which file failed) in the WARN — the pre-#215 modal-path
   code preserved the dump in idea.log on write failure, the new helper silently loses it.
2. **(minor) Don't claim "dumps stored at <path>" when the capture failed** —
   `ScriptExecutor.kt:315-324`. In the exact scenario `testDumpCaptureFailureDoesNotMaskTimeoutError`
   exercises, the FAILED line still asserts the dumps are stored while nothing was written. Have
   `captureDiagnosticDumps` return a success flag and append the dump sentence only when true
   (or soften to "diagnostics folder: <path>"), and pin it in that test.
3. **(doc) Refresh the stale KDoc quote** — `McpServerIntegrationTest.kt:938` still cites the
   old timeout message.

## VERDICT: APPROVE-WITH-CHANGES

The decided cancellation flow, the no-dump-content-in-result contract, API stability, the
NonCancellable design, and the shared-helper adoption are all correctly implemented and
well-tested. The three changes above are small, contained, and none blocks the core behavior.
