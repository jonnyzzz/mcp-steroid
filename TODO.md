# TODO

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
  - [ ] Scenario A follow-up: `prompts/src/main/prompts/skill/native-mcp-tools.md` article
    (guard fence → LIST → schema-first → CALL → caveats), one full KtBlock matrix run before
    merge; same PR fixes stale `required_plugins` in `coding-with-intellij-patterns.md` (3 sites).

- [ ] **runInspectionsDirectly follow-ups (#69 ask 1)** — deliberately deferred, not work-in-progress.
  - *Deferred:* a `PsiFile`-accepting overload (and any richer per-file batch surface). It is a
    `McpScriptContext` surface growth — gated by PHILOSOPHY Tenet 3 / the 3-reviewer consensus, same
    as the explicit-`Project` overload (#94). Revisit only if that gate is cleared.
  - *Already shipped (2026-06, NOT part of this item):* per-tool crash isolation (#93), per-file
    PSI-invalid tolerance, and the additive `InspectionRunResult.failedTools` section — all without
    touching the argument list.

- [ ] Backend management follow-ups (deferred, surfaced during the design):
  - Stream download progress to the agent (downloads can take minutes; CLI is silent until done).
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
