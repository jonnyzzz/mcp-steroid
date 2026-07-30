# Design: production process runner in `mcp-core` (v6 — file-redirect model)

Status: v6 — IMPLEMENTATION CONTRACT. History: v1–v4 specified a
pipe-and-pump model, ratified by a 3-round agent quorum; the product owner
then simplified mid-implementation (2026-07-30): output goes to **log
files** under `~/.mcp-steroid/logs`, read after exit — no pipes, no pumps,
no live callbacks. v5 (the file model) went through one more 3× quorum
round (claude, codex, gemini — all CHANGES-REQUIRED, findings convergent);
v6 folds in every v5 finding (table at the end). The final 3× CODE review
before the PR re-verifies contract-vs-implementation.

## Problem (unchanged)

Production code starts OS processes with raw `ProcessBuilder`, each call
site re-inventing (or forgetting) stream handling, timeouts, and kill logic:

- `ai-agents/.../AiAgentCli.kt` (`ProcessAiAgentCliRunner.run`) — reads
  merged output with `readText()` and calls `waitFor()` with **no timeout**:
  a hung agent CLI hangs devrig forever.
- `npx-kt/.../BinLauncher.kt` (`ensureWindowsPathEntry`) — PowerShell PATH
  registration, `waitFor()` with **no timeout**.
- `npx-kt/.../ManagedBackend.kt` (`spawnDetachedOnWindows`) — WMI spawn
  helper, stderr and the resulting PID routed through **temp files**.

In `devrig mcp` mode stdout is the MCP JSON-RPC channel — the helper must
never write to `System.out` (the test-only `test-helper` ProcessRunner
prints to stdout and is unusable in production).

## Requirements (from the product owner, immutable)

1. Processes always run to completion (we wait for finish).
2. **stdin closed** (null device).
3. stdout/stderr **redirected to files** under `~/.mcp-steroid/logs`; read
   the files after exit when needed.
4. A **command file** records the invocation; the runner references the
   files via slf4j.
5. Naming: `process-<name>-<timestamp>-…-{command,stdout,stderr}.log`.
6. **Merge to one file** when Java supports it — VALIDATED (probe,
   2026-07-30): `Redirect.appendTo` appends, `Redirect.to` truncates,
   `redirectErrorStream(true)` + one `Redirect.to` file = single cleanly
   interleaved file. Merged is the default; split mode (2 files) for
   callers that must distinguish stderr.
7. **Cleanup**: at most 10 000 process files, at most 30 days, touching
   ONLY this runner's own files (other logs live in the same folder),
   non-recursive.
8. **API to delete** a run's files when the caller knows they're not needed.
9. **Timeout** (kill the child tree) + **exit code** result;
   Windows/Linux/macOS; `mcp-core` (3-OS TC matrix).

## API

`kotlin.time.Duration`; finite positive durations validated at construction.

```kotlin
/* package com.jonnyzzz.mcpSteroid.util.process */

class ProcessRunSpec(
    val command: List<String>,          // argv, non-empty
    val timeout: Duration,              // finite, positive; spawn → exit
    val name: String? = null,           // filename tag; sanitized to [A-Za-z0-9._-],
                                        // max 40 chars, empty→"process" fallback;
                                        // default: executable file name
    val workingDir: Path? = null,
    val environment: Map<String, String> = emptyMap(),  // ADDED on top of inherited
    val mergeStderrIntoStdout: Boolean = true,
    val logsDir: Path = defaultProcessLogsDir(),        // override for tests/embedding
)

fun defaultProcessLogsDir(): Path      // ~/.mcp-steroid/logs (== devrig HomePaths.logsDir)

class ProcessRunLogs(
    val commandLog: Path,
    val stdoutLog: Path,               // BOTH streams when merged
    val stderrLog: Path?,              // null when merged
) {
    /** Bounded UTF-8 read of stdoutLog (both streams when merged). Streams
     *  at most maxChars BYTES from the file head (UTF-8 decodes N bytes to
     *  ≤ N chars, so the char bound holds); a longer file gets the marker
     *  " …[truncated by ProcessRunner]" APPENDED ON TOP of the cap (the
     *  marker never ends in a digit — PID-parsing callers rely on that)
     *  plus a WARN. maxChars must be positive. Returns "" (one DEBUG line)
     *  when the file is missing (delete()/cleanup); other I/O errors
     *  propagate as IOException. */
    fun readStdout(maxChars: Int = DEFAULT_PROCESS_READ_LIMIT): String
    /** Same contract; "" when merged (no stderr file) or missing. */
    fun readStderr(maxChars: Int = DEFAULT_PROCESS_READ_LIMIT): String
    /** Requirement 8. Best-effort, idempotent; already-deleted files are
     *  success (never WARN). */
    fun delete()
}

class ProcessRunResult(val exitCode: Int, val duration: Duration, val logs: ProcessRunLogs)

/** Family supertype — best-effort call sites catch ONE clause and get
 *  timeout AND start-failure (interruption intentionally stays outside:
 *  InterruptedException propagates). The child tree (if it existed) has
 *  been killed. [outputTail]: last lines read back from the files, bounded
 *  100 lines / 8 KiB, tagged STDOUT-only in merged mode (attribution is
 *  unrecoverable post-merge); NEVER in the message (secrets). Tail-read
 *  failures WARN and yield an empty tail — they never mask this exception. */
sealed class ProcessRunException(
    message: String,                    // name + pid + limit; no output text
    val processName: String,
    val pid: Long,                      // -1 when the child never started
    val outputTail: List<ProcessLine>,
    val logs: ProcessRunLogs,
) : RuntimeException(message)

class ProcessTimeoutException(..., val timeout: Duration, ...) : ProcessRunException
/** ProcessBuilder.start() failed (missing binary, bad workdir). cause = the
 *  IOException. command.log kept with a START-FAILED record. */
class ProcessStartException(..., cause: java.io.IOException) : ProcessRunException

enum class ProcessStream { STDOUT, STDERR }
data class ProcessLine(val stream: ProcessStream, val text: String)

/** Blocking. @throws ProcessTimeoutException, ProcessStartException,
 *  InterruptedException (child killed, flag restored), IOException only
 *  for pre-start infrastructure failure (logsDir/command.log not
 *  creatable — nothing was written). */
fun runProcess(spec: ProcessRunSpec): ProcessRunResult

/** Public, parameterized for direct testing; runProcess triggers it at
 *  most once per JVM (AtomicBoolean CAS) and throttled by marker file. */
fun cleanupProcessLogs(logsDir: Path, maxFiles: Int = 10_000, trimTo: Int = 8_000,
                       maxAge: Duration = 30.days, nowMillis: Long): Int

fun nullDevice(): File                  // NUL | /dev/null; reused by detached IDE spawn
```

No collect function, no line callback: the files are the capture;
`result.logs.readStdout()` is the read path.

## Mechanics

**File naming & allocation.** Base prefix:
`process-<name>-<yyyyMMdd-HHmmss-SSS>-<pid>-<seq>` where `<pid>` is the
CURRENT JVM's pid (cross-process uniqueness — two devrig JVMs in the same
millisecond must never collide; same convention as devrig's `Log.kt`
per-pid sessions) and `<seq>` a JVM-global AtomicInteger (same-JVM
concurrency). Belt-and-braces: `command.log` is created with `CREATE_NEW`;
on `FileAlreadyExistsException` bump seq and retry (bounded retries).
Suffixes: `-command.log`, `-stdout.log`, `-stderr.log` (split only).
POSIX: files created with owner-only permissions (best-effort — command.log
holds full argv).

**command.log** (structured fields JSON-encoded via kotlinx.serialization —
repo mandate; env keys SORTED, values NEVER written):

```
started:  2026-07-30T14:03:22.117+02:00
command:  ["claude","mcp","add","…"]
workdir:  "/path" | inherited
env-keys: ["KEY1","KEY2"]
merged:   true
```

appended on completion (every path):
`pid: <n>` (omitted if never started) then one of
`exit: <code>` | `exit: TIMEOUT after <t>` | `exit: INTERRUPTED` |
`exit: START-FAILED: <msg>` and `duration: <d>`.
On start failure the already-created stdout/stderr redirect files are KEPT
(empty; cleanup reaps them) and the WARN log references commandLog (the
thrown exception also carries `logs`). One DEBUG line at start and at
completion references commandLog; kills/timeouts/deletions WARN.

**Run.** `ProcessBuilder(command)`; stdin `Redirect.from(nullDevice())`;
merged: `redirectErrorStream(true)` + `redirectOutput(Redirect.to(stdout))`;
split: `Redirect.to` each. Then `process.waitFor(timeout)`:
- in time → append completion, return.
- expiry → v4 kill state machine verbatim (capture descendants while root
  alive → destroy() root → re-capture union → destroyForcibly all → 1 s
  wait; re-capture if root alive, force root → 1 s wait, WARN + proceed if
  unkillable → final sweep from captured live handles, WARN survivors) →
  append TIMEOUT → throw with tail from the files' last 8 KiB. Files KEPT.
- `InterruptedException` from waitFor → kill tree (before restoring the
  flag, so the bounded waits inside the kill work) → append INTERRUPTED →
  restore flag → rethrow.
- `start()` IOException → append START-FAILED → throw ProcessStartException
  (cause attached).
UTF-8 applies at read time (malformed → U+FFFD; legacy-code-page children
degrade — accepted; migrated PowerShell scripts get the
`$OutputEncoding = [Console]::OutputEncoding = UTF8` prologue).

**Cleanup.** Triggered by runProcess BEFORE file allocation, at most once
per JVM (AtomicBoolean CAS) AND skipped if the `.process-cleanup-stamp`
marker file in logsDir is younger than 4 hours (touch/update it after a
scan; the marker never matches the runner grammar). The scan:
non-recursive, `Files.isRegularFile`, names matching the STRICT runner
grammar `^process-.+-\d{8}-\d{6}-\d{3}-\d+-\d+-(command|stdout|stderr)\.log$`
(an unrelated `process-foo.log` NEVER matches). Two passes:
1. AGE: delete files older than 30 days.
2. COUNT: if still over `maxFiles` (10 000), delete oldest-first down to
   `trimTo` (8 000) — hysteresis so the trim runs once per ~2 000 runs, not
   on every start. Ordering key: the FILENAME timestamp+pid+seq (NOT mtime —
   the completion append bumps command.log's mtime and would split a run's
   triple across the boundary); files sharing a run prefix are grouped so
   triples live or die together. Age floor: the count pass never deletes a
   run younger than 1 hour (protects live concurrent runs; the age pass is
   inherently safe).
Already-deleted files (concurrent cleanup from a parallel devrig JVM,
`deleteIfExists == false`, NoSuchFileException) are SUCCESS, not WARN. One
WARN summary line when anything was deleted; per-file WARN only on real
failures. Cleanup failure never fails the run. There is deliberately no
cross-process lock: with the age floor, filename-ordered deletion, and
hysteresis, deleting a live run requires it to stay active while 2 000+
newer runs accumulate — accepted as best-effort (proportionate; these are
short CLI commands).

## Carried over from v1–v4 (unchanged)

Typed exceptions, no `(value, errorFlag)`; required finite timeout; null-
device stdin; the ratified kill state machine; name-not-argv in messages;
WARN/DEBUG discipline; never System.out; distinct names from test-helper
types (`ProcessResultValue` etc.); no `internal`; `require` at
construction; kotlin.time. Dropped with the pipe model: pumps, event
protocol, budgets, drainTimeout, watchdog, onLine, ProcessLineDecoder,
ProcessOutputBudgetException, collect function.

## Migration plan (same PR)

| Call site | Change |
|---|---|
| `ai-agents/AiAgentCli.kt` | `AiAgentCliInvocation` gains `val timeout: Duration = 120.seconds` (the interface change is pinned HERE: `AiAgentCliRunner.run(invocation)` keeps its shape; the budget travels in the invocation; fakes unaffected). `ProcessAiAgentCliRunner` = `runProcess` (merged) + `logs.readStdout()` → `AiAgentCliResult(exitCode, output)`. `ai-agents` gains `project(":mcp-core")` (verified acyclic). Runner KDoc documents the `ProcessRunException` family throw. Logs kept. |
| `npx-kt/InstallCommand.kt` — all four `runner.run` sites | Catch `ProcessRunException` (now = timeout AND start-failure; a missing agent binary takes these graceful boundaries instead of today's Main.kt stack trace + exit 64 — deliberate improvement, flagged in the PR): install list (30 s) → fall back to known names + stderr diagnostic; each removal (30 s) → log, continue; add (120 s) → "Registration FAILED … did not complete: <message>" + pinned exit **1**; `--check` list (30 s) → unreadable → drift exit code. `--check`'s "writes NOTHING" wording is refined: read-only means NO agent-config/launcher mutation; diagnostic process logs under `~/.mcp-steroid/logs` are still written (KDoc + user text updated). Fake-runner tests pin all four sites for both failure types. |
| `npx-kt/BinLauncher.kt` `ensureWindowsPathEntry` | `runProcess`, split, 60 s, UTF-8 prologue; after completion dump non-blank `readStderr()` to `System.err` (was live INHERIT — equivalent for a ≤60 s command); stdout never read (was DISCARD). `logs.delete()` on exit 0; kept + WARN path otherwise. Wrapped by the existing broad `catch (e: Exception)` — still non-fatal. |
| `npx-kt/ManagedBackend.kt` `spawnDetachedOnWindows` | `runProcess`, split, 10 s, UTF-8 prologue; PowerShell prints the PID to stdout (both temp files + cleanup deleted). STRICT parse: exactly one non-blank stdout line, `trim().toLongOrNull()` — anything else is an error that KEEPS the files (chatter must not become a fake PID). `logs.delete()` only on exit 0 AND successful parse. Failures reference the log paths in `error(...)` — better than today. Timeout → existing "WMI spawn helper timed out" wording. Script text factored + platform-neutrally unit-tested. WMI-created-IDE indeterminacy documented as before. |
| `npx-kt/ManagedBackend.kt` `spawnIdeProcess` (POSIX) | Stays raw `ProcessBuilder` (detached-by-design, never awaited, own log files); comment points here; uses public `nullDevice()`. |

Out of scope: `IdeUnpacker.kt` (buildSrc-shared build tooling).

## Testing plan (`mcp-core/src/test`, JUnit 5, all 3 OS unchanged)

Child-JVM fixture (`${java.home}/bin/java` + @argfile classpath;
deterministic bytes; no shell). Modes: exit-with-code + fixed interleaved
lines, exact-UTF-8 bytes (multi-byte + malformed), stdin-EOF report,
sleep-forever, spawn-grandchild-report-pid, env/cwd echo, flood-N-lines.
All tests use a temp `logsDir`; `cleanupProcessLogs` is tested directly
with small limits and an injected `nowMillis`.

1. Exit 0/7 propagate; duration > 0; naming grammar (incl. pid token);
   command.log: argv JSON, SORTED env keys (values absent), pid line,
   exit + duration completion.
2. Merged: one file, child's write order, stderrLog null, readStderr "".
3. Split: streams separated.
4. UTF-8 exact + U+FFFD for malformed; multi-byte char cut at the
   readStdout byte boundary decodes without corruption beyond the cut.
5. stdin closed → immediate EOF report.
6. Timeout → ProcessTimeoutException; child AND grandchild dead
   (deadline-polled); TIMEOUT completion line; files kept; tail non-empty;
   message contains no output text.
7. Interrupt mid-run → InterruptedException, flag restored, child+
   grandchild dead, INTERRUPTED completion line.
8. Missing executable → ProcessStartException (cause IOException);
   command.log has START-FAILED; logs on the exception.
9. workingDir + environment reach the child.
10. readStdout bounded: cap honored, marker appended, marker not ending in
    a digit; full read unaffected; maxChars ≤ 0 rejected.
11. delete(): exactly this run's files; idempotent; read-after-delete "".
12. Cleanup: age pass; count pass with hysteresis (maxFiles/trimTo);
    filename-timestamp ordering keeps a run's triple together; age floor
    spares young files; non-matching neighbors (`devrig.log`,
    `process-foo.log`, `process-x.txt`, nested dirs) untouched; marker file
    respected/refreshed.
13. Same-JVM concurrency: N parallel runProcess → 3N distinct files, no
    cross-talk.
14. Invalid specs at construction; all-emoji name falls back to a usable
    tag; sanitization strips separators.
15. nullDevice() reads as EOF.

Plus plan B in npx-kt: fake-runner tests for the four InstallCommand
boundaries × {timeout, start-failure}; factored WMI script text test
(prologue, PID-to-stdout, strict parse).

## v5 quorum findings → v6 resolution

| Finding | Resolution |
|---|---|
| Cross-JVM filename collision (all three, blocking) | pid token in the name + CREATE_NEW/retry allocation |
| Cleanup O(N log N) every start (gemini#2) | 4 h marker-file throttle + once-per-JVM CAS + 10 000→8 000 hysteresis |
| Start IOException outside the family (gemini#3, claude-NB2, codex#4-part) | ProcessStartException in the family; InstallCommand boundaries now cover missing binaries (deliberate, flagged); interruption stays outside |
| Cap accounting: run's own files push over limit (codex#2) | Hysteresis (trimTo 8 000) absorbs it |
| Over-broad `^process-.*\.log$` (codex#2) | Strict full grammar + isRegularFile |
| Cross-process cleanup races (codex#3, claude-NB3) | Idempotent deletes (NoSuchFile = success), 1 h age floor on count pass, filename ordering + triple grouping; NO locks — documented best-effort with rationale |
| Once-per-JVM guard untestable in-suite (codex#3) | Guard trivial (CAS); cleanup logic tested via the public parameterized function; guard verified by review |
| Timeout budgets not expressible (codex#4, claude-NB1) | `AiAgentCliInvocation.timeout` field; interface shape unchanged |
| `--check` "writes NOTHING" contradiction (codex#5) | Read-only redefined = no config mutation; docs updated |
| Read-contract ambiguities (codex#6, claude-NB4) | KDoc split per method; byte-bounded streaming read; marker on top of cap; positive maxChars; missing → "" + DEBUG; tail-read never masks |
| command.log grammar gaps (claude-NB5) | INTERRUPTED variant; pid omitted on start failure; empty redirect files kept; pre-start infra failure = plain IOException |
| WMI parse too lenient / delete ambiguity (codex-NB1, claude-NB6) | Strict single-line parse; delete only on exit 0 + parse OK; marker ends in a letter |
| Hand-rolled JSON in command.log (gemini, codex-NB2) | kotlinx.serialization for all structured fields; sorted keys |
| World-readable argv (codex-NB3) | Owner-only permissions, best-effort POSIX |
| START-FAILED discoverability (codex-NB3) | WARN references commandLog; exception carries logs |
| `registerOnUserPathWindows` name wrong (claude-NB7) | `ensureWindowsPathEntry` |
| Test gaps (claude-NB8, codex#6) | Tests 4 (boundary cut), 11 (read-after-delete), 12 (marker), 13 (concurrency), 14 (emoji name) added |
