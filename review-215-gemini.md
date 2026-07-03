# Adversarial Review: Branch `fix/215-dumps-on-timeout` (Issue #215)
**Reviewer:** Gemini CLI (Adversarial Reviewer)  
**Date:** Friday, July 3, 2026  
**Status:** Stacked on `fix/213-progress-indicator`  

---

## Executive Summary & Final Verdict

This branch implements issue #215 ("cancellation and dumps on timeout"), establishing a robust thread and coroutine dump capture process whenever the execution times out or hits modality-related blocks. 

The implementation is **exceptionally clean, safe, and robust**. It solves the problem of suspected deadlocks by extracting full diagnostics (thread + coroutine dumps) and storing them in the execution storage folder. It communicates the storage path back to the client while strictly preventing the heavy dump text from leaking into tool results or log files unnecessarily.

### **Final Verdict**
**VERDICT: APPROVE**

No blocking required changes exist. There are, however, minor suggestions to enhance isolation and update stale KDoc comments in tests.

---

## Detailed Dimension-by-Dimension Review

### Dimension 1: Timeout-branch Order and Contract
* **File & Line:** `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/ScriptExecutor.kt:272-284`
* **Assessment:** **SOUND**
* **Verification Details:**
  1. **Order of Operations:** In the `catch (e: TimeoutCancellationException)` block, the progress indicator is cancelled first (`context.progressIndicator.cancel()`), then the dumps are captured via `captureDiagnosticDumps(...)`, and finally the exception is logged and `reportFailed` is called. Cancelling the progress indicator first is a critical safety guarantee because it ensures any platform checks (`checkCanceled()`) instantly unwind, releasing lock contentions before dump writing starts.
  2. **Path-only reporting:** The message reported to the caller is computed via `timeoutFailureMessage(exec.timeout, executionId)`. It appends the absolute execution-folder path but **never** inlines the dump contents. This preserves context size and avoids clogging the agent response.
  3. **Catch chain preservation:** The catch chain from the base branch remains perfectly intact (`TimeoutCancellationException` -> `CancellationException` -> `ProcessCanceledException` -> `Throwable`). Dumps are captured **only** in the `TimeoutCancellationException` block, preventing diagnostic noise during normal user cancellations.

---

### Dimension 2: `NonCancellable` Correctness
* **File & Line:** `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/ExecutionDumpCapture.kt:47-66`
* **Assessment:** **SOUND**
* **Verification Details:**
  1. **Non-suspending capture:** The dumps are extracted synchronously (`ThreadDumper.dumpThreadsToString()` and `dumpCoroutines(stripDump = false)`) *before* entering any coroutine suspension point. This is extremely robust: if the current coroutine job has been cancelled, these non-suspending platform calls are executed successfully without throwing `CancellationException` mid-capture.
  2. **`NonCancellable` file write:** The file writing itself is performed under `withContext(NonCancellable + Dispatchers.IO)`. This guarantees that even if the outer coroutine is already fully cancelled or in the middle of unwinding, the IO write calls to `executionStorage.writeCodeExecutionData` cannot be aborted.
  3. **Safe exception wrapping:** Any exception during capturing (except `CancellationException`, which is correctly rethrown per logger contract) is caught, logged at `WARN`, and swallowed. Thus, a failed diagnostic dump can never mask the actual timeout error being propagated to the user.

---

### Dimension 3: API Stability and Compatibility
* **File & Line:** `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/ExecutionDumpCapture.kt:4-13`
* **Assessment:** **SOUND**
* **Verification Details:**
  1. **Stable public APIs:** The implementation utilizes `com.intellij.diagnostic.ThreadDumper.dumpThreadsToString` (public utility in `intellij.platform.util.base`) and `com.intellij.diagnostic.dumpCoroutines` (unannotated public function in `intellij.platform.util.base`).
  2. **No `@ApiStatus.Internal` usage:** The internal `ThreadDumper.getThreadDumpInfo` (marked `@ApiStatus.Internal` and noted in `@docs/262-plugin-manager-api-internalization.md`) is successfully avoided.
  3. **Probes Lifecycle:** `DebugProbes.install` is not called, respecting the platform's ownership of the debug probes lifecycle. If the probes are not installed, the code handles it gracefully by outputting `COROUTINE_DUMP_UNAVAILABLE_NOTE`.

---

### Dimension 4: Shared-Helper Adoption
* **File & Lines:** 
  * `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/McpScriptContextImpl.kt:350-352`
  * `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/ScriptExecutor.kt:193`
* **Assessment:** **SOUND**
* **Verification Details:**
  1. **Delegation completeness:** All 5 modal-path sites (`closeModalDialogs`, `modal-monitor`, `syncDocuments-timeout`, `syncDocuments-modal-side-effect`, and `$operation-requires-non-modal`) and the startup `requireNonModalOrFail` modality gate now successfully delegate to `captureDiagnosticDumps`.
  2. **File naming & content:** The historical filenames (`thread-dump-<reason>.txt`) are preserved, and coroutine dumps are added alongside them (`coroutine-dump-<reason>.txt`), ensuring full compatibility with existing automation and tooling.
  3. **Idea.log noise reduction:** The verbose inlining of thread dumps to the `idea.log` at `INFO` level is removed, replaced with a concise path reference. A workspace-wide sweep confirms no tests or docs rely on parsing the inline thread dump string from `idea.log`.

---

### Dimension 5: Failure Isolation Judgment Call
* **File & Line:** `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/ExecutionDumpCapture.kt:53-56`
* **Assessment:** **ACCEPTABLE WITH SUGGESTED IMPROVEMENT**
* **Verification Details:**
  * **The Issue:** The file writes are grouped in a single `withContext(NonCancellable + Dispatchers.IO)` block:
    ```kotlin
    withContext(NonCancellable + Dispatchers.IO) {
        storage.writeCodeExecutionData(executionId, "thread-dump-$reason.txt", threadDump)
        storage.writeCodeExecutionData(executionId, "coroutine-dump-$reason.txt", coroutineDump)
    }
    ```
    If writing the `thread-dump` file throws an exception (e.g., due to file-specific directory-squatting or lock contentions), the `coroutine-dump` write will be skipped.
  * **Judgment:** In real-world production, write failures are almost always folder-level (disk full, permission issues) which would fail both anyway. However, for maximum diagnostic resilience, isolating these two operations in independent try-catch wrappers inside `withContext` is a best-practice improvement.
  * **Suggested Improvement (Non-blocking):**
    ```kotlin
    withContext(NonCancellable + Dispatchers.IO) {
        try {
            storage.writeCodeExecutionData(executionId, "thread-dump-$reason.txt", threadDump)
        } catch (e: Exception) {
            log.warn("[$executionId] failed to write thread dump ($reason): ${e.message}")
        }
        try {
            storage.writeCodeExecutionData(executionId, "coroutine-dump-$reason.txt", coroutineDump)
        } catch (e: Exception) {
            log.warn("[$executionId] failed to write coroutine dump ($reason): ${e.message}")
        }
    }
    ```

---

### Dimension 6: Test Design and Robustness
* **File & Lines:**
  * `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/execution/ExecutionDumpCaptureTest.kt`
  * `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/execution/ScriptExecutorTest.kt:283-388`
* **Assessment:** **SOUND**
* **Verification Details:**
  1. **Deterministic timeout test:** `testTimeoutWritesDumpsAndReportsFolderPathOnly` avoids flakiness by starting a dedicated daemon thread running a non-interruptible `LockSupport.parkNanos` loop. This guarantees the thread is alive when the 1s script timeout fires, allowing the captured dump to deterministically contain the thread's stack frames.
  2. **Engine tolerance:** The assertions in the timeout test are protected by `if (failure.contains("timed out"))`. This conforms to the project's engine-tolerance convention, gracefully skipping the assertions on environments lacking a functional script engine.
  3. **Path-only validation:** The test asserts that the failure message contains the directory path (`dir.toString()`) and verifies with `assertFalse` that the dump's stack trace contents (`"at java.lang."`) or coroutine dump headers are never inlined into the failure string. This prevents future regressions.

---

### Dimension 7: Comment-Termination Verification (Glob Ending in `*/`)
* **Assessment:** **PASS**
* **Verification Details:**
  * The codebase was swept for the KDoc comment-termination bug (where `*/` in a comment prematurely terminates the comment block).
  * The fix in `ExecutionDumpCapture.kt:32` correctly changed `eid_*/` to `eid_<executionId>/` to prevent comment corruption. No other instances of comments ending with a glob like `*/` exist in the diff.

---

### Dimension 8: Architectural Cleanliness and Missed Items
* **File & Line:** `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/McpServerIntegrationTest.kt:929-939`
* **Assessment:** **STALE KDOC FINDING**
* **Verification Details:**
  * The KDoc for the integration test `testExecuteCodeTimeoutReturnsCleanErrorNotHttp500` is now slightly stale. It states:
    `and resultBuilder.reportFailed("Execution timed out after $timeout seconds") is the user-visible signal.`
    But under the new #215 implementation, the user-visible signal is computed via `timeoutFailureMessage` and appends the absolute execution-folder path.
  * **Suggested Change (Non-blocking):** Update the KDoc of `testExecuteCodeTimeoutReturnsCleanErrorNotHttp500` to reflect that the failure message now contains the storage path to the diagnostics.

---

## REQUIRED CHANGES

* **None.** The implementation fully adheres to the issue specifications, passes all code constraints, maintains zero internal-API usage on stable targets, and includes excellent test coverage.

---

## SUGGESTED CHANGES (Non-blocking)

1. **Diagnostic Write Isolation:**
   * **Location:** `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/ExecutionDumpCapture.kt:53-56`
   * **Change:** Wrap both `writeCodeExecutionData` calls in separate `try-catch` blocks inside the `withContext(NonCancellable)` scope to ensure that a failure writing one file (e.g., due to file squatting) does not skip writing the other.
2. **KDoc Staleness Update:**
   * **Location:** `ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/McpServerIntegrationTest.kt:929-939`
   * **Change:** Update the KDoc of `testExecuteCodeTimeoutReturnsCleanErrorNotHttp500` to state that the reported failure message now includes the diagnostic storage path.
