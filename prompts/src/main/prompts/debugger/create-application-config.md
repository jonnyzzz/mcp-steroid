Create Application Run Configuration

Create a new Application run configuration for a main class.

###_IF_IDE[RD]_###
In Rider, use native test runner actions instead of ApplicationConfiguration (which is JVM-specific).

**Run .NET tests from editor context:**
```kotlin
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.ide.DataManager

// Open test file, position caret, fire action
val basePath = project.basePath ?: error("No basePath")
val testFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(basePath + "/Path/To/Tests.cs")
    ?: error("Test file not found")
val editors = withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).openFile(testFile, true) }
val editor = (editors.filterIsInstance<TextEditor>().firstOrNull() ?: error("No editor")).editor
val offset = editor.document.text.indexOf("class MyTestFixture")
withContext(Dispatchers.EDT) { editor.caretModel.moveToOffset(offset) }

// RiderUnitTestRunContextAction = run, RiderUnitTestDebugContextAction = debug
val action = ActionManager.getInstance().getAction("RiderUnitTestRunContextAction") ?: error("Not found")
withContext(Dispatchers.EDT) {
    val ctx = DataManager.getInstance().getDataContext(editor.contentComponent)
    val event = AnActionEvent.createEvent(ctx, action.templatePresentation.clone(), "EditorPopup", ActionUiKind.NONE, null)
    ActionUtil.performAction(action, event)
}
println("Tests started")
```

To list existing run configurations:
```kotlin
import com.intellij.execution.RunManager
val runManager = RunManager.getInstance(project)
runManager.allSettings.forEach { println(it.name + " (" + it.type.displayName + ")") }
```
###_ELSE_###
Create a new Application run configuration for a Kotlin/Java main class.

```kotlin[IU]
import com.intellij.execution.RunManager
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.openapi.module.ModuleManager

val mainClassName = "com.example.MainKt"  // TODO: Set your main class FQN
val configName = "MyApp"  // TODO: Set configuration name

val runManager = RunManager.getInstance(project)

// Check if configuration already exists
val existing = runManager.findConfigurationByName(configName)
if (existing != null) {
    println("Run configuration already exists:", configName)
    return
}

val factory = ApplicationConfigurationType.getInstance().configurationFactories.first()
val settings = runManager.createConfiguration(configName, factory)
val config = settings.configuration as ApplicationConfiguration

config.mainClassName = mainClassName

// Set module (pick the first available or filter by name)
val modules = ModuleManager.getInstance(project).modules
val module = modules.firstOrNull { it.name.endsWith(".main") }
    ?: modules.firstOrNull()
if (module != null) {
    config.setModule(module)
}

settings.storeInDotIdeaFolder()
runManager.addConfiguration(settings)
runManager.selectedConfiguration = settings

println("Created run configuration:", configName, "main:", mainClassName)
```
###_END_IF_###

# See also

Related debugger operations:
- [Debug Run Configuration](mcp-steroid://debugger/debug-run-configuration) - Start existing config in debug mode
- [Set Line Breakpoint](mcp-steroid://debugger/set-line-breakpoint) - Set breakpoint before debugging

Overview resources:
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Essential debugger knowledge
