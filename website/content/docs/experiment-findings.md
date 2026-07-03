---
title: "Experiment Findings: IDE vs Shell Agents"
description: "Measured A/B results — the same coding tasks with and without MCP Steroid, on real codebases, with evidence-based scoring"
---

We continuously run A/B experiments where the **same agent gets the same task twice**: once with
MCP Steroid (full IDE access) and once as a plain shell agent (grep, sed, javac — anything it
wants). Same model, same repository, same prompt intent. Every claim below is backed by a test
class in this repository and a CI run you can re-execute.

**Models:** Claude Opus 4.8 and GPT 5.5 (Codex CLI). **Targets:** [Keycloak](https://github.com/keycloak/keycloak)
pinned to tag `26.6.4` (~213k files indexed) and [YouTrackDB](https://github.com/JetBrains/youtrackdb)
pinned to a fixed revision.

## Methodology: no self-reported wins

Early runs taught us to trust nothing an agent says about itself:

- One baseline run **claimed "17 issues found" in 17 seconds with zero tool calls** — pure
  hallucination, and the old scorer accepted it (CI run `991971408`). Every scorer is now
  **evidence-based**: answers must carry machine-checkable markers (`ISSUE: <file>:<line>`,
  `SUBTYPE: <fqn>`, …) matched against ground truth derived mechanically (e.g. `javac -Xlint:cast`
  for inspections). Scorers live in
  [`SemanticTaskScoring.kt`](https://github.com/jonnyzzz/mcp-steroid/blob/main/test-integration/src/main/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/SemanticTaskScoring.kt),
  each with TDD unit tests.
- A leg whose agent **tooling crashed** (API rate-limit death mid-run) is excluded from the
  verdict — neither side gets a win against a run that never had a fair chance. Timeouts count
  honestly as "did not finish within budget".
- Completeness/correctness is scored identically for both legs; the dashboard shows the verdict
  plus raw duration, cost, tokens, and per-tool call counts for every run.

## Findings

### Structural search: 10× faster at the same completeness

Find every no-arg `Optional.get()` call across YouTrackDB's multi-module reactor — including
grep-hostile chains like `findFirst().get()` and `reduce(Integer::sum).get()`, with
`Future`/`Atomic`/`ThreadLocal` `.get()` bait everywhere.
([`StructuralSearchYoutrackdbTest`](https://github.com/jonnyzzz/mcp-steroid/blob/main/test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/StructuralSearchYoutrackdbTest.kt))

| Claude Opus 4.8 | with MCP (SSR) | shell agent (grep) |
|---|---|---|
| completeness | 12/13 files, 31/36 sites | 12/13 files, 35/36 sites |
| **time** | **1m 48s** | 18m 13s |
| **cost** | **$0.75** | $3.32 |

The IDE's AST-exact `Matcher` answers in one query what took the shell agent 42 turns of grepping
and manual type-classification. *(runs `993038190`)*

### Inspections: more complete, 2.6× faster

Which of 8 candidate Keycloak files contain a **genuinely redundant cast**? Ground truth comes from
`javac -Xlint:cast`: exactly 4 real ones; the other 4 files are cast-heavy decoys where classic-Java
`instanceof` does *not* narrow, so the casts are required — a semantic distinction text search cannot
make. ([`KeycloakInspectionsTest`](https://github.com/jonnyzzz/mcp-steroid/blob/main/test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/KeycloakInspectionsTest.kt))

| Claude Opus 4.8 | with MCP (RedundantCast inspection) | shell agent |
|---|---|---|
| true positives | **4/4** | 3/4 |
| time / cost | **1m 01s / $0.32** | 2m 40s / $0.81 |

*(runs `993036643`; GPT 5.5 reached parity on this task — `993037268`)*

### Safe rename: 5.6× faster, and the manual path is fragile

Rename a widely-used method across Keycloak, verified by a clean build afterwards.
([`KeycloakRenameTest`](https://github.com/jonnyzzz/mcp-steroid/blob/main/test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/KeycloakRenameTest.kt))

| Claude Opus 4.8 | with MCP (`RenameProcessor`) | shell agent (manual sweep) |
|---|---|---|
| outcome | ✔ verified rename | ✔ eventually |
| time | **5m 26s** | 30m 21s |
| cost | **$0.88** | $13.20 |

In a repeat run the manual sweep **died mid-way after 18 minutes** with nothing usable, while the
PSI rename completed in 7m 39s with a green build — the one-shot atomic refactoring is not just
faster, it removes a long window of half-renamed state. *(runs `991971402`, `992152358`)*

### Type hierarchy & find-usages: exact answers, fraction of the time

Enumerate every transitive implementor of `org.keycloak.authentication.Authenticator` — including
leaves that only implement it through abstract bases, which `grep "implements Authenticator"`
structurally misses.
([`KeycloakTypeHierarchyTest`](https://github.com/jonnyzzz/mcp-steroid/blob/main/test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/KeycloakTypeHierarchyTest.kt),
[`KeycloakFindUsagesTest`](https://github.com/jonnyzzz/mcp-steroid/blob/main/test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/KeycloakFindUsagesTest.kt))

- Type hierarchy, Claude: complete both ways — **1m 21s with MCP vs 4m 54s without** (3.6×).
  GPT 5.5: 48s vs 1m 39s. *(runs `991971394`, `991971396`)*
- Find-usages, Claude: **48s vs 1m 09s**, 5 turns vs 9. *(runs `991971398`, `991971400`)*

### Change signature: 42 overrides, zero missed, build green

Add a parameter to `Authenticator#authenticate` and ripple it through every override and call
site — including a `default` method in a sub-interface, an anonymous class, and javadoc mentions
that must *not* change.
([`KeycloakChangeSignatureTest`](https://github.com/jonnyzzz/mcp-steroid/blob/main/test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/KeycloakChangeSignatureTest.kt))

With MCP, `ChangeSignatureProcessor` updated **42 overrides with zero misses and a green build**
in 4m 57s. Honest result: Claude also completed it manually in 4m 19s — frontier models *can* do
mechanical sweeps; the IDE's value here is the atomic, verified-by-construction result rather than
raw speed. *(runs `993033897`, `993033899`)*

### Call-hierarchy reachability: finds what grep structurally cannot

Which REST endpoints can transitively reach `BruteForceProtector#failedLogin`? The interesting
paths cross a DI lookup (`session.getProvider(...)`) and an interface callback — links that do not
exist as text. ([`KeycloakCallHierarchyTest`](https://github.com/jonnyzzz/mcp-steroid/blob/main/test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/KeycloakCallHierarchyTest.kt))

Claude with MCP found **39 endpoints vs 27 without**, at lower cost ($2.59 vs $3.71) though more
wall-clock. *(runs `993035361`, `993035363`)*

## Honest negatives (we publish these too)

- **GPT 5.5 + SSR**: the Codex MCP leg gave up after 1m 25s without an exact match while its grep
  baseline earned an exact 13/13 in 7m 32s — the SSR skill flow for Codex needs work. *(run `993038200`)*
- **GPT 5.5 + call hierarchy**: the Codex MCP leg hit its 30-minute budget vs a 2m 53s baseline —
  driving the IDE call-hierarchy APIs is currently harder for GPT 5.5 than shell exploration. *(run `993035363`)*

## The pattern

Frontier agents usually get *close* on correctness either way. What the IDE changes is the
**economics and the guarantees**: 10× faster structural queries, 2.5–5× cheaper semantic tasks,
atomic refactorings with a green build instead of minutes of half-edited state — and exact,
resolve-based answers where text search is structurally blind (transitive hierarchies, DI edges,
type-dependent findings).

All experiments run continuously on CI; every number above is parsed from the runs' logs by the
[experiments-report](https://github.com/jonnyzzz/mcp-steroid/tree/main/experiments-report) pipeline —
the same code that renders our internal dashboard. To reproduce locally, see the test classes linked
above; run IDs are TeamCity build IDs recorded for provenance.
