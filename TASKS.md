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
   `--version`/`--help` output (`DevrigRootCommand` / help rendering in `npx-kt/.../Cli.kt`, plus
   `printVersion`); (3) the dist `npx-kt/src/main/dist/licenses/README.md`;
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

## startable-backends follow-ups (PR #139 shipped the core)

PR #139 delivered: marker `ideHome` identity, `pluginPath` marker field, the four-group backend
taxonomy, dedup, running-managed exclusion, and `open_project` auto-start. Design + contract:
`docs/startable-backends-design.md`. Remaining:

12. **Live-prove running-managed exclusion from startable.** #139 covers it with unit tests at
    `startableBackends()`, `DevrigBackendService.candidates()`, and CLI render levels, but it was
    not live-verified by actually starting a managed IDE. Re-verify live now that Finding A has
    removed the 120 s timeout (start re-provisions vmoptions + the current plugin on every
    not-already-running start, so a managed IDE boots reachable).

13. **Group 4 (downloadable) — real catalog flow.** Today group 4 is advertise-only: the footer
    points at the full-cycle install command `devrig backend download <product>`. Decide whether
    `open_project` / `devrig backend` should ever enumerate concrete downloadable IDEs (catalog),
    or keep it advertise-only.
