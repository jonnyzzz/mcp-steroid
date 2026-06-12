Debugger: Demo Debug Test (End-to-end)

Launches a debug test session via context action: open test file, position caret on test class/method, fire debug action. Falls back to JUnitConfiguration in IntelliJ if needed.

###_IF_IDE[RD]_###

```kotlin
// In Rider, use RiderUnitTestDebugContextAction to debug tests.
// JUnitConfiguration does NOT exist in Rider.

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.ide.DataManager

// Step 1: Open test file and position caret on test class
val basePath = project.basePath ?: error("No basePath")
val testFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath + "/DemoRider.Tests/LeaderboardTests.cs")
    ?: error("Test file not found")
val editors = withContext(Dispatchers.EDT) {
    FileEditorManager.getInstance(project).openFile(testFile, true)
}
val textEditor = editors.filterIsInstance<TextEditor>().firstOrNull() ?: error("No text editor")
val editor = textEditor.editor
val text = editor.document.text
val classOffset = text.indexOf("class LeaderboardTests")
withContext(Dispatchers.EDT) { editor.caretModel.moveToOffset(classOffset) }

// Step 2: Debug tests via native Rider action
val action = ActionManager.getInstance().getAction("RiderUnitTestDebugContextAction")
    ?: error("RiderUnitTestDebugContextAction not found")
withContext(Dispatchers.EDT) {
    val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
    val presentation = action.templatePresentation.clone()
    val event = AnActionEvent.createEvent(dataContext, presentation, "EditorPopup", ActionUiKind.NONE, null)
    ActionUtil.performAction(action, event)
}
println("Debug test execution started")
```

For debugging .NET code in Rider:
1. Set breakpoints using `mcp-steroid://debugger/set-line-breakpoint` (XDebuggerUtil works in Rider)
2. Open the test file, position caret on test class, fire `RiderUnitTestDebugContextAction`
3. Wait for breakpoint hit using `mcp-steroid://debugger/wait-for-suspend`
4. Evaluate variables using `mcp-steroid://debugger/evaluate-expression`
5. Step through code using `mcp-steroid://debugger/step-over` (only if needed — skip if the bug is visible from evaluation at the breakpoint)
###_ELSE_###

## Primary path — `JUnitConfiguration` (deterministic)

For Java / Kotlin tests in IntelliJ, build a `JUnitConfiguration`
programmatically and launch it with `DefaultDebugExecutor`. This is the
**deterministic** path: a session is registered the moment
`ProgramRunnerUtil.executeConfiguration(...)` returns, and the
configuration target is the fully-qualified class (and optional method)
you specified — there is no caret-position ambiguity. Use this path by
default; fall back to the context action only when caret targeting is
trivially unambiguous AND you accept its "silent no-op on ambiguity"
behavior described below.

```kotlin[IU]
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.junit.JUnitConfiguration
import com.intellij.execution.junit.JUnitConfigurationType
import com.intellij.openapi.module.ModuleManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope

val testClassFqn = "com.example.MyTest"  // TODO: fully-qualified test class
val testMethodName: String? = null        // optional — set to e.g. "myTestMethod" to debug a single test

// Either pin the module by exact name (preferred — deterministic) OR leave
// null to fall back to a `.test`-suffix heuristic. The heuristic is fragile
// against Gradle source-set / `:module:test` naming and is only a
// convenience for single-test-module projects.
val explicitModuleName: String? = null    // TODO: e.g. "mcp-steroid.npx-kt.test" for a deterministic launch

val runManager = RunManager.getInstance(project)
val factory = JUnitConfigurationType.getInstance().configurationFactories.first()
val configName = "Debug-${testClassFqn.substringAfterLast('.')}${testMethodName?.let { ".$it" } ?: ""}"

// Reuse if a config with the same name already exists — idempotent across
// reruns. `settings.isTemporary = true` lets IntelliJ's run-config rotation
// drop the entry automatically, so a forgotten cleanup recipe isn't fatal.
val settings = runManager.allSettings.firstOrNull { it.name == configName }
    ?: runManager.createConfiguration(configName, factory).also {
        it.isTemporary = true
        runManager.addConfiguration(it)
    }
val junitConfig = settings.configuration as JUnitConfiguration

val data = junitConfig.persistentData
data.MAIN_CLASS_NAME = testClassFqn
if (testMethodName != null) {
    data.TEST_OBJECT = JUnitConfiguration.TEST_METHOD
    data.METHOD_NAME = testMethodName
} else {
    // Class-level rerun must explicitly clear METHOD_NAME — otherwise a
    // previous method-level launch's value lingers on the reused settings.
    data.TEST_OBJECT = JUnitConfiguration.TEST_CLASS
    data.METHOD_NAME = ""
}

// CRITICAL: the JUnit runner needs a module assignment. Without setModule()
// the configuration is invalid and `executeConfiguration` returns without
// starting anything — exactly the "silent no-op" failure mode the context
// action also has. Validate the chosen module actually contains the test
// class via JavaPsiFacade — a wrong module is the second silent failure.
val modules = ModuleManager.getInstance(project).modules.toList()
val module = run {
    if (explicitModuleName != null) {
        modules.firstOrNull { it.name == explicitModuleName }
            ?: error("Module not found: '$explicitModuleName'. Candidates: ${modules.map { it.name }}")
    } else {
        modules.firstOrNull { it.name.endsWith(".test") }
            ?: modules.firstOrNull { it.name.contains("test", ignoreCase = true) }
            ?: error("No test module found. Candidates: ${modules.map { it.name }}")
    }
}
val resolves = readAction {
    JavaPsiFacade.getInstance(project)
        .findClass(testClassFqn, GlobalSearchScope.moduleScope(module)) != null
}
check(resolves) {
    "Module '${module.name}' does not contain '$testClassFqn'. " +
        "Set `explicitModuleName` to the module the class actually lives in. " +
        "Modules: ${modules.map { it.name }}"
}
junitConfig.setModule(module)

// `checkConfiguration` raises a `RuntimeConfigurationException` when the
// launcher would silently no-op (missing module, invalid class, etc.).
// Catch and surface the message so the agent fixes the config rather than
// chasing a missing `currentSession` afterwards.
try {
    junitConfig.checkConfiguration()
} catch (e: com.intellij.execution.configurations.RuntimeConfigurationException) {
    // `RuntimeConfigurationException.message` is deprecated under IntelliJ
    // 2025.3+ in favor of the non-deprecated `localizedMessage` inherited
    // from Throwable. Same content, no -Werror noise.
    error("JUnitConfiguration is invalid: ${e.localizedMessage}. Fix the config before launching debug.")
}

val executor = DefaultDebugExecutor.getDebugExecutorInstance()
withContext(Dispatchers.EDT) {
    ProgramRunnerUtil.executeConfiguration(settings, executor)
}

println("Started debug session: ${settings.name} (module=${module.name})")
println("Next: wait for breakpoint hit via `mcp-steroid://debugger/wait-for-suspend`")
```

## Alternative — `DebugContextAction` (faster but caret-sensitive)

If your caret can be placed unambiguously on a test class or method
identifier, firing the platform's `DebugClass` / `DebugContextAction`
saves a few lines of setup. **It has one important failure mode**:
when the caret is on a `fun` / `class` keyword (not the identifier
itself), or in an ambiguous spot, the action silently shows a
"nothing here" popup instead of starting a session — the dialog killer
dismisses the popup and **no `XDebugSession` is registered**.
`mcp-steroid://debugger/wait-for-suspend` will then time out with
`currentSession=null`. Do not retry the same context action; switch to
the `JUnitConfiguration` recipe above.

```kotlin[IU]
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.ide.DataManager

// Step 1: Open test file and position caret ON the method or class name
val basePath = project.basePath ?: error("No basePath")
val testFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath + "/src/test/kotlin/com/example/MyTest.kt")
    ?: error("Test file not found")
val editors = withContext(Dispatchers.EDT) {
    FileEditorManager.getInstance(project).openFile(testFile, true)
}
val textEditor = editors.filterIsInstance<TextEditor>().firstOrNull() ?: error("No text editor")
val editor = textEditor.editor
val text = editor.document.text
// IMPORTANT: caret must be on the NAME identifier, not on the keyword before it.
// Caret on `fun` / `class` triggers a silent "nothing here" popup; the dialog
// killer dismisses it and no session starts.
val caretOffset = run {
    val funIdx = text.indexOf("fun myTestMethod")
    if (funIdx >= 0) funIdx + "fun ".length
    else text.indexOf("class MyTest") + "class ".length
}
withContext(Dispatchers.EDT) { editor.caretModel.moveToOffset(caretOffset) }

// Step 2: Debug via context action.
val action = ActionManager.getInstance().getAction("DebugClass")
    ?: error("DebugClass action not found")
withContext(Dispatchers.EDT) {
    val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
    val presentation = action.templatePresentation.clone()
    val event = AnActionEvent.createEvent(dataContext, presentation, "EditorPopup", ActionUiKind.NONE, null)
    ActionUtil.performAction(action, event)
}
println("Debug context action fired — verify XDebuggerManager.currentSession is non-null before waiting for suspend")
```

For debugging Java/Kotlin tests in IntelliJ:
1. Set breakpoints using `mcp-steroid://debugger/add-breakpoint` (idempotent, uses `writeIntentReadAction` for the Kotlin platform's threading contract).
2. Launch the debug session via the **`JUnitConfiguration` recipe above** — this is the deterministic path. Use the context-action recipe only when caret targeting is trivially unambiguous and a silent no-op is acceptable.
3. Wait for breakpoint hit using `mcp-steroid://debugger/wait-for-suspend` — it explicitly distinguishes "no session started" (recover via the JUnit recipe) from "session never paused" (recover via breakpoint diagnosis).
4. Evaluate variables using `mcp-steroid://debugger/evaluate-expression` — review its "Kotlin / K2 evaluation caveats" section before evaluating receiver/property access.
5. Step through code using `mcp-steroid://debugger/step-over` (only if needed — skip if the bug is visible from evaluation at the breakpoint).
6. After verification, remove temporary breakpoints (`mcp-steroid://debugger/remove-breakpoint`) and delete the run configuration via `RunManager.getInstance(project).removeConfiguration(settings)`.
###_END_IF_###

# See also

Related test operations:
- [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Access test results

Related IDE operations:
- [Run Configuration](mcp-steroid://ide/run-configuration) - List and execute run configs
