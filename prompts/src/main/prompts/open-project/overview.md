Open Project Workflow Overview

Overview of how to open projects in IntelliJ via MCP.

# Opening Projects in IntelliJ via MCP

This guide explains how to open projects in IntelliJ using the MCP Steroid plugin.

## Available Tool

### `steroid_open_project`

Opens a project in the IDE. This tool initiates the project opening process and returns quickly.

**Parameters:**
- `project_path` (required): Absolute path to the project directory
- `task_id` (required): Task identifier for logging
- `reason` (required): Why you are opening the project
- `trust_project` (optional): If true, trust the project before opening (default: true)

## Important: Project Opening is Asynchronous

Project opening is **asynchronous** - the `steroid_open_project` tool returns immediately after
initiating the open operation. You **MUST poll** to verify the project is fully ready.

## Verification Workflow (Required)

After calling `steroid_open_project`, follow this workflow:

```
1. Call steroid_open_project(project_path="/path/to/project", trust_project=true, ...)
2. Poll steroid_list_windows() every 2-3 seconds until:
   - The project appears in the windows list
   - modalDialogShowing is false (no dialogs blocking)
   - indexingInProgress is false (indexing complete)
   - projectInitialized is true
3. If modalDialogShowing is true:
   - Call steroid_take_screenshot() to see the dialog
   - Use steroid_input() to interact with the dialog
4. Use steroid_take_screenshot() to visually confirm project is loaded
5. Verify with steroid_list_projects() that the project appears
```

## Window Info Fields for Polling

`steroid_list_windows` returns these fields for each window:

| Field | Description |
|-------|-------------|
| `project_name` | The single routing key for the window's project (null if not a project window). Look up that project's human-readable `name` and `path` via `steroid_list_projects` by this key. |
| `modalDialogShowing` | True if any modal dialog is showing in IDE |
| `indexingInProgress` | True if project is indexing (dumb mode) |
| `projectInitialized` | True if project is fully initialized |

To find the right project for a file or directory path, pick the project (from `steroid_list_projects`) whose `path` is the longest prefix of your target path — this disambiguates nested checkouts and git worktrees.

## Workflows

### Quick Open (Trusted Project)

When you trust the project and want to skip dialogs:

```
1. steroid_open_project(project_path="/path/to/project", trust_project=true, ...)
2. Poll steroid_list_windows() until indexingInProgress=false and projectInitialized=true
3. steroid_list_projects() to verify project is open
```

Resource: `mcp-steroid://open-project/open-trusted`

### Interactive Open (With Dialog Handling)

When you need to see and interact with dialogs:

```
1. steroid_open_project(project_path="/path/to/project", trust_project=false, ...)
2. Poll steroid_list_windows() - check modalDialogShowing
3. If modalDialogShowing=true:
   a. steroid_take_screenshot() to see current state
   b. steroid_input() to click dialog buttons
4. Continue polling until indexingInProgress=false and projectInitialized=true
5. steroid_list_projects() to verify
```

Resource: `mcp-steroid://open-project/open-with-dialogs`

### Programmatic Open (Via Code)

For advanced scenarios using IntelliJ APIs directly:

```kotlin
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.ide.impl.OpenProjectTask
import java.nio.file.Path

// Trust and open via APIs
val path = Path.of("/absolute/path/to/project")
TrustedProjects.setProjectTrusted(path, true)
ProjectManagerEx.getInstanceEx().openProjectAsync(path, OpenProjectTask { })
```

Resource: `mcp-steroid://open-project/open-via-code`

## Trust Project Dialog

When opening an untrusted project, IntelliJ shows a "Trust Project" dialog asking:
- **Trust Project**: Opens with full functionality
- **Preview in Safe Mode**: Opens with limited functionality (no build/run)
- **Don't Open**: Cancels the operation

Using `trust_project=true` in `steroid_open_project` automatically trusts the project,
skipping this dialog entirely.

## Common Scenarios

### Opening a New Project

```
steroid_open_project(
    project_path="/Users/me/projects/new-project",
    task_id="open-new-project",
    reason="Opening project to work on feature X",
    trust_project=true
)
```

### Opening in New Window

```
steroid_open_project(
    project_path="/Users/me/projects/another-project",
    task_id="open-in-new-window",
    reason="Opening second project for comparison",
    trust_project=true,
    force_new_frame=true
)
```

### Checking if Project is Already Open

Before opening, you can check if the project is already open:

```
steroid_list_projects()
```

If the project path matches an already-open project, `steroid_open_project` will
return immediately with a message indicating the project is already open.

## Troubleshooting

### Project not appearing in list
- Poll `steroid_list_windows()` - check if `indexingInProgress` is still true
- Wait for indexing to complete (can take several minutes for large projects)
- Use `steroid_take_screenshot()` to see if there's a dialog waiting
- Check IDE logs for errors

### Trust dialog keeps appearing
- Make sure to set `trust_project=true` in the tool call
- Or use `steroid_input()` to click the "Trust Project" button

### Project opens but is empty/broken
- Check `steroid_list_windows()` for `projectInitialized` status
- Wait for `indexingInProgress` to become false
- The project may need additional configuration
- Check if all required plugins are installed via `steroid_execute_code`
- Some projects require specific SDKs to be configured

### Modal dialog is blocking
- Check `modalDialogShowing` in `steroid_list_windows()` response
- Use `steroid_take_screenshot()` to see the dialog
- Use `steroid_input()` to interact with the dialog

## Available Resources

| Resource URI | Description |
|-------------|-------------|
| `mcp-steroid://open-project/overview` | This overview document |
| `mcp-steroid://open-project/open-trusted` | Example: Open with automatic trust |
| `mcp-steroid://open-project/open-with-dialogs` | Example: Open with dialog handling |
| `mcp-steroid://open-project/open-via-code` | Example: Open via IntelliJ APIs |

---

## Related Resources

### Skill Guides
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API reference and patterns
- [Debugger Skill Guide](mcp-steroid://prompt/debugger-skill) - Debug workflows
- [Test Runner Skill Guide](mcp-steroid://prompt/test-skill) - Test execution

### Project Opening Examples
- [Open Project Overview](mcp-steroid://open-project/overview) - This document
- [Open Trusted](mcp-steroid://open-project/open-trusted) - Auto-trust project opening
- [Open with Dialogs](mcp-steroid://open-project/open-with-dialogs) - Interactive dialog handling
- [Open via Code](mcp-steroid://open-project/open-via-code) - Programmatic opening

### Related Example Guides
- [IDE Examples](mcp-steroid://ide/overview) - IDE power operations
- [LSP Examples](mcp-steroid://lsp/overview) - Code navigation
- [Debugger Examples](mcp-steroid://debugger/overview) - Debugging workflows
- [Test Examples](mcp-steroid://test/overview) - Test execution
- [VCS Examples](mcp-steroid://vcs/overview) - Version control operations
