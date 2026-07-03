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
 * MCP-win experiment: **compile-error triage, time-to-green** (jonnyzzz/mcp-steroid#169 follow-up) —
 * the pure-efficiency story. A patch applied at IDE start (before the agent runs) seeds exactly
 * [SEEDED_SITES.size][SEEDED_SITES] deterministic single-line compile errors across TWO modules of the
 * pinned Keycloak 26.6.4 tree (server-spi-private + services). The agent's task: make the project
 * compile again, verified by the bounded build `mvn -q -DskipTests -pl server-spi-private,services -am
 * compile`.
 *
 * Every seeded error needs TYPE knowledge, not pattern matching (see [SEEDED_ERRORS_PATCH]):
 *  1. `JsonUtils:152` — `List<Integer>` local for `splitClaimPath(...)`, which returns `List<String>`
 *     and feeds `getJsonValue(node, List<String>)` (wrong generic parameter).
 *  2. `CredentialHelper:77` — return type narrowed to `AuthenticatorFactory`; the body's three casts
 *     (Authenticator/FormAction/ClientAuthenticator factories) only share the declared supertype
 *     `ConfigurableAuthenticatorFactory` (incompatible return type; a cast "fix" at the return
 *     statement compiles but is wrong — the scorer only accepts the declaration site).
 *  3. `DenyAccessAuthenticator:62` — `Boolean requiresUser()`; primitives have no covariant boxing, so
 *     it cannot implement `boolean requiresUser()` from the `Authenticator` SPI in the OTHER module.
 *  4. `ResetOTP:140` — `session.getProvider("keycloak-otp", CredentialProvider.class)`: the
 *     `KeycloakSession` overloads are `(Class)`, `(Class, String)`, `(Class, ComponentModel)` — the
 *     called overload does not exist; the arguments must be swapped back.
 *  5. `ConditionalUserAttributeValue:39` — `Map<String, Object>` for `getConfig()`, which returns
 *     `Map<String, String>` whose values feed `String` locals and `Boolean.parseBoolean` (wrong
 *     generic parameter).
 *
 * The asymmetry under test: with MCP the IDE's red-code analysis lists every error in ALL modules at
 * once, with resolved types on both sides of each mismatch. Without MCP the agent pays a multi-minute
 * Maven cycle per iteration — and Maven stops at the first failing module, so the services errors are
 * INVISIBLE until server-spi-private is fixed and rebuilt. Correctness should converge; the dashboard
 * metric that separates the legs is agent time and tool-call/token effort.
 *
 * Verdict ([scoreCompileTriage]): every seeded site fixed (evidence-based `FIXED: <file>:<line>`
 * markers matched against the seeded lines, spam-guarded) AND the bounded build reported SUCCESS —
 * emitted as an `[ARENA]` block. A/B per agent; with-MCP asserts exec_code; correctness is a dashboard
 * metric, not a hard gate.
 */
class KeycloakCompileTriageTest {

    // 80 min, following the KeycloakRenameTest precedent: the without-MCP baseline is build-heavy — it
    // legitimately needs several full `mvn -pl server-spi-private,services -am compile` cycles (each a
    // multi-minute reactor run over ~15 modules) because Maven reveals the seeded errors module by
    // module (rename's baseline measured 30.3 min agent time on CI build 991971402, and container
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
                consoleTitle = "keycloak-compiletriage-$agentName-$modeLabel",
                project = IntelliJProject.ProjectFromGitCommitAndPatch(
                    cloneUrl = "https://github.com/keycloak/keycloak.git",
                    repoOwnerAndName = "keycloak/keycloak",
                    // The commit of the pinned 26.6.4 release tag — the same revision KeycloakProject
                    // checks out. SEEDED_ERRORS_PATCH and SEEDED_SITES are derived at exactly this
                    // commit; when bumping it, re-verify the patch applies and re-derive the lines.
                    baseCommit = "dc1bfc54bf1462f7e79822adb4c59aba7e25d50f",
                    testPatch = SEEDED_ERRORS_PATCH,
                    displayName = "keycloak-compile-triage",
                ),
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

            val score = scoreCompileTriage(combined, SEEDED_SITES)
            println("[TEST] keycloak compile-triage [$agentName+$modeLabel] safe=${score.safe} " +
                    "matched=${score.matchedCount}/${SEEDED_SITES.values.sumOf { it.size }} " +
                    "reported=${score.reportedCount} buildGreen=${score.buildGreen}")
            if (score.missingSites.isNotEmpty()) {
                println("[TEST]   missed seeded sites: ${score.missingSites}")
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
                summary = "fixed=${score.matchedCount}/${SEEDED_SITES.values.sumOf { it.size }} " +
                        "reported=${score.reportedCount} buildGreen=${score.buildGreen}",
            )

            if (withMcp) assertUsedExecuteCodeEvidence(combined)
        } finally {
            lifetime.closeAllStacks()
        }
    }

    private fun taskDescription(): String = buildString {
        appendLine("Task: the working tree does not compile — an interrupted refactoring changed a handful of")
        appendLine("single lines across the `server-spi-private` and `services` modules. Make the project")
        appendLine("compile again.")
        appendLine()
        appendLine("Rules:")
        appendLine("- Find EVERY compile error and fix each one at its root cause with the minimal correct")
        appendLine("  change — restore the intended type / signature / arguments on the broken line.")
        appendLine("- Do NOT paper over an error with a cast or @SuppressWarnings, and do NOT \"fix\" callers or")
        appendLine("  interfaces that were already correct.")
        appendLine("- Verify with the bounded build from the project root:")
        appendLine("  `mvn -q -DskipTests -pl server-spi-private,services -am compile`")
    }

    private fun outputMarkers(): String = buildString {
        appendLine("Output (markers on their own lines):")
        appendLine("ERRORS_FOUND: <total count of distinct compile errors you found>")
        appendLine("FIXED: <path>:<line> — <what was wrong and the correct type>   ← one line per source line")
        appendLine("  you changed; <line> is the line number YOU EDITED in that file")
        appendLine("BUILD_AFTER_FIX: <SUCCESS or FAILURE>")
    }

    private fun withMcpPrompt(): String = buildString {
        appendLine("The Keycloak project is open in IntelliJ IDEA — a large multi-module Java project.")
        appendLine()
        append(taskDescription())
        appendLine()
        appendLine("Use IntelliJ's project-wide error analysis via `steroid_execute_code`: the IDE sees every")
        appendLine("compile error in ALL modules at once with both sides of each type mismatch resolved — e.g.")
        appendLine("compile the project with `com.intellij.openapi.compiler.CompilerManager` and collect the")
        appendLine("ERROR messages, or collect error-level daemon highlighting for the affected modules. Then")
        appendLine("fix the broken lines. A Maven run stops at the first failing MODULE, so it cannot show you")
        appendLine("the full error list in one pass — the IDE can.")
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
        appendLine("Beware: Maven stops at the first failing module, so a single build run does NOT reveal the")
        appendLine("errors in downstream modules — keep iterating until the bounded build is green.")
        appendLine()
        append(outputMarkers())
    }

    companion object {
        private const val SCENARIO = "keycloak__compile_triage"

        /**
         * Ground truth: the exact lines the seeded patch changed (file simple name → line). The agent
         * must fix each error AT this site; javac may point elsewhere (e.g. the CredentialHelper error
         * is reported at the `return factory;` statement on line 85, and the JsonUtils declaration
         * error cascades to the call on 157), but the one clearly-correct single-line repair is always
         * the seeded line itself.
         */
        private val SEEDED_SITES = mapOf(
            "JsonUtils.java" to setOf(152),                     // server-spi-private: wrong generic parameter
            "CredentialHelper.java" to setOf(77),               // server-spi-private: incompatible return type
            "DenyAccessAuthenticator.java" to setOf(62),        // services: boxed return vs SPI's primitive
            "ResetOTP.java" to setOf(140),                      // services: call to a non-existent overload
            "ConditionalUserAttributeValue.java" to setOf(39),  // services: wrong generic parameter
        )

        /**
         * The seeded breakage, applied by [IntelliJProject.ProjectFromGitCommitAndPatch] on top of the
         * 26.6.4 commit before the IDE starts. Five single-line, plausible-looking refactoring leftovers.
         * Verified against the real tree: `git apply --check` passes on dc1bfc54, the patched tree fails
         * `mvn -DskipTests -pl server-spi-private,services -am compile` with exactly the errors described
         * in the class KDoc, and the unpatched tree builds green with the same command.
         */
        private val SEEDED_ERRORS_PATCH = """
            |diff --git a/server-spi-private/src/main/java/org/keycloak/utils/CredentialHelper.java b/server-spi-private/src/main/java/org/keycloak/utils/CredentialHelper.java
            |index 65fa48d..027858f 100755
            |--- a/server-spi-private/src/main/java/org/keycloak/utils/CredentialHelper.java
            |+++ b/server-spi-private/src/main/java/org/keycloak/utils/CredentialHelper.java
            |@@ -74,7 +74,7 @@ public class CredentialHelper {
            |                 }));
            |     }
            |
            |-    public static ConfigurableAuthenticatorFactory getConfigurableAuthenticatorFactory(KeycloakSession session, String providerId) {
            |+    public static AuthenticatorFactory getConfigurableAuthenticatorFactory(KeycloakSession session, String providerId) {
            |         ConfigurableAuthenticatorFactory factory = (AuthenticatorFactory)session.getKeycloakSessionFactory().getProviderFactory(Authenticator.class, providerId);
            |         if (factory == null) {
            |             factory = (FormActionFactory)session.getKeycloakSessionFactory().getProviderFactory(FormAction.class, providerId);
            |diff --git a/server-spi-private/src/main/java/org/keycloak/utils/JsonUtils.java b/server-spi-private/src/main/java/org/keycloak/utils/JsonUtils.java
            |index 3a809bf..72e7b88 100644
            |--- a/server-spi-private/src/main/java/org/keycloak/utils/JsonUtils.java
            |+++ b/server-spi-private/src/main/java/org/keycloak/utils/JsonUtils.java
            |@@ -149,7 +149,7 @@ public class JsonUtils {
            |      */
            |     public static Object getJsonValue(JsonNode node, String claim) {
            |         if (node != null && claim != null) {
            |-            List<String> paths = splitClaimPath(claim);
            |+            List<Integer> paths = splitClaimPath(claim);
            |             if (paths.isEmpty() || claim.endsWith(".")) {
            |                 return null;
            |             }
            |diff --git a/services/src/main/java/org/keycloak/authentication/authenticators/access/DenyAccessAuthenticator.java b/services/src/main/java/org/keycloak/authentication/authenticators/access/DenyAccessAuthenticator.java
            |index 367add4..ce34f8b 100644
            |--- a/services/src/main/java/org/keycloak/authentication/authenticators/access/DenyAccessAuthenticator.java
            |+++ b/services/src/main/java/org/keycloak/authentication/authenticators/access/DenyAccessAuthenticator.java
            |@@ -59,7 +59,7 @@ public class DenyAccessAuthenticator implements Authenticator {
            |     }
            |
            |     @Override
            |-    public boolean requiresUser() {
            |+    public Boolean requiresUser() {
            |         return false;
            |     }
            |
            |diff --git a/services/src/main/java/org/keycloak/authentication/authenticators/conditional/ConditionalUserAttributeValue.java b/services/src/main/java/org/keycloak/authentication/authenticators/conditional/ConditionalUserAttributeValue.java
            |index a9467d4..937639f 100644
            |--- a/services/src/main/java/org/keycloak/authentication/authenticators/conditional/ConditionalUserAttributeValue.java
            |+++ b/services/src/main/java/org/keycloak/authentication/authenticators/conditional/ConditionalUserAttributeValue.java
            |@@ -36,7 +36,7 @@ public class ConditionalUserAttributeValue implements ConditionalAuthenticator {
            |     @Override
            |     public boolean matchCondition(AuthenticationFlowContext context) {
            |         // Retrieve configuration
            |-        Map<String, String> config = context.getAuthenticatorConfig().getConfig();
            |+        Map<String, Object> config = context.getAuthenticatorConfig().getConfig();
            |         String attributeName = config.get(ConditionalUserAttributeValueFactory.CONF_ATTRIBUTE_NAME);
            |         String attributeValue = config.get(ConditionalUserAttributeValueFactory.CONF_ATTRIBUTE_EXPECTED_VALUE);
            |         boolean includeGroupAttributes = Boolean.parseBoolean(config.get(ConditionalUserAttributeValueFactory.CONF_INCLUDE_GROUP_ATTRIBUTES));
            |diff --git a/services/src/main/java/org/keycloak/authentication/authenticators/resetcred/ResetOTP.java b/services/src/main/java/org/keycloak/authentication/authenticators/resetcred/ResetOTP.java
            |index 41d315f..b0a7439 100755
            |--- a/services/src/main/java/org/keycloak/authentication/authenticators/resetcred/ResetOTP.java
            |+++ b/services/src/main/java/org/keycloak/authentication/authenticators/resetcred/ResetOTP.java
            |@@ -137,7 +137,7 @@ public class ResetOTP extends AbstractSetRequiredActionAuthenticator implements
            |
            |     @Override
            |     public OTPCredentialProvider getCredentialProvider(KeycloakSession session) {
            |-        return (OTPCredentialProvider)session.getProvider(CredentialProvider.class, "keycloak-otp");
            |+        return (OTPCredentialProvider)session.getProvider("keycloak-otp", CredentialProvider.class);
            |     }
            |
            |     @Override
            |""".trimMargin()
    }
}
