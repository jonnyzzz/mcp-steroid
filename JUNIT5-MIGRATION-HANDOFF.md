# JUnit 5 migration — handoff

This branch (`junit5-migration`, lives only on `origin`) carries the in-progress
migration of `:ij-plugin`'s test suite from JUnit 3 / 4 to JUnit 5 (Jupiter).
The work landed on `main` first, was deemed not-fully-complete, and was moved
here for review while `main` reverted to a pre-migration state. The
`ExecutionEventBroadcaster` production fix (commit `d5fda889`) was a real
lifecycle bug surfaced by the migration and was **kept on `main`** — it's not
part of this branch's "revert me" scope.

## Status at handoff

| Layer | State |
|---|---|
| Local `:ij-plugin:test` | **247 / 0 fail / 1 skipped** — same as the pre-migration baseline (invariant held) |
| Vintage (`tasks.test`) | 111 tests, JUnit 3 BPC + leftovers, runs in its own JVM |
| Jupiter (`tasks.testJUnit5`) | 136 tests, run in a separate JVM (forced split, see below) |
| Total test files in Jupiter | 27 of 42 (64%) |
| BPC files migrated to `@TestApplication` | 20 of 35 (57%) |

## Architecture put in place

These pieces are stable and worth keeping for any continuation:

1. **JUnit BOM 5.13.4.** IntelliJ's `TestFixtureExtension` (auto-registered by
   `@TestApplication`) calls `ExtensionContext.getEnclosingTestClasses()`
   which was added in JUnit 5.13. With 5.11.4 every JUnit 5 IDE-fixture test
   class-init fails `NoSuchMethodError`.
2. **`testFramework(TestFrameworkType.JUnit5)` plus `Platform`.** Platform
   stays in `ij-plugin/build.gradle.kts` until every BPC test is migrated;
   JUnit5 does NOT transitively bring it in.
3. **Engine split into two separate test tasks.** `tasks.test` runs the
   Vintage engine; `tasks.testJUnit5` (`intellijPlatformTesting.testIde.
   register("testJUnit5")`) runs the Jupiter engine. They share the `test`
   source set; the engine filter (not the source set) decides which task
   picks each class up. `tasks.test.finalizedBy("testJUnit5")` so
   `:ij-plugin:test` triggers both. The split is **required** —
   `@TestApplication` and `BasePlatformTestCase` both manage the IntelliJ
   `Application` as a JVM-wide singleton, mixing them in one JVM tears
   down the Application at the wrong moment (`TestLoggerFactory: Already
   shutdown` against `FSRecordsImpl.connect`).
4. **`prepareSandbox_testJUnit5` wired** into the same content-copy block
   as `prepareTestSandbox` so the new sandbox carries kotlinc / ocr-tesseract
   / EULA. Without this every `executeWithProgress` style test would have
   failed with "Kotlinc executable not found".
5. **`ExecutionEventBroadcaster` project-disposable hook (kept on `main`,
   commit `d5fda889`).** The broadcaster stores `ExecutionState(project = …)`
   in an app-scoped `ConcurrentHashMap` and cleans it via a scheduled task
   that fires `DemoModeSettings.minDisplayTimeMs` after `onCompleted`. When
   the project disposed before that timer fired, the entry leaked a
   disposed `ProjectImpl` — JUnit 5's leak hunter caught this and failed
   every test that drove `ExecutionManager.evalCode`. The hook removes the
   entry the moment the project disposes; in production the natural
   completion path still wins.

## Migrated (move-with-this-branch)

These commits are the "JUnit 5 migration body" on this branch (newest first):

```
c0237c08  migrate ApplyPatchTest (840 LOC, 31 tests) to JUnit 5
b854514d  migrate 4 HIGH-tier (medium-sized) BPC tests to JUnit 5
cdc7d583  migrate 4 HIGH-tier (small) BPC tests to JUnit 5
526c2725  migrate 5 medium-tier BPC tests to JUnit 5 @TestApplication
fe8587ba  migrate 6 low-tier BPC tests to JUnit 5 @TestApplication
52e16433  split into Vintage + Jupiter test tasks (isolated JVMs)
889a6718  bump JUnit BOM 5.11.4 -> 5.13.4
6b7b5447  enable JUnit Platform; migrate 7 pure-JUnit-4 tests to Jupiter
```

Plus the broken Windows-fix attempt that lives here so it doesn't pollute
`main`:

```
850f6021  hand bundled 7z.exe to LocalIdeProvisioner for Windows IDE unpack
```

(`850f6021` does NOT fix the Windows TC regression — the Provider chain
hits a config-time chicken-and-egg with `:intellij-downloader:
extractSevenZipResources`. Left here as record of the attempt.)

## Deferred — needs per-file lifecycle work

15 files still on Vintage on this branch. Each one was tried in bulk-Agent
batches and reverted because of one of the patterns below — they need
focused, per-file investigation that is NOT bulk-mechanical.

### `:ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/`

| File | LOC | Test count | Known blocker |
|---|---|---|---|
| `execution/CodeEvalManagerTest.kt` | 60 | 2 | broadcaster-shape leak + kotlinc subprocess can't resolve `PsiStatement` under `@TestApplication` (Java/Kotlin bundled plugin not on `ScriptClassLoaderFactory.ideClasspath()`) |
| `execution/DialogKillerTest.kt` | 240 | 6 | One `dialogKillerClosesDialogBeforeExecution()` test times out at 30 s under `@TestApplication`. Test is EDT-modality-sensitive; needs `@RunInEdt(allMethods = true)` or per-method `@RunMethodInEdt`. Other 5 tests passed under the bulk attempt. |
| `execution/ExecutionManagerTest.kt` | 87 | 4 | Three `executeWithProgress*` tests time out at 30/60 s. `executeWithProgress` flows through `ExecutionEventBroadcaster`; even after the `d5fda889` fix, the project lifecycle under `@TestApplication`-managed Project disposal differs from BPC's per-method light-project. |
| `execution/ExecutionStorageTaskTest.kt` | 100 | 4 | `successFileAndWrappedScriptCreated()` times out at 30 s. Same coroutine-modality / project-lifecycle pattern as ExecutionManagerTest. |
| `execution/FindDuplicatesRecipeTest.kt` | 193 | 1 | Migration of all-or-nothing test class — one test, fails on kotlinc resolution. |
| `execution/McpScriptContextTest.kt` | 151 | 10 | All 10 tests failed — broadcaster + kotlinc classpath. Needs `@RegisterExtension` for the script context fixture. |
| `execution/ScriptExecutionAvailabilityTest.kt` | 39 | 1 | Timeout pattern. |
| `execution/ScriptExecutorTest.kt` | 202 | 8 | 5 of 8 timed out. |
| `koltinc/ScriptClassLoaderFactoryTest.kt` | 273 | 7 | 6 passed, 1 failed in the bulk attempt — partial migration risk. |
| `server/FetchResourceToolTest.kt` | 195 | n/a | **migrated** in `b854514d` — *correction:* this is already on Jupiter; leaving here only to note that the partial-migrate file accounting is messy. |
| `server/IdeExamplesExecutionTest.kt` | 919 | 20 | All 20 — both broadcaster leak and kotlinc-classpath compounding. |
| `server/LanguageSupportExecutionTest.kt` | 208 | 1 | Single test, fails on kotlinc resolution. |
| `server/LspExamplesExecutionTest.kt` | 286 | 10 | 9 passed, 1 failed in bulk attempt. |
| `McpServerIntegrationTest.kt` | 1994 | 31 | Largest file in the suite. Each test drives the MCP server, indirectly invoking kotlinc. Combination of both blockers + scale. |
| `SteroidsMcpToolsetTest.kt` | 158 | 6 | 2 passed, 4 failed. |
| `BaseTestCase.kt` | 27 | (helper) | Pure extension on `BasePlatformTestCase` (`setSystemPropertyForTest`, `setServerPortProperties`). Kept on BPC because its 12 callers above are still BPC. Migrate last, once all callers are off BPC. |

## Underlying blockers to fix BEFORE the next migration push

Both blockers are real production / test-infra bugs, NOT migration mechanics:

1. **kotlinc-subprocess classpath under `@TestApplication`.**
   `ScriptClassLoaderFactory.ideClasspath()` walks the loaded plugin set and
   composes a classpath for the kotlinc subprocess. Under `BasePlatformTestCase`
   the bundled `com.intellij.java` + `org.jetbrains.kotlin` plugins are on
   the platform's loaded set; under `@TestApplication` (JUnit 5) they aren't.
   Result: every `executeSteroidCode { … }` block in a migrated test fails
   with `unresolved reference: PsiStatement` / `ExtractMethodHandler` /
   similar Java/Kotlin-plugin API references.
   Likely fix: declare those bundled plugins explicitly in the
   `intellijPlatformTesting.testIde { register("testJUnit5") }` block, or
   teach `ScriptClassLoaderFactory` to fall back to the platform classpath
   in test mode.

2. **`@TestApplication` project lifecycle vs `timeoutRunBlocking`.**
   `BasePlatformTestCase` runs each test method against a fresh light
   project on the EDT (with modality state set); `@TestApplication` keeps
   one Application across the class and runs methods off-EDT by default.
   Tests that lean on EDT modality (`DialogKillerTest`) or that retain
   project state through coroutine context (`ExecutionManagerTest`)
   stall in `timeoutRunBlocking` under Jupiter.
   Likely fix per file: add `@RunInEdt(allMethods = true)` or
   `@RunMethodInEdt`, audit `withContext(Dispatchers.EDT)` blocks,
   inject `Disposable` via parameter instead of via `testRootDisposable`.

## Recommended approach for continuation

- Fix the kotlinc classpath issue **first** — that unblocks `IdeExamplesExecutionTest`,
  `LanguageSupportExecutionTest`, `LspExamplesExecutionTest`,
  `FindDuplicatesRecipeTest`, `ScriptExecutorTest`, `McpServerIntegrationTest`,
  `SteroidsMcpToolsetTest`, `CodeEvalManagerTest`, `McpScriptContextTest` —
  ~75 % of the remaining @Test methods.
- Then tackle EDT-modality files one at a time (DialogKillerTest is the
  prototype).
- Keep the **same-test-count invariant** (`./gradlew :ij-plugin:test`
  reporting 247 / 0 fail / 1 skipped) gate every migration commit.
- Don't bulk-dispatch via an agent — each remaining file is small enough
  to migrate inside one focused human pass, and the agent's revert-on-fail
  loop was the slowest part of this session.

## Reproducing locally

```bash
# (1) pull the branch
git fetch origin junit5-migration
git checkout junit5-migration

# (2) run the suite; both engines run sequentially
./gradlew :ij-plugin:test

# (3) summary numbers
find ij-plugin/build/test-results/test       -name 'TEST-*.xml' | wc -l   # Vintage classes
find ij-plugin/build/test-results/testJUnit5 -name 'TEST-*.xml' | wc -l   # Jupiter classes
```

## Files touched on `main` you may want to KEEP from this work

(These are on `main`; this branch carries them too. Not part of "revert me".)

- `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/demo/ExecutionEventBroadcaster.kt`
  — `Disposer.register(project) { activeExecutions.remove(executionId) }` —
  real lifecycle bugfix, surfaced by the migration but valid independently.
