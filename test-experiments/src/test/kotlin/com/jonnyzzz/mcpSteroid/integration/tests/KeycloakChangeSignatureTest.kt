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
 * MCP-win experiment: **project-wide change signature** (jonnyzzz/mcp-steroid#169) — the sequel to
 * [KeycloakRenameTest]. The agent adds a trailing `boolean silent` parameter to
 * `org.keycloak.authentication.Authenticator#authenticate(AuthenticationFlowContext)` — a widely
 * overridden SPI method — and must ripple the change through EVERY override and call site so the
 * project still compiles.
 *
 * Verified against the pinned Keycloak 26.6.4 tag, the production modules (server-spi-private +
 * services) contain 29 override declarations beyond the interface itself, including the traps a manual
 * sweep misses: a `default` method in a sub-interface (`ConditionalAuthenticator`), an abstract base
 * (`AbstractIdpAuthenticator`), an anonymous class (`SpnegoAuthenticatorFactory.SINGLETON_DISABLED`),
 * plus `super.authenticate(context)` call sites and javadoc/JS-string mentions of `authenticate(context)`
 * (`ScriptBasedAuthenticator`) that must NOT be touched.
 *
 * With MCP the agent drives IntelliJ's `ChangeSignatureProcessor` via `steroid_execute_code` — PSI
 * updates the declaration, all overrides and all call sites atomically, with the new argument's default
 * value inserted at call sites. Without MCP, a shell/editor sweep must find each override by hand.
 *
 * Verdict ([scoreChangeSignature]): signature changed AND all ground-truth overrides updated AND the
 * post-change build reported SUCCESS — emitted as an `[ARENA]` block. A/B per agent; with-MCP asserts
 * exec_code; correctness is a dashboard metric, not a hard gate.
 */
class KeycloakChangeSignatureTest {

    // 80 min, following the KeycloakRenameTest precedent: the without-MCP baseline is edit-heavy — it
    // legitimately needs ~30 min of agent work for the manual override sweep + rebuild (rename measured
    // 30.3 min agent time on CI build 991971402, and container start + Keycloak import + verification
    // pushed the method past 50). The slow baseline IS the experiment's finding — it must complete and
    // emit its [ARENA] block so the dashboard can show the gap, not die as a timeout with no data.
    // TC-side executionTimeoutMin=180 fits two 80-min methods.

    @Test @Timeout(value = 80, unit = TimeUnit.MINUTES)
    fun `claude with mcp`() = run("claude", withMcp = true)

    @Test @Timeout(value = 80, unit = TimeUnit.MINUTES)
    fun `claude without mcp`() = run("claude", withMcp = false)

    @Test @Timeout(value = 80, unit = TimeUnit.MINUTES)
    fun `codex with mcp`() = run("codex", withMcp = true)

    @Test @Timeout(value = 80, unit = TimeUnit.MINUTES)
    fun `codex without mcp`() = run("codex", withMcp = false)

    private fun run(agentName: String, withMcp: Boolean) {
        val modeLabel = if (withMcp) "mcp" else "none"
        val lifetime = CloseableStackHost()
        try {
            val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
                consoleTitle = "keycloak-changesig-$agentName-$modeLabel",
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
            val result = agent.runPrompt(if (withMcp) withMcpPrompt() else baselinePrompt(), timeoutSeconds = 2400)
                .awaitForProcessFinish()
            val agentDurationMs = System.currentTimeMillis() - startedAt
            val combined = result.stdout + "\n" + result.stderr

            val score = scoreChangeSignature(combined, REQUIRED_OVERRIDES)
            println("[TEST] keycloak change-signature [$agentName+$modeLabel] safe=${score.safe} " +
                    "signatureChanged=${score.signatureChanged} missing=${score.missingOverrides.size} " +
                    "buildGreen=${score.buildGreen}")
            if (score.missingOverrides.isNotEmpty()) {
                println("[TEST]   missed overrides: ${score.missingOverrides}")
            }

            recordSemanticRun(
                scenario = SCENARIO,
                agentName = agentName,
                withMcp = withMcp,
                claimedFix = score.safe,
                rawOutput = result.rawStdout,
                exitCode = result.exitCode,
                agentDurationMs = agentDurationMs,
                runDir = session.runDirInContainer,
                summary = "signatureChanged=${score.signatureChanged} " +
                        "overrides=${score.reportedOverrides.size} missing=${score.missingOverrides.size} " +
                        "buildGreen=${score.buildGreen}",
            )

            if (withMcp) assertUsedExecuteCodeEvidence(combined)
        } finally {
            lifetime.closeAllStacks()
        }
    }

    private fun taskDescription(): String = buildString {
        appendLine("Task: change the signature of the method `$SYMBOL`")
        appendLine("by adding a trailing parameter `boolean silent` — the new signature is")
        appendLine("`void authenticate(AuthenticationFlowContext context, boolean silent)`.")
        appendLine()
        appendLine("The change must ripple PROJECT-WIDE so everything still compiles:")
        appendLine("- the interface declaration itself,")
        appendLine("- EVERY override — including `default` methods in sub-interfaces, abstract base classes,")
        appendLine("  and anonymous classes,")
        appendLine("- EVERY call site — pass `false` as the new argument (including `super.authenticate(...)` calls).")
        appendLine("Do NOT modify javadoc comments or string literals that merely mention `authenticate(context)`.")
    }

    private fun withMcpPrompt(): String = buildString {
        appendLine("The Keycloak project is open in IntelliJ IDEA — a large multi-module Java project.")
        appendLine()
        append(taskDescription())
        appendLine()
        appendLine("Use IntelliJ's Change Signature refactoring via `steroid_execute_code`:")
        appendLine("`com.intellij.refactoring.changeSignature.ChangeSignatureProcessor` with the existing")
        appendLine("parameters plus a new `ParameterInfoImpl` (type `boolean`, default value `false` for call")
        appendLine("sites) — PSI updates the declaration, all overrides and all call sites atomically. Do NOT sed.")
        appendLine("After the change, build the affected modules (e.g. server-spi-private + services) to verify.")
        appendLine()
        appendLine("Output (markers on their own lines):")
        appendLine("SIGNATURE_CHANGED: yes")
        appendLine("OVERRIDES_UPDATED: <total count of updated overriding methods>")
        appendLine("OVERRIDE_UPDATED: <fully.qualified.ClassName>   ← one line per class whose override was updated")
        appendLine("BUILD_AFTER_CHANGE: <SUCCESS or FAILURE>")
        appendLine("TOOL_EVIDENCE: <copy the line starting with execution_id: ...>")
    }

    private fun baselinePrompt(): String = buildString {
        appendLine("The Keycloak project is checked out (a large multi-module Java project).")
        appendLine("IntelliJ MCP tools are unavailable in this run — use shell commands only.")
        appendLine()
        append(taskDescription())
        appendLine()
        appendLine("Beware: a naive text replace will over-match unrelated `authenticate` methods and miss")
        appendLine("non-obvious overrides, breaking the build.")
        appendLine("After the change, build the affected modules (e.g. server-spi-private + services) to verify.")
        appendLine()
        appendLine("Output (markers on their own lines):")
        appendLine("SIGNATURE_CHANGED: yes")
        appendLine("OVERRIDES_UPDATED: <total count of updated overriding methods>")
        appendLine("OVERRIDE_UPDATED: <fully.qualified.ClassName>   ← one line per class whose override was updated")
        appendLine("BUILD_AFTER_CHANGE: <SUCCESS or FAILURE>")
    }

    companion object {
        private const val SCENARIO = "keycloak__change_signature"
        private const val SYMBOL = "org.keycloak.authentication.Authenticator#authenticate(AuthenticationFlowContext)"

        // Ground truth derived from the pinned Keycloak 26.6.4 tag: every class in the production modules
        // (server-spi-private + services) that declares `void authenticate(AuthenticationFlowContext …)`
        // as an override. The interface itself is covered by SIGNATURE_CHANGED. The anonymous class inside
        // SpnegoAuthenticatorFactory (SINGLETON_DISABLED) is deliberately NOT required — agents report
        // anonymous classes under inconsistent names ($1, .SINGLETON_DISABLED, the factory class) and the
        // build-green check catches a genuine miss there anyway. Testsuite-module overrides are likewise
        // excluded so scoring does not depend on whether the testsuite is part of the Maven import.
        private val REQUIRED_OVERRIDES = setOf(
            "org.keycloak.authentication.authenticators.AttemptedAuthenticator",
            "org.keycloak.authentication.authenticators.access.AllowAccessAuthenticator",
            "org.keycloak.authentication.authenticators.access.DenyAccessAuthenticator",
            "org.keycloak.authentication.authenticators.broker.AbstractIdpAuthenticator",
            "org.keycloak.authentication.authenticators.browser.ConditionalOtpFormAuthenticator",
            "org.keycloak.authentication.authenticators.browser.CookieAuthenticator",
            "org.keycloak.authentication.authenticators.browser.IdentityProviderAuthenticator",
            "org.keycloak.authentication.authenticators.browser.OTPFormAuthenticator",
            "org.keycloak.authentication.authenticators.browser.PasskeysConditionalUIAuthenticator",
            "org.keycloak.authentication.authenticators.browser.PasswordForm",
            "org.keycloak.authentication.authenticators.browser.RecoveryAuthnCodesFormAuthenticator",
            "org.keycloak.authentication.authenticators.browser.ScriptBasedAuthenticator",
            "org.keycloak.authentication.authenticators.browser.SpnegoAuthenticator",
            "org.keycloak.authentication.authenticators.browser.UsernameForm",
            "org.keycloak.authentication.authenticators.browser.UsernamePasswordForm",
            "org.keycloak.authentication.authenticators.browser.WebAuthnAuthenticator",
            "org.keycloak.authentication.authenticators.conditional.ConditionalAuthenticator",
            "org.keycloak.authentication.authenticators.directgrant.ValidateOTP",
            "org.keycloak.authentication.authenticators.directgrant.ValidatePassword",
            "org.keycloak.authentication.authenticators.directgrant.ValidateUsername",
            "org.keycloak.authentication.authenticators.resetcred.ResetCredentialChooseUser",
            "org.keycloak.authentication.authenticators.resetcred.ResetCredentialEmail",
            "org.keycloak.authentication.authenticators.resetcred.ResetOTP",
            "org.keycloak.authentication.authenticators.resetcred.ResetPassword",
            "org.keycloak.authentication.authenticators.sessionlimits.UserSessionLimitsAuthenticator",
            "org.keycloak.authentication.authenticators.x509.ValidateX509CertificateUsername",
            "org.keycloak.authentication.authenticators.x509.X509ClientCertificateAuthenticator",
            "org.keycloak.organization.authentication.authenticators.browser.OrganizationAuthenticator",
        )
    }
}
