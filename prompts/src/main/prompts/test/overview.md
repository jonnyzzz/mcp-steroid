Test Execution Examples Overview

Overview of IntelliJ test execution, result inspection, compile checks, Maven/Gradle runners, and testing workflow patterns.

# Test Execution Examples Overview

This directory contains runnable examples for IntelliJ test execution and result inspection APIs.

## Preferred Workflow — Run Test at Caret (IDE-agnostic)

```
1. Open test file in editor
   ↓
2. Position caret on test method or class
   ↓
3. Fire RunClass / DebugClass (IntelliJ) or RiderUnitTestRunContextAction (Rider)
   — Action title shows test name: "Run 'myTestMethod'" / "Debug 'myTestMethod'"
   — Default shortcut: Ctrl+Shift+F10 (run) — shown in gutter icon tooltip
   ↓
4. Poll RunContentManager → ProcessHandler.isProcessTerminated()
   ↓
5. Inspect results via SMTRunnerConsoleView
```

See `mcp-steroid://test/run-test-at-caret` for the full runnable example.

## Alternative Workflow — Run by Named Configuration

```
1. List run configurations
   ↓
2. Execute test configuration (ProgramRunnerUtil)
   ↓
3. Find RunContentDescriptor via RunContentManager
   ↓
4. Get execution console → descriptor.getExecutionConsole()
   ↓
5. Cast to test console → console as? SMTRunnerConsoleView
   ↓
6. Get results viewer → consoleView.getResultsViewer()
   ↓
7. Access test tree → resultsForm.getTestsRootNode()
   ↓
8. Inspect results → testProxy.isPassed(), isDefect(), getChildren(), etc.
```

## Demo Sanity Check (This Repo)

Use the demo test `DemoTestByJonnyzzz` in `com.jonnyzzz.mcpSteroid.ocr` to verify the full flow:

1. Create or select a run configuration for `DemoTestByJonnyzzz`
   - Add VM option: `-Dmcp.demo.by.jonnyzzz=true` (the test is skipped otherwise)
2. Use [List Run Configurations](mcp-steroid://test/list-run-configurations) to find its name
3. Start it with [Run Tests](mcp-steroid://test/run-tests) (Run or Debug executor)
4. Poll with [Wait for Completion](mcp-steroid://test/wait-for-completion)
5. Inspect results via [Inspect Test Results](mcp-steroid://test/inspect-test-results)

Or run [Demo Debug Test](mcp-steroid://test/demo-debug-test) for a one-call end-to-end debug flow.

## Available Examples

### Basic Test Execution

- **`run-test-at-caret.md`** - Run/debug at caret position (IDE-agnostic, preferred)
- **`run-test-class-structured.md`** - Run one test class and return structured JSON (Gradle-backed in IDEA; SM-based run configuration elsewhere)
- **`list-run-configurations.md`** - List all run configurations in the project
- **`run-tests.md`** - Execute a named test run configuration
- **`wait-for-completion.md`** - Wait for test execution to complete
- **`inspect-test-results.md`** - Access and inspect test results

### Test Result Navigation

- **`test-tree-navigation.md`** - Navigate test tree structure
- **`test-failure-details.md`** - Access failure messages and stack traces
- **`test-statistics.md`** - Get test counts (passed/failed/ignored)

### Advanced Patterns

- **`demo-debug-test.md`** - End-to-end debug run for the demo test
- **`listen-execution-events.md`** - Use ExecutionListener for execution lifecycle
- **`access-test-output.md`** - Read test console output
- **`find-recent-test-run.md`** - Access most recent test execution

## Key API Classes

### Run Configuration Management
- `RunManager` - Access run configurations
- `RunnerAndConfigurationSettings` - Configuration settings
- `ProgramRunnerUtil` - Execute configurations
- `ExecutorRegistry` - Get executors (Run, Debug, etc.)

### Test Execution Results
- `RunContentDescriptor` - Handle to execution results
- `SMTRunnerConsoleView` - Test console view
- `SMTestRunnerResultsForm` - Test results form with tree
- `SMRootTestProxy` / `SMTestProxy` - Test tree nodes
- `AbstractTestProxy` - Base class for test nodes

### Process Management
- `ProcessHandler` - Control and monitor process
- `ExecutionListener` - Listen to execution lifecycle events
- `RunContentManager` - Access all running/completed processes

## Common Patterns

### Pattern 1: Execute and Wait

```kotlin
import com.intellij.execution.RunManager
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ui.RunContentManager

// Execute configuration
val manager = RunManager.getInstance(project)
val setting = manager.allSettings.first { it.name == "MyTests" }
val executor = ExecutorRegistry.getInstance().getExecutorById(DefaultRunExecutor.EXECUTOR_ID)!!

withContext(Dispatchers.EDT) {
    ProgramRunnerUtil.executeConfiguration(setting, executor)
}

println("Execution started: ${setting.name}")

// Second call - poll for completion
val runContentManager = RunContentManager.getInstance(project)
val descriptor = runContentManager.allDescriptors.lastOrNull { it.displayName == "MyTests" }
val handler = descriptor?.processHandler

if (handler?.isProcessTerminated == true) {
    println("Test execution completed with exit code: ${handler.exitCode}")
} else {
    println("Tests still running...")
}
```

### Pattern 2: Access Test Results

```kotlin
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView

val manager = RunContentManager.getInstance(project)
val descriptor = manager.allDescriptors.lastOrNull()

// Get test console
val console = descriptor?.executionConsole as? SMTRunnerConsoleView
if (console == null) {
    println("Not a test execution or results not available")
    return
}

// Get test tree root
val resultsForm = console.resultsViewer
val rootProxy = resultsForm.testsRootNode

// Inspect results
val allTests = rootProxy.allTests
val passed = allTests.count { it.isPassed }
val failed = allTests.count { it.isDefect }
val ignored = allTests.count { it.isIgnored }

println("Test Results:")
println("  Passed: $passed")
println("  Failed: $failed")
println("  Ignored: $ignored")
println("  Total: ${allTests.size}")

// Print failures
rootProxy.children.forEach { test ->
    if (test.isDefect) {
        println("\nFailed: ${test.name}")
        println("  Error: ${test.errorMessage}")
        println("  Stack: ${test.stacktrace?.take(200)}...")
    }
}
```

### Pattern 3: Navigate Test Tree Recursively

```kotlin
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView

fun printTestTree(proxy: AbstractTestProxy, indent: String = "") {
    val status = when {
        proxy.isPassed -> "✓"
        proxy.isDefect -> "✗"
        proxy.isIgnored -> "○"
        proxy.isInProgress -> "→"
        else -> "?"
    }

    println("$indent$status ${proxy.name} (${proxy.duration}ms)")

    proxy.children.forEach { child ->
        printTestTree(child, "$indent  ")
    }
}

val manager = RunContentManager.getInstance(project)
val descriptor = manager.allDescriptors.lastOrNull()
val console = descriptor?.executionConsole as? SMTRunnerConsoleView
val rootProxy = console?.resultsViewer?.testsRootNode

if (rootProxy != null) {
    printTestTree(rootProxy)
}
```

## Threading Considerations

- Execute run configurations on EDT: `withContext(Dispatchers.EDT) { ... }`
- Access PSI/VFS in read actions: `readAction { ... }`
- Poll completion status from background thread (no EDT blocking)
- Test tree navigation can be done on any thread after tests complete

## Stateful Execution

Remember that each `steroid_execute_code` call runs in the same IDE process; state persists between calls:

1. **Call 1**: Start test execution
2. **Call 2+**: Poll for completion (quick, non-blocking checks)
3. **Final call**: Inspect results after completion

This pattern avoids timeout issues and provides better feedback to the agent.

---

## Pre-Test Compile Checks

### Incremental Compile Check After Bulk File Creation (~10s vs ~90s for mvnw test-compile)

After creating multiple files in a batch `writeAction`, run a project-wide incremental compile to catch errors fast — before paying for a full Gradle/Maven test cycle:

```kotlin
import com.intellij.task.ProjectTaskManager
import org.jetbrains.concurrency.await

val result = ProjectTaskManager.getInstance(project).buildAllModules().await()
println("Compile errors: ${result.hasErrors()}, aborted: ${result.isAborted()}")
// If hasErrors() == true: read and fix compile errors BEFORE running tests.
// This saves a full Gradle/Maven compile round-trip (~60-90s saved per error).
```

**Use this BEFORE running `./gradlew test` after bulk file creation** — if any file has a package mismatch, typo, or missing import, this surfaces it in ~10s instead of waiting ~90s for the full test compile.

### Fast Compile Check via runInspectionsDirectly (~5s vs ~90s for mvnw test-compile)

Run this BEFORE `./mvnw` to catch errors early:
```kotlin
val vf = findProjectFile("src/main/java/com/example/NewClass.java")!!
val result = runInspectionsDirectly(vf)
val findings = readAction {
    result.entries.flatMap { (id, descs) ->
        descs.map { descriptor ->
            mapOf("toolId" to id, "message" to descriptor.descriptionTemplate)
        }
    }
}
val status = when {
    result.failedTools.isNotEmpty() -> "check_failed"
    findings.isNotEmpty() -> "findings"
    else -> "clean"
}
printJson(mapOf("status" to status, "findings" to findings, "failedTools" to result.failedTools))
```

**Scope limitation**: `runInspectionsDirectly` is **file-scoped** — it only checks the single file you pass. After modifying a widely-used class (DTO, command, entity), also check dependent files or run `./mvnw test-compile` for project-wide verification.

**Never treat an empty findings map alone as clean.** Always check `failedTools`; a sweep-level setup failure or a crashing inspection may leave findings empty while the check did not complete cleanly.

**Inspect MODIFIED files too** — not just newly created ones. After adding methods to an existing file (e.g., `findByFeature_Code` to a Spring Data repository), run `runInspectionsDirectly` on that file immediately. Spring Data JPA derived query names throw `QueryCreationException` at Spring context startup — the Spring Data plugin inspection catches these in ~5s, before `./mvnw test` (~90s).

**`runInspectionsDirectly` also catches Spring issues**: Duplicate `@Bean` definitions, missing `@Component` annotations, unresolved `@Autowired` dependencies. Run it on your `@Configuration` classes BEFORE `./mvnw test` to catch Spring bean override exceptions early.

---

## Module-Targeted Tests

**Rule: Always target the specific module(s) you modified. Never run the full test suite
until you have a targeted-test PASS.**

**Maven multi-module:**
```
# CORRECT — run only the modified module's tests (~15-20s)
./mvnw -pl visits-service test -Dtest=VisitControllerTest
./mvnw -pl visits-service test  # all tests in module (~20s)

# WRONG — runs all modules (~90s), most irrelevant to your change
./mvnw test
```

**Gradle multi-project:**
```
# CORRECT — run only the subproject you changed (~15s)
./gradlew :visits-service:test --tests '*VisitControllerTest*'
./gradlew :visits-service:test  # all tests in subproject (~20s)

# WRONG — runs all subprojects (~90s+)
./gradlew test
```

**Single-module Maven (petclinic-rest, etc.):**
```
# Target a specific test class to verify your change (~10s)
./mvnw test -Dtest=PetRestControllerTests

# Only run full suite if targeted tests pass and you want final confidence
./mvnw test
```

**When to use each:**
- After adding a new method or annotation: run the test class for that layer only
- After modifying entity/DTO: run entity tests + the controller test that uses it
- After all targeted tests pass: optionally run `./mvnw test` once for final verification
- Never run full suite on the first attempt — always target first

**Preferred: run targeted tests via the Bash tool** (outside steroid_execute_code):
```
# Maven
./mvnw test -Dtest=VisitControllerTest -Dspotless.check.skip=true
# Gradle
./gradlew :visits-service:test --tests '*VisitControllerTest*' --rerun-tasks
```
Use the IntelliJ Maven/Gradle runner (below) only when you need test results inside a steroid_execute_code workflow.

---

## BANNED: Do NOT Use ProcessBuilder for Routine Maven/Gradle Builds or Tests

**This section applies to running Maven/Gradle INSIDE `steroid_execute_code`.** Running Maven/Gradle via the **Bash tool** (outside steroid_execute_code) is always correct and is the preferred approach for simple test runs — use it first.

**ProcessBuilder("./mvnw", ...)** spawns a child process inside IntelliJ's JVM — this bypasses IDE process management, causes classpath conflicts, and produces 200k+ char output that overflows MCP token limits.

**Alternatives (in priority order):**
0. **Bash tool** (PREFERRED, OUTSIDE steroid_execute_code) — run `./mvnw test -Dtest=MyTest` or `./gradlew :module:test --tests '*MyTest*'` directly from the Bash tool. Zero IntelliJ overhead. This is the first choice for any test execution that does not require IDE-integrated results.
1. **Maven IDE runner** (inside steroid_execute_code) — `MavenRunConfigurationType.runConfiguration()` — use when you need pass/fail result inside a steroid_execute_code workflow
2. **Gradle IDE runner** (inside steroid_execute_code) — `ExternalSystemUtil.runTask()` with `GradleConstants.SYSTEM_ID`
3. **ProcessBuilder("./mvnw")** — ONLY when pom.xml was just modified AND the IDE runner's SMTRunnerEventsListener latch has already timed out

**`GeneralCommandLine("docker", ...)` and `ProcessBuilder("docker", ...)` inside steroid_execute_code are BANNED** — same reason as `./mvnw`: they spawn a child process inside IntelliJ's JVM.
- Docker socket availability: `java.io.File("/var/run/docker.sock").exists()` (no process spawn needed)
- Docker CLI operations (inspect, exec, etc.): use the **Bash tool** outside steroid_execute_code

---

## Maven IDE Runner — Structured Pass/Fail, No Token Overflow

Use this for Maven test execution inside steroid_execute_code. Always pass `modal=smart_non_modal` (the default) on the `steroid_execute_code` call to auto-dismiss modals:

```kotlin[IU]
import org.jetbrains.idea.maven.execution.MavenRunConfigurationType
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes
val mavenResult = CompletableDeferred<Boolean>()
project.messageBus.connect().subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsListener {
    override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) { mavenResult.complete(testsRoot.isPassed) }
    override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {}
    override fun onTestsCountInSuite(count: Int) {}
    override fun onTestStarted(test: SMTestProxy) {}
    override fun onTestFinished(test: SMTestProxy) {}
    override fun onTestFailed(test: SMTestProxy) {}
    override fun onTestIgnored(test: SMTestProxy) {}
    override fun onSuiteFinished(suite: SMTestProxy) {}
    override fun onSuiteStarted(suite: SMTestProxy) {}
    override fun onCustomProgressTestsCategory(categoryName: String?, count: Int) {}
    override fun onCustomProgressTestStarted() {}
    override fun onCustomProgressTestFailed() {}
    override fun onCustomProgressTestFinished() {}
    override fun onSuiteTreeNodeAdded(testProxy: SMTestProxy) {}
    override fun onSuiteTreeStarted(suite: SMTestProxy) {}
})
MavenRunConfigurationType.runConfiguration(project,
    MavenRunnerParameters(true, project.basePath!!, "pom.xml",
        listOf("test", "-Dtest=FeatureReactionServiceTest", "-Dspotless.check.skip=true"), emptyList()),
    null, null) {}
val mvnPassed = withTimeout(5.minutes) { mavenResult.await() }
println("Maven IDE runner: passed=$mvnPassed")
// If modal dialog blocks: steroid_take_screenshot -> steroid_input dismiss -> retry. Do NOT fall back to ProcessBuilder.
```

---

## Last-Resort Fallback: ProcessBuilder("./mvnw") — Two Conditions Must BOTH Be True

**Only use ProcessBuilder("./mvnw") when ALL of the following are true:**
1. You just modified `pom.xml` in this session (IDE re-import dialog may block the runner latch), AND
2. The Maven IDE runner (`MavenRunConfigurationType.runConfiguration()`) has already timed out or failed

**If pom.xml was NOT modified: use the Maven IDE runner above — do NOT use ProcessBuilder.**

After pom.xml changes, IntelliJ triggers a Maven re-import dialog that blocks the IDE runner latch for up to 600s. First try: `MavenRunConfigurationType.runConfiguration()` with `modal=smart_non_modal` (the default). If the latch times out after 2 minutes, only then use ProcessBuilder:
```kotlin
// Always use ./mvnw (Maven wrapper) not 'mvn' — system mvn is not installed
// CRITICAL: Spring Boot test output routinely exceeds 200k chars. NEVER print untruncated output.
// Do NOT use -q — Maven quiet mode suppresses "Tests run:" summary. Exit code 0 alone is NOT sufficient.
val process = ProcessBuilder("./mvnw", "test", "-Dtest=MyValidatorTest", "-Dspotless.check.skip=true")
    .directory(java.io.File(project.basePath!!))
    .redirectErrorStream(true).start()
val lines = process.inputStream.bufferedReader().readLines()
val exitCode = process.waitFor()
println("Exit: $exitCode | total output lines: ${lines.size}")
// Capture BOTH ends: Spring context / Testcontainers failures appear at the START;
// Maven BUILD FAILURE summary appears at the END. takeLast alone misses early errors.
println("--- First 30 lines (Spring context / Testcontainers errors appear here) ---")
println(lines.take(30).joinToString("\n"))
println("--- Last 30 lines (Maven BUILD FAILURE summary appears here) ---")
println(lines.takeLast(30).joinToString("\n"))
```

**Run FAIL_TO_PASS tests one at a time** — NOT `-Dtest=Test1,Test2,Test3,Test4` all at once. 4 Spring Boot tests x 25k chars each = 100k+ chars, MCP token overflow.

---

## Docker Pre-Check — Run Proactively When Tests Use @Testcontainers
```kotlin
// Check Docker socket directly — no process spawn needed
val dockerOk = java.io.File("/var/run/docker.sock").exists()
println("Docker available: $dockerOk")
// If ALL FAIL_TO_PASS tests contain @Import(TestcontainersConfiguration.class)
//   OR extend AbstractIT/AbstractITBase/IntegrationTest, AND dockerOk=false →
//   SKIP the test run — go directly to ./mvnw test-compile verification.
//   These tests have NO H2 fallback: a DockerException is guaranteed.
// If dockerOk=false AND the test does NOT use Testcontainers: attempt it anyway.
//   Many "integration" tests use H2 in-memory DB and do NOT require Docker at all.
```

---

## Gradle Test Runner

PREFERRED over `ProcessBuilder("./gradlew")` inside steroid_execute_code — the latter spawns a nested Gradle daemon from within the IDE JVM, causing classpath conflicts:

```kotlin[IU]
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import org.jetbrains.plugins.gradle.util.GradleConstants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.minutes
val result = CompletableDeferred<Boolean>()
val s = ExternalSystemTaskExecutionSettings()
s.externalProjectPath = project.basePath!!
// After writing new source files: add "--rerun-tasks" to force test execution even if UP-TO-DATE
s.taskNames = listOf(":api:test", "--tests", "shop.api.composite.product.ProductCompositeServiceApplicationTests", "--rerun-tasks")
s.externalSystemIdString = GradleConstants.SYSTEM_ID.toString()
ExternalSystemUtil.runTask(s, com.intellij.execution.executors.DefaultRunExecutor.EXECUTOR_ID,
    project, GradleConstants.SYSTEM_ID,
    object : TaskCallback { override fun onSuccess() { result.complete(true) }; override fun onFailure() { result.complete(false) } },
    ProgressExecutionMode.IN_BACKGROUND_ASYNC, false)
val ok = withTimeout(5.minutes) { result.await() }; println("Gradle result: success=$ok")
// When success=false: read JUnit XML test results directly for failure details:
val testResultsDir = findProjectFile("build/test-results/test")
testResultsDir?.children?.filter { it.name.endsWith(".xml") }?.forEach { xmlFile ->
    val content = String(xmlFile.contentsToByteArray(), xmlFile.charset)
    val failures = Regex("<failure[^>]*>(.+?)</failure>", RegexOption.DOT_MATCHES_ALL)
        .findAll(content).map { it.groupValues[1].take(300) }.toList()
    if (failures.isNotEmpty()) println("FAIL ${xmlFile.name}: " + failures.first())
    else println("PASS ${xmlFile.name}")
}
```

---

## Verify @ControllerAdvice / @ExceptionHandler Exists

CRITICAL before writing controllers that throw custom exceptions — if no global handler exists, the API returns 500 instead of 404, breaking tests:
```kotlin[IU]
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
val scope = GlobalSearchScope.projectScope(project)
// Annotation lookup + search are index-backed — one smartReadAction owns the whole query
val adviceClasses = smartReadAction {
    val adviceAnnotation = JavaPsiFacade.getInstance(project).findClass("org.springframework.web.bind.annotation.ControllerAdvice", allScope())
        ?: JavaPsiFacade.getInstance(project).findClass("org.springframework.web.bind.annotation.RestControllerAdvice", allScope())
    if (adviceAnnotation != null) {
        AnnotatedElementsSearch.searchPsiClasses(adviceAnnotation, scope).findAll().toList()
    } else emptyList()
}
println("@ControllerAdvice classes: " + adviceClasses.map { it.qualifiedName })
// Find which exceptions each @ExceptionHandler covers:
adviceClasses.forEach { cls ->
    readAction {
        cls.methods.forEach { m ->
            val handler = m.annotations.firstOrNull { it.qualifiedName?.endsWith("ExceptionHandler") == true }
            if (handler != null) {
                val exTypes = handler.findAttributeValue("value")?.text ?: "(all)"
                println("  ${cls.name}.${m.name} handles: $exTypes → HTTP ${
                    m.annotations.firstOrNull { it.qualifiedName?.endsWith("ResponseStatus") == true }
                        ?.findAttributeValue("code")?.text ?: "?"
                }")
            }
        }
    }
}
// If adviceClasses is empty: the project has NO global exception handler.
// Controllers that throw custom exceptions will return 500. Add a @RestControllerAdvice class.
```

---

## Inspection Signal Semantics — Do NOT Misclassify

- **`[ConstantValue] Value ... is always 'null'`** on a DTO accessor in a test file → **CRITICAL BUG**: the DTO record is missing that component field. Do NOT dismiss as "pre-existing static analysis noise".
  - **`#ref` and `#loc` in ConstantValue output**: unresolved IntelliJ template placeholders. Look for DTO/record accessor calls in the test file that do NOT match any declared record component. Add the missing component.
- **`[ClassCanBeRecord]`** on a new DTO class → **REQUIRED**: convert to Java record (reference solution uses records).
- **`[ClassEscapesItsScope]`** on a `public` inner class inside Spring `@Service`/`@Repository` → **Expected**: safe to ignore.
- **`[GrazieInspectionRunner]`**, **`[DeprecatedIsStillUsed]`** → **Cosmetic**: low priority.

---

## Verification Gate — Run FAIL_TO_PASS Tests Before Marking Work Complete

```
./mvnw test -Dtest=ClassName -Dspotless.check.skip=true
# OR
./gradlew :module:test --tests "com.example.ClassName" --rerun-tasks --no-daemon
```

**Compile success alone is NOT sufficient.**

**FULL SUITE before `ARENA_FIX_APPLIED: yes`**: After FAIL_TO_PASS tests pass, ALSO run the complete test suite to catch regressions in other test classes.

**Deprecation warnings are not errors**: Compiler output like `warning: 'getVirtualFilesByName(...)' is deprecated` is non-fatal — the script succeeded. Only retry on explicit `ERROR` responses with no `execution_id`.

---

## Maven Patterns Reference

For Maven-specific patterns (MavenRunner, MavenRunnerParameters, Maven sync after pom.xml changes), see `mcp-steroid://skill/execute-code-maven`.

---

## Related Resources

### Skill Guides
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API reference and patterns
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Debug workflows and session management
- [Test Runner Skill Guide](mcp-steroid://prompt/test-skill) - Essential test execution knowledge

### Test Execution Examples
- [Test Overview](mcp-steroid://test/overview) - This document
- [Run Test at Caret](mcp-steroid://test/run-test-at-caret) - IDE-agnostic caret context action
- [List Run Configurations](mcp-steroid://test/list-run-configurations) - Discover available tests
- [Run Tests](mcp-steroid://test/run-tests) - Execute named test configurations
- [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Access results
- [Test Tree Navigation](mcp-steroid://test/tree-navigation) - Navigate test hierarchy
- [Wait for Completion](mcp-steroid://test/wait-for-completion) - Poll test status
- [Demo Debug Test](mcp-steroid://test/demo-debug-test) - End-to-end debug flow for demo test

### Related Example Guides
- [Debugger Examples](mcp-steroid://debugger/overview) - Debugging workflows
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations
- [LSP Examples](mcp-steroid://lsp/overview) - Code navigation
- [Open Project Examples](mcp-steroid://open-project/overview) - Project opening
