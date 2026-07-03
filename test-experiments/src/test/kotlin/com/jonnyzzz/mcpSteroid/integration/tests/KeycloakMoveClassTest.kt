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
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * MCP-win experiment: **move a class between packages** (jonnyzzz/mcp-steroid#169) — the third of the
 * refactoring trilogy after [KeycloakRenameTest] and [KeycloakChangeSignatureTest]. The agent moves the
 * enum `org.keycloak.authentication.AuthenticationFlowError` (server-spi-private module) to a new
 * package `org.keycloak.authentication.errors` in the SAME module, updating every reference so the
 * project still compiles and ZERO occurrences of the old FQN remain anywhere in the tree.
 *
 * Verified against the pinned Keycloak 26.6.4 tag, the production reference sites are:
 *  - 32 `services`-module files that import the FQN;
 *  - 8 SAME-PACKAGE users with NO import line at all — `org.keycloak.authentication` is a SPLIT
 *    package (3 files in server-spi-private: AbstractAuthenticationFlowContext,
 *    AuthenticationFlowException, ForkFlowException; 5 in services: AuthenticationProcessor,
 *    DefaultAuthenticationFlow, FormAuthenticationFlow, ClientAuthenticationFlow,
 *    AuthenticationSelectionResolver). A grep for the old FQN NEVER finds these, yet each needs a NEW
 *    import added after the move or compilation breaks — the manual-sweep trap;
 *  - 1 production RESOURCE: `services/src/main/resources/scripts/authenticator-template.js` holds the
 *    FQN as a string (`Java.type("org.keycloak…AuthenticationFlowError")`) — a pure-source move leaves
 *    it dangling with the build still green (there is no META-INF/services registration for this enum;
 *    the JS `Java.type` string IS the resource-file trap here);
 *  - plus a javadoc code sample in ScriptBasedAuthenticator.java, 4 testsuite JS scripts and one docs
 *    .adoc — 46 old-FQN occurrences across 45 files tree-wide at the pinned tag.
 *
 * With MCP the agent drives IntelliJ's `MoveClassesOrPackagesProcessor` via `steroid_execute_code` —
 * PSI moves the file, rewrites imports/FQN references/javadoc, adds the missing imports to the
 * same-package users, and (with text-occurrence search) rewrites the non-Java FQN strings atomically.
 * Without MCP, a grep-driven sweep must discover the grep-invisible same-package usages by compiling.
 *
 * Verdict ([scoreMoveClass]) is EVIDENCE-based: after the agent finishes, the harness itself checks in
 * the container (identically for both legs) that the file exists at the new path and is gone from the
 * old one, and greps the whole tree for the old FQN — the OBJECTIVE residue count. Only the per-file
 * `UPDATED:` list (checked against the hand-derived ground truth above) and the build claim come from
 * the agent's output. Emitted as an `[ARENA]` block; A/B per agent; with-MCP asserts exec_code;
 * correctness is a dashboard metric, not a hard gate.
 */
class KeycloakMoveClassTest {

    // 80 min, following the KeycloakRenameTest precedent: the without-MCP baseline is edit/build-heavy —
    // it must update ~40 files, discover the 8 grep-invisible same-package usages via compile errors, and
    // rebuild (the rename baseline measured 30.3 min agent time on CI build 991971402, and container
    // start + Keycloak import + verification pushed the method past 50). The slow baseline IS the
    // experiment's finding — it must complete and emit its [ARENA] block so the dashboard can show the
    // gap, not die as a timeout with no data. TC-side executionTimeoutMin=180 fits two 80-min methods.

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
                consoleTitle = "keycloak-moveclass-$agentName-$modeLabel",
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

            // Objective, harness-run verification — identical for both legs. A failure here is logged
            // loudly but does NOT abort the run: the [ARENA] block must still reach the dashboard.
            val verification = runCatching { verifyMoveInContainer(session) }.getOrElse { e ->
                println("[TEST] WARNING: in-container move verification failed: $e")
                MoveVerification(classAtNewPath = null, classAtOldPath = null, oldFqnResidueCount = null)
            }

            val score = scoreMoveClass(
                output = combined,
                requiredUpdatedFiles = REQUIRED_UPDATED_FILES,
                classAtNewPath = verification.classAtNewPath,
                classAtOldPath = verification.classAtOldPath,
                oldFqnResidueCount = verification.oldFqnResidueCount,
            )
            println("[TEST] keycloak move-class [$agentName+$modeLabel] safe=${score.safe} " +
                    "moveDone=${score.moveDone} missing=${score.missingUpdatedFiles.size} " +
                    "residue=${score.oldFqnResidueCount} buildGreen=${score.buildGreen}")
            if (score.missingUpdatedFiles.isNotEmpty()) {
                println("[TEST]   missed reference sites: ${score.missingUpdatedFiles}")
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
                summary = "moveDone=${score.moveDone} updated=${score.reportedUpdatedFiles.size} " +
                        "missing=${score.missingUpdatedFiles.size} residue=${score.oldFqnResidueCount} " +
                        "buildGreen=${score.buildGreen}",
            )

            if (withMcp) assertUsedExecuteCodeEvidence(combined)
        } finally {
            lifetime.closeAllStacks()
        }
    }

    private data class MoveVerification(
        val classAtNewPath: Boolean?,
        val classAtOldPath: Boolean?,
        val oldFqnResidueCount: Int?,
    )

    /**
     * Run the objective checks inside the container: file existence at the old/new path and a tree-wide
     * grep counting remaining old-FQN occurrences (.git/.idea/target/node_modules excluded). The old FQN
     * cannot false-positive on the new one — `authentication.errors.AuthenticationFlowError` does not
     * contain the `authentication.AuthenticationFlowError` substring.
     */
    private fun verifyMoveInContainer(session: IntelliJContainer): MoveVerification {
        val projectDir = session.intellijDriver.getGuestProjectDir()
        val script = """
            p='$projectDir'
            [ -f "${'$'}p/$OLD_PATH" ] && echo "MOVECHECK_OLD_EXISTS: yes" || echo "MOVECHECK_OLD_EXISTS: no"
            [ -f "${'$'}p/$NEW_PATH" ] && echo "MOVECHECK_NEW_EXISTS: yes" || echo "MOVECHECK_NEW_EXISTS: no"
            echo "MOVECHECK_RESIDUE: ${'$'}(grep -ro --binary-files=without-match \
                --exclude-dir=.git --exclude-dir=.idea --exclude-dir=target --exclude-dir=node_modules \
                'org\.keycloak\.authentication\.AuthenticationFlowError' "${'$'}p" | wc -l)"
        """.trimIndent()
        val stdout = session.scope.startProcessInContainer {
            args("bash", "-c", script)
                .timeoutSeconds(600)
                .description("Verify move-class result: file locations + old-FQN residue grep")
                .quietly()
        }.awaitForProcessFinish().stdout

        fun flag(marker: String): Boolean? =
            Regex("""(?m)^$marker:\s*(yes|no)""").find(stdout)?.groupValues?.get(1)?.let { it == "yes" }
        val residue = Regex("""(?m)^MOVECHECK_RESIDUE:\s*(\d+)""").find(stdout)?.groupValues?.get(1)?.toIntOrNull()
        return MoveVerification(
            classAtNewPath = flag("MOVECHECK_NEW_EXISTS"),
            classAtOldPath = flag("MOVECHECK_OLD_EXISTS"),
            oldFqnResidueCount = residue,
        )
    }

    private fun taskDescription(): String = buildString {
        appendLine("Task: move the enum class `$OLD_FQN`")
        appendLine("(declared in the server-spi-private module) to a new package")
        appendLine("`org.keycloak.authentication.errors` in the SAME module — the new fully-qualified name is")
        appendLine("`$NEW_FQN`.")
        appendLine()
        appendLine("The move must ripple PROJECT-WIDE so everything still compiles:")
        appendLine("- the class file itself moves to the new package directory and its `package` declaration changes,")
        appendLine("- every `import` of the old FQN is rewritten,")
        appendLine("- files that used the class from the SAME package without any import now need a NEW import")
        appendLine("  (note: `org.keycloak.authentication` is a split package — it exists in BOTH the")
        appendLine("  server-spi-private and services modules),")
        appendLine("- FQN strings in non-Java files (resources, script templates, docs) must be updated too.")
        appendLine()
        appendLine("We will verify OBJECTIVELY after you finish: the file must exist at")
        appendLine("`$NEW_PATH`,")
        appendLine("must be gone from `$OLD_PATH`, and a")
        appendLine("project-wide search for the old fully-qualified name must return ZERO matches —")
        appendLine("sources, resources, scripts and docs all count.")
    }

    private fun outputMarkers(): String = buildString {
        appendLine("Output (markers on their own lines):")
        appendLine("MOVE_DONE: yes")
        appendLine("UPDATED_COUNT: <total number of changed files>")
        appendLine("UPDATED: <path/relative/to/repo/root>   ← one line per changed file (derive the list from")
        appendLine("  `git status --porcelain` so nothing is forgotten; include the moved file and every reference site)")
        appendLine("BUILD_AFTER_MOVE: <SUCCESS or FAILURE>")
    }

    private fun withMcpPrompt(): String = buildString {
        appendLine("The Keycloak project is open in IntelliJ IDEA — a large multi-module Java project.")
        appendLine()
        append(taskDescription())
        appendLine()
        appendLine("Use IntelliJ's Move refactoring via `steroid_execute_code`:")
        appendLine("`com.intellij.refactoring.move.moveClassesOrPackages.MoveClassesOrPackagesProcessor` with a")
        appendLine("destination package `org.keycloak.authentication.errors` (create it in the server-spi-private")
        appendLine("source root) and BOTH `searchInComments` and `searchInNonJavaFiles` set to true — PSI moves the")
        appendLine("file, rewrites every import/FQN/javadoc reference, adds missing imports to same-package users,")
        appendLine("and updates non-Java text occurrences atomically. Do NOT sed.")
        appendLine("After the move, build the affected modules (e.g. server-spi-private + services) to verify.")
        appendLine()
        append(outputMarkers())
        appendLine("TOOL_EVIDENCE: <copy the line starting with execution_id: ...>")
    }

    private fun baselinePrompt(): String = buildString {
        appendLine("The Keycloak project is checked out (a large multi-module Java project).")
        appendLine("IntelliJ MCP tools are unavailable in this run — use shell commands only.")
        appendLine()
        append(taskDescription())
        appendLine()
        appendLine("Beware: a grep for the old fully-qualified name will NOT find the same-package usages —")
        appendLine("they have no import line to rewrite, yet they break compilation after the move. And FQN")
        appendLine("strings hiding in non-Java files do not break the build, but they count in the verification.")
        appendLine("After the move, build the affected modules (e.g. server-spi-private + services) to verify.")
        appendLine()
        append(outputMarkers())
    }

    companion object {
        private const val SCENARIO = "keycloak__move_class"
        private const val OLD_FQN = "org.keycloak.authentication.AuthenticationFlowError"
        private const val NEW_FQN = "org.keycloak.authentication.errors.AuthenticationFlowError"
        private const val OLD_PATH =
            "server-spi-private/src/main/java/org/keycloak/authentication/AuthenticationFlowError.java"
        private const val NEW_PATH =
            "server-spi-private/src/main/java/org/keycloak/authentication/errors/AuthenticationFlowError.java"

        // Ground truth derived from the pinned Keycloak 26.6.4 tag: every PRODUCTION reference site
        // (server-spi-private + services src/main) of the moved enum — 32 import-based users, 8
        // same-package users with NO import (the grep-invisible ones), and the production JS resource
        // holding the FQN as a `Java.type(...)` string. Testsuite scripts and docs are deliberately NOT
        // required here (per the KeycloakChangeSignatureTest precedent — the testsuite may not be part
        // of the Maven import); they are still covered by the objective tree-wide residue grep.
        private val REQUIRED_UPDATED_FILES = setOf(
            // server-spi-private — same-package users, no import line (grep-invisible):
            "server-spi-private/src/main/java/org/keycloak/authentication/AbstractAuthenticationFlowContext.java",
            "server-spi-private/src/main/java/org/keycloak/authentication/AuthenticationFlowException.java",
            "server-spi-private/src/main/java/org/keycloak/authentication/ForkFlowException.java",
            // services — same-package users, no import line (grep-invisible; split package):
            "services/src/main/java/org/keycloak/authentication/AuthenticationProcessor.java",
            "services/src/main/java/org/keycloak/authentication/AuthenticationSelectionResolver.java",
            "services/src/main/java/org/keycloak/authentication/ClientAuthenticationFlow.java",
            "services/src/main/java/org/keycloak/authentication/DefaultAuthenticationFlow.java",
            "services/src/main/java/org/keycloak/authentication/FormAuthenticationFlow.java",
            // services — import-based reference sites:
            "services/src/main/java/org/keycloak/authentication/authenticators/access/DenyAccessAuthenticator.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/broker/AbstractIdpAuthenticator.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/broker/IdpConfirmLinkAuthenticator.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/broker/IdpConfirmOverrideLinkAuthenticator.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/broker/IdpEmailVerificationAuthenticator.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/broker/IdpUsernamePasswordForm.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/browser/AbstractUsernameFormAuthenticator.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/browser/OTPFormAuthenticator.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/browser/RecoveryAuthnCodesFormAuthenticator.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/browser/ScriptBasedAuthenticator.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/browser/SpnegoAuthenticator.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/browser/WebAuthnAuthenticator.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/client/AbstractJWTClientValidator.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/client/ClientIdAndSecretAuthenticator.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/client/FederatedJWTClientAuthenticator.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/client/JWTClientAuthenticator.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/client/JWTClientSecretAuthenticator.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/client/X509ClientAuthenticator.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/conditional/ConditionalLoaAuthenticator.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/conditional/ConditionalUserAttributeValue.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/directgrant/ValidateOTP.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/directgrant/ValidatePassword.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/directgrant/ValidateUsername.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/resetcred/ResetCredentialChooseUser.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/resetcred/ResetCredentialEmail.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/sessionlimits/UserSessionLimitsAuthenticator.java",
            "services/src/main/java/org/keycloak/authentication/authenticators/x509/ValidateX509CertificateUsername.java",
            "services/src/main/java/org/keycloak/authentication/forms/RegistrationUserCreation.java",
            "services/src/main/java/org/keycloak/organization/authentication/authenticators/broker/IdpAddOrganizationMemberAuthenticator.java",
            "services/src/main/java/org/keycloak/organization/authentication/authenticators/browser/OrganizationAuthenticator.java",
            "services/src/main/java/org/keycloak/protocol/docker/DockerAuthenticator.java",
            "services/src/main/java/org/keycloak/protocol/saml/profile/ecp/authenticator/HttpBasicAuthenticator.java",
            // services — production resource with the FQN as a Java.type("...") string:
            "services/src/main/resources/scripts/authenticator-template.js",
        )
    }
}
