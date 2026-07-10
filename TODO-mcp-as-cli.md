# MCP-as-CLI spike notes (epic #188)

Branch: `spike/mcp-as-cli` (NOT committed — local spike per request).
Goal: expose the existing `steroid_*` MCP tools as `devrig` shell subcommands, as a thin,
stateless frontend over the **existing** bridge — no new MCP tools, no duplicated plugin logic.

## What was built

A reusable CLI-tool layer + all 8 epic-#188 subcommands, wired to the same handlers the
`devrig mcp` stdio proxy uses (`StubMcpSteroidTools` → `Devrig*ToolHandler` → `DevrigToolBridgeClient`).

New/changed files:
- `mcp-steroid-server/.../server/FetchResourceToolHandler.kt` — extracted `resolveResourcePayload(uri, ctx)`
  and `canonicalResourceEntryPoints()`; the MCP tool and the CLI now share URI→payload resolution.
- `npx-kt/.../devrig/CliToolSupport.kt` — shared layer: `ToolCallResult.renderTo(command, json, out)`,
  `toEnvelopeJson()`, `CliExit` codes, `stderrProgressReporter()`.
- `npx-kt/.../devrig/FetchResourceCommand.kt` — prompt/fetch_resource (Generic context, no IDE needed).
- `npx-kt/.../devrig/ToolBackedCommands.kt` — execute_code, execute_feedback, input, take_screenshot,
  open_project (+ `--wait` poll loop), via `runToolCall { }` helper.
- `npx-kt/.../devrig/ListWindowsCommand.kt` — list_windows (typed response; own renderer).
- `npx-kt/.../devrig/Cli.kt` — 9 new clikt subcommands + `DevrigCommand` variants + `runCli` dispatch.
- `npx-kt/.../devrig/Main.kt`, `DevrigBeacon.kt`, `HelpCommand.kt` — exhaustive-`when` + help + telemetry.
- Tests: `FetchResourceCommandTest`, `McpAsCliParseTest`, `CliToolSupportTest` (npx-kt).

## What worked well

- **The bridge reuse is genuinely thin.** Every tool-backed command is ~10-20 lines: build the
  `*Params`, get the handler from `StubMcpSteroidTools`, call it, `renderTo`. No tool logic in the CLI.
  This is the strongest signal that the direction is right — the CLI is a projection, not a fork.
- **`resolveResourcePayload` extraction** removed the only real duplication risk (URI resolution) and
  let `devrig prompt` work **without a running IDE** — verified from the shell against the built binary.
- **The shared `renderTo`/envelope + `CliExit`** fit all 5 `ToolCallResult`-returning commands cleanly
  (execute_code, feedback, input, screenshot, open_project). One place decides stdout/stderr routing,
  exit codes, and `--json`.
- **Agent-usable errors**: missing args print a runnable example with a real URI (pulled from the
  generated article classes) and a `→ devrig list_projects` next step. Validated from the shell.
- **stdout stays clean** for data commands (headliner suppressed via `isMcpAsCliToolCommand()`), so
  `devrig prompt ... | ...` and `--json | jq` work.

## What felt wrong / rough edges

1. **Three different `--json` shapes.** `renderTo` uses `{tool, command, isError, content}`;
   `list_projects` (shared with `devrig project`) uses `{tool, projects}`; `list_windows` uses raw
   `{windows, backgroundTasks}` with no `tool` header. The typed-response listers don't fit the
   `ToolCallResult` envelope. **Decision needed for the final PR:** either wrap all `--json` output in a
   common `{tool, command, ...}` header, or accept two families (tool-result vs typed-response) and
   document it. Leaning toward a common header.
2. **`<subcommand> --help` shows the global banner, not per-command help.** This is the *existing*
   devrig behavior (`--help` → `DevrigCommandHelp` → `printHelp`), inherited by the new commands. The
   global banner now lists each command with a runnable example, so it's serviceable, but true
   per-command clikt help would be better. Changing it touches shared `DevrigCliktCommand` plumbing —
   deferred out of the spike.
3. **`open_project --wait` uses `Thread.sleep` in a `runBlocking` loop.** Works, but it re-creates
   `StubMcpSteroidTools` each poll and blocks a thread. Fine for a one-shot CLI; would want a proper
   suspend/backoff loop (and to reuse the discovery push stream the monitor already has) if we invest.
4. **`runCli` is non-suspend and each command wraps its own `runBlocking(Dispatchers.IO)`** (matching
   the existing `runProjectCommand`). Consistent, but if the CLI grows it'd be cleaner to make the
   dispatch suspend end-to-end.
5. **`list_projects` is an alias to `devrig project`** (reconciliation per #191). Output is identical and
   exposes `project_name`. Good, but the MCP `ListProjectsResponse` shape (`ListedProject`) and the
   `devrig project` JSON shape are *near*-identical, not identical — a future unify could pick one.

## Command status

| Command | Parse+validate | Bridge wiring | Unit-tested (no IDE) | Verified vs live IDE |
|---|---|---|---|---|
| `prompt` / `fetch_resource` | ✅ | ✅ (Generic + project) | ✅ end-to-end | n/a (bundled, no IDE) ✅ |
| `list_projects` | ✅ | ✅ (alias → project) | ✅ (no-IDE path) | ⬜ needs IDE |
| `list_windows` | ✅ | ✅ | ✅ (no-IDE path) | ⬜ needs IDE |
| `execute_code` | ✅ (file/inline/modal/timeout) | ✅ | ✅ parse + validation | ⬜ needs IDE |
| `execute_feedback` | ✅ (rating range) | ✅ | ✅ parse + validation | ⬜ needs IDE |
| `open_project` (+`--wait`) | ✅ | ✅; `--wait` = basic poll | ✅ parse | ⬜ needs IDE |
| `take_screenshot` (+`--out`) | ✅ | ✅; `--out` writes PNG | ✅ parse | ⬜ needs IDE |
| `input` | ✅ | ✅ (raw sequence forwarded) | ✅ parse | ⬜ needs IDE |

"Needs IDE" = the bridge call itself is exercised by existing handler tests
(`DevrigToolBridgeClientTest`, `DevrigListToolHandlersTest`); only the CLI→handler glue for those five
is not yet covered by an in-process fake-bridge test (see follow-ups).

## Test coverage delivered

`./gradlew :npx-kt:test` and `./gradlew :mcp-steroid-server:test` both green. Covered: command parsing
for all 8 commands, missing/invalid-arg usage errors with examples, stdout/stderr separation, the
`--json` envelope, unknown-URI handling, prompt resolution without an IDE, and the render/exit-code
contract. Not yet covered: CLI→bridge payload mapping via an injected fake bridge for the five
tool-backed commands (would need `StubMcpSteroidTools` to accept an injectable `DevrigToolBridgeClient`).

## Recommendation for the final PR shape

**One foundational PR + follow-ups**, not a single mega-PR:

- **PR 1 (foundation):** `CliToolSupport` + `resolveResourcePayload` extraction + `prompt`/`fetch_resource`
  + `list_projects`/`list_windows`. Small, fully testable without an IDE, immediately useful
  (docs discovery + routing keys). Resolve the `--json` shape decision here (finding #1) so the envelope
  is locked before the write-commands land.
- **PR 2:** `execute_code` (highest value) — after making `StubMcpSteroidTools` accept an injectable
  bridge so the payload mapping gets a fake-bridge unit test. Add one Docker integration smoke test.
- **PR 3:** `execute_feedback` + the UX/debug trio (`take_screenshot --out`, `input`, `open_project --wait`).

Rationale: the write/IDE-driving commands (PR2/PR3) carry the real risk (need a live IDE to truly
verify, and `--wait`/`--out` semantics deserve their own review), while PR1 is low-risk and unblocks
agents that lost their MCP tools in a session. Keeping PR1 IDE-free also keeps its CI fast.

## Round 2 — hardening all tools (done)

- [x] **Unified `--json` envelope** `{tool, command, isError, data}` across ALL commands (finding #1
      resolved). `data` = `{content:[...]}` for tool-results, `{projects:[...]}` / `{windows,backgroundTasks}`
      for listers. `CliToolSupport.cliEnvelopeJson` is the single writer.
- [x] **Injectable handlers for testability**: each tool-command takes
      `tools: McpSteroidTools = StubMcpSteroidTools(this)`; `FakeMcpSteroidTools` + recording handlers
      drive **glue tests** for execute_code / feedback / input / take_screenshot / open_project, plus
      list_windows / list_projects (args→`*Params`→render→exit; no IDE). Bridge payload→wire mapping stays
      covered by `DevrigToolBridgeClientTest`.
- [x] `execute_code --code-file=-` reads the script from stdin.
- [x] `take_screenshot --out` creates parent dirs, writes decoded PNG, reports abs path; no-image case noted.
- [x] `open_project --wait` poll loop is test-friendly (injected `ListWindowsToolHandler`, small
      attempts/interval), quiet on stdout under `--json`.
- [x] `list_projects` reconciled: human = `devrig project`; `--json` = unified envelope via the MCP handler.

## Round 3 — Docker live-IDE smoke (written; needs Docker to run)

- [x] `CliDevrigToolsIntegrationTest` (`:test-integration`, opt-in — excluded from default runs) drives the
      deployed `devrig` binary as a plain CLI against a real dockerized IDE: `list_projects --json`
      (envelope + routing key), `execute_code` (marker printed by the IDE), `fetch_resource --project_name`.
      **Executed & green** — BUILD SUCCESSFUL, all 3 cases pass end-to-end against a live IDE container
      (~9 min). Run with: `./gradlew :test-integration:test --tests '*CliDevrigToolsIntegrationTest*'`.
      Confirmed: the devrig bridge translates the exposed routing key (`demo-project-<hash>` from
      `list_projects`) to the IDE's internal `project_name` before forwarding — agents only ever use the
      exposed key.

## Follow-ups / open questions

- [ ] Per-command `--help` (finding #2) — or explicitly accept the global banner and document it.
      **Update (Round 4):** `execute_code --help` now returns real per-command help (usage, required
      options, first-call rules, resource pointers). Confirm the other commands and close this out.
- [ ] `open_project --wait`: reuse the monitor's push stream instead of a sleep-poll loop.
- [ ] Agent-usable docs: add a `mcp-steroid://` article (or extend the skill) describing the CLI
      mapping so agents discover `devrig <tool>` the same way they discover the MCP tools.
- [ ] Optional client-side `input` sequence validation via `InputSequenceParser` (currently the IDE
      re-parses the raw sequence). **Round 4 confirms this is needed** — see the stack-trace leak below.

## Round 4 — Codex edge-case bug hunt (2026-07-07)

Source: `docs/mcp-as-cli/mcp-as-cli-codex-findings.md` — Codex drove `devrig-dev` (`…e6c62445`) against a
live host IDE, 22 shell probes across all 8 commands. Full reproducers are in that file. Triage:

**Status (2026-07-08): all Round-4 findings below are FIXED** (`:npx-kt`; `./gradlew :npx-kt:test` green,
spot-checked with `devrig-dev`). Contract decisions taken: (1) relative `--project_path` is **resolved
against cwd**; (2) every failure — usage/parse AND runtime — **emits the `isError:true` envelope** under
`--json` (exit codes unchanged: 64 usage, 69 unavailable). New shared writer `renderCliError`
(`CliToolSupport.kt`) is the single place that routes an error to a stderr line or a `--json` envelope.

### High — correctness / dangerous
- [x] **`open_project` accepts a relative `--project_path` and resolves it against `/`, not the caller's
      cwd.** FIXED: `runOpenProjectCommand` now normalizes `Path.of(projectPath).toAbsolutePath()
      .normalize()` against the caller's cwd before forwarding (and for the `--wait` poll). Pinned by
      `CliErrorEnvelopeTest."open_project resolves a relative --project_path against cwd"`.

### High — `--json` contract holes (the dominant theme)
Two distinct classes used to escape the `{tool, command, isError, data}` envelope even with `--json`:
- [x] **(a) Client-side usage / parse / validation errors → stderr-only, exit 64, no envelope.** FIXED
      (contract: emit envelope). `parseDevrigCommand` now records the concise message + best-effort
      command name and whether `--json` was requested (detected from raw tokens, since the exception
      aborts flag capture); `runCli`'s `DevrigCommandParseError` branch emits the `isError` envelope under
      `--json`, else prints the full help to stderr — exit stays 64. clikt-internal errors (unknown flag)
      get their specific "Error:" line lifted into the message. `--code-file` missing/non-regular now
      throws `CodeArgException` → enveloped. `--timeout`/`--success_rating` type errors ride the same path.
- [x] **(b) Runtime tool/bridge errors that SHOULD be enveloped but aren't.** FIXED: every pre-render
      catch (`runToolCall` for execute_code/feedback/input, `runScreenshotCommand`, `runOpenProjectCommand`,
      list_windows/list_projects) routes through `renderCliError(..., command.json, ...)` →
      `ProjectRouteNotFoundException` = enveloped USAGE(64), generic failure = enveloped UNAVAILABLE(69).
      Pinned by `CliErrorEnvelopeTest` (stale project_name + bridge failure + `--out` write failure).

### Medium — error-message quality
- [x] **`input` validation errors leak full server stack traces.** FIXED: `runInputCommand` validates the
      sequence client-side with `InputSequenceParser` before dispatch; on failure it returns the concise
      parser message + accepted-syntax hint via `renderCliError` (no stack, no round-trip). Still forwards
      the raw sequence on success (IDE stays the parsing source of truth). Verified: envelope text carries
      no `com.jonnyzzz.mcpSteroid.vision` / stack frames.
- [x] **`execute_code --timeout <= 0` is not validated client-side.** FIXED: `ExecuteCodeCliCommand`
      rejects a non-positive `--timeout` with a `UsageError` before dispatching (rides the F2 envelope
      path under `--json`).

### Medium — `take_screenshot --out` reporting
- [x] **The `--out` path is under-reported in the envelope.** FIXED: `writeScreenshotOut` returns the
      written absolute path; `runScreenshotCommand` appends a `Saved --out: <abs>` text item to the
      rendered result so the real destination appears in both human output and the `--json` envelope
      `data`. Relative `--out` policy = resolved-against-cwd + reported (the abs path communicates it).
      Write failures are enveloped (finding b).

### Low — rough edge
- [x] **`prompt --json` reports `"command": "fetch_resource"`.** FIXED: `DevrigCommandFetchResource`
      carries `commandName` (set to `prompt` / `fetch_resource` by the respective clikt command);
      `runFetchResourceCommand` echoes it into `renderTo`. Pinned by two `FetchResourceCommandTest` cases.

### Confirmed OK (no action)
Per-command `execute_code --help`; `--json` success envelopes; `list_projects`/`list_windows` +
routing key; `fetch_resource` (bundled, `--project_name`, unknown/empty/bad-scheme URI); `code-file=-`
stdin incl. empty + >1 MB; unknown-flag "Did you mean" suggestion; `success_rating` range check;
`take_screenshot` bad/no window_id; `input press:ESCAPE` + bad window_id; `open_project` nonexistent
path + already-open-with-backend + `--wait`; `list_projects --json | jq` (no banner leak).

## Round 5 — contract hardening (test-first, `:npx-kt:test` green: 1393 tests)

All 9 findings below are test-pinned (`McpAsCliContractTest`, `CliErrorEnvelopeTest`,
`McpAsCliParseTest`, `LauncherSelfHealPredicateTest`, `ExecuteCodeCommandTest`,
`ScreenshotAndOpenProjectCommandTest`) and spot-checked with `devrig-dev` (`jq`-valid envelopes,
exactly one JSON doc). Contract decisions taken:

- **Exit codes** — added `CliExit.DATA_ERROR=65` (bridge returned unusable data: no image / undecodable
  base64) and `CliExit.IO_ERROR=74` (genuine write/read failure) alongside the existing OK / TOOL_ERROR
  (1) / USAGE (64) / UNAVAILABLE (69). BSD sysexits. USAGE stays for fixable input (missing arg,
  malformed path *string*).

1. **Unified `--json` error contract.** Every failure path emits exactly one
   `{tool, command, isError:true, data}` envelope on stdout (strict-JSON valid) with a meaningful
   non-zero exit; no stack traces on stdout, no success-then-fail. The `Main.kt` `runCli` catch-all now
   also envelopes under `--json` (and rethrows `CancellationException`).
2. **`execute_code --modal <unknown>`** validated at PARSE (`ExecuteCodeCliCommand`) → rides the
   parse-error envelope (honors `--json`, exit 64). Removed the stderr-only branch in the runner.
3. **`open_project --wait --json`** no longer prints an intermediate success envelope. Under `--wait`
   the poll runs BEFORE any stdout; one FINAL envelope reflects the outcome — ready → success (exit 0),
   timeout → isError UNAVAILABLE. Transient poll failures still retried; readiness contract unchanged.
4. **Local path / IO errors normalized** through `renderCliError`: unreadable `--code-file` → IO_ERROR,
   malformed `--code-file`/`--project_path`/`--out` path string → USAGE, `--out` write failure
   (directory/permission) → IO_ERROR. No stack traces to `Main`.
5. **Input single source of truth.** Removed the client-side `InputSequenceParser` rejection — the raw
   sequence is forwarded verbatim (version-skew safe). The server stack-trace leak is fixed by
   `sanitizeServerError` (strips JVM frames), applied ONLY to the `input` tool's error result — never
   `execute_code`, whose trace is the agent's own script.
6. **`take_screenshot --out` strict contract.** Exit 0 only when the file is actually written; no image
   → DATA_ERROR, undecodable base64 → DATA_ERROR. Success exposes a STRUCTURED `data.savedOut` absolute
   path in the `--json` envelope (devrig-owned envelope only — wire `ToolCallResult` untouched, Tenet 5).
7. **Parse-error command name** via a closed-set subcommand scan (`recoverCommandName` +
   `DEVRIG_SUBCOMMAND_NAMES`), replacing the `firstOrNull { !startsWith("-") }` heuristic. Covers global
   flags before/after the subcommand, option values as separate tokens, unknown command, unknown option.
8. **`execute_feedback --execution_id`** kept for MCP-surface parity (steroid_execute_feedback likewise
   accepts-but-ignores it; `FeedbackParams` has no field). Help text now states it is contextual only /
   not forwarded; a glue test pins that it never reaches `FeedbackParams`. No wire change.
9. **Tenet 3 boundary.** `ensureBinLauncher` on-start self-heal is gated by
   `DevrigCommand.selfHealsLauncherOnStart()` — the 8 stateless tool facades no longer mutate on-disk
   launcher/PATH state; lifecycle commands (mcp/install/backend/project/help/version) still do. The
   launcher integration tests use `devrig version` (lifecycle), so they are unaffected.

### Remaining risks / follow-ups
- Docker `CliDevrigToolsIntegrationTest` (opt-in, needs a live IDE) not re-run this round — the launcher
  gating only affects tool facades, which it drives by absolute path, and unit coverage is comprehensive.
  Re-run before a release if #9 is in scope.
- `open_project --wait` still uses a bounded sleep-poll loop (not the monitor push stream) — unchanged.
