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

- `devrig backend` says only `No backends detected.` on a clean machine although initialization guidance
  claims it lists available products. Bare `devrig backend download --json` is the real discovery path.
- Initialization promotes explicit `backend start`, while `steroid_open_project` already starts the sole
  installed managed backend and waits for its MCP marker.
- Only IDEA Ultimate baseline 262 uses the unattended Remote Development launcher. The current pre-IDE
  guidance does not explain that choice or that no frontend window is required.
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
- [ ] Run Claude and Codex scenarios sequentially in fresh containers and review their
  `<<<IMPROVEMENTS>>>` artifacts. Locally blocked: Docker is available, but all Anthropic/OpenAI agent
  API-key environment variables are unset; the tests remain enabled and unweakened.
- [x] Run final quorum review.
- [ ] Commit atomically, push the branch, and open a PR.

## Validation status

- Passed focused devrig initialization, empty-backend output, zero-candidate recovery, and open-project
  schema contracts.
- Passed markdown/payload round-trip, per-IDE availability, Maven/Gradle first-open import, and the changed
  type-hierarchy Kotlin block against IDEA stable and IDEA 2026.2 EAP.
- Passed `:test-experiments:compileTestKotlin`, workflow helper tests, and the task-prompt purity method.
- Independent reviews completed with the repository subagent quorum, `run-agent.sh` Codex, Claude Opus 5,
  and Claude Fable 5. Their findings were iterated into the current wording and assertions.

## Experiment discipline

The Claude and Codex cases each use a new container; backend state is never shared. Assertions use raw
NDJSON tool events, not decoded prose. A run must prove the ordered download/open/list/execute path and
score the first successful deep hierarchy result as complete. Explicit CLI start, initial empty project
lists, article fetches, and recoverable failed opens are recorded as quality signals rather than required
steps.

The semantic score is a lower-bound regression oracle for the pinned Keycloak commit: at least 70 distinct
reported FQNs plus known indirect/cross-module implementors. It detects the observed stale-import result and
shallow searches, but it does not pin the entire upstream class set. The agent task still requires the full,
untruncated hierarchy; this E2E primarily gates autonomous backend discovery and IDE use.

Docker agent runs are sequential. If local API credentials are unavailable, the tests continue to fail
hard as designed; no runtime skip or weakened assertion is permitted.
