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

## Follow-ups / open questions

- [ ] **Docker live-IDE smoke suite** (`CliDevrigToolsIntegrationTest`, opt-in) — the remaining item.
- [ ] Per-command `--help` (finding #2) — or explicitly accept the global banner and document it.
- [ ] `open_project --wait`: reuse the monitor's push stream instead of a sleep-poll loop.
- [ ] Agent-usable docs: add a `mcp-steroid://` article (or extend the skill) describing the CLI
      mapping so agents discover `devrig <tool>` the same way they discover the MCP tools.
- [ ] Optional client-side `input` sequence validation via `InputSequenceParser` (currently the IDE
      re-parses the raw sequence).
