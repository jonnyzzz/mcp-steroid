IntelliJ Test Runner Skill Guide

Use IntelliJ test execution APIs via steroid_execute_code to run tests, inspect test results, check test status, and access test output.


# IntelliJ Test Runner Skill

Use IntelliJ test execution APIs from `steroid_execute_code` to run tests and inspect results.

## Quickstart

**Preferred — run a specific test at caret position (IDE-agnostic):**
1) Open the test file in the editor and position the caret on the test method or class.
2) Fire the context action — see `mcp-steroid://test/run-test-at-caret`.
   - When the caret is on a test, the action title shows the name: **"Run 'testMethodName'"** / **"Debug 'testMethodName'"**
   - IntelliJ: action IDs `RunClass` (Ctrl+Shift+F10) and `DebugClass`
   - Rider: action IDs `RiderUnitTestRunContextAction` and `RiderUnitTestDebugContextAction`
3) Poll for completion and access results (example: `mcp-steroid://test/inspect-test-results`).

**Alternative — run by named run configuration:**
1) Load `mcp-steroid://test/overview` and pick the examples you need.
2) List available run configurations (example: `mcp-steroid://test/list-run-configurations`).
3) Execute a test configuration (example: `mcp-steroid://test/run-tests`).
4) Navigate test tree and check individual test status (example: `mcp-steroid://test/tree-navigation`).

## Stateful steroid_execute_code workflow

`steroid_execute_code` is stateful. Split test execution into multiple short calls:

- Call #1: list run configurations
- Call #2: execute test configuration (ProgramRunnerUtil)
- Call #3: locate RunContentDescriptor via RunContentManager
- Call #3+: poll ProcessHandler.isProcessTerminated() until tests complete
- Call #4: access test results from SMTRunnerConsoleView
- Call #5: navigate test tree and inspect failures

Avoid long waits or sleeps inside a single call; prefer multiple short polls.

###_IF_IDE[RD]_###
## Run .NET Tests in Rider (Native Test Runner)

Rider uses its own test runner via context actions. Do NOT use JUnitConfiguration — it does not exist in Rider.
Do NOT use `dotnet test` CLI when the native Rider test runner is available — the native runner integrates
with the debugger and provides better results.

**Run tests from editor context (recommended):**
Open the test file, position the caret on the test class or method, and invoke the action:

```kotlin
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.ide.DataManager

// 1. Open the test file in editor
val basePath = project.basePath ?: error("No basePath")
val testFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath + "/Path/To/MyTests.cs")
    ?: error("Test file not found")
val editors = withContext(Dispatchers.EDT) {
    FileEditorManager.getInstance(project).openFile(testFile, true)
}
val textEditor = editors.filterIsInstance<TextEditor>().firstOrNull() ?: error("No text editor")
val editor = textEditor.editor

// 2. Position caret on test class or method
val text = editor.document.text
val offset = text.indexOf("class MyTestClass")  // or a specific test method name
withContext(Dispatchers.EDT) { editor.caretModel.moveToOffset(offset) }

// 3. Run tests via native Rider action
val action = ActionManager.getInstance().getAction("RiderUnitTestRunContextAction")
    ?: error("Action not found")
withContext(Dispatchers.EDT) {
    val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
    val presentation = action.templatePresentation.clone()
    val event = AnActionEvent.createEvent(dataContext, presentation, "EditorPopup", ActionUiKind.NONE, null)
    ActionUtil.performAction(action, event)
}
println("Tests started")
```

**Key Rider test actions:**
- `RiderUnitTestRunContextAction` — Run tests at caret position (test class or method)
- `RiderUnitTestDebugContextAction` — Debug tests at caret position
- `RiderUnitTestRunSolutionAction` — Run all tests in the solution

**Important**: Rider test results do NOT appear in `RunContentManager` or `SMTRunnerConsoleView`.
Results appear in Rider's Unit Test tool window. The agent should use the test action,
then check the debugger for breakpoint hits.
###_ELSE_###
## Run a Specific Test — Caret Context Action (preferred)

Open the test file, position the caret on the test method or class, and fire the context action.
The action title dynamically shows the test name: **"Run 'myTestMethod'"** / **"Debug 'myTestMethod'"**.

```kotlin[IU]
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.LocalFileSystem

val testFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(
    project.basePath + "/src/test/kotlin/com/example/MyTest.kt"
) ?: error("Test file not found")
val editors = withContext(Dispatchers.EDT) {
    FileEditorManager.getInstance(project).openFile(testFile, true)
}
val editor = editors.filterIsInstance<TextEditor>().firstOrNull()?.editor
    ?: error("No text editor")

// Prefer method offset over class offset — reduces ambiguous "Choose configuration" dialog
val text = editor.document.text
val offset = text.indexOf("fun myTestMethod").takeIf { it >= 0 } ?: text.indexOf("class MyTest")
withContext(Dispatchers.EDT) { editor.caretModel.moveToOffset(offset) }

// RunClass = "Run 'myTestMethod'" (Ctrl+Shift+F10); DebugClass = "Debug 'myTestMethod'"
val action = ActionManager.getInstance().getAction("RunClass") ?: error("RunClass not found")
withContext(Dispatchers.EDT) {
    val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
    val event = AnActionEvent.createEvent(
        dataContext, action.templatePresentation.clone(), "EditorPopup", ActionUiKind.NONE, null
    )
    ActionUtil.performAction(action, event)
}
println("Test started")
```

See `mcp-steroid://test/run-test-at-caret` for full pattern with run/debug variants.

## Run a Specific JUnit Test Class (fallback API)

Use when the context action shows a dialog or a specific programmatic configuration is needed:

```kotlin[IU]
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.execution.RunManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor

val factory = JUnitConfigurationType.getInstance().configurationFactories.first()
val config = factory.createTemplateConfiguration(project) as JUnitConfiguration
config.name = "Run test"
val data = config.persistentData               // typed as JUnitConfiguration.Data
data.MAIN_CLASS_NAME = "com.example.MyValidatorTest"
data.TEST_OBJECT = JUnitConfiguration.TEST_CLASS  // ← constant, NOT a string literal "class"
config.setWorkingDirectory(project.basePath!!)
val settings = RunManager.getInstance(project).createConfiguration(config, factory)
RunManager.getInstance(project).addConfiguration(settings)
ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
println("Test run started")
```

⚠️ **Common mistake**: `data.TEST_OBJECT = "class"` → compile error. Always use `JUnitConfiguration.TEST_CLASS`.
⚠️ **Module required**: set `junitConfig.setModule(module)` — without it the configuration is invalid.
###_END_IF_###

## Key APIs to use

**Run Configuration Management**
- `RunManager.getInstance(project)` - access all run configurations
- `RunManager.allSettings` - list of RunnerAndConfigurationSettings
- `RunManager.selectedConfiguration` - currently selected config

**Execution**
- `ProgramRunnerUtil.executeConfiguration(settings, executor)` - execute config
- `ExecutorRegistry.getExecutorById(DefaultRunExecutor.EXECUTOR_ID)` - get Run executor
- `ExecutorRegistry.getExecutorById(DefaultDebugExecutor.EXECUTOR_ID)` - get Debug executor

**Accessing Results**
- `RunContentManager.getInstance(project).allDescriptors` - all run content
- `RunContentDescriptor.getExecutionConsole()` - get console (may be SMTRunnerConsoleView)
- `SMTRunnerConsoleView.getResultsViewer()` - get SMTestRunnerResultsForm
- `SMTestRunnerResultsForm.getTestsRootNode()` - get test tree root (SMRootTestProxy)

**Test Result Inspection**
- `SMTestProxy.isPassed()`, `isDefect()`, `isIgnored()`, `isInProgress()`
- `SMTestProxy.getChildren()` - navigate test tree
- `SMTestProxy.getErrorMessage()`, `getStacktrace()` - failure details
- `SMTestProxy.getDuration()` - execution time
- `SMTestProxy.getAllTests()` - flatten test tree

**Process Monitoring**
- `RunContentDescriptor.getProcessHandler()` - get ProcessHandler
- `ProcessHandler.isProcessTerminated()` - check if execution finished
- `ProcessHandler.exitCode` - get exit code (if available)

## Common pitfalls

- Test results are only available after execution starts (descriptor.executionConsole may be null initially).
- ExecutionConsole must be cast to SMTRunnerConsoleView for test-specific features.
- Test tree is populated asynchronously as tests run.
- Always check `isProcessTerminated()` before accessing final results.
- Execute configurations on EDT (use `withContext(Dispatchers.EDT)`).
- Wait for smart mode before accessing run configurations.

## Related Resources

### Skill Guides
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Essential IntelliJ API patterns
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Debug session management
- [Test Runner Skill Guide](mcp-steroid://prompt/test-skill) - This guide

### Test Execution Resources
- [Test Overview](mcp-steroid://test/overview) - Complete test execution workflow and patterns
- [List Run Configurations](mcp-steroid://test/list-run-configurations) - Discover available test configs
- [Run Tests](mcp-steroid://test/run-tests) - Execute test configurations
- [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Access and analyze test results
- [Test Tree Navigation](mcp-steroid://test/tree-navigation) - Navigate test hierarchy
- [Wait for Completion](mcp-steroid://test/wait-for-completion) - Poll for test execution status

### Related Example Guides
- [Debugger Examples](mcp-steroid://debugger/overview) - Debugging and session management
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations
- [Open Project Examples](mcp-steroid://open-project/overview) - Project opening workflows
