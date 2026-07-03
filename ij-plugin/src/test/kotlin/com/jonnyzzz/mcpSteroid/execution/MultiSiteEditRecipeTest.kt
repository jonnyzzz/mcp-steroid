/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jonnyzzz.mcpSteroid.TestResultBuilder
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the read-replace-save recipe that replaced the removed `applyPatch { }`
 * DSL (#206): read a file's content, `content.replace(OLD, NEW)`, pre-check
 * every match, then save all files inside a single `writeAction { }` via
 * `VfsUtil.saveText`. This is exactly what the prompt corpus now teaches
 * (execute-code-tool-description "Multi-site edits", anchor-safe-editing
 * Step 4) — these tests keep the taught instructions true.
 *
 * Inherits the behavioral checklist that mattered from the deleted
 * ApplyPatchTest: disk persistence, multi-file batches, all-or-nothing
 * discipline, CRLF and unicode round-trips, and the write-action contract.
 */
class MultiSiteEditRecipeTest : BasePlatformTestCase() {

    // Off the EDT, like real script bodies (and so writeAction {} has to do
    // its own dispatch — the exact path the instructions describe).
    override fun runInDispatchThread(): Boolean = false

    private fun createContext(): McpScriptContextImpl {
        val disposable = Disposer.newDisposable(testRootDisposable, "recipe-test")
        return McpScriptContextImpl(
            project = project,
            executionId = ExecutionId("recipe-test"),
            disposable = disposable,
            resultBuilder = TestResultBuilder(),
            executionScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        )
    }

    private fun createProjectFile(relativePath: String, content: String): VirtualFile {
        val basePath = project.basePath ?: error("Project base path is not available")
        return WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            val filePath = Paths.get(basePath, relativePath)
            val parent = VfsUtil.createDirectories(filePath.parent.toString())
            val name = filePath.fileName.toString()
            val child = parent.findChild(name) ?: parent.createChildData(this, name)
            VfsUtil.saveText(child, content)
            child
        }
    }

    private fun diskText(vf: VirtualFile): String = Files.readString(vf.toNioPath())

    fun testSingleFileReadReplaceSavePersistsToDiskAndDocument(): Unit = timeoutRunBlocking(30.seconds) {
        val context = createContext()
        val vf = createProjectFile("recipe/Single.java", "class Single { int OLD_VALUE = 1; }")

        // The taught recipe, verbatim shape:
        val content = String(vf.contentsToByteArray(), vf.charset)
        check(content.contains("OLD_VALUE")) { "no match — verify with Grep first" }
        context.writeAction { VfsUtil.saveText(vf, content.replace("OLD_VALUE", "NEW_VALUE")) }

        assertEquals(
            "edit must persist to DISK before the script returns",
            "class Single { int NEW_VALUE = 1; }", diskText(vf),
        )
        val doc = context.readAction { FileDocumentManager.getInstance().getDocument(vf)?.text }
        assertEquals(
            "the in-memory Document must see the same content (VFS+PSI consistency the prompts promise)",
            "class Single { int NEW_VALUE = 1; }", doc,
        )
    }

    fun testMultiFileEditsInOneWriteAction(): Unit = timeoutRunBlocking(30.seconds) {
        val context = createContext()
        val a = createProjectFile("recipe/multi/A.java", "class A { void oldA() {} }")
        val b = createProjectFile("recipe/multi/B.java", "class B { void oldB() {} }")
        val c = createProjectFile("recipe/multi/C.java", "class C { void oldC() {} }")

        // The execute-code-tool-description "Multi-site edits" recipe:
        val edits = listOf(
            Triple(a.path, "oldA", "newA"),
            Triple(b.path, "oldB", "newB"),
            Triple(c.path, "oldC", "newC"),
        )
        val resolved = edits.map { (path, old, new) ->
            val vf = context.findFile(path) ?: error("not found: $path")
            val content = String(vf.contentsToByteArray(), vf.charset)
            check(content.contains(old)) { "no match in $path — verify with Grep first" }
            Triple(vf, content.replace(old, new), path)
        }
        context.writeAction { resolved.forEach { (vf, updated, _) -> VfsUtil.saveText(vf, updated) } }

        assertEquals("class A { void newA() {} }", diskText(a))
        assertEquals("class B { void newB() {} }", diskText(b))
        assertEquals("class C { void newC() {} }", diskText(c))
    }

    fun testPreCheckFailureLeavesAllFilesUntouched(): Unit = timeoutRunBlocking(30.seconds) {
        val context = createContext()
        val a = createProjectFile("recipe/atomic/A.java", "class A { void oldA() {} }")
        val b = createProjectFile("recipe/atomic/B.java", "class B { /* anchor drifted */ }")

        val edits = listOf(
            Triple(a.path, "oldA", "newA"),
            Triple(b.path, "oldB", "newB"),   // not present — must fail the batch pre-check
        )
        val thrown = runCatching {
            val resolved = edits.map { (path, old, new) ->
                val vf = context.findFile(path) ?: error("not found: $path")
                val content = String(vf.contentsToByteArray(), vf.charset)
                check(content.contains(old)) { "no match in $path — verify with Grep first" }
                Triple(vf, content.replace(old, new), path)
            }
            context.writeAction { resolved.forEach { (vf, updated, _) -> VfsUtil.saveText(vf, updated) } }
        }.exceptionOrNull()

        assertNotNull("batch with a bad anchor must fail in the pre-check", thrown)
        assertTrue("failure must name the file", thrown!!.message!!.contains("B.java"))
        // All-or-nothing: the pre-check runs BEFORE the writeAction, so file A is untouched.
        assertEquals("class A { void oldA() {} }", diskText(a))
        assertEquals("class B { /* anchor drifted */ }", diskText(b))
    }

    fun testWriteActionWrapperDispatchesFromBackgroundThread(): Unit = timeoutRunBlocking(30.seconds) {
        // "How to call a write action" from a script body: the context wrapper is a
        // suspend call usable straight from the (background) script — no EDT juggling.
        val context = createContext()
        val vf = createProjectFile("recipe/wrap/W.java", "x")

        val insideWrite = context.writeAction {
            VfsUtil.saveText(vf, "y")
            com.intellij.openapi.application.ApplicationManager.getApplication().isWriteAccessAllowed
        }
        assertTrue("writeAction {} must actually hold write access", insideWrite)
        assertEquals("y", diskText(vf))
    }

    fun testSaveTextOutsideWriteActionThrows(): Unit = timeoutRunBlocking(30.seconds) {
        // Pins WHY the instructions say to wrap: VfsUtil.saveText without a write
        // action must be rejected by the platform, not silently succeed.
        createContext() // ensure the same environment as the other tests
        val vf = createProjectFile("recipe/nowrap/N.java", "x")

        val thrown = runCatching { VfsUtil.saveText(vf, "y") }.exceptionOrNull()
        assertNotNull("saveText outside writeAction must throw", thrown)
        assertEquals("file content must be unchanged after the rejected write", "x", diskText(vf))
    }

    fun testCrlfContentPreservedThroughRecipe(): Unit = timeoutRunBlocking(30.seconds) {
        val context = createContext()
        val vf = createProjectFile("recipe/crlf/File.txt", "line1\r\nOLD\r\nline3\r\n")

        val content = String(vf.contentsToByteArray(), vf.charset)
        context.writeAction { VfsUtil.saveText(vf, content.replace("OLD", "NEW")) }

        assertEquals(
            "CRLF line endings must survive the read-replace-save round trip",
            "line1\r\nNEW\r\nline3\r\n", diskText(vf),
        )
    }

    fun testUnicodeContentPreservedThroughRecipe(): Unit = timeoutRunBlocking(30.seconds) {
        val context = createContext()
        val vf = createProjectFile("recipe/unicode/U.java", "// комментарий 你好 🚀 OLD")

        val content = String(vf.contentsToByteArray(), vf.charset)
        context.writeAction { VfsUtil.saveText(vf, content.replace("OLD", "NEW")) }

        assertEquals(
            "multi-byte content must survive the charset round trip",
            "// комментарий 你好 🚀 NEW", diskText(vf),
        )
    }

    fun testExactlyOnceDisciplineCatchesAmbiguousAnchor(): Unit = timeoutRunBlocking(30.seconds) {
        // The anchor-safe recipe's uniqueness check: an anchor occurring twice must
        // be caught by the taught pre-check idiom before any replace lands.
        val context = createContext()
        val vf = createProjectFile("recipe/ambiguous/D.java", "foo(); foo();")

        val content = String(vf.contentsToByteArray(), vf.charset)
        val occurrences = Regex(Regex.escape("foo()")).findAll(content).count()
        val thrown = runCatching {
            check(occurrences == 1) { "anchor occurs $occurrences times — expand it with surrounding context" }
            context.writeAction { VfsUtil.saveText(vf, content.replace("foo()", "bar()")) }
        }.exceptionOrNull()

        assertNotNull("ambiguous anchor must fail the uniqueness pre-check", thrown)
        assertEquals("foo(); foo();", diskText(vf))
    }

    // ============================================================
    // The escape-hatch recipe: unified diff via the platform engine
    // (mcp-steroid://ide/apply-unified-diff — complex changes only)
    // ============================================================

    private fun applyDiff(context: McpScriptContextImpl, diffText: String): List<String> {
        // The recipe shape from ide/apply-unified-diff.md, condensed.
        val reader = com.intellij.openapi.diff.impl.patch.PatchReader(diffText)
        reader.parseAllPatches()
        val results = mutableListOf<String>()
        for (patch in reader.textPatches) {
            val relativePath = patch.beforeName ?: patch.afterName ?: error("patch without a file name")
            val vf = context.findProjectFile(relativePath) ?: error("not found: $relativePath")
            val text = String(vf.contentsToByteArray(), vf.charset)
            val applied = com.intellij.openapi.diff.impl.patch.apply.GenericPatchApplier.apply(text, patch.hunks)
                ?: error("patch did not apply to $relativePath")
            if (applied.status == com.intellij.openapi.diff.impl.patch.ApplyPatchStatus.ALREADY_APPLIED) {
                results += "ALREADY_APPLIED: $relativePath"
                continue
            }
            check(applied.status == com.intellij.openapi.diff.impl.patch.ApplyPatchStatus.SUCCESS) {
                "patch applied with status ${applied.status} on $relativePath"
            }
            results += "PATCHED: $relativePath"
            runBlockingWrite(context, vf, applied.patchedText)
        }
        return results
    }

    private fun runBlockingWrite(context: McpScriptContextImpl, vf: VirtualFile, text: String) {
        WriteAction.computeAndWait<Unit, RuntimeException> { VfsUtil.saveText(vf, text) }
    }

    private val unifiedDiff = """
        --- a/patched/Service.java
        +++ b/patched/Service.java
        @@ -1,5 +1,6 @@
         public class Service {
             public int compute(int x) {
        -        return x * 2;
        +        // doubled, then offset
        +        return x * 2 + 1;
             }
         }
    """.trimIndent() + "\n"

    fun testUnifiedDiffAppliesCleanly(): Unit = timeoutRunBlocking(30.seconds) {
        val context = createContext()
        createProjectFile(
            "patched/Service.java",
            "public class Service {\n    public int compute(int x) {\n        return x * 2;\n    }\n}\n",
        )

        val results = applyDiff(context, unifiedDiff)

        assertEquals(listOf("PATCHED: patched/Service.java"), results)
        val vf = context.findProjectFile("patched/Service.java")!!
        assertTrue(diskText(vf).contains("return x * 2 + 1;"))
    }

    fun testUnifiedDiffToleratesDriftedPosition(): Unit = timeoutRunBlocking(30.seconds) {
        // The reason this recipe exists: the hunk's expected line numbers are wrong
        // (file gained a header the diff has never seen) — the platform's tolerance
        // ladder must still land the change. A literal exact-position match would fail.
        val context = createContext()
        createProjectFile(
            "patched/Service.java",
            "// license header\n// added after the diff was generated\n\n" +
                "public class Service {\n    public int compute(int x) {\n        return x * 2;\n    }\n}\n",
        )

        val results = applyDiff(context, unifiedDiff)

        assertEquals(listOf("PATCHED: patched/Service.java"), results)
        val vf = context.findProjectFile("patched/Service.java")!!
        val after = diskText(vf)
        assertTrue("edit must land despite the positional drift", after.contains("return x * 2 + 1;"))
        assertTrue("pre-existing header must survive", after.startsWith("// license header"))
    }

    fun testUnifiedDiffDetectsAlreadyApplied(): Unit = timeoutRunBlocking(30.seconds) {
        // Idempotency the literal-replace flow cannot offer: re-applying a landed
        // patch is a recognized no-op, not an "anchor not found" failure.
        val context = createContext()
        createProjectFile(
            "patched/Service.java",
            "public class Service {\n    public int compute(int x) {\n        // doubled, then offset\n        return x * 2 + 1;\n    }\n}\n",
        )

        val results = applyDiff(context, unifiedDiff)

        assertEquals(listOf("ALREADY_APPLIED: patched/Service.java"), results)
    }

    fun testUnifiedDiffUnmatchableHunkFailsBeforeWrite(): Unit = timeoutRunBlocking(30.seconds) {
        // A diff against entirely different content must fail cleanly (null from the
        // engine -> error) with the file untouched — no applySomehow force-placement.
        val context = createContext()
        val vf = createProjectFile("patched/Service.java", "class TotallyDifferent {}\n")

        val thrown = runCatching { applyDiff(context, unifiedDiff) }.exceptionOrNull()

        assertNotNull("unmatchable diff must throw", thrown)
        assertEquals("class TotallyDifferent {}\n", diskText(vf))
    }
}
