# AGENT-STEROID.md - IntelliJ API Usage Guide for LLM Agents

This document provides instructions for LLM agents (like Claude) on how to effectively use the MCP Steroid server to interact with the IntelliJ Platform API. The goal is to make you a **power user of IntelliJ APIs** - always prefer using IntelliJ's capabilities over manual file operations.

## Core Philosophy

**BE AGGRESSIVE WITH INTELLIJ API USAGE.**

Instead of:
- Reading files manually with file tools → Use IntelliJ's VFS and PSI
- Searching with grep → Use IntelliJ's Find Usages, Structural Search
- Manual refactoring → Use IntelliJ's automated refactorings
- Guessing code structure → Query the project model directly

The IDE has indexed everything. It knows the code better than any file search. **USE IT.**

## Available MCP Tools

> **Project / backend naming contract:** if you reach this guide via
> the **devrig stdio MCP server** (`devrig mcp`), the `project_name`
> values returned by `steroid_list_projects` follow the
> [`docs/devrig-naming.md`](../devrig-naming.md) spec — they include a
> hash suffix (`<slug(name)>-<hash8>`) and are routed through an
> on-demand snapshot per call. The in-IDE MCP Steroid endpoint exposes
> the raw IntelliJ `Project.name` instead; pick one transport and
> stick to its naming.

### `steroid_list_projects`
List all open projects in the IDE. Use this to get project names for `steroid_execute_code`.

There is **no top-level `ide`/`plugin`/`pid` header** in the response.
Instead the response carries `backends[]` (one entry per discovered
backend) and every `projects[]` entry self-attributes to its backend via
`backend_name`. See "Choosing a backend" below for the `backends[]`
shape.

### `steroid_list_windows`
List open IDE windows and their associated projects. Some windows may not be tied to a project and a project can have multiple windows.
Use this in multi-window setups to pick the right `project_name` and `window_id` for screenshot/input tools.

Like `steroid_list_projects`, the response has **no top-level
`ide`/`plugin`/`pid` header**: it carries `backends[]` (same shape as in
`steroid_list_projects`), and every `windows[]` and `backgroundTasks[]`
entry names its owning backend via `backend_name`.

**Response Fields (per window):**
| Field | Description |
|-------|-------------|
| `projectName` | Project name (null if not a project window) |
| `projectPath` | Project base path |
| `windowId` | Unique window identifier for screenshot/input targeting |
| `modalDialogShowing` | **True if any modal dialog is showing in IDE** |
| `indexingInProgress` | **True if project is indexing (dumb mode)** |
| `projectInitialized` | **True if project is fully initialized** |
| `isActive` | Whether window is currently focused |
| `isVisible` | Whether window is visible |
| `backend_name` | The backend (IDE) this window belongs to — matches a `backends[]` entry |

**Response also includes `backgroundTasks` (list of running tasks):**
| Field | Description |
|-------|-------------|
| `title` | Task title (e.g., "Indexing", "Building") |
| `text` | Current status text |
| `fraction` | Progress 0.0-1.0 (null if indeterminate) |
| `isIndeterminate` | True if no percentage available |
| `projectName` | Associated project name |
| `backend_name` | The backend (IDE) this task runs in — matches a `backends[]` entry |

Use the modality/indexing fields and `backgroundTasks` to poll for project readiness after `steroid_open_project`.

### `steroid_take_screenshot`
Capture a screenshot of the IDE frame and return image content.

**HEAVY ENDPOINT**: Use only for debugging and tricky configuration. Prefer `steroid_execute_code` for regular automation.

Parameters:
- `project_name` (required): Target project from `steroid_list_projects`
- `task_id` (required): Task identifier for logging
- `reason` (required): Why the screenshot is needed
- `window_id` (optional): Window id from `steroid_list_windows` to target a specific window

Artifacts saved under the execution folder:
- `screenshot.png`
- `screenshot-tree.md`
- `screenshot-meta.json`

The response includes `window_id` (also from `steroid_list_windows`); pass it to `steroid_input` to target the same window.

### `steroid_input`
Send input events (keyboard + mouse) using a sequence string.

**HEAVY ENDPOINT**: Use only for debugging and tricky configuration. Prefer `steroid_execute_code` for regular automation.

Parameters:
- `project_name` (required): Target project from `steroid_list_projects`
- `task_id` (required): Task identifier for logging
- `reason` (required): Why the input is needed
- `window_id` (required): Window id from `steroid_list_windows` (also returned by `steroid_take_screenshot`)
- `sequence` (required): Comma-separated or newline-separated input sequence (commas inside values are allowed unless they look like `, <step>:`; commas are optional when using newlines)

Sequence examples:
- `stick:ALT, delay:400, press:F4, type:hurra`
- `click:CTRL+Left@120,200`
- `click:Right@screen:400,300`

Notes:
- Comma separators are detected by `, <step>:` patterns, so avoid typing `, delay:` etc in text.
- Trailing commas before a newline are ignored.
- Use `#` for comments until the end of the line.
- Targets default to screenshot coordinates; use `screen:` for absolute screen pixels.
- Input focuses the screenshot window before dispatching events.

### `steroid_execute_code`
Execute Kotlin code directly in IntelliJ's runtime. This is your primary tool.

Parameters:
- `project_name` (required): Target project from `steroid_list_projects`
- `code` (required): Kotlin suspend function body
- `reason` (required): Human-readable explanation of what you're doing - **be detailed so sub-agents can understand the intent**
- `task_id` (required): Group related executions together
- `timeout` (optional): Timeout in seconds for your script body (default 600; smart_non_modal's pre-flight has its own bounds)
- `modal` (optional): IDE-preparation + modal-dialog policy. `smart_non_modal` (default) closes stray dialogs, requires non-modal, commits/saves docs + refreshes VFS, waits for indexing, then closes+fails on any modal during the run — the safe choice for PSI/editing. `non_modal` only asserts a non-modal start. `unleashed` does no checks (intentional modal-dialog workflows or trivial actions only, never PSI). Finer control via the `McpScriptContext` methods `closeModalDialogs()` / `monitorAndCloseModalDialogs()` / `allowModalDialog()` / `syncDocuments()` / `waitForSmartMode()`.

**Best Practice: Delegate to Sub-Agent**

When working with IntelliJ APIs, consider spawning a **sub-agent** to handle `steroid_execute_code` calls:

1. **Retry capability**: IntelliJ API is vast - sub-agent can try multiple approaches without polluting main context
2. **Context isolation**: Errors and debugging stay within sub-agent, keeping main agent focused
3. **Clear intent**: Provide detailed `reason` so sub-agent understands what you're trying to achieve
4. **Iteration friendly**: Sub-agent can fix errors and retry without main agent tracking each attempt

The sub-agent pattern prevents context rot from failed code attempts and allows specialized focus on making the IntelliJ code work.

### `steroid_execute_feedback`
Provide feedback on execution results. Use after `steroid_execute_code` to rate success.

### `steroid_open_project`
Open a project in the IDE. This tool initiates the project opening process and returns quickly.

**IMPORTANT**: Project opening is **ASYNCHRONOUS**. This tool returns immediately after initiating the open operation. You **MUST poll** `steroid_list_windows` to verify the project is fully ready.

Parameters:
- `project_path` (required): Absolute path to the project directory to open
- `task_id` (required): Task identifier for logging
- `reason` (required): Why you are opening the project
- `trust_project` (optional): If true, trust the project path before opening (skips trust dialog). Default: true

**Verification Workflow (Required):**
1. Call `steroid_open_project` with the project path
2. Poll `steroid_list_windows` every 2-3 seconds until:
   - The project appears in the windows list
   - `modalDialogShowing` is `false` (no dialogs blocking)
   - `indexingInProgress` is `false` (indexing complete)
   - `projectInitialized` is `true`
3. If `modalDialogShowing` is `true`:
   - Call `steroid_take_screenshot` to see the dialog
   - Use `steroid_input` to interact with the dialog
4. Use `steroid_take_screenshot` to visually confirm the project is loaded
5. Verify with `steroid_list_projects` that the project appears

#### Choosing a backend (`backend_name`)

When you reach the IDE through the **devrig stdio MCP server**
(`devrig mcp`), more than one IDE can be running behind a single MCP
connection. The devrig `steroid_open_project` schema exposes an extra
parameter, `backend_name`, that selects which IDE receives the open
request. This parameter is **devrig-only** — on a direct in-IDE
connection (one MCP server == one IDE) it is not advertised, and if sent
anyway it is logged and ignored.

`steroid_list_projects` and `steroid_list_windows` **self-describe on
both surfaces**: neither response has a top-level `ide`/`plugin`/`pid`
header — instead each carries `backends[]`, and every `projects[]`,
`windows[]`, and `backgroundTasks[]` entry names its owning backend via
`backend_name`. The devrig response lists one `backends[]` entry per
discovered backend (including non-routable ones — see the rules below);
a direct in-IDE response lists exactly one entry (the IDE you are
connected to). Both shapes are identical.

To pick a value:

1. Call `steroid_list_projects` and read `backends[]`. Each entry carries
   `backend_name` (the value to pass as `backend_name`, an opaque id like
   `"iu-9fk2a0xQ"`), `displayName` (human label, not unique across
   same-product IDEs), `locator` (disambiguator, e.g.
   `"build IU-261.x, pid 1234"`), `routable` (true only for IDEs you can
   actually open into), `plugins[]` (the IDE's relevant plugins, each
   `{ id, name, version, kind }`; a `kind: "mcp-steroid"` entry means the
   MCP Steroid plugin is installed), `openProjects[]` (`{ project_name, name,
   path, backend_name }` for each project open in that backend), and
   `managed` (true for the devrig-managed sandbox). Each `projects[]`
   entry carries `project_name`, the raw folder `name`, `path`, and a
   `backend_name` naming its owning backend.
2. **Prefer the backend that already has the same project — or another
   git worktree of the same repository — open.** Worktrees of one repo
   share build/index/VCS context, so reusing that IDE keeps it warm and
   avoids a redundant second indexing. Match
   `backends[].openProjects[].path` against the repo you are opening
   (same repo root / shared `.git`); if none matches, prefer a `managed`
   backend, else any listed backend.
3. Pass the chosen `backend_name` to `steroid_open_project`.

Rules:
- `backend_name` is a **devrig-only** parameter; it has no effect on a
  direct in-IDE connection.
- `backends[]` lists **all** discovered backends — marker IDEs (including
  ones with zero open projects), port-only IDEs running **without** the
  MCP Steroid plugin, and devrig-managed backends that are **not
  running**. Only entries with `routable: true` (running IDEs with a live
  MCP Steroid bridge, i.e. a `plugins[]` entry of `kind: "mcp-steroid"`)
  are valid `backend_name` targets for `open_project` — **check
  `routable` before passing a `backend_name`**. Non-routable rows are
  provision/start targets: make them routable with
  `devrig backend provision <id>` / `devrig backend start <id>` (see
  below). Passing a non-routable or unknown `backend_name` returns a
  self-correcting error listing the routable `backend_name`s.
- A `backend_name` is **not stable across IDE restarts** (it is derived
  from the pid) — **re-read `steroid_list_projects` rather than caching**
  it.

**Managing backends from the agent.** To list, provision, or run
backends, call the `devrig` CLI directly — the *same* devrig you run as
your MCP server:

- `devrig backend` — list installed + running backends.
- `devrig backend download <id>` — fetch an IDE.
- `devrig backend start <id>` / `devrig backend stop <id>` — run/stop one.
- `devrig backend provision <id>` — install the MCP Steroid plugin into it.

The backend ids come from `devrig backend --json` (the
`backends[].backend_name` field). Run `devrig` — the stable launcher the
devrig binary keeps current on your `PATH` (under `~/.mcp-steroid/bin`) — e.g.
`devrig backend ...`.

See also: `mcp-steroid://open-project/managing-backends`.

## Critical Rules

### 1. The Script Body is a SUSPEND Function

The script body runs in a **coroutine context**. This means:
- **Prefer Kotlin coroutine APIs** over blocking Java APIs
- You can call any `suspend` function directly
- **NEVER use `runBlocking`** - it will cause deadlocks

### 2. Imports Are Optional

Default imports are provided automatically. Add imports only when you need APIs outside the defaults.

```kotlin
import com.intellij.psi.PsiManager
import com.intellij.openapi.application.readAction

val psiFile = readAction {
    PsiManager.getInstance(project).findFile(virtualFile)
}
```

### 3. Read/Write Actions for PSI/VFS

IntelliJ requires proper threading:
- **`readAction { }`** - for reading PSI, VFS, indices
- **`writeAction { }`** - for modifying PSI, VFS, documents

Both are suspend functions that work naturally in the script body.

## Script Structure Template

```kotlin
// Script body is a suspend function; execute wrapper not required.
// waitForSmartMode() is automatic under modal=smart_non_modal (the default)

val result = readAction {
    // PSI/VFS reads here
}

// Output results
println(result)
```

## Context Available in the Script Body

```kotlin
// Available properties:
project      // The IntelliJ Project instance
params       // Original tool parameters (JsonElement)
disposable   // For resource cleanup
isDisposed   // Check if context is disposed

// Output methods:
println("Values", "separated", "by spaces")
// Pretty-print as JSON
printJson(mapOf("hello" to "world"))

// Report progress
progress("Processing...")

// waitForSmartMode() is automatic under modal=smart_non_modal (the default)
```

## Modal Dialog Handling

By default, if a modal dialog appears during script execution, the code is automatically cancelled and a screenshot of the dialog is returned. This is useful because:

1. Modal dialogs (like "Restart IDE?") block the IDE and require user interaction
2. The LLM agent can see the dialog screenshot and decide how to proceed (use `steroid_input`)
3. Prevents scripts from hanging indefinitely waiting for user input

**When modal cancellation fires:**
- Execution is cancelled immediately
- Screenshot of the dialog is captured and returned
- The result includes "MODAL DIALOG DETECTED" message
- Use `steroid_input` to interact with the dialog, or `steroid_take_screenshot` for a fresh view

**Opening a dialog on purpose:**

By default (`modal=smart_non_modal`) a modal dialog that appears while your script runs is closed and the
call fails. If your script intentionally shows a dialog (a refactoring confirmation, etc.), call
`allowModalDialog()` before the action to disable that monitor for the rest of the run (re-arm with
`monitorAndCloseModalDialogs()`). For a script whose whole job is to drive a dialog, run with
`modal=unleashed` instead (no checks at all).

```kotlin
// We're about to show a dialog on purpose — don't let the monitor close it / fail the run
allowModalDialog()

// Now invoke action that shows a dialog
val actionManager = ActionManager.getInstance()
val action = actionManager.getAction("RestartIde")
val dataContext = SimpleDataContext.builder()
    .add(CommonDataKeys.PROJECT, project)
    .build()
ActionUtil.invokeAction(action, dataContext, "mcp", null, null)
```

## Common Patterns

### 1. Get Project Information

```kotlin
println("Project: ${project.name}")
println("Base path: ${project.basePath}")
println("Is open: ${project.isOpen}")
```

### 2. Access System/IDE Information

```kotlin
// Java version
println("Java: ${System.getProperty("java.version")}")

// IDE log path
val logPath = com.intellij.openapi.application.PathManager.getLogPath()
println("Log: $logPath/idea.log")

// Plugin info
val plugins = com.intellij.ide.plugins.PluginManagerCore.getLoadedPlugins()
plugins.filter { it.isEnabled }.take(10).forEach {
    println("  ${it.name}: ${it.version}")
}
```

### 3. Find and Inspect Plugins

```kotlin
val plugin = com.intellij.ide.plugins.PluginManagerCore.loadedPlugins
    .find { it.pluginId.idString == "com.example.myplugin" }

if (plugin != null) {
    println("Found: ${plugin.name}")
    println("Version: ${plugin.version}")
    println("Enabled: ${plugin.isEnabled}")
    plugin.dependencies.forEach { dep ->
        println("  Depends on: ${dep.pluginId} (optional: ${dep.isOptional})")
    }
}
```

### 4. Query Extension Points

```kotlin
// List all extension points containing "kotlin" or "script"
project.extensionArea.extensionPoints
    .filter { it.name.contains("kotlin", ignoreCase = true) }
    .forEach { ep ->
        println("${ep.name}: ${ep.extensionList.size} extensions")
    }
```

### 5. Open Another Project

```kotlin
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.project.ex.ProjectManagerEx
import java.nio.file.Path

val projectPath = Path.of("/path/to/project")
val projectManager = ProjectManagerEx.getInstanceEx()
val result = projectManager.openProjectAsync(projectPath, OpenProjectTask { })
println("Open result: $result")
```

### 6. Invoke IDE Actions (Including Restart)

Use `ActionManager` to invoke any IDE action programmatically:

```kotlin
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext

val actionManager = ActionManager.getInstance()
val action = actionManager.getAction("RestartIde")  // Or any action ID

if (action == null) {
    println("Action not found")
    return
}

// Create data context with project
val dataContext = SimpleDataContext.builder()
    .add(CommonDataKeys.PROJECT, project)
    .build()

// Invoke the action
println("Invoking action...")
ActionUtil.invokeAction(action, dataContext, "mcp", null, null)
```

**Common action IDs:**
| Action ID | Description |
|-----------|-------------|
| `RestartIde` | Restart IDE |
| `InvalidateAndRestart` | Invalidate Caches and Restart |
| `GotoFile` | Go to File dialog |
| `GotoClass` | Go to Class dialog |
| `GotoSymbol` | Go to Symbol dialog |
| `FindInPath` | Find in Files |
| `ReformatCode` | Reformat Code |

**⚠️ WARNING**: `RestartIde` will terminate your MCP connection!

### 7. Inspect JAR Contents

```kotlin
import java.util.jar.JarFile
import java.io.File

val jarFile = JarFile(File("/path/to/plugin.jar"))
jarFile.entries().toList()
    .filter { it.name.endsWith(".class") }
    .forEach { println(it.name) }
jarFile.close()
```

### 8. Use Reflection for Exploration

```kotlin
try {
    val clazz = Class.forName("org.jetbrains.kotlin.idea.SomeClass")
    println("Found class: ${clazz.name}")

    clazz.methods.filter { it.parameterCount == 0 }.take(10).forEach { m ->
        println("  ${m.name}(): ${m.returnType.simpleName}")
    }
} catch (e: ClassNotFoundException) {
    println("Class not found")
}
```

## Power User Patterns

### Find Usages via PSI

```kotlin
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.openapi.application.readAction


readAction {
    val psiElement = // ... get your element
    val usages = ReferencesSearch.search(psiElement, GlobalSearchScope.projectScope(project))
    usages.forEach { ref ->
        println("Usage at: ${ref.element.containingFile?.virtualFile?.path}:${ref.element.textOffset}")
    }
}
```

### Navigate Project Structure

```kotlin
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil

val contentRoots = ProjectRootManager.getInstance(project).contentRoots
contentRoots.forEach { root ->
    println("Content root: ${root.path}")
    VfsUtil.iterateChildrenRecursively(root, null) { file ->
        if (file.extension == "kt") {
            println("  Kotlin file: ${file.path}")
        }
        true
    }
}
```

### Run Inspections Programmatically

Use `runInspectionsDirectly()` for reliable inspection results - it bypasses the daemon's focus-dependent caching:

```kotlin
val file = requireNotNull(findProjectFile("src/main/kotlin/MyClass.kt")) { "File not found" }

// Recommended: bypasses daemon, works regardless of window focus
val problems = runInspectionsDirectly(file)

if (problems.isEmpty()) {
    println("No problems found!")
} else {
    problems.forEach { (inspectionId, descriptors) ->
        descriptors.forEach { problem ->
            println("[$inspectionId] ${problem.descriptionTemplate}")
        }
    }
}
```

**Note**: The daemon-based `getHighlightsWhenReady()` may return stale results if the IDE window is not focused. Use `runInspectionsDirectly()` for MCP automation.

### Execute Actions

```kotlin
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext

val actionManager = ActionManager.getInstance()
val action = actionManager.getAction("GotoFile")

if (action != null) {
    println("Found action: ${action.templatePresentation.text}")

    // To invoke it:
    val dataContext = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .build()
    ActionUtil.invokeAction(action, dataContext, "mcp", null, null)
}
```

### Discover Available Actions

```kotlin
import com.intellij.openapi.actionSystem.ActionManager

val actionManager = ActionManager.getInstance()
val allActionIds = actionManager.getActionIds("")

// Find actions by keyword
val matchingActions = allActionIds.filter {
    it.contains("refactor", ignoreCase = true)
}

matchingActions.take(20).forEach { actionId ->
    val action = actionManager.getAction(actionId)
    val text = action?.templatePresentation?.text ?: "N/A"
    println("$actionId -> $text")
}

println("Total matching actions: ${matchingActions.size}")
```

## Working with Rider and C# Projects

MCP Steroid fully supports JetBrains Rider for C# and .NET development. All `steroid_*` tools work with Rider projects.

### Key Differences from Java/Kotlin

1. **Language Support**: Rider uses ReSharper for C# language features (PSI, inspections, refactorings).
2. **Project Model**: Solutions (`.sln`) and projects (`.csproj`) instead of Gradle/Maven.
3. **PSI Structure**: C# PSI trees use different element types than Java/Kotlin.
4. **File Types**: Primary files are `.cs`, `.csproj`, and `.sln`.

### Rider-Specific Tools

All MCP Steroid tools work identically in Rider:
- `steroid_execute_code` - Execute Kotlin scripts with full Rider API access
- `steroid_list_projects` - Lists open C# solutions
- `steroid_list_windows` - Works with Rider windows
- `steroid_take_screenshot` - Capture Rider UI
- `steroid_input` - Interact with Rider
- `steroid_open_project` - Open C# solutions

### C# File Operations

Read a C# file:

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiManager

val file = findProjectFile("src/Example.cs")
if (file != null) {
    val text = readAction { PsiManager.getInstance(project).findFile(file)?.text }
    println(text)
} else {
    println("File not found")
}
```

List C# files:

```kotlin
val csFiles = findProjectFiles("**/*.cs")
csFiles.forEach { println(it.path) }
```

Open a C# file in the editor:

```kotlin
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor

val file = findProjectFile("src/Example.cs")
if (file != null) {
    FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, file), true)
    println("Opened Example.cs in editor")
}
```

### ReSharper Integration

Rider integrates ReSharper for C# language features. Key points:
- Standard IntelliJ PSI APIs work for basic operations
- Some advanced features may require ReSharper-specific APIs
- Refactoring and inspections use ReSharper backend services

### Common Gotchas

1. **Solution Structure**: Rider uses `.sln` files; use `project.basePath` for the solution root
2. **File Extensions**: Filter for `"*.cs"` instead of `"*.java"` or `"*.kt"`
3. **PSI Classes**: C# PSI elements have different class names
4. **Project Model**: Modules in Rider map to `.csproj` projects

### Example: Creating a C# Class

```kotlin
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Path

val projectPath = Path.of(requireNotNull(project.basePath))
val projectDir = readAction {
    VfsUtil.findFileByIoFile(projectPath.toFile(), true)
}

writeAction {
    val src = projectDir?.findChild("src") ?: projectDir?.createChildDirectory(this, "src")
    val csFile = src?.createChildData(this, "Example.cs")
    csFile?.setBinaryContent(
        """
        using System;

        namespace MyNamespace
        {
            public class Example
            {
                public static void Main(string[] args)
                {
                    Console.WriteLine("Hello from MCP Steroid!");
                }
            }
        }
        """.trimIndent().toByteArray()
    )
}

println("Created src/Example.cs")
```

### Further Reading

- [C# Examples for Rider](../rider-integration/CSHARP-EXAMPLES.md)
- [Rider Plugin Development](https://plugins.jetbrains.com/docs/intellij/rider.html)

## Error Handling

Always handle errors gracefully:

```kotlin
try {
    // risky operation
} catch (e: Exception) {
    println("Error: ${e.javaClass.simpleName} - ${e.message}")
    e.printStackTrace() // goes to IDE log
}
```

## Best Practices

1. **waitForSmartMode() is automatic under the default `modal=smart_non_modal`** (skipped under `non_modal` / `unleashed`) - call it again only after you trigger indexing mid-script

2. **Use `readAction { }` for any PSI/VFS read** - even simple property access

3. **Use `writeAction { }` for any PSI/VFS modification** - always

4. **Imports are optional** - add top-level imports only when needed

5. **Leverage suspend context** - prefer Kotlin coroutine APIs (`delay`, `async`, `withContext`)

6. **Use meaningful `task_id`** - groups related executions for tracking

7. **Report progress for long operations** - `progress("Step 1 of 5...")`

8. **Print results** - the output is your only way to see what happened

9. **Use reflection cautiously** - APIs may change between IDE versions

10. **Prefer IntelliJ APIs over file operations** - the IDE has already indexed everything

## Debugging Tips

1. **Check IDE logs**: Use `steroid_execute_code` to get the log path:
   ```kotlin
   println(com.intellij.openapi.application.PathManager.getLogPath() + "/idea.log")
   ```

2. **Print class info**: When unsure about an object:
   ```kotlin
   println("Type: ${obj?.javaClass?.name}")
   println("Methods: ${obj?.javaClass?.methods?.map { it.name }}")
   ```

3. **Use printJson**: For complex objects, JSON serialization often works:
   ```kotlin
   printJson(mapOf("key" to value, "count" to items.size))
   ```

## Remember

The MCP Steroid server gives you **direct access to IntelliJ's runtime**. This is incredibly powerful:

- You can query the project model
- You can invoke any IntelliJ API
- You can run refactorings
- You can execute actions
- You can inspect plugins
- You can access PSI (the parsed code model)

**The script body is a suspend function.** Use this to your advantage - call suspend APIs directly, use coroutine primitives, and never block.

**Don't settle for file-level operations when you have IDE-level access.**

Be bold. Explore the API. Use reflection to discover. The IDE is your tool - wield it.

## Available MCP Resources

The MCP server provides comprehensive guides and examples as resources. Load these to learn IntelliJ API patterns:

### Skills (High-Level Guides)

- **`mcp-steroid://skill/intellij-api-poweruser-guide`** - Essential patterns for PSI, refactoring, code search, inspections
- **`mcp-steroid://skill/debugger-guide`** - Debugger APIs: breakpoints, sessions, thread inspection
- **`mcp-steroid://skill/test-runner-guide`** - Test execution and result inspection APIs
- **`mcp-steroid://skill/debug-remote-ide-guide`** - How to debug another IDE instance (CLion, Rider, etc.) from IntelliJ IDEA

### Example Collections

#### LSP-Like Operations
- **`mcp-steroid://lsp/overview`** - Overview of all LSP operation examples
- `mcp-steroid://lsp/go-to-definition`, `find-references`, `hover`, `completion`, `document-symbols`, `rename`, `formatting`, `code-action`, `signature-help`, `workspace-symbol`

#### IDE Power Operations
- **`mcp-steroid://ide/overview`** - Overview of refactorings and code generation
- `mcp-steroid://ide/extract-method`, `introduce-variable`, `inline-method`, `change-signature`, `move-file`, `safe-delete`, `optimize-imports`, `generate-override`, `inspect-and-fix`, `hierarchy-search`, `call-hierarchy`, `run-configuration`, `pull-up-members`, `push-down-members`, `extract-interface`, `move-class`, `generate-constructor`, `project-dependencies`, `inspection-summary`, `project-search`

#### Debugger Operations
- **`mcp-steroid://debugger/overview`** - Overview of debugger examples
- `mcp-steroid://debugger/set-line-breakpoint`, `debug-run-configuration`, `debug-session-control`, `debug-list-threads`, `debug-thread-dump`

#### Test Execution
- **`mcp-steroid://test/overview`** - Overview of test execution and result inspection
- `mcp-steroid://test/list-run-configurations` - List all run configurations
- `mcp-steroid://test/run-tests` - Execute test configuration
- `mcp-steroid://test/wait-for-completion` - Poll for test completion
- `mcp-steroid://test/inspect-test-results` - Access test results and failures
- `mcp-steroid://test/test-tree-navigation` - Navigate test tree hierarchy
- `mcp-steroid://test/test-statistics` - Get test counts and statistics
- `mcp-steroid://test/test-failure-details` - Access detailed failure information
- `mcp-steroid://test/find-recent-test-run` - Find most recent test execution

#### Project Opening
- **`mcp-steroid://open-project/overview`** - How to open projects via MCP
- `mcp-steroid://open-project/open-trusted`, `open-with-dialogs`, `open-via-code`

#### Version Control
- **`mcp-steroid://vcs/overview`** - VCS operation examples
- `mcp-steroid://vcs/git-annotations`, `git-history`

### How to Use Resources

Load resources to get working code examples:

```kotlin
// First, read the overview to understand the workflow
// Load: mcp-steroid://test/overview

// Then load specific examples
// Load: mcp-steroid://test/list-run-configurations

// Copy the code and customize it for your use case

val manager = RunManager.getInstance(project)
val allSettings = manager.allSettings

println("Run Configurations (${allSettings.size}):")
allSettings.forEach { setting ->
    println("  • ${setting.name} (${setting.type.displayName})")
}
```

**Pro Tip**: Always start with the overview resource (e.g., `mcp-steroid://test/overview`) to understand the complete workflow, then load specific examples as needed.
