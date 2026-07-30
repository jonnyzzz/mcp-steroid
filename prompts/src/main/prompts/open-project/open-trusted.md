Open Project (Trusted)

Open a project with automatic trust, skipping the trust dialog.

# Open Project (Trusted)

Open a project with automatic trust, skipping the trust dialog.

## Workflow

### Step 1: Open the Project

Call `steroid_open_project` with `trust_project=true`:

```kotlin
val openProjectJson = """
{
  "tool": "steroid_open_project",
  "arguments": {
    "project_path": "/absolute/path/to/your/project",
    "task_id": "open-my-project",
    "reason": "Opening project to implement feature X",
    "trust_project": true
  }
}
""".trimIndent()
println(openProjectJson)
```

The tool will:
1. Mark the project path as trusted (skips trust dialog)
2. Initiate project opening in the background
3. Return immediately with next steps

### Step 2: Wait for Project to Load

Wait 2-5 seconds for the project to initialize. The time depends on:
- Project size
- Number of files to index
- Installed plugins

### Step 3: Verify Project is Open

Call `steroid_list_projects` to verify:

```kotlin
val listProjectsJson = """
{
  "tool": "steroid_list_projects",
  "arguments": {}
}
""".trimIndent()
println(listProjectsJson)
```

Expected response includes your project:

```kotlin
val expectedResponseExample = """
{
  "projects": [
    {"project_name": "your-project-9fk2a0xq", "name": "your-project", "path": "/absolute/path/to/your/project", "backend_name": "iu-9fk2a0xq"}
  ]
}
""".trimIndent()
println("Expected response format:\n$expectedResponseExample")
```

The real response also carries a `backends` lookup (elided above for brevity): on a direct
in-IDE connection it always holds exactly one element resolving `backend_name` to the IDE's
identity (`intellij` = `{name, version, build}`) — see
[Managing IDE backends](mcp-steroid://open-project/managing-backends).

### Step 4: Start Working

Now you can use `steroid_execute_code` with the project:

```kotlin
val executeCodeJson = """
{
  "tool": "steroid_execute_code",
  "arguments": {
    "project_name": "your-project-9fk2a0xq",
    "code": "println(\"Project: ${'$'}{project.name}\")",
    "task_id": "verify-project",
    "reason": "Verifying project is accessible"
  }
}
""".trimIndent()
println(executeCodeJson)
```

## Complete Example Session

```
→ steroid_open_project(project_path="/Users/me/projects/my-app", task_id="open-app", reason="Opening to add feature", trust_project=true)
← Project opening initiated...

→ [wait 3 seconds]

→ steroid_list_projects()
← {"projects":[{"project_name":"my-app-9fk2a0xq","name":"my-app","path":"/Users/me/projects/my-app","backend_name":"iu-9fk2a0xq"}],
   "backends":[{"backend_name":"iu-9fk2a0xq","intellij":{"name":"IntelliJ IDEA 2026.1.3","version":"2026.1.3","build":"IU-261.25134.95"}}]}

→ steroid_execute_code(project_name="my-app-9fk2a0xq", code="println(project.basePath)", ...)
← /Users/me/projects/my-app
```

Route by the `project_name` from `steroid_list_projects` (the unique, opaque key), NOT the
human-readable folder `name`.

## When to Use This Approach

- You trust the project and its build scripts
- You want the fastest way to open a project
- You're automating project operations

## Alternatives

If you need to review the trust dialog first, use the "open-with-dialogs" workflow instead.

## See Also

Related project opening examples:
- [Open Project Overview](mcp-steroid://open-project/overview) - Complete opening guide
- [Open with Dialogs](mcp-steroid://open-project/open-with-dialogs) - Interactive dialog handling
- [Open via Code](mcp-steroid://open-project/open-via-code) - Programmatic opening

Related MCP tools:
- `steroid_open_project` - Tool for opening projects via MCP
- `steroid_list_projects` - List all open projects
- `steroid_list_windows` - Check project initialization status

Overview resources:
- [Open Project Examples Overview](mcp-steroid://open-project/overview) - All project opening workflows
- [IntelliJ API Power User Guide](mcp-steroid://prompt/skill) - Core API patterns
