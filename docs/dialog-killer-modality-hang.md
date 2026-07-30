# DialogKiller hang — modal not closed (investigation notes)

Status: **RESOLVED (2026-05-31)** — the `exec_code` pre-flight was reworked so it can no longer
hang under a modal (see **Resolution** below). The investigation notes that follow are kept for
history; the originally-proposed `UiWithModelAccess` candidate was **not** taken (it is
`@ApiStatus.Internal`).

> **Superseded by the `modal` enum redesign (same day).** The killer-first / `allow_modal` pre-flight in
> *Resolution* was the intermediate fix; it was then unified into a single `modal` profile enum
> (`smart_non_modal` | `non_modal` | `unleashed`) + `McpScriptContext` methods. **Authoritative current
> spec: `docs/exec-code-options-redesign.md` → "SPEC — current, implemented".** The root-cause analysis
> here stays valid: write-intent EDT / smart-mode are modality-sensitive; the fix is Yuriy's `isModalEdt()`
> check + bounded EDT ops + a synchronous dialog sweep before any PSI work.

## Resolution (implemented 2026-05-31)

The root cause was confirmed: the hang sites are write-intent `Dispatchers.EDT` dispatches (and
indexing/smart-mode waits) that cannot make progress during a `DialogWrapper.show()` modal loop —
**all of these are modality-sensitive and hang under a modal**:
- the killer's own `EDT + any()` enumeration;
- the pre-flight `commitAndSaveAllDocuments` (write-intent EDT, no `any()`) — the literal hang site
  with `dialog_killer=false`;
- `VfsRefreshService.awaitRefresh()` (write-intent EDT work);
- **`context.waitForSmartMode()` / `waitForIndexesReady`** in the `[RUN]` stage — a recent change
  started calling it from the script-execution context, and **smart-mode / indexing cannot complete
  while a modal is up**, so the run itself stalls (this was the trigger that re-surfaced the hang).

`ModalityState.any()` passes the modality filter but does NOT lift the write-intent lock, so
`EDT + any()` alone is not guaranteed non-blocking; and waiting for smart mode is inherently blocked
by an open modal regardless of dispatcher.

Rather than switch to the (internal) `UiWithModelAccess` dispatcher, the `exec_code` pre-flight in
`ScriptExecutor.executeWithProgress` was restructured so commit/VFS only run when it is safe, and any
residual write-intent stall fails fast instead of hanging:

1. **Dialog killer started FIRST**, spanning the whole execution (immediate first pass, then polls).
   Launched as a child of the request `coroutineScope` and cancelled via the execution `Disposable`
   so it never blocks structured completion. Running it for the *whole* execution (not just the
   pre-flight) is what lets the modality-sensitive operations above — commit/VFS in `[PRE]`/`[POST]`
   AND `waitForSmartMode()` in `[RUN]` — make progress: the killer keeps dismissing modals so smart
   mode/indexing can complete and the body can run.
2. **Non-modal gate** `isModalEdt()` = `withContext(Dispatchers.EDT + ModalityState.any().asContextElement())
   { ModalityState.current() != ModalityState.nonModal() }`. Stable API
   (`LaterInvocator.isInModalContext()` is `@ApiStatus.Internal` — banned). `any()` lets the read run
   under a modal.
3. **Commit + VFS refresh only when non-modal**, and **bounded** by `commitAndSaveAllDocumentsGuardedOnEdt`
   — `withTimeout(60 s) { withContext(Dispatchers.EDT) { commitAllDocuments(); saveAllDocuments();
   vfsRefreshService.awaitRefresh() } }`. **VFS refresh also requires a non-modal EDT** (it would hang
   under a modal too), so it lives inside this guarded EDT block. On timeout ⇒ `log.error` + throw
   `ToolCallErrorException("IntelliJ appears deadlocked …")` (clean tool error, not a 10-min stall).
4. **Branch when modal:** `allow_modal=true` ⇒ skip commit/VFS with a warning in the result;
   otherwise enumerate via `DialogWindowsLookup.withModalityCheck` and **hard-fail only on a real modal
   dialog** (not on mere indexing/progress, which also elevates `ModalityState`).
5. **Post-flight commit** re-checks `isModalEdt()` (current modality) before committing.
6. **Stage markers** (`[PRE] …`, `[RUN] script`, `[POST] …`) are logged on entry to each stage, so if
   anything ever stalls the last marker pinpoints the exact stuck stage. *Updated for issue #154:* the
   markers originally went into the tool result, which broke machine parsing of script output; they now
   go to **idea.log only** — a failing stage propagates its own error untouched, and the last `[PRE]`
   line in idea.log pinpoints a stuck stage.

New `exec_code` parameter **`allow_modal: Boolean = false`** drives step 4 (threaded through
`ExecCodeParams` + the tool schema, and forwarded by the devrig stdio bridge in
`DevrigBridgeToolHandlers`). Validation: `:test-integration:test --tests '*DialogKillerIntegrationTest*'`
(macOS+Docker repro) must fail-fast / proceed without the multi-minute hang.

## Symptom

On macOS + Docker Desktop + Xvfb (the local `:test-integration` `DialogKillerIntegrationTest`),
the dialog killer never closes the test's modal `DialogWrapper`. The killing
`steroid_execute_code` execution hangs ~10+ min until the MCP request times out
(`Process MCP request failed … Terminated by timeout`). Because the test class shares one
lazy IDE `session`, the first stuck modal cascades into all 5 tests failing (≈55 min). The
same "elevated modality never resolved" also froze the live IDE's MCP when a probe elevated
modality without closing it. **Passes on CI Linux** historically — this is environment-specific.

## Why it matters

The dialog killer's whole purpose is to **close every modal and restore
`ModalityState.nonModal()`** so VFS refresh, the next execution, and the MCP server can run.
While a modal is up and unresolved, everything downstream is blocked.

## Pinpointed hang location (key result)

From the IDE log of the killing execution (`eid_…-explicit-dialog-killer`):

```
ExecutionManager Starting execution
CodeEvalManager Compiling script
Script evaluation complete
ScriptExecutor Starting execution
ScriptExecutor Running 1 script block(s)        <-- last execution line
[then only unrelated background IDE logs; execution never continues]
```

So it compiles, enters `ScriptExecutor`, reaches the **pre-flight `killProjectDialogs`** (right
after "Running 1 script block(s)"), and hangs there. With the timeout-based detection restored
(see below), `DialogWindowsLookup.withDialogWindows` runs:
1. `canPumpEdtNonModal()` — a 100 ms-timeout probe; correctly returns "modal present" (never hangs).
2. **`withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { findDialogsOwnedBy(projectFrame) }`** — **HANGS** (no timeout).

The DialogKiller's own capture/close logs never appear because it never gets past that
enumeration.

## Root cause (current best understanding — codex-reviewed)

A `run-agent.sh codex` review of the chain of thought **refuted** the first theory ("`any()`
modality isn't pumped"). The corrected, leading hypothesis:

**`Dispatchers.EDT` is the *legacy write-intent* coroutine dispatcher** — it dispatches via
`dispatchCoroutineOnEDT(…, needsWriteIntent = true)` (`EdtCoroutineDispatcher.kt`). During a
`DialogWrapper.show()` modal loop, write-intent runnables can be **withheld by
`NonBlockingFlushQueue`** independently of modality (`NonBlockingFlushQueue.kt:229,316`).
`ModalityState.any()` itself *is* accepted under any modality (`ModalityStateEx.java:102`) — so
the modality filter was never the problem. The killer's enumeration / capture / close all use
`Dispatchers.EDT + any()`, request the write-intent lock, and queue behind the modal forever.

Corroborating coroutine dump (`/tmp/ti-dialog-dump.txt`): an execution coroutine parked with a
sibling `DispatchedCoroutine{Active} state: CREATED [Dispatchers.EDT]` — an EDT-dispatched
coroutine queued and never started while the EDT sat in `WaitDispatchSupport.enter` →
`DialogWrapper.doShow`.

**Candidate fix (NOT taken — superseded by Resolution above; `UiWithModelAccess` is `@ApiStatus.Internal`):**
run the killer's modal-time UI work on the **non-write-intent** UI dispatcher,
`Dispatchers.UiWithModelAccess + ModalityState.any().asContextElement()` (verified present in
this platform at `core-api/.../coroutines.kt`), at `DialogWindowsLookup` (enumeration + modality
check), `DialogKiller.closeDialog`, and the `VisionService`/`ScreenshotImageProvider` capture path;
move `delay(10)` out of the UI block; add short timeouts so a future regression surfaces as a tool
error instead of an MCP-request timeout.

**Why this isn't the whole answer (all-weather requirement).** A `DialogWrapper` can be shown by
*any* code path — including IntelliJ's own **old write-intent APIs**. The killer must close
**every** modal regardless of how it was opened, so switching the killer's *own* dispatch to
non-write-intent is necessary but may not be sufficient. This must be **confirmed by live
debugging** (below) before we commit a fix; the `UiWithModelAccess` change was applied and then
**reverted** pending that confirmation.

## What was ruled OUT (so we don't re-chase it)

- **OCR is not the cause.** Sub-agent verified `ExecUtil.execAndGetOutput(cmd, timeout)` is
  fully EDT-independent (pooled reader threads + a JDK semaphore honoring the timeout; zero
  `invokeAndWait`/EDT touchpoints — `LaterInvocator`/`CapturingProcessRunner`/`ProcessHandler`).
  It returns within its timeout regardless of a blocked EDT. (We still deferred it off the
  capture path as good hygiene — see below — but it was never the hang.)
- **The `current() != nonModal()` detection (`c2c1ddd1`, "Kudos Yuriy Artamonov") is not the
  cause.** Reverting it to the timeout-based `canPumpEdtNonModal` probe did NOT fix the hang —
  the *enumeration* (also EDT+`any()`, no timeout) hangs the same way. Sub-agent confirmed
  `ModalityState.current()` reads the global `LaterInvocator` stack and is not corrupted by the
  `any()` context element; `LaterInvocator.isInModalContext()` is the platform-blessed check.
- **A non-`any()` EDT call in the capture path is not the cause.** Audited: `captureOnEdt`,
  `resolveComponent`, `ScreenshotImageProvider.captureComponent` all use
  `Dispatchers.EDT + ModalityState.any()`. The only non-`any` `Dispatchers.EDT` calls are in the
  *input* path (`SwingInputExecutor`), irrelevant here.
- **Raw `invokeLater(any())` vs coroutine `Dispatchers.EDT + any()`** — per maintainer, these are
  equivalent; not a fix to pursue. (Switching to the older API will not help.)
- **The "`any()` modality is not pumped" theory itself — REFUTED by codex.** `any()` is accepted
  under any modality; the real suspect is the *write-intent* nature of legacy `Dispatchers.EDT`
  (see Root cause above), not the modality filter.

## Open question (drives the live-debug)

With the write-intent hypothesis, the live-debug question becomes: during the
`DialogWrapper.show()` modal loop, is the killer's `Dispatchers.EDT + any()` task withheld by
`NonBlockingFlushQueue` because it requests the write-intent lock — and does
`Dispatchers.UiWithModelAccess + any()` (non-write-intent) actually run there? Breakpoint targets:
- `DialogWindowsLookup.withDialogWindows` (the EDT+`any` enumeration) — does it ever enter?
- `EdtCoroutineDispatcher.dispatch` (`needsWriteIntent=true`) vs the `UiWithModelAccess` dispatcher.
- `NonBlockingFlushQueue` (`:229`, `:316`) — is the write-intent runnable enqueued-but-withheld?
- Whether the test's modal (opened from inside the MCP execution coroutine context) carries a
  context that changes dispatch — hence the test now opens it from **vanilla EDT**.

## Test-harness issues found

- **Shared lazy `session`** in `DialogKillerIntegrationTest` cascades one stuck modal into all 5
  failures (and ≈55-min runs). Isolate per-test (fresh session) or add a teardown that
  force-closes windows for fast, attributable feedback.
- The test opens its modal from inside a `steroid_execute_code` script (the MCP execution
  coroutine context). It should open from a **vanilla EDT** scope
  (`CoroutineScope(Dispatchers.EDT).launch { … dialog.show() }`) so the modal does not inherit
  the execution's coroutine scope/modality — both more realistic and a cleaner repro.

## Artifacts already in place (origin/main)

Remote-debug infra is now **complete** — both JVMs always run a JDWP agent, so you can attach and
step through the modal loop live. Both **must stay `suspend=n`** (a `suspend=y` would block the
whole test on CI where nobody attaches — see `jdwp-suspend-n-test-modules` memory):

- **IDE debug port** (`IDE_DEBUG_PORT` = `ContainerPort(5005)`): IDE JVM always starts
  `-agentlib:jdwp=…,server=y,suspend=n,address=*:5005`, published + Docker-mapped.
- **devrig debug port** (`DEVRIG_DEBUG_PORT` = `ContainerPort(5006)`, commit `b970e037`): the
  in-container devrig (`npx-kt`) JVM gets its own agent via `DEVRIG_OPTS` with `quiet=y` (so it
  doesn't corrupt `devrig mcp`'s stdout JSON-RPC). Different port ⇒ debug IDE + devrig at once.
- **Host-side print** matches the JVM's own wording with the **host-mapped** port:
  `Listening for transport dt_socket at address: <host-port>`, plus `IDE_DEBUG_PORT` /
  `DEVRIG_DEBUG_PORT` in `session-info.txt`. (The in-container port is invisible from the host.)
- **Attach recipe**: `mcp-steroid://debugger/debug-attach-remote-jvm`
  (`prompts/.../debugger/debug-attach-remote-jvm.md`) — `RemoteConfiguration` with
  `SERVER_MODE=false` at `localhost:<host-port>`, or low-level
  `DebuggerManagerEx.attachVirtualMachine`. Docs: `test-integration/AGENTS.md` →
  "Remote-debugging the Dockerized IDE".
- **VisionService refactor**: `object` → `@Service(Service.Level.PROJECT)`; `capture` returns after
  the screenshot image only; OCR (and any `deferred` provider) is queued on the service scope to
  run AFTER capture. Good hygiene, but does NOT fix this hang.
- **`UiWithModelAccess` candidate**: applied to `DialogWindowsLookup` then **reverted** — not
  committed pending the live-debug confirmation (all-weather requirement, above).

## Next steps (superseded — see Resolution at top; kept for history)

1. Start a playground / Docker test; grab the host-mapped `IDE_DEBUG_PORT` and attach IntelliJ's
   "Remote JVM Debug" (module `ij-plugin`) per the attach recipe. (Open the test modal from vanilla
   EDT — already done in `DialogKillerIntegrationTest`.)
2. Breakpoint `DialogWindowsLookup.withDialogWindows` + `EdtCoroutineDispatcher.dispatch` +
   `NonBlockingFlushQueue`: confirm the write-intent task is withheld during the modal and that
   `Dispatchers.UiWithModelAccess + any()` is dispatched there.
3. If confirmed, re-apply the `UiWithModelAccess` change across the killer's modal-time UI work
   (enumeration, modality check, close, capture), move `delay(10)` out of the UI block, add short
   timeouts. Verify the killer closes the modal **and restores `ModalityState.nonModal()`**, and
   that it works against modals opened by arbitrary (incl. write-intent) code paths.
