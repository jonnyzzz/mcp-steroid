# `steroid_apply_patch` API audit vs standard agent edit tools

> **Status (2026-07-03):** the in-script `applyPatch { }` DSL analyzed below is **removed from
> the product** ([#206](https://github.com/jonnyzzz/mcp-steroid/issues/206)) after run-3 eval
> data showed a 64% call failure rate. The efficient successor (tolerance-ladder matching, see
> the `GenericPatchApplier` section) is backlogged as
> [#208](https://github.com/jonnyzzz/mcp-steroid/issues/208). Last main revision with the DSL
> present: [`97363152`](https://github.com/jonnyzzz/mcp-steroid/commit/97363152153f6e6c3077e6ca2265a8aef5f4e2c2).
> This document stays as the design record for #208. Interim: the platform engine is already
> reachable today via the `mcp-steroid://ide/apply-unified-diff` recipe (an escape hatch for
> complex changes / existing diffs — the read-replace-save script remains the primary flow).

Cross-referencing our tool's input shape and semantics against the
built-in file-mutation tools of the major AI coding CLIs. Evidence
sources: tool-use NDJSON from the DPAIA autoresearch runs (real Claude
Code CLI), cloned / inspected source (`openai/codex`,
`sst/opencode`), Pi documentation (`mariozechner.at/posts/
2025-11-30-pi-coding-agent`), and Anthropic Text-Editor tool docs.

## Comparison table

| Tool | Field names | Multi-file | Atomicity | Pre-validation | Notes |
|------|-------------|------------|-----------|----------------|-------|
| **Claude Code CLI `Edit`** | `{file_path, old_string, new_string, replace_all?}` | no (one file / call) | per-file | `old_string` must occur exactly once unless `replace_all=true` | Field names observed directly from DPAIA NDJSON |
| **Claude Code CLI `MultiEdit`** | `{file_path, edits: [{old_string, new_string, replace_all?}]}` *(from Anthropic tool reference; never observed in DPAIA runs)* | no (one file, N edits) | file-scoped: all or none | each edit's `old_string` must be unique before it is applied; edits are applied top-to-bottom, each sees the result of the previous | Agents rarely reach for it; Claude's `Edit` chain is the observed pattern |
| **Claude Code CLI `Write`** | `{file_path, content}` | no | full-file overwrite | n/a | |
| **Anthropic Text-Editor `str_replace`** | `{old_str, new_str}` (plus `path`) | no | per-file | exactly-one match | Different naming convention from Claude Code CLI |
| **Codex `apply_patch` (model-facing tool)** | `{input: string}` — opaque V4A envelope: `*** Begin Patch / *** Add File: / *** Update File: / *** Delete File: / *** Move to: / @@ / +/-/  lines` | yes | **not atomic** — `apply_hunks_to_files` writes each file sequentially with `fs.write_file`; mid-patch failure leaves partial state | envelope-level parse is up front, but per-hunk context match runs inside the write loop | Freeform grammar for GPT-5; JSON fallback for gpt-oss. NDJSON emits `ThreadItem::FileChange{changes:[{path,kind:{type:"add/update/delete", movePath?}, diff}], status}`. Sources: `codex-rs/apply-patch/src/lib.rs:260-361`, `protocol.rs:3807-3821`, `app-server-protocol/v2.rs:5432-6076` |
| **OpenCode `edit`** | `{filePath, oldString, newString, replaceAll?}` (camelCase) | no | per-file (semaphore-locked) | fuzzy-match pipeline (9 `Replacer` strategies) — throws if zero or >1 match | Auto-runs formatter + LSP diagnostics after the write and appends errors to tool output. Source: `packages/opencode/src/tool/edit.ts:35-45, 192-196, 673-710` |
| **OpenCode `apply_patch`** | `{patchText: string}` — same V4A envelope as Codex | yes | **near-atomic (2-phase)**: phase 1 parses + validates all hunks and derives new contents in memory; phase 2 writes sequentially (no rollback if a later write errors) | strong — `Patch.parsePatch` + `deriveNewContentsFromChunks` + `afs.stat` existence checks for every hunk before any write | Single permission prompt covering all paths. Source: `packages/opencode/src/tool/apply_patch.ts:41-209` |
| **Pi CLI `edit`** | `{path, oldText, newText}` | no | per-file | `oldText` must match exactly (including whitespace) | Minimal toolset philosophy ("read/write/edit is all you need"). No multi-edit / apply-patch. Source: `mariozechner.at/posts/2025-11-30-pi-coding-agent` |
| **Pi CLI `write`** | `{path, content}` | no | full-file | n/a | |
| **`steroid_apply_patch` (ours)** | `{project_name, task_id, reason, hunks: [{file_path, old_string, new_string}]}` | **yes** | **fully atomic** — pre-flight resolves every hunk in a single read-action, validates exactly-one-occurrence per hunk, then applies all hunks in a single `WriteCommandAction` command (one undo step, PSI committed in the same action) | yes, all hunks validated before any edit lands (throws `ApplyPatchException` with hunk index + path + both offsets on non-unique, missing, or unresolvable) | Plus DialogKiller + `Observation.awaitConfiguration` pre-flight to keep the write action from blocking on modals / project saves |

## Findings

### Field-name alignment

- **`old_string` / `new_string`** — matches **Claude Code CLI `Edit`** exactly. Codex's V4A envelope has no JSON field; Anthropic's Text-Editor calls them `old_str` / `new_str`; OpenCode's `edit` uses camelCase `oldString` / `newString`; Pi uses `oldText` / `newText`. Our choice is the most-widely-recognisable for agents that were trained against Claude Code.
- **`file_path`** — aligned with Claude Code `Edit`. Originally we used `path`; renamed to `file_path` in the same commit as this audit so Claude-trained agents can re-use their `Edit` knowledge without a translation step. (OpenCode `edit` uses camelCase `filePath`; Pi uses `path` — our choice matches the most common upstream.)
- **Our extra keys** (`project_name`, `task_id`, `reason`) are MCP-specific and have no counterpart in any built-in. They're justified: we need the project to run in, audit grouping, and a one-line summary for the execution log.

### Atomicity — we have the strongest guarantee

Ranked by how robust atomicity is across a multi-file patch:

1. **`steroid_apply_patch`** — pre-flight + single `WriteCommandAction` = actual all-or-nothing within the IDE's transaction boundary. If the write fails, nothing is committed.
2. **OpenCode `apply_patch`** — 2-phase (parse+validate all, then write all), but the write phase is sequential with no rollback if a later write errors mid-loop.
3. **Codex `apply_patch`** — parse up front, but no atomicity on write. Mid-patch failures leave the FS in an inconsistent state.
4. **Claude `MultiEdit`** — scoped to ONE file; sequential within the file.
5. **Claude `Edit`, OpenCode `edit`, Pi `edit`** — single hunk, single file. No atomic multi-hunk.

### Features we deliberately don't have

V4A envelopes (Codex, OpenCode `apply_patch`) cover file lifecycle ops: **add, delete, rename/move**. We support only `update`. That's by design — `steroid_apply_patch` is an in-place literal-text batcher, orthogonal to file creation (`Write`, `findProjectFile().delete()` inside `steroid_execute_code`, `moveClass` skill, etc.). Adding them would grow our surface without matching what users currently need (validated over iter-15 + iter-17 where no "add file" was part of any applyPatch call).

### Diff format — JSON-native vs V4A envelope

Codex + OpenCode `apply_patch` both accept an opaque V4A-style envelope as a string. Ours ships the hunks as structured JSON. Tradeoffs:

- **Structured JSON (ours)**: trivial to pretty-print, log, diff-in-diff, or round-trip through MCP's tool-input validation. No fragile text parser on the server.
- **V4A envelope (Codex/OpenCode)**: one string field is simpler to slot into an OpenAI function-call signature. But the parser is complex (handles context lines, hunk headers, file-kind sentinels, moves). Both of their parsers are 100s of lines of real code.

Our JSON-native choice avoids a parser entirely. Against Claude's `Edit` + `MultiEdit`, it's the natural multi-file generalisation.

## How the IntelliJ platform itself applies patches — `GenericPatchApplier` comparison

The IDE ships its own patch engine, used by VCS **Apply Patch**, shelf/unshelve, and stash: 
`GenericPatchApplier` (`platform/vcs-impl/src/com/intellij/openapi/diff/impl/patch/apply/GenericPatchApplier.java`,
~1,400 lines), driven from `ApplyTextFilePatch`. It solves the same problem as our DSL — apply
text edits to a file that may have drifted — with the opposite design at almost every decision
point.

### Input model

The platform consumes **unified-diff hunks** (`PatchHunk` → `SplitHunk`): context lines around
the change, added/removed lines, and expected line numbers. Ours consumes **literal
`old_string`/`new_string` pairs** with no context lines and no positions. The platform's model
carries redundancy the matcher can exploit; ours carries exactly one anchor that must match
byte-for-byte.

### Matching: a 4-step tolerance ladder vs one exact `indexOf`

`GenericPatchApplier.execute()` walks a ladder (`GenericPatchApplier.java:179-236`):

1. **Exact match with full context** at the expected position.
2. **Exact hunk body, reduced/without context** — searches up to `ourMaxWalk = 1000` lines away
   from the expected position (`testForPartialContextMatch` + `ExactMatchSolver`).
3. **"Variable place" match** — insert/delete complemented, still exact-body, position-free.
4. Optionally `trySolveSomehow()` (`:363-393`): `LongTryMismatchSolver` **tolerates mismatching
   lines inside the hunk body**, trims identical tails, pads short hunks — genuine fuzzy
   application as a last resort.

Whatever still fails lands in a `myNotBound` list and the result is **PARTIAL**, never a hard
stop. Our DSL does step 0 only: `document.text.indexOf(old_string)` must succeed and be unique
(`ApplyPatch.kt:156-168`); any miss rejects the whole patch.

There is also a strict platform variant — `PlainSimplePatchApplier` (`@ApiStatus.Internal`),
line-number-based and all-or-nothing (`null` on any mismatch) — which shows the platform
deliberately keeps *both* postures and picks per use-case.

### Status model and idempotency

The platform returns `SUCCESS / PARTIAL / ALREADY_APPLIED / SKIP / FAILURE / ABORT`
(`ApplyPatchStatus.java`). **`ALREADY_APPLIED` is detected** — a hunk whose *after* state is
already in the file is recognized and skipped, so re-applying a patch is idempotent. Our DSL has
no equivalent: an already-applied hunk simply fails "old_string not found" (the closest-candidate
diagnostics are the agent's only clue).

### Failure handling: interactive merge vs structured error

On PARTIAL/FAILURE the platform does not give up — `ApplyTextFilePatch` hands the leftovers to a
**three-way merge UI** with base-revision texts (`getMergeData()` →
`ApplyPatchForBaseRevisionTexts`), letting a human resolve what the matcher couldn't. Our
agent-facing equivalent is the structured error message (hunk index, path, closest-candidate
lines). The platform also wraps its own engine in `catch (Throwable)` with a fallback state
("GenericPatchApplier is buggy, limit AIOOB impact on user") — degrade, never crash.

Bonus capability we lack entirely: `weightContextMatch(maxWalk, maxPartsToCheck)` scores how
well a patch fits a given base text — the platform uses it to *choose the best base revision*
before applying (shelf/unshelve).

### What this means for us (run-3 evidence)

At eval scale our exact-match posture failed **56 of 87 DSL invocations (64 %)** — 52 of them
"old_string not found", i.e. precisely the drift class the platform ladder was built to absorb
(steps 2–4 exist because exact-match-at-position fails in practice). Two caveats before reusing
the platform engine directly:

- `GenericPatchApplier` lives in an `impl` package (no `@ApiStatus` marking, but not a stable
  API surface either) — per this repo's public-stable-API rule, prefer **borrowing the ladder
  design** (exact → context-relaxed → position-relaxed → fuzzy-with-report) over linking the
  class, unless its stability is confirmed.
- The platform's PARTIAL model conflicts with our atomicity guarantee (the strongest surveyed,
  see above). The ladder can be adopted *inside* the all-or-nothing pre-flight: resolve every
  hunk with tolerance, but still land all-or-nothing in one `WriteCommandAction`, and report
  per-hunk match quality (exact / moved / fuzzy) in the result.

Cross-reference: OpenCode's `edit` faces the same literal-pair problem and answers it the same
way the platform does — a 9-strategy fuzzy `Replacer` pipeline (see the comparison table above).
We are the only surveyed implementation with **zero** tolerance between "byte-exact" and "fail".

## Recommendations

1. ~~Consider renaming `hunks[].path` → `hunks[].file_path` to match Claude's canonical naming.~~ **Applied in this commit.** Every hunk's file identifier is now `file_path`, matching Claude Code `Edit` exactly.
2. **Keep** `old_string` / `new_string`. Matches Claude Code CLI `Edit` verbatim.
3. **Keep** the JSON-native hunk array. Strictly easier to validate / log than V4A.
4. **Keep** `project_name`, `task_id`, `reason` — they're justified by MCP semantics.
5. **Do NOT add** `add`/`delete`/`move` ops to this tool — they belong to a separate tool (or to `steroid_execute_code` VFS APIs).
6. **Keep** the pre-flight-then-single-WriteCommandAction atomicity. Our guarantee is strictly stronger than every other tool surveyed.
7. **Adopt a tolerance ladder inside the pre-flight** (from the `GenericPatchApplier` comparison
   above): exact match first, then whitespace-normalized, then closest-match-with-diff in the
   error. Run-3 eval data (56/87 DSL calls failed, 52 on exact-match misses) shows the
   zero-tolerance posture is the tool's dominant failure mode. Do not adopt the platform's
   PARTIAL semantics — keep all-or-nothing, add tolerance only to hunk *resolution*.

## Sources

- DPAIA run NDJSON: `test-experiments/build/test-logs/test/run-20260423-080715-*/agent-claude-code-1-raw.ndjson` (+ 10 more runs) — observed Claude `Edit`, `Write` tool-use shapes.
- OpenAI Codex source: `~/Work/openai-codex/codex-rs/apply-patch/src/lib.rs:260-361`, `codex-rs/protocol/src/protocol.rs:3807-3821`, `codex-rs/app-server-protocol/src/protocol/v2.rs:5432-6076`, `codex-rs/tools/src/apply_patch_tool.rs:89-99`.
- OpenCode source: `~/Work/opencode-sst/packages/opencode/src/tool/edit.ts` (lines 35-45, 192-196, 673-710), `apply_patch.ts:41-252`.
- Pi Coding Agent: `https://mariozechner.at/posts/2025-11-30-pi-coding-agent/`.
- Anthropic Text-Editor tool: `https://platform.claude.com/docs/en/agents-and-tools/tool-use/text-editor-tool.md`.
- IntelliJ platform patch engine: `~/Work/intellij/community` — `platform/vcs-impl/src/com/intellij/openapi/diff/impl/patch/apply/GenericPatchApplier.java` (execute ladder :179-236, trySolveSomehow :363-393, weightContextMatch :160), `ApplyTextFilePatch.java:40-62` (merge fallback), `PlainSimplePatchApplier.java` (strict variant), `ApplyPatchStatus.java:16` (status model).
- Run-3 eval failure data: private `mcp-steroid-logs` repo, `2026-06-29/REVIEW.md` (2026-07-02 addendum).
