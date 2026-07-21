# Design philosophy — read this first

This document is the canonical statement of four tenets that govern every
change to MCP Steroid: code, tool surface, prompt resources, and the
devrig CLI. It is
written for AI agents first (imperative) and human contributors second
(rationale at the bottom).

If you are about to **add a new MCP tool**, **add a new method to
`McpScriptContext`**, or **propose a new "helper" inside `steroid_execute_code`'s
runtime** — read this first and re-read it. Most of the time the answer is
"don't; teach the agent to call the IntelliJ API directly."

A runtime mirror of this file lives at
`mcp-steroid://skill/design-philosophy` so you can fetch it via
`steroid_fetch_resource` mid-session.

---

## Tenet 1 — minimal MCP tool surface

**Don't propose new `steroid_*` tools.** MCP Steroid intentionally maintains
a small set of MCP tools. Today there are 8:

- `steroid_list_projects`
- `steroid_list_windows`
- `steroid_open_project`
- `steroid_execute_code`
- `steroid_execute_feedback`
- `steroid_take_screenshot`
- `steroid_input`
- `steroid_fetch_resource`

This is the entire surface. The number is intentionally low. Improvements
to "agents deliver more" do **not** come from additional tools — they come
from richer prompt resources, sharper tool descriptions, and teaching the
agent to call IntelliJ APIs directly inside `steroid_execute_code`.

A new tool may be added only when **all** of these are true:

1. The need cannot be met by `steroid_execute_code` + a direct IntelliJ API
   call. Document the specific API path you ruled out.
2. It cannot be met by a richer `mcp-steroid://` prompt resource (recipe).
3. Three independent reviewers (`run-agent.sh codex` / `claude` / `gemini`)
   agree the tool is justified, after reading this file.

Anything short of that — propose a recipe instead.

**Worked example — apply-patch removed, twice.** A dedicated
`steroid_apply_patch` tool duplicated what `steroid_execute_code` already
delivers and was removed (May 2026). The in-script `applyPatch { }` DSL
survived it — until full-scale eval data showed its exact-match resolution
failed 64% of real calls, and it was removed too (#206, July 2026); a
tolerance-matching successor is backlogged as #208. Both removals are what
Tenet 1 looks like in practice — unlock IDE depth rather than keep an
attractive-but-unreliable generic edit path.

## Tenet 2 — power lives in prompts and direct IntelliJ API usage

**Teach the agent to call IntelliJ's APIs as IntelliJ exposes them.** Don't
wrap them. Don't introduce parallel "agent-friendly" abstractions.

Concretely:

- **Inside `steroid_execute_code`:** call `FilenameIndex`,
  `JavaPsiFacade`, `ProjectTaskManager`, `XDebuggerUtil`, `VfsUtil`,
  `ReferencesSearch`, `MainPassesRunner`, `Observation.awaitConfiguration`,
  `smartReadAction { }`, `writeAction { }`, etc. directly. The full plugin
  classpath is loaded; typed imports work.
- **In prompt resources:** when teaching a recipe, show the canonical
  IntelliJ idiom. If the IDE-version-drift cost is real, document the drift
  inline rather than wrapping it.
- **Don't add a "helper" method to a `McpSteroid*` class** to save the
  agent from typing 5 lines of IntelliJ API. The 5 lines themselves teach
  the agent a transferable skill.
- **Reflection is fine as a probe, never in the recipe you ship.** The
  full reflection policy lives in the MCP server's startup instructions
  (the text every agent receives on session init) and at
  `mcp-steroid://prompt/skill`.

This tenet harmonises with the strategy page's *"Give AI the whole IDE,
not just the files"*: the **MCP tool** surface stays narrow on purpose;
the **IntelliJ capability** surface stays full, exposed via
`steroid_execute_code` plus prompts.

## Tenet 3 — devrig is stateless

**The `devrig` binary holds no state across calls.** Every CLI
invocation is a fresh process; `devrig mcp` (the stdio MCP server)
holds only in-memory caches that live for the duration of the
session and are rebuilt from scratch on the next process start.

Concretely:

- **No persistent state on disk** is owned by devrig itself.
  Existing on-disk artefacts (managed-backend installs under
  `~/.mcp-steroid/backends/`, pid markers under
  `~/.mcp-steroid/markers/`, the per-binary download cache) are
  inputs the devrig process *reads*, never something it
  serialises its own state into.
- **No cross-call coordination.** Two `devrig` processes against
  the same `~/.mcp-steroid` directory must work identically to
  one process; the spec at
  [`docs/devrig-naming.md`](devrig-naming.md) does not assume
  exclusive ownership.
- **In-memory caches are allowed** within one process — the live
  routing-model snapshot, the marker decoder cache, the
  managed-backend installer's per-call working set. They die with
  the process.
- **Background scanning is implementation-detail, not contract.**
  Today devrig runs marker / port / per-IDE-stream scanners in the
  background of `devrig mcp`; whether those stay or get replaced
  by on-demand rebuild (see
  [`docs/devrig-scanning-research.md`](devrig-scanning-research.md))
  is a tactical decision. Neither variant changes the contract
  callers see.

**Why this matters:**

- The spec's *"every MCP call selects its target from the current
  snapshot, no in-flight reroute"* contract relies on devrig being
  reconstructable from scratch on every fresh invocation. There is
  nothing to "migrate" between versions of devrig.
- Persistent state would force devrig into the schema-migration
  business — a tax that doesn't pay back for a CLI whose entire job
  is to forward MCP calls to running IDEs.
- The stateless invariant is what lets two devrigs on one workstation
  (one for each IDE family, or two agents on one machine) coexist
  cleanly without locking.

**Adding state to devrig requires:**

1. A written argument that the in-memory + on-call-rebuild model
   genuinely cannot cover the case. "More efficient" is not enough.
2. Explicit reviewer consensus (`run-agent.sh codex` / `claude` /
   `gemini`). One reviewer disagreeing kills the proposal.
3. A migration story for what happens when a future devrig version
   reads the older state shape — devrig must be deletable + re-
   installable without losing functionality the user cares about.

## Tenet 4 — `McpScriptContext` methods are last-resort

**Don't add methods to `McpScriptContext` casually.** The helpers
exposed to scripts running inside `steroid_execute_code` (see
`ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/McpScriptContext.kt`
for the current surface — `project`, `disposable`, `printJson(...)`,
`progress(...)`, `findProjectFile(...)`, `projectScope()`,
the inspection / highlighting helpers, etc.) exist because the
IntelliJ API genuinely could not cover the case at the time. They are
not the default extension point; the IntelliJ API is. The surface is
already substantial — that's why "don't grow it" is a tenet, not a
preference.

**Adding a new method to `McpScriptContext` requires:**

1. A short written argument that the IntelliJ-native path is genuinely
   intractable. "Less convenient" is not intractable.
2. Explicit reviewer consensus across `run-agent.sh codex` / `claude` /
   `gemini`. One reviewer disagreeing kills the proposal — propose a
   prompt-resource recipe instead.
3. The new method must teach an idiom that's reusable across many tasks,
   not specialised to one DPAIA scenario.

**`applyPatch { }` is the cautionary example of this bar.** The DSL earned
its `McpScriptContext` seat for atomic multi-site edits — and lost it when
eval data showed 64% of real calls failed on exact-match resolution
(removed in #206; tolerance-matching successor tracked in #208). Earning
the surface is necessary but not sufficient: a method must also prove
agents can use it reliably at scale. New context methods must clear both
bars.

## Tenet 5 — the devrig↔plugin WIRE is additive-only (the devrig-computed output is not)

**The wire between `devrig` and the in-IDE plugin must stay forward AND
backward compatible at all times. No breaking changes — ever.** A given
devrig binary talks to many plugin versions and vice-versa (Tenet 3:
devrig is reconstructable, not migratable).

**Scope this precisely.** The additive-only contract binds the
**devrig↔IDE wire ONLY**: the `/projects/stream` and `/windows`
responses, the JSON-RPC `/tools/call` params devrig POSTs to the bridge,
and the `@Serializable` DTOs they (de)serialize (`NpxStreamEnvelope`,
`NpxBridgeWindowsResponse`, `PidMarker`, `ToolCallResult`, `ProjectInfo`).
Every change to one of those shapes is **additive and optional**:

- New fields are optional with a safe default (`= null` / `= ""` /
  `= false` / a defaulted enum). An older peer that omits them still
  decodes; a newer peer that ignores them still works.
- Never remove, rename, or retype an existing field. Enums must degrade
  on an unknown value, not throw.

**One-release waiver — startable-backends (2026-06-22).** The additive-
only constraint was **deliberately waived for the startable-backends
change**. devrig and the plugin shipped together in that release, so DTO
fields, marker fields, and tool-response shapes were reshaped and removed
outright (no deprecation shims). Specifically: `BackendInfo` and
`ListedBackendInfo` were deleted; `backends[]` was removed from
`ListProjectsResponse` and `ListWindowsResponse` (which now carry only
`projects` / `windows` + `backgroundTasks`); the CLI `backend --json`
shape changed from one `backends[]` to three explicit arrays
(`mcpSteroidBackends[]`, `otherIdes[]`, `startableBackends[]`). This
waiver is a one-time exception, not a precedent — the principle stays in
force for all future changes. See `docs/startable-backends-design.md`.

**The devrig-computed MCP/CLI output is devrig-owned and outside this
contract — free to reshape.** `steroid_list_projects` results
(`ListProjectsResponse`, `ListedProject`) and the devrig CLI
`backend`/`project --json` output are built by devrig from its own
routing snapshot and returned to one freshly-attached agent or user —
devrig **never fetches** the IDE's `steroid_list_projects`, so these
types never cross the wire. They may be renamed, restructured, or
re-keyed at will; only the wire above is frozen.

- **Prefer resolving new behavior inside devrig over extending the
  wire.** The `backend_name` routing parameter is the canonical example:
  it is an MCP-surface parameter that devrig resolves **locally** to a
  target IDE and **never forwards** to the bridge, so the
  `steroid_open_project` bridge call stayed byte-identical while the
  agent gained backend selection. `backend_name` is resolved locally,
  never forwarded.
- Because the per-project backend reference lives on the devrig-owned
  `ListedProject` (not on the wire), the wire `ProjectInfo` stays
  pristine `{name, path}`. `ProjectsStreamService` only ever builds
  `ProjectInfo(name, path)`, so no backend reference ever crosses the
  wire.
- Every wire change ships with a cross-version compatibility test (the
  wire-pristineness guard `WirePristinenessTest` asserts the wire never
  serializes the devrig-only `backend_name`/`project_name`/`ListedProject`
  keys; `DevrigToolBridgeClientTest` pins the forwarded params) and a
  one-line entry in the `ij-plugin/CLAUDE.md` wire-contract table.

**Why:** the protocol is the one thing two independently-versioned
binaries share; a breaking change there strands every mismatched pair.
Additive-only is what makes "delete devrig, reinstall any version" safe.
Conversely, the devrig-computed output answers a single live agent and
need not carry that burden.

---

## Where this comes from

External anchors (read at least one before proposing changes that touch
the tool surface or `McpScriptContext`):

- **RLM methodology** — <https://jonnyzzz.com/RLM.md>. Six-step protocol
  (assess → decide → decompose → execute → synthesize → verify) with
  context-size-driven strategy: direct processing under 4K tokens,
  grep-first up to ~50K, partition + map to parallel sub-agents above
  that or across multiple files. Applies to every code, doc, or research
  task in this repo.
- **Agentic experience and tools** —
  <https://jonnyzzz.com/blog/2026/03/24/agentic-experience-and-tools/>.
  *"Agents follow the same processes as humans. No shortcuts. No special
  agent-only paths."* This is the source of Tenet 2.
- **Strategy page** — <https://devrig.dev/docs/strategy/>.
  "Give AI the whole IDE, not just the files." Phase 1 (IDE plugin) →
  Phase 2 (benchmarks) → Phase 3 (headless runtime).

## How this is used in practice

When you read this file, you should be able to:

- Identify a proposed change that adds a tool or context method, and
  reject it (or, in rare cases, accept it via the criteria above).
- Identify wording in a `mcp-steroid://` resource that wraps an IntelliJ
  API instead of teaching it, and rewrite it.
- Identify gaps where a prompt resource should exist instead of a code
  change, and write the resource.

Per-folder agent guides (`ij-plugin/CLAUDE.md`, `prompts/AGENTS.md`,
`test-integration/AGENTS.md`, `test-experiments/CLAUDE.md`,
`docs/CLAUDE.md`, `website/CLAUDE.md`) cite these tenets and apply them
in their local context. The root `CLAUDE.md` / `AGENTS.md`, the runtime
mirror at `mcp-steroid://skill/design-philosophy`, the most-trafficked
prompt resources (`mcp-steroid-info.md`, `execute-code-tool-description.md`,
`coding-with-intellij.md`), and the public `README.md` all link here.

## For human contributors — rationale

The tenets exist because every additional tool, every additional context
method, and every wrapper helper has compounding costs:

- **Additional surface area for the agent to mis-route.** An agent that
  has 30 tools is measurably worse at picking the right one than an
  agent that has 10. Tool descriptions compete for attention in the
  agent's context.
- **Drift between tools and the IntelliJ API they were meant to abstract.**
  Wrappers fall behind on every IDE release; agents reading them learn
  yesterday's idiom.
- **Forks in the recipe corpus.** Two ways to do the same thing — the
  tool and the API — means prompts must explain both, and the agent has
  to choose. The agent picks badly more often than not.

The lever that compounds *positively* is prompt quality. Good recipes
teach the agent transferable skills it carries to the next task. Better
descriptions route the agent to the right tool first try. A `mcp-steroid://`
article with a 30-line copy-paste Kotlin snippet is worth ten new
single-purpose tools.

When in doubt: write a recipe, run the DPAIA arena, measure the delta in
tokens / tool calls / wall time, iterate.
