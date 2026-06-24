package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Integration test: verifies that Claude uses JavaPsiFacade.getInstance(project).findClass()
 * (or KotlinClassShortNameIndex) to look up a class by name instead of grepping files.
 *
 * The test gives Claude a class-lookup task and checks whether it uses a proper PSI API
 * for class discovery or falls back to file reading / grep.
 */
class PsiClassLookupPromptTest {

    private val lifetime by lazy {
        CloseableStackHost()
    }

    @AfterEach
    fun tearDown() {
        lifetime.closeAllStacks()
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun `claude uses JavaPsiFacade or KotlinClassShortNameIndex to find class and list methods`() {
        val session = IntelliJContainer.create(lifetime, IntelliJContainerOpts(
            consoleTitle = "PSI class lookup prompt test — Claude",
        )).waitForProjectReady()

        val console = session.console
        val agent = session.aiAgents.claude

        console.writeStep(text = "Building prompt for PSI class lookup")

        val prompt = buildString {
            appendLine("# Task: Find a Kotlin data class and list its methods using PSI")
            appendLine()
            appendLine("Use `steroid_execute_code` to find the class named `Player` in the project.")
            appendLine("It is a Kotlin data class in `com.jonnyzzz.mcpSteroid.demo`.")
            appendLine("List all its public methods with their signatures.")
            appendLine()
            appendLine("Read `mcp-steroid://skill/coding-with-intellij-psi` for the correct PSI class lookup pattern.")
            appendLine("Use `JavaPsiFacade.getInstance(project).findClass()` or `KotlinClassShortNameIndex`")
            appendLine("— do NOT grep source files or use the Glob tool.")
            appendLine()
            appendLine("## Required Output")
            appendLine()
            appendLine("Print these markers on separate lines:")
            appendLine("CLASS_FOUND: yes")
            appendLine("LOOKUP_API: <name of the PSI API you used, e.g. JavaPsiFacade or KotlinClassShortNameIndex>")
            appendLine("METHODS: <comma-separated list of method names found>")
        }

        console.writeStep(text = "Running agent prompt")

        val result = agent.runPrompt(prompt, timeoutSeconds = 600).awaitForProcessFinish()
        val output = result.stdout
        val combined = result.stdout + "\n" + result.stderr

        console.writeStep(text = "Validating agent output")

        // If agent failed, check if required markers were still emitted
        val hasClassFoundMarker = hasAnyMarkerLine(output, "CLASS_FOUND", "Class found")
        if (result.exitCode != 0 && !hasClassFoundMarker) {
            console.writeError("Agent exited with code ${result.exitCode}")
            result.assertExitCode(0, message = "PSI class lookup prompt test")
        }
        console.writeInfo("Agent exited with code ${result.exitCode ?: "?"}")

        // Agent must have used steroid_execute_code
        console.writeInfo("Checking: steroid_execute_code usage evidence")
        assertUsedExecuteCodeEvidence(combined)
        console.writeSuccess("execute_code evidence found")

        // Key check: agent must have used JavaPsiFacade or KotlinClassShortNameIndex (or findClass)
        console.writeInfo("Checking: PSI class lookup API usage in exec_code")
        val usedJavaPsiFacade = combined.contains("JavaPsiFacade", ignoreCase = false)
        val usedKotlinIndex = combined.contains("KotlinClassShortNameIndex", ignoreCase = false)
        val usedFindClass = combined.contains("findClass", ignoreCase = false)
        val usedPsiLookup = usedJavaPsiFacade || usedKotlinIndex || usedFindClass

        check(usedPsiLookup) {
            buildString {
                appendLine("Agent must use JavaPsiFacade.findClass() or KotlinClassShortNameIndex inside steroid_execute_code.")
                appendLine()
                appendLine("Expected: 'JavaPsiFacade', 'KotlinClassShortNameIndex', or 'findClass' in exec_code body.")
                appendLine("Got: JavaPsiFacade used          = $usedJavaPsiFacade")
                appendLine("     KotlinClassShortNameIndex   = $usedKotlinIndex")
                appendLine("     findClass used              = $usedFindClass")
                appendLine()
                appendLine("The prompts in coding-with-intellij-psi.md need to explicitly steer agents")
                appendLine("toward PSI-based class lookup instead of file grepping.")
                appendLine()
                appendLine("Output:\n$combined")
            }
        }
        console.writeSuccess("PSI class lookup API usage confirmed")

        // Verify the LOOKUP_API marker was emitted
        console.writeInfo("Checking: LOOKUP_API marker")
        val lookupApi = findMarkerValue(output, "LOOKUP_API", "Lookup API")
        check(lookupApi != null) {
            "Agent must output LOOKUP_API marker.\nGot: null\nOutput:\n$combined"
        }
        console.writeSuccess("LOOKUP_API: $lookupApi")

        // Verify class was found
        console.writeInfo("Checking: CLASS_FOUND marker")
        val classFound = findMarkerValue(output, "CLASS_FOUND", "Class found")
        check(classFound != null && classFound.contains("yes", ignoreCase = true)) {
            "Agent must output CLASS_FOUND: yes.\nGot: $classFound\nOutput:\n$combined"
        }
        console.writeSuccess("CLASS_FOUND: $classFound")

        console.writeSuccess("Agent used PSI API for class lookup")
        console.writeHeader("PASSED")

        println("[TEST] Claude used PSI class lookup API ($lookupApi) to find Player")
    }
}
