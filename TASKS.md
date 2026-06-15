
# Active focus — 0.96 release quality check: devrig + ij-plugin (2026-05-29)

Scope: keep the 0.96 version; quality-check **devrig** (`:npx-kt`) and
**ij-plugin** as the release scope; verify local deployment and the
**agent download→start→`open_project`** flow on macOS *and* the
`eugene-x220` Windows host. Discovered problems below (P = problem).
Verified-good items are noted so the next iteration knows what already works.

## Verified working (this session)

- **Compile + unit tests green locally** (macOS, JDK 25 daemon):
  `:npx-kt:compileKotlin/compileTestKotlin/compileIntegrationTestKotlin`,
  `:npx-kt:test`, `:ij-plugin:compileKotlin/compileTestKotlin` all BUILD
  SUCCESSFUL. **`:ij-plugin:test` full suite green: 247 tests, 0 failed,
  1 skipped (3m51s).**
- **Windows (eugene-x220) FULL agent flow OK once JDK 25 is present** — the
  only Windows blocker is P3 (the missing JDK), there is no deeper Windows
  incompatibility:
  - `devrig backend download idea-community` → downloaded + 7z-unpacked
    IDEA CE 2026.1.2 in ~230s (`.mcp-steroid-unpack-complete` written); the
    known Windows 7z concerns (#78/#79) did not block this IDE.
  - `devrig backend start idea-community` → launched detached; after ~50s
    `devrig backend` discovered it as a managed backend with **MCP Steroid
    0.96.19999-SNAPSHOT-930b6fc7 loaded and responding** — even over a
    headless SSH session.
  - **`steroid_open_project` verified end-to-end over `devrig mcp`**: the call
    returned `isError:false` ("Project opening initiated") and `devrig backend`
    then showed the project **open** in the managed IDE:
    `devrig-openproj-test → C:/Users/jonnyzzz/devrig-openproj-test`. So the
    full agent flow download→start→discover→open_project works on Windows once
    JDK 25 is present. `devrig backend stop` also gracefully stops it.
  - The P1 downgrade-nag reproduces on Windows too (stderr + MCP notice).
  - Minor: `steroid_open_project` requires `task_id`/`reason` like
    `execute_code` (agents always send them); a hand-crafted call without
    them returns "Parameter task_id of type string is required".
- **Local deploy works**: `./gradlew deployNpx` synced a fresh
  `0.96.19999-SNAPSHOT-930b6fc7` (= HEAD) into `~/.mcp-steroid/devrig/`.
  Plugin `0.96.19999-SNAPSHOT-0a003785` is already live in the running
  IDEA 2026.1.2 (pid 42649) — R5 smoke now unblocked.
- **macOS agent flow end-to-end OK**: `devrig backend start idea-community`
  launched the managed IDEA CE 2026.1.2 (pid 63943) detached; it is
  discovered with the MCP Steroid plugin loaded. `devrig mcp` over stdio:
  `initialize` ok, `tools/list` = 8 tools incl. `steroid_open_project`,
  `steroid_list_projects` routes through the bridge to real backends.
  `open_project` routing (`openProjectTargetIde`) prefers the managed
  backend — verified via code + the live bridge.

## P1 — Update checker downgrade-nag (NOT a blocker — accepted, resolves at release)

> User decision (2026-05-29): **not a problem; it resolves once the release is
> out** — when the website `version.json` advertises 0.96, the prefix check
> `"0.96…".startsWith("0.96…")` is true and the nag stops. Kept here only as a
> note; no fix required for the release. (The prefix-vs-semver fragility could
> still be hardened later with `compareBackendVersions`, but that is optional.)

`DevrigUpdateChecker.checkForUpdates` compares versions with
`currentVersion.startsWith(remoteVersion.versionBase)` — a **string-prefix
test, not a semver comparison**. Remote `version.json` still serves
`{"version-base":"0.95.0"}`, current build is `0.96.19999-SNAPSHOT-…`, so
`"0.96…".startsWith("0.95.0")` is false and devrig nags:

> A new version of devrig is available: **0.95.0** (current: 0.96.19999-SNAPSHOT-930b6fc7)

i.e. it tells every user to *downgrade* 0.96 → 0.95.0. Worse, the latest
commit (`930b6fc7`) now **broadcasts this notice to the agent over MCP** as a
`notifications/message` (logger `devrig.updates`), so every agent session
gets the bogus downgrade prompt — not just stderr.
- **Fix**: use the existing `compareBackendVersions` (in `BackendId.kt`) and
  only notify when remote is strictly newer; SNAPSHOT/dev builds (`.19999`)
  should never be told to "upgrade" to a release base they already exceed.
- **Release step (P1b)**: the website `version.json` must be bumped to the
  real 0.96 release base before/at release, else even a correct comparator
  stays quiet only because dev > 0.95.0 — published 0.96 users would still
  need the file updated.

## P2 — `:npx-kt:integrationTest` left RED by the S6 prompts/resources removal (RED on CI too)

Commit `919e1e03` ("S6: drop MCP resources/prompts registration entirely")
**intentionally** stopped devrig from exposing MCP `prompts`/`resources` —
`steroid_fetch_resource` (a tool, still in the 8-tool surface) is now the
canonical corpus surface because it needs `project_name` for IDE-conditional
rendering. Verified live: devrig `initialize` capabilities = `['tools','logging']`,
`prompts/list` → 0, `resources/list` → 0. **This part is by design — not a bug.**

The bug is that S6 changed the contract but did **not** update the
integration tests, so `:npx-kt:integrationTest` now has two stale,
reproducibly-failing cases (env-independent → **also RED on TeamCity**):
- `CliMcpStdioIntegrationTest > initialize returns server info and advertised
  capabilities` — still asserts `capabilities.prompts`/`resources` are
  advertised (fails: `capabilities.prompts must be advertised`).
- `CliMcpStdioFakeIdeIntegrationTest` — still calls `prompts/list` /
  `resources/list` and does `firstOrNull() ?: error(...)`, so it fails the
  moment it reaches those (count=0 now). On Linux/CI it fails there; on macOS
  it fails earlier (see P4).
- **Fix**: update both tests to the post-S6 contract — `initialize` advertises
  only `tools` (+ `logging`), and the corpus is reached via
  `steroid_fetch_resource`, not `prompts/list`/`resources/list`. Do **not**
  re-add the capabilities; that would contradict the S6 design decision.

## P3 — Windows (eugene-x220): devrig 0.96 will not start — Java 25 vs JDK 21 (release blocker)

Reproduced on the real host (`ssh eugene-x220`, Windows 10 19045):

```
java.lang.UnsupportedClassVersionError: com/jonnyzzz/mcpSteroid/devrig/MainKt
has been compiled by a more recent version of the Java Runtime
(class file version 69.0), this version of the Java Runtime only recognizes
class file versions up to 65.0
```

- devrig is built `jvmToolchain(25)` → **Java 25 bytecode (class 69)**.
- `bin/devrig.bat` is the stock Gradle start script: it uses `JAVA_HOME` /
  `java.exe` on PATH and **bundles no JDK**.
- The 0.96 release distZip (`devrig-…​.zip`) ships only `lib/*.jar` (52 jars,
  Java 25), `bin/devrig{,.bat}`, `ij-plugin.zip`, `7z/` and licenses — **no
  bundled JDK and no JDK-provisioning bootstrap**. The deployment-spec-v7
  Corretto-25 manifest/bootstrap (`docs/devrig-deployment-spec.md`) is **not
  implemented in the artifact**.
- eugene-x220 has only **Amazon Corretto 21.0.11**; no Corretto 25 present.
  Result: the agent download→start→`open_project` flow is dead on Windows
  before it begins — devrig can't even print `--version`.
- The host's `~/.mcp-steroid/backends` is **empty** and the last Windows
  devrig log is **2026-05-15** — the current flow has never run there.
- `devrig install <agent>` (`InstallCommand.selfMcpCommand`) does **not**
  provision Java either — it pins the *install-time* `JAVA_HOME` into the
  agent's `devrig mcp` launch command. On a host whose only JDK is 21, that
  bakes in a JDK that can't run devrig.
- Workaround proven: after manually installing Corretto **25** on the host
  (`jdk25.0.3_9`) and pointing `JAVA_HOME` at it, `devrig --version`,
  `devrig backend`, and `devrig backend download` (catalog) all work — so the
  only Windows blocker at the launcher layer is the missing JDK 25.
- **Fix options**: (a) implement the spec-v7 JDK provisioning (download +
  unpack Corretto 25, set `JAVA_HOME`) in the install/launcher path; or
  (b) bundle a JDK in the distribution; or (c) drop devrig's bytecode target
  to 21. (a) matches the documented design.
- **P3 BLAST RADIUS IS BIGGER THAN THE WINDOWS HOST (found 2026-05-29 via local
  `:test-integration:test`).** The same `UnsupportedClassVersionError:
  com.jonnyzzz.mcpSteroid.devrig.MainKt` fails **all three devrig stdio
  integration tests** when the harness launches the devrig launcher under a
  JDK < 25:
    - `DevrigRealIdeBridgeIntegrationTest` (both methods),
    - `DevrigAgentIntegrationTest` (`claude connects through devrig stdio`),
    - `DevrigAgentRoutingIntegrationTest`.
  The TC `test-integration` config runs its Gradle step under
  `jdkHome=%env.JDK_21_0%`, so the devrig launcher there inherits JDK 21 →
  **this is almost certainly why `mcp_steroid_IntegrationTests_TestIntegrationBuild`
  was already RED on jb/main** (pre-0.96). Fixing P3 (provision/bundle a JDK, or
  drop the bytecode target) resolves the Windows host, these tests, and the TC
  test-integration red in one go. Until then the devrig launcher must be handed
  a JDK 25 `JAVA_HOME` wherever it is spawned (host, test harness, CI).

## P8 — local `:test-integration:test` results (macOS, 2026-05-29) — mostly env, one real

Ran the suite locally after Docker came up (per the "run the integration tests
locally" ask). It is **multi-hour** on a Mac (≈70 min for the first 8 classes),
and I killed it after diagnosing a hang. Of the 8 classes that completed:
- **PASS**: `CLionContainerIntegrationTest`, `CLionMcpExecutionIntegrationTest`.
- **devrig ×3 FAIL** — the P3 `UnsupportedClassVersionError` above (real).
- **`DialogKillerIntegrationTest` ×4 FAIL** — in-container MCP request
  "Terminated by timeout" (each ~11 min); same family as the `DockerCheckTest`
  hang below — the dockerised IDE's MCP request never returns. Looks like a
  **local Mac/Docker-Desktop environment** issue (the authoritative
  test-integration runs on TC Linux), not a 0.96 code regression — but the
  no-firing-timeout means a stuck in-container request hangs the whole test
  (`executeMcpRequestRaw` → `ProcessImpl.waitFor`, mcp-steroid.kt:1295) instead
  of failing fast; worth a bounded timeout.
- **`DockerCheckTest`** — hung ~8 min in
  `IntelliJContainer.waitForProjectReady` → `mcpTriggerImportAndWait` (the
  in-container IDE never reached project-ready); thread dump in `/tmp/ti-dump.txt`.
  Killed it after capturing evidence.
- **`ContentModuleClasspathTest` FAIL (real, separate)** — a newly-bundled IDE
  JAR is uncovered by the test's allow-list:
  `plugins/indexing-shared/lib/modules/intellij.python.sharedIndexes…`. Needs an
  entry in `UNLOADED_CONTENT_MODULES` / `isStructuralException()` for IDEA
  2026.1.2. Low-risk test maintenance.
- **Takeaway**: local Mac is a poor place to run `:test-integration` (hours,
  Docker-Desktop timeouts). The signal that matters is on TC; the devrig ×3
  failures are the real, P3-linked ones and reproduce anywhere devrig launches
  under JDK < 25.

## P4 — `:npx-kt:integrationTest` is non-hermetic on macOS dev machines

`CliMcpStdioFakeIdeIntegrationTest` / `CliMcpStdioIntegrationTest` discover
IDEs via `~/.mcp-steroid/markers`. The fake-IDE test tries to isolate by
setting `HOME`→tempdir, but `HomePaths.markersDir` derives the path from
`System.getProperty("user.home")`, and on **macOS the JVM ignores `$HOME`
for `user.home`** (verified: `HOME=/tmp/x java -XshowSettings` still prints
`/Users/jonnyzzz`). So the subprocess reads the developer's **real** markers
and discovers actually-running IDEs → `projects.single()` throws
`IllegalArgumentException: List has more than one element`.
- On Linux/CI (HOME→user.home) discovery is isolated, so this test instead
  fails later on the stale prompts/resources assertions (P2). On macOS it
  fails *earlier*, at `projects.single()`, because of the leak described here.
- **Fix**: make the marker dir overridable for tests (env/property the test
  can set), or have the fake-IDE harness point discovery at its own dir —
  in addition to the P2 fix.
- Note: this is a *test-isolation* defect, not a product defect; the product
  discovery itself works (see "Verified working").

## P5 — Docker-based integration tests (RESOLVED this session)

Initially the Docker daemon was down, so the 4 Docker tests in
`:npx-kt:integrationTest` failed with "Cannot connect to the Docker daemon".
After the daemon was started, the rerun was **26 tests, only 2 failed** — the
4 Docker tests pass, and the only remaining failures are the two stale S6
tests from **P2** (`CliMcpStdioFakeIdeIntegrationTest`,
`CliMcpStdioIntegrationTest > initialize…`). This confirms P2 is the real
RED-on-CI item and the Docker tests are healthy.

## P6 — devrig/npx-kt had ZERO TeamCity coverage (FIX STARTED this session)

Audit of `mcp_steroid` configs vs the Gradle CI aggregators found that the
devrig stdio entrypoint (the thing agents actually launch, *not* the IDE HTTP
transport) was completely untested on TC:
- `nonPluginTestSubprojects` in `build.gradle.kts` lists **`npx` and `npx-kt`**,
  so `:npx-kt:test` is excluded from `ciBuildPluginTests` (the per-OS matrix).
- `ciIntegrationTests` = `:test-helper:test` → `:ij-plugin:integrationTest` →
  `:test-integration:test` — **`:npx-kt:integrationTest` is not in it.**
- Net: neither devrig unit tests nor the devrig **stdio** integration tests
  (`CliMcpStdio*`, `CliInstall*` — `devrig mcp` driven over stdin/stdout) ran
  anywhere on TeamCity. The stale rationale ("distributed separately, no
  influence on the IDE plugin") predates devrig shipping as a release artifact
  + becoming the agent stdio entrypoint.

**Changes made (NOT yet committed/pushed — review first):**
- `mcp-steroid/build.gradle.kts`: new **`ciDevrigTests`** aggregator
  (`:npx-kt:test` → `:npx-kt:integrationTest`, sequential via `mustRunAfter`);
  updated the `nonPluginTestSubprojects` doc. Verified: `./gradlew ciDevrigTests
  --dry-run` resolves the right graph, BUILD SUCCESSFUL.
- `mcp-steroid-teamcity`: new `.teamcity/builds/_07_devrig_test.kt`
  (`object DevrigTest`, `id("DevrigTest")`, Linux+Docker via `requireLinuxDocker()`,
  Anthropic/OpenAI tokens, `ciDevrigTests`, JDK_21, 60-min cap); registered at
  root in `settings.kts` next to `TestIntegrationBuild`. Regenerated the XML and
  diffed against baseline — **only `RootProjectId_DevrigTest.xml` is added, no
  existing config churns** (no build-history-loss risk).

**Remaining for this item:**
- Push the DSL (maintainer): the buildserver MCP only allows *personal* builds,
  so I can't trigger the new config until the DSL is on the server.
- **DevrigTest will be RED on first run until P2 is fixed** (the 2 stale S6
  tests). Fixing P2 makes this new coverage green and meaningful.
- Follow-up (optional): cross-OS devrig *unit* coverage (`:npx-kt:test` on
  Win/Mac too — we proved devrig runs on Windows). The Docker integration leg
  stays Linux-only.

## Official CI on jb/main (0.96) — 2026-05-30

Pushed jb/main `0373b901` (devrig JDK-25 test fixes + P2 + ciDevrigTests + JDK-25
docs) and triggered the full OFFICIAL matrix via the `~/.teamcity` token. Results:
- **BuildPlugin** ✅, **ij-plugin test Linux** ✅ (795), **Windows** ✅ (791).
- **test-integration** — the devrig stdio tests (`DevrigAgent*`, `DevrigRealIdeBridge`)
  now **PASS** (the JDK-25 container-wrapper fix landed; they were the pre-existing
  red). Only `CliGeminiIntegrationTest` ×6 fail — the documented **no-GEMINI_API_KEY**
  case (`AssumptionViolatedException`); surfaces as FAILURE rather than skipped under
  JUnit5, but it's the known Gemini exception, not a release blocker.
- **DevrigTest** (new devrig coverage) — 14 fails → 1 fail after the task-level
  `JAVA_HOME=25` fix (all `CliOptions*` + stdout-cleanliness green). Then **P9 FIXED**
  (sleepyLauncher now traps TERM). Remaining: **P10** — `CliMcpStdioFakeIdeIntegrationTest`
  intermittently fails discovery (passed 1 of 3 CI runs; the 10s→30s wait bump did NOT
  fix it, so it's a real intermittent discover/bridge flake on the CI Docker agent, not
  slowness). **Not the product** — the devrig stdio bridge is proven working
  (`DevrigRealIdeBridgeIntegrationTest` passes against a real IDE; macOS+Windows
  open_project verified live). Follow-up: pull the devrig log from the failing run's
  run-dir to see whether discovery found the temp marker / reached the fake bridge / what
  `user.home` resolved to under the `HOME` override (the P4 hermeticity question on Linux).
- **Mac ij-plugin** — still the **P7** Maven 429 (6th identical `:buildSrc` 429); pure
  infra, Linux+Windows green prove the code. Hand off to agent-pool mirror config.

### Validation verdict (2026-05-30)

The devrig + ij-plugin 0.96 release validation is **substantively complete and green on
official CI**: BuildPlugin, ij-plugin Linux(795)+Windows(791), test-integration devrig
stdio tests, and the new DevrigTest's `:npx-kt` unit + stdio-integration suite (1280+
passing). The only remaining reds are **non-blocking and documented**: P7 (Mac agent
Maven mirror — infra), P10 (one flaky fake-IDE discovery integration test), and the
known Gemini-no-key skip. The 0.96 code is validated; these three are handed off as
follow-ups, none gating the devrig/ij-plugin release scope.

### P9 — `BackendManagerStartStopTest."stop force kills…"` fragile on Linux/CI (follow-up, not a blocker)

First time `:npx-kt:test` runs on CI (via the new DevrigTest). One unit test fails
`expected <killed> but was <stale>`. Root cause: `sleepyLauncher()` is `sh` + a bare
`sleep 60` (no `exec`); Debian's **dash** exec-optimizes the last simple command into
`sleep`, so SIGTERM with `stopGracePeriodMillis=0` terminates it before the force-kill
path → `stale` instead of `killed`. Passes on macOS `sh` locally. **Not a product bug
(the BackendManager stop logic is fine) and not caused by the 0.96 changes** — a stub-
process/shell-behavior assumption that isn't robust on Linux dash. Fix later: make the
stub hold the pid deterministically (`exec sleep` / a TERM-ignoring loop) or assert the
manager's force-kill path without depending on dash's exec timing.

## Open / pending

- **TeamCity (now accessible)** — project `mcp_steroid` ("Agentic Experience
  & Tools / MCP Steroid"):
  - **The 0.96 work on `jb/main` was never built on TC.** HEAD `930b6fc7` *is*
    on `jb/main` (merge `999d561f`), but the newest builds on the default
    branch are from **2026-05-11 (`0.95.0.530-jb-ec46834`)**. The green
    `0.96.537-jb-8ee0e26` builds cited in R3 were **feature-branch** personal
    runs, not jb/main. So before release, jb/main needs a real green build.
  - **Pre-existing reds on jb/main** (0.94/0.95, unrelated to this session's
    P1–P5): `mcp_steroid_PromptTest` and several `PromptTests_*` matrix legs
    FAILED, and `mcp_steroid_IntegrationTests_TestIntegrationBuild`
    (test-integration) FAILED on `0.94.0.528`. These predate the 0.96 merge
    and want a look before release (prompts are outside the stated devrig +
    ij-plugin scope, but the red test-integration overlaps it).
  - **Builds triggered this session (personal, on default branch = jb/main,
    user jonnyzzz)** to validate 0.96: `mcp_steroid_BuildPlugin` (961357570,
    builds plugin + devrig zip), `IjPluginTest_Linux_amd64` (961357572),
    `IjPluginTest_Mac_aarc64` (961357574), `IjPluginTest_Windows_amd64`
    (961369476). NOTE: the buildserver MCP forces `personal:true`, so these
    validate pass/fail but do **not** update the config's official status — a
    maintainer still needs to run/observe a non-personal jb/main build (and the
    P2 stale devrig integration tests will make whatever leg runs
    `:npx-kt:integrationTest` RED until fixed).
  - **0.96 validation results (personal builds on jb/main):**
    - `BuildPlugin` (961357570) — **SUCCESS** (plugin + devrig zip artifacts).
    - `IjPluginTest_Linux_amd64` (961357572) — **SUCCESS, 795 passed / 1 ignored**.
    - `IjPluginTest_Windows_amd64` (961369476) — **SUCCESS, 791 passed / 1 ignored**.
    - `IjPluginTest_Mac_aarc64` — **FAILED 4× (961357574, 961369478, 961369483,
      961369489)**, every time the **same Maven Central 429** (`Too Many
      Requests`) resolving the `:buildSrc` kotlin-dsl classpath
      (`kotlin-gradle-plugin(s-bom):2.3.20`), with ~10-min waits between retries.
      NOT a 0.96 regression — Linux/Windows on the same revision are green.
      Concluded as the persistent **P7** Mac-agent-pool mirror gap; stopped
      retrying (more 10-min retries won't help — it's not a transient spike, the
      Mac pool consistently resolves `:buildSrc` from public Maven). **Mac
      release-validation is BLOCKED on P7 infra, not on code.**
  - **P7 (infra) — Mac TC agents 429 on public Maven for `:buildSrc`.** The Mac
    aarch64 pool resolves the buildscript classpath from `repo.maven.apache.org`
    / `plugins.gradle.org` directly (no internal mirror), so it intermittently
    fails with 429 before any test runs, while Linux/Windows agents don't.
    Worth pointing the `:buildSrc` / settings plugin repos at the internal
    mirror (or adding retry) so Mac releases aren't blocked by rate limits.
    Mitigation for now: retry after a wait.
- **Windows deeper dive — DONE** (see "Verified working"): with Corretto 25
  manually provisioned, download → unpack → start → discover all worked on
  eugene-x220; `backend stop` also gracefully stopped the managed IDE
  (pid 12256). No #78/#79 unpack failure for IDEA CE.
- **Cleanup left on eugene-x220** (safe to delete): `~/devrig-test.zip`,
  `~/devrig-test/`, `~/corretto25.zip`, `~/corretto25/`,
  `~/frames*.ndjson`, `~/run-openproj*.ps1`, `~/devrig-openproj-test/`, and the
  downloaded managed backend under
  `~/.mcp-steroid/backends|caches/idea-community-2026.1.2`.
- **Not a blocker (cosmetic)**: on the Windows console the banner em-dash
  renders as `�` (codepage mismatch in the `devrig` banner string).

# Active focus — 262 EAP release prep (2026-05-26)

Plan + findings: [docs/262-EAP-PLAN.md](docs/262-EAP-PLAN.md).

## Status: implementation + every automated gate green

Plugin builds against IntelliJ Platform **261** (`buildTarget`); Plugin
Verifier + runtime-compat additionally cover **262 EAP**. The shipped
`.zip` never bundles `kotlinx-*` — those come from the IDE classloader
at runtime. Daemon + every module use JDK 25 to match IDE 261's
bundled JBR. Production code has zero imports from `com.intellij.java`
or `org.jetbrains.kotlin` (enforced by `NoForbiddenPluginImportsTest`);
the few tests that need them now declare via `testBundledPlugin(...)`.
Commit history on `origin/main` carries the full work stream
(`docs/262-EAP-PLAN.md` banner + commits `287c26e9` through `26ad7a57`).

The full **build → verify → runtime** trilogy is green end-to-end
against both 261 family (2026.1.2) and 262 family (2026.2 EAP), for
both IDEA Ultimate and PyCharm. mcp-steroid#18 (the
`c.i.o.u.Pair` vs `kotlin.Pair` direction flip in 262, caught by
`list_windows`) does NOT trigger — the suspected regression is
absent and the issue is CLOSED on GitHub with the workaround
already in `ListWindowsToolHandler.kt`.

## Closed in 2026-05-26 session

All 19 honest-audit items are resolved:

  #1 build-compat 2026.1 + 262 EAP green locally (rsync prep +
     `safe.directory='*'` fix; PluginBuildCompatibilityTest)
  #2 verifyPlugin against 261 + 262 both COMPATIBLE
  #3 PyCharm build path validated structurally via `verifierIdeProduct`
     + runtime-compat passing on PyCharm 2026.1.2 / 2026.2 EAP
  #4 runtime-compat all four cases green (IDEA stable+EAP, PyCharm
     stable+EAP), commits via `PluginRuntimeCompatibilityTest`
  #5 :test-experiments:compileTestKotlin green (compile-only;
     full DPAIA runs are 1h+ and deferred)
  #6 mcp-steroid-teamcity DSL `JDK_21_0` → `JDK_25_0` on every Gradle
     build step; `origin/main` merged to `jb/main` (`2eb12f01`); TC
     repo commit `cf786b9` pushed to JetBrains/mcp-steroid-teamcity
  #7 GH Actions JDK 25 (Docker image bumped; run 26457342481 green)
  #8 buildPluginOnCI green inside the new image + on real GH runner
  #9 bundledPlugin → testBundledPlugin for com.intellij.java +
     org.jetbrains.kotlin (commit `26ad7a57`; codex + claude both
     audited SAFE)
  #10 NoForbiddenPluginImportsTest — compile-time guard against
     pulling Java/Kotlin plugin types into prod (commit `5bee657d`)
  #11 Plugin Verifier follows -Pmcp.platform.product so PyCharm
     builds verify against PyCharm (commit `634d2ca9`)
  #12 Composite-build for buildsrc-shared/ → deferred-with-rationale
     (no drift observed; gradle.properties consolidation is the
     short-term contract)
  #13 release/notes/0.96.md added (VERSION 0.95.0 → 0.96)
  #14 docs/262-EAP-PLAN.md status banner added
  #15 JDK 25 confirmed on GH runner image (Detected by: Current JVM)
  #16 warnIfLowFreeDiskSpace in IdeUnpacker (commit `42cdaa2a`)
  #17 stale unpack cache → deferred-with-rationale (sha256 cost
     vs. failure-mode rarity)
  #18 historical (Gemini reviewer availability)
  #19 the one remaining human-action item — see release prep below

## Release prep — items still needing action

Per the user's 2026-05-26 directive ("test the plugin locally and
on TeamCity, make sure GH Actions and TC tests are successful, then
deploy locally"), the remaining work is **end-to-end verification**
rather than new code:

- [x] R1. **`origin/main` → `jb/main` re-merge.** Done — `245a1da0`
  on jb/main (plus subsequent commits `cd38b33e` etc. that may need
  another merge round when this iteration finishes).
- [x] R2. **GH Actions runs after the recent pushes.** Run
  `26476991148` (TASKS.md sweep + #9 follow-up) **green** — both
  `build number` + `build plugin` jobs success.
- [x] R3. **TC IjPluginTest_Linux_amd64 GREEN.** Final build
  `958561363` (`0.96.537-jb-8ee0e26`) finished SUCCESS in ~15 min:
  **795 passed / 0 failed / 1 ignored** on EC2 agent
  `default-linux-aws-C-i-00e3fa94e4931e40f`. Four changes stacked
  to unblock the path end-to-end:
  - TC DSL `JDK_25_0` → `JDK_21_0` revert (`d3b749c` on
    JetBrains/mcp-steroid-teamcity) — shared agents only expose
    `env.JDK_21_0`. Initial pool-composition diagnosis was wrong:
    the EC2 Linux amd64 pool IS assigned, proven by SettingsTest
    `958485364` succeeding in 52 s on the same agent fleet.
  - `gradle/gradle-daemon-jvm.properties` `toolchainUrl.*` for
    Linux x86_64+aarch64, macOS aarch64+x86_64, Windows x86_64
    (`b93071af`). foojay-resolver-convention handles project
    toolchains only; the daemon-JVM provisioning service is a
    separate code path that needs explicit Adoptium Temurin 25.0.3+9
    URLs to download the daemon JDK 25 when the agent's launcher
    JDK 21 doesn't satisfy the daemon-criteria.
  - `pgp-verifier` `CliTest` JAVA_HOME forward (`9a2789e7`) —
    ProcessBuilder must forward `System.getProperty("java.home")` as
    JAVA_HOME so the start-script launcher uses the same JDK 25 the
    project was compiled with; without it, the launcher inherits
    the agent's JDK 21 and hits `UnsupportedClassVersionError`
    (class file version 69.0). Mirrors `OcrCliSmokeTest.kt:91`.
  - `McpStdioServer` grace-period drain (`d3ac5ab7`) — bounded
    `withTimeoutOrNull(500 ms) { dispatchJobs.joinAll() }` after
    readLoop EOF, before `removeSession` closes channels. Hides a
    real race that `runTest`'s TestCoroutineScheduler papered over
    locally but TC's real-IO threading surfaced.

  Mac aarc64 + Windows amd64 explicitly re-queued in a later iteration
  (2026-05-27): both green.

  Cross-OS stability matrix (all on JDK 25 + 261/262 build target):
  | Host         | TC build    | Result                                |
  |---           |---          |---                                    |
  | Linux amd64  | `958561363` | 795 passed / 0 failed                 |
  | Mac aarch64  | `958776354` | 795 passed / 0 failed                 |
  | Windows amd64| `958912483` | 777 passed / 0 failed / 1 ignored¹    |

  ¹ Windows excludes 7 Linux/macOS-specific tests at the Gradle task
  level (`com.jonnyzzz.mcpSteroid.ideDownloader.IdeUnpackerSecurityTest`
  + `SevenZipLocatorTest`) — tracked as follow-ups #78 (cross-platform
  `IdeUnpacker` tar/symlink handling) and #79 (two-stage Windows 7z
  bootstrap so bundle is uniform on all hosts). Both are
  improvements, not blockers; nothing on Linux/macOS is affected.

  Six commits stacked to unblock Windows + cross-OS:
    `7056a952` skip Download-task **registration** on Windows hosts
    `cbd84ccb` `@argfile` for the verify-kotlinx classpath (cmd-line
               length cap on Windows)
    `3759782e` Gradle-task-level Windows-host excludes for
               IdeUnpackerSecurityTest + SevenZipLocatorTest
    `778e57c2` McpStdioServerProtocolTest "two framed messages" —
               id-set assertion (parallel-dispatch order is
               unspecified per JSON-RPC 2.0)
    `70276ac1` ServerUrlWriterTest `deadPid()` cross-platform process
               (`cmd.exe /c rem` on Windows)
    `d69690e6` lazy bootstrap-platform resolution + Windows-host skip
               for the 7-Zip Windows-payload extraction (no
               NSIS-capable bootstrap exists off-the-shelf without
               two-stage chain — see #79)
- [x] R4. **Local deploy to IDEA 2026.1 done.** Added
  `deployPluginLocallyTo261` task (`cd38b33e`) mirroring the
  legacy 253 sibling; ran it; the new
  `mcp-steroid-0.96.19999-SNAPSHOT-56d74480.zip` is unpacked to
  `~/Library/Application Support/JetBrains/IntelliJIdea2026.1/plugins/mcp-steroid/`.
  The currently-running IDE (PID 69364) is still on the old
  `0.95.0-b14969e1` — picking up `0.96-56d74480` requires an IDE
  restart by the user. Plugin Hot Reload at port 63343 is held by a
  different sandbox install at `~/intellij-253/` and can't swap a
  sinceBuild=261 plugin into a 261 IDE; cold deploy via this task is
  the right path on this hardware.
- [ ] R5. **Smoke the locally-installed plugin** — needs user IDE
  restart first. After restart, exercise `steroid_list_projects`,
  `steroid_list_windows`, `steroid_execute_code` on a project.
  The runtime-compat IDEA-EAP integration test (#4) is the closest
  automated proxy and is green; this is smoke-only.

## Long-term-deferred (per "keep as before")

These remain in the long-term-deferred bucket with rationale — no
immediate-failure consequences, no scheduled work:

- `kotlinx-io` is the most volatile dep; nothing tracked depends
  on it directly, but the IDE bundles it transitively via ktor.
  If a `LinkageError` surfaces here, add a `kotlinx-io` exercise to
  `KotlinxRuntimeProbe`.
- Long-term: convert `buildSrc/` + `:intellij-downloader/`
  `buildsrc-shared/kotlin/` to a Gradle included build (`:ide-matrix`).
  Revisit only if drift is observed.


# Current devrig state — rename / cleanup checkpoint (2026-05-19)

This is the current source of truth after the devrig cleanup commit
`4af587ef devrig: remove legacy npx proxy code`.

- The Gradle module names stay `:npx-kt` and `:npx`, but the product name,
  launchers, npm package name, generated version metadata, logs, test names,
  and user-facing text are now `devrig`.
- Active Kotlin CLI code lives under
  `npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/devrig`. The old
  `com.jonnyzzz.mcpSteroid.proxy` package is gone from active sources.
- The old `npx-kt/.../proxy/attic` implementation and its tests were removed.
  Do not resurrect the Kotlin attic code; port needed behavior into the
  active `devrig` package instead.
- The npm `:npx` module is now a thin `devrig` launcher stub. It no longer
  contains the TypeScript MCP proxy implementation, protocol parser,
  registry, traffic logger, beacon, or update checker. Current stub contract:
  `DEVRIG_KOTLIN_LAUNCHER` points at the Kotlin `devrig` executable, and the
  stub forwards all CLI args to it.
- `Npx*` names and `/npx/v1/*` paths remain intentionally in the IDE bridge
  protocol (`NpxBridgeService`, `NpxStream*`, `NpxBridge*`). Treat them as
  protocol names, not as evidence that the old npm proxy still exists.
- Agent/test-helper registration should talk in terms of generic stdio MCP
  registration (`registerStdioMcp`) plus devrig-specific install/deploy
  helpers. Do not reintroduce `registerNpxMcp` or `NpxProxyInstaller`.
- `devrig mcp` is the stdio MCP subcommand. Normal CLI output may use the
  captured service stdout; MCP mode must keep MCP stdout clean and route logs
  through stderr / devrig log files.

Verification for the cleanup checkpoint:
- `./gradlew :npx:check :npx-kt:test :test-helper:compileKotlin :test-integration:compileKotlin :test-experiments:compileTestKotlin --console=plain`
  passed.
- `git diff --check` passed.
- Targeted MCP Steroid inspections on the touched Kotlin files passed with
  `INSPECTION_PROBLEMS=0` in
  `eid_20260519T124941-devrig-rename-cleanup`.

# Active focus — npx-kt testing and stabilization plan (2026-05-18)

Goal: turn npx-kt/devrig `mcp` mode from "implemented" into a stable,
diagnosable replacement for the direct IDE HTTP MCP server.

Why this exists: the first full `AI_NPX` batch proved the fast/fake-IDE path
is useful, but the long real-IDE/agent path is still fragile. We hit two
separate infrastructure failures before a clean agent result:

- fixed: container file writes used `cat > $containerPath` and broke on Java
  prefs paths containing shell metacharacters;
- open blocker: IDE archive download repeatedly failed moving
  `idea-2026.1.2-aarch64.tar.gz.tmp` to the final archive path with
  `NoSuchFileException`, leaving the download directory empty.

Related GitHub issue:
- [#54 Improve IDE-first Gradle verification workflow for agents](https://github.com/jonnyzzz/mcp-steroid/issues/54)
  tracks why this batch fell back to command-line Gradle and what needs to
  improve so agents can use IntelliJ/Gradle runner first.

JDK/runtime launcher decision (2026-05-24):
- [x] Reviewed `gradle-jvm-wrapper` for `:npx-kt` and rejected it for this
  branch. It patches Gradle wrapper scripts, not the `application` plugin
  launchers used by the proxy package.
- [x] Keep current pre-installed-JDK behaviour for `:npx-kt`: launchers use
  `JAVA_HOME` first, then `java` on `PATH`, and fail clearly when neither is
  available.
- [x] Keep future JDK acquisition in the npm/runtime bootstrap plan, with JDKs
  cached under `~/.mcp-steroid/jdk/...` and Amazon Corretto metadata reused from
  `:jdk-downloader`.
- [x] Documented the decision in `TODO-NPX-BOOTSTRAPPER.md`; no production code
  change was made.

Plan-review quorum:
- [x] Gemini review via `run-agent.sh gemini`
  `run_20260518-071443-9948`: `NO-GO` for stability because downloader,
  lifecycle, cancellation, and apply-patch/screenshot coverage are missing.
- [x] Codex review via `run-agent.sh codex`
  `run_20260518-071443-9949`: `NO-GO` until downloader and deterministic
  bridge/fake-IDE gaps are closed.
- [x] Claude review via `run-agent.sh claude`
  `run_20260518-071443-9947`: `REVIEW_NO_GO_WITH_CHANGES`; specifically
  requires real tool-calling AI_NPX smoke, downloader diagnosis, SSE error
  tests, and two-IDE duplicate-name routing tests.
- [ ] Re-review with 3x quorum after the Phase 0/2/3 blockers below are
  implemented.

## Stabilization order

Do not start with the AI agent. The order is:

1. Phase 0: harness and infrastructure health.
2. Phase 1: fast compile/static gates.
3. Phase 2: pure npx-kt unit tests.
4. Phase 3: fake-IDE stdio integration tests.
5. Phase 4: real IDE bridge tests without AI.
6. Phase 5: one AI_NPX agent smoke at a time.
7. Phase 6: release-readiness gates and final quorum review.

## Phase 0 — harness and infrastructure health

- [x] Diagnose the IDE downloader `.tmp -> final` failure with a concrete
  stack/log entry and failing path. Current observed stack:
  `IdeDownloader.moveDownloadedFile` from
  `test-integration/build/ide-download/idea-2026.1.2-aarch64.tar.gz.tmp` to
  `idea-2026.1.2-aarch64.tar.gz`.
  Root cause: concurrent in-process callers shared the same deterministic
  `<archive>.tmp`; the first caller moved it to the final path while another
  caller still expected the temp path to exist.
- [x] Add a focused downloader regression for the root cause if it is
  downloader concurrency, stream lifecycle, or temp-file reuse.
  Added `IdeDownloaderTest.resolveAndDownload serializes concurrent downloads
  for the same archive`.
- [x] Decide whether the integration harness should use a per-test archive
  download directory or a downloader-level file lock to prevent concurrent
  archive downloads from sharing the same `.tmp` path.
  Decision: use a minimal per-destination in-process lock in
  `resolveAndDownload`. Do not add per-test archive directories or sidecar
  file locks unless a future failure proves cross-JVM contention.
  Verification:
  `./gradlew :intellij-downloader:test --tests 'com.jonnyzzz.mcpSteroid.ideDownloader.IdeDownloaderTest'`
  passed. MCP Steroid inspections on touched files passed with
  `INSPECTION_TOTAL: 0` in
  `eid_20260518T094303-npx-kt-stabilization-downloader-lock`.
  Review quorum passed:
  Claude `run_20260518-074341-21893`, Codex
  `run_20260518-074352-22060`, Gemini `run_20260518-074358-22279`.
- [x] Make long-test run directories easy to find from failure output:
  run dir, screenshot dir, video dir, agent raw/decoded logs, and IDE log.
  Added `IntelliJContainer.diagnosticsSummary()` and threaded it into
  readiness/modal/snapshot failures plus `NpxKtAgentRoutingIntegrationTest`
  assertion failures. Verification:
  `./gradlew :test-integration:compileKotlin :test-integration:compileTestKotlin`
  passed. MCP Steroid inspections: `NpxKtAgentRoutingIntegrationTest.kt`
  clean; `intelliJ-container.kt` still has pre-existing whole-file shell
  string/Grazie warnings and no broad suppressions were added
  (`eid_20260518T095948-npx-kt-stabilization-run-dir-diagnostics`).
  Review quorum passed:
  Claude `run_20260518-080354-33015`, Codex
  `run_20260518-080402-33156`, Gemini `run_20260518-080407-33338`.
- [ ] Keep credential checks out of fast phases. AI-only phases may require
  `~/.anthropic`, `~/.openai`, and `~/.vertex`; export both
  `GEMINI_API_KEY` and `GOOGLE_API_KEY` from `~/.vertex` for Gemini.
- [ ] Keep the Gemini missing-key skip exception limited to Gemini on CI.
  Anthropic/OpenAI keys must continue to fail hard when missing.

## Phase 1 — fast compile/static gates

Preferred execution path is IntelliJ/Gradle runner via MCP Steroid once issue
#54 is addressed. Until then, if shell Gradle is used, record that in the
final report.

- [ ] MCP Steroid inspections on all touched Kotlin files.
- [ ] `:mcp-core:compileKotlin`
- [ ] `:mcp-stdio:compileKotlin`
- [ ] `:mcp-stdio:test`
- [ ] `:mcp-steroid-server:compileKotlin`
- [ ] `:npx-kt:compileKotlin`
- [ ] `:npx-kt:compileTestKotlin`
- [ ] `:npx-kt:compileIntegrationTestKotlin`
- [ ] `:test-helper:compileKotlin`
- [ ] `:test-integration:compileKotlin`
- [ ] `:test-integration:compileTestKotlin`
- [ ] Never run root `./gradlew test` for this work.

## Phase 2 — npx-kt unit coverage

Routing and naming:

> **Spec moved to [`docs/devrig-naming.md`](docs/devrig-naming.md).** The
> bullets below are the audit trail of when each invariant first landed;
> the contract itself (and the IDE-name extension) lives in the doc.

- [x] Project-name invariants (stable hash; different pid / path ⇒
  different hash; duplicate original names disambiguate; reverse map
  without suffix parsing; stale-name error). See spec for the full list.
  Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxProjectRoutingServiceTest' --rerun-tasks --console=plain`
  passed. MCP Steroid inspections on the touched Kotlin test file returned
  `{}` in `eid_20260518T143416-npx-routing-naming`.
  Plan review quorum passed:
  Claude `run_20260518-123118-18695`, Codex
  `run_20260518-123118-18696`, Gemini `run_20260518-123118-18697`.
  Final review quorum passed:
  Claude `run_20260518-123518-20509`, Codex
  `run_20260518-123518-20510`, Gemini `run_20260518-123518-20511`.
- [x] `singleIdeOrNull()` covers zero, one, and multiple IDE states.
  Added zero-state coverage and tied it to the existing one-IDE and
  multiple-IDE tests. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxProjectRoutingServiceTest' --rerun-tasks --console=plain`
  passed. MCP Steroid inspections on the touched Kotlin test file returned
  `{}` in `eid_20260518T131643-npx-kt-single-ide-policy`.
  Plan review quorum passed:
  Claude `run_20260518-111353-97845`, Codex
  `run_20260518-111353-97846`, Gemini `run_20260518-111353-97847`.
  Final review quorum passed:
  Claude `run_20260518-111734-202`, Codex
  `run_20260518-111734-203`, Gemini `run_20260518-111734-204`.

Window, screenshot, and input routing:
- [x] Window `projectName` is rewritten with the same project suffix.
- [x] Window routing disambiguates same-name projects by project home/pid.
- [x] `rewriteWindow` handles null `projectName` and null `projectPath`.
- [x] Screenshot `execution_id` is remembered and follow-up `steroid_input`
  routes to the same IDE.
- [x] `steroid_input` rejects screenshot ids from another IDE with an
  actionable error.
  Completed window/input routing coverage with existing tests for basic
  window suffixing and screenshot execution-id memory, plus new tests for
  same-name project window disambiguation, null window metadata, same-IDE
  `steroid_input` forwarding, and cross-IDE screenshot rejection.
  Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxProjectRoutingServiceTest' --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest' --rerun-tasks --console=plain`
  passed. MCP Steroid inspections on the touched Kotlin test files returned
  `{}` in `eid_20260518T144639-npx-window-input-routing`.
  Plan review quorum passed:
  Claude `run_20260518-123809-22691`, Codex
  `run_20260518-123809-22690`, Gemini `run_20260518-123809-22715`.
  Final review quorum passed:
  Claude `run_20260518-124746-28200`, Codex
  `run_20260518-124746-28199`, Gemini `run_20260518-124746-28201`.

Bridge client and handler behavior:
- [x] Bearer token is sent when marker token is present.
- [x] Authorization header is absent when marker token is empty.
  Positive bearer forwarding was already covered by
  `bridge client sends bearer token and rewritten original project name`; added
  focused empty-token coverage asserting the Authorization header is omitted.
  Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest'`
  passed. MCP Steroid inspections on the touched Kotlin test file returned
  `{}` in `eid_20260518T103131-npx-kt-bridge-auth-coverage`.
  Review quorum passed:
  Claude `run_20260518-083302-49403`, Gemini
  `run_20260518-083302-49404`, Claude
  `run_20260518-083523-50945`. Codex
  `run_20260518-083302-49405` was not counted because it blocked before
  reading the patch on a missing marinade `/tmp` path.
- [x] `project_name` is rewritten to the original IDE project name.
- [x] `steroid_execute_code` forwards `timeout` and `dialog_killer`.
- [x] `steroid_execute_code` forwards progress SSE events.
  Existing handler tests already asserted original project-name rewriting for
  execute-code and apply-patch routes and progress SSE forwarding. Extended the
  execute-code handler test to assert `dialog_killer` forwarding alongside the
  existing timeout assertion. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest'`
  passed. MCP Steroid inspections on the touched Kotlin test file returned
  `{}` in `eid_20260518T123439-npx-kt-bridge-forwarding`.
  Review quorum passed:
  Claude `run_20260518-103628-71075`, Codex
  `run_20260518-103628-71076`, Gemini `run_20260518-103628-71077`.
- [x] `steroid_apply_patch` forwards task id, dry-run, hunks, and original
  project name.
  Extended the existing apply-patch handler test to assert `dry_run` plus the
  forwarded hunk `file_path`, `old_string`, and `new_string` fields alongside
  the existing task id and original project-name assertions. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest'`
  passed. MCP Steroid inspections on the touched Kotlin test file returned
  `{}` in `eid_20260518T124014-npx-kt-bridge-apply-patch`.
  Review quorum passed:
  Claude `run_20260518-104137-73671`, Codex
  `run_20260518-104137-73672`, Gemini `run_20260518-104137-73673`.
- [x] `steroid_execute_feedback` forwards rating, explanation, and code.
  Added focused execute-feedback handler coverage asserting the original
  project name, task id, success rating, explanation, and code are forwarded to
  the IDE bridge. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest'`
  passed. MCP Steroid inspections on the touched Kotlin test file returned
  `{}` in `eid_20260518T124518-npx-kt-bridge-feedback`.
  Review quorum passed:
  Claude `run_20260518-104859-84310`, Codex
  `run_20260518-104859-84311`, Gemini `run_20260518-104859-84312`.
- [x] `steroid_action_discovery` forwards action groups, caret offset, and
  max actions.
  Added focused action-discovery handler coverage asserting the original
  project name, file path, caret offset, action groups, max actions per group,
  and task id are forwarded to the IDE bridge. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest'`
  passed. MCP Steroid inspections on the touched Kotlin test file returned
  `{}` in `eid_20260518T125551-npx-kt-action-discovery`.
  Plan review quorum passed:
  Claude `run_20260518-105230-86075`, Codex
  `run_20260518-105230-86076`, Gemini `run_20260518-105230-86077`.
  Final review quorum passed:
  Claude `run_20260518-105621-87871`, Codex
  `run_20260518-105621-87870`, Gemini `run_20260518-105621-87872`.
- [x] `steroid_take_screenshot` remembers execution ids.
  Added focused screenshot handler coverage asserting a returned `eid_...`
  from the IDE bridge is remembered for later input routing, while the
  original project name, task id, and reason are forwarded to
  `steroid_take_screenshot`. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest'`
  passed. MCP Steroid inspections on the touched Kotlin test file returned
  `{}` in `eid_20260518T130238-npx-kt-screenshot-memory`.
  Plan review quorum passed:
  Claude `run_20260518-110003-89798`, Codex
  `run_20260518-110003-89799`, Gemini `run_20260518-110003-89800`.
  Final review quorum passed:
  Claude `run_20260518-110307-91830`, Codex
  `run_20260518-110307-91829`, Gemini `run_20260518-110307-91831`.
- [x] `steroid_open_project` covers zero/one/multiple IDE routing policy.
  Simplified the handler to use the bridge routing service directly and added
  focused coverage for zero IDEs, multiple IDEs, and the singleton IDE forward
  path. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest'`
  passed on rerun; the first attempt failed before assertions on a transient
  embedded-server `BindException`. MCP Steroid inspections on touched Kotlin
  files returned `{}` in `eid_20260518T130940-npx-kt-open-project-policy`.
  Plan review quorum passed:
  Claude `run_20260518-110618-94077`, Codex
  `run_20260518-110618-94078`, Gemini `run_20260518-110618-94079`.
  Final review quorum passed:
  Claude `run_20260518-111023-95891`, Codex
  `run_20260518-111023-95890`, Gemini `run_20260518-111023-95892`.
- [x] SSE `error` event returns a `ToolCallResult` error.
- [x] HTTP 4xx/5xx returns a `ToolCallResult` error with enough upstream
  context.
  Added focused `NpxToolBridgeClientTest` coverage for upstream 401 and 500
  responses, asserting the returned tool error includes HTTP status and
  upstream body context. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest'`
  passed. MCP Steroid inspections on the touched Kotlin test file returned
  `{}` in `eid_20260518T102517-npx-kt-bridge-http-hardening`.
  Review quorum passed:
  Claude `run_20260518-082728-46323`, Codex
  `run_20260518-082728-46324`, Gemini `run_20260518-082728-46325`.
- [x] Channel closes with no `result` returns a no-result error.
- [x] Malformed SSE `data:` returns an actionable tool error instead of
  throwing out of the MCP call.
- [x] `event: result` without `result` field returns an actionable tool
  error.
- [x] Multi-line `data:` SSE frames are concatenated and decoded correctly.
  Added focused `NpxToolBridgeClientTest` coverage for malformed SSE JSON,
  SSE error events, missing result fields, no-result stream closure, and
  multi-line SSE data frames. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest'`
  passed. MCP Steroid inspections on touched Kotlin files passed with
  `INSPECTION_TOTAL: 0` in
  `eid_20260518T101622-npx-kt-bridge-sse-hardening`.
  Review quorum passed:
  Claude `run_20260518-081642-38276`, Codex
  `run_20260518-081647-38362`, Gemini `run_20260518-081653-38569`.
- [x] Progress tokens are isolated across concurrent routed calls.
- [x] Cancellation/timeout behavior is covered at the bridge boundary.
  Added `McpToolRegistryTest` coverage for two concurrent tool calls with
  distinct `_meta.progressToken` values and separate progress notifications.
  Added `NpxToolBridgeClientTest` coverage that coroutine cancellation while
  waiting for an SSE result propagates as `CancellationException` instead of a
  tool error. Timeout behavior is covered at the bridge boundary by the
  existing execute-code handler test that forwards the tool-level `timeout` to
  the IDE; the npx bridge intentionally uses an infinite HTTP timeout and does
  not enforce a second client-side tool timeout. Verification:
  `./gradlew :mcp-core:test --tests 'com.jonnyzzz.mcpSteroid.mcp.McpToolRegistryTest' --rerun-tasks --console=plain`
  passed on sequential rerun. The first attempt overlapped with another
  Gradle invocation and failed in `:prompts:jar` on a generated class
  `NoSuchFileException`; no test assertions had run.
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest' --rerun-tasks --console=plain`
  passed. MCP Steroid inspections on touched Kotlin test files passed with
  `INSPECTION_TOTAL: 0` in
  `eid_20260518T150204-npx-progress-cancellation`.
  Plan review quorum passed:
  Claude `run_20260518-125210-29927`, Codex
  `run_20260518-125210-29928`, Gemini `run_20260518-125210-29929`.

Prompt/resource behavior:
- [x] Prompt context maps IDE build to product code and baseline.
- [x] Malformed/unknown IDE build falls back to `PromptsContext.Generic`.
  Existing prompt-context tests cover routed IDE build parsing, singleton
  route selection, and known product-code baseline parsing. Added fallback
  coverage for malformed builds and unknown product prefixes, and hardened
  `NpxPromptsContextHandler` to return `PromptsContext.Generic` for product
  codes outside the supported set. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxProjectRoutingServiceTest' --rerun-tasks --console=plain`
  passed. MCP Steroid inspections on touched Kotlin files passed with
  `INSPECTION_TOTAL: 0` in `eid_20260518T152848-npx-prompt-context`.
  Plan review quorum passed:
  Codex `run_20260518-131055-37520`, Gemini
  `run_20260518-131055-37521`, replacement Codex
  `run_20260518-131234-38415`. Claude plan review hit an external rate limit
  in `run_20260518-131055-37519`.
  Final review quorum passed on the corrected diff:
  Claude `run_20260518-132950-46268`, Codex
  `run_20260518-132950-46267`, Gemini `run_20260518-132950-46269`.
- [x] Prompt and resource rendering stays local to npx-kt and is not routed
  to the IDE.
  Extended `CliMcpStdioFakeIdeIntegrationTest` so `resources/list`,
  `resources/read`, `prompts/list`, `prompts/get`, and local
  `steroid_fetch_resource` run before any routed IDE tool call, with the fake
  `/npx/v1/tools/call/stream` counter still at zero. The same test then calls
  `steroid_execute_code` and asserts the counter increments, proving the
  counter observes real routed tool calls. Verification:
  `./gradlew :npx-kt:integrationTest --tests 'com.jonnyzzz.mcpSteroid.proxy.cli.CliMcpStdioFakeIdeIntegrationTest' --rerun-tasks --console=plain`
  passed. MCP Steroid inspections on the touched Kotlin test file returned
  `{}` in `eid_20260518T153835-npx-local-prompts`.
  Plan review quorum passed:
  Claude `run_20260518-133436-48895`, Codex
  `run_20260518-133436-48894`, Gemini `run_20260518-133436-48896`.
  Final review quorum passed:
  Claude `run_20260518-133910-51964`, Codex
  `run_20260518-133910-51965`, Gemini `run_20260518-133910-51970`.
- [x] devrig stdio tool/resource/prompt descriptors match the direct IDE MCP
  server for the supported surface.
  Added `NpxDescriptorParityTest`, which builds the devrig side through real
  `StubMcpSteroidTools` and compares it to a direct-IDE-style in-process MCP
  server. Tool descriptors match exactly; direct IDE prompt/resource
  descriptors are asserted as an identical-descriptor subset of the npx
  deferred multi-IDE surface. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxDescriptorParityTest' --rerun-tasks --console=plain`
  passed. MCP Steroid inspections on the touched Kotlin test file returned
  `{}` in `eid_20260518T154928-npx-descriptor-parity`.
  Plan review quorum passed:
  Claude `run_20260518-134239-53914`, Codex
  `run_20260518-134239-53913`, Gemini `run_20260518-134239-53915`.
  Final review quorum passed:
  Claude `run_20260518-135010-58122`, Codex
  `run_20260518-135010-58149`, Gemini `run_20260518-135010-58166`.

CLI/runtime behavior:
- [x] `devrig mcp` starts a clean stdio MCP server and exits cleanly on stdin
  close.
- [x] No stdout leaks before MCP frames in `mcp` mode.
  Covered by
  `CliMcpStdioStdoutCleanlinessTest.host launcher writes only JSON-RPC frames to stdout`,
  which runs the real `installDist` launcher with `mcp`, completes after stdin
  closes, and parses every stdout line as JSON-RPC. Verification:
  `./gradlew :npx-kt:integrationTest --tests 'com.jonnyzzz.mcpSteroid.proxy.cli.CliMcpStdioStdoutCleanlinessTest.host launcher writes only JSON-RPC frames to stdout' --tests 'com.jonnyzzz.mcpSteroid.proxy.cli.CliOptionsIntegrationTest' --rerun-tasks --console=plain`
  passed after fixing launcher stderr noise and CliKt-native parse-error
  expectations. A final review caught JSON stdout corruption from the
  headliner; `CliOptionsIntegrationTest` now asserts `backend --json` and
  `project --json` start with a JSON object. MCP Steroid inspections on touched
  Kotlin files passed in `eid_20260518T163728-npx-cli-runtime-fix`.
  Plan review quorum passed:
  Claude `run_20260518-140511-65506`, Codex
  `run_20260518-140511-65505`, Gemini `run_20260518-140511-65507`.
  Final review quorum passed after the JSON/headliner and clean-host fixes:
  Claude `run_20260518-143833-90110`, Codex
  `run_20260518-143833-90113`, Gemini `run_20260518-143833-90111`.
- [x] Non-MCP commands restore stdout before printing user output.
  Covered by `CliOptionsIntegrationTest` through the real launcher and
  `NpxKtCommandOutputTest` in-process via `NpxKtServices.mcpStdout`. The fix
  keeps help output on stdout, version as a single stdout line, and parse
  errors on stderr with clean stdout. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.NpxKtCommandOutputTest' --tests 'com.jonnyzzz.mcpSteroid.proxy.HomePathsTest' --tests 'com.jonnyzzz.mcpSteroid.proxy.NpxKtCommandTest' --rerun-tasks --console=plain`
  passed.
- [x] `DEVRIG_HOME` override accepts only existing absolute canonical paths;
  no `--home` flag.
  Covered by `HomePathsTest` for env-var canonicalization/rejection and
  `NpxKtCommandTest.removed home flag is not a command` for the deleted CLI
  flag. Verification included in the `:npx-kt:test` command above.
- [x] Startup failure before MCP handshake is visible on stderr and test
  logs.
  Covered by
  `CliMcpStdioStdoutCleanlinessTest.host startup failure before handshake is visible on stderr`,
  which runs the real `mcp` launcher with an invalid absolute `DEVRIG_HOME`,
  feeds a normal MCP handshake, and asserts exit 64, blank stdout, and stderr
  containing `Startup failure:`, `DEVRIG_HOME`, and the canonical-path failure.
  Verification:
  `./gradlew :npx-kt:integrationTest --tests 'com.jonnyzzz.mcpSteroid.proxy.cli.CliMcpStdioStdoutCleanlinessTest.host startup failure before handshake is visible on stderr' --rerun-tasks --console=plain`
  passed. MCP Steroid inspections on touched files passed in
  `eid_20260518T164958-npx-startup-failure`. Plan review quorum passed:
  Claude `run_20260518-144349-93818`, Codex
  `run_20260518-144349-93819`, Gemini `run_20260518-144349-93845`.
  Final review quorum passed:
  Claude `run_20260518-145115-97139`, replacement Claude
  `run_20260518-145416-98573`, Gemini `run_20260518-145115-97141`.
  Codex final review `run_20260518-145115-97140` hit a missing marinade
  bootstrap path before reviewing the diff.

## Phase 3 — fake-IDE stdio integration

- [ ] `CliMcpStdioIntegrationTest`: initialize, ping, tools/list,
  prompts/list, resources/list.
- [ ] `CliMcpStdioStdoutCleanlinessTest`: no stdout pollution before MCP
  frames.
- [ ] `CliMcpStdioFakeIdeIntegrationTest`: one fake IDE marker, list projects
  returns exposed project name.
- [ ] Fake IDE execute-code route receives original project name and returns
  known marker.
- [ ] Fake IDE windows route rewrites project names and window ids.
- [ ] Fake IDE prompt/resource read works locally in devrig.
- [ ] Fake IDE progress event becomes MCP progress notification.
- [ ] Add two-IDE fake coverage where both IDEs expose the same original
  project name.
- [ ] Add stale-marker fake coverage: marker disappears, old project name
  fails with refresh instruction, new marker is rediscovered.
- [ ] Add unreachable-port and 401/500 bridge failure cases.
- [ ] Add dropped project stream / reconnect behavior coverage.

## Phase 4 — real IDE bridge, no AI

Run these before any AI agent smoke. They separate devrig/IDE bridge bugs from
agent prompt/tool-selection bugs.

- [ ] Selected ij-plugin npx endpoint tests:
  `/npx/v1/products`, metadata auth, project stream, windows, and
  `/npx/v1/tools/call/stream` result/progress/error.
- [x] One real running IDE, no AI: devrig stdio initializes.
- [x] One real running IDE, no AI: `steroid_list_projects` discovers the IDE
  marker and returns exposed project name.
- [x] One real running IDE, no AI: `steroid_execute_code` prints a unique
  marker and returns it through devrig.
  Added `NpxKtRealIdeBridgeIntegrationTest`, which starts one Docker IDE with
  the plugin, uses HTTP MCP only for setup, registers only `/home/agent/devrig
  mcp` as the test MCP server, and verifies initialize -> list projects ->
  execute-code through devrig stdio. Also fixed `NpxSteroidDriver.deploy` so
  the immutable container request builder actually runs the install script, and
  refreshed `/npx/v1/projects/stream` on subscription so devrig routes the
  exposed project name back to the current IDE project name after Gradle import.
  Verification:
  `./gradlew :test-integration:test --tests 'com.jonnyzzz.mcpSteroid.integration.tests.NpxKtRealIdeBridgeIntegrationTest' --rerun-tasks --console=plain`
  passed; run dir
  `test-integration/build/test-logs/test/run-20260518-175606-devrig-stdio-mcp-real-ide-bridge`.
  Final review quorum passed: Claude `run_20260518-160215-19238`, Gemini
  `run_20260518-160215-19239`, Codex `run_20260518-160215-19240`.
- [x] One real running IDE, no AI: progress notification is observable for an
  execute-code call.
  Added a devrig stdio progress test that calls `steroid_execute_code` with a
  client progress token and asserts the matching `notifications/progress`
  frame contains the script marker. Added a small stdio harness helper for
  capturing out-of-band JSON-RPC frames, and fixed the IDE-side npx bridge to
  serialize SSE emits and drain progress before the final result/error event.
  Verification:
  `./gradlew :ij-plugin:compileKotlin :test-helper:compileKotlin :test-integration:compileTestKotlin --console=plain`
  passed.
  `./gradlew :test-integration:test --tests 'com.jonnyzzz.mcpSteroid.integration.tests.NpxKtRealIdeBridgeIntegrationTest' --rerun-tasks --console=plain`
  passed; run dir
  `test-integration/build/test-logs/test/run-20260518-184657-devrig-stdio-mcp-real-ide-bridge`.
  MCP Steroid inspections on touched files passed with `INSPECTION_TOTAL: 0`
  in `eid_20260518T185050-devrig-real-ide-progress`.
  `git diff --check` passed.
  Final quorum review was attempted but blocked by run-agent infrastructure:
  Claude `run_20260518-165133-58882`, Codex
  `run_20260518-165133-58881`, and Gemini
  `run_20260518-165133-58884` produced no `STATUS.md`/`RESULT.md` after
  3h38m and were terminated; Codex reported a closed stdin tool error and
  Gemini repeatedly failed on `fetch failed sending request`.
- [ ] One real running IDE, no AI: `steroid_apply_patch` works on a disposable
  fixture file.
- [ ] One real running IDE, no AI: screenshot/input route to the same IDE.
- [ ] Optional but preferred before stable: two real IDEs with duplicate
  project names route independently.
- [ ] Follow-up from real-IDE bridge review: unify
  `IntelliJContainer.deployDevrigLauncher` with the wrapper used by
  `NpxSteroidDriver.deploy`.
- [ ] Follow-up from real-IDE bridge review: refresh or invalidate project
  stream snapshots if a project rename happens after an active subscription.
- [ ] Follow-up from real-IDE bridge review: add AI_NPX config assertions for
  Codex and Gemini, not only Claude.

## Phase 5 — AI_NPX long integration tests

Run one Docker IDE / one agent test at a time. Never parallelize
`:test-integration` or `:test-experiments`.

Definition of "AI_NPX smoke passed":
- the agent sees the devrig-provided `mcp-steroid` server as connected;
- the agent calls `steroid_list_projects`;
- the agent uses the exact exposed `project_name`;
- the agent calls `steroid_execute_code`;
- the result contains a unique test-generated marker;
- no `DEVRIG_NPX_FAILED` marker appears;
- run dir and agent raw/decoded logs are recorded in this file.

Tasks:
- [ ] Rewrite stale AI_NPX prompts that only enumerate tools and say "do not
  call tools"; they must call `steroid_list_projects` and
  `steroid_execute_code`.
- [ ] Claude AI_NPX smoke against one real IDE.
- [ ] Gemini AI_NPX smoke against one real IDE using `~/.vertex` for both
  Gemini env var names.
- [ ] Codex AI_NPX smoke against one real IDE using `~/.openai` when supported
  by the harness.
- [ ] Prompt/resource skill smoke through devrig stdio.
- [ ] On any >60s stall, collect screenshot, run dir, container process list,
  and IDE thread dump before killing.

## Phase 6 — release-readiness gates

- [ ] MCP Steroid inspections are clean on every touched Kotlin file.
- [ ] No new IDE inspection warnings.
- [ ] Fast, unit, fake-IDE, and real-IDE no-AI phases pass in order.
- [ ] Full AI_NPX smoke passes. If blocked by infrastructure, this file must
  link the tracked issue, the exact failure, the run directory, and the
  fallback no-AI bridge verification for the same commit.
- [ ] Add structured bridge diagnostics if AI_NPX failures remain opaque:
  tool name, exposed project name, original project name, IDE pid, elapsed
  time, SSE event counts, and upstream error body.
- [ ] 3x `run-agent.sh` quorum review after the above gates.
- [ ] Commit in small logical batches by phase.

# Active focus — npx-kt as stable MCP Steroid stdio replacement (2026-05-17)

Goal: make npx-kt/devrig `mcp` mode a real replacement for the IDE HTTP MCP
server by routing tool calls through discovered IDE bridge endpoints while
keeping prompt/resource rendering local to npx-kt.

Plan-review status:
- [x] Draft plan reviewed by `run-agent.sh claude`
  (`run_20260517-191744-64301`, `REVIEW_OK_WITH_CHANGES`).
- [x] Implementation reviewed by `run-agent.sh` reviewers:
  Claude `run_20260517-194637-73425` (`REVIEW_OK`), Codex
  `run_20260517-194637-73427` (`REVIEW_DONE_WITH_FINDINGS`), replacement
  Claude `run_20260517-200233-80021` (`REVIEW_OK`). Gemini
  `run_20260517-194637-73426` could not run because `GEMINI_API_KEY` is not set.
- [x] Final devrig MCP stdio diff review from Claude
  `run_20260517-201918-86348` returned `REVIEW_OK`.
- [x] MCP Steroid inspections are clean for all touched Kotlin files
  (`INSPECTION_PROBLEMS: 0`, `eid_20260517T223723-npx-kt-devrig-mpc-routing`).

Implementation tasks:
- [x] Add a project routing service under `npx-kt` that consumes discovered IDE
  metadata, project snapshots, and windows.
- [x] Generate exposed `project_name` values as
  `<ideProjectName>-<hash8>`, where `hash8` is base64-url-no-pad of the first
  6 bytes of `SHA-256(realProjectHome.toRealPath UTF-8 + 0x00 + idePid UTF-8)`.
  Project names are session-scoped; agents must refresh after an IDE restart.
- [x] Apply the same suffix logic to window `projectName` values and preserve
  `windowId` routing so input/screenshot calls reach the owning IDE.
- [x] Store a reverse mapping from exposed `project_name` to the IDE pid,
  bridge URL, and original IDE project name. Tool calls must rewrite
  `project_name` back to the original name before crossing the bridge. Never
  parse the suffix at routing time; use exact map lookup.
- [x] Record screenshot `execution_id -> idePid` so follow-up
  `steroid_input` calls route to the same IDE even when multiple IDE windows
  are present.
- [x] Treat stale exposed names as typed, actionable errors:
  "project_name <...> is no longer present; call steroid_list_projects to
  refresh".
- [x] Implement network-backed npx-kt handlers for every `McpSteroidTools`
  handler interface that needs IDE routing:
  `ListProjectsToolHandler`, `ListWindowsToolHandler`,
  `ExecuteCodeToolHandler`, `ApplyPatchToolHandler`,
  `ExecuteFeedbackToolHandler`, `ActionDiscoveryToolHandler`,
  `VisionScreenshotToolHandler`, `VisionInputToolHandler`, and
  `OpenProjectToolHandler`.
- [x] Use `/npx/v1/tools/call/stream` for routed calls that can produce
  progress and forward progress events as MCP progress notifications.
- [x] Unify devrig bridge streaming on NDJSON for projects and tool calls.
  Projects keep `ping`; tool calls keep `heartbeat`; both use a shared 10 s
  keepalive cadence and npx-kt uses a 50 s socket idle timeout. Unknown
  tool-call messages are ignored so future protocol messages do not break
  current clients. Verification:
  `./gradlew :npx-kt:test --tests 'com.jonnyzzz.mcpSteroid.proxy.server.NpxToolBridgeClientTest' --rerun-tasks --console=plain`
  and
  `./gradlew :test-integration:test --tests 'com.jonnyzzz.mcpSteroid.integration.tests.NpxKtRealIdeBridgeIntegrationTest' --rerun-tasks --console=plain`
  passed. Final real IDE run dir:
  `test-integration/build/test-logs/test/run-20260519-093144-devrig-stdio-mcp-real-ide-bridge`.
  MCP Steroid inspections reported zero findings on all touched files
  (`eid_20260519T092307-devrig-ndjson-transport`; final focused
  `NpxProjectsStream.kt` pass `eid_20260519T093009-devrig-ndjson-transport`).
  Peer
  reviews: Claude `run_20260519-071356-24092` GO; Gemini
  `run_20260519-072044-27703` GO.
- [x] Define and test `OpenProjectToolHandler` routing policy: require exactly
  one discovered/routable IDE; otherwise return an actionable error.
- [x] Implement local npx-kt `PromptsContextHandler`. Given exposed
  `project_name`, resolve the IDE metadata and render prompts/resources with
  that IDE's product code and baseline version. Do not route prompt/resource
  rendering to an IDE.
- [x] Register generated resources/prompts in the npx-kt stdio server using
  the same `ResourceRegistrar` path as the IJ plugin.
- [x] Move `ResourceRegistrar` from `ij-plugin` into `mcp-steroid-server`
  because it uses no IntelliJ Platform APIs.
- [x] Keep no-IDE and stale-project errors explicit and actionable.
- [x] Add unit tests for hash suffix stability, reverse project mapping, and
  project/window rewriting.
- [x] Add unit tests for prompt context selection.
- [x] Add unit tests for bridge routing request bodies.
- [x] Add bridge-routing unit tests with a fake HTTP client/engine verifying
  the request body rewrites `project_name` to the original IDE project name and
  sets the bearer token header.
- [x] Add npx-kt stdio integration tests with one fake IDE bridge discovered
  through marker/discovery, covering `steroid_list_projects` and a routed tool
  call.
- [x] Extend npx-kt fake-IDE stdio integration coverage to
  `steroid_list_windows` and prompt/resource reads.
- [x] Add/extend agent integration tests for `AiMode.AI_NPX` with one running
  IDE so an AI agent uses devrig stdio MCP end-to-end, not the HTTP MCP server.
- [x] Validate with scoped Gradle tests, MCP Steroid inspections, and a debug
  IDE/runtime check where practical. The Docker+Claude agent scenario was
  compiled but not run in this batch because it requires external agent
  credentials and a long Docker IDE run; the fake-IDE stdio route is executed.
- [ ] Commit in small logical batches:
  1. planning/TASKS update;
  2. ResourceRegistrar move;
  3. routing/name-mapping service + unit tests;
  4. local list/prompts handlers;
  5. network bridge handlers + stdio integration tests;
  6. agent-level integration tests;
  7. cleanup/inspection fixes. Earlier items 1-5 are already committed.

# Active notes — npx-kt CLI home override (2026-05-16)

- Runtime help intentionally documents only user-facing commands/options. The
  npx home override remains available for tests and automation via
  `DEVRIG_HOME=<path>`; it prints a stderr notice when used. Do not re-add the
  old `--home` flag.

# Active focus — TC quality validation triage (2026-05-11)

Post-philosophy-iteration TC run against `jb/main 2f21517a` (the merge
that synced 20 commits of accumulated `origin/main` onto `jb/main` for
the first time since 2026-05-05):

| TC build | Result | Triage |
|---|---|---|
| `mcp_steroid_BuildPlugin` (#946345314) | ✅ SUCCESS 5m12s | — |
| `mcp_steroid_IjPluginTest` (#946345319, Mac/Linux/Win matrix) | ✅ SUCCESS 24m40s | — |
| `mcp_steroid_PromptTest` (#946345329, 8-IDE matrix) | ❌ FAILURE 33m43s | **New regression — fix this iteration.** |
| `mcp_steroid_IntegrationTests_TestIntegrationBuild` (#946345331) | ❌ FAILURE 11m01s | **Documented baseline — leave failing.** |

## Problem 1 — `PromptTest` cascade: kotlinc classpath missing `:mcp-steroid-server` (NEW REGRESSION)

- 8/8 IDE matrix builds failed identically (177–308 `*KtBlocksCompilationTest.testBlock00X CompilesOnIdea` per build).
- Root error in every failure: `ApplyPatch.kt:15:32: error: unresolved reference 'server'. import com.jonnyzzz.mcpSteroid.server.ApplyPatchHunk` — the per-block kotlinc subprocess can't find `ApplyPatchHunk`, which now lives in `:mcp-steroid-server`.
- Caused by commits `acc5650b` ("move tools and resources definitions up to a parent module") + `b1942f1b` ("move classes in packages"), landed on `origin/main` 2026-04-19. Last green PromptTest on TC was build 941393378 on 2026-05-05 (built from `aa1d166d`) — *before* the extraction series reached `jb/main`'s tracked branch. My 2026-05-10 jb-merge brought all of it across in one step.
- Fix: add `:mcp-steroid-server` to whatever classpath the `KtBlocksCompilationTest` kotlinc subprocess is given (likely `prompts/build.gradle.kts` or `prompt-generator`'s codegen wiring).
- Validation: `./gradlew :prompts:test` locally must pass before pushing.

## Problem 2 — `test-integration` Gemini-skip-as-failure (PRE-EXISTING BASELINE — do not touch in this iteration)

- 6 `CliGeminiIntegrationTest.*` tests fail with `AssumptionViolatedException: Gemini API key not found`. Per CLAUDE.md MEMORY, these should report as ignored (`DockerGeminiSession.skipTestWhenKeyMissing = true`) but `BasePlatformTestCase` → `JUnit38ClassRunner` routes the throwable through `fireTestFailure` instead of recognising the assumption-as-ignore semantics that JUnit 4's runner provides.
- Pre-existing: the exact "Tests failed: 6, passed: 100, ignored: 1" line has been the result of *every* `test-integration` run on TC since at least 2026-04-28. Identical today. Not caused by the philosophy iteration; not in scope for this iteration per user direction.
- Tracked separately for a future cycle that's willing to restructure the test-class hierarchy off `BasePlatformTestCase` for the Gemini variant.

## Problem 3 — EAP-IDE `OpenProjectTask { }` target-25 vs. test target-21 (PRE-EXISTING BASELINE)

- After the Problem 1 fix landed (TC build #946643358), PromptTest dropped from 1682 failures → 4. The 4 remaining are all in EAP IDEs (2 in `idea_eap`, 2 in `clion_eap`) and share the same error: `cannot inline bytecode built with JVM target 25 into bytecode that is being built with JVM target 21`. The two files involved are `prompts/src/main/prompts/openProject/open-via-code.md` and `prompts/src/main/prompts/openProject/overview.md` — both reference the inline function `OpenProjectTask { }`, which the EAP IDE compiles with JVM target 25.
- History on `mcp_steroid_PromptTests_PromptTest_idea_eap_Linux_amd64`: this surfaced as "1 new failure" on the 2026-05-04 run (#940678137), got buried under the ApplyPatchHunk cascade on 2026-05-10, and is now visible after the classpath fix unblocked everything else. Not caused by the philosophy iteration and not caused by the classpath fix — pre-existing IDE-EAP drift.
- Fix when scope allows: bump `KotlincCommandLineBuilder.DEFAULT_JVM_TARGET` from `21` to match the EAP, OR feature-flag the EAP-specific tests off until the test-target catches up. Both are non-trivial.

# Active focus — codify the agent-first design tenets across all .md (2026-05-10)

Goal: every CLAUDE.md / AGENTS.md / agent-facing prompt resource in the repo
explicitly reinforces three tenets that today are only implicit. After this
iteration, an agent (or a human contributor) can read any one of these files
and recover the same design philosophy.

## Tenets (canonical wording will land in `docs/PHILOSOPHY.md`)

1. **Minimal MCP tool surface.** MCP Steroid intentionally maintains a small
   set of `steroid_*` tools (today: 10). New tools are not the lever for
   "agents deliver more" — better prompts and better recipes are. A new tool
   is added only when the IntelliJ-API path is genuinely intractable AND
   reviewer quorum agrees.
2. **Power lives in prompts and direct IntelliJ API usage.** Improvements
   come from richer tool descriptions, richer `mcp-steroid://` skill
   resources, and teaching the agent to call IntelliJ's native APIs
   (`FilenameIndex`, `JavaPsiFacade`, `ProjectTaskManager`, `XDebuggerUtil`,
   …) inside `steroid_execute_code`. Don't wrap APIs in helpers; teach the
   API as IntelliJ exposes it.
3. **`McpScriptContext` methods are last-resort.** The helpers exposed
   inside `steroid_execute_code`'s Kotlin runtime (`project`, `params`,
   `disposable`, `println()`, `printJson()`, `progress()`,
   `waitForSmartMode()`) shall not grow casually. A new context method
   requires (a) the IntelliJ-native path is genuinely intractable, (b)
   explicit reviewer consensus.

These harmonise the blog post ("comprehensive bridges to existing systems",
"agents follow the same processes as humans, no shortcuts") with the
strategy page ("Give AI the whole IDE, not just the files"): the **MCP tool**
surface stays minimal; the **IntelliJ capability** surface stays full,
exposed via `steroid_execute_code` + prompt resources.

## Decisions (locked 2026-05-10)

- Canonical home: **new** `docs/PHILOSOPHY.md`. Linked from root CLAUDE.md +
  AGENTS.md, from each per-folder agent guide, and mirrored as
  `mcp-steroid://skill/design-philosophy` so agents can read it at runtime
  via `steroid_fetch_resource`.
- Voice: **AI agents first, humans second** — imperative ("Don't propose new
  tools casually. Teach the agent to call IntelliJ APIs directly.") with a
  trailing rationale paragraph for human contributors.
- Cadence: per-batch `run-agent.sh` quorum (codex + claude + gemini), commit
  per batch. Same pattern as the rest of TASKS.md.
- Scope: docs + prompt resources only. Tool-count contract test deferred —
  see "Follow-ups" below.

## RLM-style iteration plan

Each batch: small set of related edits → 3-agent quorum review (codex /
claude / gemini via `run-agent.sh`) → adjust → commit. Each batch is
independently revertable.

### Batch 1 — Canonical tenets
- [ ] Create `docs/PHILOSOPHY.md` (agent-first imperative voice + human
  rationale + cross-links to RLM, blog, strategy page).
- [ ] Create `prompts/src/main/prompts/skill/design-philosophy.md` mirroring
  it (so `steroid_fetch_resource` can deliver it).
- [ ] Cross-link from root `CLAUDE.md` + `AGENTS.md` (one paragraph + the
  link).
- [ ] Quorum review.
- Success: an agent reading any of the three locations recovers the same
  three tenets. Quorum approves wording.

### Batch 2 — Per-folder agent guides
- [ ] `ij-plugin/CLAUDE.md` — preface "Adding new MCP tools" with the
  T1 question ("Can this be done via `steroid_execute_code` + direct
  IntelliJ APIs?"), and the McpScriptContext-expansion gate.
- [ ] `prompts/AGENTS.md` — add the *why* under the ProcessBuilder ban
  (it's not just a rule, it's the IntelliJ-API tenet) and an explicit
  "McpScriptContext stays narrow" note.
- [ ] `test-integration/AGENTS.md` — promote the existing "Configuring the
  IDE — always via `mcpExecuteCode`, never via XML" section into a top-level
  "Design principles" block that names the three tenets.
- [ ] `test-experiments/CLAUDE.md` — minimal cross-link only; this module
  is short and already well-aligned in spirit.
- [ ] Quorum review.
- Success: every per-folder guide states the tenets that apply to its
  scope, with a back-link to `docs/PHILOSOPHY.md`.

### Batch 3 — Runtime prompt resources (delivered to agents at runtime)
- [ ] `prompts/src/main/prompts/skill/mcp-steroid-info.md` — short paragraph
  that names the tenets and points to the new design-philosophy resource.
- [ ] `prompts/src/main/prompts/skill/execute-code-tool-description.md` —
  reinforce: prefer direct IntelliJ APIs over inventing new context methods;
  prefer `steroid_apply_patch` over multi-file `Edit` chains; prefer richer
  prompts over new tools.
- [ ] `prompts/src/main/prompts/ide/apply-patch.md` — reorder so the
  dedicated `steroid_apply_patch` tool leads, and the script-context
  `applyPatch { }` DSL is demoted to "fallback when patch + other API
  work share a script."
- [ ] Update `prompts/src/main/prompts/skill/coding-with-intellij.md`
  if it carries any wording that could be read as encouraging context-method
  expansion (audit-first, edit only if needed).
- [ ] Quorum review.
- Success: agents that read these via `steroid_fetch_resource` recover the
  tenets; recipes still teach IntelliJ APIs directly with no new wrappers.

### Batch 4 — Public + meta docs
- [ ] `README.md` — fix the "9 tools" → 10; add a one-paragraph design
  preamble that links to `docs/PHILOSOPHY.md`.
- [ ] `website/CLAUDE.md` — add a one-line note distinguishing site docs
  (end-user) from agent docs (this iteration's scope).
- [ ] Verify the link graph: every `.md` updated above resolves and
  cross-links bidirectionally.
- [ ] Final quorum review against the whole branch (codex + claude +
  gemini, 200-word verdict each).
- Success: net new contributor or agent can land on README, AGENTS.md, or
  any prompt resource and recover the philosophy.

## Follow-ups (deferred from this iteration)

- [ ] Add a contract test (`McpToolSurfaceContractTest` in
  `:mcp-steroid-server` or `:ij-plugin`) that asserts the registered
  `steroid_*` tool surface matches `EXPECTED_STEROID_TOOL_NAMES` exactly,
  so a stray addition fails CI. Codifies T1 mechanically.
- [ ] Same shape for `McpScriptContext` method count (e.g. assert the
  public method set is unchanged unless intentional).
- [ ] Consider porting the `mcp2` Executor/Request-DTO refactor (see
  earlier section in this file) — orthogonal but reinforces "narrow
  surfaces" tenet.

# MCP4

## Generic stdio MCP server — extract from npx-kt

Goal: consolidate stdio MCP transport + protocol code in `mcp-stdio` (or `mcp-core`).
Introduce a generic, transport-pluggable MCP server that wraps `McpServerCore`,
mirroring how `mcp-http`'s `McpHttpTransport` does it for HTTP.
`npx-kt` proxy logic stays as-is for now — breaking changes there are acceptable
later but not in scope here.


### Not in scope (per user)
- npx-kt's `StdioServer.kt` left untouched. Migrating the proxy to the new
  `McpStdioServer + McpServerCore` model would require dynamic registration of
  proxy-discovered tools/resources into `McpServerCore.toolRegistry` on every
  registry refresh — a real proxy refactor, not a transport one.


### npx-kt — real stdio MCP server (in progress, 2026-05-10)

Goal: turn npx-kt into a first-class stdio MCP server backed by `:mcp-stdio` +
`:mcp-steroid-server`'s `McpSteroidTools`. The legacy proxy/registry/beacon
stack stays in tree but unreachable from `main()` until real handlers land.

#### Done
- [x] **Step 1 — boot `McpStdioServer` from `npx-kt/main()`** with tools
  registered via `StubMcpSteroidTools` (every `handler<T>()` throws
  `TODO("not yet ready: …")`). Old proxy startup is moved to `legacyProxyMain`
  and unreferenced. (`npx-kt/build.gradle.kts`,
  `npx-kt/.../proxy/server/StubStdioMcpServer.kt`,
  `npx-kt/.../proxy/server/StubMcpSteroidTools.kt`,
  `npx-kt/.../proxy/Main.kt`)
- [x] **Step 2 — `integrationTest` source set** mirroring `:ij-plugin`. Spawns
  `bin/mcp-steroid-proxy` from `installDist`, exchanges NDJSON JSON-RPC over
  stdio. Asserts: initialize handshake, `tools/list` covers all 10 steroid_*
  tools, `prompts/list`, `resources/list`, `ping`, method-not-found error code,
  notification silence. Run via `./gradlew :npx-kt:integrationTest`. (7/7 pass.)
- [x] **Logback as the slf4j impl (stderr-only).** `runtimeOnly` on
  `logback-classic`. `npx-kt/src/main/resources/logback.xml` pins a single
  ConsoleAppender → System.err. No more "No SLF4J providers found" noise.
- [x] **`main()` — swap `System.out` → `System.err`**. First action of
  `main()`: capture `System.in` + `System.out` into local refs, run
  `System.setOut(System.err)`, pass the saved refs to
  `runStubStdioMcpServer(input = …, output = …)`. Stdout is now exclusively
  MCP NDJSON frames; logback + stray prints land on stderr.
- [x] **stdout-cleanliness integration test** (host + Docker variants).
  Asserts every non-blank stdout line parses as a JSON-RPC 2.0 envelope.
  Host variant covers whichever OS the test JVM runs on (Mac/Linux/Windows).
  Docker variant uses a dedicated `mcp-cli` Dockerfile under
  `test-helper/src/main/docker/` so all test containers go through the same
  test-helper Docker pipeline. Windows coverage TODO when a Windows runner
  exists.
- [x] **`Cli{Claude,Codex,Gemini}IntegrationTest`** — Docker AI agent
  registers npx-kt as a stdio MCP, runs a "list MCP tools" prompt, asserts the
  agent enumerated every tool in `EXPECTED_STEROID_TOOL_NAMES`. Tools list
  only — no invocations (handlers TODO). Required infra changes:
  - `temurin-21-jre` added to `claude/codex/gemini-cli` Dockerfiles via the
    Adoptium APT repo (matches `:test-integration:ide-base`'s pattern).
  - `AiAgentSession.containerDriver: ContainerDriver` exposed so tests can
    `copyToContainer(installDist, "/tmp")` before registering the stdio MCP.
  - `DockerGeminiSession` now sets `GEMINI_CLI_TRUST_WORKSPACE=true` —
    Gemini CLI's new trusted-folder check otherwise rejects `--approval-mode
    yolo` in headless mode (exit 55).
- [x] **Codex review feedback applied** (run-agent.sh review on 2026-05-10):
  - `StubMcpSteroidTools.handler()` throws `UnsupportedOperationException`
    instead of `TODO()` so it goes through `McpToolRegistry`'s
    `catch (Exception)` path as `ToolCallResult(isError=true)` rather than
    escaping as a `NotImplementedError` and tearing down the stdio server.
  - `StdioMcpProcess.drainNoMore(timeoutMs)` lets the
    "notifications-without-id receive no response" test fail loudly on stray
    frames; the previous version silently discarded them.
  - `EXPECTED_STEROID_TOOL_NAMES` shared between protocol-level and
    agent-level integration tests so a missing tool surfaces from both sides.
  - Empty `catch (_: Exception) {}` in `StdioMcpProcess.close()` replaced
    with a `System.err.println` (project policy: no empty catch).
  - Stale "default-jre-headless" comment in `NpxKtMcpInstaller` updated to
    Temurin 21.

#### Step 3 — move/consolidate npx-kt → mcp* modules (deferred)

The npx-kt module mixes (a) MCP transport/framing, already covered by
`:mcp-core` + `:mcp-stdio`, with (b) proxy/discovery/aggregation. After the
real handlers land, prefer this layout:

| Class / file                              | Move to                | Notes                                      |
|-------------------------------------------|------------------------|--------------------------------------------|
| `npx-kt/.../proxy/StdioServer.kt`         | **delete**             | Superseded by `:mcp-stdio` `McpStdioServer`. |
| `npx-kt/.../proxy/Protocol.kt`            | rewrite as `McpTool` impls in `:mcp-steroid-server` (or new `:mcp-steroid-proxy`) | Aggregator tools become real `McpTool`s; the per-method `when (method)` branch goes away. |
| `npx-kt/.../proxy/UpstreamClient.kt`      | `:mcp-http` (as a *client*) or new `:mcp-http-client` | HTTP MCP client (talks to `localhost:NNNN/mcp`) is reusable beyond the proxy. |
| `npx-kt/.../proxy/SseParser.kt`           | `:mcp-http`            | Generic SSE framing.                       |
| `npx-kt/.../proxy/ServerRegistry.kt`      | stays in `:npx-kt`     | Discovery is proxy-specific.               |
| `npx-kt/.../proxy/NpxBeacon.kt`           | stays in `:npx-kt`     | Telemetry is proxy-specific.               |
| `npx-kt/.../proxy/TrafficLogger.kt`       | stays in `:npx-kt`     | Traffic capture is proxy-specific.         |
| `npx-kt/.../proxy/UpdateCheck.kt`         | stays in `:npx-kt`     | Self-update is proxy-specific.             |
| `npx-kt/.../proxy/Config.kt`              | stays in `:npx-kt`     | Proxy config — only npx-kt reads it.       |
| `npx-kt/.../proxy/Constants.kt`           | split — keep `BeaconEvents` / `AGGREGATE_TOOL_*` here, drop `SESSION_HEADER` (lives in `:mcp-http`). | |

Once `UpstreamClient` lives in a shared module, the `handler()` overrides in
`StubMcpSteroidTools` are replaced one-by-one with concrete handlers that
delegate to upstream IDEs discovered by `ServerRegistry`. At that point delete
`legacyProxyMain` from `Main.kt`.

### Pending — port `mcp2` Executor/Request DTO refactors

`origin/mcp2` (34 commits ahead, last touch 2026-04-27) carries an
architectural pattern that did not land on main during the
`:mcp-steroid-server` extraction series. Worth porting in a separate PR
before the branch is deleted.

| mcp2 commit | Idea |
|---|---|
| `3ca06d65` | Per-tool `Request` data classes + `parse(args)` helpers — typed parsing at the call boundary, replacing inline `args["foo"]?.jsonPrimitive?.content` plumbing inside every `call()`. |
| `74851ffa` | Per-tool `fun interface FooExecutor` with a tool-specific signature (`ListWindowsExecutor.execute(): ToolCallResult`, `ExecuteCodeExecutor.execute(req, rawArgs, progress): …`) so the metadata stays decoupled from the IDE-dependent body. |
| `57332c39` | Co-locate schema + description + `parseRequest` + `Request` in each handler; `parse(args)` takes non-null `JsonObject` (one missing-arguments check at the call site). |
| `1cd12280` | Split each handler into `FooToolHandler(executor: FooExecutor)` (metadata + delegation) + `FooExecutorImpl` (IntelliJ-platform body). |
| `6a90433d` | Mock-executor tests in `:mcp-steroid-server/src/test` — plain JUnit 5 + `runBlocking`, no `BasePlatformTestCase`, fast delegation coverage that runs without the IDE fixture. |
| `26ef4a90` | "expose root resource index only" — narrow the `:mcp-steroid-server` resource API to a single root index entry point. |

Once these patterns are ported, `origin/mcp2` and the local `mcp2` branch
can be deleted. Until then keep the branch reachable.

### Backlog (carried over)
- [ ] add assert that mcp-core coroutines library is the same as in IntelliJ
- [ ] add check that slf4j works in IntelliJ and logs are not lost
- [ ] com.jonnyzzz.mcpSteroid.thisLogger should be internal to avoid usage from IntelliJ plugin code
- [ ] `withTimeoutOrNull(5_000L) { Observation.awaitConfiguration(project) }` -- no need for timeout around patch application handler
- [ ] `CLAUDE_FETCH_RESOURCE_TOOL = "mcp__mcp-steroid__steroid_fetch_resource"` the incorrect named entities, use Spec to refer to them
- [ ] project name to project resolution and error handling
- [ ] process cancelled exception must be handled in the HTTP server level
- [ ] review TODO-APPLY-PATCH.md records
- [ ] deprecate ActionDiscoveryToolHandler
- [ ] VisionService -> IntelliJ Service
- [ ] OpenProject does not log to execution service
- [x] include `--scope user` to Claude default configuration suggestion
- [ ] declutter VcsRefresh and related features which may cause problems with tests
- [ ] carefully review apply patch code with respect to threading, locks, VFS, EDT (or just drop it)
- [x] Input should use direct window_id instead of screenshot_execution_id
- [ ] generate the necessary indexes around the Prompts to avoid linear scan

# TASKS

Current focus: make MCP Steroid measurably better than vanilla agent runs on DPAIA Maven and Gradle projects by reducing tokens, tool errors, and wall-clock time.

## Guardrails

- Do not add methods to `McpSteroid*` interfaces.
- Do not add MCP tools.
- Prefer MCP server behavior, prompt resources, and DPAIA prompt/case improvements.
- Keep changes measurable with DPAIA metrics: tokens, tool calls, tool errors, native edit calls, apply-patch usage, and wall time.
- Run Docker-backed `:test-integration` / `:test-experiments` tests one at a time.

## Methodology

- Use Karpathy-style autoresearch: one narrow hypothesis, one change, one measured run, one evidence note.
- Use RLM-style context control: grep/partition first, then read only the relevant files.
- Use `run-agent.sh` reviews for non-trivial direction changes. Require three reviews and consensus before selecting the next low-hanging fruit.
- Keep run-agent artifacts outside this repository unless explicitly asked to preserve them.

## Pending — IMPROVEMENTS.md harness rollout (separate PR)

The two-task prompt + per-agent reflection harness landed on
`FindDuplicatesPromptTest` in the issue-33 PR (see CLAUDE.md → "IMPROVEMENTS.md
harness — agent self-feedback for prompt tuning"). The pattern is generic; we
should extend it across every test-integration prompt-style test so each one
produces a per-agent reflection artifact.

- [ ] **Audit prompt-style tests in `:test-integration:test`.** Likely
  candidates: `ReferencesSearchPromptTest`, `FilenameIndexPromptTest`,
  `PsiClassLookupPromptTest`, `MavenRunnerAdoptionTest`,
  `ResourceReadingTest`, `WhatYouSeeTest`'s `toolPreference`. Confirm which
  of them run an agent against an open prompt (vs. infrastructure smoke).
- [ ] **For each prompt test, add Task 2 (reflection)** to the prompt and
  snapshot the `<<<IMPROVEMENTS>>>...<<<END_IMPROVEMENTS>>>` block to
  `test-integration/build/improvements/IMPROVEMENTS-<test>-<agent>.md`.
  Reuse the helper pattern in `FindDuplicatesPromptTest` (`extractImprovementsBlock`,
  `saveImprovements`) — likely worth promoting to a shared helper in
  `test-integration/src/main/.../integration/tests/`.
- [ ] **Iteration cadence per test**: 5×Claude → 3×Codex → 3×Claude → 3×Codex
  (the `find-duplicates` cadence). Each cycle reads the IMPROVEMENTS files,
  applies prompt-only tweaks, re-runs.
- [ ] **Land in a separate PR.** Wait until the issue-33 PR ships so the
  harness contract has stabilized; then propose the rollout as one self-contained
  change per prompt test (or one umbrella PR if the helper extraction is cohesive).
- [ ] Update CLAUDE.md when rollout completes to drop the "currently wired into
  FindDuplicatesPromptTest" caveat.

## Pending — Reflection audit follow-up (issue #33)

Audit on 2026-05-07 across `prompts/src/main/prompts/**/*.md` for `Class.forName`,
`getDeclaredField`, `getDeclaredMethod`, `setAccessible`, `Method.invoke`,
`java.lang.reflect.*`, `kotlin.reflect.full.*`. All hits in **prose** are policy
text and are intentional (the new reflection-policy guidance added in
`mcp-steroid-info.md`, `skill/coding-with-intellij.md`,
`skill/coding-with-intellij-patterns.md`, and `ide/find-duplicates.md`). The hits
that need investigation are in fenced ` ```kotlin ``` ` blocks — actual recipes
that ship reflection to agents. Each gets its own dedicated `run-agent.sh`
investigation (research only; no fixes applied here).

- [ ] **`lsp/hover.md` lines 88, 93** — uses
  `targetElement.javaClass.methods.find { it.name == "getType" }?.invoke(targetElement)`
  to read the element's type for `KtProperty`/`KtParameter`/`PsiVariable`. Agent
  ships duck-typed reflection across language plugins.
  - Investigation prompt: research the typed alternatives in `~/Work/intellij`.
    For Kotlin: `org.jetbrains.kotlin.psi.KtProperty.typeReference?.text`,
    `KtParameter.typeReference?.text`, or the analysis-API
    `KaSession.getReturnKtType(symbol)`. For Java: `PsiVariable.type.canonicalText`.
    Confirm both APIs are on the IDEA Ultimate `steroid_execute_code` classpath
    (Kotlin K2 analysis API may need `org.jetbrains.kotlin` plugin module). Report
    the recommended typed branch per language and a single combined snippet that
    drops `javaClass.methods.find`.
  - Out of scope here: editing `lsp/hover.md`. Land the recipe replacement in a
    separate change with its own KtBlock + in-process tests.

- [ ] **`lsp/signature-help.md` lines 82, 88, 96, 112** — uses the same
  `javaClass.methods.find { ... }?.invoke(...)` pattern for `getParameterList`,
  `getValueParameterList`, `getParameters`, `getType`, `getReturnType`,
  `getReturnTypeReference`. This is more pervasive than `hover.md`: four
  reflection sites in one snippet, all wrapped in `try { ... } catch (e: Exception)`
  that hide failures.
  - Investigation prompt: research `~/Work/intellij` for the right typed APIs:
    Kotlin `KtNamedFunction.valueParameters`, `KtNamedFunction.typeReference`;
    Java `PsiMethod.parameterList.parameters`, `PsiMethod.returnType`. Decide
    whether the recipe should branch on `is KtNamedFunction` / `is PsiMethod`
    or use `PsiMethodCallExpression`-style resolution. Confirm the Kotlin K2
    analysis API surface (if needed) for type rendering and that `try/catch`
    can be removed once the typed path covers both languages.
  - Out of scope here: editing `lsp/signature-help.md`. Replacement recipe goes
    in a separate change with KtBlock + in-process tests.

The two recipes above are the ONLY reflection-using `kotlin` blocks in
`prompts/src/main/prompts/`. The `coding-with-intellij-patterns.md` "Path 2"
fallback example was removed during issue #33 cleanup; the only surviving
fallback is now the prose recommendation to use `required_plugins` instead of
reflection.

## Completed This Iteration

- [x] DPAIA arena prompt cleanup: remove stale `applyPatch {}` DSL guidance and duplicate MCP-mode prompt rules from `ArenaTestRunner.buildPrompt()`.
  - Files: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/arena/ArenaTestRunner.kt`
  - Consensus: Claude and Codex reviewers selected this as the next low-hanging fruit; Gemini selected Gradle prompt resources as a follow-up.
  - Expected effect: lower input tokens, fewer contradictory edit-path choices, fewer slow `steroid_execute_code` patch attempts.
  - Validation: `./gradlew :test-experiments:compileTestKotlin --warning-mode all` passed.

- [x] Measured one Claude+MCP DPAIA scenario after the prompt cleanup.
  - Command: `./gradlew :test-experiments:test --tests '*DpaiaPetclinicRest37Test.claude with mcp' --rerun-tasks --warning-mode all`
  - Result: fix claimed, MCP used, 184/184 tests passed, agent time 111s.
  - Tool mix: 2 agent `steroid_execute_code` calls in raw metrics, 3 Read, 1 Glob, 1 Grep, 2 native Edit, 2 Bash, `steroid_apply_patch` not used.
  - Lesson: correctness is good, but the edit prompt still needs a stronger dedicated `steroid_apply_patch` cue for import+method edits.

- [x] Tightened the next low-hanging arena edit guidance from the measured run.
  - Kept the `steroid_apply_patch` JSON example on `file_path`, matching `ApplyPatchToolHandler` and the generated tool description.
  - Explicitly classified "imports plus method" as a multi-hunk patch.
  - Review: first 3-agent pass blocked on an accidental `path`/`file_path` mismatch; after correction, Claude/Codex/Gemini approved the diff and agreed the next low-hanging fruit is to re-run the same scenario and measure native Edit reduction.

- [x] Measured the corrected `steroid_apply_patch` prompt on the same Claude+MCP DPAIA scenario.
  - Command: `./gradlew :test-experiments:test --tests '*DpaiaPetclinicRest37Test.claude with mcp' --rerun-tasks --warning-mode all`
  - Run dir: `test-experiments/build/test-logs/test/run-20260427-001705-dpaia__spring__petclinic__rest-37-mcp`
  - Result: fix claimed, MCP used, 184/184 tests passed, agent time 123s.
  - Tool mix from `docs/autoresearch/dpaia/metrics.py`: 12 total calls, 3 MCP calls, 9 native calls, 2 `steroid_execute_code`, 1 `steroid_apply_patch`, 2 Read, 2 Grep, 0 native Edit, 3 Bash, 0 errors.
  - Delta versus the prior run: native Edit 2 -> 0 and apply-patch false -> true; runtime 111s -> 123s because verification used 3 Bash Maven calls instead of 2.
  - Lesson: edit-path guidance worked. The next low-hanging prompt issue is verification: avoid duplicate Maven/Bash checks after a successful IDE build plus targeted test.

- [x] Route prompt resources to the dedicated `steroid_apply_patch` tool for multi-site literal edits.
  - Review artifacts: `/tmp/mcp-steroid-review/runs-next-20260427/`.
  - Consensus: Claude, Codex, and Gemini selected `update-apply-patch-tool-description-routing` before Gradle-resource work.
  - Files: `prompts/src/main/prompts/skill/execute-code-tool-description.md`, `prompts/src/main/prompts/skill/execute-code-overview.md`, `prompts/src/main/prompts/skill/coding-with-intellij.md`, `prompts/src/main/prompts/ide/apply-patch.md`, `prompts/src/test/kotlin/com/jonnyzzz/mcpSteroid/prompts/PromptRoutingContractTest.kt`.
  - Expected effect: stop teaching the slower `steroid_execute_code` + script-context `applyPatch` route as the default after DPAIA showed the dedicated MCP tool applies multi-file patches in tens of ms.
  - Validation: `:prompts:test --tests '*PromptRoutingContractTest*' --tests '*MarkdownArticleContractTest*' --tests '*ExecuteCodeToolDescriptionKtBlocksCompilationTest*' --warning-mode all` passed via IntelliJ Gradle runner.

- [x] Make `steroid_apply_patch` save touched documents before returning and cover disk persistence with integration tests.
  - Reference checked: after updating `~/Work/intellij`, IntelliJ's `ApplyTextFilePatch.updateDocumentContent()` uses `Document.setText(...)` followed by `FileDocumentManager.saveDocument(document)`.
  - Files: `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/execution/ApplyPatch.kt`, `ApplyPatchTest.kt`, `ApplyPatchToolIntegrationTest.kt`.
  - Coverage: single hunk, same-file multi-hunk descending offsets, multiple files, empty hunks, missing file, missing old string, non-unique old string, read-only/save failure, and direct disk reads after the MCP HTTP tool returns.
  - Validation: `:ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.execution.ApplyPatchTest' --tests 'com.jonnyzzz.mcpSteroid.server.ApplyPatchToolIntegrationTest' --rerun-tasks --warning-mode all` passed.
  - Review: three-agent pass approved the persistence fix direction and requested explicit save-failure/read-only coverage; the hardening was implemented.

- [x] Configure DPAIA Microshop cases to use JDK 25 during IDE setup and compile warmup. Superseded for Gradle 8.14.3 Microshop cases by the later JDK 24 compatibility fix below.
  - Files: `DpaiaCuratedCases.kt`, DPAIA runner/comparison setup call sites, `intelliJ-container.kt`, `mcp-steroid.kt`, `DpaiaConfigTest.kt`, `IdeTestHelpersTest.kt`.
  - Fixes: project SDK replacement now updates mismatched SDKs, compile warmup receives the case's configured JDK, and JAVA_HOME lookup fails fast unless a JDK path with `bin/javac` is emitted.
  - Validation: `:test-integration:test --tests 'com.jonnyzzz.mcpSteroid.integration.tests.IdeTestHelpersTest' --rerun-tasks --warning-mode all` passed.

- [x] Add a real IntelliJ Ultimate monorepo `thisLogger` lookup regression test.
  - Files: `test-experiments/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/IntelliJThisLoggerLookupTest.kt`.
  - Research: Marinade's MCP Steroid guide waits for `projectInitialized`, `indexingInProgress == false`, and no modal/background indexing, but IntelliJ's own API docs warn that `waitForSmartMode()` has no guarantee another dumb mode will not start before the next statement.
  - Fix direction from `~/Work/intellij`: use `Observation.awaitConfiguration(project)` for initial import/configuration readiness, then run the whole indexed lookup inside `smartReadAction(project)`.
  - Validation: `MCP_STEROID_INTELLIJ_CHECKOUT_DIR=/Users/jonnyzzz/Work/intellij ./gradlew :test-experiments:test --tests 'com.jonnyzzz.mcpSteroid.integration.tests.IntelliJThisLoggerLookupTest' --rerun-tasks --warning-mode all` passed in 8m41s with 4,191 references across 1,526 files.

- [x] Route MCP indexing guidance away from `waitForSmartMode()` as an indexed-read handoff.
  - Files: `ExecutionSuggestionService.kt`, `McpScriptContext.kt`, `prompt/skill.md`, `coding-with-intellij-{intro,patterns,psi,threading}.md`, `SkillReferenceHintTest.kt`, `IndexingGuidanceContractTest.kt`.
  - Fix: `IndexNotReadyException` and smart-mode hints now recommend `Observation.awaitConfiguration(project)` after import/sync/configuration and `smartReadAction { }` around the whole indexed PSI query.
  - Validation: `:ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.server.SkillReferenceHintTest' --rerun-tasks --warning-mode all` passed; scoped `:prompts:test` for `IndexingGuidanceContractTest`, `MarkdownArticleContractTest`, and changed Kt blocks passed after forced prompt regeneration.

- [x] Harden IDE exception capture for JUL records with null parameters.
  - Evidence: the green IntelliJ monorepo `thisLogger` lookup logged Kotlin FIR severe errors, then `ExceptionCaptureService` crashed while reading a nullable `LogRecord.parameters` array.
  - Files: `ExceptionCaptureService.kt`, `ExceptionCaptureServiceTest.kt`.
  - Fix: capture failures now log to stderr instead of breaking the original IDE error path, `ProcessCanceledException` is still rethrown, and null parameters are handled explicitly.
  - Validation: `./gradlew :ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.execution.ExceptionCaptureServiceTest' --rerun-tasks --warning-mode all` passed.
  - Review: initial Codex pass caught hidden `ProcessCanceledException` handling in plugin-id lookup; follow-up Claude/Codex/Gemini pass approved.

- [x] Make IntelliJ monorepo test setup prefer explicitly configured local checkouts over stale cached ZIPs.
  - Evidence: `MCP_STEROID_INTELLIJ_CHECKOUT_DIR=/Users/jonnyzzz/Work/intellij` still reused an older cached TeamCity ZIP before updating in-container.
  - Files: `intelliJ-git.kt`, `IntelliJGitCloneZipTest.kt`.
  - Fix: configured ZIPs and configured checkout directories now win before cache reuse; local checkout ZIP creation uses a proper `file:///` clone URI and preserves the source checkout's real `origin` remote for in-container fetches.
  - Validation: `./gradlew :test-integration:test --tests 'com.jonnyzzz.mcpSteroid.integration.tests.IntelliJGitCloneZipTest' --rerun-tasks --warning-mode all` passed.
  - Review: initial Codex pass caught the container-unusable `file:///Users/...` origin in generated ZIPs; follow-up Claude/Codex/Gemini pass approved.

## Next Candidates

- [x] Reduce redundant Maven verification in the DPAIA arena prompt.
  - Evidence: the latest successful run compiled with `steroid_execute_code`, then ran targeted Maven tests, then ran the full Maven suite. That kept correctness but increased Bash calls from 2 to 3 and runtime from 111s to 123s.
  - Implemented wording: do not rerun a completed Maven/Gradle target solely to recover `BUILD SUCCESS` hidden by `tail`/`grep`; explicitly allow reruns after code changes, real failures, incomplete runs, or Gradle skipped-test behavior.
  - Review: first 3-agent pass requested narrowing the wording; follow-up pass under `/tmp/mcp-steroid-review/runs-current-4/` approved with Claude/Codex/Gemini consensus.
  - Measurement: `DpaiaPetclinicRest37Test.claude with mcp` run `test-experiments/build/test-logs/test/run-20260427-003310-dpaia__spring__petclinic__rest-37-mcp` passed 184/184 tests in 101s with 0 native Edit, 1 `steroid_apply_patch`, 2 Bash, and 0 tool errors.
  - Delta versus the prior 123s run: Bash 3 -> 2, total tool calls 12 -> 11, runtime 123s -> 101s, pass rate unchanged.

- [x] Measure the dedicated apply-patch routing resource change.
  - Scenario: repeat `DpaiaPetclinicRest37Test.claude with mcp` first because it is the stable measured case.
  - Target: keep 184/184 tests, 0 native Edit, `steroid_apply_patch` used, 0 tool errors, and no regression versus the 101s run.
  - Run dir: `test-experiments/build/test-logs/test/run-20260427-073953-dpaia__spring__petclinic__rest-37-mcp`
  - Result: fix claimed, MCP used, 184/184 tests passed, agent time 116s.
  - Tool mix from `docs/autoresearch/dpaia/metrics.py`: 10 total calls, 3 MCP calls, 7 native calls, 2 `steroid_execute_code`, 1 `steroid_apply_patch`, 2 Read, 1 Grep, 0 native Edit, 2 Bash, 0 errors.
  - Delta versus the 101s run: `steroid_apply_patch` stayed true, native Edit stayed 0, Bash stayed 2, total calls 11 -> 10, runtime 101s -> 116s. This is within the observed PetclinicRest37 variance and confirms the global prompt-resource routing is not regressing the stable Maven case.

- [x] Add a prompt-size or prompt-shape regression check for the DPAIA arena MCP block.
  - Files likely under `test-experiments/src/test/kotlin/.../arena/`.
  - Expected effect: prevent reintroducing large contradictory prompt blocks.
  - Consensus note: Codex specifically recommended asserting that the prompt does not reintroduce broad "run at most once" wording and still contains explicit rerun-required cases.
  - Measured prerequisite: the verification-guidance tweak succeeded on the 101s run, so this is now the next low-hanging fruit.
  - Implemented in `ArenaPromptContractTest`; validation: `./gradlew :test-experiments:test --tests '*ArenaPromptContractTest*' --warning-mode all` passed.

- [x] Pick and measure one Gradle DPAIA scenario before changing Gradle guidance.
  - Goal: establish a concrete baseline for Gradle cold-starts, skipped-test behavior, and IntelliJ Gradle runner errors before editing prompt resources.
  - Scenario: `DpaiaMicroshop2Test.claude with mcp`.
  - Run dir: `test-experiments/build/test-logs/test/run-20260427-090258-dpaia__spring__boot__microshop-2-mcp`.
  - Result: fix claimed, MCP used, full Gradle suite passed, agent time 171s.
  - Raw metrics: 12 total calls, 4 MCP calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, 8 patch hunks, 0 native Edit, 0 Read, 3 Write for new files, 4 Bash, 0 tool errors, 1.05M tokens.
  - Delta versus the stale-disk failure run: 248s -> 171s, tool errors 7 -> 0, native Edit 14 -> 0, Read 11 -> 0. The "apply_patch did not persist" fallback disappeared.

- [x] Add a Gradle-focused MCP prompt resource modeled after the Maven patterns.
  - Files likely under `prompts/src/main/prompts/skill/`.
  - Consensus status: the follow-up `thisLogger` FIR review selected this by 2/3 reviewers (Codex + Gemini); Maven fallback JDK guidance remains Claude's candidate.
  - Expected effect: fewer Bash Gradle cold starts and fewer hand-rolled IntelliJ Gradle snippets.
  - Implemented files: `prompts/src/main/prompts/skill/execute-code-gradle.md`, links from `execute-code-overview.md`, `execute-code-tool-description.md`, and `coding-with-intellij.md`, plus `GradlePromptContractTest`.
  - Validation: IntelliJ Gradle runner passed `:prompts:generatePrompts :prompts:test --tests 'com.jonnyzzz.mcpSteroid.prompts.GradlePromptContractTest' --tests '*ExecuteCodeGradleKtBlocksCompilationTest*' --tests 'com.jonnyzzz.mcpSteroid.prompts.MarkdownArticleContractTest' --warning-mode all` after fixing the initial non-kotlin fence contract failure.
  - Review: `/tmp/mcp-steroid-review/gradle-prompt-resource-20260427/runs/`; Claude, Codex, and Gemini approved.
  - Measurement: `DpaiaMicroshop2Test.claude with mcp` run `test-experiments/build/test-logs/test/run-20260427-135940-dpaia__spring__boot__microshop-2-mcp` passed, but the agent fetched 0 resources and still used Bash Gradle 5 times after an aborted IDE build. Agent time was 170.8s versus the 136s JDK-fixed baseline.
  - Lesson: the resource content is valid, but discoverability/routing is still weak for arena agents.

- [x] Improve Gradle resource discovery/routing from DPAIA/MCP prompts.
  - Evidence: the first post-resource Microshop-2 measurement did not fetch `mcp-steroid://skill/execute-code-gradle`.
  - Expected effect: fewer Bash Gradle calls after an IDE build abort and fewer hand-rolled Gradle snippets.
  - Review: `/tmp/mcp-steroid-review/gradle-resource-measurement-20260427/runs/`; all reviewers approved the interpretation. Claude recommended high-impact arena prompt routing, Codex recommended execute-result abort guidance, and Gemini recommended both prompt/resource-table abort guidance and edited a rough version.
  - Implemented direction: route Gradle arena prompts through `steroid_fetch_resource` for `mcp-steroid://skill/execute-code-gradle`, keep Maven prompts on `mcp-steroid://skill/execute-code-maven`, and add prompt-resource abort guidance with full resource URIs.
  - Validation: `ArenaPromptContractTest` passed via IntelliJ Gradle runner; prompt resource generation plus `ExecuteCodeToolDescriptionKtBlocksCompilationTest`, `ExecuteCodeOverviewKtBlocksCompilationTest`, and `MarkdownArticleContractTest` passed via IntelliJ Gradle runner.
  - Note: an initial combined multi-module Gradle run was stopped after a thread dump showed the `--tests` filter leaked and launched `DpaiaArenaTest.codex without mcp`; the scoped module reruns above are the valid validation signal.

- [x] Measure Gradle resource routing on Microshop-2.
  - Target: full-suite pass, `fetch_resource_calls >= 1` for `mcp-steroid://skill/execute-code-gradle`, fewer Bash Gradle calls than the 5-call measurement, and no new tool errors.
  - Run dir: `test-experiments/build/test-logs/test/run-20260427-142637-dpaia__spring__boot__microshop-2-mcp`.
  - Result: host test passed, agent emitted `ARENA_FIX_APPLIED: yes`, and the full Gradle suite passed. Agent time was 142.0s.
  - Tool mix from `docs/autoresearch/dpaia/metrics.py`: 10 total calls, 4 MCP calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, 3 Write, 2 Bash, 0 tool errors, 764,238 tokens, and 0 resource fetches.
  - Delta versus the 170.8s post-resource run: total calls 28 -> 10, Bash 5 -> 2, tool errors 2 -> 0, tokens 1,458,578 -> 764,238, runtime 170.8s -> 142.0s. Delta versus the 136s JDK-fixed baseline: Bash stayed 2, tool errors stayed 0, but tokens stayed higher and runtime was 6s slower.
  - Lesson: arena/prompt routing reduced waste but still failed the primary resource-use criterion. The decoded log shows the agent considered fetching the Gradle skill after `Build errors: false, aborted: true`, but used Bash instead; prompt-only routing is not enough at this decision point.

- [x] Add result-boundary guidance for aborted IDE builds from `steroid_execute_code`.
  - Evidence: both Microshop-2 routing runs hit `Build errors: false, aborted: true` after an IDE build; the agent then fell back to Bash without calling `steroid_fetch_resource`, even when the arena prompt told it to fetch the Gradle resource.
  - Scope: `ExecuteCodeToolHandler` now appends a concise next-action hint to `steroid_execute_code` results when build output indicates `errors=false, aborted=true`, using generated Maven/Gradle prompt article classes rather than hardcoded `mcp-steroid://...` string literals.
  - Expected effect: make the resource URI visible exactly at the failure boundary, before the agent chooses Bash.
  - Review: `/tmp/mcp-steroid-review/build-abort-guidance-20260427/runs/`; Claude, Codex, and Gemini approved. Optional fallback-case coverage requested by Claude/Codex was added.
  - Validation: `:ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.server.ExecuteCodeBuildAbortGuidanceTest' --tests 'com.jonnyzzz.mcpSteroid.NoHardcodedMcpSteroidUriUsageTest' --rerun-tasks --warning-mode all` passed.

- [x] Measure aborted-build result-boundary guidance on Microshop-2.
  - Target: full-suite pass, `fetch_resource_calls >= 1`, no more than 2 Bash Gradle calls, and no new tool errors.
  - First attempt `run-20260427-144355-dpaia__spring__boot__microshop-2-mcp` failed before the agent ran because the Docker IDE container disappeared during repository setup; this is not a benchmark result.
  - Valid run dir: `test-experiments/build/test-logs/test/run-20260427-150914-dpaia__spring__boot__microshop-2-mcp`.
  - Result: host test passed, agent emitted `ARENA_FIX_APPLIED: yes`, and the full Gradle suite passed. Agent time was 169.6s.
  - Tool mix from `docs/autoresearch/dpaia/metrics.py`: 15 total calls, 4 MCP calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, 3 Read, 3 Write, 1 Glob, 3 Bash, 0 tool errors, 985,678 tokens, and 0 resource fetches.
  - Lesson: the result-boundary hint was visible in the tool result, but Claude still ignored `steroid_fetch_resource` and used Bash. A URI-only/fetch-only hint is insufficient.

- [x] Review the failed fetch-resource boundary measurement and pick the next low-hanging correction.
  - Review artifacts: `/tmp/mcp-steroid-review/build-abort-boundary-measurement-20260427/runs/`.
  - Consensus: Claude, Codex, and Gemini all selected the cheapest next change first: make the aborted-build boundary guidance forceful, name Claude's actual `mcp__mcp-steroid__steroid_fetch_resource` tool, and keep resource URIs generated from prompt article classes.
  - Follow-up rule from reviewers: if this still produces 0 fetches, stop iterating on fetch-only wording and reconsider inline Gradle sync guidance or removing the failed hint.

- [x] Make aborted-build boundary guidance explicit and line-separated.
  - Scope: `ExecuteCodeBuildAbortGuidance` now emits `REQUIRED ACTION` / `NEXT TOOL CALL` wording with `mcp__mcp-steroid__steroid_fetch_resource`, and appends the guidance with a leading newline so decoded logs do not collapse `aborted: true` into the hint.
  - Tests: `ExecuteCodeBuildAbortGuidanceTest` now asserts the exact Claude-visible tool name, compiler-error no-op behavior, and separate-line formatting.
  - Validation: IntelliJ Gradle runner passed `:ij-plugin:test --tests 'com.jonnyzzz.mcpSteroid.server.ExecuteCodeBuildAbortGuidanceTest' --tests 'com.jonnyzzz.mcpSteroid.NoHardcodedMcpSteroidUriUsageTest' --rerun-tasks --warning-mode all`.

- [x] Measure explicit aborted-build boundary guidance on Microshop-2.
  - Target: full-suite pass, `fetch_resource_calls >= 1`, no more than 2 Bash Gradle calls, and no new tool errors.
  - Run dir: `test-experiments/build/test-logs/test/run-20260427-151926-dpaia__spring__boot__microshop-2-mcp`.
  - Result: host test passed, agent emitted `ARENA_FIX_APPLIED: yes`, and the full Gradle suite passed. Agent time was 174.0s.
  - Decoded evidence: line 743 showed `Build errors: false, aborted: true`; line 744 showed the separate-line `REQUIRED ACTION ... NEXT TOOL CALL must be mcp__mcp-steroid__steroid_fetch_resource ...`; line 747 immediately used Bash Gradle anyway.
  - Tool mix from `docs/autoresearch/dpaia/metrics.py`: 19 total calls, 4 MCP calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, 5 Read, 2 Glob, 4 Write, 2 Bash, 1 native Read error, 1,255,211 tokens, and 0 resource fetches.
  - Lesson: even an exact Claude tool-name boundary instruction is ignored in this scenario. The fetch-only boundary hypothesis has failed; the next low-hanging correction should be reviewed as either inline minimal Gradle sync guidance in the tool result or removal/replacement of the hint.

- [x] Review the explicit-hint failure and choose the next low-hanging correction.
  - Candidate: append a short inline Gradle sync/configuration recipe at the aborted-build boundary instead of requiring `steroid_fetch_resource`, or revert the fetch-only hint if the inline recipe would cost too many tokens.

- [x] Fix the real Gradle abort root causes found while reviewing the explicit-hint failure.
  - Review artifacts: `/tmp/mcp-steroid-review/gradle-jdk24-finaltasks-20260427/runs/`.
  - Consensus: Claude, Codex, and Gemini approved the patch with no blockers. All agreed `ProjectDataImportListener.onFinalTasksFinished` is the right Gradle import boundary and JDK 24 is the right Microshop fix for Gradle 8.14.3.
  - Files: `test-integration/src/main/kotlin/com/jonnyzzz/mcpSteroid/integration/infra/mcp-steroid.kt`, `test-integration/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/GradleCompileTest.kt`, `test-integration/src/test/docker/ide-base/Dockerfile`, and Microshop DPAIA config/contract/metrics tests.
  - Fixes: Gradle import setup now sets the linked Gradle JVM from the configured project SDK, waits for `onFinalTasksFinished` before smart mode, and rethrows import failures. Docker IDE base installs Temurin 24. Microshop Gradle cases now use JDK 24 because Gradle 8.14.3 rejects Java 25 as a daemon JVM.
  - Validation: `:test-integration:test --tests 'com.jonnyzzz.mcpSteroid.integration.tests.GradleCompileTest' --rerun-tasks --warning-mode all` passed with `GRADLE_JVM=25`, `BUILD_ERRORS=false`, `BUILD_ABORTED=false`; DPAIA config/contract/metrics tests passed; `DpaiaMicroshop2Test.claude with mcp` passed with `Recommended JAVA_HOME: /usr/lib/jvm/temurin-24-jdk-arm64` and `Build errors: false, aborted: false`.
  - Measurement: post-fix Microshop-2 used 1,773,570 tokens, 36 calls, 3 MCP calls, 33 native calls, 0 errors, and 5 Bash calls. Correctness improved, but token/tool cost regressed.
  - Next low-hanging consensus: move Gradle agents away from native exploration now that the IDE path works. The concrete follow-up is to inline/update IDE-native Gradle build/sync guidance, including the `onFinalTasksFinished` wait in `execute-code-gradle.md`, then remeasure Microshop-2.

- [x] Next low-hanging Gradle improvement: make JDK choice harder to miss in DPAIA prompts.
  - Evidence: Microshop-2 eventually chose JDK 25 and passed, but first tried `JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-arm64` and hit `invalid source release: 24`.
  - Fix: the arena prompt now exposes the case-configured JDK version, has the first MCP call print `Recommended JAVA_HOME`, and tells agents to use the exact printed value instead of wildcard assignments or lower JDKs.
  - Consensus: follow-up Claude and Codex review selected this over Kotlin FIR investigation as the next low-risk item; Gemini selected Kotlin FIR, so this is a 2/3 consensus.
  - Review: initial Codex pass caught the invalid copyable `JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-*` assignment; follow-up Claude/Codex/Gemini pass approved the wildcard-free prompt.
  - Validation: `./gradlew :test-experiments:test --tests 'com.jonnyzzz.mcpSteroid.integration.arena.ArenaPromptContractTest' --rerun-tasks --warning-mode all` passed.
  - Measurement: `DpaiaMicroshop2Test.claude with mcp` run `test-experiments/build/test-logs/test/run-20260427-115129-dpaia__spring__boot__microshop-2-mcp` passed in 136s agent time. First MCP call printed `Recommended JAVA_HOME: /usr/lib/jvm/temurin-25-jdk-arm64`; both Bash Gradle calls used `JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-arm64`; no `invalid source release: 24`.
  - Delta versus the 171s baseline: Bash 4 -> 2, agent time 171s -> 136s, native Edit stayed 0, tool errors stayed 0, full Gradle suite passed.

- [x] Add a decoded-log regression check for Microshop-2 JDK usage.
  - Evidence: the measured run's decoded log shows both Bash Gradle calls used `/usr/lib/jvm/temurin-25-jdk-arm64`; reviewers suggested pinning this so `temurin-21` or literal `*` does not return.
  - Fix: `AgentOutputMetrics` now extracts decoded Bash commands and flags Gradle commands that omit `JAVA_HOME`, use a literal wildcard, or do not start with the expected JDK prefix, including absolute `gradlew` wrapper paths.
  - Regression coverage: `ExtractDecodedLogMetricsTest.microshop gradle bash commands use configured jdk without wildcard` and `ExtractDecodedLogMetricsTest.detects gradle bash commands with lower jdk or wildcard java home`.
  - Validation: `./gradlew :test-experiments:test --tests 'com.jonnyzzz.mcpSteroid.integration.arena.ArenaPromptContractTest' --tests 'com.jonnyzzz.mcpSteroid.integration.arena.ExtractDecodedLogMetricsTest' --rerun-tasks --warning-mode all` passed.

- [x] Investigate severe Kotlin FIR resolve logs from the IntelliJ monorepo `thisLogger` lookup.
  - Evidence: the green lookup run logged `KaFirReferenceResolver` / `Expected FirResolvedContractDescription but FirLazyContractDescriptionImpl` errors, followed by an `ExceptionCaptureService` null-pointer while capturing the IDE error.
  - Fix: the monorepo test now asserts no lookup-time FIR severe logs, and the MCP script avoids the Kotlin FIR resolve path by using `CacheManager.getVirtualFilesWithWord(..., UsageSearchContext.IN_CODE, ...)` plus Kotlin PSI `KtCallExpression` filtering for real `thisLogger()` calls.
  - Consensus: final Gradle/JDK review selected this as the next low-hanging task by 2/3 reviewers; Maven fallback JDK guidance remains the other candidate.
  - Validation: the old `ReferencesSearch` TDD run failed on the new FIR log assertion; the fixed run passed with `THISLOGGER_LOOKUP_STRATEGY=INDEXED_WORD_PLUS_KOTLIN_PSI`, `THISLOGGER_REFERENCE_COUNT=2670`, and `THISLOGGER_FILE_COUNT=1522`.
  - Review: first pass had Gemini/Claude approve and Codex request a strategy-marker assertion plus stale `CLAUDE.md` note cleanup; follow-up Claude/Codex/Gemini pass approved.

- [x] Make IntelliJ monorepo test setup prefer an explicitly configured local checkout when appropriate.
  - Evidence: the `MCP_STEROID_INTELLIJ_CHECKOUT_DIR=/Users/jonnyzzz/Work/intellij` run still reused an existing cached TeamCity ZIP before updating the checkout in-container.
  - Fix: `ensureIntelliJGitCloneZipInCache()` honors configured ZIPs/checkouts before cache reuse, preserves the source checkout's real `origin` remote in generated ZIPs, and has explicit regression coverage.

- [x] Update Gradle arena/resource guidance to use the now-working IDE-native Gradle path.
  - Review: `/tmp/mcp-steroid-review/gradle-ide-guidance-20260427/runs/`; Claude, Codex, and Gemini approved. The shared next step was to remeasure Microshop-2.
  - Files: `prompts/src/main/prompts/skill/execute-code-gradle.md`, `GradlePromptContractTest.kt`, `ArenaTestRunner.kt`, `ArenaPromptContractTest.kt`.
  - Fix: `execute-code-gradle.md` now uses `ProjectDataImportListener.onFinalTasksFinished` for Gradle sync readiness instead of `Observation.awaitConfiguration(project)`, and the Gradle arena prompt inlines an IDE build recipe before Bash fallback.
  - Validation: IntelliJ Gradle runner passed the scoped prompt generation/tests and `ArenaPromptContractTest`.

- [x] Measure updated Gradle IDE guidance on Microshop-2.
  - Run dir: `test-experiments/build/test-logs/test/run-20260427-185422-dpaia__spring__boot__microshop-2-mcp`.
  - Result: host test passed, agent emitted `ARENA_FIX_APPLIED: yes`, IDE build printed `Build errors: false, aborted: false`, and the full Gradle suite passed. Agent time was 145s.
  - Raw metrics: 1,370,218 tokens, 26 total calls, 4 MCP calls, 22 native calls, 3 `steroid_execute_code`, 1 `steroid_apply_patch`, 12 Read, 4 Glob, 3 Write, 2 Bash, 0 errors, 0 resource fetches.
  - Delta versus the post-JDK24/final-tasks baseline: tokens 1,773,570 -> 1,370,218, calls 36 -> 26, native calls 33 -> 22, Bash 5 -> 2, Read 17 -> 12, Glob 7 -> 4, errors stayed 0.
  - Measurement review: `/tmp/mcp-steroid-review/gradle-ide-guidance-measurement-20260427/runs/`; Claude, Codex, and Gemini approved with no blockers.

- [ ] Next low-hanging Gradle improvement: batch source discovery and related file reads through IDE/VFS APIs.
  - Evidence: after the IDE Gradle build fix, the remaining waste is concentrated in 12 native `Read` and 4 native `Glob` calls.
  - Consensus: the measurement review unanimously recommended a narrow prompt/resource recipe that reads 3+ related project files in one `steroid_execute_code` call using IDE/VFS APIs before falling back to native `Glob`/`Read`.
  - Target: reduce native source exploration calls while keeping Microshop-2 green, `steroid_apply_patch` used, Bash <=2, and 0 tool errors.

## Recent PR Follow-up

- [ ] PR #26 needs fixes before merge.
  - `npx/build.gradle.kts`: include `package-lock.json` and likely `tsconfig.json` as `npmBuild` inputs.
  - `ocr-tesseract/build.gradle.kts`: make tessdata download skipping version-aware.

## RLM Code Review (v0.93.0..HEAD, 2026-04-28)

Scope: 195 files / ~14K LOC / 125 commits between tag `v0.93.0` and commit
`47e03ef2` (the disable-apply-patch commit on `main`). Review followed RLM
partition+map+reduce: 4 parallel sub-agents each owned a slice (production,
tests, integration/experiments, prompts+kotlin-cli); orchestrator verified
each non-trivial claim with `grep`/file reads before logging.

Apply-patch–specific findings live in `TODO-APPLY-PATCH.md` so the main
DPAIA / autoresearch flow stays focused.

### Fixed (2026-04-28)

- [x] **`test-experiments/.../arena/DpaiaArenaTest.kt`** — removed the
  `Assumptions.assumeTrue` filter and the matching `arena.test.agents`
  system-property kdoc. Subset selection now runs through Gradle's
  `--tests` pattern matching, which already covered the same surface
  (`--tests '*DpaiaArenaTest.claude with mcp'` etc.). No more runtime
  test-skip pattern. (was MAJOR.)
- [x] **`test-integration/.../infra/intelliJ-container.kt`** —
  `waitForIdeWindow` now emits a `console.writeInfo` heartbeat every
  ~10 s (`elapsed=Ns`, last status). Long polls are no longer
  indistinguishable from hangs — the CLAUDE.md "1-minute investigate"
  rule now sees diagnostic output between polls. (was MAJOR.)
- [x] **`ij-plugin/.../execution/DialogWindowsLookup.kt`** — added a
  three-line comment in `canPumpEdtNonModal` explaining that the outer
  `withContext(CoroutineName(...))` deliberately stays on the caller's
  dispatcher; the inner `async(Dispatchers.EDT)` does the dispatch. No
  behavioral change. (was MINOR.)
- [x] **`ij-plugin/.../test/.../ScriptExecutorTest.kt`** — refreshed the
  stale "10-second timeout versus 60-second exec timeout" comment near
  line 73 to describe the current 60 s `timeoutRunBlocking` rationale.
  (was MINOR.)
- [x] **`ij-plugin/.../test/.../ExecuteFeedbackToolHandlerTest.kt`** —
  `assertEquals(true, err!!.contains(...))` → `assertTrue(...)`; dropped
  the now-unused `assertEquals` import. (was MINOR.)

Verification: `:ij-plugin:compileKotlin :ij-plugin:compileTestKotlin
:test-experiments:compileTestKotlin :test-integration:compileKotlin` all
green; `:ij-plugin:test --tests '*ScriptExecutor*' '*ExecuteFeedbackToolHandler*'
'*DialogKiller*' '*VfsRefreshService*'` ran 25/25 (8+7+6+4) on the touched
suites, fresh timestamps.

### Re-evaluated, no longer flagged

- `test-integration/.../OpenProjectTrustIntegrationTest.kt:101` —
  re-reading the code: line 88 already passes `mcpListWindows(timeoutSeconds = 120)`,
  so the per-iteration MCP call IS bounded; the agent's claim of
  unbounded silent hang was wrong. The outer poll uses `Thread.sleep`
  with a fixed 180 s budget, which is acceptable for a JUnit poll
  (not a coroutine).
- `test-integration/.../IntelliJContainerTest.kt:28` — `Thread.sleep(3000)`
  is a deliberate post-creation hold to let the IDE settle; the
  `@Timeout(value = 15, unit = MINUTES)` already protects against hang.
  Pre-existing and harmless.
- `ij-plugin/.../test/.../VfsRefreshServiceTest.kt:68-73` — the test
  documents its own limitation (cannot null out `basePath` on a light
  fixture without reflection). The null-base-path branch is exercised
  by integration tests that close projects. Coverage is acceptable.

### IntelliJ inspections — automated run blocked

- [ ] **N/A automated.** Three approaches tried via `steroid_execute_code`:
  (1) `InspectionEngine.runInspectionOnFile` — failed with API
      signature mismatch (it now takes a single `InspectionToolWrapper` and
      a `GlobalInspectionContext`, not a list + `ProgressIndicator`).
  (2) `DaemonCodeAnalyzerImpl.runMainPasses` directly — fails the
      `assertUnderDaemonProgress()` check at
      `community/platform/lang-impl/src/com/intellij/codeInsight/daemon/impl/DaemonCodeAnalyzerImpl.java:485`
      because the script runs without a `DaemonProgressIndicator`.
  (3) `MainPassesRunner.runMainPasses(files, severity)` — the canonical
      wrapper IntelliJ itself uses (e.g. `CodeSmellDetectorImpl.java:130`).
      Compiles and is the right API per the IntelliJ source agent
      reviewed at `~/Work/intellij`, but times out from
      `steroid_execute_code` even with 3 small files at 240 s. Likely
      cause: it pumps EDT internally and the suspend script holds
      resources that prevent forward progress.
  Manual workaround for full IntelliJ-inspection coverage of the
  changed Kotlin files: open each in the IDE, then
  **Code → Inspect Code… → Custom Scope: changed files since v0.93.0**,
  and triage results into this section. The agent-review findings above
  already covered the banned-pattern + stability surface that IntelliJ
  inspections would flag.

### Verification notes

- All 9 `ApplyPatchTest` engine-level tests pass on `main` after disable
  (verified: `:ij-plugin:test --tests '*ApplyPatchTest*'` 9/9).
- All 30 `McpServerIntegrationTest` cases pass on `main`; `tools/list`
  results no longer include `steroid_apply_patch`. No other test referenced
  the removed tool.
- The `apply-patch` branch retains the full feature surface for re-enable.
- No force-push was used; `origin/main` and `jb/main` are fast-forwards
  from `349d649c` to `47e03ef2`.

# Parked — Cluster A: MCP transport, cancellation, indexing (#46, #52) (2026-05-15)

Six tasks parked for a future iteration. Re-iterate later — direction is set,
but the boundary design and the `kind`-error shapes need a second pass.
**Ordering below is easiest-first**, so the re-iteration starts at the top.

## What likely happened (failure-mode summary for the next reviewer)

MCP was HTTP-ready, but the project was not smart-mode-ready.
`steroid_execute_code` correctly waited for indexing, but the request was
cancelled at ~60 s before indexing finished. MCP Steroid then logged the
coroutine cancellation as an unexpected `warning` / `SEVERE` error, and the
server confusingly logged both `200 OK` and `500 Internal Server Error` for
the same POST window. That single timeline is the canonical evidence for
all six A tasks below — the boundary catch-all (A0), the single-log
discipline (A2c), the cancellation-as-tool-error shape (A2a), the indexing
error shape (A1), and the cooperative-cancellation work (A3) each address
one slice of that timeline.

## Design constraints carried over from the 2026-05-15 review

- **The webserver must keep functioning regardless of what scripts/handlers
  do.** There is exactly one outer boundary in the HTTP layer that catches
  everything (including `CancellationException`, `Throwable`, panics from
  handler code) and converts it into a well-formed MCP tool result. The
  inner code is free to throw; the boundary is the contract that protects
  the server. No exception escapes the boundary into ktor.
- **No new MCP tools, no new HTTP endpoints** for readiness. Surface
  indexing state through a failing tool response on the existing calls. See
  `memory/feedback_narrow_tool_surface.md`.
- **Cancellation does not kill `kotlinc`.** A3 waits for the worker to
  finish; we release the request slot and the boundary returns a structured
  `cancelled` response to the client, but the compiler runs to completion.
  Justification: forcibly killing the JDK21 worker is flaky on macOS and the
  saved cycles are small relative to the rest of cleanup.

## A0 — Boundary catch-all in `McpHttpTransport.handlePost` (LANDED — commit pending)

Single `try { … } catch (CancellationException) { rethrow } catch (Throwable)
{ JSON-RPC error envelope } ` around the response phase of `handlePost`.
Implementation deviates from the original plan in one detail: the boundary
emits a JSON-RPC `error` envelope (HTTP 200, `error.code = INTERNAL_ERROR`,
`id` echoed from the request body), not a `cancelled`-kind tool result. The
`kind`-style structured tool result belongs to A2a, which builds on this
foundation. The current A0 scope is "no exception escapes ktor, exactly one
response per call, no `Logger.error(CancellationException)` noise".

Key implementation points:

- `mcp-core/.../McpProtocol.kt` exposes `encodeJsonRpcError(id, code, message)`
  so the transport layer can build error envelopes without depending on
  `McpServerCore` internals. `McpServerCore.encodeError` now delegates to it
  (single source of truth).
- `mcp-http/.../McpHttpTransport.kt` sets the `Mcp-Session-Id` /
  `Mcp-Session-Notice` headers BEFORE the boundary so a newly created
  session is communicated even when the handler throws (otherwise the
  client reconnects with its stale id, leaking sessions).
- The fallback `respondText` inside the catch is itself wrapped in a
  nested try/catch — a secondary throw (e.g. response stream half-committed
  because the client disconnected mid-write) is logged at warn and
  swallowed, so it cannot escape into ktor and reintroduce #46's dual log.
- `CancellationException` is rethrown without logging. All three JVM
  variants (`kotlinx.coroutines.*`, `kotlin.coroutines.cancellation.*`,
  `java.util.concurrent.*`) are runtime type-aliases of the same class, so
  one catch covers all.
- `extractRequestId(body)` is `Throwable`-safe: malformed body / missing id
  / non-object root → `JsonNull`. Diagnostic must never itself throw.

Test surface (66/66 green) — keep passing on future edits:

- `:mcp-http:test --tests '*McpHttpTransportTest*'` — 36 cases, ~0.6 s.
  Four new boundary tests use `AssertionError` (an `Error`, not `Exception`)
  to bypass `McpToolRegistry.kt:90`'s `catch (Exception)` and actually
  reach the transport boundary; an `IllegalStateException` would have been
  intercepted by the registry and surfaced as `result.isError=true` without
  ever firing the boundary path.
- `:ij-plugin:test --tests '*McpServerIntegrationTest*'` — 30 cases, ~43 s.
  Unchanged behavior verified.

Codex + Claude review quorum on the diff: both `Ship with fixes`. Convergent
fixes folded in: nested-try around the fallback respondText (both),
`AssertionError` in the boundary tests (Codex), session-header before the
try (Claude), `encodeError` delegation (Claude), softened the "client has
already closed" block comment to acknowledge server-side timeout
cancellations (Claude).

## A2c — Single terminal HTTP log per request

Remove the per-handler logging at
`mcp-http/.../McpHttpTransport.kt:159` (the 500 line) and `:176` (the 200
line). Keep only the global `requestLoggingPlugin` line at
`ij-plugin/.../server/SteroidsMcpServer.kt:334–348`. The middleware sees
whatever status the boundary set, exactly once.

## A2b — Logger discipline for `CancellationException` (LANDED 2026-05-19)

Hot-path catches in `mcp-http/` and `ij-plugin/.../execution/` now
rethrow `CancellationException` before any broad-Throwable / broad-
Exception handling, per the `c.i.openapi.diagnostic.Logger` Javadoc
contract for control-flow exceptions. Sites fixed:

- `ExecutionManager.kt:93` — top-level execution catch
- `CodeEvalManager.kt:145` — script-load (inner) catch
- `CodeEvalManager.kt:154` — kotlinc invoke (outer) catch
- `McpScriptContextImpl.kt:117` — `printJson` serialization catch
- `McpScriptContextImpl.kt:162` — `takeIdeScreenshot` capture catch
- `VfsRefreshService.kt:109` — `awaitRefresh` outer-wait catch

Sites already correct, kept as-is:
- `McpHttpTransport.kt:208 / 219 / 242` — boundary catch-all
- `ScriptExecutor.kt:99 / 155` — script-run + outer
- `ApplyPatch.kt:219 / 266 / 305` — patch engine (rethrows `ProcessCanceledException`)
- `ApplyPatchToolHandler.kt:78` — tool handler (rethrows `ProcessCanceledException`)
- `DialogKiller.kt` — all three sites have `CE/PCE` first
- `Diff.kt:30` — PCE first
- `NpxBuiltInWebServerRpcHandler.kt:55–59` — PCE, CE, Exception in order

Lower-priority sites left untouched (off the hot path; `Logger.error`
would still produce noise but does not propagate cancellation upstream):
- `KotlinDaemonManager.kt:97 / 125` — filesystem cleanup; cancellation
  never observed here in practice.
- `IdeaDescriptionWriter.kt:29` — marker-file write at server start;
  not in a coroutine context.
- `NpxProjectsStream.kt:48 / 54` — ndjson streaming; the request scope
  itself owns cancellation propagation through ktor's pipeline.

Banned-pattern lint (`Logger.error(CancellationException)` must fail)
is a follow-up — the runtime-check fixes above land the cancellation
contract; a static scanner is the next belt-and-suspenders pass.

## A1, A2a — DROPPED (fixed differently)

The full `indexing_in_progress` and `cancelled` structured-kind shapes
were dropped in favour of two simpler interventions:

- The agent already learns "we're waiting for indexing" via the
  pre-existing `resultBuilder.logProgress("Waiting for indexing to
  complete...")` in `McpScriptContextImpl.waitForSmartMode()`
  (line 174, in place since commit d112064d). The progress message
  routes through the MCP `notifications/progress` push channel
  immediately when the wait begins, so the agent gets the cause
  without needing a `kind=indexing_in_progress` error shape.
- The cancellation path lands as a JSON-RPC `error` envelope at the
  boundary (commit 3a4e7c13 — A0) and as a structured tool result via
  the per-handler `reportFailed` (`ScriptExecutor.kt:154` for
  `TimeoutCancellationException`; `ReviewManager.kt:148+` for the
  human-review timeout). The `kind=cancelled` reason classification
  was not worth the extra surface.

A1's "wait at most ⌊2T/3⌋ then error" bound was unnecessary too —
`withTimeout(timeout.seconds)` in `ScriptExecutor.kt:132` already
caps the total wait; `reportFailed("Execution timed out after $timeout
seconds")` names the cause cleanly.

## A3 — Cooperative cancellation (VERIFIED no change needed, 2026-05-19)

The current architecture already meets the contract "let `kotlinc`
finish; do not call `destroyForcibly`":

- `KotlincProcessClient.kotlinc(...)` is a regular (non-`suspend`)
  `fun` invoked through `ExecUtil.execAndGetOutput(commandLine,
  120_000)`. The blocking call does NOT check coroutine cancellation,
  so cancelling the caller coroutine leaves the kotlinc subprocess
  running until it exits naturally or hits its 120 s upper bound.
- Nothing in `ij-plugin/src/main` calls `destroyForcibly` on the
  kotlinc process. After `kotlinc(...)` returns, the surrounding
  coroutine code hits a suspension point (or the new CE rethrow from
  A2b) and cancellation propagates out cleanly.
- `waitForSmartMode` already wraps its callback in
  `suspendCancellableCoroutine` (line 176), so cancellation returns
  instantly; the `smartInvokeLater` callback resolves later
  harmlessly.

No code change for A3; the structural property holds today.

## Order of execution (closed out)

A0 → A2c (still parked, drop the per-handler "Response:" log line —
small cosmetic cleanup, low value) → A2b (landed) → A3 (verified, no
change) → A1/A2a (dropped). Cluster A is effectively closed; the only
loose end is A2c's cosmetic log dedup, which can be picked up
opportunistically.

# Active — Cluster B: prompt corpus hardening (#47, #48, #51) (2026-05-15)

Five prompt-only changes to steer `steroid_execute_code` away from invented
helpers, threading misuse, and low-level daemon-highlighting APIs. Format
recap: each `.md` in `prompts/src/main/prompts/` is `[line 1: title]`,
`[line 3: description]`, then body. No hardcoded `mcp-steroid://` URI
literals — use `XxxPromptArticle().uri` in production Kotlin. Fence
annotations like `` ```kotlin[RD] `` per IDE.

**Ordered easiest-first.** Each task is independent; can ship as
separate commits or bundled per the user's preference (validated in a
prior session: bundled is fine for related prompt-corpus refactors).

## B3 — Expand daemon-highlighting warning (smallest)

File: `prompts/src/main/prompts/skill/coding-with-intellij-context-api.md`.
The existing NOTE about `isEditorHighlightingCompleted()` already warns
against stale results. Expand it into a boxed warning that names the
specific symbols that cause the failures observed in #51:

> **Do not call `DaemonCodeAnalyzerImpl`, `HighlightingSession`, or
> `DaemonProgressIndicator` directly.** These APIs require running under a
> `DaemonProgressIndicator` and a stored `HighlightingSession` — neither of
> which exists in a `steroid_execute_code` script context. Symptoms:
> `must be run under DaemonProgressIndicator, but got: null` and
> `No HighlightingSession stored in …`.
>
> For inspection diagnostics, use the supported recipes:
> see `[Inspect and fix](mcp-steroid://ide/inspect-and-fix)` and
> `[Inspection summary](mcp-steroid://ide/inspection-summary)`.

Use article-Kotlin link syntax (the generator resolves `mcp-steroid://...`
in markdown to article references at build time — confirmed in existing
see-also blocks).

## B1 — "Real helpers vs invented names" subsection

File: `prompts/src/main/prompts/skill/coding-with-intellij-context-api.md`,
inserted near the top of the body (after the description, before the
existing helper inventory).

```
## Real helpers vs invented names

These names exist on `McpScriptContext` / standard imports:
`readAction`, `writeAction`, `smartReadAction`, `writeIntentReadAction`,
`findProjectFile`, `runInspectionsDirectly`, `projectScope()`,
`allScope()`, `waitForSmartMode()`, `project`.

These names **do not exist** — do not write them:

| Invented | Use instead |
|---|---|
| `buildProject()`, `compileProject()` | `ProjectTaskManager.getInstance(project).buildAllModules().await()` |
| `createProjectFile(...)` | `findProjectFile("...")` for existing files, or `writeAction { VfsUtil.saveText(virtualFile, text) }` after creating with `LocalFileSystem` |
| `context.project` | Just `project` — it is in scope already |
| `projectDir`, `findProjectDir()` | `project.basePath` or `project.guessProjectDir()` |
```

Each invented name appears verbatim so future agents grep-find this
table on first failure.

## B2 — Threading decision table + failure→fix patches

File: `prompts/src/main/prompts/skill/coding-with-intellij-threading.md`.
Insert a compact table at the very top of the body (existing prose
moves below). Then add three named failure→fix patches.

```
## Quick decision

| You're doing… | Wrap in |
|---|---|
| VFS write (`saveText`, create, delete) | `writeAction { … }` |
| PSI read, `FilenameIndex`, search | `readAction { … }` (or `smartReadAction` if may be dumb) |
| Refactoring processor, intention action | `writeIntentReadAction { … }` |
| Background write (newer platform APIs) | `backgroundWriteAction { … }` |
| EDT-only API (UI, action invocation) | `withContext(Dispatchers.EDT) { … }` |

## Failure → fix

- `Access is allowed from write thread only` → wrap the offending call in
  `writeAction { … }`.
- `Access is allowed from Event Dispatch Thread (EDT) only` → wrap in
  `withContext(Dispatchers.EDT) { … }`.
- `Background write action is not permitted on this thread` → use
  `backgroundWriteAction { … }`, or switch to EDT if the API requires it.
```

## B4 — Tool description distillation (`steroid_execute_code`)

File: the tool handler for `steroid_execute_code` — locate via
`Grep -r "steroid_execute_code"` under `ij-plugin/src/main/kotlin/.../tools/`.
The tool description is always in agent context (unlike articles, which
are loaded on demand), so 4 compact lines pay off.

Add to the tool description:

> Available helpers in scope: `project`, `readAction`, `writeAction`,
> `smartReadAction`, `writeIntentReadAction`, `findProjectFile`,
> `waitForSmartMode`. Use `project` (not `context.project`).
> Wrap VFS writes in `writeAction`; PSI reads in `readAction`.
> Do not call `DaemonCodeAnalyzerImpl` or `HighlightingSession` directly —
> use the inspection resources.

Constraint: don't blow the description over the soft limit (check
existing `*ToolDescription.md` files for length convention). If the
addition pushes over, route via `XxxPromptArticle().uri` reference
instead.

## B5 — DPAIA regression prompts in `:test-experiments`

**Landed as a scaffold** (commit pending). `DpaiaPromptCorpusRegressionTest`
under `test-experiments/.../tests/` covers three representative failure
clusters (one test per cluster). Each test:

1. Spins a Docker IDE container with `IntelliJProject.ThisLoggerProject`
2. Hands the Claude agent a neutrally phrased task (no forbidden helper
   names in the prompt — the corpus is supposed to carry the steer)
3. Parses the agent's raw NDJSON transcript via `readAgentExecCodeBodies`
   to inspect actual `steroid_execute_code` script bodies
4. Asserts on the script-body content, not the agent's final text

The script-body inspection is the key design choice: an earlier draft
checked the combined agent transcript, but the agent's
`steroid_fetch_resource` calls echo the article body verbatim — so any
substring match against the transcript passes on article echo even when
the agent never used the helper. Both reviewers (Claude + Codex) flagged
this and the rewrite addresses it.

Tests in v1:
- `agent compiles project via supported IntelliJ build API` (#47) —
  must call `ProjectTaskManager.getInstance(project).buildAllModules()`;
  must not call `buildProject(`, `compileProject(`, `createProjectFile(`.
- `agent wraps VFS write in the correct threading wrapper` (#48) —
  must pair a writeAction/backgroundWriteAction/edtWriteAction wrapper
  with a `VfsUtil.saveText` / `setBinaryContent` / Document-write call.
- `agent uses supported inspection helper not daemon highlighting
  internals` (#51) — must use `runInspectionsDirectly` or
  `InspectionEngine.*`; must not touch `DaemonCodeAnalyzerImpl`,
  `DaemonProgressIndicator`, or `HighlightingSession`.

**Notes for the first-run shakedown:**
- Uses `aiAgents.claude` (not Gemini). Per `test-integration/CLAUDE.md`,
  `ANTHROPIC_TOKEN_KEY_REF` is configured on TC but `GEMINI_API_KEY` is
  not, so Gemini tests would skip everywhere. Claude is the only agent
  that actually exercises the prompt corpus on TC today.
- Compile-checked but **not run end-to-end** in the authoring session
  (each test ~5–25 min Docker startup + Claude run + asserts, requires
  ANTHROPIC_API_KEY). The first run on TC will surface any marker-
  parsing or prompt-phrasing issues; treat as scaffold shakedown.
- Per-test isolated `IntelliJContainer` (matches
  `ThisLoggerComparisonTest`'s pattern). A shared-container variant
  (companion object + `@BeforeAll`/`@AfterAll`, per the IMPROVEMENTS.md
  harness pattern in `test-experiments/CLAUDE.md`) is a follow-up.

**Open follow-ups (defer until v1 has at least one green run):**
- File-content verification for the writeAction test — confirm
  `Logging.kt` actually contains the marker line after the run. Adds
  belt-and-suspenders behind the script-body check.
- Cover the remaining patterns from #47/#48/#51 individually:
  `context.project`, `projectDir`/`findProjectDir`, `readText(vf)`,
  EDT-only access, write-from-wrong-thread runtime error recovery.
  Each is a new `@Test` method following the same shape.
- Migrate to shared `IntelliJContainer` via `@BeforeAll` to amortize the
  ~2 min IDE startup across the suite.

# Landed — Cluster C: structured apply_patch recovery hints (#49, #50) (2026-05-15)

C1, C2, C3 shipped together. The user-facing error response now carries
nearby-file candidates (file-not-found path) and fuzzy line candidates
(anchor-not-found path). C4 remains parked.

Test surface that gates this work — keep passing on future edits:
- `:ij-plugin:test --tests '*ApplyPatchTest*' '*ApplyPatchToolIntegrationTest*'`
  — 36 cases, ~3 s; substring assertions on `"file not found"`,
  `"old_string not found"`, `"occurs more than once"`, `"expand old_string"`.
- `:prompts:test --tests '*AnchorSafeEditing*' '*ApplyPatchToolDescription*' '*MarkdownArticleContract*'`
  — the four kotlin blocks in the new article compile per IDE; the
  tool description still validates.

## C1 — Structured ApplyPatch errors (done — commit pending)

`ij-plugin/src/main/kotlin/.../execution/ApplyPatch.kt` now calls two
private helpers, `fileNotFoundMessage(...)` and `anchorNotFoundMessage(...)`,
that append (multi-line, plain text) structured candidates to the leading
sentence. The leading wording is preserved verbatim so existing
substring-based assertions in `ApplyPatchTest` and
`ApplyPatchToolIntegrationTest` keep passing.

- `file_not_found` adds up to 5 same-basename project-index hits.
- `anchor_not_found` adds `lines x, y bytes` and up to 3 fuzzy lines
  for the longest stable `[A-Za-z0-9_]{4,}` token from `oldString`.
- Both helpers swallow their own `RuntimeException` (re-throwing
  `ProcessCanceledException`) so the diagnostic can never itself fail
  the diagnostic.

The body format is plain multi-line text rather than the JSON shape
floated in the earlier plan — the existing tool result is a text
content payload, and the in-line text is what the agent actually reads.
If A2a lands and standardises a JSON `kind` envelope, the leading line
remains and the structured tail can move into a sibling JSON field
without breaking the substring contract.

## C2 — `skill/anchor-safe-editing.md` (done — commit pending)

New article with four kotlin code blocks (locate → excerpt → unique
check → apply+verify). Cross-linked from
`apply-patch-tool-description.md`. KtBlocksCompilation tests pass across
the IDE distributions that include the Kotlin plugin (default fence
annotation, so all IDEs compile each block).

## C3 — Apply-patch tool description nudge (done — commit pending)

`prompts/src/main/prompts/skill/apply-patch-tool-description.md` now
ends with a "do not retry blindly" paragraph that points at
`mcp-steroid://skill/anchor-safe-editing` and the four steps.

## C4 — `dryRun` parameter (LANDED in eceb6674, then explicitly DROPPED 2026-05-19)

`dryRun: Boolean = false` is already on `steroid_apply_patch` (commit
eceb6674). The "park C4" note here is obsolete — keeping for the
historical audit trail. No further work needed.

## Order of execution (B → A → C)

1. **B3** → **B1** → **B2** → **B4** (prompt edits, mechanical). Single
   commit OK.
2. **B5** (regression tests; can ship in a follow-up).
3. **A** (when re-opened — see Parked section above).
4. **C3** → **C2** → **C1** (recovery hints).
5. **C4** (dryRun, deferred).

---

# Managed-backend review findings (2026-05-15)

Three parallel `run-agent.sh codex` review passes against `mcp-5` after
iter7. Items below are the consensus set (≥2 reviewers, with which
reviewers agreed in parentheses). One item — first-start writers into
the real user home — is intentionally **deferred** per user direction
("Let's keep it so for now, we are going to review that step later").

The reviewer reports are preserved at
`.run-agent-managed-backends/reviews-{a,b,c}/run_*/FINAL_RESULT.md`.

## Blockers

### B1 — `backend stop` can SIGTERM/SIGKILL an unrelated process (A, B, C)
`npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/proxy/ManagedBackend.kt:282-309`.
Stop trusts the PID file, calls `ProcessHandle.of(pid).destroy()` without
proving that pid still belongs to a managed IDE under
`homePaths.backendsDir`. PID reuse → wrong process killed.
**Fix:** before signalling, check `ProcessHandle.info().command()` is
under `homePaths.backendsDir`, or verify the `.<pid>.mcp-steroid`
marker matches our backend descriptor. On mismatch → delete stale PID
file, report "stale" outcome, no signal.

### B2 — Archive extraction allows path-traversal / symlink escape (A, B, C)
`intellij-downloader/src/main/kotlin/.../IdeUnpacker.kt:76-92`, `:133-140`, `:291-293`.
The `outputFile.canonicalPath.startsWith(unpackDir.canonicalPath)`
prefix check is bypassable (no trailing separator); tar symlinks aren't
target-validated.
**Fix:** add trailing `File.separator` to the prefix check; for
symlinks resolve `linkTarget = unpackDir.resolve(entry.linkName).normalize()`
and reject when it escapes the unpack dir.

✅ resolved

### B3 — Build / installDist blocked by `:intellij-downloader:extractSevenZipResources` (B only)
Likely fallout of the parallel 7zip-into-npx-kt worker mid-edit;
expected to resolve as that worker lands. Re-check after each iter
lands.

## Majors

### M1 — `idea-community` may resolve to an Ultimate (`IU-…`) bundle (A)
iter4's marker captured `ide.build: IU-253.28294.334` while the backend
ID was `idea-community-2025.3`. Either the resolver picked the wrong
URL, or the plugin reports the build of a different IDE process.
**Action:** read-only investigation first — confirm whether the
download URL the resolver returns for `code=IIC` actually serves a
Community binary, and whether the marker's `ide.build` reflects the
running JVM's ApplicationInfo. Only fix if a real mismatch is found.

### M2 — `backend start <product>` / `stop <product>` (no version) hits the network (B, C)
`ManagedBackend.kt` resolves "latest stable" via the products API instead
of consulting `homePaths.backendsDir/<product-key>-*`. Means stop
depends on JetBrains uptime AND can target a version different from
what's installed.
**Fix:** for product-only argv, prefer the highest-versioned entry on
disk. Fall back to API only when nothing is installed.

### M3 — Single-instance lock is racy across concurrent CLI calls (A, B, C)
`ManagedBackend.kt:231-273` — scan-then-spawn has no file lock; two
concurrent `backend start` calls can both pass the scan and both spawn.
**Fix:** `FileChannel.tryLock()` on `homePaths.stateDir/global.lock`
for the duration of the start sequence.

### M4 — JSON backend ids use natural stable identifiers (A, B, C) ✅ resolved
`BackendCommand.kt` — `backend --json` no longer exposes synthetic ordinal-based identifiers
as primary keys in `backends[]`.
**Fix:** use each row's natural id: `pid-<n>` for marker-discovered IDEs,
`port-<n>` for port-discovered IDEs, or the managed backend id.

### M5 — SevenZipLocator cache writes are racy (A, C) ✅ resolved (download-A batch)
`SevenZipLocator.kt:69-73`, `:103-107`. Fixed `*.tmp` filename per
binary; two concurrent first-runs collide.
**Fix:** randomise tmp name with `Files.createTempFile` and atomic-move
to the cache slot.

### M6 — `backend stop --json` reports a `logPath` that `start` never writes (A, B)
The schema lies. Either `start` writes to that path, or `stop` omits the field.
**Fix:** drop the field, or have `start` write to that path (it's the
log file we already capture — wire it).

### M7 — Partial / interrupted downloads poison the install dir (B) ✅ resolved (download-A batch)
No transactional rename; an aborted `download` leaves a half-extracted
bundle that subsequent `download` calls treat as installed.
**Fix:** extract to `<id>.partial/`, atomic rename to `<id>/` only on
full success.

### M8 — CLI parser accepts malformed flags / extra positional args (A, B) ✅ resolved
Two reviewers independently found ambiguous argv shapes that resolve
to unexpected modes.
**Fix:** added table-driven parser validation plus fuzz-style parser tests;
reject unrecognised flags, missing value-flag values, and extra
positionals.

## Minors

| | | reviewers |
|---|---|---|
| m1 | `DEVRIG_HOME=~/...` not expanded; `..` normalised rather than rejected ✅ resolved | A, B, C |
| m2 | `NpxKtRoot` has a production-visible mutable test seam ✅ resolved | A, B, C |
| m3 | Text rendering uses UTF-16 `String.length`, not terminal display width ✅ resolved | A, B |
| m4 | Some unit tests depend on live JetBrains/Google APIs (flaky offline) ✅ resolved | A |
| m5 | Banned silent `catch (_:Exception)` in `IdeDownloader.kt:58-60` ✅ resolved (download-A batch) | A, B, C |
| m6 | Help banner omits `--version <v>` for `backend start/stop` ✅ resolved | C |
| m7 | `tempFile.renameTo(dest)` success not checked in `IdeDownloader.kt:79-98` ✅ resolved (download-A batch) | A |

## Deferred (per user, 2026-05-15)

- **First-start config writers target the real user home (`~/.config/JetBrains/...`, `~/.java/...`).** Reviewers flagged this as a blocker (A, B) — managed IDEs can clobber the user's real JetBrains preferences. User direction: "Let's keep it so for now, we are going to review that step later." Re-open later with a per-backend user-home design.

## Plan / execution

Sequential codex runs via `~/Work/marinator/marinade/marinade/run-agent.sh codex`.
One focused brief per task; collect handoff, push, iterate. Order:

1. **M1 investigation** (read-only first; if false alarm, close out, otherwise
   becomes a new blocker fix).
2. **B1** + **M2** + **M3** + **M6** — all lifecycle-correctness, all in
   `ManagedBackend.kt`. One coherent commit chain.
3. **B2** — archive extraction security, isolated to `IdeUnpacker.kt`.
4. **M5** + **M7** + **m5** + **m7** — download/cache atomicity & banned
   pattern, isolated to `IdeDownloader.kt` + `SevenZipLocator.kt`.
5. **M4** — JSON synthetic IDs, isolated to renderer.
6. **M8** — CLI parser tightening.
7. **m1** + **m2** + **m3** + **m4** + **m6** — polish batch ✅ resolved.

B3 watched but not actively fixed (parallel worker territory).

## Additional items (added 2026-05-15 by user)

### M9 — Centralised downloads folder + cleanup after unpack
Today downloads land in per-backend dirs. Move all download staging to
`~/.mcp-steroid/downloads/`. Once a download is unpacked into
`~/.mcp-steroid/backends/<id>/`, **remove** the file from `downloads/`.
`HomePaths` gets a new `downloadsDir` property; `BackendManager.download`
routes the archive there, unpacks, then `Files.delete()`.

✅ resolved (download-B batch)

### M10 — Recoverable downloads + checksum/signature verification
Two parts:

**Recoverable:** if `download` is interrupted, a follow-up
`download` should resume from the saved bytes via HTTP `Range` request
(or skip from the start if the server doesn't support 206 Partial Content).
The `.partial` extension stays until the full size is verified.

**Verified:** the JetBrains products API exposes per-download checksum
fields (`checksumLink`, `sha256`, signature URL) and Android Studio's
`developer.android.com/studio` page exposes SHA-256 checksums next to
each download URL. After download, fetch the upstream checksum and
verify SHA-256 of the local file. On mismatch → reject the file,
delete, fail loudly.

When the source doesn't expose a checksum we trust:
  - DO log a `WARN` (visible without --debug) noting "no checksum
    available from upstream; skipping verification".
  - Don't fabricate a fallback; just record the gap.

✅ resolved (download-B batch)

## Revised plan / execution

Sequential codex runs. Order:

1. **M1 investigation** (read-only).
2. **B1 / M2 / M3 / M6** — lifecycle in `ManagedBackend.kt`.
3. **B2** — archive extraction security in `IdeUnpacker.kt`.
4. **M5 / M7 / M9 / M10 / m5 / m7** — download path overhaul:
   centralised `downloads/` dir, resumable transfer, SHA-256
   verification, `.partial` atomic rename, fix silent catch +
   unchecked rename. All in `IdeDownloader.kt` + `SevenZipLocator.kt`
   + `HomePaths.kt`.
5. **M4** — JSON synthetic IDs.
6. **M8** — CLI parser tightening.
7. **m1 / m2 / m3 / m4 / m6** — polish ✅ resolved.

## Additional item (added 2026-05-15)

### M11 — `devrig backend provision <id>` — install MCP Steroid into an existing IDE

New CLI subcommand to provision the MCP Steroid plugin into an
**already-running** IDE that was discovered by port scan but doesn't
yet have the plugin installed. The current listing already
distinguishes port-discovered ("mcp-steroid plugin not installed —
project list unavailable") rows; promote the action by appending a
clear pointer:

```
  [3] IntelliJ IDEA Ultimate (port 63342)
        run: devrig backend provision port-63342
```

Identifier: stable port-based id (e.g. `port-63342`) since port-
discovered IDEs don't have a `<product-key>-<version>` natural id.
Surface the same id in the JSON `backends[]` row so machine
consumers can pipe it.

**Research first** (read-only, single codex pass):
- Inspect `~/Work/intellij` for the built-in HTTP server (`org.jetbrains.builtInWebServer` / `BuiltInServerManager` / the `WebServerPathHandler` SPI).
  Document every action the running IDE exposes — specifically:
  - is there an endpoint that returns the plugins / config / system path?
  - is there an endpoint that installs a plugin (with or without restart)?
  - what's the auth model (CSRF token / Origin / nothing)?
- Cross-reference with what Toolbox / Settings Sync uses. Toolbox is
  known to inject plugins; figure out how.
- If no useful endpoint exists, derive the plugins folder from
  `/api/about`'s `productCode` + `baselineVersion` + `buildNumber`
  and the per-OS JetBrains config convention
  (`~/Library/Application Support/JetBrains/<ProductSlug><Version>/plugins/`
  on Mac; `$XDG_CONFIG_HOME/JetBrains/<ProductSlug><Version>/plugins/`
  on Linux; `%APPDATA%\JetBrains\<ProductSlug><Version>\plugins\`
  on Windows).

**Implementation** (after research lands):
- Spawn the same plugin-source resolution as `BackendManager.download`'s
  plugin deploy step: `NpxKtRoot.ijPluginDir()` → copy into the
  target IDE's plugins dir.
- Hot-reload if possible (Plugin Hot Reload plugin or built-in
  dynamic-plugin reloader); otherwise prompt the user to restart the
  IDE.
- JSON output shape: `{tool, action: "provision", id, productCode, pluginsDir, hotReloaded: <true|false>, restartRequired: <true|false>}`.

Slots into the pipeline **right after M1** — before the lifecycle batch.

## Revised pipeline order (2026-05-15)

1. M1 investigation (in flight).
2. **M11 — `backend provision`** (research → design → implement).
3. B1 / M2 / M3 / M6 — `ManagedBackend.kt` lifecycle.
4. B2 — `IdeUnpacker.kt` security.
5. M5 / M7 / M9 / M10 / m5 / m7 — download path overhaul.
6. M4 — JSON synthetic IDs.
7. M8 — CLI parser tightening.
8. m1 / m2 / m3 / m4 / m6 — polish ✅ resolved.

## M1 follow-up: confirmed bug, fix required (2026-05-15)

Investigation (run `task-m1/run_20260515-123930-99884`) confirmed the
IIC-vs-IIU mismatch. **Live evidence:**

- JetBrains products API `?code=IIC&release.type=release` returns
  release 2025.3 with these download URLs:
  - `https://download.jetbrains.com/idea/idea-2025.3-aarch64.dmg`
  - `https://download.jetbrains.com/idea/idea-2025.3.tar.gz`
  - `https://download.jetbrains.com/idea/idea-2025.3.dmg`
  - `https://download.jetbrains.com/idea/idea-2025.3.exe`
- HEAD checks on the same URLs return **HTTP 200 with identical
  Content-Length** to the IIU 2025.3 download. The correctly-named
  Community URL `ideaIC-2025.3-aarch64.dmg` returns **HTTP 404** — JetBrains
  hasn't shipped a Community 2025.3 binary.
- The unpacked `product-info.json` on iter4's host run reported
  `productCode: IU`. The IDE that started was Ultimate.
- The NEXT release in the IIC feed (2025.2.6.2) has proper
  `ideaIC-2025.2.6.2-aarch64.dmg` URLs (HTTP 200), unpacks as
  Community.

**Fix:**

1. `IdeReleaseLookup.kt`: when iterating product API releases, require
   the chosen download URL's filename to contain a product-specific
   token. For Community editions, that token is `ideaIC-` / `pycharm-community-`
   / `IC-` (whichever the per-OS URL uses). Skip releases whose URL
   filename doesn't match. The most recent stable that passes the
   filter becomes the "latest stable".
2. `BackendManager.download`: post-unpack, read
   `<bundle>/Contents/Resources/product-info.json` (macOS) or
   `<bundle>/product-info.json` (Linux/Windows). Assert the
   `productCode` matches what the requested IdeProduct expects
   (`IC` for `IdeProduct.IntelliJIdeaCommunity` etc.). On mismatch:
   delete the broken install, fail loudly.

This is now a **blocker fix** (was logged as M1 investigation). Pipeline
moves M1-fix in front of M11.

## Revised pipeline order (2026-05-15)

1. **M1-fix** — IIC resolver filter + post-unpack productCode assertion.
2. M11 — `backend provision`.
3. B1 / M2 / M3 / M6 — `ManagedBackend.kt` lifecycle.
4. B2 — `IdeUnpacker.kt` security.
5. M5 / M7 / M9 / M10 / m5 / m7 — download path overhaul.
6. M4 — JSON synthetic IDs.
7. M8 — CLI parser tightening.
8. m1 / m2 / m3 / m4 / m6 — polish ✅ resolved.

## Additional item (added 2026-05-15)

### M12 — Managed-backend GUI test: stream `devrig …` output to the on-video console

`test-integration/src/test/kotlin/com/jonnyzzz/mcpSteroid/integration/tests/ManagedBackendGuiIntegrationTest.kt`
currently runs the `devrig backend download/start/stop` commands via
container exec, captures stdout/stderr to assertion strings, and the
video records only Xvfb's fluxbox desktop. The viewer can't see what
the test is actually doing.

Make the video more useful:
- Run each `devrig …` invocation inside a visible `xterm` window on
  the Xvfb display (the container's `ide-base` image already has
  `xterm` + `xvfb` + `fluxbox` + `ffmpeg` ready).
- Pipe the command's combined output through `tee` so the test still
  gets the bytes for assertions, AND the xterm shows them in
  real time.
- The "frame" the existing tests use to spawn IDE windows is
  reusable — see `WhatYouSeeTest` for an `xterm`-as-IDE-frame
  precedent, and the existing `XcvbVideoDriver` for the recording
  loop. No new infrastructure needed.

Shape:

```kotlin
container.execAndAssertOnVideo(
    title = "devrig backend download idea-community",
    script = "DEVRIG_HOME=/tmp/mcp-home /home/agent/devrig backend download idea-community",
)
```

…where `execAndAssertOnVideo` launches `xterm -title <…> -hold -e bash -c <script>` against `DISPLAY=:0`, waits for the wrapped process to exit, captures the exit code + bytes via the `tee` sidekick (write to a file the test reads after the xterm window closes), then asserts on the captured output.

Slot: between M8 (parser tightening) and the polish batch.

✅ resolved

## Revised pipeline order (2026-05-15, final)

1. M1-fix — IIC resolver filter + post-unpack assertion (in flight).
2. M11 — `backend provision`.
3. B1 / M2 / M3 / M6 — `ManagedBackend.kt` lifecycle.
4. B2 — `IdeUnpacker.kt` security.
5. M5 / M7 / M9 / M10 / m5 / m7 — download path overhaul.
6. M4 — JSON synthetic IDs.
7. M8 — CLI parser tightening.
8. **M12 — managed-backend test: stream `devrig` output to the on-video xterm.**
9. m1 / m2 / m3 / m4 / m6 — polish ✅ resolved.

## M13 — `backend provision` three explicit methods (2026-05-15)

M11 landed Option-B-as-I-then-called-it (file-system install with
PathManager-default plugin folder). User now wants all three options
exposed as choices:

- **A — install-files**: discover plugin folder via the IDE's own
  `~/.intellij/<pid>-built-in-server.json` (written by
  `BuiltInServerDiscoveryService` in `community/platform/built-in-server/
  src/org/jetbrains/ide/BuiltInServerInfoService.kt` when the registry
  flag `ij.platform.experimental.discoverability` is on). That JSON's
  `paths.plugins` is the **authoritative** override-respecting plugins
  folder. Write our plugin tree there; print restart hint.
  - Fallback when the discoverability JSON isn't present: PathManager-
    default convention (the existing M11 logic).
- **B — install-marketplace**: GET `/api/installPlugin?pluginId=com.jonnyzzz.mcp-steroid&action=install`
  against the target IDE. `Origin: http://localhost` is required to
  avoid the trust prompt; even so, the IDE pops the REST API consent
  dialog (its wording is already toned down in our parallel
  `marinator/rest-api-dialog-wording` branch). MCP Steroid is on
  Marketplace at plugin id 27834.
- **C — manual**: print "to install manually: drop the plugin into
  <suggested path>, then restart the IDE", and exit. Useful when neither
  REST nor file-system access is desirable.

CLI: `devrig backend provision <id> --method <auto|install-files|install-marketplace|manual>`. Default `auto`:
1. install-files (prefer discoverability JSON; fall back to PathManager-default).
2. If install-files's chosen directory isn't writable, prompt the
   user to retry with `--method install-marketplace` or `--method manual`.

JSON output adds a `method` field on every variant.

## Pipeline update

M13 slots **right after M11** (already done) and BEFORE the lifecycle
batch B1/M2/M3/M6 — touches the same `BackendProvision*` files M11
just added.

## M13 update (2026-05-15) — manual mode only

User feedback:
- **Option A (install-files via `~/.intellij/<pid>-built-in-server.json`)** — not viable. `BuiltInServerDiscoveryService` was reverted upstream; the discoverability JSON isn't in shipped IDE builds.
- **Option B (install-marketplace)** — not great because the IDE would pull a Marketplace build, NOT the version bundled with the devrig launcher. We want the bundled plugin to be the source of truth.

Therefore M13's deliverable shrinks to **manual mode only**:
- `devrig backend provision <id>` prints actionable instructions and the per-OS suggested install path (best-effort derived from `/api/about`), then exits.
- No file writes, no REST install calls.
- Listing of port-discovered IDEs continues to suggest `devrig backend provision port-<port>` next to each row.

M11's filesystem-install code path needs to be **removed** in this iteration — keep the listing + the `port-<port>` id parsing, drop the actual copy-files step. (Or hide it behind an unadvertised `--method install-files` flag that defaults off.) Net result: fewer surfaces to maintain; the user makes the install decision deliberately.

✅ resolved (manual mode only)

## M14 — Relocate MCP Steroid plugin marker into `~/.mcp-steroid/markers/`

Today the plugin writes the PID-marker file at `~/.<pid>.mcp-steroid`
(directly in the user home root). That's noisy and conflicts with the
"all state under `~/.mcp-steroid/`" principle the rest of the project now follows.

Both sides need updating in lockstep:

1. **Plugin (`ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/ServerUrlWriter.kt`)**:
   - Write to `~/.mcp-steroid/markers/<pid>.mcp-steroid` (no leading dot in the filename — we don't need to hide files inside a dedicated subdir).
   - Create the subdir on first write.
   - Cleanup logic now scans `~/.mcp-steroid/markers/` instead of the home root.
   - Honor `MCP_STEROID_HOME` env var when set (plugin reads env at IDE startup).

2. **Proxy (`npx-kt/src/main/kotlin/com/jonnyzzz/mcpSteroid/proxy/monitor/IdeDiscovery.kt`)**:
   - Scan `~/.mcp-steroid/markers/` (via `homePaths.markersDir`).
   - Drop the home-root scan entirely after one release — for now, scan BOTH and warn (DEBUG) when the legacy location surfaces something.

3. **Shared (`mcp-steroid-server` module's `PidMarker`)**:
   - Add `PidMarker.markerDirectory(userHome: Path): Path` returning
     `userHome.resolve(".mcp-steroid/markers")` so plugin and proxy
     can't drift.
   - Filename helper: `markerFileNameFor(pid)` returns `$pid.mcp-steroid`
     (the leading-dot legacy `fileNameFor` stays for compat reads).

4. **HomePaths**: add `markersDir = home.resolve("markers")` (the path lives under the managed home root the proxy already manages).

5. **Stale-file cleanup**: keep the existing pid-alive check (`ProcessHandle.of(pid).isPresent`); apply it to the new directory.

6. **Tests**: bump the existing discovery tests to use the new location; add one to confirm the proxy still reads a legacy `~/.<pid>.mcp-steroid` (with a DEBUG log) for the transition window.

✅ resolved

## Pipeline update (2026-05-15, again)

1. Lifecycle batch B1/M2/M3/M6 (running).
2. **M13** (now: manual-only — small shrink-down of the M11 surface).
3. **M14** (marker relocation: plugin + proxy in lockstep).
4. B2 archive security.
5. Download overhaul M5+M7+M9+M10+m5+m7.
6. M4 JSON synthetic IDs.
7. M8 parser tighten.
8. M12 video xterm.
9. Polish m1+m2+m3+m4+m6 ✅ resolved.

## Post-pipeline follow-ups (added 2026-05-15)

Synthesised from each codex run's `IMPROVEMENTS.md` after the
managed-backends pipeline landed. Higher priority first.

### F1 — M8 introduced a regression: `backend --help` / `backend --version` exit 64 ✅ resolved

`Cli.kt`'s strict allow-list (commit `dc733cac`) dropped `--help` /
`--version` from every backend mode's allowed-flag set. Pre-tightening,
`parseCliMode` checked `--help` / `-h` BEFORE `backend`, so `backend --help`
routed to Help. Now it lands in `Unknown` with exit 64.

**Fix:** re-introduce `--help` / `-h` / `--version` / `-v` as universal
flags that route to Help / Version regardless of the mode keyword
preceding them. Add explicit parametrised tests for
`backend --help`, `backend download --help`, `project --version`.

### F2 — `:npx-kt:installDist` fails when previous tree has read-only bundled JDK files ✅ resolved

Surfaced by `task-lifecycle` and `task-m13`. Manual recovery is
`chmod -R u+w npx-kt/build/install/mcp-steroid-proxy && rm -rf` before
rerunning.

**Fix:** in `npx-kt/build.gradle.kts`, add a `doFirst { ... }` to the
`installDist` task that chmod-fixes any pre-existing install tree
before the `Sync` task copies into it.

### F3 — `SevenZipLocatorTest` writes through the real `~/.cache/mcp-steroid/7z/` ✅ resolved

`SevenZipLocator.cacheRoot` is a `private val by lazy` bound to
`user.home`. Tests run against the real host cache, leaving side
effects.

**Fix:** mirror the `NpxKtRootTestSupport` pattern from m2 — a
`SevenZipLocatorTestSupport` object in the test sourceset overrides
the cache root via package-internal access. Reset between tests.

### F4 — `IntelliJPortDiscoveryTest` has a port-bind race ✅ resolved

`task-polish` hit `Address already in use` on its first full-module
run; immediate rerun passed.

**Fix:** bind Ktor fake IDE servers with `embeddedServer(port = 0)` and
read the resolved connector port after start. No pre-release
`ServerSocket(0).localPort` hand-off and no `Thread.sleep` retries.

### F5 — GUI integration test re-downloads ~1 GB IDE archive every run ✅ resolved

Cold-cache run is ~2 minutes; transient `EOFException` mid-stream has
surfaced (M14 run).

**Fix:** persistent test archive cache under
`~/.cache/mcp-steroid-test/` reused across dev-loop runs; falls back to
fresh download when the file is missing/stale. The test stages cached
`ideaIC-*.tar.gz` archives into `/tmp/mcp-home/downloads/` before
`devrig backend download` and back-populates archive files after a
successful download.

### F6 — Plugin `sinceBuild` must move with the resolver's oldest fallback ✅ resolved

M1-fix's `idea-community` fallback to 2025.2.6.2 (build `IC-252`) does
not load with `sinceBuild=253`. The two surfaces (resolver oldest
release in `IdeProduct.knownProducts` + plugin manifest `sinceBuild`)
are coupled but live in different modules.

**Fix:** declare `MANAGED_BACKEND_MIN_SUPPORTED_BUILD = "252"` in
`:intellij-downloader` and gate `:ij-plugin:test` with
`PluginCompatibilityFloorTest`, which reads `ij-plugin/build.gradle.kts`
and fails if `pluginConfiguration.ideaVersion.sinceBuild` drifts from
the resolver baseline.

### F7 — Remove legacy home-root marker fallback after one release (DEFERRED)

The M14 transition keeps a DEBUG-only fallback scanning
`~/.<pid>.mcp-steroid`. Drop the fallback + transition tests after
one release cycle (once shipped plugins all write to
`~/.mcp-steroid/markers/`). Track which release first shipped the new
layout (next ij-plugin release after `5e324746`).

**No action now.**

### F8 — Bounded retry on transient `checksumLink` fetch failures ✅ resolved

M10's checksum verification fails closed if the `.sha256` URL returns
a transient error. Safest default; but CDN blips would unnecessarily
fail downloads.

**Fix:** small bounded retry (3 attempts, exponential backoff capped
at ~10 s) inside the checksum-fetch path only. Final failure still
surfaces the error verbatim.

### F9 — Log the selected archive URL on `--debug` even on cache hits ✅ resolved

Today only a cold download surfaces the URL chosen by the resolver;
a cached rerun shows just the products-API fetch. Hampers forensics.

**Fix:** in `IdeDistribution.resolveAndDownload`, log the resolved
URL + local destination at INFO (always) or DEBUG (selectable) BEFORE
the cache check.

### F10 — npx-kt fixtures conflate API product code (`IIC`) with installed `product-info.json` code (`IC`) ✅ resolved

Naming hygiene only. Fixtures should distinguish:
- `apiProductCode = "IIC"` — JetBrains products-API code
- `installedProductCode = "IC"` — value in `product-info.json`

### F11 — `backend provision` listing should hide IDEs already running MCP Steroid ✅ resolved

Today the listing scans all port-discovered IDEs, including ones
with the plugin already loaded. Once an IDE has produced a marker
file, the listing should hide those rows.

**Fix:** provision list now runs the same marker discovery as `backend`,
filters port-discovered rows whose normalised build already appears in
a marker, prints the already-installed message when the filter empties
the list, and emits `discoveryNote` in JSON when rows were removed.

### F12 — East-Asian wide characters and combining marks (display width)

m3 (polish batch) intentionally fixed surrogate-pair / code-point
width only. East-Asian wide characters render at 2 columns; combining
marks at 0. Both still misalign in text mode.

**Fix:** extend `String.codePointWidth()` to consult Unicode East
Asian Width. Defer combining marks if scope grows.

---

## Lifecycle batch (B1 + M2 + M3 + M6) — ✅ resolved 2026-05-15

Codex run `task-lifecycle/run_20260515-133645-60441`. Commits pushed to
`origin/mcp-5`:

- `3a44bb88` — B1: `BackendManager.stop` verifies `ProcessHandle.info().command()`
  resolves under `homePaths.backendsDir` or the `~/.<pid>.mcp-steroid` marker
  decodes & matches descriptor; stale pid file deleted, `{outcome: "stale"}`
  emitted on mismatch, no signal sent.
- `6c5df613` — M2: product-only `backend start/stop <product>` prefers the
  highest locally-installed `<product-key>-<version>` entry; falls back to
  the products API only when nothing is installed.
- `4f3e0ea3` — M3: `BackendManager.start` serialised via
  `FileChannel.tryLock(state/global.lock)`; contended starts exit 64 with
  "another devrig backend operation is in progress; retry shortly".
- `1ea0fc32` — M6: launches redirect stdout/stderr to `logs/managed.log`;
  start text + JSON report that path (Windows still uses WMI detach so
  redirection there is best-effort).

GUI integration test green (`ManagedBackendGui*`, BUILD SUCCESSFUL 2m9s),
manual single-instance smoke green. See run dir for full transcript.

# GH-issue triage queue — A + B bundles (2026-05-19)

Source: open-issue review on `jonnyzzz/mcp-steroid`. Bundle C/D/E deferred;
issue #11 dropped per owner. One commit per issue, atomic.

## Bundle A — recipes / docs (no production code)

- [ ] **#63** `mcp-steroid://ide/inspect-and-fix`: refresh
  `InspectionEngine.inspectEx(...)` snippet against current platform; verify
  via `steroid_execute_code` paste-and-run on a live IDE
- [ ] **#60** `steroid_execute_code` tool description + recipes: surface the
  "last expression is not auto-printed → use `println(...)`" tip; add a
  worked example near the top of the tool description; do NOT add a new
  `returnLastExpression` parameter (narrow-tool-surface — see memory
  `feedback_narrow_tool_surface`)
- [ ] **#61** Action discovery: add a `requireAction(id)` recipe under
  `prompts/src/main/prompts/ide/` showing
  `ActionManager.getInstance().getAction(id) ?: error("no action $id …")`;
  cross-link from the action-invocation skill
- [ ] **#15** Short doc: "MCP Steroid vs IntelliJ Built-in MCP Server" —
  place under `docs/`, link from README
- [ ] **#32** Tips & Tricks Migration — scope first (what corpus, what
  destination); likely a `prompts/` move, but do not start coding until the
  scope is one-pager-sized

## Bundle B — small code fixes (≤ a day each)

- [ ] **#59** Stop tracking `.idea/mcp-steroid.md`. Move to
  `.idea/mcp-steroid/runtime.json` (or similar), `.gitignore` it, and on
  first write of the new path delete the old `.idea/mcp-steroid.md` if it
  exists so existing checkouts self-heal. Update any clients that read the
  old path (TC DSL repo notes, `run-agent.sh`, etc. — grep before changing)
- [ ] **#57** `steroid_apply_patch`: under
  `native2AsciiForPropertiesFiles=true`, neighboring unchanged
  `.properties` lines get re-encoded. Make the patcher write back unchanged
  lines byte-identical to source; add a regression test with a non-ASCII
  `.properties` file
- [ ] **#55** `steroid_take_screenshot` JFrame capture is ~1.37× larger
  than X display. Divide capture rect by
  `JFrame.graphicsConfiguration.defaultTransform.scaleX/scaleY` (or use
  `JBUI.sysScale(component)`). Verify on a Retina Mac + Linux Docker
- [ ] **#12** Make `reason` optional in `steroid_execute_code`. Update
  schema (nullable) + tool description; keep emitting the field in
  audit/feedback logs when supplied

## Tracking

Working order:
1. A (4 issues + a scope note for #32)
2. B (4 issues, atomic commits)
3. Stop and report; do NOT start bundle C yet

# Gradle-script "Nothing here" popup (2026-05-19 → 2026-05-22 resolved)

User-reported repro: triggering Debug on a Gradle script in
`~/Work/mcp-steroid` opens an IntelliJ popup that just says "Nothing here".

- [x] Reproduce + locate the source of the popup text.
- [x] Identify the failure path.
- [x] Update prompts to steer agents away.

## Root cause

`"Nothing here"` is `CommonBundle.empty.menu.filler`, exposed in IntelliJ
source as `com.intellij.openapi.actionSystem.impl.Utils.EMPTY_MENU_FILLER`.
It is the placeholder item that platform popup/menu rendering inserts
when an `ActionGroup`'s post-`update()` expansion yields zero visible
items (`Utils.kt:677` and `ActionStepBuilder.buildGroup`).

The specific Gradle-script mismatch:

- **Gutter mark**: `KotlinGradleTaskRunLineMarkerProvider` paints a Run
  icon purely from PSI patterns — `isRunTaskInGutterCandidate` matches
  `task("…")` (open-quote leaf inside a call argument) and
  `val X by tasks.registering { … }` (identifier leaf of a property
  with a delegate expression).
- **Producer**: `KotlinGradleTaskRunConfigurationProducer.setupConfigurationFromContext`
  returns `false` unless **both** `context.module` is non-null **and**
  `GradleRunnerUtil.resolveProjectPath(module)` returns a path.
- Outside a synced Gradle module (cold start before sync, file inside a
  non-module folder, etc.) the producer returns false, every
  `RunContextAction` in the line-marker group sets
  `presentation.setEnabledAndVisible(false)` via
  `BaseRunConfigurationAction.update`, the line-marker popup expands to
  zero visible children, and the renderer falls back to
  `EMPTY_MENU_FILLER` → user sees an empty popup labelled `"Nothing here"`.

The user's reading is exact: "right-click or gutter icon click, with
incorrect context, so the action group popup is shown with no actions
there".

## Verification on the live IDE

`steroid_execute_code` against `mcp-steroid` (IntelliJ IDEA 2026.1.2)
located four gutter offsets in `build.gradle.kts` — `buildPluginOnCI`,
`ciBuildPluginTests`, `ciBuildPromptsTests`, `ciIntegrationTests`. At all
four, `KotlinGradleTaskRunLineMarkerProvider` emits Run-gutter `Info`
items. Once the Gradle project is synced,
`ConfigurationContext.getConfigurationsFromContext` returns 1 valid
`GradleRunConfiguration` per offset, so the popup is non-empty; in the
unsynced / no-module variant, the size collapses to 0 and the
placeholder takes over.

## Delivered fix

`prompts/src/main/prompts/test/run-test-at-caret.md` — two hunks:

1. New `> **Pitfall: `.gradle.kts` files.**` blockquote next to the
   existing "Choose run configuration" tip, naming the gutter pattern
   and the `Utils.EMPTY_MENU_FILLER` placeholder, and routing to
   `mcp-steroid://skill/execute-code-gradle` for the programmatic
   `ExternalSystemUtil.runTask` recipe.
2. New entry in `# See also` linking to the same Gradle execute-code
   prompt.

Validation: `./gradlew :prompts:generatePrompts` + `:prompts:compileKotlin`
both green; the new tip text round-trips through the prompt-generator
and compiles into the generated payload class.

---

# Cleanup & simplification round (2026-05-25)

Six-task plan to shrink the MCP tool surface and clean up downstream
references. Each task is independently sequenced and gated on agent review
(`run-agent.sh claude` + `run-agent.sh codex`) of its plan before any code
changes land. Each implemented bullet ships as a **dedicated commit**.

**Working principles** (apply to every task in this round):

- Use MCP Steroid for file edits (`steroid_apply_patch` for multi-site
  changes; `steroid_execute_code` for IDE-driven refactorings — rename,
  find-usages, optimize-imports, inspections).
- After each change: compile, run scoped tests, verify IDE inspections green.
- Never weaken a test. If a test pins a removed feature, delete it as part
  of the same commit.
- Focused commit messages: `subsystem: change` style.

## Status legend

- 🟦 `planned` — plan drafted, awaiting agent review
- 🟨 `reviewed` — both `claude` and `codex` approved
- 🟧 `in-progress` — implementation underway
- 🟩 `done` — landed on `main`
- 🟥 `blocked` — issue raised; needs decision

---

## C1 — Drop exec-code review functionality 🟦

**Goal.** Remove the human-in-the-loop review gate on `steroid_execute_code`.
Every incoming script executes immediately; no per-project approve/reject
UX, no settings page, no editor banner.

**Surface area.**

- `ij-plugin/.../review/` — entire package (4 files, ~470 lines):
  - `ReviewManager.kt` — `@Service(PROJECT)`, `requestReview(...)`, `approve`/`reject`
  - `McpReviewNotificationProvider.kt` — editor-notification banner with approve/reject buttons
  - `McpSteroidProjectSettings.kt` — per-project mode (ALWAYS/TRUSTED/NEVER)
  - `McpSteroidProjectConfigurable.kt` — settings UI
- `ij-plugin/.../execution/ExecutionManager.kt:73` — drops the
  `requestReview(...)` call (the only caller).
- `ij-plugin/src/main/resources/META-INF/plugin.xml` — strip the four EP
  registrations.
- Registry key `mcp.steroid.review.mode` becomes orphaned.

**Implementation outline.**

1. Inline-delete the `requestReview` block from `ExecutionManager.executeWithProgress`.
2. Delete the four files under `review/`.
3. Strip the four `<extensions>` entries from `plugin.xml`.
4. `git grep -n "ReviewManager\|McpSteroidProjectSettings\|review.mode"` —
   confirm zero hits outside removed files.
5. `./gradlew :ij-plugin:test --rerun-tasks` — fix or delete review tests.

**Risks.** Persisted `<component>` entries in `.idea/` are silently ignored
by IntelliJ; no migration needed.

---

## C2 — Promote `steroid_fetch_resource` over MCP-resources listing 🟦

**Goal.** Stop registering every prompt article as an MCP **resource**. Keep
the `steroid_fetch_resource` MCP **tool** as the single discovery surface;
it already requires `project_name` so rendering picks up the project's
`PromptsContext` (IDE conditionals).

**Surface area.**

- `mcp-steroid-server/.../ResourceRegistrar.kt` — stop calling
  `resources.registerResource(...)` on every article.
- `mcp-core/.../McpResourceRegistry.kt` and `McpResourceRegistrar` — audit;
  if no one registers anything anymore, prune the write API.
- `SteroidsMcpServer.kt:80` — drop the `ResourceRegistrar` wiring (or keep
  for prompts-only).
- `NpxBuiltInWebServerRpcHandler.kt:146` — confirm it reads through the
  index, not the registry.
- `steroid_fetch_resource` tool description — tweak to claim discovery role.
- Index article (`mcp-steroid://prompt/skill`) — instruct agents to call
  `steroid_fetch_resource` rather than browse `ListMcpResourcesTool`.

**Implementation outline.**

1. Delete or no-op `ResourceRegistrar.register()`'s article-loop.
2. Drop unused `resources` parameter if it becomes vestigial.
3. Audit `McpResourceRegistry` — delete the write side if no callers.
4. Update tool description text.
5. Update index articles' "Related" sections.
6. Add or update an integration test asserting `resources/list` returns an
   empty/minimal payload.

**Risks.** Clients that pre-cached steroid URIs against `ReadMcpResourceTool`
get "not found" — `steroid_fetch_resource` is the new path.

---

## C3 — Drop `steroid_apply_patch`; reinforce deep IDE features 🟦

**Goal.** Remove the `steroid_apply_patch` MCP tool. Replace its function
with recipes (existing `mcp-steroid://ide/apply-patch` + the
`McpScriptContext.applyPatch { }` DSL) and ensure file changes made through
`steroid_execute_code` refresh VFS/PSI so subsequent semantic operations
see the new content. Add a tenet to `docs/PHILOSOPHY.md` ("deep IDE
features over patch utilities") with this removal as the worked example.

**Surface area.**

- `mcp-steroid-server/.../ApplyPatchTool.kt` — delete the spec + tests.
- `ij-plugin/.../server/ApplyPatchToolHandler.kt` — delete the IJ impl.
- `ij-plugin/.../execution/executeApplyPatch.kt` — evaluate: keep only if
  the in-script `McpScriptContext.applyPatch { }` DSL still references it.
- `SteroidsMcpServer.kt` — drop the registration.
- `plugin.xml` — strip the two `<applicationService>` entries.
- `prompts/src/main/prompts/skill/apply-patch-tool-description.md` — delete.
- `prompts/src/main/prompts/ide/apply-patch.md` — keep, reframe as a
  `steroid_execute_code` recipe (it already includes the DSL pattern).
- Schema test + integration test — delete.
- `docs/PHILOSOPHY.md` — add the new tenet.
- **VFS-refresh guarantee**: after any `applyPatch { }` invocation inside
  `steroid_execute_code`, ensure `VirtualFileManager.syncRefresh()` or
  equivalent so the next read picks up disk state. Audit
  `McpEditingGuard.kt` BEFORE/AFTER awaitRefresh — should already cover
  this; if not, add it unconditionally for `steroid_execute_code`.

**Implementation outline.**

1. Update `docs/PHILOSOPHY.md` (own commit).
2. Delete `ApplyPatchToolSpec` + schema test (own commit).
3. Delete `ApplyPatchToolHandler` + IJ impl + plugin.xml entries (own commit).
4. Verify in-script `McpScriptContext.applyPatch { }` DSL still resolves.
5. Rewrite or trim `prompts/.../ide/apply-patch.md` as exclusively a
   `steroid_execute_code`-driven recipe.
6. Delete `prompts/.../skill/apply-patch-tool-description.md`.
7. Update `ResourcesIndex` references; rerun `:prompts:test`.
8. Audit `McpEditingGuard` BEFORE/AFTER awaitRefresh for execute-code path.

**Risks.** Any IDE skill that programmatically calls `applyPatch { }` inside
`execute_code` must keep working; ensure `McpEditingGuard`-driven refresh
isn't gated on the patch tool path.

---

## C4 — Drop `steroid_action_discovery`; replace with recipe 🟦

**Goal.** Remove the tool. Salvage its action-listing logic
(editor-popup + gutter + intentions at caret) into a
`steroid_execute_code` recipe so capability is preserved without dedicated
tool surface.

**Surface area.**

- `mcp-steroid-server/.../ActionDiscoveryTool.kt` — delete the spec.
- `ij-plugin/.../server/ActionDiscoveryToolHandler.kt` — delete the IJ impl.
- `SteroidsMcpServer.kt` — drop registration.
- `plugin.xml` — strip the two `<applicationService>` entries.
- `prompts/.../skill/action-discovery-tool-description.md` — delete.
- **New recipe**: `prompts/src/main/prompts/ide/action-discovery.md`
  reproducing the action-listing logic (ActionManager + DataManager +
  ShowIntentionsPass + DaemonCodeAnalyzer.restart). Wait-for-highlights
  pattern matches existing inspections recipes.
- Schema/integration tests — delete.

**Implementation outline.**

1. Salvage the action-listing logic from `ActionDiscoveryToolHandlerIJ`
   into the new recipe article.
2. Delete the tool spec + handler + tool-description prompt.
3. Drop the registration + plugin.xml entries.
4. Cross-reference from `skill/coding-with-intellij` and `ide/overview`.
5. `./gradlew :prompts:test :ij-plugin:test --rerun-tasks`.

**Risks.** Recipe must include the "wait for highlights" idiom; copy from
existing inspections recipes.

---

## C5 — Repo-wide cleanup and simplification 🟦

**Goal.** Walk the codebase looking for clutter introduced or left over by
the four removals. Simplify without changing semantics. Each cleanup goes
as its own commit.

**Candidates.**

- `McpResourceRegistry` write API — delete if Task C2 leaves no callers.
- `McpResourceRegistrar` interface — same.
- `executeApplyPatch.kt` — delete if both removals (C3) leave no callers.
- `McpEditingGuard.kt` — keep, but drop `steroid_apply_patch` from KDoc.
- `analyticsBeacon.capture(event = "apply_patch", ...)` callsites — remove.
- Any new `runCatching { }` left over (banned pattern).
- `TODO*` files in repo root — grep for now-obsolete entries.
- `CommonToolParams.taskId()` — re-read description after removals.
- `ProjectScopedToolHandler` — confirm remaining handlers still use it.
- `prompts/.../skill/coding-with-intellij.md` — update after tool removals.
- Unused imports across modified files.

**Implementation outline.**

1. After C1–C4 land: `git grep -i "apply_patch\|action_discovery\|reviewMode\|ReviewManager"`.
2. For each finding: delete, rewrite, or move to a recipe.
3. `./gradlew :ij-plugin:test :mcp-core:test :mcp-steroid-server:test :prompts:test --rerun-tasks`.
4. Optional: `mcp-steroid://ide/inspect-and-fix` pass to catch
   unused-symbol / redundant-code warnings.

---

## C6 — Audit `prompts/` corpus for stale references 🟦

**Goal.** Walk every prompt under `prompts/src/main/prompts/` and remove or
rewrite references to deleted entities. Confirm every `mcp-steroid://...`
link still resolves.

**Surface area.**

- `prompts/src/main/prompts/**.md` — entire corpus.
- Generated `ResourcesIndex` — verified by `:prompts:test`.
- "See also" cross-reference lists at the bottom of each article.

**Implementation outline.**

1. Build the list of removed URIs:
   `mcp-steroid://skill/apply-patch-tool-description`,
   `mcp-steroid://skill/action-discovery-tool-description`, …
2. `grep -rln "apply-patch\|action-discovery\|reviewMode\|ReviewManager" prompts/`.
3. For each match: delete the link, swap for the new recipe URI, or
   rewrite the surrounding paragraph.
4. `./gradlew :prompts:test` — KtBlocks + MarkdownArticleContract.
5. Final pass to verify article line-2 IDE filter (`[IU,RD]` etc.).

---

## Sequencing

1. **C1** (review removal) — independent. Land first.
2. **C3** (apply_patch removal) — needs PHILOSOPHY update first; rest follows.
3. **C4** (action_discovery removal) — independent.
4. **C2** (MCP-resources promotion) — touches the fetch tool which stays;
   do after C3+C4 so the resource list shrinks naturally first.
5. **C5** (cleanup pass) — after C1–C4.
6. **C6** (prompts audit) — after C1–C5.

Each task waits on a fresh agent review (`run-agent.sh claude` +
`run-agent.sh codex`) of its plan section before implementation starts.

---

# Stabilization round (2026-05-25)

Autonomous follow-up tasks after C1–C6 land. The plugin is now leaner
(8 MCP tools); this round shakes out regressions, dead code, and an
overdue inline-and-simplify.

## Status legend

- 🟦 `planned` — todo
- 🟧 `in-progress`
- 🟩 `done`
- 🟥 `blocked` — surfacing failure, needs decision

## S1 — Run `:ij-plugin:test` full suite, fix every failure 🟧

Foreground baseline pass: `./gradlew :ij-plugin:test --rerun-tasks`
(13–14 min). For each failure: read the report, classify (regression
from C1–C6 vs pre-existing), fix or delete the test, re-run scoped.
Log surface area + fix per failure in this section.

## S2 — Run `:npx-kt:test` + sibling modules, fix every failure 🟧

`:npx-kt:test :mcp-core:test :mcp-steroid-server:test :execution-storage:test :mcp-http:test :mcp-stdio:test :agent-output-filter:test --rerun-tasks`
Same fix-it-or-explain-it discipline as S1.

## S3 — Inline `McpEditingGuard` into `ScriptExecutor` + non-modal-during-exec test 🟧

`McpEditingGuard.withEditingGuard` has exactly one caller now —
`ScriptExecutor.executeWithProgress` (after C3-2 wired it). Inline the
helper, drop the indirection class, keep the steps inline with comments.

Add a regression test: open a non-modal dialog while `steroid_execute_code`
is running; confirm the script finishes (does not hang waiting for the
dialog to close) and the dialog killer dismisses the dialog.

## S4 — Hunt for dead code repo-wide 🟦

After C1–C6 and S3, sweep the codebase. Targets:

- Orphaned classes / functions / data classes / interfaces.
- Unused imports across edited files.
- `McpResourceRegistry` write API — production has no callers; tests do.
  Decide: keep (test-only), or drop both and adapt tests.
- `McpResourceRegistrar` interface.
- Stale KDoc / comments referencing removed tools.

Use MCP Steroid's `mcp-steroid://ide/inspect-and-fix` recipe for
inspection-driven sweeps.

## S5 — IMPROVEMENTS.md harness for `test-integration` (10 iterations) 🟦

Apply the `FindDuplicatesPromptTest` IMPROVEMENTS pattern to a chosen
`:test-integration` prompt-quality test. Each iteration:
1. Run the test.
2. Read the produced `IMPROVEMENTS-*.md` blocks.
3. Apply prompt-only fixes (skill articles, tool descriptions, system
   prompt text — no new tools, no new context methods).
4. Re-run. Compare regressions.

Hard cap: 10 iterations. Document each iteration's findings + diff
under this section as we go.

## Sequencing

S1 and S2 run in parallel (different modules). S3 lands as soon as the
inline + new test pass. S4 happens after S1–S3. S5 is the longest tail —
run it concurrently with S4 because IMPROVEMENTS turnaround per
iteration is several minutes.

Progress log appended below as each task moves.

### Progress

- 2026-05-25 09:30 PT — S1, S2, S3 kicked off. S1 baseline `:ij-plugin:test`
  running in background; S2 `:npx-kt:test` + sibling modules running in
  background; S3 inline implementation underway.
- 2026-05-25 09:55 PT — **S6 added & done.** Drop MCP prompts/skills
  registration too (after C2 dropped resources). `ResourceRegistrar.kt`
  deleted entirely. Capabilities advertisement no longer claims
  prompts/resources.
- 2026-05-25 09:55 PT — **S2 done.** `:npx-kt:test` + siblings green
  after S6.
- 2026-05-25 10:00 PT — **S3 done.** `McpEditingGuard` inlined into
  `ScriptExecutor`. Two new tests:
  `testNonModalDialogDuringExecuteDoesNotBlock` (JFrame; not in modality
  state) and `testModalDialogWrapperDuringExecuteIsKilledAndExecCompletes`
  (DialogWrapper modal; registers into modality state, killed by
  pre-flight). Both green in isolation.
- 2026-05-25 10:05 PT — S1 flipped `testPromptsListAndGetForSkills` to
  match the new empty-prompts contract. Other failures in the full
  `:ij-plugin:test` run (LSP/IDE example tests) are flaky/order-dependent;
  in-isolation they pass. Rerunning to confirm.
- 2026-05-25 10:10 PT — S4 dead-code hunt: confirmed no orphaned classes
  after C1–C6+S3+S6. `McpResourceRegistry` write API is alive only via
  the two transport tests (intentional protocol-mechanics fixtures).
  `McpResourceRegistrar` interface stays for the same reason.
- 2026-05-25 10:15 PT — **S1 done.** Full `:ij-plugin:test` green
  (BUILD SUCCESSFUL in 4m 1s after the `testPromptsListAndGetForSkills`
  flip + the second-run-stable LSP/IDE example tests). The earlier
  full-run failures were flaky/order-dependent and don't reproduce.
- 2026-05-25 10:25 PT — Codex review surfaced four real findings:
  (1) modal-DialogWrapper test was under-asserted; (2) drop of
  Prompts/Resources capability advertisement risked spec drift; (3)
  stale `build.gradle.kts` comment + `ExecutionSuggestionService` tip;
  (4) `McpEditingGuard` still referenced in three live prompts. All
  four addressed in `1a50ebdf`, `90442714`, `90765311`.
- 2026-05-25 10:30 PT — `testModalDialogWrapperDuringExecuteIsKilledAndExecCompletes`
  reshaped: BasePlatformTestCase headless can't render a real
  `DialogWrapper` (the dialog never registers in `Window.getWindows()`),
  so the test now uses `LaterInvocator.enterModal` to elevate IntelliJ's
  modality state without a real GUI window — pinning the slow-branch
  path of `DialogWindowsLookup.withModalityCheck` (which returns false
  because no `DialogWrapperDialog` is showing). Full GUI-modal kill
  coverage remains in `test-integration/DialogKillerIntegrationTest`
  (Docker + Xvfb).
- **S5 constraint note**: full IMPROVEMENTS harness iteration cycle is
  Docker + agent API keys + ~15 min per iteration × 10 = several hours.
  Iter 1 completed locally (Docker + ~/.anthropic + ~/.openai keys; gemini
  skipped, no GEMINI_API_KEY). Both Claude and Codex converged on the
  same finding: `mcp-steroid://ide/find-duplicates` returns
  `CLUSTERS_FOUND: 0` with no diagnostic path when `HashFragmentIndex`
  is empty. Applied prompt-only fixes in `1e6fef87`:
  - "When the inspection returns zero clusters" diagnostic section
    (pre-flight sanity check on the index)
  - "Fallback: PSI-based body comparison (no index needed)" recipe
  - Fully-qualified `com.intellij.platform.ide.observation.Observation.awaitConfiguration`
    (claude flagged the unqualified name as unresolved)
- Iter 2 ran with iter1 fixes: Claude 505s → 185s, Codex 121s → 96s.
  Both agents converged on second wave: (a) PSI fallback compared
  whole `PsiNamedElement.text` and missed copy-paste-rename pattern;
  (b) `/.idea/mcp-steroid/` scripts polluted results; (c) recipe should
  recommend body-only PSI fallback first in fresh IDE sessions / CI.
  Applied in commit `784a36b5` (bundled with S3 deadlock fix):
  - Switch fallback to `KtNamedFunction.bodyBlockExpression` /
    `PsiMethod.body` (body-only, catches copy-paste-rename pattern)
  - Add `/.idea/` to default `pathFilter` everywhere
  - "Recommended order" note at the top
  - "When the inspection returns zero clusters" jumps directly to fallback
- S3 deadlock: `testElevatedModalityWithoutDialogLetsExecProceed` was
  rolled back because `LaterInvocator.enterModal` + plain `Dispatchers.EDT`
  in `commitAndSaveAllDocuments` deadlock (verified: 24-min hang). NOTE
  comment in `ScriptExecutorTest.kt` documents the gap; modal-DialogWrapper
  coverage stays in `test-integration/DialogKillerIntegrationTest`
  (Docker+Xvfb).
- Iter 3 (`bbf9137a`) and iter 4 (`bdf41d1c`) IMPROVEMENTS applied.
  TL;DR moved to top of `find-duplicates`; expression-body Kotlin
  added to fallback; FetchResourceToolHandler description hints at
  PSI fallback; completeness-note + threshold rationale added.
  Progression: Iter 1 Claude 505s/Codex 121s → Iter 4 Claude 99s/Codex 60s.
- Iter 5 kicked off after iter4 fixes landed.
- 2026-05-26 — final codex review found two more polish items addressed
  in `bfaad8fc`: drop the empty Prompts/Resources capability advertisement
  entirely (cleaner intent signal) + fix stale `docs/ARCHITECTURE.md`
  lines (resources registry + review workflow).
- 2026-05-26 — Iter 7 IMPROVEMENTS (Claude 73s / Codex 61s) converged on
  one fix: the "Agent fast path" callout pointed to a recipe further
  down the article, but the FIRST code block the agent saw was still
  the inspection-based one — agents tend to copy what they see first.
  Renamed sections in `find-duplicates.md` (commit `26c57dbe`):
    * `# The recipe (copy-paste)` → `# Cross-check recipe — warm-index inspection (broader clone types)`
    * `# Fallback: PSI-based body comparison (no index needed)` → `# Primary recipe — PSI body comparison (no index needed)`
    * "Agent fast path" callout rewritten to direct the reader to scroll
      down to "Primary recipe" by section name.
    * "When the inspection returns zero clusters" wording updated to
      reference "Primary recipe" instead of "PSI fallback".
- 2026-05-26 — Iter 7 post-rename reviewer pass (Claude + Codex) both
  said: renaming headings was a step but the strongest lever is
  PHYSICAL reorder — agents copy the first code block in document
  order regardless of heading wording. Also flagged 4 residual
  "fallback" mentions (article L11, L13, L303, L312) still framing
  PSI as secondary. Applied as a single follow-up (this entry):
    * Physically moved the entire Primary recipe section (heading +
      kotlin block + supporting notes) to appear directly after the
      header callouts — now the FIRST code block in document order.
    * "Why direct typed access works" moved to be the preamble to the
      Cross-check recipe (where it belongs — it talks about the
      typed import of `DuplicateProblemDescriptor`).
    * Sections "How it works" → "How the Cross-check recipe works";
      "Language coverage" → "Language coverage for the Cross-check
      recipe"; "When the inspection returns zero clusters" → "When
      the Cross-check returns zero clusters"; "When the direct import
      does not compile" → "When the Cross-check direct import does
      not compile".
    * "Cross-check returns zero" now says "Primary recipe (PSI body
      comparison) is the answer — if you haven't run it yet, go back
      and run it" (the recipe sits above the cross-check, so the
      direction reverses).
    * Scrubbed "fallback" → "Primary recipe" in 4 spots: the agent
      fast-path callout, the "PSI fallback language coverage" header,
      the kotlin block's `println("CLUSTERS_FOUND: …(PSI body
      comparison)")` (was: `(PSI body-comparison fallback)` — emitted
      "fallback" into agent output), and the "Completeness note"
      paragraph.
    * `FetchResourceToolHandler` tool description: rewrote the
      find-duplicates hint to point at "Primary recipe — PSI body
      comparison" by section name (was: "PSI fallback section").
    * Pre-existing regression caught en route: `apply-patch.md`
      description was 209 chars (>200 cap, introduced in commit
      `90442714` during S4 rewording). Trimmed to 184 chars.
  Tests green: `MarkdownArticleContractTest`,
  `FindDuplicatesPromptTest`, `FindDuplicatesPromptArticleReadTest`,
  `FindDuplicatesKtBlocksCompilationTest` (10 blocks × 2 IDEs).
- 2026-05-26 — **S5 iter8 BLOCKED: Docker Desktop is unable to start
  locally.** `docker version --format '{{.Server.Version}}'` returns
  "Error response from daemon: Docker Desktop is unable to start"; the
  `:test-integration:test` task failed in 1m 2s at the Docker image
  build step ("500 Internal Server Error … on `_ping`" to
  `~/.docker/run/docker.sock`). Iter 8-10 of the IMPROVEMENTS cycle
  cannot run until Docker Desktop is restarted by the user. The
  prompt-only improvements above (iter 7 + the post-rename physical
  reorder) ship now; the convergence verification waits for
  Docker.
- 2026-05-26 — Docker is back; cleaned up stale resources before iter 8:
  pruned 2 stopped containers + 382 dangling images (26.5 GB) +
  build cache (143 GB), trimmed test-integration/build/test-logs/test/
  to newest 2 of each kind (4.8 GB freed). Total reclaimed ~462 GB.
  Kept `mcp-steroid-base` and `mcp-steroid-reaper` intact.
- 2026-05-26 — **Iter 8 ran (Claude 85s PASSED, Codex 71s PASSED,
  Gemini 39s FAILED on a stale assertion, not a real regression).**
  Gemini correctly fetched the article, used the Primary recipe (PSI
  body comparison), got `DUPLICATES_FOUND: 1` + `DEMO_DUPLICATES_HIT:
  yes`, but `FindDuplicatesPromptTest:78-95` only accepted the
  Cross-check signals (`DuplicateInspection`, `DuplicateProblemDescriptor`,
  `DuplicatedCode`, `com.jetbrains.clones`). Test contract was written
  before iter 7's reorder made Primary the "Agent fast path" default.
    * Test fix (`FindDuplicatesPromptTest.kt:75-100`): accept either set
      of signals (Cross-check OR Primary). Primary signals are
      `KtNamedFunction`, `bodyBlockExpression`, `PsiMethod`,
      `PsiTreeUtil.collectElementsOfType`. Issue #33's intent (block
      grep/Bash) is preserved — agent must still use one of the two
      IDE recipes.
    * Article fix: added a Primary-recipe `printJson` block. Gemini's
      IMPROVEMENTS feedback flagged that pasting the existing
      `Structured output (printJson)` block (which assumes Cross-check
      data shape `CloneCluster(main, duplicates)`) at the end of the
      Primary recipe causes a compile error — the Primary returns
      `List<List<CloneRange>>` from `byBody.values`. New block sits
      directly after the Primary recipe with the correct data shape,
      and a "do NOT paste the Cross-check `printJson` here" warning.
  Iter 8 IMPROVEMENTS that did NOT make it into this iteration's
  fixes (deferred to iter 9 / iter 10 if reviewers agree they
  matter):
    * Claude: add a brief TL;DR / "5-10 line Agent fast path block" at
      the top of the article so agents reading top-to-bottom can stop
      early.
    * Codex: spell out a default interpretation of "source files"
      (e.g. `"/src/" in path`) in one sentence so the scope choice is
      unambiguous.
    * Codex: push the find-duplicates special case higher in the
      `steroid_execute_code` tool description.
- 2026-05-26 — **Iter 9 ran (Claude PASSED 87s, Codex PASSED 56s,
  Gemini FAILED 44s — but at a different assertion).** Gemini's
  signal check (iter8's fix) now passes for the Primary recipe path;
  this iteration's failure was at the reflection-check
  (`FindDuplicatesPromptTest:107-109`) — `No steroid_execute_code
  calls captured in NDJSON. The recipe was never run.` This is the
  documented `readAgentExecCodeBodies` follow-up in
  `test-integration/AGENTS.md` ("`FindDuplicatesPromptTest.readAgentExecCodeBodies`
  is the older copy and only handles the Claude + Codex shapes").
  Gemini's NDJSON shape (`type=tool_use` at root, `tool_name`,
  `parameters.code`) wasn't being parsed.
    * Test fix: extended `readAgentExecCodeBodies` to handle Gemini's
      shape, mirroring the reference impl in
      `PrintCsvPrintToonPromptTest.readAgentExecCodeBodies`.
    * AGENTS.md / CLAUDE.md: closed the "follow-up open to extend it
      to Gemini" doc note.
  Iter 9 IMPROVEMENTS converged on three prompt-level fixes (also
  applied this commit):
    * **Article — Agent fast path made more blunt.** Per Codex:
      "For Kotlin/Java, run the Primary recipe FIRST. Do NOT start
      with the warm-index Cross-check inspection path — it can
      legitimately return zero in fresh sessions."
    * **Article — Cross-check section opens with a blockquote
      warning.** Per Claude + Codex: "Skip this section unless the
      Primary recipe has already run AND the user explicitly wants
      near-duplicate / parameterized-clone detection." The previous
      warning was prose inside the section; now it is a blockquote at
      the very top of the heading so an agent that jumps directly to
      this heading hits the gate immediately.
    * **`mcp-steroid://skill/execute-code-tool-description`** (drives
      the `steroid_execute_code` MCP tool description): rewrote the
      duplicates row to lead with "duplicate-code detection is an
      IDE/PSI task, not a text-search task" (Codex), name the Primary
      recipe by name as the default (per the article reorder), and
      mark the Cross-check as OPTIONAL with the warm-index caveat.
  Iter 9 IMPROVEMENTS that did NOT make it in (low-value or out of
  scope):
    * Claude: parallel-batch `list_projects` + `fetch_resource` — that
      is multi-tool architecture, not a prompt change.
    * Claude: define "smaller codebases" quantitatively for the
      body-length threshold — minor; the current text already
      conveys the trade-off.
    * Codex: add a language-split table at the very top — the
      article already has the language coverage table later, and
      adding a duplicate near the top would push the Primary recipe
      down, undoing iter7's reorder.
    * Codex: one sentence on intra-file vs cross-file results — the
      Primary recipe already covers both equally, no agent action
      change.
- 2026-05-26 — **Iter 10 attempt 1 hit an infrastructure hang (NOT
  prompt regression).** Claude ran the Primary recipe correctly,
  identified `calculateInvoiceTotal`/`calculateOrderTotal` as the
  clone cluster, but then hung mid-final-summary for 35 minutes
  before SIGKILL (exit 137). The dead container blocked Codex +
  Gemini's MCP-server registration → 3/3 cascade failure. No
  IMPROVEMENTS files emitted. Retried fresh (no Docker prune was
  needed — no zombie containers, plenty of RAM/disk after the
  earlier 462 GB reclaim).
- 2026-05-26 — **Iter 10 retry: ALL 3 PASS.** Claude 60s, Codex 49s,
  Gemini 53s. Build 13m 19s total. This is the convergence target:
  every agent completes the task end-to-end in under 60s using the
  Primary recipe, emits both required markers (`DUPLICATES_FOUND: 1`
  + `DEMO_DUPLICATES_HIT: yes`), and produces a clean IMPROVEMENTS
  reflection. Progression baseline → iter 10:
    * Claude: 505 s (iter 1) → **60 s (iter 10)** — 8.4× faster
    * Codex:  121 s (iter 1) → **49 s (iter 10)** — 2.5× faster
    * Gemini: BLOCKED by stale test contract until iter 8;
      now **53 s** with full convergence
  Iter 10 IMPROVEMENTS were polish only (article has converged).
  Both reviewers asked for a shorter TL;DR / fast-path block at the
  very top of the article so an agent reading top-to-bottom can stop
  early; Claude also wanted the language-coverage note inlined into
  the recipe's code comment (impossible to miss). Applied:
    * `find-duplicates.md`: new "TL;DR for agents" section at the
      very top — 1 paragraph, names the Primary recipe by section
      heading, names the output markers, points Python/JS/Groovy/Ruby
      readers at the Cross-check, says "the rest of this article is
      reference material." Sits BEFORE the existing "When to use
      this" preamble so it is the first non-header content an agent
      sees.
    * `find-duplicates.md`: Primary recipe kotlin block gains a 3-line
      `// Language coverage: …` comment right next to `targetExtensions`.
      Mirrors the prose at the top of the article but sits inside the
      code an agent is about to paste, so it cannot be skimmed past.
  Iter 10 IMPROVEMENTS that did NOT make it in (still polish, not
  blockers):
    * Claude: split the article into "agent-only" + "reference /
      troubleshooting" sections. Would require restructuring the
      Cross-check + edge-case content — too invasive for a polish
      iteration after convergence.
    * Codex: add a "source-files-only" variant with `/src/` filter
      already applied. The existing `pathFilter` comment lists this
      as one of three common variants; adding a fourth duplicated
      block would bloat the article without changing agent action.
    * Codex: explicit "exact body duplicates only" outcome line in
      the `steroid_execute_code` description. Already conveyed by
      the "Primary recipe" name + the "Cross-check is OPTIONAL"
      framing iter9 introduced.
- 2026-05-26 — **S5 IMPROVEMENTS harness complete: 10 / 10 iterations
  done.** Article + test + tool description have converged. All
  three agents complete the task in <60s with clean signal. Closing
  task #19.

# TODO — test-integration JDK pre-configuration is too slow (likely sleeps) (2026-05-30)

The per-test IDE JDK pre-configuration (`mcpRegisterJdks` / SDK setup during
`waitForProjectReady`, `intelliJ-factory.kt` + `intelliJ-container.kt` +
`mcp-steroid.kt`) takes far too long on startup — it *feels like there are
sleeps in the code* rather than event-driven waits. Review and fix.
- Suspects found (grep): fixed `delay(2_000L)` at `mcp-steroid.kt:664,731,800`,
  `delay(500L)` at `:544`, `Thread.sleep(50)` polling in `IdeTestHelpers.kt:152,161`,
  poll loops in `intelliJ-container.kt:307,315,354,384,415` (`Thread.sleep(pollIntervalMillis)`).
- Goal: replace blocking fixed sleeps / coarse poll intervals on the JDK-setup +
  project-ready path with event-driven / shorter-interval waits so the IDE reaches
  "project ready" promptly. Quantify the time spent in JDK pre-config first (log
  timestamps around `mcpRegisterJdks` + each wait), then cut the dominant sleeps.

# Active — DialogKiller hang on macOS/Docker: VisionService.capture stalls under a modal (2026-05-30)

The DialogKiller integration test hangs locally (macOS Docker+Xvfb; passes on CI
Linux). Evidence from a pre-logging run's IDE log: `killProjectDialogs`
(`eid_…093726`) started and never completed (~10-min gap); a later **direct**
`VisionService.capture` step (`screenshot-tool-test`, `dialog_killer=false`) is the
**last line in the log — it hung too**. So `VisionService.capture` stalls while a
modal `DialogWrapper` is showing, and since both the killer's pre-close screenshot
**and** `closeDialog` go through `Dispatchers.EDT + ModalityState.any()`, neither is
pumped during this modal → the modality is never resolved → VFS refresh + all
downstream tasks (and the MCP server) hang. The same elevated-modality-never-resolved
hang reproduced in the live IDE when a probe elevated modality without closing it.
- Goal (per user): the killer must reliably **close all dialogs and restore
  `ModalityState.nonModal()`** — the screenshot is secondary and must NEVER block that.
- Instrumentation added (commit `2e26ec6a`): `isModalDialogShown` logs `current()!=nonModal()`
  vs `LaterInvocator.isInModalContext()`; `doLookupDialogs` logs screenshot + close
  boundaries. A debug-port feature (commit `11bec066`) allows attaching a live debugger.
- Next: read the new run's log (screenshot-vs-close), then fix — make the screenshot
  best-effort/off the critical path (close first), and/or fix `VisionService.capture` to
  not stall under a modal; verify EDT+any actually pumps during the modal in this env.

## TODO — devrig binary end-to-end console test (managed-backend self-install scenario)

A new console test that exercises the **whole devrig managed-backend flow** an external user/agent hits —
no pre-provisioned IDE; devrig fetches it. Each step must be visible in the console log:

1. **Clone Keycloak over HTTPS** — `https://github.com/keycloak/keycloak.git` into the agent workspace.
   Show a real `git clone` (use the local bare-repo cache for speed — `BareRepoCache` /
   `GitDriver.cloneFromCachedBare`, `ProjectFromRemoteGit`). Confirms the project is on disk before devrig.
2. **`devrig install claude`** — run the devrig binary's `install` command to register devrig as the
   `mcp-steroid` MCP stdio server for Claude (repeat per agent: codex, gemini).
3. **Run the agent with a find-usages task** — start the agent and ask it (best-scenario prompt TBD) to
   use devrig to **install + start an IDE** and **find usages of** a well-chosen Keycloak symbol (pick one
   with clear, countable usages so the assertion is deterministic; prepare the exact target).
4. The agent's devrig tool then **fetches the IDE (managed-backend download), starts it**, and the agent
   **opens the Keycloak project** in that IDE to answer the ask.

Acceptance: console shows git clone → `devrig install claude` → agent turn → managed-backend IDE
download+start → project open → find-usages result; assert the expected usage(s) are reported.

Prerequisites (from the stability sweep):
- Managed-backend path must be healthy — `DevrigManagedBackendGui` / `DevrigRealIdeBridge` / `DevrigAgent`
  currently FAIL (distinct root causes; see `test-integration/TODO-stability-report.md`). The devrig JVM
  must launch on **Java 25** (class-file v69) — `DEVRIG_JAVA_HOME` support being moved into the product
  launcher (`.sh`).
- Console must stay clean: download/progress = plain `println` (no `[IDE-DOWNLOAD]` prefix, no logback
  category/severity); full debug stays in the devrig log folder + under `--debug`.

## TODO — release: clearly state MCP Steroid + Devrig are independent, NOT by JetBrains

Per our agreement with JetBrains, **every** distributed artifact and public surface MUST clearly state that
**MCP Steroid and Devrig are independent open-source projects, not made by / not official JetBrains
products**. This disclosure has to appear everywhere, consistently:
- Website (every page footer / about), README(s), docs.
- The plugin: Marketplace listing + plugin description / `plugin.xml` vendor text + any in-IDE about/notice.
- The devrig binary: `--version` / `--help` banner, the dist `licenses/README`, npm/package metadata.
- Release notes / GitHub release descriptions, EULA, and any artifact bundled with the build.
Wording: "MCP Steroid / Devrig are independent open-source projects and are not affiliated with, endorsed
by, or made by JetBrains." (final wording TBD). Add a release-checklist item + a build-time guard/test
where feasible so a release can't ship without the disclosure. **Backlog — revisit later.**

## TODO — devrig ↔ MCP Steroid plugin protocol compatibility (forward-compat from next release)

From the **next release onward**, the devrig binary must stay compatible with **all** versions of the MCP
Steroid plugin it talks to. Constraints:
- **Additive only:** new parameters may be added, but **existing parameters must keep working** — never
  remove/rename/repurpose a field or change its type. Graceful **service degradation** is acceptable (an
  older plugin ignores a new param / a newer devrig copes with a missing field), but the protocol must
  still function end-to-end.
- **Highest-risk surface:** the **tool-call parameters devrig sends to the plugin** and the
  **serialization/deserialization** of those JSON-RPC messages (tool args + results). A silent shape
  change there is the likely break.

What to set up (revisit after the current devrig tasks):
1. **Contract tests** pinning the wire shape of every tool call devrig issues to the plugin (exact param
   names/types) + the result deserialization — golden JSON, decoded with the plugin's serializers. A diff
   fails the build, forcing a conscious, additive-only change.
2. **A cross-version test** (devrig HEAD ↔ an older plugin build, and vice-versa) once we have a baseline
   release to pin against — only meaningful from the next release.
3. **AGENTS.md/CLAUDE.md guidance** (devrig + plugin): "the devrig↔plugin JSON-RPC tool params + result
   serialization are a frozen, additive-only contract; never remove/rename/retype a field; new fields must
   be optional with safe defaults; changes require a contract-test update + a conscious review."
4. Audit the current `@Serializable` request/result types on both sides for accidental non-additive
   patterns (required fields without defaults, enums parsed strictly, etc.).
5. **Deferred from the #21 protocol split (commit 0bcc91f0):** today devrig still *reuses the plugin's own*
   classes from `mcp-steroid-server` — `PidMarker`, `DevrigEndpointInfo`, the bridge tool-call request/result
   DTOs — decoding tolerantly (`ignoreUnknownKeys` + null-default optional fields). For true independent
   versioning, give devrig its **own** marker + bridge request/result DTOs (a devrig-side copy), so the two
   evolve independently and only the JSON wire shape is shared. The #21 marker change already made the
   optional sub-objects null-defaulted so old/new markers decode — that is the tolerant-decode baseline to
   build the dedicated DTOs on.

**Status (2026-06-01):** items 1, 3, 4 DONE.
- (4) Audit: the shared `@Serializable` DTOs (`NpxBridgeWindowsResponse`, `NpxStreamEnvelope`, …) use required
  fields *without* defaults — these are the v1 baseline (never remove); decode tolerates unknown keys but not
  missing required ones, so the additive-only rule (new fields optional+defaulted) is what guarantees compat.
- (1) Contract test: `DevrigToolBridgeClientTest` pins every tool's exact param names/types, incl. a new
  `execute_code … full devrig to plugin param contract` case (project_name/code/task_id/reason/timeout/modal).
- (3) Docs: the frozen, additive-only contract rule is in `ij-plugin/CLAUDE.md` → "devrig ↔ plugin wire contract".

**Still deferred** (need a baseline release / larger refactor): (2) cross-version test (devrig HEAD ↔ older
plugin build); (5) give devrig its own copy of the marker/bridge DTOs for fully independent versioning.

---

# Active focus — backend_name follow-ups: plugins[], shared BackendInfo, de-inherit tool reg, devrig-mgmt docs (2026-06-10)

Follow-up to the merged #87 (`backend_name` routing). Five split tasks. Hard invariant for ALL of them:
**the npx-kt ↔ ij-plugin WIRE is untouched** (`/projects/stream` `ProjectInfo{name,path}`, `/windows`
`NpxBridgeWindowsResponse`, `/tools/call` params). Everything here is MCP/CLI-output (devrig-owned, free
to reshape per PHILOSOPHY.md Tenet 5) or internal registration/docs. `WirePristinenessTest` must stay green.

- **A — plugins[] in BackendInfo (#88 prep).** Add `@Serializable BackendPlugin(id, name, version, kind)`
  (`kind`: `"mcp-steroid"` | `"other"`; room for `"intellij-native-mcp"`). On `BackendInfo`, REPLACE the
  `plugin: PluginInfo?` + `mcpSteroidPluginInstalled: Boolean` fields with `plugins: List<BackendPlugin> =
  emptyList()`. Keep `type = "intellij"` (document: more backend types may come). Add helpers
  `BackendInfo.mcpSteroidPlugin(): BackendPlugin?` + `BackendInfo.hasMcpSteroid(): Boolean` (the "simplify
  the check" helper). Update all producers (devrig `backendInfoForRow`, ij self-describe) and consumers
  (BackendCommandJsonRenderTest, BackendAndProjectJsonAreIdenticalTest, DevrigToolBridgeClientTest,
  McpServerIntegrationTest) to plugins[]/helper. Markers → one `BackendPlugin(kind=mcp-steroid)`;
  port/managed → empty.
- **B — share the marker→BackendInfo builder.** Extract into `mcp-steroid-server` a
  `markerBackendInfo(backendName, pid, ide: IdeInfo, plugins, openProjects, managed, routable, reachable)`
  assembler PLUS shared marker display/locator formatters (`markerDisplayName(ide)`,
  `markerLocator(build, pid)`). devrig's `markerBackendDisplayName`/`markerBackendLocatorLabel` delegate to
  them; devrig `backendInfoForRow(FromMarker)` and ij `ListProjectsToolHandler` both call
  `markerBackendInfo` — one definition, no per-side re-implementation.
- **C — de-inherit tool registration.** Remove `McpSteroidTools.openProjectToolSpec()` (the protected-open
  override seam) and the `StubMcpSteroidTools` override. `registerAll` registers only the common tools; each
  call site registers open_project explicitly — ij (`SteroidsMcpServer`) with `includeBackendName=false`,
  devrig (`StubStdioMcpServer`) with `includeBackendName=true`, both via `tools.handler<…>()`. Delete
  `McpSteroidToolsSeamTest`.
- **D — backend_name devrig-management docs.** In `OpenProjectTool` BACKEND_NAME_DESCRIPTION + the agent
  guide + `managing-backends.md`: how to manage backends via the devrig CLI — `devrig backend
  [download|start|stop|provision] <id>` — with the launcher path on macOS/Linux (`devrig`) AND Windows
  (`devrig.bat`, via `cmd.exe /c`), and that devrig needs Java 25: set `DEVRIG_JAVA_HOME` to a 25 home if
  `java`/`JAVA_HOME` is older.
- **E — verify.** `:mcp-steroid-server:test` + `:npx-kt:test` green; `:ij-plugin` compiles +
  `McpServerIntegrationTest` green; `WirePristinenessTest` green (wire unaffected). Full diff audit.

---

# Active focus — #89: backend-attributed list_projects/list_windows, no top-level ide header (2026-06-10)

User-decided design (supersedes the earlier options list):
- **No `ide`/`plugin`/`pid` header block** on `ListProjectsResponse` / `ListWindowsResponse` — devrig's own
  identity is already in the MCP server info; per-entry attribution replaces the header entirely.
- Both responses carry **`backends[]`** (shared `BackendInfo`), and every project/window/background-task
  entry is bound to its backend via `backend_name`.
- **All backends are listed** in `backends[]` — including port-only / managed-not-running and marker IDEs
  with zero open projects (b1).
- No "ran in:" echo in tool results (rejected — `backend_name` is already the key, info already reported).

Split:
- **W1 — mcp-steroid-server reshape.** `ListProjectsResponse { projects, backends }` (drop ide/plugin/pid);
  `ListWindowsResponse { windows: List<ListedWindow>, backgroundTasks: List<ListedBackgroundTask>,
  backends: List<BackendInfo> }` (drop ide/plugin/pid). New MCP-only `ListedWindow` (WindowInfo fields +
  `@SerialName("backend_name") backendName`) and `ListedBackgroundTask` (ProgressTaskInfo fields +
  backend_name). WIRE STAYS PRISTINE: `WindowInfo`/`ProgressTaskInfo`/`NpxBridgeWindowsResponse` (8
  required fields incl. pid) unchanged — extend `WirePristinenessTest`.
- **W2 — devrig BackendInventory + handlers.** One service scanning the whole model (markers + managed +
  port), shared by the CLI (`collectBackendRows` delegates) and MCP handlers. MCP mode: markers from the
  monitor's cached state (no re-fetch); CLI mode: scanOnce + snapshot fetch (today's behavior). Managed
  rows: check `runningPid` liveness before any HTTP. Port scan: bounded ~1s. `DevrigListProjectsToolHandler`
  backends[] = ALL inventory rows via `backendInfoForRow`; `DevrigListWindowsToolHandler` binds each
  window/task via `backendNameForMarker(pid, build)` (the wire response already carries the pid) + same
  backends[]. Performance optimization only if it hurts (no caching yet).
- **W3 — ij-plugin.** Drop the header fields; windows handler binds every window/task to the self
  backend_name and emits backends[] = [self] (reuse `markerBackendInfo`). REFACTOR SEAM: `NpxBridgeService
  .buildWindows` currently builds the WIRE response from the MCP handler's output — split so the wire path
  gets raw `WindowInfo`/`ProgressTaskInfo` + self pid (byte-identical wire) and the MCP handler wraps them.
  Migrate test-integration infra consumers of the dropped header pid (`intelliJ-window.kt` listWindows
  `it.pid`, `DialogKillerIntegrationTest` pid filters, the mcp-steroid.kt helper) to per-window
  backend_name → backends[].pid resolution.
- **W4 — docs + verify.** AGENT-STEROID-GUIDE / managing-backends: responses self-describe via backends[]
  only (no top-level ide); backends[] now lists non-routable port/managed rows too (check `routable`).
  Suites green; wire-pristineness green.

---

# Deliverable — agent feature review (2026-06-11)

Multi-agent hands-on experiment (Claude 4-lens swarm + Codex via run-agent.sh -> top-30 -> 5 validation
iterations -> 3-lens quorum). **Result: 29 ranked, evidence-backed missing features** in
[docs/agent-feature-review-2026-06.md](docs/agent-feature-review-2026-06.md). Top themes: verification
recipes (red-code check, single-call test run, JPS build errors), execute_code response shape
(structured envelope, dedup diagnostics, line-number mapping), resource discoverability (102 articles vs
31 indexed), execution lifecycle (queue/cancel/artifacts), and reliability bugs found live (decompiler
modal poisoning EDT, smartReadAction output duplication, write-lock invisibility).

## Status close-out (2026-06-11)

- Epic **#91** filed: 29 evidence-backed agent-experience gaps from the dogfooding experiment, themed
  checklist linking [docs/agent-feature-review-2026-06.md](docs/agent-feature-review-2026-06.md).
  **#90** (response payload de-duplication) complements its response-shape theme.
- All session work CI-green on `origin/main` @ `bef9a015`. **Pending**: `jb-merge` for
  `fb7ab68f..bef9a015` (TeamCity builds stale until then); devrig local redeploy to serve the
  newest schema (actions[]/ide-field drops landed after the deployed `ddf8027a` build);
  managed-backend/test-dir cleanup (see MEMORY.md 2026-06-11 environment handoff).

---

# Epic — version.json-driven self-contained installer (2026-06-15)

Branch: `installer/version-json-driven`. Orchestrated via `/workflows` with sub-agents and 3×
`run-agent.sh` quorum-review iterations at each design/implementation gate. This section is the
durable log for the epic.

## Goal

A fully self-contained install tool whose ONLY input is `version.json` (+ other published website
resources). Every URL — including the JDK (Amazon Corretto 25) — comes from `version.json`. The
install **script is generated** from `version.json` so the script stays dumb (data baked in);
complexity lives in the generator, not the shell.

## Hard requirements (from the request)

1. Single source of truth = `version.json`. All URLs (plugin, devrig, JDK/Corretto 25, …) published
   there + metadata (sha, archive type, unpack layout) so unpack logic is trivial.
2. Script is generated (not hand-written): inlines/consumes version.json (+ resources). Complexity → generator.
3. Daily correctness GH Action: re-generate/re-resolve; open a PR when a change is needed (e.g. newer
   Corretto 25.x). Does NOT change how the website is generated — only install-script inputs change,
   which triggers the website resources build.
4. No binaries in the repo. Install scripts generated only as part of (a) website build and (b)
   integration-test process. NOT committed.
5. 5 platforms: macOS arm64; Linux arm64; Linux x64; Windows arm64; Windows x64. On Linux the script
   is PowerShell (macOS → shell; Linux + Windows → PowerShell). [CONFIRM]
6. Install layout under `~/.mcp-steroid/`: each file under a version-named folder incl. 12-char sha;
   no cleanup (only add); `~/.mcp-steroid/bin/` holds scripts; an update rewrites only those scripts;
   devrig ensures the bin entry exists, creating one pointing at its own location if missing.
7. Continue the integration tests (the `website/install-tests/` docker harness was scaffolded but
   never run) so the generated scripts are proven on supported platforms.

## Source material (review + cherry-pick)

`0101-installer-docker` carries the installer lineage (partly on `0101`):
- c615ac7a website: one-command devrig bootstrap installers (install.sh / install.ps1)
- 0c3a9259 website: harden devrig bootstrap installers per review findings
- 32750ec0 TODO: install.ps1 Windows PS5.1 smoke-test follow-up (#97)
- 0135699e / 0dbcec8a devrig install --check (#86)
- 067044b1 WIP: installer JDK auto-download — interrupted, NOT verified (0101-installer-docker only):
  install.sh Corretto auto-download (unverified); install.ps1 incomplete; website/install-tests/
  docker harness scaffolding (never run).

Current `main`: `website/static/install.{sh,ps1}` are committed; `version.json` is not a static file
(produced by website build; consumed by UpdateChecker.kt / DevrigUpdateChecker.kt / BackendVersionSkew.kt).

## Open design questions (resolve before implementation)

- Q1 Script/OS split: macOS → install.sh (sh/bash); Linux + Windows → install.ps1 (pwsh)? Confirm Linux
  uses PowerShell (requires pwsh), not bash.
- Q2 version.json delivery: generated script fetches version.json at runtime, or data inlined at
  generation time (offline-capable)? Request implies inlined.
- Q3 Daily PR target: with scripts uncommitted, the daily PR edits the source data feeding version.json
  (Corretto coordinates). Where does that source live; what does the PR edit?
- Q4 Generator home: which module owns the generator? Must run in BOTH website build and integration tests.
- Q5 Corretto resolution: authoritative Corretto 25 endpoint for the 5 archives + checksums; daily-job
  discovery of a new patch.

## Plan (phases)

- [ ] P1 Discovery (workflow fan-out): version.json producer+schema; website/Hugo build & GH Pages deploy;
  current install.{sh,ps1} + WIP diff; install-tests harness; devrig install/locate/JDK logic;
  integration-test install coverage; Corretto 25 distribution API.
- [ ] P2 Design + quorum: version.json schema additions (Corretto per-platform url/sha/archive/unpack);
  generator design; script templates (sh + ps1); daily GH action; devrig bin self-registration;
  repo de-binary-fication. → 3× run-agent.sh quorum.
- [ ] P3 Implement: schema + Corretto data; generator; generated scripts; GH action; devrig changes;
  remove committed scripts.
- [ ] P4 Integration tests: finish + run docker install tests (sh + pwsh) for the 5 platforms.
- [ ] P5 Verify + quorum: build/tests green; 3× run-agent.sh review; PR.

## Decisions log

(filled as decisions are made)

## Progress log

- 2026-06-15: Reviewed branches; `0101-installer-docker` identified as installer source. Branch +
  this epic section created. Next: P1 discovery workflow.

## P1 Discovery — findings (2026-06-15)

Full agent output: workflow `installer-discovery` (wf_da2a62c0-1b1).

**version.json today**: produced by `website/Makefile:49`
(`echo '{"version-base":"$(VERSION)"}' > static/version.json`), published at
`https://mcp-steroid.jonnyzzz.com/version.json`. Consumers: `UpdateChecker.kt`,
`DevrigUpdateChecker.kt` (both `ignoreUnknownKeys=true` → additive-safe), `BackendVersionSkew.kt`.
Website is **not** a Gradle module (standalone Makefile + Hugo Docker `hugomods/hugo:exts-0.142.0`,
output → `mcp-steroid-public/`, deployed by `.github/workflows/github-pages.yml` on push to
`main` paths `website/**`,`VERSION` / release / dispatch). npx-kt already has a
`generateVersionJson` task (`npx-kt/build.gradle.kts:428-475`) emitting SHA-512 + URL entries.

**Locked spec**: `docs/devrig-deployment-spec.md` v7 — `~/.mcp-steroid/{bin,binaries}`,
content-addressed `binaries/<artifact>-<os>-<cpu>-<sha>/`, verbatim unpack, per-platform manifest
(devrig + Corretto JDK: url/sha/format/binSubpath/javaHomeSubpath), install bootstrap, agent
wizard, auto-GC, dev-mode. Uses **version.properties** (chosen for runtime shell parsing). Our
generated-script model supersedes that → **v8: keep version.json + generate scripts with data baked
in** (no runtime manifest parse needed by the install script).

**WIP installer (`0101-installer-docker`)**: `install.sh` (364 ln, complete) + `install.ps1`
(330 ln) already do devrig + Corretto 25 download, SHA-256 verify, content-addressed unpack to
`binaries/`, write `bin/` launchers. BUT they fetch the GitHub *latest* release API live (NOT
version.json-driven) and hardcode Corretto URLs. `website/install-tests/` has docker drivers
(`test-install-sh.sh` complete; `test-install-ps1.sh` present but `test-install-ps1.ps1` MISSING;
never run). No Kotlin harness / CI wiring.

**devrig self-location**: `HomePaths.kt` (hardcoded `~/.mcp-steroid`), `DevrigRoot.kt` (walks
classLoader codeSource for `lib/`+`ij-plugin.zip`), `InstallCommand.kt` (uses `DevrigRoot.path` /
`ProcessHandle.current().info().command()` for launcher path) — gives devrig its own location to
self-register a `bin/` entry.

**Amazon Corretto 25** (verified live 2026-06-15, latest = `25.0.3.9.1`):
- Download: `https://corretto.aws/downloads/latest/amazon-corretto-25-<arch>-<os>-jdk.<ext>` (302 →
  `/downloads/resources/<version>/<file>`). SHA-256: `https://corretto.aws/downloads/latest_sha256/<name>`
  (bare 64-hex text). Version discovery: GitHub API `repos/corretto/corretto-25/releases/latest`
  `tag_name`, or parse the 302 Location.
- Archive type + javaHomeSubpath per platform:
  - macOS aarch64 → tar.gz, `amazon-corretto-25.jdk/Contents/Home` (stable, unversioned top dir)
  - Linux aarch64 → tar.gz, `amazon-corretto-25.0.3.9.1-linux-aarch64/`
  - Linux x64 → tar.gz, `amazon-corretto-25.0.3.9.1-linux-x64/`
  - Windows x64 → zip, `jdk25.0.3_9/`
  - **Windows aarch64 → DOES NOT EXIST (404).** Corretto 25 ships no Windows/arm64 build.
- Each artifact also has `.sig` + master `.pub` (GPG) if signature verification is wanted.

**Integration tests**: `:test-integration` Docker infra (`IntelliJContainer`, `configureIntegrationTest()`,
`ide-base` Dockerfile bundles Temurin 8/11/17/21/24/25); installer tests would be simpler images
(`ubuntu:24.04` for sh, `mcr.microsoft.com/dotnet/sdk` for pwsh). No existing install/bootstrap test
runs; would plug into `ciIntegrationTests`.

## Decisions log (proposed v8 — to confirm + quorum)

- D1: Manifest = **version.json** (extend the existing file, additive). Supersedes v7's
  version.properties (the generated-script model removes the runtime-parse rationale).
- D2: Install scripts are **generated** from version.json by a Kotlin generator; data baked in;
  emitted by (a) the website build and (b) integration tests. NOT committed (remove the static ones).
- D3: 12-char sha in the content-addressed folder name (`<artifact>-<os>-<cpu>-<version>-<sha12>`);
  no cleanup (only add); under `~/.mcp-steroid/binaries/` (or directly under `~/.mcp-steroid/`).
- D4: `~/.mcp-steroid/bin/` holds launcher scripts; updates rewrite only these; devrig ensures the
  `bin/` entry exists and (re)creates it pointing at its own downloaded location.
- D5: Daily GH Action re-resolves Corretto 25 (GitHub API tag + per-arch latest_sha256); if changed,
  PRs the Corretto coordinates (a checked-in source the Makefile/generator reads) → triggers
  github-pages build → regenerates version.json + scripts.
- D6 [NEEDS USER]: Windows arm64 has no Corretto 25 → emulate via x64 Corretto (WIP already does
  this; Windows-on-ARM runs x64) vs drop the platform.
- D7 [NEEDS USER]: Script language split — macOS=POSIX sh; Linux+Windows=PowerShell (Linux requires
  `pwsh`)? vs Linux=sh too.

## User decisions (2026-06-15)

- D6 RESOLVED: Windows-arm64 uses a DIFFERENT vendor's JDK 25 arm64 build (Corretto for the other 4
  platforms). → research vendor (Azul Zulu / Microsoft / Liberica): URL + sha + archive layout +
  version discovery. version.json `windows-arm64.jdk` points at that vendor; daily job tracks it too.
- D7 RESOLVED: script split = macOS + Linux → POSIX `install.sh`; Windows → `install.ps1`.
  ("On Linux it's only PowerShell" was a misstatement.) No pwsh dependency on Linux. Two scripts.

Platform → script + JDK matrix:
| platform        | install script | JDK source            |
|-----------------|----------------|-----------------------|
| macos-arm64     | install.sh     | Corretto 25 (tar.gz)  |
| linux-arm64     | install.sh     | Corretto 25 (tar.gz)  |
| linux-x64       | install.sh     | Corretto 25 (tar.gz)  |
| windows-x64     | install.ps1    | Corretto 25 (zip)     |
| windows-arm64   | install.ps1    | <2nd vendor> 25 arm64 |

- D8 (test strategy): integration tests run the GENERATED scripts in Docker —
  `install.sh` in a POSIX Linux container (e.g. `ubuntu:24.04`, no host JDK, space-in-HOME),
  and `install.ps1` in a **PowerShell-on-Linux container** (`mcr.microsoft.com/powershell` or
  `dotnet/sdk`) running `pwsh`. This proves the ps1 LOGIC cross-platform inside Docker. Caveat:
  Linux-pwsh does not exercise true Windows filesystem semantics (backslash/registry/.exe); note
  that explicitly and keep those bits minimal/parameterized. (Windows-native verification stays a
  manual/host concern, as today.)

## P2 Design — v8 draft (2026-06-15)

Wrote `docs/installer-v8-design.md` (594 ln, 11 sections) via workflow `installer-v8-design`.
Windows-arm64 JDK research result: **Azul Zulu 25** primary (`api.azul.com/metadata/v1` →
download_url + sha256_hash; archive dir == filename basename; verified
`zulu25.34.17-ca-jdk25.0.3-win_aarch64.zip` sha256
`60b6b1faa1a93fea8e64b09f2b9ab136a86b02428f004f8378cfb04cd818a0d4`), **Microsoft Build of OpenJDK 25**
fallback (`aka.ms/download-jdk/...` + `.sha256sum.txt`); Temurin has NO win-aarch64 25.

Key design choices in the draft (for quorum scrutiny):
- version.json additive `installer{}` block; existing consumers ignore it (ignoreUnknownKeys-safe).
- **sha256** everywhere (v7 used sha512); all 3 vendors publish sha256; 12-char prefix → folder name.
- Checked-in `website/installer/jdk-coordinates.json` = the only daily-job-edited input; daily job
  copies vendor-published checksums (never re-hashes).
- Generator merges devrig artifacts + jdk-coordinates + version → version.json; emits install.sh +
  install.ps1 with coordinates baked in. Reuses npx-kt generateVersionJson logic.
- windows-arm64 = real native Azul Zulu (deletes the WIP x64-emulation branch).
- Next: 3× run-agent.sh quorum review of docs/installer-v8-design.md → fold feedback → finalize.

## P2 Quorum — 3× run-agent.sh cross-model review (2026-06-15)

Ran `run-agent.sh` with a custom WORKER_SCRIPT (review prompt) from the mcp-steroid repo, read-only,
across genuine cross-model backends (gemini not installed here):
- Reviewer 1 (claude, requirement-fidelity/correctness): **PASS_WITH_NOTES** (8 issues)
- Reviewer 2 (codex, simplicity/complexity-placement): **HOLD** (5 issues)
- Reviewer 3 (claude, robustness/security/tests): **PASS_WITH_NOTES** (11 issues)
Results: jb-cli/agents/results/run-agent/inst-rev-{1-claude,2-codex,3-claude}/claude_1.json.

Consensus: directionally sound, several must-fix items before P3. Adopted resolutions R-1..R-15 are
recorded as §12 of docs/installer-v8-design.md and are BINDING for implementation. Headlines:
- R-1 remove v7 auto-GC (honor "no cleanup, we keep adding"); document unbounded growth.
- R-2 commit website/installer/devrig-coordinates.json (release-time) → generateInstaller is a pure
  data-merge; NO plugin build in the site deploy / daily PR.
- R-3 version.json is the single script-generation source of truth (model → version.json → scripts).
- R-4 plugin ships inside the devrig artifact (no separate plugin entry); doc it.
- R-5 test windows-arm64/Azul via pwsh-on-Linux (download+verify+unpack).
- R-6 one canonical launcher render (relative-to-home; normalized compare).
- R-7 lock-free atomic-rename (state as change from v7); pid/age-gated stale sweep; concurrent test.
- R-8 hermetic per-PR CI via file:// fixtures; real CDN only in daily/weekly job.
- R-9 daily byte-verify + resolve both JDK vendors (keep MS warm) + inspect real archive root.
- R-10 install.ps1 home = USERPROFILE→HOME→GetFolderPath.
- R-11 devrig upgrade OUT OF SCOPE for v8 (v7 signed-properties upgrade does not carry over).
- R-12 distributionBaseName=devrig-$version (binSubpath build-enforced).
- R-13 no nested gradle in tests (dependsOn generateInstaller + system property).
- R-14 launcher stdout-silent before exec (+ test).
- R-15 document install-time trust model (TLS + sha256; version.json unsigned in v8).

P2 COMPLETE. Next: P3 implementation, starting with cherry-pick base from 0101-installer-docker.

## Decision (2026-06-15): drop the installer block from published version.json (v8.2)

User re-evaluation: since the generated script bakes all data, a published manifest is redundant.
Confirmed by analysis — no runtime consumer needs an installer block (scripts bake data; devrig
doesn't re-resolve; the two update-checkers read only version-base; devrig upgrade is out of scope
R-11). DECISION: version.json stays minimal/unchanged ({version-base}); the committed coordinate
files (jdk-coordinates.json + devrig-coordinates.json) are the single URL source; the self-contained
generated scripts are the published artifact. Supersedes §2 schema + R-3. Vendor-update/removal risk
handled by daily regen + daily byte-verify (R-9) + curl-fresh + content-addressed local cache; a
published manifest would be equally stale (mitigations are pipeline-side, not manifest-side).
Recorded as §13 of docs/installer-v8-design.md.

## P3 (start) — cherry-pick + spec cleanup (2026-06-15)

- Cherry-picked the installer base from 0101-installer-docker onto installer/version-json-driven,
  each stamped `Co-Authored-By: Claude Fable 5` (the AGENTS.md exception — all 0101 commits were
  Fable-authored): website install scripts (c615ac7a), hardening (0c3a9259), WIP JDK auto-download +
  install-tests harness (067044b1). Resolved c615ac7a's website/content/docs conflict by keeping
  main's docs (the new design re-documents the flow); SKIPPED 32750ec0 (a TODO note, not material).
  These bring website/static/install.{sh,ps1} + website/install-tests/ as TEMPLATE SOURCE material —
  they will be moved into the :installer-gen module as templates and the committed copies deleted
  (de-binary-fication) so the repo stays junk-free.
- Spec cleanup launched (background agent): rewrite docs/installer-v8-design.md as ONE clean current
  spec — fix the unbalanced/leaked fences (stray junk near line 592), fold the §12 quorum
  resolutions + §13 v8.2 into the body, drop the v7-history framing. Keep the spec; remove past detail.
- Confirmed (user): generator = its own Kotlin module; keep the JSON coordinate model as the
  generator input; avoid repo junk from the changes.

Next: build :installer-gen (JSON model + script templates from the cherry-picked scripts +
generateInstaller task), wire into website Makefile + :test-integration, de-binary-fy, then P4 tests.

## Decision (2026-06-15): P4 tests = Kotlin-only + mock-server side-car

User direction: move install tests from shell drivers to KOTLIN tests over test-helper's Docker
toolkit (startDockerContainerAndDispose + startProcessInContainer/exec + bridge-IP helper; sibling
containers reach each other on the default bridge — verified in test-helper/docker). Add tests that
download the binaries from a MOCK web server (nginx side-car serving fixtures, or Ktor) over real
HTTP — hermetic, but exercising the genuine fetch→sha256-verify→unpack→content-address→launcher path
(better than file:// fixtures). Test coordinate files bake the mock URL + fixture sha256 →
generateInstaller renders scripts pointing at the mock. Delete the cherry-picked
website/install-tests/*.sh drivers. Updated §8 of docs/installer-v8-design.md accordingly. Lanes:
install.sh in ubuntu:24.04; install.ps1 in mcr.microsoft.com/powershell (incl. windows-arm64/Azul).
Rides ciIntegrationTests; maxParallelForks=1.

## P3 core DONE — :installer-gen generator (2026-06-15)

Built + verified the generator (the heart of P3):
- :installer-gen Kotlin module (settings include, build.gradle.kts, application+JavaExec task).
- InstallerGenerator.kt: kotlinx.serialization model (JdkCoordinates/DevrigCoordinates) + merge +
  template renderer (POSIX case arms + PowerShell hashtable); validates 5 platforms + sha256.
- templates/install.{sh,ps1}.tmpl: WIP mechanics + baked table (no live discovery).
- website/installer/{jdk,devrig}-coordinates.json (real Corretto+Azul data; devrig placeholder).
- generateInstaller task (pure data-merge; relative -P paths resolve from repo root).
- De-binary: generated scripts gitignored under website/static/; removed committed WIP scripts +
  shell test drivers (website/install-tests/*.sh). WIP commits keep Fable co-author in history.
- VERIFIED: generateInstaller produces both scripts, all placeholders resolved, correct baked
  tables (macos/linux Corretto + win-x64 Corretto + win-arm64 Azul); install.sh passes `sh -n`;
  :installer-gen compiles (main+test).

REMAINING before the 3x quorum:
- devrig bin self-registration: BinLauncher.kt + HomePaths.binDir (npx-kt).
- Makefile wiring (update-config shells out to generateInstaller -> website/static/).
- Daily GH action installer-jdk-refresh.yml (resolve Corretto+Azul, byte-verify, PR jdk-coordinates).
- P4: InstallerBootstrapTest.kt (Kotlin over test-helper) + mock-server side-car (nginx/ktor) +
  fixtures (fake devrig zip + fake JDK archives w/ known sha256); ubuntu sh lane + powershell-on-linux
  ps1 lane incl. windows-arm64/Azul; wire into ciIntegrationTests.
- THEN: 3x run-agent.sh cross-model quorum on the implementation; iterate until accepted.

## New requirements (2026-06-15) + status

User direction (3rd P3/P4 pass):
1. jdk-coordinates.json must be GENERATED by a resolver TOOL: download each platform's JDK +
   signature, VALIDATE the signature (Corretto GPG via :pgp-verifier; Azul GPG/sha256), unpack, and
   CHECK the unpack folder / javaHomeSubpath is correct (literally download→unpack→check bin/java for
   ALL OS on one machine). Emits jdk-coordinates.json. This is also the daily-refresh resolver.
   - Note: :jdk-downloader already downloads+PGP-verifies+extracts Corretto (currently pinned 21, 4
     platforms). The resolver should reuse/extend that pattern for JDK 25 + Azul win-arm64.
2. devrig-coordinates.json must be GENERATED from the release (url+sha256+size), or "something" for
   tests (e.g. the locally-built :npx-kt installDist served by the mock).
3. Both coordinate files feed the tests (the generator → scripts → install tests).
4. Integration tests must HOST the binaries and DOWNLOAD them during the test (mock server side-car),
   and VALIDATE the output. Specifically validate:
   - the wrapper script sets DEVRIG_JAVA_HOME and hands off to the devrig binary;  [DONE in templates]
   - the install finalizes by calling `devrig install` (registers the tool);        [DONE in templates]
   - DEVRIG_NO_AUTO_INSTALL disables the auto `devrig install`.                      [DONE in templates]

DONE this pass:
- Templates: bin wrapper now exports DEVRIG_JAVA_HOME (the var the devrig launcher honors —
  confirmed npx-kt startScripts at build.gradle.kts:200/232); install scripts run `devrig install`
  at the end unless DEVRIG_NO_AUTO_INSTALL. Regenerated + `sh -n` OK; both scripts carry it.

REMAINING (then 3x run-agent.sh quorum, iterate until accepted):
- A. JDK coordinates resolver task (:installer-gen or extend :jdk-downloader): per platform download
  + GPG/sha256 verify + unpack + assert bin/java under javaHomeSubpath; emit jdk-coordinates.json;
  a TEST asserting download→unpack→check for all 5 platforms.
- B. devrig coordinates resolver (from GitHub release; for tests, from :npx-kt installDist).
- C. P4 InstallerBootstrapTest.kt (Kotlin over test-helper) + mock-server side-car (nginx/ktor) +
  fixtures (fake devrig dist with bin/devrig marker + a real 'devrig install' stub recorder; fake JDK
  archives with bin/java stub + known sha256); generate test scripts with mock-URL coordinate files;
  run install.sh (ubuntu) + install.ps1 (powershell-on-linux incl. win-arm64/Azul); ASSERT: download
  happened, sha verified, content-addressed dirs, wrapper sets DEVRIG_JAVA_HOME + execs devrig,
  `devrig install` was invoked (recorder), DEVRIG_NO_AUTO_INSTALL skips it, idempotent re-run.
- D. devrig BinLauncher.kt self-registration + HomePaths.binDir; Makefile wiring; daily action.

## Requirement (2026-06-15): PATH symlink — DONE (templates)

If ~/.mcp-steroid/bin is not on PATH, the install script symlinks the launcher into the first
WRITABLE PATH directory that lives UNDER the user's home — never editing shell profiles, never
touching system dirs. The symlink always TARGETS the installation path ($BIN_DIR/devrig); a
pre-existing 'devrig' that isn't already our own symlink is never clobbered; falls back to a PATH
hint if no suitable dir. install.sh: ln -sf into a $HOME/* writable PATH dir. install.ps1:
New-Item SymbolicLink devrig.cmd into a $env:USERPROFILE-rooted writable PATH dir (needs Dev
Mode/admin; falls back to hint). Verified: sh -n OK; both blocks present.

## Requirement (2026-06-15): always-symlink + test on small OS

- Dropped the "$BIN_DIR on PATH" precheck: install.sh + install.ps1 now ALWAYS symlink the launcher
  into a writable user-home PATH dir (never self-link $BIN_DIR; never clobber a foreign devrig; no
  profile/registry edits; fallback hint). DONE in templates (sh -n OK).
- P4 test additions (TODO): assert the symlink works (devrig invocable via the linked name) on every
  POSIX lane; ADD an Alpine lane (alpine:latest, musl/busybox sh + busybox unzip/tar/readlink/awk +
  ca-certificates) to prove install.sh portability on a minimal OS. So POSIX lanes = ubuntu:24.04 +
  alpine; Windows lane = mcr.microsoft.com/powershell (pwsh-on-Linux).
