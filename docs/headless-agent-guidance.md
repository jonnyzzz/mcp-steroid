# Headless IDE agent guidance

## Goal

Make Claude Code and Codex autonomously discover, provision, open, and use an IntelliJ IDEA Ultimate
2026.2 Remote Development backend through devrig on a clean machine. The agent should solve a semantic
Keycloak type-hierarchy task with IntelliJ APIs without being handed devrig commands, MCP tool names, or
the hierarchy recipe.

This work changes prompts, descriptions, and behavioral tests. It does not add MCP tools or
`McpScriptContext` helpers.

## Success contract

From a fresh container with devrig registered but no IDE installed or running, each agent must:

1. Discover the `steroid_*` IDE capability from MCP initialization guidance.
2. Discover and download IDEA Ultimate 2026.2.0.1.
3. Open `/home/agent/keycloak`; `steroid_open_project` may auto-start the installed backend, so an
   explicit `devrig backend start` is not required.
4. Wait for both IDE reachability and Maven project-model readiness.
5. Use a deep IntelliJ `ClassInheritorsSearch` with the returned opaque `project_name`.
6. Return the complete `Authenticator` hierarchy and a prompt-only improvement reflection.
7. Leave evidence that the live IDE is the trusted, unattended IU-262 Remote Development backend with
   MCP Steroid installed and no agent credentials in its process environment.

## Evidence from the merged scenario

The merged E2E proves the Remote Development launcher, plugin installation, process marker, trusted
environment, project route, and semantic hierarchy query. It does not prove autonomous discovery: its
prompt currently supplies the exact download/start commands, every MCP step, and the hierarchy recipe.

Three independent audits found the same agent-facing gaps:

- The baseline `devrig backend` said only `No backends detected.` on a clean machine although initialization
  guidance claimed it listed available products. Bare `devrig backend download --json` is the real discovery path.
- Baseline initialization promoted explicit `backend start`, while `steroid_open_project` already starts the sole
  installed managed backend and waits for its MCP marker.
- Only IDEA Ultimate baseline 262 uses the unattended Remote Development launcher. The previous pre-IDE
  guidance did not explain that choice or that no frontend window was required.
- Window/index flags can become ready before a first Maven import is configured. A hierarchy query can
  therefore return a plausible but incomplete result unless the agent awaits external-system readiness.
- The backend-management article cannot bootstrap a zero-project session because
  `steroid_fetch_resource` requires a `project_name`; the essential clean-machine route must be in MCP
  initialization and tool/CLI descriptions.

## Working plan

- [x] Create a fresh worktree and branch from `origin/main`.
- [x] Audit the merged Remote Development E2E and every pre-IDE agent-visible surface.
- [x] Obtain independent prompt, experiment, execute-code, and backend-lifecycle reviews.
- [x] Replace scripted E2E instructions with a task-only Claude/Codex discovery prompt and raw-event
  workflow assertions.
- [x] Add red unit contracts for clean-machine discovery, auto-start, frontendless operation, and the two
  readiness phases.
- [x] Rework initialization, open-project descriptions, prompt entry points, backend article, and
  hierarchy guidance consistently.
- [x] Run focused unit, prompt-generation, Kotlin-fence, and experimental harness contracts.
- [x] Run two Claude scenarios in fresh containers, review their `<<<IMPROVEMENTS>>>` artifacts, and
  iterate on the observed frontendless-readiness and hierarchy-classification gaps.
- [x] Run the Codex scenario in a fresh container through the local jb-central OpenAI route and review its
  raw NDJSON plus `<<<IMPROVEMENTS>>>` artifact. The credential entered only the Codex CLI process; the
  backend-environment assertion proved it did not reach IntelliJ.
- [x] Run the final adversarial artifact review and close its runtime gate. The reviewer returned GO after
  independently checking discovery, ordered tool calls, semantic output, backend invariants, and teardown.
- [x] Reconcile the branch with current `origin/main` and preserve the new generated-CLI contracts while
  resolving conflicts.
- [x] Rerun the focused post-rebase server, devrig, prompt/KtBlock, and pure experiment validation.
- [x] Push the final rebased prompt iteration, update PR #441, and obtain green current-head checks.

## Validation status

- Passed focused devrig initialization, empty-backend output, zero-candidate recovery, and open-project
  schema contracts.
- Passed markdown/payload round-trip, per-IDE availability, Maven/Gradle first-open import, and the changed
  type-hierarchy Kotlin block against IDEA stable and IDEA 2026.2 EAP.
- Passed `:test-experiments:compileTestKotlin`, workflow helper tests, the task-prompt purity method, and
  the implementing-class scoring/final-marker rejection regressions.
- Passed the live Claude task-only Docker scenario in 7m53s. Claude autonomously discovered the empty
  backend state, listed downloads, installed IU 2026.2.0.1, relied on `steroid_open_project` auto-start,
  routed the project without a frontend, triggered and awaited Maven import for 136 modules, then found
  72 named inheritors and correctly reported 70 named implementing classes after excluding two
  sub-interfaces.
  The harness also proved the IU-262 Remote Development process, managed MCP Steroid plugin, marker,
  trusted/noninteractive environment, credential isolation, live Keycloak route, and clean shutdown.
- Passed the post-iteration Claude scenario in 5m16s. The agent made no window, screenshot, or input calls;
  fetched only the Maven readiness recipe; imported all 136 modules; and completed the hierarchy in one
  successful semantic query. The raw PSI search contained 74 inheritors: 70 named implementing classes,
  two named sub-interfaces, and two anonymous/local implementations without qualified names. Its final
  answer contained the intended 70 named classes, and every backend/process assertion and teardown passed.
- Passed the Codex task-only Docker scenario in 5m26s (6m15s for the full Gradle invocation with rebuild).
  MCP initialization completed before the first tool call. Codex started with an empty project list,
  discovered `devrig backend download --json`, installed the exact IU 2026.2.0.1 build, called
  `steroid_open_project` with no explicit backend start, retained `keycloak-jybgaanr`, triggered and awaited
  Maven configuration, fetched the hierarchy recipe, and completed every IDE call without error.
  Its deep PSI search found 74 raw inheritors: 70 named implementing classes, two named sub-interfaces, and
  two anonymous implementations. An independent `FilenameIndex`/`InheritanceUtil` scan matched the 70-class
  set exactly, and the final answer emitted 70 unique class markers. Backend mode, managed plugin, trust and
  noninteractive flags, API-credential isolation, the current Bearer/`_ijt` marker-credential invariant,
  route liveness, stop, and process cleanup all passed. Artifact review found an expired Remote Development
  `#jt` join fragment outside that sanitizer's coverage; `TODO.md` now tracks making preserved backend logs
  generally publication-safe.
- Independent reviews completed with the repository subagent quorum, `run-agent.sh` Codex, Claude Opus 5,
  and Claude Fable 5. Their findings were iterated into the current wording and assertions.
- The final adversarial artifact review returned GO for the full Claude-and-Codex goal. It kept the separate
  MCP-initialization harness gate and exact pinned hierarchy oracle open because this successful run does not
  implement either deferred regression.
- The post-rebase matrix passed the focused server and devrig contracts, 16 prompt tests (including both
  type-hierarchy KtBlock cases), and eight pure experiment methods with zero failures, errors, or skips.
- After the final SDK-guidance iteration, `ExternalSystemFirstOpenPromptContractTest`, the corpus-wide
  `MarkdownArticleContractTest`, and all ten `ExecuteCodeMavenKtBlocksCompilationTest` cases passed. The
  edited Kotlin contract test is also clean under every enabled file-scoped inspection with no failed tools.
- The final read-only review returned GO after verifying the conditional null-SDK behavior, module-aware
  selection, canonical home validation, post-write read action, contract coverage, and KtBlock results.
- PR #441 is mergeable; compile, Linux PowerShell Docker, and Windows PowerShell checks passed after the
  final rebase and prompt iteration.
- File-scoped IDE inspections ran on every changed production Kotlin file. They found no actionable
  changed-line diagnostic, but the `OpenProjectTool.kt` check is not called clean: two Kotlin/UAST
  inspections crashed on `KtFakeSourceElementKind.PluginGenerated`, so `failedTools` correctly made it
  `check_failed`. The reproducible inspection-engine follow-up is recorded in `TODO.md`.

## Claude iteration findings

The first live task-only run validated the intended discovery path but found one remaining runtime
contradiction: the result returned by `steroid_open_project` still made `steroid_list_windows` and a
screenshot mandatory, even though a frontendless Remote Development backend has neither. The result
guidance now leads with path polling through `steroid_list_projects`, treats windows as an optional
attended-frontend signal, and names Maven/Gradle import as a separate readiness phase.

Claude also correctly noticed that `ClassInheritorsSearch` returns sub-interfaces, abstract classes,
concrete classes, and anonymous/local classes together. The hierarchy article now teaches that
`checkDeep=true` crosses interface → sub-interface → class edges, classifies the bounded named result,
reports unnamed counts separately, and keeps abstract classes unless the task requests concrete-only
implementations. The fetch-resource routing description uses the exhaustive wording “every direct +
transitive subtype / implementor.” The E2E score now removes the base/sub-interface types from its class
count and rejects the base interface or either known sub-interface in final `SUBTYPE:` markers.

The second reflection also suggested documenting stdout/stderr separation for `devrig backend download
--json`. That suggestion was not adopted: the transcript shows the agent explicitly merged the streams
with `2>&1`, then correctly parsed stdout on its next call. Recommending diagnostic suppression would hide
useful failures without fixing a product-guidance gap.

## Codex iteration findings

Codex's first action was `steroid_list_projects`; it did not need the task prompt to name devrig, an MCP
tool, or a resource URI. Its reflection nevertheless suggested an explicit `steroid_*` discovery hint and a
short first-open checklist. Both already exist in initialization and `steroid_open_project` output, and the
raw trace shows Codex followed them, so duplicating them again would add prompt weight without changing the
observed path.

The hierarchy reflection similarly asked for unnamed-result guidance that the article already supplies.
Codex used it correctly: two sub-interfaces and two anonymous implementations stayed outside the 70 FQN
class markers and were reported separately. No further hierarchy wording changed.

One headless-specific ambiguity was real: Maven configuration completed while
`ProjectRootManager.getInstance(project).projectSdk` was null. The existing repair section was routed only
from a visible yellow IDE banner, which a frontendless backend cannot show. The Maven first-open entry point
now calls out the same programmatic SDK check but treats null as a warning, not an automatic failure. It keeps
valid project-local PSI work intact and routes to SDK repair only when the task needs JDK-dependent capability
or diagnostics show it is absent, after inspecting module SDKs instead of blindly choosing a registered JDK.
The executable repair recipe canonicalizes and validates an explicit home through IntelliJ APIs, refuses
ambiguous candidates, and performs every project/module SDK model read under a read action.

## Experiment discipline

The Claude and Codex cases each use a new container; backend state is never shared. Assertions use raw
NDJSON tool events, not decoded prose. A run must prove the ordered download/open/list/execute path and
score the first successful deep hierarchy result as complete. Explicit CLI start, initial empty project
lists, article fetches, and recoverable failed opens are recorded as quality signals rather than required
steps.

The semantic score is a lower-bound regression oracle for the pinned Keycloak commit: at least 70 distinct
named implementing-class FQNs plus known indirect/cross-module implementors. The base interface and two
known sub-interfaces do not contribute to that count. It detects the observed stale-import result and
shallow searches, but it does not pin the entire upstream class set. The agent task still requires the full,
untruncated named hierarchy; this E2E primarily gates autonomous backend discovery and IDE use.

Docker agent runs are sequential. Local OpenAI access may come from direct credentials or the jb-central
proxy wrapper used for this run; either way, credentials are injected only into the agent CLI process. If
credentials are unavailable, the tests continue to fail hard as designed; no runtime skip or weakened
assertion is permitted.
