# devrig Remote Development backend E2E

Status: implementation complete; final review and PR in progress
Owner branch: `feat/devrig-remote-backend-e2e`
Worktree: `/Users/jonnyzzz/Work/mcp-steroid-wt-devrig-remote-backend`
Started: 2026-07-31

## Goal

Prove the complete first-use devrig story on a clean Docker machine for both Claude Code and Codex:

1. A prepared Keycloak checkout and JDK are present, with dependency/build caches warm enough that the
   scenario measures IDE/agent behavior rather than public repository availability.
2. Only locally built devrig installation inputs are preloaded. The simulated user installs devrig into
   a fresh home and registers it with the selected agent through the real CLI.
3. The user asks the agent for the existing Keycloak `Authenticator` type-hierarchy result. The task is
   deliberately expensive to solve reliably without a Java IDE and its indices.
4. The agent invokes devrig. devrig downloads IntelliJ IDEA Ultimate 2026.2 (build line 262), installs
   the bundled MCP Steroid plugin, and starts the IDE as a Remote Development backend.
5. The backend opens Keycloak, finishes project import/indexing, and exposes the normal MCP Steroid
   project and `steroid_execute_code` surfaces without any Remote Development frontend.
6. The agent returns a hierarchy result that passes the existing semantic scorer.

The product behavior belongs in devrig. The E2E belongs in `:test-experiments` because it downloads and
starts a full IDE, launches a real external agent, and is intentionally long-running.

## Acceptance criteria

The work is complete only when all of these are demonstrated:

- Two explicit tests exist: Claude Code and Codex. They run sequentially, never concurrently.
- Each test starts with an ephemeral agent home and ephemeral `~/.mcp-steroid` state.
- The agent registration is performed with the installed devrig CLI; the test does not hand-write an MCP
  registration that bypasses the user flow.
- The IDE archive may come from a host-mounted download cache, but the managed backend install, config,
  system, log, plugin, marker, and agent-registration state begin empty.
- IntelliJ IDEA Ultimate is pinned to `2026.2.0.1` (`IU-262.8665.337`). An unavailable exact build fails clearly; it
  must not silently fall back to 261 or a different product.
- devrig launches the native Remote Development entry point:
  `bin/remote-dev-server run` on Linux/macOS or `bin/remote-dev-server.exe run` on Windows.
- The launch does not pass a project path. devrig waits for MCP discovery and then routes the existing
  `steroid_open_project` call, avoiding the marker-before-project-open race. Because this is a
  devrig-managed backend opening a path explicitly requested by the user, the process sets
  `REMOTE_DEV_NON_INTERACTIVE=1` and `REMOTE_DEV_TRUST_PROJECTS=1`.
- The discovered marker identifies the managed 262 Ultimate installation and the managed MCP Steroid
  plugin directory.
- `idea.log` identifies `IdeProductMode.REMOTE_DEV_SERVER` / the MCP Steroid run mode as remote
  development backend.
- `steroid_open_project`, `steroid_list_projects`, and `steroid_execute_code` work against Keycloak
  without a frontend client.
- The reused hierarchy scorer finds at least 40 inheritors of
  `org.keycloak.authentication.Authenticator`, including:
  `UsernamePasswordForm`, `OTPFormAuthenticator`, and `IdpConfirmLinkAuthenticator`.
- All unit/contract tests and both Docker cases pass from the dedicated worktree, with no ignored warning
  or unexplained error in the relevant IDE/devrig logs.
- A three-reviewer quorum, including Claude `claude-fable-5` and `claude-opus-5`, reports no blockers
  after the final iteration.

## Confirmed IntelliJ 262 launch contract

Research against `/Users/jonnyzzz/Work/intellij` established:

- `bin/remote-dev-server.sh run [project]` is the wrapper entry point, but it is not appropriate for
  devrig PID ownership because it can restart and retain a parent wrapper process.
- The native entry point is `bin/remote-dev-server run [project]` on Unix and
  `bin/remote-dev-server.exe run [project]` on Windows.
- `run` maps to the `remoteDevHost` starter. Starting without a project is supported.
- The native 262 launcher reads the existing adjacent `<IDE_HOME>.vmoptions` file used by devrig.
  Therefore the managed config/system/log/plugin/storage locations remain effective.
- MCP Steroid already treats the process as a Remote Development backend. The unattended starter calls
  `appFrameCreated`, so the lifecycle listener can start Ktor and publish the PID marker before a project
  is open.
- The marker can appear before a launcher-supplied project finishes opening. The chosen launch passes no
  project and retains the existing explicit `steroid_open_project` route.
- Direct native launch is preferred for the first implementation because devrig's pid file, stop path,
  and marker validation all assume the spawned PID is the IDE PID. The shell wrapper would make the
  tracked PID a parent wrapper and needs separate process-tree work.

Primary source anchors:

- `build/resources/linux/scripts/remote-dev-server.sh`
- `community/native/XPlatLauncher/src/remote_dev.rs`
- `community/native/XPlatLauncher/src/default.rs`
- `community/platform/core-api/src/com/intellij/idea/WellKnownCommand.java`
- `remote-dev/cwm-host-unattended/.../UnattendedHostStarter.kt`
- `remote-dev/remote-dev-worker/src/utils/ide_{linux,darwin,windows}.go`
- `remote-dev/remote-dev-server-plugin/.../launcher.sh`

## Implemented launch behavior

`BackendManager.startLocked` now resolves a `ManagedBackendLaunchSpec` containing the executable,
ordered arguments, working directory, and launch environment. For Remote Development launches it blocks
until a matching live MCP Steroid marker appears, then writes the marker/backend PID to
`state/<backend-id>.pid`. This explicitly supports the native launcher handing work to a different PID;
the short-lived launcher PID is never published as a ready backend.

Remote Development selection is intentionally narrow:

| Product/build | Launcher | Arguments | Extra environment |
|---|---|---|---|
| `idea-ultimate`, baseline `262` | native `bin/remote-dev-server[.exe]` | `run` | `REMOTE_DEV_JDK_DETECTION=false`, `REMOTE_DEV_NON_INTERACTIVE=1`, `REMOTE_DEV_TRUST_PROJECTS=1` |
| IDEA Ultimate 261/263 | descriptor's standard launcher | none | none |
| IDEA Community, every build | descriptor's standard launcher | none | none |
| Every other product | descriptor's standard launcher | none | none |

For IU 262, a missing native launcher or `plugins/remote-dev-server` is a validation failure. There is
no silent fallback to the standard launcher. Other products/builds are not required to ship those assets.

`writeBackendVmOptions` places `<bundle-dir-name>.vmoptions` next to the managed IDE bundle and
sets isolated config, system, log, plugin, and MCP execution-storage paths. The native launcher uses
that same adjacent user-VM-options location on every supported OS; no Remote-Development-specific
mirror is needed. `deployMcpSteroidPlugin` installs the current bundled plugin below the managed
cache's `plugins/mcp-steroid` directory before every cold start.

The 262 native launcher normally enables `jdk.configure.existing` for Remote Development. On an empty
profile, `ExistingJdkConfigurationActivity` calls `AddJdkService.createIncompleteJdk` from a background
write action; `SdkConfigurationUtil.createSdk` then uses `invokeAndWait` and the platform logs a deadlock
SEVERE. devrig sets the launcher's supported `REMOTE_DEV_JDK_DETECTION=false` opt-out. The clean-machine
fixture still contains JDK 21 and uses it for the verified online/offline Maven builds; only the broken
workstation-style automatic SDK registration is disabled in the unattended backend.

The exact version `2026.2.0.1` is a released stable build and resolves to `IU-262.8665.337`; no EAP
channel or moving alias is involved.

## Implementation design

### Launch description

Represent a managed backend launch as an executable, ordered arguments, working directory, and
environment. The installed-product resolver validates Remote Development assets only for IU baseline
262, then selects the native launcher with the single `run` argument and both non-interactive trust flags.

The spawn layer must accept arguments on Unix and Windows. Windows WMI command-line construction must
quote each argument without changing the existing detached-lifetime behavior. Process discovery and
stop validation must continue to recognize both the executable and its managed-bundle arguments.

No shell wrapper is used in this scope. musl support, if required later, needs explicit wrapper child-PID
tracking and is not silently emulated here.

### Download selection

The E2E requests `devrig backend download idea-ultimate --version 2026.2.0.1`, so resolution cannot
select 2026.1 or a different product. The descriptor must report product code `IU` and build baseline 262.

### Project opening

Managed backend startup remains project-agnostic:

1. Start `remote-dev-server run`.
2. Wait for a live MCP Steroid marker matching the exact managed IDE home, build, plugin id, and devrig
   bridge endpoint. Persist and return that marker PID, which may differ from the launcher PID.
3. Route `steroid_open_project`; the managed backend already trusts user-requested project paths through
   `REMOTE_DEV_TRUST_PROJECTS=1`.
4. Poll the project/window readiness surface and then run the semantic task.

This keeps the existing agent-facing contract and avoids duplicate project-open requests.

### Docker fixture

The scenario uses Keycloak 26.6.4 at commit `dc1bfc54bf1462f7e79822adb4c59aba7e25d50f`
and its existing hierarchy task/scorer. Each ephemeral container clones the pinned revision from the
host bare-repository cache and uses JDK 21. Maven/Gradle dependency caches are mounted separately from
the otherwise-clean home. The fixture proves an online and then offline warm build with:

`./mvnw -q -DskipTests -pl server-spi-private,services -am compile`

Keycloak 26.6.4 pins `maven-build-cache-extension:1.2.0`, whose `CacheConfigImpl` eagerly injects
`MavenSession`. IDEA's Maven embedder enumerates lifecycle participants before a session is seeded, so
that version logs a Guice `ProvisionException`. The fixture upgrades only that extension to `1.2.1`
before warming caches. Version 1.2.1 changes the constructor to `Provider<MavenSession>` and defers
session access until initialization; caching remains enabled and both online and offline builds remain
mandatory.

The container receives local devrig installation artifacts and a persistent IDE archive download cache,
but the user home and devrig managed state are new for every test method.

Claude and Codex use the repository's real agent-session drivers and real API credentials. The prompt
states the user task and required output markers, but does not hand the agent the hierarchy answer or a
filesystem-grep recipe. Raw NDJSON is retained so the test can prove the agent called devrig/MCP rather
than merely mentioning it.

## Test-first work plan

- [x] Create a clean worktree and branch.
- [x] Read root, `docs`, `test-experiments`, and shared `test-integration` guides.
- [x] Research the 262 native Remote Development entry point, VM-options precedence, lifecycle, and
  marker timing in IntelliJ sources.
- [x] Obtain final independent reviews from Claude Fable 5, Claude Opus 5, and a third
  reviewer; record decisions below.
- [x] Add failing unit tests for native launcher resolution, required distribution contents, ordered
  launch arguments, Windows quoting, and PID/process recognition.
- [x] Implement the IU-262-only native Remote Development launch path.
- [x] Confirm exact stable version `2026.2.0.1` resolves to `IU-262.8665.337`.
- [x] Build a narrow Docker feasibility probe: start the managed native backend, observe its marker, open
  Keycloak, and execute one `ClassInheritorsSearch` without a frontend.
- [x] Add the clean-machine fixture and explicit Claude/Codex `:test-experiments` cases.
- [x] Run scoped unit/contract tests and the complete `:npx-kt:test` suite before and after rebasing.
- [x] Add and pass focused launcher-PID → marker-PID handoff, marker-timeout, Remote Development
  environment, Docker-volume, log-stream teardown, and NDJSON parser tests.
- [x] Run Claude E2E and Codex E2E separately. Apply the one-minute hang rule and capture live dumps
  before terminating any stuck run.
- [x] Inspect relevant source diagnostics and runtime logs; fix every warning/error in scope.
- [x] Run final three-reviewer quorum, iterate on blockers, and repeat affected tests.
- [x] Record final evidence, update TODOs, commit atomically, push, and open
  [PR #411](https://github.com/jonnyzzz/mcp-steroid/pull/411).

## Risks and explicit non-goals

- Remote Development is an Ultimate capability. Community distributions are not accepted as substitutes.
- Linux native Remote Development excludes musl. Supporting musl is a follow-up unless the target Docker
  base proves to be musl.
- Backend startup may be allowed without a connected frontend, but licensing behavior under long-lived
  use must be observed rather than guessed. The expected 262 code path ties licensing to connected CWM
  sessions, not MCP-only startup.
- Screenshot/window tooling may be incomplete without a frontend. Acceptance relies on project routing
  and `steroid_execute_code`, not GUI rendering.
- This work adds no `steroid_*` tool, no `McpScriptContext` method, and no IntelliJ API wrapper.

## Evidence log

### 2026-07-31 — initial research

- Confirmed native `remote-dev-server run` maps to `remoteDevHost` and accepts no project.
- Confirmed adjacent managed VM options and plugin path apply to the native launcher.
- Confirmed MCP Steroid lifecycle startup is compatible with `IdeProductMode.isBackend`.
- Identified marker-before-project-open race and selected explicit post-discovery project opening.
- Identified exact-PID constraint that rules out the shell wrapper for the first implementation.
- Identified stable-channel resolver risk for an unreleased 2026.2 build.

### 2026-07-31 — live IU 262 Docker proof

- Resolved stable IntelliJ IDEA Ultimate `2026.2.0.1`, build `IU-262.8665.337`.
- Started the native `bin/remote-dev-server run` process without a project argument or frontend.
- Observed `IDE run mode: remote development (backend)` in the IDE log.
- Confirmed the devrig state PID equals the MCP Steroid marker PID.
- Opened Keycloak afterward through `steroid_open_project`.
- Executed deep `ClassInheritorsSearch` for `org.keycloak.authentication.Authenticator` and received
  72 classes, including all three required indirect implementors.
- Confirmed the shell wrapper is unsuitable because its restart loop breaks exact PID ownership.

### 2026-07-31 — keyed Claude clean-machine run

- Installed devrig for Claude at user scope inside a fresh Docker home.
- Downloaded and verified the 1.544 GB stable IU `2026.2.0.1` archive, installed MCP Steroid, and
  launched `remote-dev-server run` with the non-interactive trusted-project environment.
- Opened the pinned Keycloak checkout without a Remote Development frontend and returned all 72
  deep `Authenticator` inheritors, including the three required indirect classes.
- The agent workflow and backend itself completed successfully and PID 2661 stopped cleanly. The test
  remained red only because its post-run verifier incorrectly expected native-launcher JVM options in
  `ps` argv. The 262 launcher embeds libjvm, so the corrected verifier checks the effective managed
  plugin/cache properties recorded in `idea.log` and preserves that log for the final audit.
- Artifacts: `test-experiments/build/test-logs/test/run-20260731-123121-devrig-remote-backend-keycloak-claude`.

### 2026-07-31 — diagnostic and readiness iteration

- Reproduced the Codex double-start race: the shell start returned launcher PID 1881 before readiness;
  `steroid_open_project` triggered another start whose real MCP marker used PID 2032.
- `backend start` now waits for a matching live marker and persists its PID. Unit coverage forces an
  intentional launcher/backend PID handoff and proves stop targets the backend PID.
- Source-traced the clean-profile JDK SEVERE to `ExistingJdkConfigurationActivity` plus
  `SdkConfigurationUtil.createSdk`; selected the native launcher's supported
  `REMOTE_DEV_JDK_DETECTION=false` setting.
- Source-traced Keycloak's Maven `ProvisionException` to build-cache extension 1.2.0 eager
  `MavenSession` injection; selected 1.2.1, which uses a provider and keeps caching active.
- The E2E now fails on any IDEA SEVERE, the known Maven core-extension exception signatures, or any
  unexpected MCP Steroid WARN/ERROR/SEVERE, while preserving both IDEA and launcher logs.

### 2026-07-31 — final verification

- After the final readiness, PID-identity, shell-ownership, and operation-lock iterations, the focused
  lifecycle/list classes passed 50/50. On the rebased branch the terminal `:npx-kt:test` suite discovered
  1,445 tests: 1,441 executed and passed, with four pre-existing Windows-only skips and no failures or
  errors.
- The terminal Remote Development workflow contracts passed 4/4, the wider pure agent-output
  parser/workflow contracts passed 9/9, and the volume-isolation/log-stream
  infrastructure contracts passed 5/5. All `:test-experiments` and `:test-integration` invocations
  were serialized.
- A deterministic regression reproduced an unsuccessful Remote Development launch whose launcher was
  absent from one `ProcessHandle` snapshot. `terminateFailedBackendStart` now repeats bounded process
  discovery until three consecutive scans are quiet, so a failed start cannot leave a launcher or
  backend process behind.
- Direct IntelliJ inspections ran on every modified Kotlin production/test file with no failed tools.
  The post-quorum sweep found four `BlockingMethodInNonBlockingContext` findings after `stopLocked`
  became suspendable; keeping its blocking body non-suspending under the caller's `Dispatchers.IO`
  context removed all four. The terminal eight-file inspection was clean with zero findings and zero
  failed tools.
- Claude passed from a fresh Docker home, used the installed
  `/home/agent/.mcp-steroid/bin/devrig`, registered the user-scope MCP server, downloaded and started
  IU `2026.2.0.1`, opened Keycloak, and returned 70 subtypes in both the independently parsed tool
  result and independently parsed final answer in 175 seconds. Artifact:
  `test-experiments/build/test-logs/test/run-20260731-173006-devrig-remote-backend-keycloak-claude`.
- Codex then passed alone from another fresh Docker home and returned 70 subtypes in both independently
  parsed channels in 144 seconds. Artifact:
  `test-experiments/build/test-logs/test/run-20260731-173355-devrig-remote-backend-keycloak-codex`.
- A separate raw-NDJSON audit extracted only the user-visible final response from each artifact and
  independently counted 70 unique `SUBTYPE:` entries with all three required transitive classes for
  both Claude and Codex.
- Both runs crossed the one-minute threshold while making progress, so live screenshots and
  in-container JVM thread dumps were captured before they were allowed to continue. Both teardown
  paths stopped IU and left no Docker containers or managed backend processes.
- Both preserved idea and launcher logs are sanitized for Bearer headers, IntelliJ `_ijt` URL tokens,
  and `x-ijt` JSON header values while retaining safe diagnostics. The four artifacts from both passing
  Docker runs were re-sanitized locally and verify at zero marker-credential matches. Production full-
  marker logging remains tracked by [#405](https://github.com/jonnyzzz/mcp-steroid/issues/405).
- A post-quorum Docker rerun reached the fresh-home fixture but could not launch Claude because this
  shell had none of `ANTHROPIC_API_KEY`, `CLAUDE_EVAL_API_KEY`, or `~/.anthropic`; the test failed hard
  as designed and no skip was added. A separate Docker process-scan probe then proved the new teardown
  matcher detects an exact managed-bundle launcher argv while rejecting an unrelated shell whose
  command merely contains the same path. The earlier credentialed Claude/Codex Docker passes remain the
  end-to-end evidence.

### 2026-07-31 — complete `steroid_execute_code` call audit

- The terminal audit froze after
  `eid_20260731T103836-eid_20260731T100732-devrig-remote-backend-final-verification` and deduplicated
  5,962 raw events by `call_id` across 23 root/sub-agent rollout logs. It found 1,108 unique calls:
  1,069 successful, 39 failed, and 5,794.748 aggregate tool-seconds. The earlier 464- and 1,080-call
  numbers were narrower snapshots, not the complete terminal corpus.
- The 39 errors were 21 caller-script compilation errors, 13 caller-script runtime errors, four
  transient route-disappearance errors, and one dropped `/tools/call/stream` result. Eleven calls ran
  at least 60 seconds and all succeeded; no script timeout occurred. The corpus contained 362 scripts
  that used `RunContentManager`.
- Four non-duplicate problems were filed:
  - [#402](https://github.com/jonnyzzz/mcp-steroid/issues/402) — IU 262 Gradle recipes can lose test
    results and pre-test failure output.
  - [#403](https://github.com/jonnyzzz/mcp-steroid/issues/403) — the prompt corpus contradicts itself
    about client, script, transport, and orchestration timeouts.
  - [#404](https://github.com/jonnyzzz/mcp-steroid/issues/404) — the no-output hint falsely diagnoses
    legitimate conditional zero-result scripts as missing `println`.
  - [#405](https://github.com/jonnyzzz/mcp-steroid/issues/405) — production marker logging exposes all
    marker credentials: Authorization headers, `_ijt` URL tokens, and `x-ijt` header values.
- Existing owners received complete-corpus evidence instead of duplicate issues: process completion
  polling [#20](https://github.com/jonnyzzz/mcp-steroid/issues/20), recurring FilenameIndex directory
  crashes [#66](https://github.com/jonnyzzz/mcp-steroid/issues/66) (reopened), route resilience and
  misleading compile hints [#91](https://github.com/jonnyzzz/mcp-steroid/issues/91), compilation latency
  [#207](https://github.com/jonnyzzz/mcp-steroid/issues/207), and execution storage appearing in project
  indices [#280](https://github.com/jonnyzzz/mcp-steroid/issues/280). The seven-case correction was also
  added to #404. Timeout dump work remains [#215](https://github.com/jonnyzzz/mcp-steroid/issues/215),
  but this corpus did not reproduce it.
- The final independent re-audit added terminal evidence to #20, #91, #207, #280, #402, #403, and
  #405. The last new caller runtime failure was an inspection-loop `IndexOutOfBoundsException`; its
  duplicated diagnostics and generic hint belong to #91 rather than a new issue. Four independent
  auditors ultimately agreed exactly on the terminal census and mapping. No genuinely untracked defect
  remained, so no duplicate issue was created.

## Review log

### Independent implementation review — BLOCKER (pre-final iteration)

The read-only `independent_review` sub-agent found retry-sensitive `firstOrNull` assertions, premature
exit-code handling, missing `steroid_list_projects` proof, an unnecessary macOS VM-options mirror, the
misleading standard-launcher download message, and the verifier's invalid `ps`-argv assumption. The
iteration now accepts any successful retry, proves Keycloak through `steroid_list_projects`, defers the
Codex-137 decision until all evidence passes, validates effective managed properties in `idea.log`,
removes the mirror, reports the effective launcher, and gives every shell invariant a named failure
diagnostic.

### Final static sub-agent review — PASS after iteration

The final static reviewer identified launcher/interpreter false ownership, list commands that trusted a
live reused PID without comparing `startInstant`, unsafe raw marker diagnostics, and delivery/rebase
gaps. Deterministic regressions failed before the lifecycle/list fixes and passed afterward. Marker
diagnostics now project only safe fields; tool and final-answer scores are independent; the branch was
rebased cleanly onto `origin/main` while preserving all upstream `TODO.md` additions. A follow-up
iteration also made marker validation lazy for durable JSON PID identity and falls back to direct PID
inspection when one process listing transiently omits a live tracked process.

The next Codex quorum caught a newly advanced `origin/main` commit whose
`TODO-TC-COVERAGE-AUDIT.md` appeared deleted in the branch diff. The verified iteration was committed
and rebased again; the branch is zero commits behind `origin/main` and preserves that upstream audit
file.

### Final three-model quorum — PASS

- Claude Fable 5: `run_20260731-175744-47024`, PASS. It verified all six bounded acceptance claims
  and reported only two already tracked deferrals: applying the environment allowlist to standard
  managed launches and revalidating native Remote Development for baseline 263+.
- Claude Opus 5: `run_20260731-180448-50119`, PASS. It verified all six claims and reported five
  non-blocking observations. The unstaged evidence doc is committed during delivery; readiness
  liveness/timing and legacy-migration locking are recorded in `TODO.md`; the remaining scoring and
  broken-install-list observations are pre-existing or expected behavior.
- Codex: `run_20260731-175744-47025`, PASS with no findings. Its preceding full review
  (`run_20260731-173936-39114`) correctly blocked because `origin/main` had advanced with
  `TODO-TC-COVERAGE-AUDIT.md`; rebasing preserved that file and the bounded rerun passed.

The first full Fable attempt failed with repeated 529 capacity errors and the first full Opus attempt
hit the 900-second runner wall without a review. Both were rerun independently against the same bounded
acceptance surface and produced the persisted PASS verdicts above.

### Complete execute-code audit review — PASS

Four independent execute-code auditors rechecked the corpus and every proposed issue against existing
GitHub ownership. The terminal two-agent reconciliation agreed exactly on 1,108 calls, the 39-error
taxonomy, timing, and counters. A fourth post-delivery auditor independently recomputed the same census
from the frozen 5,962-event raw artifact and its `call_id`-deduplicated form, including all eleven
successful calls lasting at least 60 seconds. The auditors agreed that #402–#405 are distinct defects
and that every remaining observation belongs to #20, #66, #91, #207, #215, or #280. The final audit
verdict was PASS with no untracked finding.
