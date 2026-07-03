# Review: issue #215 timeout diagnostic dumps

Reviewer: codex

Scope reviewed: `git diff origin/fix/213-progress-indicator...HEAD` on `fix/215-dumps-on-timeout`, plus `gh issue view 215 -R jonnyzzz/mcp-steroid`. I did not run Gradle, per prompt.

## Required Changes

None.

## 1. Timeout Branch Order And Result Contract

Verdict: PASS.

`ScriptExecutor.executeCodeBlocks` catches only `TimeoutCancellationException` for the timeout diagnostic path at `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/ScriptExecutor.kt:269`. The functional order is correct: cancel the execution progress indicator first at `ScriptExecutor.kt:277`, capture dumps at `ScriptExecutor.kt:282`, then report failure at `ScriptExecutor.kt:284`. A `log.warn` precedes the cancel at `ScriptExecutor.kt:271`; I do not count that as a contract violation because no dump capture or result mutation happens before `indicator.cancel()`.

The catch chain remains the decided shape: `TimeoutCancellationException` at `ScriptExecutor.kt:269`, `CancellationException` rethrow at `ScriptExecutor.kt:285`, `ProcessCanceledException` rethrow at `ScriptExecutor.kt:287`, then generic `Throwable` at `ScriptExecutor.kt:295`. Since `ProcessCanceledException` extends `CancellationException` on the verified platform, the CE branch catches current PCEs first, but it rethrows them, so the behavior is still correct and the explicit PCE branch documents the invariant.

Timeout dumps fire only on TCE: the `"timeout"` capture call appears only in the TCE branch at `ScriptExecutor.kt:282`. The CE/PCE branches do not capture dumps.

Tool-result trace: `captureDiagnosticDumps` writes files and logs only a folder path to IDEA log at `ExecutionDumpCapture.kt:57-60`; it does not call `resultBuilder`. The MCP-visible timeout additions are the timeout exception stack message via `logRemappedException` at `ScriptExecutor.kt:283` and the failed line via `reportFailed(timeoutFailureMessage(...))` at `ScriptExecutor.kt:284`. `timeoutFailureMessage` appends only `project.executionStorage.resolveExecutionDir(executionId).toAbsolutePath()` at `ScriptExecutor.kt:315-323`. No dump content is added to the tool result by the changed code.

## 2. NonCancellable Correctness

Verdict: PASS.

The string capture is done before the non-cancellable write block at `ExecutionDumpCapture.kt:50-51`. That matches the issue design: `ThreadDumper.dumpThreadsToString()` is a plain non-suspending static call, and `dumpCoroutines(stripDump = false)` is also non-suspending and catches its own unexpected `Throwable` in the platform implementation (`/Users/jonnyzzz/Work/intellij/community/platform/util/base/src/com/intellij/diagnostic/coroutineDumper.kt:50-65`).

The actual file writes run under `withContext(NonCancellable + Dispatchers.IO)` at `ExecutionDumpCapture.kt:53-56`. The storage implementation nests `withContext(Dispatchers.IO)` in `execution-storage/src/main/kotlin/com/jonnyzzz/mcpSteroid/storage/ExecutionStorage.kt:132-135`; because the caller has already installed `NonCancellable` as the Job, the nested dispatch should not abort merely because the original execution job was cancelled. Filesystem failures still throw and are swallowed/logged by the helper, as intended.

## 3. API Stability

Verdict: PASS.

Verified against local platform sources:

- `ThreadDumper.dumpThreadsToString()` is public and unannotated at `/Users/jonnyzzz/Work/intellij/community/platform/util/base/src/com/intellij/diagnostic/ThreadDumper.java:35-39`.
- `ThreadDumper.getThreadDumpInfo(...)` is `@ApiStatus.Internal` at `ThreadDumper.java:61-62` and is not used by the plugin diff.
- `dumpCoroutines(...)` is a public top-level function with no `@Internal` annotation at `/Users/jonnyzzz/Work/intellij/community/platform/util/base/src/com/intellij/diagnostic/coroutineDumper.kt:50`.
- `DebugProbes.install()` is not called by the plugin diff. The only grep hit is the explanatory comment at `ExecutionDumpCapture.kt:21`.

## 4. Shared Helper Adoption

Verdict: PASS.

All five existing `McpScriptContextImpl.captureThreadDump` modal-path reasons still delegate through the single helper:

- `closeModalDialogs` at `McpScriptContextImpl.kt:261`
- `modal-monitor` at `McpScriptContextImpl.kt:284`
- `syncDocuments-timeout` at `McpScriptContextImpl.kt:316`
- `syncDocuments-modal-side-effect` at `McpScriptContextImpl.kt:325`
- `"$operation-requires-non-modal"` at `McpScriptContextImpl.kt:336`

The private wrapper delegates to `captureDiagnosticDumps(project, executionId, reason)` at `McpScriptContextImpl.kt:350-351`. The modality gate also delegates directly at `ScriptExecutor.kt:193`. The historical thread-dump filenames are preserved by the helper's `thread-dump-$reason.txt` write at `ExecutionDumpCapture.kt:54`, and coroutine dumps are added beside them at `ExecutionDumpCapture.kt:55`.

Behavior change: the old modal helper logged the entire thread dump inline to IDEA log; the new helper logs only the absolute execution folder path at `ExecutionDumpCapture.kt:57-60`. I grepped tests/docs for reliance on the inline IDEA-log dump and found no dependency. Existing docs/prompts describe the execution-folder artifact, not parsing inline logs.

## 5. Failure Isolation

Verdict: ACCEPTABLE, not a required change.

The helper has one try block and sequential writes, so a failed `thread-dump-<reason>.txt` write prevents the coroutine dump write in the same call (`ExecutionDumpCapture.kt:53-55`, caught at `ExecutionDumpCapture.kt:63-64`). That is less diagnostic than independent best-effort writes would be.

I do not require a change for this issue because the failure is isolated from the user-visible timeout report, which is the primary safety property. The real squatting tests cover that failure mode. Independent per-file writes would be a reasonable follow-up hardening, but current behavior is acceptable for #215.

## 6. Tests

Verdict: PASS with minor coverage caveat.

`ScriptExecutorTest.testTimeoutWritesDumpsAndReportsFolderPathOnly` uses a daemon thread parked with `LockSupport.parkNanos` for an 8s deadline and a 1s script timeout (`ScriptExecutorTest.kt:300-358`). That shape is reasonable: the thread ignores interrupts, remains alive long enough for the dump, and cannot hang the test process. The assertions are engine-tolerant: if runtime is not reached and the failure does not contain `"timed out"`, the dump/path assertions are skipped at `ScriptExecutorTest.kt:326`. That is a silent skip of the #215 assertions in an engine-unavailable environment, but it follows the existing class convention and should exercise the contract on normal `:ij-plugin:test`.

`ScriptExecutorTest.testDumpCaptureFailureDoesNotMaskTimeoutError` uses a real directory-squatting injection for `thread-dump-timeout.txt` at `ScriptExecutorTest.kt:367-389`, not a test-only branch. Good.

`ExecutionDumpCaptureTest` covers the helper contract directly: both files are written at `ExecutionDumpCaptureTest.kt:27-50`, and write failure is swallowed at `ExecutionDumpCaptureTest.kt:54-60`.

Path-only regression coverage is adequate but not exhaustive. The test would catch dump content in the FAILED line through the failure-message checks at `ScriptExecutorTest.kt:344-353` and would catch coroutine dump content in `builder.messages` by header at `ScriptExecutorTest.kt:354-357`. It would not catch a regression that logs a plain JVM thread dump into `builder.messages` without the coroutine header. Current code has no such path, so this is only an optional hardening note.

## 7. KDoc Comment-Termination Sweep

Verdict: PASS.

The fixed execution folder example uses `eid_<executionId>/` at `ExecutionDumpCapture.kt:32`, so it does not contain the dangerous `*/` sequence. I swept the added diff for `*/`; the matches were copyright one-liners, normal KDoc closing lines, and one single-line KDoc, not path globs ending in `*/`.

## 8. Other Notes

Verdict: PASS.

`timeoutFailureMessage` handles `resolveExecutionDir` failure best-effort and falls back to the original timeout message at `ScriptExecutor.kt:315-323`. That is reasonable: a path cannot be reported if resolving the execution folder itself fails, and the timeout report is not masked.

Storage API use is correct: filenames are simple names with no slash, and `writeCodeExecutionData` is the existing execution-data API (`ExecutionDumpCapture.kt:54-55`; storage implementation at `execution-storage/src/main/kotlin/com/jonnyzzz/mcpSteroid/storage/ExecutionStorage.kt:132-135`).

The existing `McpServerIntegrationTest` KDoc is stale: it still quotes `resultBuilder.reportFailed("Execution timed out after $timeout seconds")` at `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/McpServerIntegrationTest.kt:927-939`. The test assertions remain compatible because they look for timeout wording and the configured timeout at `McpServerIntegrationTest.kt:1000-1013`. This is not a blocker for #215, but updating the comment would reduce future confusion.

## Final Verdict

VERDICT: APPROVE
