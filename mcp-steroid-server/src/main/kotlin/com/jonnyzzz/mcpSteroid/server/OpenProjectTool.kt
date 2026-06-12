package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.InputSchemaElement
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.boolean
import com.jonnyzzz.mcpSteroid.mcp.description
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.mcp.get
import com.jonnyzzz.mcpSteroid.mcp.param
import com.jonnyzzz.mcpSteroid.mcp.required
import com.jonnyzzz.mcpSteroid.mcp.string
import com.jonnyzzz.mcpSteroid.thisLogger
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable


/**
 * Handler for the steroid_open_project MCP tool.
 *
 * This tool initiates opening a project in IntelliJ. It does NOT wait for the project
 * to fully open - instead it returns quickly so the client can interact with any dialogs
 * that may appear (such as the trust project dialog) using screenshot/input tools.
 *
 * The tool can optionally trust the project path before opening, which allows skipping
 * the trust dialog.
 */
class OpenProjectToolSpec(
    val includeBackendName: Boolean = false,
    val handler: () -> OpenProjectToolHandler,
) : McpToolBase() {
    private val logger = thisLogger()

    override val name = "steroid_open_project"
    override val description: String = buildString {
        append(BASE_DESCRIPTION)
        if (includeBackendName) append("\n\n").append(BACKEND_NAME_DESCRIPTION)
    }

    val projectPath = InputSchemaElement.param("project_path")
        .description("Absolute path to the project directory to open.")
        .string()
        .required()
        .registerToSchema()

    val taskId = CommonToolParams.taskId().registerToSchema()

    val reason = CommonToolParams.reason().registerToSchema()

    val trustProject = InputSchemaElement.param("trust_project")
        .description("If true, trust the project path before opening (skips trust dialog). Default: true")
        .boolean()
        .registerToSchema()

    // Devrig-only and REQUIRED there (R2.1). Registered/advertised only when includeBackendName is true.
    val backendName = if (includeBackendName) {
        InputSchemaElement.param("backend_name")
            .description(
                "REQUIRED. The backend to open the project in, identified by its `backend_name` from " +
                    "steroid_list_projects (the `backend_name` of each project, and of each `backends[]` " +
                    "entry) — an opaque id like \"iu-9fk2a0xQ\". First call steroid_list_projects and inspect " +
                    "`backends[]` (displayName, locator, routable, openProjects); only `routable: true` " +
                    "entries are valid here. PREFER the backend that already has the same " +
                    "project — or another git worktree of the same repository — open (match " +
                    "backends[].openProjects[].path / shared repo root): worktrees share build/index/VCS " +
                    "context, so reusing that IDE avoids a redundant second indexing. Otherwise prefer a " +
                    "`managed` backend, else any listed backend."
            )
            .string()
            .required()
            .registerToSchema()
    } else null

    override suspend fun call(context: ToolCallContext): ToolCallResult {
        val projectPathStr = context[projectPath]
        context[taskId]
        context[reason]
        val trustProject = context[trustProject] ?: true
        val backendNameValue = backendName?.let { context[it] }

        val requestedProjectPath = try {
            Path.of(projectPathStr).toAbsolutePath().normalize()
        } catch (e: Exception) {
            logger.warn("Invalid project path: $projectPathStr", e)
            return ToolCallResult.errorResult("Invalid project path: $projectPathStr - ${e.message}")
        }

        // Validate that the path exists
        if (!Files.isDirectory(requestedProjectPath)) {
            return ToolCallResult.errorResult("Project path is not a directory: $requestedProjectPath")
        }

        val projectPath = try {
            withContext(Dispatchers.IO) {
                requestedProjectPath.toRealPath()
            }
        } catch (e: Exception) {
            logger.warn("Failed to resolve project path: $requestedProjectPath", e)
            return ToolCallResult.errorResult("Failed to resolve project path: $requestedProjectPath - ${e.message}")
        }

        return handler().handleOpenProject(
            OpenProjectParams(
                projectPath = projectPath.toString(),
                trustProject = trustProject,
                backendName = backendNameValue,
            )
        )
    }

    private companion object {
        const val BASE_DESCRIPTION = """Open a project in the IDE. This tool initiates the project opening process and returns quickly.

IMPORTANT: Project opening is ASYNCHRONOUS. This tool returns immediately; you MUST poll to verify the project is fully ready before using it.

Verification Workflow:
1. Call steroid_open_project with the project path
2. Poll steroid_list_windows repeatedly (every 2-3 seconds) until:
   - The project appears in the windows list
   - modalDialogShowing is false (no dialogs blocking)
   - indexingInProgress is false (indexing complete)
   - projectInitialized is true
3. If modalDialogShowing is true, use steroid_take_screenshot + steroid_input to handle dialogs
4. Use steroid_take_screenshot to visually confirm the project is fully loaded
5. Verify with steroid_list_projects that the project appears

Dialog Handling:
- If trust_project=true (default), the trust dialog is skipped automatically
- Other dialogs (project type, SDK selection, etc.) may still appear
- Always check modalDialogShowing in steroid_list_windows response"""

        const val BACKEND_NAME_DESCRIPTION = """Choosing a backend (multiple IDEs):
This connection can route to more than one running IDE. Call steroid_list_projects first: `backends[]`
lists ALL backends (including non-routable ones); pass a `routable: true` entry's `backend_name` to
open the project in that specific IDE. PREFER the backend that already has the same project — or another git worktree of the
same repository — open (match backends[].openProjects[].path / a shared repo root): worktrees share
build/index/VCS context, so reusing that IDE keeps the context warm and avoids a redundant second
indexing. Otherwise prefer a `managed` backend, else any listed backend.

Managing backends from the agent:
To list/provision/run backends, call the devrig CLI (the same devrig you run as your MCP server):
`devrig backend` (list), `devrig backend download <id>`, `devrig backend start <id>`, `devrig backend
stop <id>`, `devrig backend provision <id>`. Backend ids come from `devrig backend --json` /
backends[].backend_name. Launcher path — macOS/Linux: `devrig` (or `<install>/bin/devrig`); Windows:
`cmd.exe /c devrig.bat backend ...`. devrig needs Java 25: if `java`/`JAVA_HOME` is not 25, set
DEVRIG_JAVA_HOME to a JDK/JRE 25 home for the devrig process. See mcp-steroid://open-project/managing-backends."""
    }
}

@Serializable
data class OpenProjectParams(
    val projectPath: String,
    val trustProject: Boolean,
    /**
     * Optional devrig-only routing hint: the stable backend id (from steroid_list_projects
     * `backend` field or `devrig backend --json` `id`) that should receive this open request.
     * Null/absent everywhere except a devrig connection. Ignored (logged) by the in-IDE plugin.
     */
    val backendName: String? = null,
)

interface OpenProjectToolHandler {
    suspend fun handleOpenProject(openProjectParams: OpenProjectParams): ToolCallResult
}
