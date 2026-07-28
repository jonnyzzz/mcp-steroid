# TODO

- [x] **Bundling devrig inside the IDE plugin — REJECTED** (2026-07-28). devrig is the future, not the
  plugin, so the plugin stays bundled inside devrig; devrig fetching the plugin on demand would add a
  runtime dependency on the plugin repository that can break in our own `backend download/start` flow.
  Freshness is solved devrig-side instead (devrig keeps itself current → so does the plugin it carries;
  known work, devrig-owned). Measurements + the full rationale:
  [`docs/devrig-bundled-in-plugin-spike.md`](docs/devrig-bundled-in-plugin-spike.md).
- [ ] **The IDE plugin is the migration path onto devrig** — it must move existing plugin users onto a
  correct, current devrig by running our canonical install scripts. The mechanism already exists
  (`DevrigSetup.kt:26-28` runs `curl … install.sh | sh` / `irm … install.ps1 | iex`, then
  `devrig connect claude`). Remaining gaps, in order of value:
  - **The offer is missable**: the onboarding notification group is `displayType="BALLOON"`
    (`plugin.xml:100`) → auto-hides in ~10 s, fires once per IDE run at project open, and no decision
    is persisted. Want `STICKY_BALLOON`, `Enable / Later / Don't ask again` with persisted state, and a
    status-bar widget as the always-visible fallback.
  - **"Installed" ≠ "migrated"**: `devrigInstalled()` (`OnboardingDecision.kt:36`) only checks that the
    launcher file exists, so an ancient devrig counts as done. Compare against `version.json` (the
    plugin already fetches it — `UpdateChecker.kt:58`) and offer the same install script to update.
    devrig's own `DevrigUpdateChecker` only prints to stderr, which a Claude user never sees.
  - **No progress on a ~611 MB download**: `ExecUtil.execAndGetOutput` buffers, so the background task
    shows static text for up to 30 min. Stream the installer's progress into the `ProgressIndicator`.
  - **No funnel data**: add `analyticsBeacon` events for offered → enabled → install-ok → connected.
  - **Inconsistent failure state**: the IDE path only notifies; the claude-plugin wrappers write
    `~/.mcp-steroid/markers/bootstrap-install.failed`. Write the same marker so `/devrig:status` and the
    SessionStart hook can see an IDE-side failure.
- [x] Fix `steroid_open_project` to trust a project path before opening it and cover the no-modal path with an integration test.
- [x] Agent-driven backend management: evaluated new MCP tool vs CLI passthrough vs hybrid (judge panel). **Decision: no new MCP tool** — agents manage backends via the existing `devrig backend …` CLI (fails the PHILOSOPHY 3-gate for a new tool; the CLI already does it). Shipped `mcp-steroid://open-project/managing-backends` recipe + aligned `open_project` to prefer a running devrig-managed backend (`DevrigProjectRoutingService.openProjectTargetIde()`).
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
