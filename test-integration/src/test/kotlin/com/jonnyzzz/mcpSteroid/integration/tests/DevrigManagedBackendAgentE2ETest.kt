/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.ConsoleAwareAgentSession
import com.jonnyzzz.mcpSteroid.integration.infra.DevrigContainer
import com.jonnyzzz.mcpSteroid.integration.infra.DevrigContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IdeTestFolders
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.streamDevrigLogsToConsole
import com.jonnyzzz.mcpSteroid.testHelper.DockerClaudeSession
import com.jonnyzzz.mcpSteroid.testHelper.git.BareRepoCache
import com.jonnyzzz.mcpSteroid.testHelper.git.GitDriver
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * End-to-end console scenario for the **devrig managed-backend self-install** flow an external agent hits:
 * no pre-provisioned IDE — the agent uses devrig to fetch and start one, then opens a real project and
 * answers a code-intelligence question. Each step is visible in the on-video console.
 *
 * Flow:
 * 1. Clone Keycloak from the local bare-repo cache (`file://`, fast — no network fetch).
 * 2. `devrig install claude` — register devrig's `mpc` stdio server as the `mcp-steroid` MCP for Claude.
 * 3. Run Claude with a find-usages task. Claude runs `devrig backend download/start idea-community` itself
 *    (via Bash), the already-running `devrig mpc` discovers the started backend's marker, and Claude drives
 *    the `mcp-steroid` tools to open Keycloak and find usages of a chosen symbol.
 *
 * Heaviest test in the suite: full IDE download + Keycloak clone + indexing + an agent turn. The managed
 * IDEA archive is cached on the host across runs (DevrigContainer RW-mounts the shared IDE download cache).
 *
 * ```
 * ./gradlew :test-integration:test --tests '*DevrigManagedBackendAgentE2ETest*'
 * ```
 */
class DevrigManagedBackendAgentE2ETest {

    private val projectDirInContainer = "/home/agent/keycloak"

    // devrig's home is hardcoded to `~/.mcp-steroid` (= DEVRIG_GUEST_HOME) — we never set DEVRIG_HOME.
    // The whole home stays inside the container (the multi-GB downloaded IDE never touches the host);
    // only its small `logs` dir is bind-mounted to this run's folder (DevrigContainer.create), so the
    // JVM-side monitor (streamDevrigLogsToConsole) can tail the DEBUG log files directly — the same way
    // the IntelliJ idea.log is pumped.
    private val keycloakRepoUrl = "https://github.com/keycloak/keycloak.git"

    @Test
    @Timeout(value = 60, unit = TimeUnit.MINUTES)
    fun `claude uses devrig to provision an IDE and find usages in keycloak`() = runWithCloseableStack { lifetime ->
        // The cached bare repo lets the in-container clone be a local file:// copy instead of a full fetch.
        BareRepoCache.ensureRepo(keycloakRepoUrl, IdeTestFolders.repoCacheDir)

        val container = DevrigContainer.create(
            lifetime,
            DevrigContainerOpts(
                consoleTitle = "managed-backend-agent-e2e",
                mountRepoCache = true,
            ),
        )
        val console = container.console
        // One header banner, leading with the module — classname hierarchy so output is attributable.
        console.writeHeader("test-integration — ${this::class.simpleName} — managed-backend agent E2E (devrig self-install → find usages)")

        console.writeStep("git clone $keycloakRepoUrl $projectDirInContainer  (served from the local bare-repo cache for speed)")
        val clonedFromCache = GitDriver(container.scope).cloneFromCachedBare("keycloak/keycloak", projectDirInContainer)
        assertTrue(clonedFromCache) {
            "Keycloak must be cloneable from the mounted /repo-cache bare repo (warm the cache on the host first)."
        }
        container.execAndAssert(
            description = "confirm Keycloak checkout on disk",
            script = """
                set -euo pipefail
                test -d "$projectDirInContainer/.git"
                test -f "$projectDirInContainer/pom.xml"
            """.trimIndent(),
        )
        console.writeSuccess("Keycloak checked out at $projectDirInContainer")

        console.writeStep("devrig install claude (register devrig mpc as the mcp-steroid MCP server)")
        container.execAndAssertWithConsoleStream(
            description = "devrig install claude",
            timeoutSeconds = 120,
            script = """
                set -euo pipefail
                "${container.devrig}" install claude
                claude mcp list
            """.trimIndent(),
        )

        // Tee devrig's own DEBUG log to the console so its activity (download, backend start, mpc bridge)
        // is visible live — the agent runs devrig via Claude's Bash, which buffers a command's stdout
        // until it finishes, so the log file is the only live window into what devrig is doing.
        streamDevrigLogsToConsole(lifetime, File(container.runDir, "devrig-logs"), console)

        console.writeStep("Run Claude: provision an IDE with devrig, open Keycloak, find usages")
        val claude = ConsoleAwareAgentSession(
            delegate = DockerClaudeSession.create(container.scope),
            console = console,
            agentName = "claude",
            logDir = container.runDir,
        )
        val result = claude.runPrompt(buildPrompt(container.devrig), timeoutSeconds = 45 * 60).awaitForProcessFinish()
        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        // The agent must have driven the whole flow through devrig + the mcp-steroid tools.
        assertTrue(combined.contains("backend") && combined.contains("idea-community")) {
            "Agent must run `devrig backend download/start idea-community`. Output:\n${combined.take(6000)}"
        }
        assertTrue(combined.contains("steroid_open_project")) {
            "Agent must open the Keycloak project through the mcp-steroid tools. Output:\n${combined.take(6000)}"
        }
        assertTrue(combined.contains("steroid_execute_code")) {
            "Agent must run code in the managed IDE to find usages. Output:\n${combined.take(6000)}"
        }
        assertTrue(hasMarker(output, "DEVRIG_IDE_STARTED", "yes")) {
            "Missing DEVRIG_IDE_STARTED marker — the agent did not bring up the managed backend. Output:\n${output.take(6000)}"
        }
        assertTrue(hasMarker(output, "DEVRIG_PROJECT_OPENED", "yes")) {
            "Missing DEVRIG_PROJECT_OPENED marker. Output:\n${output.take(6000)}"
        }
        val usages = usageCount(output)
        assertTrue(usages != null && usages >= 1) {
            "Expected DEVRIG_USAGES: <n>= 1 (the agent found usages of the target symbol in Keycloak). " +
                "Parsed=$usages. Output:\n${output.take(6000)}"
        }

        // The managed backend must have actually started and advertised its MCP Steroid marker.
        container.execAndAssert(
            description = "confirm a managed-backend MCP Steroid marker exists",
            script = """
                set -euo pipefail
                ls /home/agent/.mcp-steroid/markers/*.mcp-steroid >/dev/null
            """.trimIndent(),
        )
        console.writeSuccess("Agent provisioned the IDE via devrig and reported $usages usage(s)")
    }

    private fun buildPrompt(devrigPath: String): String = buildString {
        appendLine("# Task: find usages of `org.keycloak.models.UserModel` in Keycloak")
        appendLine()
        appendLine("Your goal: report how many usages there are of the interface `org.keycloak.models.UserModel`")
        appendLine("in the Keycloak project (checked out at $projectDirInContainer), using a JetBrains IDE through")
        appendLine("the `mcp-steroid` MCP tools (already registered for you, backed by the `devrig` CLI).")
        appendLine()
        appendLine("You do NOT have an IDE running yet — provision one with the `devrig` CLI (launcher: $devrigPath):")
        appendLine()
        appendLine("1. Get an IntelliJ IDEA Community backend running:")
        appendLine("   - Download it (it may already be downloaded/cached for you — this is then quick):")
        appendLine("       \"$devrigPath\" backend download idea-community")
        appendLine("   - Start it:")
        appendLine("       \"$devrigPath\" backend start idea-community")
        appendLine("2. Wait for that IDE to finish booting, then open the project IN IT via MCP: call")
        appendLine("   steroid_open_project with project_path=$projectDirInContainer. Do NOT poll")
        appendLine("   steroid_list_projects — a freshly started IDE has no project open, so just ask to open it.")
        appendLine("   The IDE takes a couple of minutes to boot/connect, so if the call fails because no IDE is")
        appendLine("   reachable yet, wait ~10s and retry steroid_open_project (up to 3 minutes). Note the")
        appendLine("   project_name from the response.")
        appendLine("3. In that project, find the usages with steroid_execute_code (project_name from step 2),")
        appendLine("   using IntelliJ PSI — wait for indexing to finish first, then:")
        appendLine("     - JavaPsiFacade.getInstance(project).findClass(\"org.keycloak.models.UserModel\", allScope)")
        appendLine("     - com.intellij.psi.search.searches.ReferencesSearch.search(psiClass).findAll().size")
        appendLine("   Print the usage count.")
        appendLine("4. Reply with EXACTLY these marker lines and nothing else after them:")
        appendLine("DEVRIG_IDE_STARTED: yes")
        appendLine("DEVRIG_PROJECT_OPENED: yes")
        appendLine("DEVRIG_USAGES: <the number of usages you found>")
    }

    private fun hasMarker(output: String, marker: String, expected: String): Boolean =
        output.lineSequence().any { it.contains("$marker: $expected", ignoreCase = true) }

    private fun usageCount(output: String): Int? =
        output.lineSequence()
            .mapNotNull { Regex("""DEVRIG_USAGES:\s*(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .lastOrNull()
}
