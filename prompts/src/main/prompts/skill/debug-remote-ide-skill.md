How to Debug Another IDE Instance

Guide for AI agents on debugging IntelliJ-based IDEs (CLion, Rider, etc.) using IntelliJ IDEA as the debugger host with MCP Steroid.

# How to Debug Another IDE Instance (for AI Agents)

> **This guide was written entirely by AI agents** using MCP Steroid while working on the IntelliJ Platform codebase.
>
> **Note:** Specific plugin names, paths, and internal details use generic placeholders. Replace `YOUR_PLUGIN_ID`, `YourAction`, and example paths with your actual values.

**Guide for AI Agents:** Debugging IntelliJ-based IDEs (CLion, IDEA, Rider, etc.) using IntelliJ's debugger

---

## Overview

This guide explains how an AI agent can debug an IntelliJ-based IDE (like CLion) by:
1. Launching the IDE in debug mode from IntelliJ IDEA
2. Using MCP Steroid to interact with the debugged IDE
3. Taking screenshots when an attended frontend exists
4. Using the debugger to inject code and inspect runtime state
5. Testing plugin functionality programmatically

**Use Case:** Validating a plugin in the target IDE while having full debugger control

---

## Architecture: Two IDEs Working Together

```
┌─────────────────────────────────────┐
│  IntelliJ IDEA (Debugger Host)      │
│                                     │
│  - intellij project open            │
│  - Run Configurations available     │
│  - Debugger UI active               │
│  - MCP Steroid connected            │
│  - Can execute Kotlin code          │
│  - Can set breakpoints              │
└────────────┬────────────────────────┘
             │ JDWP Debug Connection
             │ (port 60228, etc.)
             ▼
┌─────────────────────────────────────┐
│  Target IDE (Debugged)              │
│                                     │
│  - Running with -agentlib:jdwp      │
│  - Plugin under test loaded         │
│  - Frontend may be attached/absent  │
│  - Run mode confirmed from idea.log │
│  - Fully controllable via debugger  │
│  - State inspectable                │
└─────────────────────────────────────┘
```

**Key Insight:** IntelliJ IDEA becomes your "control center" for debugging any target IDE

---

## Step 1: Identify Available Run Configurations
```kotlin
import com.intellij.execution.RunManager

val runManager = RunManager.getInstance(project)
val allConfigs = runManager.allSettings

println("Available run configurations:")
allConfigs.forEach { config ->
    println("  - ${config.name}")
}
```

**What to Look For:**
- "CLion (dev build)"
- "IDEA (dev build)"
- "Rider (dev build)"
- Any configuration with `DevMainKt` as main class

---

## Step 2: Launch in Debug Mode Programmatically
```kotlin
import com.intellij.execution.RunManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.application.ApplicationManager

// Find the target IDE configuration
val runManager = RunManager.getInstance(project)
val targetConfig = runManager.allSettings.find {
    it.name == "TARGET_IDE (dev build)"  // Replace with your config name
}

if (targetConfig != null) {
    println("Found configuration: ${targetConfig.name}")

    // Get debug executor
    val debugExecutor = DefaultDebugExecutor.getDebugExecutorInstance()

    // Launch in debug mode (asynchronously on EDT)
    ApplicationManager.getApplication().invokeLater {
        println("Launching ${targetConfig.name} in debug mode...")
        ProgramRunnerUtil.executeConfiguration(targetConfig, debugExecutor)
        println("Debug session started")
    }
} else {
    println("Configuration not found")
}
```

**Important:** The IDE launch is asynchronous! Wait for startup before testing.

---

## Step 3: Set Breakpoints Programmatically

```kotlin
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.openapi.vfs.VfsUtil
import java.nio.file.Paths

// Set a breakpoint in your plugin code
val file = VfsUtil.findFile(
    Paths.get(
        project.basePath!!,
        "plugins/your-plugin/src/com/example/YourAction.kt"  // Replace with your path
    ),
    true
)

if (file != null) {
    val breakpointManager = XDebuggerManager.getInstance(project).breakpointManager
    val lineBreakpointType = XLineBreakpointType.EXTENSION_POINT_NAME
        .extensionList
        .filterIsInstance<XLineBreakpointType<*>>()
        .firstOrNull()

    if (lineBreakpointType != null) {
        breakpointManager.addLineBreakpoint(
            lineBreakpointType,
            file.url,
            35, // line number - adjust as needed
            null // condition
        )
        println("Breakpoint set")
    }
}
```

---

## Interacting with the Debugged IDE

### Execute Code in Target IDE's Context

When paused at a breakpoint, use "Evaluate Expression" (Alt+F8) to run code **inside the target IDE's JVM**:
```kotlin
// This code runs IN the target IDE's JVM, not IntelliJ's
import com.intellij.openapi.project.ProjectManager
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

// Get all open projects in target IDE
val projects = ProjectManager.getInstance().openProjects
println("Target IDE has ${projects.size} open projects:")
projects.forEach { p ->
    println("  - ${p.name} at ${p.basePath}")
}

// Check plugin status IN target IDE
val pluginId = PluginId.getId("YOUR_PLUGIN_ID")  // Replace with your plugin ID
val plugin = PluginManagerCore.getPlugin(pluginId)
println("Plugin:")
println("  Loaded: ${plugin != null}")
println("  Version: ${plugin?.version}")
```

### Trigger Actions Programmatically
```kotlin
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.ProjectManager

val targetProjects = ProjectManager.getInstance().openProjects
val targetProject = targetProjects.firstOrNull()

if (targetProject != null) {
    val actionManager = ActionManager.getInstance()
    val myAction = actionManager.getAction("YourPlugin.YourAction")  // Replace

    if (myAction != null) {
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, targetProject)
            .build()

        val event = AnActionEvent.createEvent(dataContext, myAction.templatePresentation, "mcp", ActionUiKind.NONE, null)
        ActionUtil.performAction(myAction, event)
        println("Action invoked")
    }
}
```

---

## Observing the Debugged IDE

### Check Process Status

```kotlin
// Find target IDE process and extract debug port
val findProcess = ProcessBuilder(listOf("bash", "-c", "ps aux | grep DevMainKt | grep -v grep"))
    .redirectErrorStream(true).start()
val processOutput = findProcess.inputStream.bufferedReader().readText()
findProcess.waitFor()
println("Target IDE processes:\n$processOutput")

val extractPort = ProcessBuilder(listOf("bash", "-c", """ps aux | grep DevMainKt | grep -o "address=[^,]*" """))
    .redirectErrorStream(true).start()
val portOutput = extractPort.inputStream.bufferedReader().readText()
extractPort.waitFor()
println("Debug port: $portOutput")
// Expected output: address=127.0.0.1:60228
```

### Monitor Logs

```kotlin
// Monitor target IDE logs (adjust path for your IDE)
val home = System.getProperty("user.home")
val logDir = "$home/Library/Logs/JetBrains/TARGET_IDE/"

val listLogs = ProcessBuilder(listOf("ls", "-la", logDir))
    .redirectErrorStream(true).start()
println("Log directory:\n${listLogs.inputStream.bufferedReader().readText()}")
listLogs.waitFor()

// Search for plugin activity in idea.log
val searchPlugin = ProcessBuilder(listOf("bash", "-c", "grep -i 'your-plugin' '$logDir/idea.log' | tail -20"))
    .redirectErrorStream(true).start()
println("Plugin activity:\n${searchPlugin.inputStream.bufferedReader().readText()}")
searchPlugin.waitFor()

// Look for errors
val searchErrors = ProcessBuilder(listOf("bash", "-c", """grep -i "error\|exception" "$logDir/idea.log" | tail -10"""))
    .redirectErrorStream(true).start()
println("Errors:\n${searchErrors.inputStream.bufferedReader().readText()}")
searchErrors.waitFor()
```

### Check Window Visibility (macOS)

```kotlin
// Check window visibility on macOS
val checkWindows = ProcessBuilder(listOf(
    "osascript", "-e",
    """tell application "System Events" to get count of windows of (first process whose name is "java")"""
)).redirectErrorStream(true).start()
val windowCount = checkWindows.inputStream.bufferedReader().readText().trim()
checkWindows.waitFor()
println("Window count: $windowCount")
// Output 0 only means that no frontend window is visible.
// It does not distinguish a supported Remote Development backend from
// unsupported plain non-backend headless mode.
```

---

## Common Patterns

### Pattern 1: Verify Plugin Loaded
```kotlin
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

val pluginId = PluginId.getId("YOUR_PLUGIN_ID")
val plugin = PluginManagerCore.getPlugin(pluginId)

if (plugin != null) {
    println("Plugin loaded: ${plugin.name} v${plugin.version}")
} else {
    println("Plugin not loaded")
}
```

### Pattern 2: List All Actions
```kotlin
import com.intellij.openapi.actionSystem.ActionManager

val actionManager = ActionManager.getInstance()
val allActionIds = actionManager.getActionIdList("")

val matchingActions = allActionIds.filter {
    it.contains("YourKeyword", ignoreCase = true)
}

println("Matching actions (${matchingActions.size}):")
matchingActions.forEach { actionId ->
    val action = actionManager.getAction(actionId)
    println("  - $actionId: ${action?.javaClass?.simpleName}")
}
```

### Pattern 3: State File Manipulation

```kotlin
// Create/modify plugin state to trigger reload
val pluginStateXml = """
    <?xml version="1.0" encoding="UTF-8"?>
    <project version="4">
      <component name="YourPluginService">
        <!-- Your plugin state here -->
      </component>
    </project>
""".trimIndent()

val stateFile = java.io.File("/path/to/project/.idea/yourPlugin.xml")
stateFile.parentFile.mkdirs()
stateFile.writeText(pluginStateXml)
println("Wrote plugin state to ${stateFile.absolutePath}")
// Target IDE will detect file change and reload
```

---

## Troubleshooting

### Target IDE Won't Start

```kotlin
// Check port availability
val checkPort = ProcessBuilder(listOf("lsof", "-i", ":5005"))
    .redirectErrorStream(true).start()
val portInfo = checkPort.inputStream.bufferedReader().readText()
checkPort.waitFor()
if (portInfo.isBlank()) {
    println("Port 5005 is available")
} else {
    println("Port 5005 is in use:\n$portInfo")
    // Kill conflicting process if needed:
    // ProcessBuilder(listOf("kill", "<PID>")).start().waitFor()
}
```

### Breakpoints Don't Hit

1. Code not executed yet
2. Breakpoint in wrong file/line
3. Source mismatch (rebuild needed)

**Solution:** Add logging or use exception as breakpoint:
```kotlin
println("DEBUG: Reached this point")
// or
throw RuntimeException("Debug marker")
```

### No Visible Window

First distinguish the two modes:

- A supported Remote Development backend can be **frontendless**: no client window is attached. Backend
  product mode takes precedence over the raw AWT-headless flag, so commands such as `rdserver-headless`
  remain supported backends.
- Plain non-backend headless mode is best-effort and unsupported because platform UI assumptions can cause
  long waits and deadlocks.

Check the target `idea.log`: a managed IU-262 backend reports Remote Development backend mode (the validated
native run logged `headless=false`). The explicit `MCP Steroid is running in a headless IDE` warning is
emitted only for plain non-backend headless mode; restart that IDE with a real display or Xvfb. Do not use
the raw `headless=` flag by itself as the mode detector.

For a supported frontendless backend, prove readiness through MCP instead of a window: wait until the
requested path appears in `steroid_list_projects`, retain its opaque `project_name`, then trigger and await
Maven/Gradle configuration before indexed semantic work. `steroid_list_windows` and screenshots are optional
diagnostics only when an attended frontend actually exists.

Use programmatic approaches:
- Monitor logs
- Query via debugger code injection
- Query the target backend through MCP Steroid when it is installed
- Use state file manipulation when appropriate

---

## Best Practices for AI Agents

1. **Always use async launch** - `invokeLater` prevents blocking forever
2. **Wait for initialization** - Monitor logs for "Loaded bundled plugins"
3. **Check logs first** - Before UI automation, verify IDE started correctly
4. **Use multiple evidence sources** - Process status + debug connection + logs + state files
5. **Document everything** - PID, debug port, log excerpts, MCP readiness evidence, and screenshots when a
   frontend exists

### Evidence Checklist

```
Process running (ps aux)
Debug connection active (IntelliJ shows it)
Logs show plugin loaded
No errors in logs
State files updated
= High confidence plugin works
```

---

## Summary

**As an AI Agent, you can:**

1. Launch IDEs in debug mode programmatically
2. Use MCP Steroid to execute code in the host IDE
3. Inject code into target IDE via debugger "Evaluate Expression"
4. Set breakpoints and inspect runtime state
5. Take screenshots for visual verification when a frontend exists
6. Monitor logs in real-time
7. Test plugins without UI automation
8. Modify behavior during debugging

**Workflow:**
```
Launch IDE -> Wait for startup -> Set breakpoints ->
Trigger functionality -> Inspect via debugger ->
Monitor logs -> Collect evidence -> Document results
```

**Remember:**
- Debugging is async - wait for readiness
- Frontendless Remote Development is supported; only plain non-backend headless mode is unsupported
- Logs are your best friend
- Multiple evidence sources = high confidence
- Document everything for reproducibility
