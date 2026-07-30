Open Project (With Dialog Handling)

Open a project and handle dialogs interactively.

# Open Project (With Dialog Handling)

Open a project and interactively handle any dialogs that appear.

## Workflow

### Step 1: Open the Project (Without Trust)

Call `steroid_open_project` without trusting:

```kotlin
val openProjectJson = """
{
  "tool": "steroid_open_project",
  "arguments": {
    "project_path": "/absolute/path/to/your/project",
    "task_id": "open-my-project",
    "reason": "Opening project to review trust settings",
    "trust_project": false
  }
}
""".trimIndent()
println(openProjectJson)
```

### Step 2: Take a Screenshot

Use `steroid_take_screenshot` to see the current IDE state. The `project_name` placeholders below are
the unique, opaque routing key from `steroid_list_projects`, NOT the human-readable folder name:

```kotlin
val takeScreenshotJson = """
{
  "tool": "steroid_take_screenshot",
  "arguments": {
    "project_name": "existing-project-9fk2a0xq",
    "task_id": "check-dialogs",
    "reason": "Checking for trust dialog or other prompts"
  }
}
""".trimIndent()
println(takeScreenshotJson)
```

Note: You need an existing open project to take screenshots. If no project is open,
the trust dialog may appear on the welcome screen.

### Step 3: Handle Dialogs with Input

If a dialog appears (e.g., Trust Project dialog), use `steroid_input`:

```kotlin
val inputJson = """
{
  "tool": "steroid_input",
  "arguments": {
    "project_name": "existing-project-9fk2a0xq",
    "task_id": "handle-trust-dialog",
    "reason": "Clicking Trust Project button",
    "window_id": "window_id_from_list_windows",
    "sequence": "click:Left@x,y"
  }
}
""".trimIndent()
println(inputJson)
```

Replace `x,y` with the coordinates of the button from the screenshot.

### Step 4: Repeat Until Project is Open

Continue taking screenshots and handling dialogs until the project opens.

### Step 5: Verify Project is Open

```kotlin
val listProjectsJson = """
{
  "tool": "steroid_list_projects",
  "arguments": {}
}
""".trimIndent()
println(listProjectsJson)
```

## Trust Project Dialog

The Trust Project dialog has these buttons:

| Button | Action |
|--------|--------|
| **Trust Project** | Opens with full functionality |
| **Preview in Safe Mode** | Opens with limited functionality |
| **Don't Open** | Cancels the operation |

## Example: Handling Trust Dialog

```
→ steroid_open_project(project_path="/path/to/untrusted-project", trust_project=false, ...)
← Project opening initiated...

→ steroid_take_screenshot(project_name="other-project-7c1b9d2a", task_id="check", reason="Check for dialogs")
← [Image showing Trust Project dialog with buttons at coordinates]

→ steroid_input(
    project_name="other-project-7c1b9d2a",
    task_id="click-trust",
    reason="Click Trust Project button",
    window_id="...",
    sequence="click:Left@350,200"
  )
← Input delivered

→ steroid_list_projects()
← {"projects":[{"project_name":"untrusted-project-1a2b3c4d","name":"untrusted-project","path":"/path/to/untrusted-project","backend_name":"iu-9fk2a0xq"}, ...],
   "backends":[{"backend_name":"iu-9fk2a0xq","intellij":{"name":"IntelliJ IDEA 2026.1.3","version":"2026.1.3","build":"IU-261.25134.95"}}]}
```

The `project_name` passed to the screenshot/input tools is the unique, opaque routing key from
`steroid_list_projects`, not the human-readable folder `name`.

## When to Use This Approach

- You want to review what the project does before trusting
- You need to handle other dialogs (project type selection, etc.)
- You're debugging project opening issues

## Tips

1. **Component Tree**: The screenshot response includes a component tree that shows
   button locations and labels, making it easier to find click coordinates.

2. **Multiple Dialogs**: Some projects may show multiple dialogs (trust, SDK selection,
   project type). Handle each one sequentially.

3. **Timeout**: If the project takes too long to open, check the IDE logs for errors.

## Alternatives

For faster opening without dialogs, use `trust_project=true` in `steroid_open_project`.

## See Also

Related project opening examples:
- [Open Project Overview](mcp-steroid://open-project/overview) - Complete opening guide
- [Open Trusted](mcp-steroid://open-project/open-trusted) - Auto-trust project opening
- [Open via Code](mcp-steroid://open-project/open-via-code) - Programmatic opening

Related MCP tools:
- `steroid_open_project` - Tool for opening projects via MCP
- `steroid_take_screenshot` - See dialog state
- `steroid_input` - Handle dialog interactions
- `steroid_list_windows` - Check project initialization status

Overview resources:
- [Open Project Examples Overview](mcp-steroid://open-project/overview) - All project opening workflows
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
