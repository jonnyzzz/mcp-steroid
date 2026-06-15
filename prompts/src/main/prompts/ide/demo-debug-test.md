IDE: Demo Debug Test (End-to-end)

End-to-end demo: launch a test in Debug mode, resume if paused, wait for completion, and print results — via the caret context action in any IDE, plus a deterministic JUnit path in IDEA.

###_IF_IDE[RD]_###
In Rider, debug tests with the native unit-test runner: open the test file, position the
caret on the test class or method, and fire `RiderUnitTestDebugContextAction`.
`JUnitConfiguration` does NOT exist in Rider, and results appear in Rider's Unit Test
tool window — NOT in `RunContentManager`/`SMTRunnerConsoleView`.

```kotlin
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.ide.DataManager

// 1. Open the test file and position the caret on the test class
val basePath = project.basePath ?: error("No basePath")
val testFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath + "/MyProject.Tests/MyTests.cs")
    ?: error("Test file not found")
val editors = withContext(Dispatchers.EDT) {
    FileEditorManager.getInstance(project).openFile(testFile, true)
}
val editor = (editors.filterIsInstance<TextEditor>().firstOrNull() ?: error("No text editor")).editor
val classOffset = editor.document.text.indexOf("class MyTests")
withContext(Dispatchers.EDT) { editor.caretModel.moveToOffset(classOffset) }

// 2. Debug tests via the native Rider action
val action = ActionManager.getInstance().getAction("RiderUnitTestDebugContextAction")
    ?: error("RiderUnitTestDebugContextAction not found")
withContext(Dispatchers.EDT) {
    val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
    val event = AnActionEvent.createEvent(dataContext, action.templatePresentation.clone(), "EditorPopup", ActionUiKind.NONE, null)
    ActionUtil.performAction(action, event)
}
println("Debug test execution started — results appear in Rider's Unit Test tool window")
```

See `mcp-steroid://debugger/demo-debug-test` for the full Rider debug walkthrough
(breakpoints, wait-for-suspend, evaluation).
###_ELSE_###
## Step 1 — Launch: debug the test at caret (works in every IDE)

`DebugClass` is a platform-level context action, so the same launch works in IDEA, PyCharm,
GoLand, WebStorm, CLion, and RubyMine. Position the caret ON the test name identifier —
a caret on the keyword before it (`fun` / `class` / `def`) triggers a silent "nothing here"
popup and no debug session starts (see `mcp-steroid://debugger/demo-debug-test` for the
failure-mode details).

```kotlin
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.ide.DataManager

// 1. Open the test file and position the caret on the test method or class NAME
val basePath = project.basePath ?: error("No basePath")
val testFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath + "/src/test/kotlin/com/example/MyTest.kt")
    ?: error("Test file not found")
val editors = withContext(Dispatchers.EDT) {
    FileEditorManager.getInstance(project).openFile(testFile, true)
}
val editor = (editors.filterIsInstance<TextEditor>().firstOrNull() ?: error("No text editor")).editor

// TODO: adapt the search strings to your language ("def test_..." in Python, "func Test..." in Go).
val text = editor.document.text
val caretOffset = run {
    val funIdx = text.indexOf("fun myTestMethod")
    if (funIdx >= 0) funIdx + "fun ".length
    else text.indexOf("class MyTest") + "class ".length
}
withContext(Dispatchers.EDT) { editor.caretModel.moveToOffset(caretOffset) }

// 2. Fire the debug context action
val action = ActionManager.getInstance().getAction("DebugClass")
    ?: error("DebugClass action not found")
withContext(Dispatchers.EDT) {
    val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
    val event = AnActionEvent.createEvent(dataContext, action.templatePresentation.clone(), "EditorPopup", ActionUiKind.NONE, null)
    ActionUtil.performAction(action, event)
}
println("Debug context action fired — proceed to the poll/inspect step")
```

###_IF_IDE[AI,IC,IU]_###
## Step 1 (IDEA alternative) — deterministic `JUnitConfiguration` launch

In IDEA, a programmatic `JUnitConfiguration` avoids caret ambiguity entirely — the target
is the fully-qualified class you set:

```kotlin[AI,IC,IU]
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.openapi.module.ModuleManager

val configurationName = "DemoTestByJonnyzzz (Debug)"  // TODO: Set your run configuration name
val testClass = "com.jonnyzzz.mcpSteroid.ocr.DemoTestByJonnyzzz"  // TODO: Set your test class FQN

val runManager = RunManager.getInstance(project)
val settings = runManager.allSettings.firstOrNull { it.name == configurationName }
    ?: runManager.createConfiguration(
        configurationName,
        JUnitConfigurationType.getInstance().configurationFactories.first()
    ).also { runManager.addConfiguration(it) }

val junitConfig = settings.configuration as? JUnitConfiguration
    ?: error("Run configuration is not JUnit: ${settings.configuration.javaClass.name}")

val data = junitConfig.persistentData
data.TEST_OBJECT = JUnitConfiguration.TEST_CLASS
data.MAIN_CLASS_NAME = testClass
junitConfig.setVMParameters("-ea -Dmcp.demo.by.jonnyzzz=true")

val modules = ModuleManager.getInstance(project).modules.toList()
val module = modules.firstOrNull { it.name.endsWith(".test") }
    ?: modules.firstOrNull { it.name.contains("test", ignoreCase = true) }
    ?: modules.firstOrNull()
if (module != null) {
    junitConfig.setModule(module)
}

runManager.selectedConfiguration = settings

val executor = ExecutorRegistry.getInstance().getExecutorById(DefaultDebugExecutor.EXECUTOR_ID)
    ?: error("Debug executor not found")

withContext(Dispatchers.EDT) {
    ProgramRunnerUtil.executeConfiguration(settings, executor)
}

println("Started debug run: ${settings.name}")
```
###_END_IF_###

## Step 2 — Poll for completion and inspect results

`steroid_execute_code` is stateful across calls — run this as a separate call after the
launch. It locates the run content by name, resumes a paused debugger, waits for the
process to finish, and prints the test tree from the platform SM test runner:

```kotlin
import com.intellij.execution.testframework.AbstractTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.RunContentManager
import com.intellij.xdebugger.XDebuggerManager

// Substring of the run-content tab name: the configuration name for a programmatic
// launch, or the test class/method name for a context-action launch.
val nameHint = "MyTest"  // TODO: set the name hint

val contentManager = RunContentManager.getInstance(project)
fun findDescriptor() = contentManager.allDescriptors.lastOrNull { it.displayName?.contains(nameHint) == true }

var descriptor = findDescriptor()
repeat(40) {
    if (descriptor != null) return@repeat
    delay(250)
    descriptor = findDescriptor()
}
if (descriptor == null) {
    println("RunContentDescriptor matching '$nameHint' not found. Did the launch start? Re-run after it does.")
    return
}

// Resume the debugger if it stopped on a breakpoint we don't care about here
val debugger = XDebuggerManager.getInstance(project)
withContext(Dispatchers.EDT) {
    debugger.debugSessions
        .filter { it.isPaused && !it.isStopped }
        .forEach { session ->
            println("Resuming session: ${session.sessionName}")
            session.resume()
        }
}

val handler = descriptor.processHandler
if (handler == null) {
    println("No process handler available yet — re-run this script")
    return
}

repeat(60) {
    if (handler.isProcessTerminated) return@repeat
    delay(250)
}
println("Process terminated: ${handler.isProcessTerminated} exitCode=${handler.exitCode}")
if (!handler.isProcessTerminated) {
    println("Still running. Re-run this script to inspect final results.")
    return
}

val console = descriptor.executionConsole as? SMTRunnerConsoleView
if (console == null) {
    println("Not a test execution or results not available")
    return
}

val results = console.resultsViewer
println("Tests status: ${results.getTestsStatus()}")
println(
    "Counts: total=${results.getTotalTestCount()} started=${results.getStartedTestCount()} " +
        "finished=${results.getFinishedTestCount()} failed=${results.getFailedTestCount()} " +
        "ignored=${results.getIgnoredTestCount()}"
)

val root = results.testsRootNode
fun status(proxy: AbstractTestProxy): String = when {
    proxy.isPassed -> "PASSED"
    proxy.isIgnored -> "IGNORED"
    proxy.isDefect -> "FAILED"
    proxy.isInProgress -> "RUNNING"
    else -> "UNKNOWN"
}

println("Root: ${root.name} [${status(root)}]")
root.children.forEach { child ->
    println("- ${child.name} [${status(child)}]")
}
```
###_END_IF_###

# See also

- [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start debugging
- [Debug Session Control](mcp-steroid://debugger/debug-session-control) - Pause/resume/stop
- [Run Test at Caret](mcp-steroid://test/run-test-at-caret) - Run/debug a test via context action
- [Run Tests](mcp-steroid://test/run-tests) - Execute test configuration
- [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Access test results
- [Test Examples Overview](mcp-steroid://test/overview) - Test workflows
