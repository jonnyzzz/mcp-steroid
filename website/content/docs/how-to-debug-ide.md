---
title: "Debugging IDE with MCP Steroid"
description: "A comprehensive guide for AI agents to debug IntelliJ-based IDEs using the debugger and MCP Steroid"
weight: 80
group: "Examples"
---

> **This documentation was written entirely by AI agents** using MCP Steroid while working on the IntelliJ Platform codebase. It demonstrates what autonomous AI agents can discover and document when given full IDE access through MCP Steroid.
>
> **Note:** Specific plugin names, paths, and internal details have been redacted. Replace placeholders like `YOUR_PLUGIN_ID`, `YourAction`, and example paths with your actual values.

**Guide for AI Agents:** Debugging IntelliJ-based IDEs (CLion, IDEA, Rider, etc.) using IntelliJ's debugger

**Date:** 2026-01-31
**Audience:** AI agents working with IntelliJ Platform development

---

## Overview

This guide explains how an AI agent can debug an IntelliJ-based IDE (like CLion) by:
1. Launching the IDE in debug mode from IntelliJ IDEA
2. Using the MCP Steroid to interact with the debugged IDE
3. Taking screenshots to observe UI state
4. Using the debugger to inject code and inspect runtime state
5. Testing plugin functionality programmatically

**Use Case:** Validating a plugin in the target IDE while having full debugger control

---

## Setup: Two IDEs Working Together

### The Architecture

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
│  - UI may be visible or headless    │
│  - Fully controllable via debugger  │
│  - State inspectable                │
└─────────────────────────────────────┘
```

**Key Insight:** IntelliJ IDEA becomes your "control center" for debugging any target IDE

---

## Step-by-Step: Launch IDE in Debug Mode

### Step 1: Identify Available Run Configurations

**Using MCP Steroid:**

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

**Example Output:**
```
Available run configurations:
  - CLion (dev build)
  - IDEA Ultimate
  - Android Studio
  - Rider (dev build)
```

### Step 2: Launch in Debug Mode Programmatically

**Code to Execute via MCP Steroid:**

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

**What This Does:**
1. Finds the run configuration by name
2. Gets the debug executor (not the run executor)
3. Launches the configuration with debugger attached
4. Returns immediately (async launch)

**Expected Result:**
- Target IDE starts with debugger attached
- Debug panel in IntelliJ shows connection
- Debug port displayed (e.g., 127.0.0.1:60228)
- Can see debugger output in Console tab

### Step 3: Wait for IDE to Start

**Important:** The IDE launch is asynchronous!

```kotlin
// After launching, wait for process to appear
import kotlin.system.measureTimeMillis

println("Waiting for target IDE to start...")

val startTime = System.currentTimeMillis()
val timeout = 120_000 // 2 minutes

while (System.currentTimeMillis() - startTime < timeout) {
    Thread.sleep(2000)
    // Check if process is running (you can parse logs or check PIDs)
    println("  Waiting... (${(System.currentTimeMillis() - startTime) / 1000}s elapsed)")
}

println("Target IDE should be running now")
```

**Better Approach:** Monitor logs or use process checking

---

## Interacting with the Debugged IDE

### Method 1: Using MCP Steroid

**Take Screenshots:**

```kotlin
// Via MCP Steroid tool
steroid_take_screenshot(
    project_name: "intellij",
    task_id: "debug-validation",
    reason: "Capture target IDE UI state"
)
```

**Result:**
- Screenshot saved to `~/.mcp-steroid/runs/[execution-id]/screenshot.png`
- Component tree saved to `screenshot-tree.md`
- Metadata in `screenshot-meta.json`

**Use Case:** Visual verification of UI state, dialogs, panels

### Method 2: Execute Code in Running IDE

**Inject Code via Debugger:**

When target IDE is paused at a breakpoint, you can execute code in its context:

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
println("  Enabled: ${plugin?.isEnabled}")
println("  Version: ${plugin?.version}")
```

**How to Execute:**
1. Set a breakpoint in target IDE code (e.g., in your plugin)
2. Trigger the breakpoint (perform an action, open a file, etc.)
3. When paused, open "Evaluate Expression" in IntelliJ (Alt+F8)
4. Paste code above
5. Execute in target IDE's context

**Power Move:** You can modify target IDE's state from the debugger!

### Method 3: Set Breakpoints Programmatically

**Add Breakpoints Before Launch:**

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
        .firstOrNull { it is XLineBreakpointType }

    if (lineBreakpointType != null) {
        val breakpoint = breakpointManager.addLineBreakpoint(
            lineBreakpointType,
            file.url,
            35, // line number - adjust as needed
            null // condition
        )
        println("Breakpoint set")
    }
}
```

**Use Case:** Automatically pause when specific code executes in target IDE

---

## Observing the Debugged IDE

### Check Process Status

**From Shell:**

```bash
# Find target IDE process
ps aux | grep "DevMainKt" | grep -v grep

# Expected output:
# user  57870  0.6  2.7  444234672  3617568  ?? S  9:31AM  1:52.07 /path/to/java -agentlib:jdwp=... DevMainKt
```

**Extract Debug Port:**

```bash
ps aux | grep "DevMainKt" | grep -o "address=[^,]*"
# Output: address=127.0.0.1:60228
```

### Monitor Logs

**Target IDE's logs location:**

```bash
# Logs directory (adjust path for your IDE)
ls -la ~/Library/Logs/JetBrains/TARGET_IDE/

# Main log file
tail -f ~/Library/Logs/JetBrains/TARGET_IDE/idea.log
```

**Search for plugin activity:**

```bash
# Look for your plugin activity
grep -i "your-plugin" idea.log | tail -20

# Look for errors
grep -i "error\|exception" idea.log | tail -10

# Check plugin loading
grep "Loaded bundled plugins" idea.log
```

### Check Window Visibility

**Using AppleScript (macOS):**

```bash
osascript << 'EOF'
tell application "System Events"
    set javaProc to first process whose name is "java"
    set winCount to count of windows of javaProc

    if winCount > 0 then
        repeat with win in windows of javaProc
            log "Window: " & (name of win)
        end repeat
        return "Found " & winCount & " windows"
    else
        return "No visible windows (headless mode)"
    end if
end tell
EOF
```

**Result tells you:**
- Visible: Can use UI automation
- Headless: Must use programmatic approaches only

---

## Testing Plugin Functionality

### Approach 1: Programmatic Invocation

**Trigger Actions Programmatically:**

```kotlin
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.project.ProjectManager

// Get the target project
val targetProjects = ProjectManager.getInstance().openProjects
val targetProject = targetProjects.firstOrNull()

if (targetProject != null) {
    // Get action by ID
    val actionManager = ActionManager.getInstance()
    val myAction = actionManager.getAction("YourPlugin.YourAction")  // Replace with your action ID

    if (myAction != null) {
        println("Action found: ${myAction.javaClass.name}")

        // Create action event
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, targetProject)
            .build()

        val event = AnActionEvent.createFromDataContext(
            "test-invocation",
            null,
            dataContext
        )

        // Invoke action
        myAction.actionPerformed(event)
        println("Action invoked")
    }
}
```

**Limitation:** This runs in IntelliJ's context, not the target IDE's

### Approach 2: Debugger Code Injection

**When Target IDE is Running:**

1. Open IntelliJ's debug console
2. Switch to target IDE's process
3. Execute code IN target IDE:

```kotlin
// This executes in target IDE's JVM
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.project.ProjectManager

val projects = ProjectManager.getInstance().openProjects
if (projects.isNotEmpty()) {
    val project = projects[0]
    val actionManager = ActionManager.getInstance()

    // List all registered actions containing your keyword
    val allActions = actionManager.getActionIdList("")
    val matchingActions = allActions.filter {
        it.contains("YourKeyword", ignoreCase = true)  // Replace with your keyword
    }

    println("Matching actions in target IDE:")
    matchingActions.forEach { actionId ->
        val action = actionManager.getAction(actionId)
        println("  - $actionId: ${action?.javaClass?.name}")
    }
}
```

**This shows:** What actions are actually available in the running target IDE

### Approach 3: State File Manipulation

**Modify Plugin State Files:**

```bash
# Create a plugin state file
cat > /path/to/project/.idea/yourPlugin.xml << 'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="YourPluginService">
    <!-- Your plugin state here -->
  </component>
</project>
EOF

# Target IDE will detect file change and reload
```

**Result:** Plugin reacts to state changes

---

## Debugging Workflow Example

### Complete Example: Validate Plugin

**Step 1: Launch Target IDE in Debug Mode**

```kotlin
val runManager = RunManager.getInstance(project)
val targetConfig = runManager.allSettings.find { it.name == "TARGET_IDE (dev build)" }

if (targetConfig != null) {
    val debugExecutor = DefaultDebugExecutor.getDebugExecutorInstance()
    ApplicationManager.getApplication().invokeLater {
        ProgramRunnerUtil.executeConfiguration(targetConfig, debugExecutor)
    }
    println("Target IDE launching in debug mode")
}
```

**Step 2: Wait for Startup**

```bash
# Monitor log for startup completion
tail -f ~/Library/Logs/JetBrains/TARGET_IDE/idea.log | grep "Loaded bundled plugins"
```

**Step 3: Set Breakpoint in Plugin Code**

In IntelliJ, navigate to your plugin's action class and click the gutter to set a breakpoint.

**Step 4: Trigger Action in Target IDE**

Perform the action in the target IDE that triggers your plugin code.

**Step 5: Breakpoint Hits in IntelliJ**

When target IDE executes the code:
1. IntelliJ debugger pauses execution
2. You see target IDE's code in IntelliJ's editor
3. Variables panel shows target IDE's runtime state
4. You can step through code, inspect variables, etc.

**Step 6: Inspect State via Debugger**

In IntelliJ's "Evaluate Expression" (Alt+F8):

```kotlin
// Show current project
project.name

// Show selected file
virtualFile?.path

// Read file content
val content = virtualFile?.inputStream?.readBytes()
println("File size: ${content?.size} bytes")
```

**Step 7: Step Through Execution**

Use debugger controls:
- F8: Step Over
- F7: Step Into
- Shift+F8: Step Out
- F9: Resume

**Step 8: Verify Success**

```bash
# Check logs for successful operation
grep "your-plugin" ~/Library/Logs/JetBrains/TARGET_IDE/idea.log | tail -5

# Look for errors
grep "ERROR\|Exception" ~/Library/Logs/JetBrains/TARGET_IDE/idea.log | tail -5
```

---

## Visual Verification

### Take Screenshot of Target IDE

**Using MCP Steroid (from IntelliJ):**

```kotlin
// This is a tool call, not Kotlin code
steroid_take_screenshot(
    project_name: "intellij",
    task_id: "validation",
    reason: "Capture target IDE UI state"
)
```

**Result:**
- Screenshot of IntelliJ (not target IDE directly)
- Shows debugger state, console output
- Component tree shows UI elements

**For target IDE specifically (macOS):**

```bash
# Use macOS screencapture
screencapture -w /tmp/target-ide-screenshot.png

# -w: Capture specific window (interactive selection)
```

### Analyze Component Tree

```bash
# Read component tree from screenshot
cat ~/.mcp-steroid/runs/[execution-id]/screenshot-tree.md

# Look for specific components
grep -i "your-component" screenshot-tree.md
```

---

## Advanced: Inject Code via Debugger

### Scenario: Test a Feature

**Goal:** Verify a specific feature works correctly

**Step 1: Set Breakpoint**

In your plugin code at the relevant method:

```kotlin
private fun processData(text: String): String {
    // Breakpoint HERE
    // Your processing logic
    return processedText
}
```

**Step 2: Trigger the Feature**

Create test data and trigger the feature in target IDE.

**Step 3: Inspect in Debugger**

When paused at breakpoint:

```kotlin
// In Evaluate Expression:
text  // See input value

// Check intermediate results
val result = processData(text)
result  // See output

// Verify it worked
result.contains("expected")  // true
```

**Step 4: Modify Behavior (Advanced)**

```kotlin
// You can even CHANGE the code while debugging!

// In Evaluate Expression, execute:
return "MODIFIED: $text"  // Custom return value

// Then press F9 to resume with YOUR return value
```

**Result:** You can test and modify features in real-time!

---

## Common Patterns

### Pattern 1: Verify Plugin Loaded

```kotlin
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

val pluginId = PluginId.getId("YOUR_PLUGIN_ID")  // Replace with your plugin ID
val plugin = PluginManagerCore.getPlugin(pluginId)

if (plugin != null && plugin.isEnabled) {
    println("Plugin loaded: ${plugin.name} v${plugin.version}")
} else {
    println("Plugin not loaded or disabled")
}
```

### Pattern 2: Check Class Accessibility

```kotlin
try {
    val clazz = Class.forName("com.example.YourAction")  // Replace with your class
    println("Class accessible: ${clazz.name}")
} catch (e: ClassNotFoundException) {
    println("Class not found: ${e.message}")
}
```

### Pattern 3: List All Actions

```kotlin
import com.intellij.openapi.actionSystem.ActionManager

val actionManager = ActionManager.getInstance()
val allActionIds = actionManager.getActionIdList("")

val matchingActions = allActionIds.filter {
    it.contains("YourKeyword", ignoreCase = true)  // Replace with your keyword
}

println("Matching actions (${matchingActions.size}):")
matchingActions.forEach { actionId ->
    val action = actionManager.getAction(actionId)
    println("  - $actionId: ${action?.javaClass?.simpleName}")
}
```

### Pattern 4: Monitor Log in Real-Time

```bash
# Terminal 1: Monitor logs
tail -f ~/Library/Logs/JetBrains/TARGET_IDE/idea.log | \
  grep -i --line-buffered "your-plugin\|error\|exception"

# Terminal 2: Trigger actions
# (perform actions, modify state, etc.)

# See logs appear in real-time
```

---

## Troubleshooting

### Target IDE Won't Start

**Check:**
1. Is a build required first?
2. Is config/system directory accessible?
3. Are ports available (debug port might be in use)?

**Solution:**

```bash
# Check port availability
lsof -i :5005  # or whatever debug port

# Kill conflicting process
kill <PID>

# Retry launch
```

### Breakpoints Don't Hit

**Reasons:**
1. Code not executed yet
2. Breakpoint in wrong file/line
3. Source mismatch (rebuild needed)

**Solution:**

```kotlin
// Add logging instead
println("DEBUG: Reached this point")

// Or use Exception as breakpoint
throw RuntimeException("Debug marker")
```

### No Visible Window

**Reason:** Target IDE running headless (no GUI)

**Support status:** headless IDEs are unsupported (best-effort) for MCP Steroid. The platform
behaves differently without a UI and long blocking waits/deadlocks in platform code have been
observed — see [#177](https://github.com/jonnyzzz/mcp-steroid/issues/177). On startup the plugin
logs an `IDE run mode: ...` INFO line and, for a plain headless IDE, a
`MCP Steroid is running in a headless IDE` WARN in `idea.log`. Give the IDE a real display instead: the normal GUI on
macOS/Windows, or a virtual one via Xvfb on Linux/CI (see [Running devrig in CI](/docs/running-on-ci/)). The workarounds
below are best-effort only.

**Approach:**
- Don't rely on UI automation
- Use programmatic testing
- Monitor logs
- Use state file manipulation

**Verify headless:**

```bash
osascript -e 'tell application "System Events" to get count of windows of (first process whose name is "java")'
# Output: 0 = headless
```

### Plugin Classes Not Found

**Reason:** Dependency issue

**Solution:**

```bash
# Check plugin.xml dependencies
cat plugins/your-plugin/resources/META-INF/plugin.xml | grep -A5 "dependencies"

# Verify JARs exist
ls -la out/dev-run/TARGET_IDE/plugins/your-plugin/

# Check logs
grep "ClassNotFoundException" ~/Library/Logs/JetBrains/TARGET_IDE/idea.log | tail -5
```

---

## Best Practices for AI Agents

### 1. Always Use Async Launch

```kotlin
// GOOD: Async launch
ApplicationManager.getApplication().invokeLater {
    ProgramRunnerUtil.executeConfiguration(config, executor)
}

// BAD: Blocking launch
ProgramRunnerUtil.executeConfiguration(config, executor)  // Blocks forever
```

### 2. Wait for Initialization

```kotlin
// Don't test immediately after launch
println("Launched target IDE, waiting 30s for startup...")
Thread.sleep(30_000)

// Better: Monitor logs for "Loaded bundled plugins"
```

### 3. Check Logs First

```bash
# Before UI automation, check if IDE started correctly
tail -100 ~/Library/Logs/JetBrains/TARGET_IDE/idea.log | \
  grep -i "error\|exception" | \
  wc -l

# If > 0, investigate before proceeding
```

### 4. Use Multiple Evidence Sources

```
Process running (ps aux)
Debug connection active (IntelliJ shows it)
Logs show plugin loaded
No errors in logs
State files updated
= High confidence plugin works
```

### 5. Document Everything

```markdown
## Test: Plugin Feature
**Status:** Success
**Evidence:**
- PID: 57870
- Debug port: 60228
- Log excerpt: [attach]
- Screenshot: [attach]
- Conclusion: Plugin functional
```

---

## Summary

**As an AI Agent, you can:**

1. Launch IDEs in debug mode programmatically
2. Use MCP Steroid to execute code
3. Set breakpoints and inspect runtime state
4. Take screenshots for visual verification
5. Monitor logs in real-time
6. Inject code via debugger console
7. Test plugins without UI automation
8. Modify behavior during debugging
9. Collect comprehensive evidence
10. Validate functionality programmatically

**Key Tools:**
- MCP Steroid (`steroid_execute_code`, `steroid_take_screenshot`)
- Debugger console (Evaluate Expression)
- Log monitoring (`tail -f idea.log`)
- Process inspection (`ps aux`)
- State file manipulation
- AppleScript (for UI when available)

**Workflow:**
```
Launch IDE -> Wait for startup -> Set breakpoints ->
Trigger functionality -> Inspect via debugger ->
Monitor logs -> Collect evidence -> Document results
```

**Remember:**
- Debugging is async - wait for readiness
- Headless mode is common - adapt your approach
- Logs are your best friend
- Multiple evidence sources = high confidence
- Document everything for reproducibility
