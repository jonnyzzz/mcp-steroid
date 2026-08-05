/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.arena.AgentToolCall
import com.jonnyzzz.mcpSteroid.integration.arena.decodeAgentFinalResponse
import com.jonnyzzz.mcpSteroid.integration.arena.decodeAgentToolCalls
import com.jonnyzzz.mcpSteroid.integration.infra.ConsoleAwareAgentSession
import com.jonnyzzz.mcpSteroid.integration.infra.DevrigContainer
import com.jonnyzzz.mcpSteroid.integration.infra.DevrigContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IdeTestFolders
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.streamDevrigLogsToConsole
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerClaudeSession
import com.jonnyzzz.mcpSteroid.testHelper.DockerCodexSession
import com.jonnyzzz.mcpSteroid.testHelper.ProjectHomeDirectory
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.git.BareRepoCache
import com.jonnyzzz.mcpSteroid.testHelper.git.GitDriver
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** Clean-machine user journeys for devrig's IU-262 headless Remote Development backend. */
@Execution(ExecutionMode.SAME_THREAD)
class DevrigRemoteDevelopmentKeycloakTypeHierarchyTest {

    @Test
    @Timeout(value = 75, unit = TimeUnit.MINUTES)
    fun `claude installs devrig and uses a remote development backend for keycloak hierarchy`() =
        runScenario("claude")

    @Test
    @Timeout(value = 75, unit = TimeUnit.MINUTES)
    fun `codex installs devrig and uses a remote development backend for keycloak hierarchy`() =
        runScenario("codex")

    @Test
    fun `agent prompt requests the outcome without scripting the bootstrap recipe`() {
        val prompt = buildPrompt()
        for (forbidden in listOf(
            "devrig",
            "steroid_",
            "mcp-steroid://",
            "ClassInheritorsSearch",
            "Observation.awaitConfiguration",
        )) {
            assertTrue(forbidden !in prompt) { "Task-only prompt must not reveal '$forbidden':\n$prompt" }
        }
        for (required in listOf(PROJECT_DIR, IDE_VERSION, KeycloakTypeHierarchyScenario.INTERFACE_FQN)) {
            assertTrue(required in prompt) { "Task-only prompt must state the reproducible outcome '$required':\n$prompt" }
        }
        assertEquals(
            1,
            prompt.lineSequence().count { "Work autonomously with the capabilities already configured for you." in it },
            "Task-only prompt must state its autonomy instruction exactly once:\n$prompt",
        )
    }

    @Test
    fun `keycloak hierarchy score counts implementing classes only`() {
        val output = buildString {
            appendLine("CLS: ${KeycloakTypeHierarchyScenario.INTERFACE_FQN}")
            for (fqn in KeycloakTypeHierarchyScenario.subInterfaces) appendLine("SUBTYPE: $fqn")
            for (fqn in KeycloakTypeHierarchyScenario.requiredTransitive) appendLine("CLS: $fqn")
        }

        val score = scoreKeycloakImplementingClasses(
            output,
            KeycloakTypeHierarchyScenario.requiredTransitive.size,
        )

        assertEquals(KeycloakTypeHierarchyScenario.requiredTransitive, score.reported)
        assertEquals(KeycloakTypeHierarchyScenario.requiredTransitive.size, score.reportedCount)
        assertTrue(score.complete)
    }

    @Test
    fun `final subtype markers reject base and sub-interfaces`() {
        val interfaces = KeycloakTypeHierarchyScenario.subInterfaces + KeycloakTypeHierarchyScenario.INTERFACE_FQN
        for (fqn in interfaces) {
            val failure = assertThrows(AssertionError::class.java) {
                assertFinalSubtypeMarkersContainClassesOnly("SUBTYPE: $fqn")
            }
            assertTrue(fqn in failure.message.orEmpty()) {
                "Failure must identify the interface reported as a class: ${failure.message}"
            }
        }
    }

    private fun runScenario(agentName: String) = runWithCloseableStack { lifetime ->
        BareRepoCache.ensureRepo(KEYCLOAK_REPO_URL, IdeTestFolders.repoCacheDir)

        val container = DevrigContainer.create(
            lifetime,
            DevrigContainerOpts(
                consoleTitle = "devrig-remote-backend-keycloak-$agentName",
                mountRepoCache = true,
                mountDependencyCaches = true,
            ),
        )
        val console = container.console
        console.writeHeader(
            "test-experiments — ${this::class.simpleName} — $agentName installs devrig, " +
                "starts IU 262 Remote Development, and queries Keycloak",
        )

        prepareKeycloak(container)
        installDevrigForAgent(container, agentName)
        assertCleanBackendState(container)
        streamDevrigLogsToConsole(lifetime, File(container.runDir, "devrig-logs"), console)

        var backendMayBeRunning = false
        try {
            val agent = ConsoleAwareAgentSession(
                delegate = createAgentSession(agentName, container),
                console = console,
                agentName = agentName,
                logDir = container.runDir,
            )
            val result = agent.runPrompt(
                buildPrompt(),
                timeoutSeconds = 50 * 60L,
            ).awaitForProcessFinish()
            backendMayBeRunning = true

            val workflow = assertAgentWorkflow(result.rawStdout)
            val finalAnswerScore = assertFinalAnswer(result.rawStdout)
            val improvements = assertImprovements(result.rawStdout)
            val improvementsArtifact = saveImprovements(container, agentName, workflow, improvements)

            assertRemoteBackendState(container)
            if (agentName == "codex" && result.exitCode == 137) {
                console.writeInfo("Codex exited with 137 after all workflow, semantic, and backend evidence passed")
            } else {
                result.assertExitCode(0, "$agentName Remote Development Keycloak hierarchy")
            }
            console.writeSuccess(
                "$agentName completed the hierarchy through the IU 262 Remote Development backend " +
                    "(${workflow.hierarchyScore.reportedCount} tool-result subtypes, " +
                    "${finalAnswerScore.reportedCount} final-answer subtypes, " +
                    "${workflow.startMode}; improvements: $improvementsArtifact)",
            )
        } finally {
            try {
                if (backendMayBeRunning || hasUltimateBackend(container)) {
                    stopAndAssertNoBackendSurvives(container)
                }
            } finally {
                sanitizePreservedBackendLogs(container.runDir)
            }
        }
    }

    private fun prepareKeycloak(container: DevrigContainer) {
        container.console.writeStep("Clone and pin Keycloak $KEYCLOAK_VERSION from the host bare-repository cache")
        val cloned = GitDriver(container.scope).cloneFromCachedBare("keycloak/keycloak", PROJECT_DIR)
        assertTrue(cloned) { "Keycloak must be available from the mounted /repo-cache bare repository." }
        GitDriver(container.scope).checkout(PROJECT_DIR, KEYCLOAK_COMMIT)

        container.execAndAssertWithConsoleStream(
            description = "warm and verify the pinned Keycloak Maven build cache",
            timeoutSeconds = 20 * 60L,
            script = $$"""
                set -euo pipefail
                cd "$$PROJECT_DIR"
                test "$(git rev-parse HEAD)" = "$$KEYCLOAK_COMMIT"
                export JAVA_HOME="/usr/lib/jvm/temurin-21-jdk-$(dpkg --print-architecture)"
                export PATH="$JAVA_HOME/bin:$PATH"
                test -x "$JAVA_HOME/bin/java"
                java -version
                extension_file=.mvn/extensions.xml
                test "$(grep -c '<version>1.2.0</version>' "$extension_file")" -eq 1
                sed -i 's#<version>1\.2\.0</version>#<version>1.2.1</version>#' "$extension_file"
                grep -F '<version>1.2.1</version>' "$extension_file"
                ./mvnw -q -DskipTests -pl server-spi-private,services -am compile
                ./mvnw -q -o -DskipTests -pl server-spi-private,services -am compile
            """.trimIndent(),
        )
    }

    private fun installDevrigForAgent(container: DevrigContainer, agentName: String) {
        val listCommand = when (agentName) {
            "claude" -> "claude mcp list"
            "codex" -> "codex mcp list"
            else -> error("Unknown agent: $agentName")
        }
        container.execAndAssertWithConsoleStream(
            description = "user installs devrig for $agentName",
            timeoutSeconds = 120,
            script = $$"""
                set -euo pipefail
                "$${container.devrig}" install $$agentName
                test -x "$$INSTALLED_DEVRIG"
                registration_check_dir=/tmp/devrig-registration-check
                mkdir -p "$registration_check_dir"
                cd "$registration_check_dir"
                $$listCommand > /tmp/devrig-mcp-list.txt
                grep -F 'mcp-steroid' /tmp/devrig-mcp-list.txt >/dev/null
            """.trimIndent(),
        )
    }

    private fun createAgentSession(agentName: String, container: DevrigContainer): AiAgentSession =
        when (agentName) {
            "claude" -> DockerClaudeSession.create(container.scope)
            "codex" -> DockerCodexSession.create(container.scope)
            else -> error("Unknown agent: $agentName")
        }

    private fun assertCleanBackendState(container: DevrigContainer) {
        container.execAndAssertWithConsoleStream(
            description = "prove the agent starts with no installed or running managed IDE",
            timeoutSeconds = 30,
            script = $$"""
                set -euo pipefail
                backend_root=/home/agent/.mcp-steroid/backends
                state_root=/home/agent/.mcp-steroid/state
                marker_root=/home/agent/.mcp-steroid/markers
                test ! -d "$backend_root" || test -z "$(find "$backend_root" -mindepth 1 -print -quit)"
                test ! -d "$state_root" || test -z "$(find "$state_root" -type f -name '*.pid' -print -quit)"
                test ! -d "$marker_root" || test -z "$(find "$marker_root" -type f -name '*.mcp-steroid' -print -quit)"
            """.trimIndent(),
        )
    }

    private fun buildPrompt(): String = buildString {
        appendLine("# Report Keycloak's complete Authenticator hierarchy")
        appendLine()
        appendLine("The pinned Keycloak checkout is at `$PROJECT_DIR`.")
        appendLine()
        appendLine("Do this with IntelliJ IDEA Ultimate $IDE_VERSION. This clean machine starts with no JetBrains IDE")
        appendLine("installed or running. Work autonomously with the capabilities already configured for you.")
        appendLine()
        append(KeycloakTypeHierarchyScenario.baselineTaskInstructions())
        appendLine()
        appendLine("After the result, explain what was hard or unclear about discovering the IDE capability,")
        appendLine("installing or starting a suitable backend, opening the project, waiting for readiness, and using")
        appendLine("IDE semantics. Suggest prompt, tool-description, server-instruction, or built-in guidance article")
        appendLine("improvements only; do not propose new tools or API methods.")
        appendLine()
        appendLine("<<<IMPROVEMENTS>>>")
        appendLine("(your reflection)")
        appendLine("<<<END_IMPROVEMENTS>>>")
    }

    private fun assertAgentWorkflow(rawNdjson: String): AgentWorkflowEvidence {
        val calls = decodeAgentToolCalls(rawNdjson)
        assertTrue(calls.isNotEmpty()) { "No Claude/Codex tool calls were decoded from raw NDJSON." }

        val downloadIndex = successfulIdeaDownloadIndex(calls)
        assertTrue(downloadIndex >= 0) {
            "Raw agent events do not contain a successful IU $IDE_VERSION download. ${summarizeCalls(calls)}"
        }

        val openIndex = calls.indexOfFirstAfter(downloadIndex) { call ->
            call.toolName == "steroid_open_project" &&
                call.argumentText("project_path") == PROJECT_DIR &&
                call.hasSuccessfulResult()
        }
        assertTrue(openIndex >= 0) {
            "Raw agent events do not contain a successful steroid_open_project for $PROJECT_DIR. " +
                summarizeCalls(calls)
        }

        val listIndex = calls.indexOfFirstAfter(openIndex) { call ->
            call.toolName == "steroid_list_projects" &&
                call.hasSuccessfulResult() &&
                call.result?.text?.contains(PROJECT_DIR) == true
        }
        assertTrue(listIndex >= 0) {
            "Raw agent events do not contain a successful steroid_list_projects result with $PROJECT_DIR. " +
                summarizeCalls(calls)
        }
        val hierarchyExecutions = calls.withIndex().mapNotNull { (index, call) ->
            if (index <= listIndex || call.toolName != "steroid_execute_code") return@mapNotNull null
            val code = call.argumentText("code")
            if (!code.contains(CLASS_HIERARCHY_API)) {
                return@mapNotNull null
            }
            val latestProjectName = latestProjectNameForPath(calls, index, PROJECT_DIR)
                ?: return@mapNotNull null
            if (call.argumentText("project_name") != latestProjectName) return@mapNotNull null
            HierarchyExecution(index, call, latestProjectName)
        }
        assertTrue(hierarchyExecutions.isNotEmpty()) {
            "Raw agent events do not contain a deep ClassInheritorsSearch routed with the latest " +
                "project_name for $PROJECT_DIR. " +
                summarizeCalls(calls)
        }
        val firstSuccessfulHierarchy = hierarchyExecutions.firstOrNull { it.call.hasSuccessfulResult() }
            ?: error("Every deep ClassInheritorsSearch execution failed. ${summarizeCalls(calls)}")
        val hierarchyScore = scoreKeycloakImplementingClasses(
            firstSuccessfulHierarchy.call.result?.text.orEmpty(),
            E2E_MIN_TOTAL,
        )
        assertTrue(hierarchyScore.complete) {
            "The first successful deep ClassInheritorsSearch was incomplete: " +
                "reported=${hierarchyScore.reportedCount}, missing=${hierarchyScore.missingRequired}. " +
                "This usually means the project model/import was not ready. ${summarizeCalls(calls)}"
        }

        val explicitStart = successfulCommandIndex(calls, "backend start idea-ultimate") >= 0
        val startMode = if (explicitStart) "explicit-cli-start" else "open-project-auto-start"
        val failedOpenAttempts = calls.count { it.toolName == "steroid_open_project" && it.result?.isError == true }
        val hierarchyErrors = hierarchyExecutions.count { it.call.result?.isError != false }
        val emptyProjectLists = calls.take(listIndex).count { call ->
            call.toolName == "steroid_list_projects" &&
                call.hasSuccessfulResult() &&
                call.result?.text?.contains(PROJECT_DIR) != true
        }
        val fetchedArticles = calls.asSequence()
            .filter { it.toolName == "steroid_fetch_resource" }
            .map { it.argumentText("uri") }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        val readinessSyncBeforeHierarchy = calls.take(firstSuccessfulHierarchy.index).any { call ->
            if (call.toolName != "steroid_execute_code") return@any false
            val code = call.argumentText("code")
            "Observation.awaitConfiguration" in code &&
                ("MavenProjectsManager" in code || "scheduleUpdate" in code)
        }
        val summary = buildString {
            appendLine("download discovered: yes")
            appendLine("start mode: $startMode")
            appendLine("failed open attempts: $failedOpenAttempts")
            appendLine("empty project-list results before routing: $emptyProjectLists")
            appendLine("list_windows used: ${calls.any { it.toolName == "steroid_list_windows" }}")
            appendLine("screenshot used: ${calls.any { it.toolName == "steroid_take_screenshot" }}")
            appendLine("input used: ${calls.any { it.toolName == "steroid_input" }}")
            appendLine("fetched articles: ${fetchedArticles.ifEmpty { listOf("none") }.joinToString()}")
            appendLine("Maven trigger+await before hierarchy: $readinessSyncBeforeHierarchy")
            appendLine("execute attempts/errors: ${hierarchyExecutions.size}/$hierarchyErrors")
            appendLine("project_name: ${firstSuccessfulHierarchy.projectName}")
            appendLine("tool-result subtype count: ${hierarchyScore.reportedCount}")
        }
        return AgentWorkflowEvidence(hierarchyScore, startMode, summary)
    }

    private fun latestProjectNameForPath(
        calls: List<AgentToolCall>,
        beforeIndex: Int,
        projectPath: String,
    ): String? {
        for (index in (beforeIndex - 1) downTo 0) {
            val call = calls[index]
            val text = call.result?.text.orEmpty()
            if (call.toolName == "steroid_list_projects" && call.hasSuccessfulResult() && projectPath in text) {
                return projectNameForPath(text, projectPath)
            }
        }
        return null
    }

    private fun projectNameForPath(resultText: String, projectPath: String): String {
        val root = Json.parseToJsonElement(resultText) as? JsonObject
            ?: error("steroid_list_projects did not return a JSON object: $resultText")
        val projects = root["projects"] as? JsonArray
            ?: error("steroid_list_projects result has no projects array: $resultText")
        val project = projects.filterIsInstance<JsonObject>().singleOrNull { candidate ->
            candidate["path"]?.jsonPrimitive?.contentOrNull == projectPath
        } ?: error("steroid_list_projects result does not contain exactly one $projectPath entry: $resultText")
        return project["project_name"]?.jsonPrimitive?.contentOrNull
            ?: error("Keycloak project entry has no project_name: $project")
    }

    private fun assertImprovements(rawNdjson: String): String {
        val finalResponse = decodeAgentFinalResponse(rawNdjson)
            ?: error("Raw agent events do not contain a final user-visible response.")
        val match = IMPROVEMENTS_BLOCK.findAll(finalResponse).lastOrNull()
            ?: error("Final response has no <<<IMPROVEMENTS>>> block: $finalResponse")
        val improvements = match.groupValues[1].trim()
        assertTrue(improvements.isNotBlank() && !improvements.startsWith("(your reflection")) {
            "Final response contains only the improvements placeholder: $finalResponse"
        }
        return improvements
    }

    private fun saveImprovements(
        container: DevrigContainer,
        agentName: String,
        workflow: AgentWorkflowEvidence,
        improvements: String,
    ): File {
        val content = buildString {
            appendLine("# Headless backend: agent reflection ($agentName)")
            appendLine()
            appendLine("Generated by DevrigRemoteDevelopmentKeycloakTypeHierarchyTest on ${Instant.now()}.")
            appendLine()
            appendLine("## Machine summary")
            appendLine()
            append(workflow.summary)
            appendLine()
            appendLine("## Agent reflection")
            appendLine()
            appendLine(improvements)
        }
        val artifactName = "IMPROVEMENTS-headless-backend-$agentName.md"
        val runArtifact = container.runDir.resolve(artifactName)
        runArtifact.writeText(content)

        val improvementsDir = ProjectHomeDirectory.requireProjectHomeDirectory()
            .resolve("test-experiments/build/improvements")
            .toFile()
        assertTrue(improvementsDir.mkdirs() || improvementsDir.isDirectory) {
            "Failed to create improvements directory: $improvementsDir"
        }
        val buildArtifact = improvementsDir.resolve(artifactName)
        buildArtifact.writeText(content)
        return buildArtifact
    }

    private fun assertFinalAnswer(rawNdjson: String): TypeHierarchyScore {
        val finalResponse = decodeAgentFinalResponse(rawNdjson)
        assertTrue(finalResponse != null) { "Raw agent events do not contain a final user-visible response." }
        assertFinalSubtypeMarkersContainClassesOnly(finalResponse.orEmpty())
        val score = scoreKeycloakImplementingClasses(finalResponse.orEmpty(), E2E_MIN_TOTAL)
        assertTrue(score.complete) {
            "The agent final response does not contain the complete hierarchy: " +
                "reported=${score.reportedCount}, missing=${score.missingRequired}."
        }
        return score
    }

    private fun scoreKeycloakImplementingClasses(output: String, minTotal: Int): TypeHierarchyScore {
        val markedClasses = IMPLEMENTING_CLASS_MARKER.findAll(output)
            .map { it.groupValues[1].trim().trim('.') }
            .toSet()
        val scoreInput = if (markedClasses.isEmpty()) {
            output
        } else {
            markedClasses.joinToString("\n") { "SUBTYPE: $it" }
        }
        val raw = scoreTypeHierarchy(
            scoreInput,
            KeycloakTypeHierarchyScenario.requiredTransitive,
            minTotal,
        )
        val reportedClasses = raw.reported - KeycloakTypeHierarchyScenario.INTERFACE_FQN -
            KeycloakTypeHierarchyScenario.subInterfaces
        return raw.copy(
            reported = reportedClasses,
            reportedCount = reportedClasses.size,
            complete = raw.missingRequired.isEmpty() && reportedClasses.size >= minTotal,
        )
    }

    private fun assertFinalSubtypeMarkersContainClassesOnly(output: String) {
        val markers = SUBTYPE_MARKER.findAll(output).map { it.groupValues[1].trim().trim('.') }.toSet()
        assertTrue(markers.isNotEmpty()) { "Final answer has no SUBTYPE markers: $output" }
        val interfaces = KeycloakTypeHierarchyScenario.subInterfaces + KeycloakTypeHierarchyScenario.INTERFACE_FQN
        val wronglyReportedInterfaces = markers intersect interfaces
        assertTrue(wronglyReportedInterfaces.isEmpty()) {
            "Final SUBTYPE markers must contain implementing classes, not interfaces: $wronglyReportedInterfaces"
        }
    }

    private fun successfulCommandIndex(calls: List<AgentToolCall>, vararg fragments: String): Int =
        calls.indexOfFirst { candidate ->
            (candidate.toolName.equals("Bash", ignoreCase = true) || candidate.toolName == "command_execution") &&
                fragments.all { it in candidate.argumentText("command") } &&
                candidate.hasSuccessfulResult()
        }

    private fun successfulIdeaDownloadIndex(calls: List<AgentToolCall>): Int =
        calls.indexOfFirst { candidate ->
            val command = candidate.argumentText("command")
            (candidate.toolName.equals("Bash", ignoreCase = true) ||
                candidate.toolName == "command_execution") &&
                "backend download idea-ultimate" in command &&
                ("--version $IDE_VERSION" in command || "--version=$IDE_VERSION" in command) &&
                candidate.hasSuccessfulResult()
        }

    private inline fun List<AgentToolCall>.indexOfFirstAfter(
        previousIndex: Int,
        predicate: (AgentToolCall) -> Boolean,
    ): Int {
        for (index in (previousIndex + 1)..lastIndex) {
            if (predicate(this[index])) return index
        }
        return -1
    }

    private fun assertRemoteBackendState(container: DevrigContainer) {
        container.execAndAssertWithConsoleStream(
            description = "verify IU 262 Remote Development backend, marker, plugin, log, and Keycloak route",
            timeoutSeconds = 180,
            script = $$"""
                set -eEuo pipefail
                failed_invariant="initialize Remote Development backend verification"
                preserve_sanitized_backend_log() {
                  local source_log="$1"
                  local target_log="$2"
                  sed -E \
                    -e 's|Bearer[[:space:]]+[$$BEARER_TOKEN_CHARACTERS]+|<redacted>|g' \
                    -e 's|([?&]_ijt=)[^&"[:space:]]+|\1<redacted>|g' \
                    -e 's|("x-ijt"[[:space:]]*:[[:space:]]*")[^"]*(")|\1<redacted>\2|g' \
                    "$source_log" > "$target_log"
                }
                backend_log_contains_credentials() {
                  local artifact="$1"
                  grep -Eq 'Bearer[[:space:]]+[$$BEARER_TOKEN_CHARACTERS]+|([?&]_ijt=|"x-ijt"[[:space:]]*:[[:space:]]*")[$$MARKER_CREDENTIAL_VALUE_CHARACTERS]+' "$artifact"
                }
                preserve_backend_log_after_failure() {
                  if [ -z "${cache:-}" ] || [ ! -d "$cache/logs" ]; then
                    return 0
                  fi
                  local failed_idea_log
                  failed_idea_log="$(find "$cache/logs" -type f -name 'idea.log' -print -quit)"
                  if [ -z "$failed_idea_log" ]; then
                    printf 'MANAGED_BACKEND_LOG_PRESERVE_SKIPPED: no idea.log under %s\n' "$cache/logs" >&2
                  elif ! preserve_sanitized_backend_log "$failed_idea_log" /mcp-run-dir/managed-backend-idea.log; then
                    printf 'MANAGED_BACKEND_LOG_PRESERVE_FAILED: %s\n' "$failed_idea_log" >&2
                  fi
                  local failed_launcher_log="$cache/logs/managed.log"
                  if [ ! -f "$failed_launcher_log" ]; then
                    printf 'MANAGED_BACKEND_LOG_PRESERVE_SKIPPED: no launcher log at %s\n' "$failed_launcher_log" >&2
                  elif ! preserve_sanitized_backend_log "$failed_launcher_log" /mcp-run-dir/managed-backend-launcher.log; then
                    printf 'MANAGED_BACKEND_LOG_PRESERVE_FAILED: %s\n' "$failed_launcher_log" >&2
                  fi
                }
                report_failed_invariant() {
                  local status=$?
                  local failed_line="$1"
                  local failed_command="$2"
                  trap - ERR
                  set +e
                  preserve_backend_log_after_failure
                  printf 'REMOTE_BACKEND_INVARIANT_FAILED: %s (line=%s command=%s status=%s)\n' \
                    "$failed_invariant" "$failed_line" "$failed_command" "$status" >&2
                  exit "$status"
                }
                trap 'report_failed_invariant "$LINENO" "$BASH_COMMAND"' ERR

                failed_invariant="IU 262 backend directory exists"
                backend_dir="$(find /home/agent/.mcp-steroid/backends -mindepth 1 -maxdepth 1 -type d -name 'idea-ultimate-*' | sort | head -1)"
                test -n "$backend_dir"
                failed_invariant="backend descriptor exists"
                descriptor="$backend_dir/backend.json"
                test -f "$descriptor"
                failed_invariant="backend descriptor is preserved in run artifacts"
                cp "$descriptor" /mcp-run-dir/managed-backend-descriptor.json
                failed_invariant="backend descriptor identifies the exact IU 262 build"
                jq -e '.productKey == "idea-ultimate" and .productCode == "IU" and .version == "$$IDE_VERSION" and .buildNumber == "$$IDE_DESCRIPTOR_BUILD"' "$descriptor" >/dev/null
                id="$(jq -r '.id' "$descriptor")"
                bundle="$backend_dir/$(jq -r '.bundleDirName' "$descriptor")"
                cache="/home/agent/.mcp-steroid/caches/$id"
                failed_invariant="backend PID state exists"
                pid="$(jq -r '.pid' "/home/agent/.mcp-steroid/state/$id.pid")"
                marker="/home/agent/.mcp-steroid/markers/$pid.mcp-steroid"

                failed_invariant="native Remote Development launcher is executable"
                test -x "$bundle/bin/remote-dev-server"
                failed_invariant="Remote Development plugin is installed"
                test -d "$bundle/plugins/remote-dev-server"
                failed_invariant="MCP Steroid plugin and EULA are installed in the backend cache"
                test -d "$cache/plugins/mcp-steroid/lib"
                test -f "$cache/plugins/mcp-steroid/EULA"
                failed_invariant="backend PID is alive"
                kill -0 "$pid"
                failed_invariant="backend process has unattended Remote Development environment"
                tr '\0' '\n' < "/proc/$pid/environ" > /tmp/managed-backend-environment.txt
                grep -Fx 'REMOTE_DEV_JDK_DETECTION=false' /tmp/managed-backend-environment.txt >/dev/null
                grep -Fx 'REMOTE_DEV_NON_INTERACTIVE=1' /tmp/managed-backend-environment.txt >/dev/null
                grep -Fx 'REMOTE_DEV_TRUST_PROJECTS=1' /tmp/managed-backend-environment.txt >/dev/null
                failed_invariant="backend process environment contains no agent API credentials"
                if grep -Eq '^(ANTHROPIC_API_KEY|OPENAI_API_KEY|CODEX_API_KEY)=' /tmp/managed-backend-environment.txt; then
                  printf 'managed backend inherited an agent API credential variable\n' >&2
                  false
                fi
                failed_invariant="MCP marker matches backend PID, home, build, and managed plugin path"
                jq -e --argjson pid "$pid" --arg home "$(readlink -f "$bundle")" --arg build "$$IDE_BUILD" \
                  --arg plugin "$(readlink -f "$cache/plugins/mcp-steroid")" \
                  '.pid == $pid and .ideHome == $home and .ide.build == $build and
                   .plugin.id == "com.jonnyzzz.mcp-steroid" and .mcpSteroidServer.pluginPath == $plugin' "$marker" >/dev/null
                failed_invariant="IDE log confirms Remote Development backend mode"
                grep -R -F -- "IDE run mode: remote development (backend)" "$cache/logs" >/dev/null

                failed_invariant="managed backend idea.log exists"
                idea_log="$(find "$cache/logs" -type f -name 'idea.log' -print -quit)"
                test -n "$idea_log"
                failed_invariant="managed backend idea.log is preserved in run artifacts"
                preserve_sanitized_backend_log "$idea_log" /mcp-run-dir/managed-backend-idea.log
                failed_invariant="preserved managed backend idea.log contains no marker credential"
                if backend_log_contains_credentials /mcp-run-dir/managed-backend-idea.log; then
                  printf 'preserved managed backend idea.log contains an unredacted marker credential\n' >&2
                  false
                fi
                failed_invariant="managed backend launcher log is preserved in run artifacts"
                preserve_sanitized_backend_log "$cache/logs/managed.log" /mcp-run-dir/managed-backend-launcher.log
                failed_invariant="preserved managed backend launcher log contains no marker credential"
                if backend_log_contains_credentials /mcp-run-dir/managed-backend-launcher.log; then
                  printf 'preserved managed backend launcher log contains an unredacted marker credential\n' >&2
                  false
                fi
                failed_invariant="backend JVM uses the managed plugin cache"
                grep -F -- "-Didea.plugins.path=$cache/plugins" "$idea_log" >/dev/null

                failed_invariant="managed backend idea.log has no SEVERE diagnostics"
                if grep -n -F ' SEVERE - ' "$idea_log" > /tmp/unexpected-ide-diagnostics.txt; then
                  cat /tmp/unexpected-ide-diagnostics.txt >&2
                  false
                fi
                failed_invariant="Maven core extensions initialize without a ProvisionException"
                if grep -n -E 'ProvisionException|ErrorInCustomProvider|MavenCoreInitializationFailure' "$idea_log" > /tmp/unexpected-ide-diagnostics.txt; then
                  cat /tmp/unexpected-ide-diagnostics.txt >&2
                  false
                fi
                failed_invariant="MCP Steroid logs no unexpected warning or error"
                grep -n -E ' (WARN|ERROR|SEVERE) - #com\.jonnyzzz\.mcpSteroid\.' "$idea_log" | \
                  grep -v -F '#com.jonnyzzz.mcpSteroid.execution.VcsConfirmationSilencer - [mcp-vcs-silencer]' \
                  > /tmp/unexpected-ide-diagnostics.txt || true
                if test -s /tmp/unexpected-ide-diagnostics.txt; then
                  cat /tmp/unexpected-ide-diagnostics.txt >&2
                  false
                fi

                failed_invariant="devrig project route eventually reports Keycloak"
                project_deadline=$((SECONDS + 60))
                while true; do
                  if "$$INSTALLED_DEVRIG" project --json > /tmp/devrig-projects.json && \
                    jq -e --arg path "$$PROJECT_DIR" '.projects[] | select(.path == $path)' /tmp/devrig-projects.json >/dev/null; then
                    break
                  fi
                  if [ "$SECONDS" -ge "$project_deadline" ]; then
                    false
                  fi
                  sleep 2
                done
                failed_invariant="backend remains alive after the agent disconnects"
                kill -0 "$pid"
                printf 'id=%s\npid=%s\n' "$id" "$pid"
            """.trimIndent(),
        )
    }

    private fun hasUltimateBackend(container: DevrigContainer): Boolean =
        container.scope.startProcessInContainer {
            args("bash", "-lc", "compgen -G '/home/agent/.mcp-steroid/backends/idea-ultimate-*' >/dev/null")
                .timeoutSeconds(10)
                .description("detect IU backend for cleanup")
                .quietly()
        }.awaitForProcessFinish().exitCode == 0

    private fun stopAndAssertNoBackendSurvives(container: DevrigContainer) {
        container.execAndAssertWithConsoleStream(
            description = "stop IU 262 and prove no managed backend process survives",
            timeoutSeconds = 180,
            script = $$"""
                set -euo pipefail
                backend_root="/home/agent/.mcp-steroid/backends/idea-ultimate-$$IDE_VERSION"
                find_managed_backend_processes() {
                  local proc_dir arg found
                  for proc_dir in /proc/[0-9]*; do
                    [ -r "$proc_dir/cmdline" ] || continue
                    found=false
                    while IFS= read -r -d '' arg; do
                      case "$arg" in
                        "$backend_root"/*) found=true ;;
                      esac
                    done < "$proc_dir/cmdline" 2>/dev/null || continue
                    if $found; then
                      printf '%s\n' "${proc_dir##*/}"
                    fi
                  done
                }
                mapfile -t managed_pids < <(
                  {
                    find /home/agent/.mcp-steroid/state -maxdepth 1 -type f -name 'idea-ultimate-*.pid' -exec jq -r '.pid' {} \; 2>/dev/null || true
                    find_managed_backend_processes
                  } | sort -nu
                )
                "$$INSTALLED_DEVRIG" backend stop idea-ultimate --version "$$IDE_VERSION"
                deadline=$((SECONDS + 60))
                for pid in "${managed_pids[@]}"; do
                  while kill -0 "$pid" 2>/dev/null && [ "$SECONDS" -lt "$deadline" ]; do sleep 1; done
                  if kill -0 "$pid" 2>/dev/null; then
                    ps -fp "$pid" >&2 || true
                    exit 1
                  fi
                done
                while true; do
                  mapfile -t surviving_managed_pids < <(find_managed_backend_processes | sort -nu)
                  if [ "${#surviving_managed_pids[@]}" -eq 0 ]; then
                    break
                  fi
                  if [ "$SECONDS" -ge "$deadline" ]; then
                    printf 'managed launcher/backend processes survived teardown: %s\n' \
                      "${surviving_managed_pids[*]}" >&2
                    for pid in "${surviving_managed_pids[@]}"; do ps -fp "$pid" >&2 || true; done
                    exit 1
                  fi
                  sleep 1
                done
                test -z "$(find /home/agent/.mcp-steroid/state -maxdepth 1 -type f -name 'idea-ultimate-*.pid' -print -quit)"
            """.trimIndent(),
        )
    }

    private fun AgentToolCall.argumentText(name: String): String =
        when (val value = arguments[name]) {
            is JsonPrimitive -> value.content
            null -> ""
            else -> value.toString()
        }

    private fun AgentToolCall.hasSuccessfulResult(): Boolean = result?.isError == false

    private fun summarizeCalls(calls: List<AgentToolCall>): String = calls.joinToString(
        prefix = "Calls: ",
        limit = 20,
    ) { call -> "${call.toolName}(${call.arguments}) result=${call.result?.isError}" }

    private data class AgentWorkflowEvidence(
        val hierarchyScore: TypeHierarchyScore,
        val startMode: String,
        val summary: String,
    )

    private data class HierarchyExecution(
        val index: Int,
        val call: AgentToolCall,
        val projectName: String,
    )

    companion object {
        private const val PROJECT_DIR = "/home/agent/keycloak"
        private const val INSTALLED_DEVRIG = "/home/agent/.mcp-steroid/bin/devrig"
        private const val KEYCLOAK_REPO_URL = "https://github.com/keycloak/keycloak.git"
        private const val KEYCLOAK_VERSION = "26.6.4"
        private const val KEYCLOAK_COMMIT = "dc1bfc54bf1462f7e79822adb4c59aba7e25d50f"
        private const val IDE_VERSION = "2026.2.0.1"
        // product-info.json / backend.json stores the numeric build; the runtime marker uses IU-<build>.
        private const val IDE_DESCRIPTOR_BUILD = "262.8665.337"
        private const val IDE_BUILD = "IU-262.8665.337"
        // Lower-bound oracle for the pinned commit; exact-set follow-up is tracked in TODO.md.
        private const val E2E_MIN_TOTAL = 70
        const val CLASS_HIERARCHY_API = "ClassInheritorsSearch"
        const val BEARER_TOKEN_CHARACTERS = "A-Za-z0-9._~+/=-"
        const val MARKER_CREDENTIAL_VALUE_CHARACTERS = "A-Za-z0-9._~+/%=-"
        private val BEARER_CREDENTIAL = Regex("Bearer\\s+[$BEARER_TOKEN_CHARACTERS]+")
        private val IJT_QUERY_CREDENTIAL = Regex("([?&]_ijt=)[^&\"\\s]+")
        private val IJT_HEADER_CREDENTIAL = Regex("(\"x-ijt\"\\s*:\\s*\")[^\"]*(\")")
        private val IMPLEMENTING_CLASS_MARKER =
            Regex("""(?im)^\s*(?:CLS|SUBTYPE)\s*:\s*([\w.$]+)""")
        private val SUBTYPE_MARKER = Regex("""(?im)^\s*SUBTYPE\s*:\s*([\w.$]+)""")
        val IMPROVEMENTS_BLOCK = Regex(
            pattern = """<<<\s*IMPROVEMENTS\s*>>>\s*\n([\s\S]*?)\n\s*<<<\s*END_IMPROVEMENTS\s*>>>""",
            options = setOf(RegexOption.IGNORE_CASE),
        )

        fun redactMarkerCredentials(text: String): String {
            val withoutBearer = BEARER_CREDENTIAL.replace(text, "<redacted>")
            val withoutIjtQuery = IJT_QUERY_CREDENTIAL.replace(withoutBearer) { match ->
                match.groupValues[1] + "<redacted>"
            }
            return IJT_HEADER_CREDENTIAL.replace(withoutIjtQuery) { match ->
                match.groupValues[1] + "<redacted>" + match.groupValues[2]
            }
        }

        fun sanitizePreservedBackendLogs(runDir: File) {
            listOf("managed-backend-idea.log", "managed-backend-launcher.log").forEach { artifactName ->
                val artifact = runDir.resolve(artifactName)
                if (artifact.isFile) artifact.writeText(redactMarkerCredentials(artifact.readText()))
            }
        }
    }
}
