# TASKS

Open tasks only. Finished work lives in git history, the per-module `CLAUDE.md`/`AGENTS.md`, the PR
descriptions, and the design docs under `docs/`. The historical running log (through 2026-06-19, ~4k lines
across the installer, npx-kt stabilization, prompts, apply-patch, managed-backend, EAP, and cleanup epics)
had its durable facts distilled into those permanent docs and was then removed; consult `git log` for the
detail behind any shipped item.

Discipline (standing rule): per change — design → implement → 0 WARNING+ IDE inspections
(`CodeSmellDetector` via `steroid_execute_code`) → 3× adversarial quorum before committing. Commits as
`Eugene Petrenko`, no AI co-author.

---

## Installer / website epic — remaining

Epic delivered via PRs #117–#127 (PR #113 closed as superseded; `install --check` revived in #127). See
the `installer-epic` memory for the full delivered list. Still open:

1. **Cut a devrig release from `main`.** The LIVE installer is broken on all platforms until then: the
   published `v0.100` binary predates the `install devrig` command (mac/linux: "no such option
   --install-script") and the Windows pathing-jar fix (windows: "input line is too long"). The new binary
   is already proven working (Mac/Linux live-install + the Mac-hosted Windows e2e). Follow
   `release/release-instructions.md`.
2. **Advance the `website` branch to `main` after that release** (`release-instructions.md` → "Stage 7c").
   This is what publishes the new install scripts to the live site; do it only once the matching release
   exists.
3. **Block S — daily vendor-JDK drift detection.** A scheduled job to catch when Corretto/Azul publish new
   JDK 25 builds or change shas. ⚠️ The version on `docs/devrig-install-oneliner` /
   `deferred/website-devrig-install-cta` is OBSOLETE (uses the deleted `CoordinateResolver`/`ResolverMain`);
   reframe around the live `:installer-gen:generateJdkModel` (it PGP-verifies on every run).
4. **Install one-liner surfacing.** Document the `curl … | sh` / `irm … | iex` one-liner (docs) + a
   devrig-first homepage CTA (website). Salvage the CONTENT from `docs/devrig-install-oneliner` +
   `deferred/website-devrig-install-cta` — their code halves are stale; do NOT merge the branches.
5. **Synchronous beacon flush for short-lived `install` subcommands.** `DevrigBeacon.capture()` is async
   (`scope.launch`), so a fast-exiting `install` / `install devrig` can exit before the "install executed"
   event sends. Add a synchronous capture + flush for all `install` subcommands.

---

## test-integration / IDE

6. **test-integration JDK pre-config is too slow (likely sleeps).** The per-test IDE JDK setup
   (`mcpRegisterJdks` / `waitForProjectReady`) feels sleep-bound. Quantify the time in JDK pre-config first
   (log timestamps), then replace blocking fixed sleeps / coarse poll intervals with event-driven waits.
   Suspects: `mcp-steroid.kt:664/731/800` (`delay(2_000L)`), `:544` (`delay(500L)`),
   `IdeTestHelpers.kt:152/161` (`Thread.sleep(50)`), `intelliJ-container.kt:307/315/354/384/415` poll loops.

> Done (removed 2026-06-19, triage-verified): the **DialogKiller modal hang** (RESOLVED 2026-05-31 —
> `docs/dialog-killer-modality-hang.md`; screenshot off the close path in `VisionService`/`DialogKiller`,
> modal-enum redesign) and the **devrig managed-backend e2e console test**
> (`DevrigManagedBackendAgentE2ETest`, on `main`, commits `cb018b33`/`4f2b7e1a`).

---

## Prompts corpus

7. **IMPROVEMENTS.md harness rollout (separate PR) — PARTIAL.** The two-task prompt + per-agent reflection
   harness has spread organically to `PrintCsvPrintToonPromptTest`, `TypeHierarchyPromptTest`,
   `StructuralSearchPromptTest` (+ `StructuralSearchYoutrackdbPromptShared`). Still open:
   (a) none of the named audit candidates (`ReferencesSearchPromptTest`, `FilenameIndexPromptTest`,
   `PsiClassLookupPromptTest`, `MavenRunnerAdoptionTest`, `ResourceReadingTest`,
   `WhatYouSeeTest.toolPreference`) are wired; (b) `extractImprovementsBlock`/`saveImprovements` are still
   copy-pasted `private fun` per test — promote to a shared helper; (c) `test-experiments/CLAUDE.md` still
   carries the "currently wired into FindDuplicatesPromptTest" caveat to drop when done.

8. **Reflection audit follow-up (issue #33).** Replace the two reflection-using `kotlin` recipes with typed
   APIs (research in `~/Work/intellij` first, then land each as its own KtBlock + in-process tests):
   - `lsp/hover.md` (lines 88, 93) — `javaClass.methods.find{"getType"}?.invoke(...)`. Typed: Kotlin
     `KtProperty.typeReference?.text` / `KtParameter.typeReference?.text` / analysis-API
     `KaSession.getReturnKtType`; Java `PsiVariable.type.canonicalText`.
   - `lsp/signature-help.md` (lines 82, 88, 96, 112) — same pattern, 4 sites in one snippet wrapped in a
     failure-hiding `try/catch`. Typed: Kotlin `KtNamedFunction.valueParameters`/`.typeReference`; Java
     `PsiMethod.parameterList.parameters`/`.returnType`.

---

## Release / cross-cutting

9. **Release independence disclosure — PARTIAL.** PRESENT: website footer + index/about, `plugin.xml`
   vendor + in-IDE notice + trademark line, `docs/devrig.md`, release notes 0.100. Still MISSING (all
   required): (1) `README.md` has NO disclosure — and its line-1 badge currently reads as an *official
   JetBrains incubator* project, directly contradicting the requirement (fix the badge too); (2) the devrig
   `--version`/`--help` banner (`HelpCommand.kt`); (3) the dist `npx-kt/src/main/dist/licenses/README.md`;
   (4) a positive independence statement in EULA (it only names JetBrains as a non-party); (5) a
   release-checklist item; (6) a build-time guard/test asserting the disclosure. (Backlog; wording TBD.)

10. **devrig ↔ plugin protocol forward-compat.** Additive-only wire contract. Done: (1) contract test
    `DevrigToolBridgeClientTest`, (3) `ij-plugin/CLAUDE.md` "devrig ↔ plugin wire contract" rule, (4) audit of
    the shared `@Serializable` DTOs. Still open:
    - (2) **cross-version test** — devrig HEAD ↔ an older plugin build (and vice-versa); only meaningful once
      there is a baseline release to pin against.
    - (5) **devrig-owned DTOs** — give devrig its own copy of the marker + bridge request/result DTOs (today it
      reuses `mcp-steroid-server`'s classes via tolerant decode) so the two evolve independently and only the
      JSON wire shape is shared.

11. **0.101 release gate — reconcile closed-but-not-on-`main` work (tracked in #102).** The 2026-06-19
    triage found issues marked done/closed whose code was reverted (commit `30236610`, "Revert the 0.101
    work batch") and never re-landed on `main`:
    - **#93 / #94 / #69** — `runInspectionsDirectly` on `main` is still a bare `InspectionEngine.inspectEx`
      (`McpScriptContextImpl.kt`): no per-tool crash isolation, no invalid-PSI tolerance, no `Project`
      param / structured findings.
    - **#99 / #100** — `devrig prompt` and `devrig exec-code --file` exist only on `0101-cli-commands`, not
      `main` (`Cli.kt` has only mcp/backend/project/install/help/version).
    Before 0.101: either re-land these onto `main` or re-open the issues so their state matches reality.
    Release gate **#92** (same-named-project routing) is also still open/unverified.
