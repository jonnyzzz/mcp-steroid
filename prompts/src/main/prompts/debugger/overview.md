Debugger Examples Overview

Overview of IntelliJ debugger operation examples.

# Debugger Examples

These resources show how to drive IntelliJ's debugger APIs from `steroid_execute_code`.
Use them as building blocks to set breakpoints, start a debug session, evaluate variables,
step through code, and inspect threads/stack frames.

## Getting Started

If you need to debug a program, follow this approach:

1. **Read this overview** to understand the overall workflow
2. **Read each resource below** before executing its step -- each contains a complete, copy-paste-ready script
3. **Execute each step as a separate `steroid_execute_code` call** -- do NOT combine steps into one large script
4. **Adapt the placeholder values** (file paths, line numbers, class names) in each script to your specific task

The resources are self-contained: each one includes all necessary imports, API calls, and
error handling. You do not need to know IntelliJ APIs in advance -- just read the resource,
adapt the parameters, and pass the code to `steroid_execute_code`.

## Recommended Workflow

Follow this sequence of resources for a complete debug session:

1. `mcp-steroid://debugger/add-breakpoint` - set breakpoint on target line
2. `mcp-steroid://debugger/create-application-config` - create run config (if needed)
3. `mcp-steroid://debugger/debug-run-configuration` - start debug session
   - For JVM/Kotlin tests, prefer the `JUnitConfiguration` recipe in
     `mcp-steroid://debugger/demo-debug-test` (deterministic — sets
     `MAIN_CLASS_NAME`, `TEST_OBJECT = TEST_CLASS`, and the test module).
     A context-menu `DebugClass` action can silently no-op when the caret
     lands on a keyword instead of an identifier.
4. `mcp-steroid://debugger/wait-for-suspend` - wait for breakpoint hit
   (distinguishes "no session started" from "session never paused" so you
   know whether to retry the launch or diagnose the breakpoint).
5. `mcp-steroid://debugger/evaluate-expression` - evaluate variables at breakpoint
6. `mcp-steroid://debugger/step-over` - step to next line
7. `mcp-steroid://debugger/evaluate-expression` - evaluate again to see changes
8. `mcp-steroid://debugger/cleanup` - remove temporary breakpoints and
   delete temporary debug run configurations once the bug is confirmed.

Each step should be a separate `steroid_execute_code` call. Do NOT combine steps into one large script.

## Available resources

### Setup
- `mcp-steroid://debugger/add-breakpoint` - add a line breakpoint on a statement inside a method body (idempotent, default choice)
- `mcp-steroid://debugger/add-inline-breakpoint` - pick a variant (lambda body, conditional return) when several statements share a line
- `mcp-steroid://debugger/remove-breakpoint` - remove breakpoints from a line
- `mcp-steroid://debugger/set-line-breakpoint` - toggle breakpoint on EDT (add/remove reference)
- `mcp-steroid://debugger/create-application-config` - create Application run configuration
- `mcp-steroid://debugger/debug-run-configuration` - start a run configuration in Debug

### Inspection (the essential ones for finding bugs)
- `mcp-steroid://debugger/wait-for-suspend` - wait for debugger to suspend (event-driven, single event)
- `mcp-steroid://debugger/monitor-debug-events` - register listeners that append events to a file; agent polls the file across calls
- `mcp-steroid://debugger/eval-helper` - reusable eval() function (copy into your scripts)
- `mcp-steroid://debugger/evaluate-expression` - full evaluation example with helper
- `mcp-steroid://debugger/step-over` - step over current line and observe changes

### Session management
- `mcp-steroid://debugger/debug-session-control` - pause/resume/stop the current session (all require EDT)
- `mcp-steroid://debugger/debug-list-threads` - list execution stacks (threads)
- `mcp-steroid://debugger/debug-thread-dump` - build a basic thread dump from stacks
- `mcp-steroid://debugger/cleanup` - remove temporary breakpoints + delete temporary run configurations after verification

### Demos
- `mcp-steroid://debugger/demo-debug-test` - end-to-end debug + test results demo

## Tips

- If `steroid_execute_code` returns `Project not found`, call `steroid_list_projects` and reuse the exact `project_name` (the unique routing key, NOT the raw folder `name`).
- Do not hardcode line numbers; locate the target statement by text (for example, the `sortedByDescending` call) before placing breakpoints.
- **Pick the right stopping primitive.** A line breakpoint on a specific statement *inside the method body* is the cheapest and most reliable. Avoid method (entry/exit) breakpoints — IntelliJ implements those via JDI `MethodEntryRequest`/`MethodExitRequest`, which fire on every method entry/exit in the JVM and are post-filtered; the IDE warns *"Method breakpoints may dramatically slow down debugging"*. For multi-statement lines like `collection.map { it.someMethod().plus(42) }` use `mcp-steroid://debugger/add-inline-breakpoint` to pick the lambda-body variant instead of the default whole-line position.
- **Breakpoints**: Use the idempotent `findBreakpointsAtLine` + `addLineBreakpoint` pattern from `mcp-steroid://debugger/set-line-breakpoint`. Do NOT use `toggleLineBreakpoint` for "ensure breakpoint exists" — it REMOVES an existing breakpoint (toggle semantics).
- **Breakpoint type cast**: Cast to `XLineBreakpointType<XBreakpointProperties<*>>`, NOT to `Nothing?` or `Void`. Rider uses `DotNetLineBreakpointProperties` — casting to `Nothing?`/`Void` causes ClassCastException in Rider.
- **Watching events across calls**: use `mcp-steroid://debugger/monitor-debug-events`. Register a self-disposing listener that appends NDJSON to `.idea/mcp-steroid/debug-events.ndjson`; the agent polls the file from outside `steroid_execute_code`. Keep listener lifetimes bounded (time + sessionStopped + project-disposable) so the per-execution classloader can be released.
- Use `mcp-steroid://debugger/debug-run-configuration` for debug launch (uses `com.intellij.execution.ProgramRunnerUtil`).
- **For variable evaluation, always copy the `eval()` helper from `mcp-steroid://debugger/evaluate-expression`**. Do NOT write your own evaluation code -- the callback API is tricky and easy to get wrong.
- **Do NOT await `value.isReady` before calling `computePresentation`** in Rider. In Rider/DotNetValue, `isReady` only completes INSIDE `computePresentation`'s async coroutine. Awaiting `isReady` first deadlocks for 30 seconds and crashes the MCP server. The `eval()` helper already handles this correctly.
- **Callback overrides**: Always use block bodies `{ }`, NOT expression bodies `=`. Example: `override fun evaluated(value: XValue) { deferred.complete(value) }`. Expression body `= deferred.complete(value)` causes a type mismatch (`Boolean` vs `Unit`).
- **Waiting for suspension**: Use `XDebugSessionListener` + `CompletableDeferred` (event-driven, no polling). See `mcp-steroid://debugger/wait-for-suspend`.
- **Scope after stepping**: After step-over, the debugger may land in a different method scope. Local variables from the caller are NOT accessible. Use `this.fieldName` to access instance state. Always get fresh `evaluator` from `session.currentStackFrame`.
- UI calls like `FileEditorManager.openFile(...)` must run on EDT (`withContext(Dispatchers.EDT)`).
- Stop debug sessions when done: use debug-session-control or
  withContext(Dispatchers.EDT) { XDebuggerManager.getInstance(project).debugSessions.forEach { it.stop() } }
- API calls use 0-indexed lines; editor line 7 means API line 6.
- `debug-list-threads` and `debug-thread-dump` require a suspended session.
- Use `DefaultDebugExecutor` and `com.intellij.execution.ProgramRunnerUtil` to start debug configs.
- In `steroid_execute_code`, do not use `return@executeSteroidCode` or `return@executeSuspend`; use plain `return`.

## API Contracts (2025.3+)

- Run/debug launch: use `ProgramRunnerUtil.executeConfiguration(settings, executor)` with a real `Executor` (`DefaultDebugExecutor.getDebugExecutorInstance()` or `ExecutorRegistry.getInstance().getExecutorById(...)`).
- For `ApplicationConfiguration`, set entry point via `mainClassName = "..."` (avoid `setMainClassName(...)`).
- Run config creation: prefer `RunManager.createConfiguration(name, factory)` then `RunManager.addConfiguration(settings)`; choose storage via `settings.storeInDotIdeaFolder()` or `settings.storeInLocalWorkspace()` before add.
- Breakpoints: use the idempotent `findBreakpointsAtLine` + `addLineBreakpoint` pattern. Cast type to `XLineBreakpointType<XBreakpointProperties<*>>` (not `Nothing?`). See `mcp-steroid://debugger/set-line-breakpoint` for the complete script.
- Session control: use `XDebuggerManager.getInstance(project).currentSession`, then `pause()`, `resume()`, `stepOver(...)`, `stop()`.
- Expression evaluation is callback-based. **Copy the complete `eval()` helper from `mcp-steroid://debugger/evaluate-expression`.**
  Key types: `XDebuggerEvaluator.XEvaluationCallback` (nested, not top-level), callback receives `XValue` (from `com.intellij.xdebugger.frame`), presentation via `XValuePresentationUtil.computeValueText()`.
- Step over: `session.stepOver(false)` on EDT, then wait for re-suspension via `XDebugSessionListener.sessionPaused()`.
- PSI/VFS lookups (for example `FilenameIndex`, `PsiManager`, documents) must run in `readAction { ... }`.

## Failure-Recovery Pattern

When a debugger script fails with unresolved imports/APIs or runtime setup errors:

1. Stop and split work into short stateful calls (breakpoint setup -> debug launch -> inspect).
2. Reuse existing debugger resources instead of inventing large custom scripts.
3. If debug setup still fails, do one final source-level diagnosis call, print the exact buggy line text from the `Document`, and report root cause clearly.
4. Always include execute_code evidence (`Execution ID` / `execution_id`) in the final answer.

---

## Related Resources

### Skill Guides
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API reference and patterns
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Essential debugger knowledge and workflows
- [Test Runner Skill Guide](mcp-steroid://prompt/test-skill) - Test execution

### Debugger Examples
- [Debugger Overview](mcp-steroid://debugger/overview) - This document
- [Add Breakpoint](mcp-steroid://debugger/add-breakpoint) - Add line breakpoint on a statement inside the method body (idempotent)
- [Add Inline Breakpoint](mcp-steroid://debugger/add-inline-breakpoint) - Pick a lambda / conditional-return variant for multi-statement lines
- [Remove Breakpoint](mcp-steroid://debugger/remove-breakpoint) - Remove breakpoints from a line
- [Set Line Breakpoint](mcp-steroid://debugger/set-line-breakpoint) - Combined add/remove reference
- [Create Application Config](mcp-steroid://debugger/create-application-config) - Create run configuration
- [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start debugging
- [Wait for Suspend](mcp-steroid://debugger/wait-for-suspend) - Wait for breakpoint hit (single event, in-script)
- [Monitor Debug Events](mcp-steroid://debugger/monitor-debug-events) - Append events to NDJSON, poll from outside
- [Eval Helper](mcp-steroid://debugger/eval-helper) - Reusable eval() function
- [Evaluate Expression](mcp-steroid://debugger/evaluate-expression) - Full evaluation example
- [Step Over](mcp-steroid://debugger/step-over) - Step through code
- [Debug Session Control](mcp-steroid://debugger/debug-session-control) - Pause, resume, stop
- [Demo Debug Test](mcp-steroid://debugger/demo-debug-test) - End-to-end debug + test results demo
- [List Threads](mcp-steroid://debugger/debug-list-threads) - Inspect execution stacks
- [Thread Dump](mcp-steroid://debugger/debug-thread-dump) - Generate thread dumps
- [Cleanup](mcp-steroid://debugger/cleanup) - Remove temporary breakpoints + delete temporary run configurations after verification

### Related Example Guides
- [Test Examples](mcp-steroid://test/overview) - Test execution and result inspection
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations
- [LSP Examples](mcp-steroid://lsp/overview) - Code navigation and intelligence
- [Open Project Examples](mcp-steroid://open-project/overview) - Project opening workflows
