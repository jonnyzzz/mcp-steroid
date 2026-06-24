/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.generateJdkTableXml
import com.jonnyzzz.mcpSteroid.integration.infra.mcpRegisterJdks
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.runWithCloseableStack
import org.jdom2.Element
import org.jdom2.input.SAXBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * Infrastructure test that validates JDK registration in the Docker container.
 *
 * Uses [McpSteroidDriver.mcpRegisterJdks] to register Temurin JDKs via IntelliJ API
 * (`SdkConfigurationUtil.createAndAddSDK`), then validates:
 * 1. JDKs are visible in [ProjectJdkTable]
 * 2. Each registered JDK has a valid homePath with `bin/java`
 * 3. A JDK can be applied as the project SDK
 * 4. IntelliJ compilation works after SDK is set
 *
 * No AI agents — pure infrastructure validation via MCP Steroid.
 *
 * ```
 * ./gradlew :test-integration:test --tests '*JdkTableIntegrationTest*'
 * ```
 */
class JdkTableIntegrationTest {

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `JDK table has registered SDKs with valid paths`() = runWithCloseableStack { lifetime ->
        val session = IntelliJContainer.create(lifetime,IntelliJContainerOpts( consoleTitle = "jdk-table-test"))
        val console = session.console

        // Step 1: Register JDKs via IntelliJ API
        console.writeStep(text = "Registering JDKs via mcpRegisterJdks")
        session.mcpSteroid.mcpRegisterJdks()
        console.writeSuccess("JDK registration call completed")

        // Step 2: Verify JDKs are visible in ProjectJdkTable
        console.writeStep(text = "Verifying ProjectJdkTable has Java SDKs")
        session.mcpSteroid.mcpExecuteCode(
            code = $$"""
                import com.intellij.openapi.projectRoots.JavaSdk
                import com.intellij.openapi.projectRoots.ProjectJdkTable

                val javaSdks = ProjectJdkTable.getInstance().getSdksOfType(JavaSdk.getInstance())
                println("JAVA_SDK_COUNT: ${javaSdks.size}")
                javaSdks.forEach { sdk ->
                    val home = sdk.homePath ?: "null"
                    val hasJava = java.io.File(home, "bin/java").exists()
                    println("JDK: name=${sdk.name} home=$home valid=$hasJava")
                }
                require(javaSdks.isNotEmpty()) { "No Java SDKs in ProjectJdkTable after mcpRegisterJdks" }
                println("JDK_TABLE_OK")
            """.trimIndent(),
            taskId = "jdk-table-test",
            reason = "Validate JDK table has registered Java SDKs",
        ).assertExitCode(0, "JDK table query should succeed")
            .assertOutputContains("JDK_TABLE_OK", message = "should have registered Java SDKs")
        console.writeSuccess("JDK table has registered Java SDKs")

        // Step 3: Verify each JDK has valid home path with bin/java
        console.writeStep(text = "Validating JDK home paths")
        session.mcpSteroid.mcpExecuteCode(
            code = $$"""
                import com.intellij.openapi.projectRoots.JavaSdk
                import com.intellij.openapi.projectRoots.ProjectJdkTable

                val javaSdks = ProjectJdkTable.getInstance().getSdksOfType(JavaSdk.getInstance())
                var allValid = true
                for (sdk in javaSdks) {
                    val home = sdk.homePath
                    if (home == null) {
                        println("INVALID: ${sdk.name} — homePath is null")
                        allValid = false
                        continue
                    }
                    val javaFile = java.io.File(home, "bin/java")
                    if (!javaFile.exists()) {
                        println("INVALID: ${sdk.name} — $home/bin/java does not exist")
                        allValid = false
                    } else {
                        println("VALID: ${sdk.name} — $home")
                    }
                }
                require(allValid) { "Some JDKs have invalid home paths" }
                println("ALL_PATHS_VALID")
            """.trimIndent(),
            taskId = "jdk-table-test",
            reason = "Validate all JDK home paths have bin/java",
        ).assertExitCode(0, "JDK path validation should succeed")
            .assertOutputContains("ALL_PATHS_VALID", message = "all JDK paths should be valid")
        console.writeSuccess("All JDK paths are valid")

        // Step 4: Apply a JDK as project SDK
        console.writeStep(text = "Setting project SDK")
        session.mcpSteroid.mcpExecuteCode(
            code = $$"""
                import com.intellij.openapi.projectRoots.JavaSdk
                import com.intellij.openapi.projectRoots.ProjectJdkTable
                import com.intellij.openapi.projectRoots.ex.JavaSdkUtil
                import com.intellij.openapi.roots.ProjectRootManager
                import com.intellij.openapi.application.edtWriteAction

                val javaSdks = ProjectJdkTable.getInstance().getSdksOfType(JavaSdk.getInstance())
                val sdk = javaSdks.firstOrNull { it.name == "21" }
                    ?: javaSdks.firstOrNull { it.name.contains("21") }
                    ?: javaSdks.first()

                edtWriteAction { JavaSdkUtil.applyJdkToProject(project, sdk) }

                val appliedSdk = ProjectRootManager.getInstance(project).projectSdk
                println("APPLIED_SDK: name=${appliedSdk?.name} home=${appliedSdk?.homePath}")
                require(appliedSdk != null) { "Project SDK should be set" }
                println("PROJECT_SDK_OK")
            """.trimIndent(),
            taskId = "jdk-table-test",
            reason = "Apply JDK 21 as project SDK",
        ).assertExitCode(0, "Applying project SDK should succeed")
            .assertOutputContains("PROJECT_SDK_OK", message = "project SDK should be set")
        console.writeSuccess("Project SDK set successfully")

        // Step 5: Verify compilation works
        console.writeStep(text = "Triggering compilation to verify SDK works")
        session.mcpSteroid.mcpExecuteCode(
            code = $$"""
                val result = com.intellij.task.ProjectTaskManager.getInstance(project)
                    .buildAllModules().blockingGet(60_000)
                val hasErrors = result?.hasErrors() ?: false
                val isAborted = result?.isAborted ?: false
                println("BUILD_RESULT: errors=$hasErrors aborted=$isAborted")
                require(!hasErrors) { "Compilation should not have errors with a valid SDK" }
                println("COMPILATION_OK")
            """.trimIndent(),
            taskId = "jdk-table-test",
            reason = "Verify IntelliJ compilation works with project SDK",
            timeout = 120,
        ).assertExitCode(0, "Compilation should succeed")
            .assertOutputContains("COMPILATION_OK", message = "compilation should pass")

        console.writeSuccess("All JDK table checks passed")
    }

    /**
     * Fidelity: the jdk.table.xml our infra generator produces must match what a live IntelliJ
     * produces via its own API (`mcpRegisterJdks` → `JavaSdk.createJdk` + `setupSdkPaths`) on the
     * same host. We start with `preloadJdkTable=false` so IntelliJ builds the table itself, dump
     * IntelliJ's serialization via `ProjectJdkImpl.writeExternal`, and compare it (per-SDK:
     * homePath, version sans arch suffix, classPath roots, sourcePath roots) to our generator.
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `generated jdk_table_xml matches IntelliJ own serialization`() = runWithCloseableStack { lifetime ->
        val session = IntelliJContainer.create(
            lifetime,
            IntelliJContainerOpts(consoleTitle = "jdk-fidelity", preloadJdkTable = false),
        )
        val console = session.console

        console.writeStep(text = "Generating jdk.table.xml from infra (our generator)")
        val expectedXml = generateJdkTableXml(session.scope)

        console.writeStep(text = "Dumping IntelliJ's own ProjectJdkTable serialization")
        session.mcpSteroid.mcpExecuteCode(
            code = $$"""
                import com.intellij.openapi.projectRoots.JavaSdk
                import com.intellij.openapi.projectRoots.ProjectJdkTable
                import com.intellij.openapi.projectRoots.impl.ProjectJdkImpl
                import com.intellij.openapi.application.ApplicationManager
                import com.intellij.openapi.components.PathMacroManager
                import com.intellij.openapi.util.JDOMUtil
                import org.jdom.Element

                val sdks = ProjectJdkTable.getInstance().getSdksOfType(JavaSdk.getInstance())
                val app = Element("application")
                val comp = Element("component").setAttribute("name", "ProjectJdkTable")
                app.addContent(comp)
                for (sdk in sdks) {
                    val e = Element("jdk").setAttribute("version", "2")
                    (sdk as ProjectJdkImpl).writeExternal(e)
                    comp.addContent(e)
                }
                // Collapse IDE-home paths to ${'$'}APPLICATION_HOME_DIR${'$'} — the component store does this
                // when persisting jdk.table.xml, and our generator emits the macro form too.
                PathMacroManager.getInstance(ApplicationManager.getApplication()).collapsePathsRecursively(app)
                java.io.File("/mcp-run-dir/jdk-table-actual.xml").writeText(JDOMUtil.write(app))
                println("DUMPED_SDKS=${sdks.size}")
            """.trimIndent(),
            taskId = "jdk-fidelity",
            reason = "Serialize IntelliJ's ProjectJdkTable to compare against our generator output",
        ).assertExitCode(0, "Dumping IntelliJ JDK table should succeed")
            .assertOutputContains("DUMPED_SDKS=", message = "should dump SDK serialization")

        val actualXml = File(session.runDirInContainer, "jdk-table-actual.xml").readText()

        // Persist both for offline inspection on failure.
        File(session.runDirInContainer, "jdk-table-generated.xml").writeText(expectedXml)

        val expected = parseJdkTableModel(expectedXml)
        val actual = parseJdkTableModel(actualXml)

        console.writeInfo("generator SDKs=${expected.keys.sorted()}")
        console.writeInfo("IntelliJ  SDKs=${actual.keys.sorted()}")

        assertEquals(expected.keys.sorted(), actual.keys.sorted(),
            "Generated and IntelliJ SDK name sets differ")

        for (name in expected.keys.sorted()) {
            val e = expected.getValue(name)
            val a = actual.getValue(name)
            assertEquals(e.homePath, a.homePath, "homePath differs for '$name'")
            assertEquals(e.version, a.version,
                "version number differs for '$name' (normalized from IntelliJ's nondeterministic display string)")
            assertEquals(e.classRoots, a.classRoots,
                "classPath roots differ for '$name' (sorted)")
            assertEquals(e.sourceRoots, a.sourceRoots,
                "sourcePath roots differ for '$name' (as set)")
            assertEquals(e.annotationRoots, a.annotationRoots,
                "annotationsPath roots differ for '$name'")
        }
        console.writeSuccess("Generator output matches IntelliJ's own serialization for ${expected.size} SDK entries")
    }

    /**
     * Validates the fix: with `preloadJdkTable=true` (default) and NO post-open registration,
     * IntelliJ loads every JDK from the pre-written `options/jdk.table.xml` — proving the file is
     * well-formed (the old XML route silently emptied the table on a single malformed attribute)
     * and that all generated aliases are present with their class roots.
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    fun `IntelliJ loads all JDKs from pre-written jdk_table_xml`() = runWithCloseableStack { lifetime ->
        val session = IntelliJContainer.create(
            lifetime,
            IntelliJContainerOpts(consoleTitle = "jdk-preload"),
        )
        val console = session.console

        val expectedNames = parseJdkTableModel(generateJdkTableXml(session.scope)).keys.sorted()
        console.writeStep(text = "Expecting pre-written JDK aliases: $expectedNames")

        val result = session.mcpSteroid.mcpExecuteCode(
            code = $$"""
                import com.intellij.openapi.projectRoots.JavaSdk
                import com.intellij.openapi.projectRoots.ProjectJdkTable

                val sdks = ProjectJdkTable.getInstance().getSdksOfType(JavaSdk.getInstance())
                sdks.sortedBy { it.name }.forEach { sdk ->
                    val classRoots = sdk.rootProvider.getFiles(com.intellij.openapi.roots.OrderRootType.CLASSES).size
                    println("SDK\t${sdk.name}\t${sdk.homePath}\tclassRoots=$classRoots")
                }
                println("NAMES=" + sdks.map { it.name }.sorted().joinToString(","))
                println("PRELOAD_TABLE_OK")
            """.trimIndent(),
            taskId = "jdk-preload",
            reason = "Verify IntelliJ loaded all JDKs from the pre-written jdk.table.xml",
        ).assertExitCode(0, "querying loaded JDK table should succeed")
            .assertOutputContains("PRELOAD_TABLE_OK")

        val namesLine = result.stdout.lineSequence().firstOrNull { it.startsWith("NAMES=") }
            ?: error("No NAMES= line in output")
        val loadedNames = namesLine.removePrefix("NAMES=").split(",").filter { it.isNotEmpty() }.sorted()

        assertEquals(expectedNames, loadedNames,
            "IntelliJ-loaded JDK names must equal the pre-written generated set")
        console.writeSuccess("IntelliJ loaded all ${loadedNames.size} pre-written JDKs")
    }
}

/** One SDK's roots/version, normalized for cross-check (arch suffix stripped from version). */
private data class JdkModel(
    val homePath: String,
    val version: String,
    val classRoots: List<String>,
    val sourceRoots: Set<String>,
    val annotationRoots: List<String>,
)

/**
 * Reduce any IntelliJ/our JDK version display string to its bare version number for comparison.
 * Handles "Eclipse Temurin 21.0.11", "Eclipse Temurin 21.0.11 - aarch64", `java version "21.0.11"`,
 * and JDK-8 forms like "Eclipse Temurin 1.8.0_492". Returns the first `N(.N)*(_N)?` token, or the raw
 * string if none is present.
 */
private fun normalizeJdkVersion(raw: String): String =
    Regex("""\d+(?:\.\d+)*(?:_\d+)?""").find(raw)?.value ?: raw

/** Parse a jdk.table.xml string into a name -> [JdkModel] map for semantic comparison. */
private fun parseJdkTableModel(xml: String): Map<String, JdkModel> {
    val doc = SAXBuilder().build(StringReader(xml))
    val component = doc.rootElement.getChild("component")
        ?: error("No <component> in jdk.table.xml")
    return component.getChildren("jdk").associate { jdk ->
        val name = jdk.attrValue("name")
        val roots = jdk.getChild("roots")
        JdkModel(
            homePath = jdk.attrValue("homePath"),
            // IntelliJ's SDK version *display string* is nondeterministic: the release-file form
            // "Eclipse Temurin 21.0.11 - aarch64" when JdkVersionDetector reads the JDK's `release` file,
            // or the `java -version` fallback `java version "21.0.11"` when that detection doesn't win the
            // race. Both — and our generator's "Eclipse Temurin 21.0.11" — encode the same version NUMBER,
            // which is the meaningful invariant (the display string is re-detected by the IDE anyway).
            // Compare the version number, not the formatted string.
            version = normalizeJdkVersion(jdk.attrValue("version")),
            classRoots = roots.rootUrls("classPath"),
            sourceRoots = roots.rootUrls("sourcePath").toSet(),
            annotationRoots = roots.rootUrls("annotationsPath"),
        ).let { name to it }
    }
}

private fun Element.attrValue(child: String): String =
    getChildren(child).firstOrNull()?.getAttributeValue("value")
        ?: error("Missing <$child value=...> under <${this.name}>")

private fun Element.rootUrls(orderType: String): List<String> {
    val typeEl = getChild(orderType) ?: return emptyList()
    val composite = typeEl.getChild("root") ?: return emptyList()
    return composite.getChildren("root").map { it.getAttributeValue("url") }
}
