# test-experiments — Agent Guide

**Experimental / long-running / less stable** Docker-based tests. Read this **in addition to** the
root `CLAUDE.md` and `test-integration/AGENTS.md` (the shared infrastructure lives there) when
changing files under `test-experiments/`.

The three repo-wide design tenets ([`docs/PHILOSOPHY.md`](../docs/PHILOSOPHY.md);
runtime mirror `mcp-steroid://skill/design-philosophy`) apply here too —
arena prompts and debugger demos are exactly the surface that "agents
deliver more" measurements run against, so prompt-quality wins land here.
New `steroid_*` tools and new `McpScriptContext` methods do not.

## What lives here

- `DpaiaArenaTest` and the DPAIA arena instance suite — agentic task scenarios.
- `DebuggerDemoTest`, `RiderDebuggerTest` — debugger-driven prompt tests.
- All long-running prompt-quality comparisons.

## What does NOT live here (despite the name)

The following tests are in `:test-integration` even though they look "experimental":

- `PluginBuildCompatibilityTest`, `PluginRuntimeCompatibilityTest`, `PluginVerificationTest`
  (multi-version compat suite).
- `RiderPlaygroundTest`, other playground tests.
- `FindDuplicatesPromptTest` and similar prompt smoke tests.

For multi-version compat, playgrounds, Rider/.NET test execution, and Docker-test CI gotchas, see
`test-integration/AGENTS.md`.

## Running

Always `:test-experiments:` prefixed and **one at a time** — each test spins up a full Docker IntelliJ
container. Two concurrent runs OOM-kill both. See root `CLAUDE.md` → "Test execution discipline" for
the 1-minute rule and stuck-test debugging.

```bash
./gradlew :test-experiments:test --tests '*DebuggerDemoTest.claude*'
./gradlew :test-experiments:test --tests '*DpaiaArenaTest*' -Darena.test.instanceId=dpaia__empty__maven__springboot3-3
./gradlew :test-experiments:test --tests '*RiderDebuggerTest*'
```

`:test-experiments:test` has an `onlyIf` guard — root `./gradlew test` silently skips it. Direct
invocation still works. Depends on `:test-integration` for the shared infrastructure (`IdeContainer`,
`ConsoleDriver`, `XcvbDriver`, `AiAgentDriver`, `ConsolePumpingContainerDriver`).

## devrig CLI normalization experiments

`DevrigCliCommandNormalizationTest` pins how raw Claude/Codex shell calls are recognized: global/trailing
`--json`, positional prompt URIs, inline code quoting, and supported Codex bash transports are accepted;
control syntax, expansion, appended commands, and unrelated wrappers are rejected. It is the narrow
normalization bucket and does not need a live agent.

`DevrigCliAgentUsabilityExperimentTest` launches real Claude and Codex sessions for four routes per agent:
task-first discovery, help → missing values → action, outcome-only discovery, and lifecycle help → every
safe action. Keep all eight cases explicit. The experiment must verify the raw shell/tool events, aliases,
focused help, generated-tool calls, positional `prompt`, and no-output recovery; prose summaries alone are
not evidence. The agent sessions intentionally receive no MCP registration, so every IDE action proves the
packaged CLI route rather than a direct MCP-tool shortcut. The long-lived `devrig mcp` action remains in
protocol integration tests; the usability experiment exercises only its help.

Run one method at a time, never alongside another Docker integration/experiment test:

```bash
./gradlew :test-experiments:test \
  --tests '*DevrigCliAgentUsabilityExperimentTest.codex follows help through missing values to an action*' \
  --rerun-tasks
```

The canonical expected behavior is [`docs/devrig-cli-contract.md`](../docs/devrig-cli-contract.md).

## Frontendless Remote Development Keycloak agent E2E

`DevrigRemoteDevelopmentKeycloakTypeHierarchyTest` is the task-only clean-machine proof for Claude and
Codex. Each method gets a fresh container, agent home, and `~/.mcp-steroid`; do not combine or parallelize
them:

```bash
./gradlew :test-experiments:test \
  --tests '*DevrigRemoteDevelopmentKeycloakTypeHierarchyTest.claude*' --rerun-tasks

./gradlew :test-experiments:test \
  --tests '*DevrigRemoteDevelopmentKeycloakTypeHierarchyTest.codex*' --rerun-tasks
```

The task prompt deliberately names only the Keycloak outcome. It must not reveal devrig, `steroid_*`, an
`mcp-steroid://` URI, `ClassInheritorsSearch`, or the Maven readiness recipe; contract coverage pins that
purity. The agent must discover the empty state, downloadable catalog, IU 2026.2 backend, on-demand project
open, external-system readiness, and deep hierarchy itself.

Readiness is frontendless: poll the requested path through `steroid_list_projects`, retain its opaque
`project_name`, then trigger/await Maven configuration. Do not require `steroid_list_windows`, screenshots,
or input; the native Remote Development backend normally has no window. The semantic result currently gates
at least 70 named implementing-class FQNs plus known indirect implementations; sub-interfaces and unnamed
implementations are classified separately. Exact full-oracle pinning remains in `TODO.md`.

### Local credentials and gateways

The agent drivers accept the normal vendor variables (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`) and the
corresponding `ANTHROPIC_BASE_URL` / `OPENAI_BASE_URL`. Loopback gateway URLs are rewritten for container
reachability by the shared test helper. Private helpers that obtain or rotate gateway credentials must stay
outside this public repository: invoke the helper around the Gradle command, never copy it into a Dockerfile,
test, or checked-in script, and never print the token value.

Credentials belong only to the external agent CLI process. The E2E must keep asserting that API keys/base
URLs do not enter the managed IntelliJ backend environment. Missing Claude/OpenAI credentials fail hard;
do not add a runtime skip or weaken the scenario.

### Evidence and iteration

- Raw tool evidence: `test-experiments/build/test-logs/test/run-*/agent-*-raw.ndjson`.
- Decoded transcript: sibling `agent-*-decoded.txt` (useful to read, never authoritative for tool calls).
- Agent reflection: `test-experiments/build/improvements/IMPROVEMENTS-headless-backend-<agent>.md`.
- Backend/launcher/IDE logs remain private artifacts until the `#jt` join-fragment sanitizer gap tracked by
  [#448](https://github.com/jonnyzzz/mcp-steroid/issues/448) is fixed; existing Bearer, `_ijt`, and `x-ijt`
  sanitization is not sufficient for publication.

Parse raw NDJSON to assert ordered download/open/list/execute calls and score the first successful semantic
query. A decoded transcript can echo fetched prompt text and create false positives. If a run crosses the
one-minute rule, collect the available process/thread/log evidence before stopping it; the absence of a
frontend screenshot is expected here. Read `docs/headless-agent-guidance.md` for the measured Claude/Codex
iterations and `docs/devrig-remote-development-backend-e2e.md` for the launcher contract.

## Remote-debugging (shared with :test-integration)

Because the infra is shared, every `:test-experiments` Docker IDE also starts with a JDWP agent on
`IDE_DEBUG_PORT` (5005), and devrig stdio-bridge runs add one on `DEVRIG_DEBUG_PORT` (5006) — both
Docker-mapped to host ports printed on the host (`Listening for transport dt_socket at address:
<host-port>` + `session-info.txt`). Attach IntelliJ's "Remote JVM Debug" to step through a debugger
demo or arena run live. **Both agents are `suspend=n` and MUST stay that way** — `suspend=y` would
block the JVM until a debugger attaches, hanging the whole test on CI. Full workflow + the attach
recipe (`mcp-steroid://debugger/debug-attach-remote-jvm`): `test-integration/AGENTS.md` →
"Remote-debugging the Dockerized IDE".

## Arena experiments (DPAIA)

Run AI agents in Docker against curated tasks; measure tool calls, tokens, runtime, success.

```bash
# Single scenario (~5 min)
./gradlew :test-experiments:test --tests '*DpaiaPetclinicRest37Test.claude with mcp' --rerun-tasks

# Full 3-pass run
SKIP_IMPROVE=1 MAX_RUNS=1 bash ../docs/dpaia-arena-runner.sh 0
```

Working notes, comparison tables, and autoresearch loop prompts live in `../docs/CLAUDE.md` and
`../docs/autoresearch/`.

### Dataset patches are repaired at parse time — triage "corrupt patch" there, not in GitDriver

The dpaia.dev dataset serialization **strips trailing whitespace from its unified diffs**, which
damages them two ways: a blank context line inside a hunk loses its mandatory leading space, and
pure-trailing context lines vanish, leaving the `@@` header promising more lines than the body
carries (11 of 304 patches in the live dataset; Petclinic36 + JhipsterApp3 failed EVERY run for
weeks as `git apply: error: corrupt patch at line N` — issue #447, fixed 2026-08-05).
`repairTrimmedUnifiedDiff` (`DpaiaDataset.kt`, pinned by `DpaiaPatchRepairTest`) fixes both shapes
at parse time, **header-driven**: the declared hunk counts decide what is hunk content, so a bare
empty line BETWEEN file sections is never absorbed as phantom context. Do NOT reach for
`git apply --recount` instead — it does exactly that absorption and matches phantom context past
EOF. Asymmetric damage (a lost `+`/`-` line) fails loudly: the repair never guesses content. The
upstream exporter fix is tracked in TODO.md (`dpaia/ee-dataset` is read-only from here).

### The #251 mandatory-first-call guard: schema rejections don't count

`firstExecutionTargetsProject` (ArenaOutputParsing.kt) requires the first EXECUTED
`steroid_execute_code` result to print the `base:` marker for the arena project. Leading
**parameter-validation rejections** ("ERROR: Parameter task_id … is required" — codex omits required
args on its first call now and then, TC build 1022424067) are skipped: the schema layer refused the
call before any project was resolved, so the result carries zero targeting information. A runtime
error or a wrong/missing marker still invalidates the run — do not widen the skip beyond the
`Parameter ` prefix.

### Validating an arena scenario on demand

Scheduled runs are sparse; after touching arena infra, trigger the affected configs directly and
watch (each run is a real Codex/Claude session, ~20–40 min):

```bash
jb tc native run start mcp_steroid_IntegrationTests_DpaiaArena_Petclinic36_Codex   # queues on main
jb tc native run log -f <run-id>
```

## IMPROVEMENTS.md harness — agent self-feedback for prompt tuning

Pattern used by `FindDuplicatesPromptTest` (issue #33; lives in `:test-integration`). Reusable in any
agent-driven prompt test on either side of the split. Goal: capture the agent's own reflection on what
was hard / unclear / missing during the run, in a form a maintainer can diff and turn into prompt tweaks.

**Two tasks per run, one prompt.** The prompt asks the agent to (1) do the real work and (2) reflect on
how it went. Reflection is bracketed by `<<<IMPROVEMENTS>>>` ... `<<<END_IMPROVEMENTS>>>` markers so the
test can extract it without parsing the rest of stdout.

**Snapshot per agent.** Each `@Test` method (one per agent — `claude`, `codex`, `gemini`) runs against a
shared companion-object IDE container and writes its block to
`test-integration/build/improvements/IMPROVEMENTS-<test>-<agent>.md`. JUnit serializes `@Test` methods
within a class, so the three agents run sequentially against one container — satisfying the "one Docker
IDE at a time" rule without paying the IDE startup cost three times.

**Hard constraint stated in the prompt.** Agents are told: *"your suggestions must be about prompts only —
skill articles, tool descriptions, system-prompt text. We cannot add MCP tools or API methods as a fix
path."* This makes the feedback actionable as `mcp-steroid://...` edits.

**Iteration cadence.** After a run, read every produced `IMPROVEMENTS-*.md`, pick the prompt-only tweaks
that match the constraint, apply them, re-run. Different agents flag different things — Claude tends to
highlight discovery and threading issues; Codex tends to highlight ambiguity in step ordering and
output-format expectations.

The harness is currently wired into `FindDuplicatesPromptTest`. Extending to the rest of the
test-integration prompt suite (`ReferencesSearchPromptTest`, `FilenameIndexPromptTest`,
`PsiClassLookupPromptTest`, `MavenRunnerAdoptionTest`, …) is tracked separately so the pattern can
stabilize on one test first.
