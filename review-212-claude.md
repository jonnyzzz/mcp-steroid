# Adversarial review — branch `fix/212-headless-mode-warn` (issue #212)

Reviewer: claude (independent). Diff reviewed: `git diff main...HEAD` (6 files, +218/−2).
Platform reference: `~/Work/intellij/community`.

## Headline finding

The recorded deviation — detecting unit-test mode via `Boolean.getBoolean("idea.is.unit.test")`
instead of `Application.isUnitTestMode` — is **not semantically equivalent in test-framework
processes**, and this is not theoretical: **this branch's own test run already fired the headless
WARN inside a unit-test process.**

Evidence:

- Production IDE process: equivalent. The production constructor reads exactly this property
  (community `ApplicationImpl.java:254`: `myTestModeFlag = Boolean.getBoolean("idea.is.unit.test")`).
- Test-framework process: NOT equivalent. The `@TestOnly` constructor
  (`ApplicationImpl.java:226–244`) hardcodes `myTestModeFlag = true` and never reads the property.
  The test bootstrap (`platform/testFramework/common/src/common/testApplication.kt:184`) uses that
  constructor, and sets `PluginManagerCore.isUnitTestMode = true` explicitly (line 147) precisely
  because the property is not reliably present.
- The only places in the platform that *set* the property are the Bazel test runner
  (`JUnit5BazelRunner.java:161`) and the starter framework (`VMOptions.kt:236`). The IntelliJ
  Platform Gradle Plugin **2.13.1** — the version this repo pins (root `build.gradle.kts:5`) —
  contains no reference to `idea.is.unit.test` anywhere in its jar (verified by extracting and
  scanning the artifact), and this repo's Gradle config does not set it either.
- **Empirical proof from this branch:**
  `ij-plugin/build/test-results/test/TEST-com.jonnyzzz.mcpSteroid.McpSamplingIntegrationTest.xml`
  (run 2026-07-03T08:56Z, after `IdeRunMode.kt` was created) captures in `<system-err>`:
  `WARN - #com.jonnyzzz.mcpSteroid.server.SteroidsMcpServer - MCP Steroid is running in a headless
  IDE. Headless mode is unsupported (best-effort): ...`
  i.e. the in-process test JVM (`isUnitTestMode=true`, property unset, `java.awt.headless=true`)
  classified as plain `HEADLESS`, logged the WARN, and (same root cause) appended the
  `HEADLESS_MCP_CLIENT_NOTICE` to the server instructions served to every in-process MCP test.

This directly violates issue #212's acceptance criteria: "Remote-dev backend and unit-test runs
produce the correct mode line and no WARN" and "Existing integration tests pass without new WARN
noise" (tests pass — `failures=0` — but with exactly the WARN noise the design forbids). It also
falsifies the KDoc claim in `IdeRunMode.kt:63–66` ("equivalent to the Application accessor") and
the comment in `IdeRunModeTest.kt:52` ("no headless WARN noise in tests") — the pure classifier is
right, but `detectIdeRunMode()` feeds it a wrong `isUnitTest` input in this environment.

## Per-dimension verdicts

### 1. Design compliance — FAIL (one point), otherwise compliant
- Precedence unit-test > remote-dev backend > headless > normal UI: correct in
  `classifyIdeRunMode` (`ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/IdeRunMode.kt:27-32`). ✓
- One INFO always: `logIdeRunMode()` called inside `startupLock`, before the bind attempt
  (`SteroidsMcpServer.kt:118`), matching the issue's placement ("recorded even if all ports are
  busy"). ✓ (Nit: if the bind fails on all ports, `port` stays 0 and a later
  `startServerIfNeeded()` call logs a second INFO line — "exactly one INFO" holds only when
  startup succeeds. Harmless, arguably desirable; not blocking.)
- WARN only plain-headless, MCP-client notice only plain-headless: correct in code
  (`SteroidsMcpServer.kt:135-138, 145-149`) — but the unit-test detection defect above makes the
  plugin's own test processes take the plain-headless path. ✗ (acceptance violated, see headline)
- No functional gating; server still starts in every mode. ✓

### 2. Recorded deviation (`Boolean.getBoolean("idea.is.unit.test")`) — FAIL
- Semantics identical to `ApplicationImpl` **only** in production processes
  (`ApplicationImpl.java:254`); NOT identical in test-framework processes (`@TestOnly` ctor
  hardcodes the flag; property unset under this repo's Gradle test runner). Empirically broken on
  this branch — see headline. ✗
- Guard test verification: `NoTestModeBranchingTest.kt:11-33` really does ban the token — it
  scans all `.kt`/`.kts` under `ij-plugin/src` (main **and** test) for the concatenated literal
  `"is"+"UnitTestMode"` and fails on any match. ✓ So the deviation was forced by the guard; the
  chosen substitute is the part that's wrong.
- Note: `PluginManagerCore.isUnitTestMode` is **not** a viable alternative — it is
  `@ApiStatus.Internal` and its own KDoc says "Use Application.isUnitTestMode instead"
  (community `PluginManagerCore.kt:86-92`).

### 3. API stability — PASS
- `IdeProductMode.isBackend`: exactly as the issue verified. Companion `@JvmStatic` getter,
  unannotated (`platform/platform-api/src/com/intellij/platform/ide/productMode/IdeProductMode.kt:19-22`);
  the `@get:ApiStatus.Experimental` `currentMode` (line 54) is referenced only inside platform
  bytecode, not by the plugin. Registered as an application service
  (`PlatformExtensions.xml:682-684`). File added to platform-api 2025-09-24 (commit `0682128`),
  so it exists in `sinceBuild = 261` (`ij-plugin/build.gradle.kts:297`). ✓
- Rejected internals stayed rejected: `AppMode` is class-level `@ApiStatus.Internal` and its own
  doc directs plugin code to `IdeProductMode#isBackend()` (community `AppMode.java`). ✓
- `Application.isUnitTestMode/isHeadlessEnvironment/isCommandLine` — public, no `@ApiStatus`
  annotations (community `core-api/.../Application.java`). ✓ No `@Internal` API is referenced by
  the diff. ✓
- Issue's precedence rationale spot-checked: `rdserver-headless` is mapped `HEADLESS` in
  `WellKnownCommand.java:94` — the backend-beats-headless ordering is genuinely needed. ✓

### 4. `runCatching` guard — PASS
- `isRemoteDevBackend()` (`IdeRunMode.kt:73`) wraps only the service lookup; any `Throwable` →
  `false` → classification proceeds; startup cannot be broken by it. ✓
- Two non-blocking observations: (a) `runCatching` also swallows
  `CancellationException`/`ProcessCanceledException`; since `buildServerInstructions()` runs
  during service construction (potentially under a cancellable container context), a swallowed
  cancellation degrades to misclassification only — acceptable here, worth knowing. (b)
  `detectIdeRunMode()`/`ideRunModeLogLine()` call `ApplicationManager.getApplication()` unguarded
  (platform type, null before app init) — unreachable in practice because both run at/after
  app-service construction. Not blocking.

### 5. Instructions-injection point — PASS
- `instructions = buildServerInstructions()` evaluates in the `mcpServer` property initializer,
  i.e. at `SteroidsMcpServer` service construction (`SteroidsMcpServer.kt:56-62`), earlier than
  `startServerIfNeeded()`. No correctness issue: all three classification inputs are launch-time
  process constants (AWT headless flag, product mode, unit-test property), so the two
  `detectIdeRunMode()` calls cannot disagree — except the theoretical case where the
  `IdeProductMode` lookup fails at construction and succeeds later (guarded → `false` →
  instructions would carry the notice while the log says backend). Theoretical, defensive-only;
  fine. The method reads no uninitialized instance state (`McpSteroidInfoPrompt` + top-level
  functions only). ✓

### 6. Docs — PASS (minimal and accurate)
- `README.md:74`, `website/content/docs/getting-started.md` (requirements bullet +
  troubleshooting entry), `website/content/docs/how-to-debug-ide.md` (support statement in the
  existing headless entry) — all match the issue's doc plan, all cite #177, all describe the WARN
  and the always-present `IDE run mode: ...` INFO line accurately (the INFO is indeed logged even
  when the bind fails, since it precedes the bind attempt). ✓
- Pre-existing nit (not this diff's doing): README requires "2026.1+" while getting-started says
  "2025.3+" — inconsistent baseline, untouched by this change.

### 7. `IdeRunModeTest` quality — ADEQUATE with one gap
- Pure-classifier coverage is complete: 4 single-flag cases, 3 precedence cases (backend beats
  headless; unit-test beats headless; unit-test beats both), with rationale comments matching the
  issue. Message-content assertions (#177 cited in WARN and notice, notice stays one line) are
  mildly tautological but cheap and pin the wire contract. ✓
- Gap: nothing exercises `detectIdeRunMode()` / `isUnitTestProcess()` in a real platform test
  environment — exactly the hole that let the headline misclassification ship. A
  `BasePlatformTestCase` asserting `detectIdeRunMode() == UNIT_TEST` would have failed on this
  branch. (Required change 2.)

### 8. Anything missed
- WARN/INFO text matches the issue verbatim; INFO includes all four raw flags. ✓
- Nit: `isUnitTestProcess`, `isRemoteDevBackend`, and both message constants are `public`
  top-level; `internal` would suffice (tests live in the same module). Not blocking.

## REQUIRED CHANGES

1. **Fix unit-test detection** so the plugin's own in-process tests classify as `UNIT_TEST`
   (today they classify `HEADLESS`: WARN in `McpSamplingIntegrationTest` stderr, headless notice
   appended to server instructions in every in-process MCP test). Preferred: make
   `isUnitTestProcess()` read `ApplicationManager.getApplication().isUnitTestMode` and add a
   narrow, commented allowlist for `IdeRunMode.kt` in `NoTestModeBranchingTest` — the guard's
   purpose is banning test-mode *branching*, and this file's KDoc already argues it is
   diagnostics-only classification. Alternative:
   `Boolean.getBoolean("idea.is.unit.test") || application.isUnitTestMode`. Do **not** use
   `PluginManagerCore.isUnitTestMode` (`@ApiStatus.Internal`).
2. **Add a platform-environment regression test** (`BasePlatformTestCase`) asserting
   `detectIdeRunMode() == IdeRunMode.UNIT_TEST`, pinning the acceptance criterion "unit-test runs
   produce the correct mode line and no WARN".
3. (Recommended, non-blocking) Correct the `isUnitTestProcess()` KDoc: the equivalence claim
   holds for production `ApplicationImpl` only; the test-framework constructor hardcodes the flag
   without the property.

VERDICT: APPROVE-WITH-CHANGES
