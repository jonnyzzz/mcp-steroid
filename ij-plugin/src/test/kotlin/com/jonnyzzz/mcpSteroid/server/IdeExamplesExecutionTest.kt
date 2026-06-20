/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.intellij.codeInspection.redundantCast.RedundantCastInspection
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.execution.ExecutionManager
import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.prompts.generated.ide.IdeIndex
import com.jonnyzzz.mcpSteroid.setSystemPropertyForTest
import com.jonnyzzz.mcpSteroid.testExecParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

class IdeExamplesExecutionTest : BasePlatformTestCase() {
    private val index = IdeIndex()

    private lateinit var refactorSamplePath: String
    private lateinit var importsSamplePath: String
    private lateinit var inspectionSamplePath: String
    private lateinit var moveSamplePath: String
    private lateinit var moveTargetDirPath: String
    private lateinit var safeDeletePath: String
    private lateinit var greeterImplPath: String
    private lateinit var pullUpBasePath: String
    private lateinit var pullUpChildPath: String
    private lateinit var pushDownBasePath: String
    private lateinit var pushDownChildAPath: String
    private lateinit var pushDownChildBPath: String
    private lateinit var extractSourcePath: String
    private lateinit var extractTargetDirPath: String
    private lateinit var moveClassPath: String
    private lateinit var moveClassTargetDirPath: String
    private lateinit var constructorSamplePath: String
    private lateinit var refactorPositions: RefactorPositions
    private lateinit var safeDeletePosition: LineColumn

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()

        val basePath = project.basePath ?: error("Project base path is not available")
        val srcVf = WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            VfsUtil.createDirectories(Paths.get(basePath, "src").toString())
        }
        PsiTestUtil.addSourceRoot(module, srcVf)

        val refactorText = """
            package sample;

            public class RefactorSample {
                public int repeatSum(int a, int b) {
                    return (a + b) * (a + b);
                }

                public int add(int a, int b) {
                    return a + b;
                }

                public int useAdd() {
                    return add(1, 2);
                }

                public int inlineTarget(int value) {
                    return value + 1;
                }

                public int useInline() {
                    return inlineTarget(5);
                }

                public void needsExtract() {
                    int total = 0;
                    total += 1;
                    total += 2;
                    total += 3;
                    System.out.println(total);
                }

                public int simplifyIf(boolean flag) {
                    int value;
                    if (flag) {
                        value = 1;
                    } else {
                        value = 2;
                    }
                    return value;
                }
            }
        """.trimIndent()

        refactorSamplePath = writeSampleFile("src/sample/RefactorSample.java", refactorText)
        refactorPositions = RefactorPositions(
            introduceVariable = lineColumnFor(refactorText, "a + b"),
            inlineCall = lineColumnFor(refactorText, "inlineTarget(5)", 1),
            changeSignature = lineColumnFor(refactorText, "public int add", "public int ".length),
            extractStartLine = lineColumnFor(refactorText, "total += 1;").line,
            extractEndLine = lineColumnFor(refactorText, "total += 3;").line,
            callHierarchy = lineColumnFor(refactorText, "add(1, 2)")
        )

        val importsText = """
            package sample;

            import java.util.List;

            public class ImportsSample {
                public int value() {
                    return 42;
                }
            }
        """.trimIndent()
        importsSamplePath = writeSampleFile("src/sample/ImportsSample.java", importsText)

        val inspectionText = """
            package sample;

            public class InspectionSample {
                public Object redundantCast() {
                    return (String) null;
                }
            }
        """.trimIndent()
        inspectionSamplePath = writeSampleFile("src/sample/InspectionSample.java", inspectionText)

        val moveText = """
            package sample.move;

            public class MoveMe {
                public String name() {
                    return "move";
                }
            }
        """.trimIndent()
        moveSamplePath = writeSampleFile("src/sample/move/MoveMe.java", moveText)
        moveTargetDirPath = createTargetDir("src/sample/moved")

        val safeDeleteText = """
            package sample;

            public class SafeDeleteSample {
                public void keep() {
                }

                private void unused() {
                }
            }
        """.trimIndent()
        safeDeletePath = writeSampleFile("src/sample/SafeDeleteSample.java", safeDeleteText)
        safeDeletePosition = lineColumnFor(safeDeleteText, "unused()", 1)

        val greeterText = """
            package sample;

            public interface Greeter {
                String greet(String name);
            }
        """.trimIndent()
        writeSampleFile("src/sample/Greeter.java", greeterText)

        val greeterImplText = """
            package sample;

            public abstract class GreeterImpl implements Greeter {
            }
        """.trimIndent()
        greeterImplPath = writeSampleFile("src/sample/GreeterImpl.java", greeterImplText)

        val baseTypeText = """
            package sample;

            public interface BaseType {
                void doWork();
            }
        """.trimIndent()
        writeSampleFile("src/sample/BaseType.java", baseTypeText)

        val implAText = """
            package sample;

            public class BaseTypeImplA implements BaseType {
                public void doWork() {
                }
            }
        """.trimIndent()
        writeSampleFile("src/sample/BaseTypeImplA.java", implAText)

        val implBText = """
            package sample;

            public class BaseTypeImplB implements BaseType {
                public void doWork() {
                }
            }
        """.trimIndent()
        writeSampleFile("src/sample/BaseTypeImplB.java", implBText)

        val pullUpBaseText = """
            package sample.pullup;

            public class PullUpBase {
            }
        """.trimIndent()
        pullUpBasePath = writeSampleFile("src/sample/pullup/PullUpBase.java", pullUpBaseText)

        val pullUpChildText = """
            package sample.pullup;

            public class PullUpChild extends PullUpBase {
                public int moveUp(int value) {
                    return value + 1;
                }
            }
        """.trimIndent()
        pullUpChildPath = writeSampleFile("src/sample/pullup/PullUpChild.java", pullUpChildText)

        val pushDownBaseText = """
            package sample.pushdown;

            public class PushDownBase {
                public int moveDown(int value) {
                    return value + 2;
                }
            }
        """.trimIndent()
        pushDownBasePath = writeSampleFile("src/sample/pushdown/PushDownBase.java", pushDownBaseText)

        val pushDownChildAText = """
            package sample.pushdown;

            public class PushDownChildA extends PushDownBase {
            }
        """.trimIndent()
        pushDownChildAPath = writeSampleFile("src/sample/pushdown/PushDownChildA.java", pushDownChildAText)

        val pushDownChildBText = """
            package sample.pushdown;

            public class PushDownChildB extends PushDownBase {
            }
        """.trimIndent()
        pushDownChildBPath = writeSampleFile("src/sample/pushdown/PushDownChildB.java", pushDownChildBText)

        val extractSourceText = """
            package sample.extract;

            public class ExtractSource {
                public String work(String input) {
                    return "Hello " + input;
                }
            }
        """.trimIndent()
        extractSourcePath = writeSampleFile("src/sample/extract/ExtractSource.java", extractSourceText)
        extractTargetDirPath = createTargetDir("src/sample/extract")

        val moveClassText = """
            package sample.moveclass;

            public class MoveClassSample {
                public String name() {
                    return "move";
                }
            }
        """.trimIndent()
        moveClassPath = writeSampleFile("src/sample/moveclass/MoveClassSample.java", moveClassText)
        moveClassTargetDirPath = createTargetDir("src/sample/movedclass")

        val constructorText = """
            package sample;

            public class ConstructorSample {
                private final int id;
                private final String name;
            }
        """.trimIndent()
        constructorSamplePath = writeSampleFile("src/sample/ConstructorSample.java", constructorText)
    }

    private fun writeSampleFile(relativePath: String, text: String): String {
        val basePath = project.basePath ?: error("Project base path is not available")
        val fullPath = Paths.get(basePath, relativePath)
        val file = WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            val parent = VfsUtil.createDirectories(fullPath.parent.toString())
            val name = fullPath.fileName.toString()
            val child = parent.findChild(name) ?: parent.createChildData(this, name)
            VfsUtil.saveText(child, text)
            child
        }
        return file.path
    }

    private fun createTargetDir(relativePath: String): String {
        val basePath = project.basePath ?: error("Project base path is not available")
        val dirPath = Paths.get(basePath, relativePath)
        val dir = WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            VfsUtil.createDirectories(dirPath.toString())
        }
        return dir.path
    }

    private data class LineColumn(val line: Int, val column: Int)

    private data class RefactorPositions(
        val introduceVariable: LineColumn,
        val inlineCall: LineColumn,
        val changeSignature: LineColumn,
        val extractStartLine: Int,
        val extractEndLine: Int,
        val callHierarchy: LineColumn
    )

    private fun lineColumnAt(text: String, offset: Int): LineColumn {
        require(offset >= 0) { "Offset must be >= 0" }
        val line = text.substring(0, offset).count { it == '\n' } + 1
        val lineStart = text.lastIndexOf('\n', offset - 1).let { if (it == -1) 0 else it + 1 }
        val column = offset - lineStart + 1
        return LineColumn(line, column)
    }

    private fun lineColumnFor(text: String, needle: String, offsetInNeedle: Int = 0): LineColumn {
        val start = text.indexOf(needle)
        require(start >= 0) { "Needle not found: $needle" }
        return lineColumnAt(text, start + offsetInNeedle)
    }

    private fun escapeKotlinString(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun configureExample(
        code: String,
        filePath: String? = null,
        line: Int? = null,
        column: Int? = null,
        startLine: Int? = null,
        endLine: Int? = null,
        newMethodName: String? = null,
        newVariableName: String? = null,
        newParameterName: String? = null,
        newParameterType: String? = null,
        newParameterDefaultValue: String? = null,
        targetDirPath: String? = null,
        baseMethodName: String? = null,
        classFqn: String? = null,
        methodName: String? = null,
        sourceClassFqn: String? = null,
        targetClassFqn: String? = null,
        memberName: String? = null,
        interfaceName: String? = null,
        targetPackage: String? = null,
        runConfigName: String? = null,
        executorId: String? = null,
        maxResults: Int? = null,
        maxInspections: Int? = null,
        fileName: String? = null,
        fileExtension: String? = null,
        inspectionShortName: String? = null,
        dryRun: Boolean? = null
    ): String {
        var updated = code
        if (filePath != null) {
            updated = updated.replace(
                Regex("val filePath = \".*?\""),
                "val filePath = \"${escapeKotlinString(filePath)}\""
            )
        }
        if (line != null) {
            updated = updated.replace(Regex("val line\\s*=\\s*\\d+"), "val line = $line")
        }
        if (column != null) {
            updated = updated.replace(Regex("val column\\s*=\\s*\\d+"), "val column = $column")
        }
        if (startLine != null) {
            updated = updated.replace(Regex("val startLine\\s*=\\s*\\d+"), "val startLine = $startLine")
        }
        if (endLine != null) {
            updated = updated.replace(Regex("val endLine\\s*=\\s*\\d+"), "val endLine = $endLine")
        }
        if (newMethodName != null) {
            updated = updated.replace(
                Regex("val newMethodName = \".*?\""),
                "val newMethodName = \"${escapeKotlinString(newMethodName)}\""
            )
        }
        if (newVariableName != null) {
            updated = updated.replace(
                Regex("val newVariableName = \".*?\""),
                "val newVariableName = \"${escapeKotlinString(newVariableName)}\""
            )
        }
        if (newParameterName != null) {
            updated = updated.replace(
                Regex("val newParameterName = \".*?\""),
                "val newParameterName = \"${escapeKotlinString(newParameterName)}\""
            )
        }
        if (newParameterType != null) {
            updated = updated.replace(
                Regex("val newParameterType = \".*?\""),
                "val newParameterType = \"${escapeKotlinString(newParameterType)}\""
            )
        }
        if (newParameterDefaultValue != null) {
            updated = updated.replace(
                Regex("val newParameterDefaultValue = \".*?\""),
                "val newParameterDefaultValue = \"${escapeKotlinString(newParameterDefaultValue)}\""
            )
        }
        if (targetDirPath != null) {
            updated = updated.replace(
                Regex("val targetDirPath = \".*?\""),
                "val targetDirPath = \"${escapeKotlinString(targetDirPath)}\""
            )
        }
        if (baseMethodName != null) {
            updated = updated.replace(
                Regex("val baseMethodName = \".*?\""),
                "val baseMethodName = \"${escapeKotlinString(baseMethodName)}\""
            )
        }
        if (classFqn != null) {
            updated = updated.replace(
                Regex("val classFqn = \".*?\""),
                "val classFqn = \"${escapeKotlinString(classFqn)}\""
            )
        }
        if (methodName != null) {
            updated = updated.replace(
                Regex("val methodName = \".*?\""),
                "val methodName = \"${escapeKotlinString(methodName)}\""
            )
        }
        if (sourceClassFqn != null) {
            updated = updated.replace(
                Regex("val sourceClassFqn = \".*?\""),
                "val sourceClassFqn = \"${escapeKotlinString(sourceClassFqn)}\""
            )
        }
        if (targetClassFqn != null) {
            updated = updated.replace(
                Regex("val targetClassFqn = \".*?\""),
                "val targetClassFqn = \"${escapeKotlinString(targetClassFqn)}\""
            )
        }
        if (memberName != null) {
            updated = updated.replace(
                Regex("val memberName = \".*?\""),
                "val memberName = \"${escapeKotlinString(memberName)}\""
            )
        }
        if (interfaceName != null) {
            updated = updated.replace(
                Regex("val interfaceName = \".*?\""),
                "val interfaceName = \"${escapeKotlinString(interfaceName)}\""
            )
        }
        if (targetPackage != null) {
            updated = updated.replace(
                Regex("val targetPackage = \".*?\""),
                "val targetPackage = \"${escapeKotlinString(targetPackage)}\""
            )
        }
        if (runConfigName != null) {
            updated = updated.replace(
                Regex("val runConfigName = \".*?\""),
                "val runConfigName = \"${escapeKotlinString(runConfigName)}\""
            )
        }
        if (executorId != null) {
            updated = updated.replace(
                Regex("val executorId = \".*?\""),
                "val executorId = \"${escapeKotlinString(executorId)}\""
            )
        }
        if (maxResults != null) {
            updated = updated.replace(Regex("val maxResults\\s*=\\s*\\d+"), "val maxResults = $maxResults")
        }
        if (maxInspections != null) {
            updated = updated.replace(Regex("val maxInspections\\s*=\\s*\\d+"), "val maxInspections = $maxInspections")
        }
        if (fileName != null) {
            updated = updated.replace(
                Regex("val fileName = \".*?\""),
                "val fileName = \"${escapeKotlinString(fileName)}\""
            )
        }
        if (fileExtension != null) {
            updated = updated.replace(
                Regex("val fileExtension = \".*?\""),
                "val fileExtension = \"${escapeKotlinString(fileExtension)}\""
            )
        }
        if (inspectionShortName != null) {
            updated = updated.replace(
                Regex("val inspectionShortName = \".*?\""),
                "val inspectionShortName = \"${escapeKotlinString(inspectionShortName)}\""
            )
        }
        if (dryRun != null) {
            updated = updated.replace(Regex("val dryRun\\s*=\\s*\\w+"), "val dryRun = $dryRun")
        }
        return updated
    }

    private suspend fun executeExample(exampleId: String, code: String): ToolCallResult {
        val manager = project.service<ExecutionManager>()
        return withContext(Dispatchers.Default) {
            manager.executeWithProgress(
                testExecParams(code, taskId = "ide-$exampleId", reason = "ide example"),
                NoOpProgressReporter
            )
        }
    }

    private fun getTextContent(result: ToolCallResult): String {
        return result.content.filterIsInstance<ContentItem.Text>().joinToString("\n") { it.text }
    }

    private fun assertExampleResult(result: ToolCallResult, header: String, ignoreCase: Boolean = false) {
        val text = getTextContent(result)
        assertTrue("Should execute without error. Output:\n$text", !result.isError)
        assertTrue(
            "Expected output to contain \"$header\". Output:\n$text",
            text.contains(header, ignoreCase = ignoreCase)
        )
    }

    fun testExtractMethodExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.extractMethodMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            filePath = refactorSamplePath,
            startLine = refactorPositions.extractStartLine,
            endLine = refactorPositions.extractEndLine,
            newMethodName = "extractedTotals",
            dryRun = false
        )

        val result = executeExample("extract-method", code)
        assertExampleResult(result, "Extracted method")
        val updatedText = readAction {
            val vf = LocalFileSystem.getInstance().findFileByPath(refactorSamplePath) ?: return@readAction ""
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@readAction ""
            document.text
        }
        assertTrue(updatedText.contains("extractedTotals"))
    }

    fun testIntroduceVariableExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.introduceVariableMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            filePath = refactorSamplePath,
            line = refactorPositions.introduceVariable.line,
            column = refactorPositions.introduceVariable.column,
            newVariableName = "sum",
            dryRun = false
        )

        val result = executeExample("introduce-variable", code)
        assertExampleResult(result, "Introduced variable")
        val updatedText = readAction {
            val vf = LocalFileSystem.getInstance().findFileByPath(refactorSamplePath) ?: return@readAction ""
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@readAction ""
            document.text
        }
        assertTrue(updatedText.contains("int sum"))
    }

    fun testInlineMethodExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.inlineMethodMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            filePath = refactorSamplePath,
            line = refactorPositions.inlineCall.line,
            column = refactorPositions.inlineCall.column,
            dryRun = false
        )

        val result = executeExample("inline-method", code)
        assertExampleResult(result, "Inlined method")
        val updatedText = readAction {
            val vf = LocalFileSystem.getInstance().findFileByPath(refactorSamplePath) ?: return@readAction ""
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@readAction ""
            document.text
        }
        assertTrue(!updatedText.contains("inlineTarget(5)"))
    }

    fun testChangeSignatureExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.changeSignatureMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            filePath = refactorSamplePath,
            line = refactorPositions.changeSignature.line,
            column = refactorPositions.changeSignature.column,
            newParameterName = "extra",
            newParameterType = "int",
            newParameterDefaultValue = "0",
            dryRun = false
        )

        val result = executeExample("change-signature", code)
        assertExampleResult(result, "Changed signature")
        val updatedText = readAction {
            val vf = LocalFileSystem.getInstance().findFileByPath(refactorSamplePath) ?: return@readAction ""
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@readAction ""
            document.text
        }
        assertTrue(updatedText.contains("int extra"))
        assertTrue(updatedText.contains("add(1, 2, 0)"))
    }

    fun testMoveFileExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.moveFileMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            filePath = moveSamplePath,
            targetDirPath = moveTargetDirPath,
            dryRun = false
        )

        val result = executeExample("move-file", code)
        assertExampleResult(result, "Moved file")

        val oldExists = readAction { LocalFileSystem.getInstance().findFileByPath(moveSamplePath) != null }
        val movedPath = Paths.get(moveTargetDirPath, "MoveMe.java").toString()
        val newFileText = readAction {
            val vf = LocalFileSystem.getInstance().findFileByPath(movedPath) ?: return@readAction ""
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@readAction ""
            document.text
        }
        assertTrue(!oldExists)
        assertTrue(newFileText.contains("package sample.moved"))
    }

    fun testSafeDeleteExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.safeDeleteMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            filePath = safeDeletePath,
            line = safeDeletePosition.line,
            column = safeDeletePosition.column,
            dryRun = false
        )

        val result = executeExample("safe-delete", code)
        assertExampleResult(result, "Safely deleted")
        val updatedText = readAction {
            val vf = LocalFileSystem.getInstance().findFileByPath(safeDeletePath) ?: return@readAction ""
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@readAction ""
            document.text
        }
        assertTrue(!updatedText.contains("unused("))
    }

    fun testOptimizeImportsExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.optimizeImportsMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            filePath = importsSamplePath,
            dryRun = false
        )

        val result = executeExample("optimize-imports", code)
        assertExampleResult(result, "Optimized imports")
        val updatedText = readAction {
            val vf = LocalFileSystem.getInstance().findFileByPath(importsSamplePath) ?: return@readAction ""
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@readAction ""
            document.text
        }
        assertTrue(!updatedText.contains("java.util.List"))
    }

    fun testGenerateOverrideExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.generateOverrideMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            filePath = greeterImplPath,
            baseMethodName = "greet",
            dryRun = false
        )

        val result = executeExample("generate-override", code)
        assertExampleResult(result, "Generated override")
        val updatedText = readAction {
            val vf = LocalFileSystem.getInstance().findFileByPath(greeterImplPath) ?: return@readAction ""
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@readAction ""
            document.text
        }
        assertTrue(updatedText.contains("String greet"))
    }

    fun testInspectAndFixExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        // The InspectionSample is a redundant `(String) null` cast; drive RedundantCast (a
        // LocalInspectionTool) rather than the prompt's SpellCheckingInspection default. The recipe
        // resolves the tool from the current profile by short-name, so it must be enabled there — a
        // bare BasePlatformTestCase profile has no inspections enabled.
        myFixture.enableInspections(RedundantCastInspection())
        val raw = index.inspectAndFixMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            filePath = inspectionSamplePath,
            inspectionShortName = "RedundantCast",
            dryRun = false
        )

        val result = executeExample("inspect-and-fix", code)
        assertExampleResult(result, "Applied quick fix")
        val updatedText = readAction {
            val vf = LocalFileSystem.getInstance().findFileByPath(inspectionSamplePath) ?: return@readAction ""
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@readAction ""
            document.text
        }
        assertTrue(!updatedText.contains("(String)"))
    }

    fun testHierarchySearchExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.hierarchySearchMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            classFqn = "sample.BaseType",
            methodName = "doWork"
        )

        val result = executeExample("hierarchy-search", code)
        assertExampleResult(result, "Inheritors of")
        val output = getTextContent(result)
        assertTrue(output.contains("BaseTypeImplA"))
        assertTrue(output.contains("BaseTypeImplB"))
    }

    fun testCallHierarchyExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.callHierarchyMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            filePath = refactorSamplePath,
            line = refactorPositions.callHierarchy.line,
            column = refactorPositions.callHierarchy.column,
            maxResults = 10
        )

        val result = executeExample("call-hierarchy", code)
        assertExampleResult(result, "Callers of")
        val output = getTextContent(result)
        assertTrue(output.contains("useAdd"))
    }

    fun testRunConfigurationExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.runConfigurationMd.ktBlock000.readPrompt()
        val result = executeExample("run-configuration", raw)
        assertExampleResult(result, "Run Configurations")
    }

    fun testPullUpMembersExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.pullUpMembersMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            sourceClassFqn = "sample.pullup.PullUpChild",
            targetClassFqn = "sample.pullup.PullUpBase",
            memberName = "moveUp",
            dryRun = false
        )

        val result = executeExample("pull-up-members", code)
        assertExampleResult(result, "Pulled up member")
        val baseText = readAction {
            val vf = LocalFileSystem.getInstance().findFileByPath(pullUpBasePath) ?: return@readAction ""
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@readAction ""
            document.text
        }
        val childText = readAction {
            val vf = LocalFileSystem.getInstance().findFileByPath(pullUpChildPath) ?: return@readAction ""
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@readAction ""
            document.text
        }
        assertTrue(baseText.contains("moveUp"))
        assertTrue(!childText.contains("moveUp"))
    }

    fun testPushDownMembersExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.pushDownMembersMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            sourceClassFqn = "sample.pushdown.PushDownBase",
            memberName = "moveDown",
            dryRun = false
        )

        val result = executeExample("push-down-members", code)
        assertExampleResult(result, "Pushed down member")
        val baseText = readAction {
            val vf = LocalFileSystem.getInstance().findFileByPath(pushDownBasePath) ?: return@readAction ""
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@readAction ""
            document.text
        }
        val childAText = readAction {
            val vf = LocalFileSystem.getInstance().findFileByPath(pushDownChildAPath) ?: return@readAction ""
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@readAction ""
            document.text
        }
        val childBText = readAction {
            val vf = LocalFileSystem.getInstance().findFileByPath(pushDownChildBPath) ?: return@readAction ""
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@readAction ""
            document.text
        }
        assertTrue(!baseText.contains("moveDown"))
        assertTrue(childAText.contains("moveDown"))
        assertTrue(childBText.contains("moveDown"))
    }

    fun testExtractInterfaceExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.extractInterfaceMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            sourceClassFqn = "sample.extract.ExtractSource",
            interfaceName = "ExtractedApi",
            targetDirPath = extractTargetDirPath,
            memberName = "work",
            dryRun = false
        )

        val result = executeExample("extract-interface", code)
        assertExampleResult(result, "Extracted interface")
        val interfacePath = Paths.get(extractTargetDirPath, "ExtractedApi.java").toString()
        val interfaceText = readAction {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(interfacePath)
            val found = if (vf != null) vf else run {
                var candidate: VirtualFile? = null
                val root = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
                if (root != null) {
                    VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
                        if (!file.isDirectory && file.name == "ExtractedApi.java") {
                            candidate = file
                            return@iterateChildrenRecursively false
                        }
                        true
                    }
                }
                candidate
            }
            found?.let { VfsUtil.loadText(it) } ?: ""
        }
        val sourceText = readAction {
            val vf = LocalFileSystem.getInstance().findFileByPath(extractSourcePath) ?: return@readAction ""
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@readAction ""
            document.text
        }
        assertTrue(interfaceText.contains("interface ExtractedApi"))
        assertTrue(sourceText.contains("implements ExtractedApi"))
    }

    fun testMoveClassExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.moveClassMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            classFqn = "sample.moveclass.MoveClassSample",
            targetPackage = "sample.movedclass",
            targetDirPath = moveClassTargetDirPath,
            dryRun = false
        )

        val result = executeExample("move-class", code)
        assertExampleResult(result, "Moved class")
        val oldExists = readAction { LocalFileSystem.getInstance().findFileByPath(moveClassPath) != null }
        val newPath = Paths.get(moveClassTargetDirPath, "MoveClassSample.java").toString()
        val newText = readAction {
            val vf = LocalFileSystem.getInstance().findFileByPath(newPath) ?: return@readAction ""
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@readAction ""
            document.text
        }
        assertTrue(!oldExists)
        assertTrue(newText.contains("package sample.movedclass"))
    }

    fun testGenerateConstructorExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.generateConstructorMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            classFqn = "sample.ConstructorSample",
            dryRun = false
        )

        val result = executeExample("generate-constructor", code)
        assertExampleResult(result, "Generated constructor")
        val updatedText = readAction {
            val vf = LocalFileSystem.getInstance().findFileByPath(constructorSamplePath) ?: return@readAction ""
            val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@readAction ""
            document.text
        }
        assertTrue(updatedText.contains("ConstructorSample("))
    }

    fun testProjectDependenciesExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.projectDependenciesMd.ktBlock000.readPrompt()
        val result = executeExample("project-dependencies", raw)
        assertExampleResult(result, "Project Dependencies")
    }

    fun testInspectionSummaryExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.inspectionSummaryMd.ktBlock000.readPrompt()
        val result = executeExample("inspection-summary", raw)
        assertExampleResult(result, "Enabled inspections")
    }

    fun testProjectSearchExampleExecutes(): Unit = timeoutRunBlocking(60.seconds) {
        val raw = index.projectSearchMd.ktBlock000.readPrompt()
        val code = configureExample(
            raw,
            fileName = "RefactorSample.java",
            fileExtension = "java",
            maxResults = 10
        )

        val result = executeExample("project-search", code)
        assertExampleResult(result, "Project Search Results")
        val output = getTextContent(result)
        assertTrue(output.contains("RefactorSample.java"))
    }
}
