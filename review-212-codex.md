# Review 212 — codex

Diff reviewed: `main...HEAD` on `fix/212-headless-mode-warn`.
Issue reviewed: `gh issue view 212 -R jonnyzzz/mcp-steroid`.
No Gradle/build/test command was run.

## Verdicts by Dimension

1. **Design compliance: APPROVE.**
   `classifyIdeRunMode` implements first-match precedence exactly as specified: unit-test, remote-dev backend, headless, normal UI (`ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/IdeRunMode.kt:27`). `logIdeRunMode` always logs the INFO line and emits WARN only for `IdeRunMode.HEADLESS` (`ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/SteroidsMcpServer.kt:135`). The MCP-client notice is appended only when `detectIdeRunMode() == HEADLESS` (`ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/SteroidsMcpServer.kt:147`).

2. **Unit-test detection deviation: APPROVE, with caveat.**
   The production `ApplicationImpl` constructor initializes `myTestModeFlag` from `Boolean.getBoolean("idea.is.unit.test")` (`/Users/jonnyzzz/Work/intellij/community/platform/platform-impl/src/com/intellij/openapi/application/impl/ApplicationImpl.java:246`, `:254`), and `isUnitTestMode()` returns that field (`:356`). The code uses the same property without referencing the banned token (`ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/IdeRunMode.kt:62`). Caveat: the test-only `ApplicationImpl(CoroutineContext, boolean)` constructor sets `myTestModeFlag = true` directly (`/Users/jonnyzzz/Work/intellij/community/platform/platform-impl/src/com/intellij/openapi/application/impl/ApplicationImpl.java:225`, `:234`), so the property is not a universal semantic equivalent for every in-memory test application. For normal IDE startup, which is what this runtime diagnostic targets, it matches.

3. **Repo guard for banned token: APPROVE.**
   `NoTestModeBranchingTest` constructs the forbidden token as `"is" + "UnitTestMode"` and scans all Kotlin/KTS files under `ij-plugin/src`, failing on any occurrence (`ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/NoTestModeBranchingTest.kt:17`, `:20`, `:23`). The branch avoids the token in production code.

4. **API stability: APPROVE.**
   `Application.isUnitTestMode`, `isHeadlessEnvironment`, and `isCommandLine` are public declarations with no `@ApiStatus.Internal` annotation at their declarations (`/Users/jonnyzzz/Work/intellij/community/platform/core-api/src/com/intellij/openapi/application/Application.java:574`, `:582`, `:590`). `IdeProductMode.isBackend` is the public companion getter (`/Users/jonnyzzz/Work/intellij/community/platform/platform-api/src/com/intellij/platform/ide/productMode/IdeProductMode.kt:16`, `:20`), while only `currentMode` is marked experimental (`:54`). `AppMode` is correctly not used by the plugin; the platform source marks it internal and documents `IdeProductMode#isBackend()` as the plugin-code equivalent (`/Users/jonnyzzz/Work/intellij/community/platform/core-api/src/com/intellij/idea/AppMode.java:16`, `:55`). The service is registered as an application service (`/Users/jonnyzzz/Work/intellij/community/platform/platform-resources/src/META-INF/PlatformExtensions.xml:681`).

5. **`runCatching` guard/startup safety: APPROVE.**
   `isRemoteDevBackend()` uses `runCatching { IdeProductMode.isBackend }.getOrDefault(false)` (`ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/IdeRunMode.kt:69`), which complies with the repo ban because it is not `runCatching{}.onFailure{}`. A failed backend-mode lookup cannot prevent startup; worst case, diagnostics classify without the backend bit.

6. **Instructions injection timing: APPROVE.**
   `McpServerCore` stores instructions passed at construction (`mcp-core/src/main/kotlin/com/jonnyzzz/mcpSteroid/mcp/McpServerCore.kt:11`, `:14`) and returns that frozen value in `InitializeResult` (`mcp-core/src/main/kotlin/com/jonnyzzz/mcpSteroid/mcp/McpServerCore.kt:218`, `:222`). `SteroidsMcpServer` constructs the core as an application service and calls `buildServerInstructions()` there (`ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/SteroidsMcpServer.kt:56`, `:61`). That is acceptable because the relevant flags are process-mode state by the time the service is requested from `appFrameCreated` / project startup (`ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/SteroidsMcpServerAppLifecycleListener.kt:16`, `:19`; `ij-plugin/src/main/kotlin/com/jonnyzzz/mcpSteroid/server/SteroidsMcpServerStartupActivity.kt:25`). `IdeProductModeImpl` is a simple service over `ProductLoadingStrategy.strategy.currentModeId`, with no dependency cycle visible (`/Users/jonnyzzz/Work/intellij/community/platform/platform-impl/src/com/intellij/platform/ide/productMode/impl/IdeProductModeImpl.kt:8`, `:11`).

7. **Docs edits: APPROVE.**
   README adds a minimal requirements statement with the unsupported headless caveat and issue link (`README.md:74`). Getting-started adds the same requirement plus a short troubleshooting entry pointing at the new WARN and INFO lines (`website/content/docs/getting-started.md:21`, `:76`). The debug guide updates the existing headless section without broad rewrites (`website/content/docs/how-to-debug-ide.md:721`).

8. **Test quality: APPROVE, with non-blocking gap.**
   `IdeRunModeTest` covers all four base modes and both relevant precedence cases, including unit-test over backend/headless (`ij-plugin/src/test/kotlin/com/jonnyzzz/mcpSteroid/server/IdeRunModeTest.kt:10`, `:42`, `:52`, `:61`). It also pins the headless warning/client notice issue reference and one-line client notice (`:69`). The remaining gap is that the actual logging path and initialize-instructions append are not tested end-to-end; given the issue asked for pure classifier coverage, I do not consider that a required change.

9. **Anything missed: APPROVE.**
   `git diff --check main...HEAD` is clean. The implementation does not add new MCP tools/context helpers, does not use `@Internal` IntelliJ APIs, does not add `internal` visibility in repo code, and does not weaken tests.

## Required Changes

None.

VERDICT: APPROVE
