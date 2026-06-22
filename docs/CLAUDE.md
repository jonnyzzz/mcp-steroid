# docs — Working Notes & Autoresearch

This folder holds long-form research, design plans, and the DPAIA arena working log. Read this **in
addition to** the root `CLAUDE.md` when changing files under `docs/` or referencing autoresearch
results.

The repo's four design tenets — small MCP tool surface; power lives in
prompts and direct IntelliJ API usage; `devrig` is stateless;
`McpScriptContext` is last-resort — are codified in
[`PHILOSOPHY.md`](PHILOSOPHY.md) (and mirrored at runtime as
`mcp-steroid://skill/design-philosophy`). Autoresearch and prompt-
optimization work in this folder is the primary lever for those tenets:
every measurement here ultimately feeds back into recipe quality, not
new tools or new context methods.

For the test code that drives DPAIA / arena scenarios see `test-experiments/CLAUDE.md`.

## Specs in this folder

Long-form contract documents owned by `docs/` (read these directly
rather than mirroring their contents into per-folder guides):

- [`PHILOSOPHY.md`](PHILOSOPHY.md) — the four design tenets.
- [`devrig-naming.md`](devrig-naming.md) — devrig CLI + stdio MCP
  project/backend naming contract (slug rule, `bootHash`,
  `archiveSha256`, `actions[].argv`, on-demand routing).
- [`devrig-scanning-research.md`](devrig-scanning-research.md) —
  decision record for on-demand `rebuildSnapshot()` vs background
  scanners (option A).
- [`devrig-deployment-spec.md`](devrig-deployment-spec.md) — locked v7
  design for `~/.mcp-steroid/` install layout: wrapper-driven
  content-addressed cache, bundled Corretto JDK, two-key signed
  self-update, auto-GC, agent registration wizard, dev-mode
  pre-population, and a native-binary alternative appendix.
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — top-level architecture
  map.
- [`TESTING-STRATEGY.md`](TESTING-STRATEGY.md) — test layout +
  per-module scoping rules.

## Prompt optimization (autoresearch)

MCP Steroid serves prompt resources (`mcp-steroid://` URIs) that guide AI agents. Optimizing these
prompts is an iterative process — running curated DPAIA tasks under controlled conditions, measuring
tool calls and tokens, then narrowing the gap between agent behavior and expected behavior with
prompt-only edits.

Findings, comparison tables, and the Karpathy-style optimization loop prompts:

- `docs/autoresearch-findings.md` — high-level summary
- `docs/autoresearch/` — loop prompts
- `docs/arena-3pass-results.md` — full 3-pass comparison table

### Key findings (from 51 arena runs + 2 autoresearch cycles)

- **MCP server instructions** (`prompts/src/main/prompts/mcp-steroid-info.md`) are metadata context,
  not behavioral directives — agents don't follow them during planning.
- **Tool descriptions** are schema reference — MANDATORY warnings in them don't change behavior.
- **MCP resources** (84 available via `ReadMcpResourceTool`) are rarely read (0/69 runs in baseline).
- **Arena prompt recipes** DO work — agents follow first-call exec_code recipes verbatim.
- **exec_code output** drives next-step decisions — agents act on compile results immediately.

### Where agent information lives (priority order)

1. **User prompt** (arena task) — agents follow this. Put recipes here.
2. **exec_code output** — agents act on results. Put suggestions here.
3. **System prompt** (MCP server instructions) — background context only.
4. **Tool schema description** — reference material, not directives.
5. **MCP resources** — rarely accessed unless explicitly fetched.

### Prompt files

| File | What it controls | Impact |
|------|-----------------|--------|
| `prompts/src/main/prompts/mcp-steroid-info.md` | MCP server instructions (system prompt) | Low — ignored by agents |
| `prompts/src/main/prompts/skill/execute-code-tool-description.md` | `steroid_execute_code` description | Medium — read as reference |
| `test-experiments/.../arena/ArenaTestRunner.kt` (`buildPrompt()`) | Arena task prompt | High — agents follow recipes |
| `prompts/src/main/prompts/skill/*.md` | MCP resources | Low — rarely read by agents |

## Active DPAIA working notes

- Repo-root `../TASKS.md` is the active task list (open tasks only). The former repo-root `MEMORY.md`
  DPAIA handoff log was distilled into the docs below + `git log` and removed — durable findings now live
  in `autoresearch-findings.md` / `arena-3pass-results.md`, not a running scratch file.
- New DPAIA ideas must also be logged in `../TODO-DPAIA.md` (or sibling).
- **Direction changes require 3 `run-agent.sh` reviews and consensus** before selecting the next
  low-hanging fruit.
- **Constraints for the autoresearch track:** do not add `McpSteroid*` interface methods; do not add
  new MCP tools. Improvements must be prompt-only (skill articles, tool descriptions, system prompt
  text).

### History (2026-04-26 → 2026-04-27)

Full chronological run-by-run history is intentionally NOT mirrored here — it churned weekly during
active iteration and lives in:

- `arena-3pass-results.md` — measured tables.
- `autoresearch-findings.md` — synthesized takeaways.
- `git log` per file — diff per change (see anchors below). The former repo-root `MEMORY.md` running
  log (which once held the day-to-day "recent facts" + next-step consensus) was distilled into these
  docs and removed; recover any specific run note from `git log -- MEMORY.md`.

When picking up the autoresearch track:
1. Read `autoresearch-findings.md` (+ `git log -- MEMORY.md` for the last next-step consensus, if needed).
2. Confirm the consensus is still load-bearing by checking `git log` since that note was written.
3. Run the most recent baseline scenario before changing prompts so you have a fresh comparison point.

### Latest landmark anchors (with `git log` commands for digging)

```bash
# Gradle abort root-cause fix — ProjectDataImportListener.onFinalTasksFinished as the sync boundary;
# DPAIA Microshop on JDK 24 because Gradle 8.14.3 rejects Java 25 daemon.
git log --oneline -- test-integration/src/main/kotlin/com/jonnyzzz/mcpSteroid/integration/infra/intelliJ.kt
git log --oneline --grep="onFinalTasksFinished\|JDK 24\|Gradle abort"

# Gradle IDE guidance — arena prompts inline ProjectTaskManager.build(*modules).await() before Bash fallback.
git log --oneline -- prompts/src/main/prompts/skill/execute-code-gradle.md
git log --oneline -- test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/ArenaTestRunner.kt

# Apply-patch DSL persistence — applyPatch { } inside steroid_execute_code saves every touched document.
git log --oneline --grep="apply.patch\|ApplyPatch"

# Aborted-build result-boundary guidance — ExecuteCodeToolHandler appends REQUIRED ACTION hint
# (uses ExecuteCodeGradlePromptArticle().uri / ExecuteCodeMavenPromptArticle().uri).
git log --oneline -- ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ExecuteCodeToolHandler.kt
git log --oneline --grep="aborted=true\|REQUIRED ACTION\|build-abort\|BuildAbortGuidance"

# IntelliJ monorepo lookup — prefer Observation.awaitConfiguration(project) + smartReadAction(project)
# over waitForSmartMode() for indexed reads (regression: IntelliJThisLoggerLookupTest).
git log --oneline --grep="awaitConfiguration\|smartReadAction\|ThisLoggerLookup"
```
