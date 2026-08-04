package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.CliExtraOption
import com.jonnyzzz.mcpSteroid.mcp.CliOptionType
import com.jonnyzzz.mcpSteroid.mcp.InputSchemaElement
import com.jonnyzzz.mcpSteroid.mcp.McpToolBase
import com.jonnyzzz.mcpSteroid.mcp.ToolCallContext
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.mcp.boolean
import com.jonnyzzz.mcpSteroid.mcp.cliSynopsis
import com.jonnyzzz.mcpSteroid.mcp.description
import com.jonnyzzz.mcpSteroid.mcp.errorResult
import com.jonnyzzz.mcpSteroid.mcp.get
import com.jonnyzzz.mcpSteroid.mcp.param
import com.jonnyzzz.mcpSteroid.mcp.required
import com.jonnyzzz.mcpSteroid.mcp.string
import com.jonnyzzz.mcpSteroid.prompts.generated.openProject.ManagingBackendsPromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeGradlePromptArticle
import com.jonnyzzz.mcpSteroid.prompts.generated.skill.ExecuteCodeMavenPromptArticle
import com.jonnyzzz.mcpSteroid.thisLogger
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable


/**
 * Handler for the steroid_open_project MCP tool.
 *
 * This tool initiates opening a project in IntelliJ. A devrig-managed backend cold start may
 * block until MCP is reachable; after forwarding the request, it does NOT wait for the project
 * to fully open. A frontend client can interact with dialogs that appear (such as the trust
 * project dialog) using screenshot/input tools.
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
    override val cliSynopsis = "open a project in the IDE"

    val projectPath = InputSchemaElement.param("project_path")
        .description("Absolute path to the project directory to open.")
        .cliSynopsis("absolute path to the project directory to open")
        .string()
        .required()
        .registerToSchema()

    val taskId = CommonToolParams.taskId().registerToSchema()

    val reason = CommonToolParams.reason().registerToSchema()

    val trustProject = InputSchemaElement.param("trust_project")
        .description("If true, trust the project path before opening (skips trust dialog). Default: true")
        .cliSynopsis("skip the trust-project dialog (default: true)")
        .boolean()
        .registerToSchema()

    // Devrig-only and OPTIONAL: when omitted with a single candidate the handler picks it automatically.
    // Registered/advertised only when includeBackendName is true.
    val backendName = if (includeBackendName) {
        InputSchemaElement.param("backend_name")
            .description(
                "Optional. The backend to open the project in — an opaque id like \"iu-9fk2a0xQ\" " +
                    "returned by steroid_open_project when called with no backend_name and several " +
                    "candidates exist. Omit when there is exactly one candidate: the handler picks it " +
                    "automatically and starts it if needed. A startable (installed but not running) " +
                    "managed IDE is started automatically; the call blocks until the IDE is reachable. " +
                    "PREFER the backend that already has the same project — or another git worktree of " +
                    "the same repository — open: worktrees share build/index/VCS context, avoiding " +
                    "redundant indexing. See ${ManagingBackendsPromptArticle().uri}."
            )
            .cliSynopsis("backend id from `devrig backend --json` to target")
            .string()
            .registerToSchema()
    } else null

    // Not a tool input: the tool returns as soon as opening starts, and the CLI itself polls
    // list_windows afterwards until the project is initialized. Declared unconditionally — unlike
    // backend_name, this changes nothing on the MCP wire, so it needs no per-surface gate.
    override val cliExtraOptions = listOf(
        CliExtraOption(
            name = "wait",
            type = CliOptionType.BOOLEAN,
            synopsis = "poll until the project is initialized (no modal, indexing done)",
        ),
    )

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
            ),
            context.mcpProgressReporter,
        )
    }

    private companion object {
        val BASE_DESCRIPTION = """Open a project in the IDE. Starting a managed backend may block until its MCP endpoint is reachable; the project-open request itself remains asynchronous.

IMPORTANT: Project opening is ASYNCHRONOUS. Verify routing and build-model readiness separately.

Verification Workflow:
1. Call steroid_open_project with the project path
2. Poll steroid_list_projects until the path appears; keep its opaque project_name for project-scoped calls
3. If a frontend window exists, steroid_list_windows reports modal/indexing/initialized state. An
   unattended Remote Development backend needs no frontend or screenshot; do not block semantic work on one
4. Project listing and window flags prove IDE reachability, not Maven/Gradle model import. On a first
   Maven/Gradle open, fetch `${ExecuteCodeMavenPromptArticle().uri}` or
   `${ExecuteCodeGradlePromptArticle().uri}`, trigger and await configuration exactly as that recipe
   shows (the Maven recipe uses Observation.awaitConfiguration(project)), then run indexed queries in
   smartReadAction

Dialog Handling:
- If trust_project=true (default), the trust dialog is skipped automatically
- Other dialogs (project type, SDK selection, etc.) may still appear in a frontend IDE
- When steroid_list_windows reports modalDialogShowing=true, use steroid_take_screenshot + steroid_input"""

        val BACKEND_NAME_DESCRIPTION = """Choosing a backend (multiple IDEs):
This connection can route to more than one running IDE. Call steroid_open_project WITHOUT a backend_name
first: if there are several candidates the tool returns them in the error message — pick one and retry
with backend_name set. PREFER the backend that already has the same project — or a git worktree of
the same repo — open: worktrees share build/index/VCS context and reusing that IDE avoids redundant
indexing. A startable (installed but not running) managed IDE is started automatically when chosen;
the call blocks until the IDE is reachable.

Managing backends from the agent:
On a clean machine, `devrig backend download --json` lists product ids with their latest stable versions.
Install one with `devrig backend download <id> [--version <version>]`; for an unattended IDEA 2026.2
Remote Development backend use `devrig backend download idea-ultimate --version <version>`. Then call
steroid_open_project: when it is the sole installed candidate, the tool starts it automatically and waits
until it is reachable. No separate start and no frontend window are required. `devrig backend start/stop`
remain available for explicit lifecycle diagnostics; `devrig backend provision` updates a running IDE.
devrig is on PATH as `devrig` — just run it.
See ${ManagingBackendsPromptArticle().uri}."""
    }
}

@Serializable
data class OpenProjectParams(
    val projectPath: String,
    val trustProject: Boolean,
    /**
     * Optional devrig-only routing hint: the stable backend id — the `backend_name` from
     * steroid_list_projects (each project's `backend_name`, and each `backends[]` entry's `backend_name`;
     * also shown by `devrig backend --json`) — that should receive this open request. Null/absent
     * everywhere except a devrig connection. Ignored (logged) by the in-IDE plugin.
     */
    val backendName: String? = null,
)

interface OpenProjectToolHandler {
    suspend fun handleOpenProject(
        openProjectParams: OpenProjectParams,
        callProgress: McpProgressReporter,
    ): ToolCallResult
}
