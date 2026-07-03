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
 * MCP-win experiment: **safe delete** (jonnyzzz/mcp-steroid#169) — the third refactoring sibling after
 * [KeycloakRenameTest] and [KeycloakChangeSignatureTest]. The agent removes the deprecated
 * `org.keycloak.jose.jws.JWSBuilder.EncodingBuilder#rsa256(PrivateKey)` wrapper AND migrates every
 * production usage to the wrapper's own one-line body — `sign(Algorithm.RS256, <same argument>)` —
 * so the project still compiles.
 *
 * Hand-verified against the pinned Keycloak 26.6.4 tag, the wrapper has exactly THREE production
 * usages scattered across three Maven modules (the reason a local grep-in-one-module sweep misses):
 *  - `core/…/org/keycloak/KeyPairVerifier.java:50`
 *  - `integration/client-cli/admin-cli/…/org/keycloak/client/cli/util/AuthUtil.java:213`
 *  - `services/…/org/keycloak/protocol/docker/DockerAuthV2Protocol.java:132`
 * None of the three files imports `org.keycloak.jose.jws.Algorithm` yet — the classic sed trap: the
 * call is rewritten but the import is forgotten and the module stops compiling. A further ~15 usages
 * live in unit tests / testsuite (out of scope: the verdict's build check covers production sources
 * only, and test-file edits are not counted as collateral). The sibling `rsa384`/`rsa512` wrappers
 * must NOT be touched.
 *
 * With MCP the agent drives IntelliJ's `SafeDeleteProcessor` (or `ReferencesSearch` + guided edits)
 * via `steroid_execute_code` — the IDE surfaces every BLOCKING USAGE before deleting, so the agent
 * migrates exactly the right sites and the delete lands clean. Without MCP, sed deletes blind and
 * discovers the cross-module usage — or the missing import — only at compile time.
 *
 * Verdict ([scoreSafeDelete]): declaration gone AND all 3 ground-truth usages migrated AND the
 * post-delete build reported SUCCESS AND no production files outside the known 4-file set changed —
 * emitted as an `[ARENA]` block. A/B per agent; with-MCP asserts exec_code; correctness is a
 * dashboard metric, not a hard gate.
 */
class KeycloakSafeDeleteTest {

    // 80 min, following the KeycloakRenameTest precedent: the without-MCP baseline is edit/build-heavy —
    // it must hunt usages across modules, migrate them, and rebuild several Maven modules to verify
    // (rename measured 30.3 min agent time on CI build 991971402, and container start + Keycloak import
    // + verification pushed the method past 50). The slow baseline IS the experiment's finding — it must
    // complete and emit its [ARENA] block so the dashboard can show the gap, not die as a timeout with
    // no data. TC-side executionTimeoutMin=180 fits two 80-min methods.

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
                consoleTitle = "keycloak-safedelete-$agentName-$modeLabel",
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

            val score = scoreSafeDelete(combined, REQUIRED_USAGE_FILES, ALLOWED_CHANGED_FILES)
            println("[TEST] keycloak safe-delete [$agentName+$modeLabel] safe=${score.safe} " +
                    "methodDeleted=${score.methodDeleted} missing=${score.missingMigrations.size} " +
                    "collateral=${score.collateralFiles.size} buildGreen=${score.buildGreen}")
            if (score.missingMigrations.isNotEmpty()) {
                println("[TEST]   missed usages: ${score.missingMigrations}")
            }
            if (score.collateralFiles.isNotEmpty()) {
                println("[TEST]   collateral edits: ${score.collateralFiles}")
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
                summary = "methodDeleted=${score.methodDeleted} migrated=${score.reportedMigrated.size} " +
                        "missing=${score.missingMigrations.size} collateral=${score.collateralFiles.size} " +
                        "buildGreen=${score.buildGreen}",
            )

            if (withMcp) assertUsedExecuteCodeEvidence(combined)
        } finally {
            lifetime.closeAllStacks()
        }
    }

    private fun taskDescription(): String = buildString {
        appendLine("Task: SAFE-DELETE the deprecated method `$SYMBOL`")
        appendLine("(the one-line wrapper whose body is `return sign(Algorithm.RS256, privateKey);`).")
        appendLine()
        appendLine("Remove the method declaration AND migrate EVERY production usage so the project still")
        appendLine("compiles: replace each `.rsa256(<arg>)` call with `.sign(Algorithm.RS256, <arg>)` — the")
        appendLine("wrapper's own body — adding the `org.keycloak.jose.jws.Algorithm` import where needed.")
        appendLine()
        appendLine("Scope rules:")
        appendLine("- Do NOT touch the sibling `rsa384`/`rsa512` wrappers or any other method.")
        appendLine("- Do NOT modify any other production file — only the declaration and the usage sites.")
        appendLine("- Files under src/test or testsuite are OUT OF SCOPE (verification compiles production")
        appendLine("  sources only, e.g. `mvn compile` — do not run test compilation).")
    }

    private fun outputMarkers(): String = buildString {
        appendLine("Output (markers on their own lines):")
        appendLine("METHOD_DELETED: yes")
        appendLine("MIGRATED: <path/relative/to/repo/File.java>:<line>   ← one line per migrated production usage")
        appendLine("CHANGED_FILE: <path>   ← one line per file from `git diff --name-only` at the end")
        appendLine("BUILD_AFTER_DELETE: <SUCCESS or FAILURE>")
    }

    private fun withMcpPrompt(): String = buildString {
        appendLine("The Keycloak project is open in IntelliJ IDEA — a large multi-module Java project.")
        appendLine()
        append(taskDescription())
        appendLine()
        appendLine("Use IntelliJ's Safe Delete refactoring via `steroid_execute_code`:")
        appendLine("`com.intellij.refactoring.safeDelete.SafeDeleteProcessor` surfaces every BLOCKING USAGE of")
        appendLine("the method before deleting (alternatively enumerate them with `ReferencesSearch` first).")
        appendLine("Migrate each surfaced production usage by PSI-guided edits, then delete the declaration.")
        appendLine("Do NOT sed. After the change, build the production sources of the affected modules")
        appendLine("(core, services, integration/client-cli/admin-cli) to verify.")
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
        appendLine("Beware: usages are scattered across multiple Maven modules, a text search also hits")
        appendLine("out-of-scope test files, and the replacement needs an import the file may not have —")
        appendLine("deleting blind breaks the build.")
        appendLine("After the change, build the production sources of the affected modules")
        appendLine("(core, services, integration/client-cli/admin-cli) to verify.")
        appendLine()
        append(outputMarkers())
    }

    companion object {
        private const val SCENARIO = "keycloak__safe_delete"
        private const val SYMBOL = "org.keycloak.jose.jws.JWSBuilder.EncodingBuilder#rsa256(PrivateKey)"

        // Ground truth hand-derived from the pinned Keycloak 26.6.4 tag: every PRODUCTION file with a
        // usage of EncodingBuilder#rsa256. Verified by hand (grep + receiver-type check): exactly one
        // call site per file, three Maven modules. Test usages (core RSAVerifierTest ×10,
        // SkeletonKeyTokenTest ×3, crypto/default CryptoPerfTest ×1, testsuite
        // OIDCJwksClientRegistrationTest ×1) are deliberately excluded — see scoreSafeDelete docs.
        private val REQUIRED_USAGE_FILES = setOf(
            "core/src/main/java/org/keycloak/KeyPairVerifier.java",
            "integration/client-cli/admin-cli/src/main/java/org/keycloak/client/cli/util/AuthUtil.java",
            "services/src/main/java/org/keycloak/protocol/docker/DockerAuthV2Protocol.java",
        )

        // The only production files the task may change: the declaration + the three usage sites.
        private val ALLOWED_CHANGED_FILES = REQUIRED_USAGE_FILES +
                "core/src/main/java/org/keycloak/jose/jws/JWSBuilder.java"
    }
}
