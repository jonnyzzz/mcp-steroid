Test: Run Test at Caret (Context Action)

Run or debug a test by opening its file, positioning the caret on a test method or class, and firing the context action — the action title shows the test name dynamically.

When the caret is on a test method, the IDE shows context actions **"Run 'testMethodName'"** and **"Debug 'testMethodName'"** — in the gutter icon, the right-click menu, and via keyboard shortcuts. This is the IDE-agnostic way to run a specific test without creating a named run configuration.

###_IF_IDE[RD]_###

```kotlin[RD]
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.LocalFileSystem

// 1. Open the test file in the editor
val testFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(
    project.basePath + "/MyProject.Tests/MyTests.cs"
) ?: error("Test file not found")
val editors = withContext(Dispatchers.EDT) {
    FileEditorManager.getInstance(project).openFile(testFile, true)
}
val editor = editors.filterIsInstance<TextEditor>().firstOrNull()?.editor
    ?: error("No text editor")

// 2. Position caret on the test method or class declaration
val text = editor.document.text
val offset = text.indexOf("public void MyTest").takeIf { it >= 0 }
    ?: text.indexOf("class MyTestClass")
withContext(Dispatchers.EDT) { editor.caretModel.moveToOffset(offset) }

// 3. Fire the context action — action title shown in UI: "Run 'MyTest'" or "Debug 'MyTest'"
// Use RiderUnitTestRunContextAction for run, RiderUnitTestDebugContextAction for debug
val actionId = "RiderUnitTestRunContextAction"
val action = ActionManager.getInstance().getAction(actionId) ?: error("Action not found: $actionId")
withContext(Dispatchers.EDT) {
    val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
    val event = AnActionEvent.createEvent(
        dataContext, action.templatePresentation.clone(), "EditorPopup", ActionUiKind.NONE, null
    )
    ActionUtil.performAction(action, event)
}
println("Tests started — results appear in Rider's Unit Test tool window")
```

**Key Rider test context actions:**
- `RiderUnitTestRunContextAction` — appears as **"Run 'TestName'"** in gutter/context menu
- `RiderUnitTestDebugContextAction` — appears as **"Debug 'TestName'"** (breakpoints will be hit)

Results appear in Rider's Unit Test tool window, not in `RunContentManager`/`SMTRunnerConsoleView`.

###_ELSE_###

`RunClass` and `DebugClass` are platform-level context-run actions (registered per executor by
the platform's executor registry), so the same recipe works in IDEA, PyCharm, GoLand, WebStorm,
CLion, and RubyMine — adjust the file path and the caret search strings to your language's test
syntax (e.g. `"def test_"` for Python, `"func Test"` for Go).

```kotlin
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.LocalFileSystem

// 1. Open the test file in the editor
val testFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(
    project.basePath + "/src/test/kotlin/com/example/MyTest.kt"
) ?: error("Test file not found")
val editors = withContext(Dispatchers.EDT) {
    FileEditorManager.getInstance(project).openFile(testFile, true)
}
val editor = editors.filterIsInstance<TextEditor>().firstOrNull()?.editor
    ?: error("No text editor")

// 2. Position caret on the test method (preferred for specificity) or class
// TODO: adapt the search strings to your language ("def test_..." in Python, "func Test..." in Go).
val text = editor.document.text
val offset = text.indexOf("fun myTestMethod").takeIf { it >= 0 }
    ?: text.indexOf("class MyTest")
withContext(Dispatchers.EDT) { editor.caretModel.moveToOffset(offset) }

// 3. Fire the context action — action title shown in UI: "Run 'myTestMethod'" or "Debug 'myTestMethod'"
// RunClass = run context action (shortcut: Ctrl+Shift+F10)
// DebugClass = debug context action (shortcut shown in gutter icon tooltip)
val actionId = "RunClass"  // switch to "DebugClass" for debugging
val action = ActionManager.getInstance().getAction(actionId) ?: error("Action not found: $actionId")
withContext(Dispatchers.EDT) {
    val dataContext = DataManager.getInstance().getDataContext(editor.contentComponent)
    val event = AnActionEvent.createEvent(
        dataContext, action.templatePresentation.clone(), "EditorPopup", ActionUiKind.NONE, null
    )
    ActionUtil.performAction(action, event)
}
println("Test started — check RunContentManager for progress and results")
```

**Key test context actions:**
- `RunClass` — appears as **"Run 'TestName'"** in editor gutter and right-click context menu
  - Default keyboard shortcut: **Ctrl+Shift+F10** (shown in gutter icon tooltip)
- `DebugClass` — appears as **"Debug 'TestName'"** — breakpoints will be hit

> **Tip:** Position the caret on the specific test method name rather than the class to avoid an
> ambiguous "Choose run configuration" dialog.

###_IF_IDE[AI,IC,IU]_###
> **IDEA fallback:** If the "Choose run configuration" dialog appears, use `JUnitConfiguration`
> directly — see [Run Tests](mcp-steroid://test/run-tests).

> **Pitfall: `.gradle.kts` files.** Gradle Kotlin scripts paint a Run gutter on PSI patterns
> (`task("…")`, `val x by tasks.registering { … }`), but the matching `RunConfigurationProducer`
> only resolves a config when the file's module is a synced Gradle module. Outside that, every
> `RunContextAction` hides itself and the gutter popup renders as **"Nothing here"** (the
> `Utils.EMPTY_MENU_FILLER` placeholder). Launch Gradle tasks programmatically via
> [Execute Code: Gradle Patterns](mcp-steroid://skill/execute-code-gradle) instead.
###_END_IF_###

Results are accessible via `RunContentManager.getInstance(project)` after execution.

###_END_IF_###

# See also

- [Run Tests](mcp-steroid://test/run-tests) - Execute by named run configuration (alternative)
- [Inspect Test Results](mcp-steroid://test/inspect-test-results) - Access results after run
- [Test Overview](mcp-steroid://test/overview) - Complete test execution workflow
- [Test Runner Skill Guide](mcp-steroid://prompt/test-skill) - Essential test knowledge
- [Execute Code: Gradle Patterns](mcp-steroid://skill/execute-code-gradle) - Launch Gradle tasks programmatically (avoids gutter "Nothing here" pitfall)
