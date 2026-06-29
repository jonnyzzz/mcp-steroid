# :experiments-report

Renders the **test-experiments comparison dashboard** — agent session performance **with vs without
MCP Steroid**, per task, per agent — as a single self-contained `index.html`.

The same module powers a **local run** and the **CI report tab**: it only *processes already-collected
run data* and writes HTML. It does no downloading and talks to no services.

## Determinism & provenance — the report is computed, never authored by an agent

This is a hard guarantee, enforced by tests:

- **Every verdict, number, delta, and graph segment is computed in Kotlin** from the parsed run data
  (`Aggregator`, `HtmlRenderer`). No LLM/agent generates any part of the page.
- The module has **no network, no LLM client, no randomness**. The only impure call is the
  `generatedAt` timestamp, taken in `main` and passed into the otherwise-pure `buildReport`/`render`.
- `HtmlRendererTest` asserts the render is **byte-for-byte deterministic** for identical input, and the
  page itself discloses this in the footer.
- The **only free text** on the page is each agent's own run **`summary`**, shown *verbatim* and
  labelled `summary:` — it is input data, not dashboard-fabricated narrative.

If you change rendering, keep it a pure function of the input data.

## What it shows

- **Overview graph** — an inline SVG per-agent stacked bar of the verdict mix (helped / hurt / neutral /
  incomplete), widths computed from the run counts.
- **Per-agent tables** — each task side-by-side **with vs without MCP**: outcome + heuristic verdict,
  **agent execution time** (IDE preparation is measured separately and *excluded*), tokens, cost,
  **model**, **agent version**, **token budget**, and a **tool-call diff** (which tools each mode used).
- **Top problems** — failures mined across runs.
- **Coverage** — every collected build with its status, so a build that failed before producing data
  shows as a gap rather than silently disappearing.

### Verdict heuristic

`succeeded()` ranks the **objective sandbox outcome** (`buildSuccess` && no failed tests) above the
agent's own `claimedFix` (agents over-claim — observed on Petclinic27, where the without-MCP run claimed
a fix yet the build failed), falling back to `claimedFix` then the JUnit status. A pair is `MCP_HELPED`
when the with-MCP run succeeded and the without-MCP run did not, `MCP_HURT` for the reverse, `NEUTRAL`
when equal, `INCOMPLETE` when a mode is missing. It is a heuristic — the raw columns carry the nuance,
and a future RLM pass can investigate further.

## Data sources (parsers)

| Source | Parser | When |
|--------|--------|------|
| `[ARENA]` blocks in the build log | `ArenaLogParser` | baseline — works on current CI logs |
| agent NDJSON (`agent-*-raw.ndjson`) | `NdjsonParser` | model, agent version, token budget, tokens, cost, tool calls (Claude stream-json + Codex item formats) |
| `dpaia-arena-run-*.json` | `RunSummaryJsonParser` | clean per-run summary (local runs / published artifacts) |
| per-build `meta.json` | `InputReader` | coverage (status of every collected build) |

Sources are merged by `(scenario, agent, mode)` — first non-null wins per field.

## Run it

```bash
# from a local test-experiments run's output:
./gradlew :experiments-report:generateExperimentsReport \
  --args="--input test-experiments/build/test-logs/test --out /tmp/dashboard.html --title 'Experiments'"

# or the wrapper used by CI:
experiments-report/render-dashboard.sh <inputDir> <outHtml> [title]
```

No new dependencies beyond `kotlinx-serialization-json` (already in the build); HTML + the overview SVG
are emitted as plain strings, so the output is one self-contained file.

The **data download** (fetching CI builds' logs/artifacts) is intentionally NOT here — it lives in the
internal `mcp-steroid-teamcity` repo's `aggregator/fetch.sh`. This module only consumes a directory.
