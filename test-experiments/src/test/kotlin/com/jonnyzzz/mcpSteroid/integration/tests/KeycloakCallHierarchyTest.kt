/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.BuildSystem
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJProject
import com.jonnyzzz.mcpSteroid.integration.infra.McpConnectionMode
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.AiAgentSession
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * MCP-win experiment: **inter-procedural CALLER hierarchy on a large codebase** (jonnyzzz/mcp-steroid#169)
 * — the dual of [KeycloakTypeHierarchyTest]'s subtypes walk. The agent must answer: "which REST/HTTP
 * endpoints (JAX-RS `@Path`/`@GET`/`@POST` methods) can transitively REACH `BruteForceProtector.failedLogin`?"
 *
 * On Keycloak 26.6.4 the only production caller is `AuthenticationProcessor.logFailure`, and every path
 * from an endpoint down to it crosses links `grep` cannot follow:
 *  - `AuthenticationFlow.processFlow()/processAction()` — interface dispatch to `DefaultAuthenticationFlow`
 *    / `FormAuthenticationFlow`, the only classes that call `logFailure`;
 *  - `TokenEndpoint.processGrantRequest` reaches it only through a DI provider lookup
 *    (`session.getProvider(OAuth2GrantType.class, grantType)` → `ResourceOwnerPasswordCredentialsGrantType`)
 *    — `TokenEndpoint.java` never mentions `AuthenticationProcessor` at all;
 *  - the broker callback endpoint (`AbstractOAuth2IdentityProvider.Endpoint#authResponse`) reaches it only
 *    through the `AuthenticationCallback` interface (`callback.error(...)` → `IdentityBrokerService`).
 * The IDE's caller hierarchy (CallerMethodsTreeStructure / ReferencesSearch ascent) follows all of these.
 *
 * A/B: each agent runs WITH and WITHOUT MCP, scored purely on completeness ([scoreCallHierarchy]) — did it
 * report the interface-hop endpoints (and enough total) — identically for both modes. The verdict is
 * emitted as an `[ARENA]` block; correctness is a dashboard metric, not a hard pass gate, so a legitimate
 * grep miss is a comparison result, not a red build.
 */
class KeycloakCallHierarchyTest {

    // Read-only reachability question — 50 min covers container start + Keycloak import + the walk.
    @Test @Timeout(value = 50, unit = TimeUnit.MINUTES)
    fun `claude with mcp`() = run("claude", withMcp = true)

    @Test @Timeout(value = 50, unit = TimeUnit.MINUTES)
    fun `claude without mcp`() = run("claude", withMcp = false)

    @Test @Timeout(value = 50, unit = TimeUnit.MINUTES)
    fun `codex with mcp`() = run("codex", withMcp = true)

    @Test @Timeout(value = 50, unit = TimeUnit.MINUTES)
    fun `codex without mcp`() = run("codex", withMcp = false)

    private fun run(agentName: String, withMcp: Boolean) {
        val modeLabel = if (withMcp) "mcp" else "none"
        val lifetime = CloseableStackHost()
        try {
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                // mode last in the title so the run-dir zip is mode-tagged (*-mcp / *-none) for the dashboard.
                consoleTitle = "keycloak-callhierarchy-$agentName-$modeLabel",
                project = IntelliJProject.KeycloakProject,
                aiMode = if (withMcp) AiMode.AI_MCP else AiMode.NONE,
                mcpConnectionMode = if (withMcp) null else McpConnectionMode.None,
            )).waitForProjectReady(buildSystem = BuildSystem.MAVEN)

            val agent: AiAgentSession = when (agentName) {
                "claude" -> session.aiAgents.claude
                "codex" -> session.aiAgents.codex
                else -> error("Unknown agent: $agentName")
            }

            val startedAt = System.currentTimeMillis()
            val result = agent.runPrompt(if (withMcp) withMcpPrompt() else baselinePrompt(), timeoutSeconds = 1800)
                .awaitForProcessFinish()
            val agentDurationMs = System.currentTimeMillis() - startedAt
            val combined = result.stdout + "\n" + result.stderr

            val score = scoreCallHierarchy(combined, REQUIRED_ENDPOINTS, MIN_TOTAL)
            println("[TEST] keycloak call-hierarchy [$agentName+$modeLabel] complete=${score.complete} " +
                    "reported=${score.reportedCount} missing=${score.missingRequired}")

            // Emit the full [ARENA] block (verdict + duration + tokens + tool-call counters) for the dashboard.
            recordSemanticRun(
                scenario = SCENARIO,
                agentName = agentName,
                withMcp = withMcp,
                claimedFix = score.complete,
                rawOutput = result.rawStdout,
                exitCode = result.exitCode,
                agentDurationMs = agentDurationMs,
                runDir = session.runDirInContainer,
                summary = "found ${score.reportedCount} endpoints; missing required ${score.missingRequired}",
            )

            if (withMcp) {
                // Prove MCP was actually exercised; completeness itself is the comparison metric, not a gate.
                assertUsedExecuteCodeEvidence(combined)
            }
        } finally {
            lifetime.closeAllStacks()
        }
    }

    private fun withMcpPrompt(): String = buildString {
        appendLine("The Keycloak project is open in IntelliJ IDEA — a large multi-module Java project.")
        appendLine()
        appendLine(taskDescription())
        appendLine()
        appendLine("Use IntelliJ's caller hierarchy via `steroid_execute_code`. BEFORE your first attempt,")
        appendLine("fetch `mcp-steroid://lsp/find-references` for the PSI search recipes. Walk CALLERS upward")
        appendLine("from the target: `MethodReferencesSearch`/`ReferencesSearch` on each method (an IDE")
        appendLine("call-hierarchy ascent, like `CallerMethodsTreeStructure`). When a frontier method")
        appendLine("implements or overrides an interface method, ALSO search references of the super method")
        appendLine("(`method.findDeepestSuperMethods()`), and follow provider/DI lookups and lambdas")
        appendLine("(`FunctionalExpressionSearch`) — that is where text search loses the chain.")
        appendLine("Do not rely on text search.")
        appendLine()
        appendLine(outputFormat())
        appendLine("TOOL_EVIDENCE: <copy the line starting with execution_id: ...>")
    }

    private fun baselinePrompt(): String = buildString {
        appendLine("The Keycloak project is checked out (a large multi-module Java project).")
        appendLine("IntelliJ MCP tools are unavailable in this run — use shell commands only (grep/rg/find).")
        appendLine()
        appendLine(taskDescription())
        appendLine()
        appendLine(outputFormat())
    }

    private fun taskDescription(): String = buildString {
        appendLine("Task: find EVERY REST/HTTP endpoint that can transitively REACH the method")
        appendLine("`$TARGET_METHOD`")
        appendLine("i.e. every JAX-RS resource method (annotated `@GET`/`@POST`/... , exposed via `@Path`)")
        appendLine("from which some call chain leads to that method. Follow calls through interfaces,")
        append("inherited helper methods, provider lookups, and lambdas. Be exhaustive.")
    }

    private fun outputFormat(): String = buildString {
        appendLine("Output (markers on their own lines):")
        appendLine("ENDPOINTS_FOUND: <total count>")
        append("ENDPOINT: <fully.qualified.ClassName#methodName>   ← one line per endpoint method")
    }

    companion object {
        private const val SCENARIO = "keycloak__call_hierarchy"
        private const val TARGET_METHOD =
            "org.keycloak.services.managers.BruteForceProtector#failedLogin(RealmModel, UserModel, ClientConnection, UriInfo, String)"

        // Endpoints whose chain to failedLogin crosses an interface / DI hop grep cannot follow — verified
        // by hand against the Keycloak 26.6.4 sources (see the class KDoc). Each entry lists acceptable
        // spellings: agents name a nested JAX-RS resource by the outer class, the nested class, or the
        // concrete subclass actually served (OIDCIdentityProvider.OIDCEndpoint extends Endpoint).
        private val REQUIRED_ENDPOINTS = listOf(
            // token grant: TokenEndpoint → session.getProvider(OAuth2GrantType) → ResourceOwnerPassword…GrantType
            setOf("org.keycloak.protocol.oidc.endpoints.TokenEndpoint#processGrantRequest"),
            // broker callback: Endpoint.authResponse → callback.error/cancelled (AuthenticationCallback)
            // → IdentityBrokerService.browserAuthentication → AuthenticationProcessor.authenticate()
            setOf(
                "org.keycloak.broker.oidc.AbstractOAuth2IdentityProvider.Endpoint#authResponse",
                "org.keycloak.broker.oidc.OIDCIdentityProvider.OIDCEndpoint#authResponse",
            ),
        )

        // Beyond the required two there are the greppable browser-flow endpoints (LoginActionsService
        // authenticate/authenticateForm/reset-credentials/registration/first-broker-login…, Authorization-
        // Endpoint buildGet/buildPost, SamlService bindings, DeviceEndpoint, DockerEndpoint) — a dozen-plus
        // total; require a handful so a near-empty answer scores incomplete without punishing naming variance.
        private const val MIN_TOTAL = 5
    }
}
