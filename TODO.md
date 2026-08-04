# TODO

- [ ] **KtBlock matrix has no GoLand/WebStorm/RubyMine/DataGrip lane** (noted in the #406 quorum
  review). Unannotated (all-IDE) prompt fences are compile-verified only against
  Idea/PyCharm/Rider/CLion (stable+EAP) yet render in GO/WS/RM/DB at runtime. Risk is low while
  fences stick to platform-level APIs, but if it ever matters, extend `KtBlockCompilationTestBase`
  with a GoLand or WebStorm distribution — repo-wide infra task, not specific to any one article.

- [ ] **ProcessAiAgentCliRunner follow-ups** (issue #407 quorum review, all minor, non-gating):
  - [ ] An `InterruptedException` from the FIRST `waitFor(timeout)` propagates without killing the
    child, so on Windows the temp output file stays locked and leaks (loudly logged after the
    bounded delete retries). Kill the process tree on that interrupt lane too, then re-interrupt.
  - [ ] `ProcessAiAgentCliRunnerTest."no temp output files are left behind"` scans the
    machine-global `java.io.tmpdir`, so a concurrent run of the same suite on one machine can
    flake it (observed once during the quorum review). Make the runner's temp-file parent
    injectable and point the test at `@TempDir` for an isolated scan.

- [ ] **Tool/resource counts drift across surfaces** (found during the 2026-07-31 plugin-description
  rewrite review). The MCP tool surface is **8** (`docs/PHILOSOPHY.md` Tenet 1, canonical; confirmed
  against live registrations), but root `CLAUDE.md` and `ij-plugin/CLAUDE.md` say "10 today", and
  `README.md` still carries "### 8 MCP Tools" plus a stale "### 58 MCP Resources" heading with
  per-category counts summing to 60 while there are 106 prompt articles. Reconcile the CLAUDE.md
  numbers with PHILOSOPHY.md and de-count the README sections (headings without volatile numbers),
  the way the Marketplace description now does.

- [ ] **KtBlock matrix ignores the production kotlinc language/api pin (drift).**
  `CodeEvalManager` compiles every `steroid_execute_code` script with the
  `mcp.steroid.kotlinc.parameters` registry extras (`-language-version 2.3 -api-version 2.3` since
  2026-07-31; was 2.2), but `KtBlockCompilationTestBase.compileAgainst` passes only
  `-Werror`/`-jvm-target`/`-no-stdlib` — despite its "must match what KotlincCommandLineBuilder
  produces" comment. A prompt fence using a language feature/stdlib API newer than the pin passes the
  matrix yet fails at runtime. Fix: add the same LV/api flags to both the kotlinc invocation and the
  `compilerOptions` cache-key list in `KtBlockCompilationTestBase`. NOTE: this invalidates the whole
  KtBlock compile cache (60–120 min recompile) — land it right after a `kotlincVersion` bump (which
  invalidates the cache anyway), never casually.

- [ ] **steroid_input / take_screenshot #309 follow-ups** (the three core defects — modal-EDT hang,
  window-relative click coordinates, wrong window_id echo — are fixed and guarded by
  `SteroidInputDialogIntegrationTest`; remaining hardening surfaced by the 2026-07-30 review):
  - [ ] Ghost-input hazard: an input step already parked on the EDT when its MCP request dies
    (client disconnect / handler timeout) still fires later against whatever UI is current.
    Consider a cancellation check inside each EDT step before dispatching.
  - [ ] `steroid_take_screenshot` has no handler-level timeout either (P1's `withTimeout` was added
    to `steroid_input` only); capture() uses `ModalityState.any()` so it does not hang under modals,
    but a wedged EDT would still stall it — consider the same safety net.
  - [ ] Update issue #309: causal theory (window_id → P1/P2) disproven; Breakpoints dialog is
    non-modal (`setModal(false)`); tool docs could state coordinates are window-relative including
    decorations, and that `screen:` targets exist.

- [ ] **devrig auto-update — follow-ups** (core shipped per `docs/updates-check/devrig-auto-update.md`,
  3×-quorum approved 2026-07-30; branch `auto-update-install-scripts`):
  - [ ] `:test-integration` lane: drive the auto-update path end-to-end against the nginx-served
    installer fixtures (real `install.sh`, real `devrig install devrig` verify + marker authority).
  - [ ] Windows process-level coverage on a Windows runner: `superviseInstallerProcess` with
    `powershell.exe -File`, atomic replacement of a RUNNING `devrig.cmd`, and the
    sharing-violation → non-zero → quiet-retry degradation (retries are uncapped by design; quorum
    nit; needs the per-OS GH matrix).
  - [ ] Transitional ping-pong hint: when the launcher version keeps regressing tick-over-tick
    (a pre-launcher agent registration pointing at an old tree), extend the restart notice with a
    "re-run `devrig install <agent>`" hint.
  - [ ] Weekly URL-liveness GH Action: also assert live version.json ↔ install-script VERSION
    agreement (the release process now gates the website advance via
    `release/scripts/verify-release-ready.sh` + Stage 9 agreement checks, but a scheduled
    assertion would catch late CDN/publish drift too).
  - [ ] Install-script transfer timeouts (`curl --max-time` / `Invoke-WebRequest -TimeoutSec`,
    generous, e.g. 1 h): bounds the unsupervised-orphan window (design Tradeoff 5) with zero
    protocol complexity; benefits manual installs too. Template change in `:installer-gen`.
  - [ ] `binaries/` auto-GC (design Tradeoff 7): after an update lands, sweep `devrig-*` trees not
    referenced by the current launcher (keep one previous) — auto-update makes disk accretion
    automatic (~50–200 MB/release). The v7 deployment-spec auto-GC sketch is the model.

- [ ] **Native MCP tools — implement per `docs/native-mcp-tools-design.md`** (spec landed first;
  research 3×-quorum validated + live-tested on IU-261.25134.95, 2026-07-22):
  - [ ] Scenario B (chosen first step): `IntelliJMcpServerProbe.listNativeTools()` (+ drop the
    banned `internal` on `IntelliJMcpServerProbeImpl`), `GET …/native-tools` bridge route,
    `mcp-steroid-server` DTOs (`available`/`unfiltered` on the wire, no `backend_name`),
    `devrig project tools <project_name> [--json]` (ProjectCommand → `invokeWithoutSubcommand`),
    explicit 404="plugin too old" branch, WirePristinenessTest + contract pins,
    `:test-integration` canary (list + `find_files_by_glob` call), wire-table entry.
  - [ ] Scenario A follow-up: short static index `skill/native-mcp-tools.md` (guard + LIST
    fallback) with a live tool-index overlay, plus dynamic per-tool pages
    `mcp-steroid://skill/native-mcp-tools/<tool-name>` rendered fresh per fetch via a
    `NativeToolPagesHandler` seam in `FetchResourceToolHandler` (in-IDE: probe-backed; devrig:
    fed by the `/native-tools` bridge endpoint; shared renderer in `mcp-steroid-server`);
    one full KtBlock matrix run before merge; same PR fixes stale `required_plugins` in
    `coding-with-intellij-patterns.md` (3 sites).

- [ ] **runInspectionsDirectly follow-ups (#69 ask 1)** — deliberately deferred, not work-in-progress.
  - *Deferred:* a `PsiFile`-accepting overload (and any richer per-file batch surface). It is a
    `McpScriptContext` surface growth — gated by PHILOSOPHY Tenet 3 / the 3-reviewer consensus, same
    as the explicit-`Project` overload (#94). Revisit only if that gate is cleared.
  - *Already shipped (2026-06, NOT part of this item):* per-tool crash isolation (#93), per-file
    PSI-invalid tolerance, and the additive `InspectionRunResult.failedTools` section — all without
    touching the argument list.

- [ ] Backend management follow-ups (deferred, surfaced during the design):
  - [x] Launch managed IntelliJ Ultimate 2026.2 as a native Remote Development backend and prove the
    clean-machine Claude/Codex Keycloak hierarchy flow described in
    `docs/devrig-remote-development-backend-e2e.md`.
  - Apply the secret-safe environment allowlist to standard managed launches too, while explicitly
    retaining `http_proxy` / `https_proxy` / `no_proxy` variants needed by IDE networking.
  - Replace hardcoded `/usr/bin/setsid` / `/bin/setsid` lookup with a portable executable search so
    detached managed launches work on non-FHS systems such as NixOS.
  - [x] Snapshot PID + start identity before launch instead of excluding raw PIDs; serialize
    download/start/stop with one operation lock and refuse to rewrite the plugin of a live target.
  - Make failed-start cleanup diagnostics distinguish a deliberate identity-change refusal from a
    termination failure.
  - Move legacy archive migration under the global backend-operation lock, or prove the current
    idempotent moves safe when two fresh `BackendManager` instances initialize concurrently.
  - Revalidate the native Remote Development launcher for baseline 263+, using cold-CI telemetry to
    tune the 180-second readiness bound and the caller-cancellation behavior before widening support.
  - Put the pure Remote Development NDJSON parser/workflow contracts on a normal CI-backed task; the
    experimental task's direct-invocation guard currently keeps them out of aggregate CI runs.
  - Stream download progress to the agent (downloads can take minutes; CLI is silent until done).
  - Add bounded retry-on-read-timeout to the shared IDE downloader. It already resumes a pre-existing
    `.tmp` with `Range`, but a socket stall currently waits 15 minutes and fails the whole Gradle test or
    backend download instead of reconnecting and resuming within the same invocation (observed 2026-08-03).
  - Consider enriching `backend --json` / `backend download --json` with release date + download channel so agents can reason about staleness; consider exposing `IdeProduct` metadata (license tier, launcher) for richer IDE choice.
  - Optional explicit `open_project` target (by managed-backend id / pid) for the case where the agent wants a specific backend even when several are running — today the global lock makes "prefer managed" sufficient.

- **plugins[] enumeration (follow-up to closed #88):** surface more IDE plugins on `BackendInfo.plugins[]`
  (e.g. the built-in IDE MCP server as `kind: "intellij-native-mcp"`). Needs an additive wire extension:
  optional `PidMarker.plugins: List<PluginInfo>? = null` (ij-plugin writes relevant plugins; old devrig
  ignores unknown key; new devrig falls back to the singular `plugin` field), devrig-side id→kind
  classification, PidMarker contract-test updates. Spec in the #88 closing comments.

- [ ] **Fix the pre-existing `:prompts:test` failure** (broken on `main` since before 2026-06-09):
  `MarkdownArticleContractTest.testNoNonKotlinFences` fails on
  `debugger/debug-attach-remote-jvm.md` (5 ```text fences at lines 10/26/66/101/123). The contract
  bans non-kotlin fences; rewrite those blocks as prose/inline code or ```kotlin. Until fixed, every
  prompts contract run reports this one failure (sessions treat it as "green if sole failure" — debt).
- [ ] **devrig-naming.md id-scheme drift**: the naming-contract doc still specifies the old
  slug/bootHash exposed ids (`IntelliJ_IDEA_2025.3.3-AbC4Df01`) while the implementation has moved to
  `productCode-hash8` backend_names (`iu-9fk2a0xQ`) and pid-salted project names. The plugins[] section
  was fixed (2026-06-10); the id-scheme sections need their own reconciliation pass.
- [ ] **list_windows graceful degradation**: devrig's `steroid_list_windows` is all-or-nothing — one
  IDE failing its `/windows` fetch errors the whole call (`coroutineScope` + `error(...)`), unlike
  `list_projects` which degrades per-backend. Return partial windows + a per-backend error marker.

- [ ] **devrig CLI must own the `--wait` polling loop (#284)**: the schema-driven-command reshape
  removed the `out` parameter from `VisionScreenshotToolSpec` and turned `--wait` into a declared
  `CliExtraOption` on `steroid_open_project`, because neither is a tool input, so the tool metadata
  carries neither behavior. **`--out` is done**: it is a devrig framework flag registered only on
  `execute_code` and `take_screenshot`, and implemented by `renderWithOut` in `CliToolSupport.kt` (verified end to end —
  `devrig take_screenshot --out=<path>` writes the PNG and prints `Saved --out: <path>`). `--wait` is
  **not**: it parses, and a generic guard in `GeneratedToolRuntime.kt` then refuses with exit 64
  (`--wait is accepted by the command line but no runtime acts on it yet`). Implement it as a
  `list_windows` poll until the project reports initialized, and delete that guard plus its test.

- [ ] **`--json` parse-time usage errors emit nothing on stdout (#284)**: a parse failure becomes
  `DevrigCommandParseError`, which prints to stderr and answers 64 with no `--json` envelope — the KDoc
  argues the failure precedes the command's options so the `--json` intent is unknowable, yet the sibling
  help path already sniffs `--json`/`--debug` off the raw tokens (`Array<String>.jsonRequested()` in
  `Cli.kt`). The same sniff could drive a parse-error envelope for machine consumers. Decide whether the
  envelope is wanted; if yes, re-introduce a `commandName` recovery that survives non-boolean pre-command
  flags (`--out` falsified the old raw-token scan — see `DevrigCommandParseError`'s KDoc), and pin the
  contract either way (it is untested today).

- [ ] **`--project_name` is not inferred from the current directory (#284)**: `resolveProjectFromCwd`
  in `npx-kt/.../devrig/server/CwdProjectResolver.kt` is fully written and unit-tested (`One` / `None` /
  `Ambiguous`) but has **zero production call sites** — confirmed by PSI `ReferencesSearch`, not grep.
  Because no inference runs, `project_name` is simply a mandatory parameter: `CommonToolParams.projectName()`
  drops `.cliOptional()`, so the command-line parser itself demands it and `devrig execute_code`
  (or `take_screenshot` / `input` / `execute_feedback` / `fetch_resource`) run without `--project_name`
  fail at **parse time** — exit 64 naming `project_name`, before any tool call. The generated usage line
  renders it un-bracketed to say so. The generated help used to promise the inference; that sentence was
  removed rather than left lying (Task 9). Wiring the inference needs two decisions the Phase B plan never
  settled: what `CwdProjectMatch.Ambiguous` should print, and whether inference applies to every tool
  declaring `project_name` or only some. When it lands, restore `.cliOptional()` on `projectName()` (so
  the parser stops demanding it), and these deliberate reminders flip back: `McpToolsCliHelpTest`'s
  `the footer promises no cwd inference…` (restore the footer line in the same commit),
  `CliFileSourceUsageTokenTest`'s `a plain required parameter renders bare, demanded` /
  `and the parser really does demand it` (re-bracket the token, and the parser must stop demanding it).

  *Not* to be confused with the separate defect this entry used to describe — that the failure came out as
  exit 69 `… Usually no IDE backend is reachable`, misdiagnosing a reachable IDE. That was a missing
  `ToolCallErrorException` arm in `GeneratedToolRuntime.kt`'s error pipeline, fixed independently and
  pinned by `CliErrorEnvelopeTest`. It affected EVERY tool-side argument rejection, not just an absent
  `project_name`, so it would have outlived the inference work.

- [ ] **Agent harnesses must gate the first task turn on MCP initialization**: initialize instructions
  solve deferred-schema discovery only after the devrig MCP server reaches ready state. A Claude Code
  run can still begin while the server is `pending`, see no `steroid_*` names or instructions, and commit
  to shell text search before initialization completes. This is a client/harness readiness problem; add
  a regression in the agent launcher instead of another server prompt or MCP tool.

- [ ] **Pin an exact semantic oracle for the Keycloak Authenticator hierarchy E2E**: the headless-agent
  discovery scenario currently gates the pinned checkout with a 70-FQN lower bound plus known indirect
  implementors. Capture the canonical full set (or query it independently after the agent run) so future
  Keycloak fixture changes can distinguish exact completeness from a strong workflow regression signal.

- [ ] **Harden the CLI tool-spec metadata layer (#284 follow-up)**: three review findings deferred from
  PR #356. (1) `CliToolSpec.schema` exposes the mutable `ToolSchema` — any consumer can call
  `register()` after registration and change the advertised `inputSchema`; expose a read-only view
  (interface with `asMcpJson()`/`asCliParams()` only). (2) No declaration-time flag-collision
  validation: a parameter flag, its `cliFileSource` flag, and a tool-level `CliExtraOption` flag can
  collide — builder checks are order-dependent (`.cliFileSource("--x").cliFlag("--x")` passes) and a
  bare `--` is accepted as a flag; validate per-tool flag/alias uniqueness at registration or pin it
  with a `devrigToolSpecs()`-wide test. (3) Wire bounds declared via the `extra {}` closure
  (`success_rating` 0..1) are invisible to `asCliParams()`, while `timeout` carries a CLI-only
  `cliMinimum` — the generated CLI cannot enforce the wire bound without parsing `asMcpJson()`; also
  `cliSynopsis` hardcodes "(default 600)" where the MCP description interpolates the constant, so the
  two can silently diverge.

- [ ] **Harvest test coverage from the abandoned `issue-284-schema-driven-cli-phase-b` branch (#284)**: that
  parallel branch (superseded by `issue-284-cli-engine`, not merged) carries ~12 test classes this branch
  lacks — MCP-as-CLI contract/parse tests, per-command tests (execute_code / fetch_resource / feedback /
  screenshot), a layered `help execute_code` topic test, and a Docker live-IDE MCP-as-CLI smoke
  (`CliDevrigToolsIntegrationTest`). They assert against phase-b's command-class architecture, so port the
  INTENT into the generated-runtime structure, don't copy the files.

- [ ] **The `devrig --help` banner does not list `install plugin` / `install devrig` (#284)**: the curated
  `LIFECYCLE_COMMANDS` in `HelpCommand.kt` documents `install [claude|codex|gemini]` and `install config`
  (pinned by `DevrigCommandOutputTest` + `McpToolsCliHelpTest`), but the `install plugin` and `install devrig`
  subcommands have no banner entry (main did not document them either — pre-existing, not a Phase B
  regression). Add their one-line descriptions to the curated banner and extend the pinned test head.

- [ ] **red-code reporter false-positives on Kotlin files**: `reportProjectRedCode` (PSI reference scan,
  `mcp-steroid-import.kt`) reports Kotlin stdlib/operator references (`mutableMapOf`, `runCatching`,
  `trim()`, `!!`, `=`) as UNRESOLVED — 95/646 on the stock Gradle test-project's `SsrRunCatchingDemo.kt`
  while the project is actually green. Java-only Keycloak showed 1/25747, so the scan is sound for Java;
  the Kotlin path needs K2-aware handling for operator/implicit references (or skip
  `KtOperationReference`-style refs / restrict the sample to Java files). Non-fatal today (logged, never
  thrown), but the signal is noise for Kotlin projects. Found validating #200's settle on
  GradleCompileTest (2026-07-02).

- [ ] **`install --check` vs the literal Tenet-3 reading (review follow-up to #86)**: `--check` itself
  is read-only, but `runsTool()` in `npx-kt/.../Main.kt` returns true for `DevrigCommandInstall`, so the
  shared CLI startup still fires the PostHog beacon (`beacon.captureStarted`) and the background update
  check — and the beacon may write `~/.mcp-steroid/.devrig-user-id` on first run (`DevrigBeacon.distinctId`).
  This is common to every devrig tool command, not specific to --check. If a strictly side-effect-free
  `--check` ever matters (e.g. for CI probes), make `runsTool()` return `!check` for install — decide
  deliberately, since it also silences the update notice for that invocation (2026-06-12).

- [ ] install.ps1 Windows smoke test: the devrig bootstrap installer (#97) was verified end-to-end on macOS (sh) and parse/behavior-checked under pwsh in Docker, but has never executed on real Windows PowerShell 5.1 — run it on a Windows box before promoting the PowerShell one-liner beyond the docs page (2026-06-12).
- [ ] **inspect-and-fix recipe idiom follow-up (#81 review minor)**: the main recipe runs
  `InspectionEngine.inspectEx` under plain `readAction { }` while the cross-project section uses
  `smartReadAction` — unify on `smartReadAction` (kotlin-fence change → re-run the scoped
  `InspectAndFixKtBlocksCompilationTest`).
- [ ] **Hardcoded-URI lint gap (#81 review minor)**: `NoHardcodedMcpSteroidUriUsageTest` scans only
  ij-plugin/prompts/prompt-generator src/main — `mcp-steroid-server/src/main` is not covered and
  already carries a pre-existing `mcp-steroid://prompt/skill` literal in `FetchResourceToolHandler`'s
  param description. Extend the lint to that module and replace the literal.
- [ ] **ContentPart.kt `enterElseIf` bug (found by #98-t2 review, pre-existing)**: `ConditionalState.enterElseIf`
  overwrites `frame.previousFilters` with only the latest branch filter, so a 3+-branch chain
  `IF[A]/ELSE_IF[B]/ELSE_IF[C]` computes the third branch as `not(B).and(C)` instead of
  `not(A).and(not(B)).and(C)`. No current article uses 3+ branches, but the corpus now leans harder
  on conditionals — fix with a unit test before anyone writes one.
- [ ] **#98 residual corpus-escape vectors (by design, documented)**: SHORTHAND_LIST_PATTERN only matches the
  current list shape, and the availability audit is non-transitive (an article referenced only from a
  skill/-root article's body escapes). Extend if a future gating bug slips through.
- [ ] **DataGrip (DB) caveat**: test-run/debug articles are now fetchable in DB where they are meaningless
  (graceful error at runtime); add a one-line DB caveat if dogfooding surfaces confusion.

## IntelliJ-family IDE coverage (IU/IC/AI) — backlog

- [ ] **Integration test lanes for IntelliJ Community (IC) and Android Studio (AI).** The `[IU,IC,AI]`
  gating now claims IntelliJ-family Java/Kotlin/PSI/SSR/debugger recipes work in IDEA Ultimate, IDEA
  Community, and Android Studio. We currently only prove the Ultimate side (KtBlock compiles against the
  `idea` distribution; `PromptArticlePerIdeFetchIntegrationTest` covers the non-IU PyCharm/Rider/CLion
  negative direction). Add Docker IDE lanes (or KtBlock distributions) for **IC** and **AI** so a positive
  fetch + a representative `steroid_execute_code` recipe is proven on both. Needs an `IdeProduct.IntelliJCommunity`
  (`IC`) and Android Studio (`AI`) image/distribution.
- [ ] **API-difference audit near Spring etc.** Some Ultimate-bound APIs (Spring, `JUnitConfiguration`'s
  framework integrations, etc.) genuinely differ or are absent in IC/AI. The IC/AI lanes above will surface
  these — keep `[IU]`-only on the genuinely Ultimate-bound fences and split the recipe where the API differs.
- [ ] **Corpus-wide `[IU]` → `[IU,IC,AI]` sweep.** This PR converted only its own articles. Audit the rest of
  `prompts/src/main/prompts/**` for `[IU]` fences/sections whose APIs are actually in IC/AI and widen them
  (leaving genuinely Ultimate-bound ones, e.g. `skill/coding-with-intellij-spring.md`, as `[IU]`).

## Test-infra consent/stub findings (from #412 T7, Android Studio ConsentDialog)

- [ ] **JetBrains user-home consent stub path is likely dead weight.** `ideUserStartupConfigFiles()`
  writes `.config/JetBrains/consentOptions/accepted`, but `ConsentOptions.getConfirmedConsentsFile()`
  resolves `PathManager.getCommonDataPath()` = `${XDG_DATA_HOME:-~/.local/share}/<vendor>` on Linux —
  so the platform never reads the `.config` copy. JetBrains-IDE consent dialogs are actually
  suppressed by `-Djb.consents.confirmation.enabled=false` in the vmoptions. Either move the stub to
  `.local/share/JetBrains/consentOptions/accepted` (careful: devrig's ManagedBackend writes this list
  into REAL user homes — macOS resolves to `~/Library/Application Support/JetBrains/...`) or drop it.
- [ ] **`writeFileInContainer` to container-local paths leaves files root-owned.** The `docker cp`
  branch creates files as `root:root` (mode from the host temp file, umask 0644): readable by the
  uid-1000 `agent` IDE but NOT rewritable. Anything the IDE must open read-write from `$HOME` cannot
  use it — `writeAndroidStudioConsentStubs` in `intelliJ.kt` had to shell-write as the `agent` user
  for exactly this reason (`AnalyticsSettings` opens its file with `RandomAccessFile(file, "rw")`).
  Audit the other `/home/agent/...` stubs (`.java/.userPrefs/...`) for the same read-write trap.
- [ ] **`AndroidStudioRuntimeCompatTest` KDoc claims AS 2026.1 bundles JBR 21 — it ships JBR 25.**
  Both #412 AS-lane runs log `JDK: 25.0.2` in idea.log for AI-261.26222.65 (2026.1.3). The
  bytecode-21 gate itself stays valuable (issue #157: older AS + minimum-supported baselines), but
  the KDoc's "AS 2026.1 bundles JBR 21" premise is stale and should be reworded against reality.

- [ ] **Console mode prints a JSON payload as one minified line (#284)**: `devrig list_projects` (and any
  generated tool whose result is a single JSON text item) emits one long minified blob, because
  `Presentation.Console.render` prints a text content item verbatim. The fix does NOT need a per-tool
  renderer: pretty-printing a text payload that happens to parse as JSON is tool-agnostic, so it belongs in
  `Presentation.Console` in `CliToolSupport.kt`. Deliberately **console-only** — the `--json` envelope now
  unpacks a JSON text payload under a `json` key (`contentDataJson`, so `jq` reaches it in one parse); that
  path is settled and must not be reshaped again for a console concern. A richer per-tool table
  (`devrig project`-style columns for the listers) is a different, larger question: it would need declared
  rendering metadata, since a `when (toolName)` is exactly what #284 removes.
