/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.execution

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.common.timeoutRunBlocking
import com.jonnyzzz.mcpSteroid.TestResultBuilder
import com.jonnyzzz.mcpSteroid.storage.ExecutionId
import java.nio.file.Paths
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for McpScriptContext implementation.
 *
 * Note: Uses a TestResultBuilder to capture output instead of storage,
 * since the new API uses ExecutionResultBuilder interface.
 */
class McpScriptContextTest : BasePlatformTestCase() {

    // Run tests off the EDT: the test EDT holds the IW lock (isReadAccessAllowed == true),
    // which would force findFile into its snapshot-only guard branch and block the
    // refresh behavior under test. Same pattern as RunInspectionsDirectlyTest.
    override fun runInDispatchThread(): Boolean = false

    private lateinit var executionId: ExecutionId

    override fun setUp() {
        super.setUp()
        executionId = ExecutionId("test-execution-id")
    }

    private fun createContext(resultBuilder: TestResultBuilder = TestResultBuilder()): Pair<McpScriptContextImpl, TestResultBuilder> {
        val disposable = Disposer.newDisposable(testRootDisposable, "test-context-$executionId")
        Disposer.register(testRootDisposable, disposable)
        val context = McpScriptContextImpl(
            project = project,
            executionId = executionId,
            disposable = disposable,
            resultBuilder = resultBuilder,
            // The modal monitor (unused by these tests) launches here; a throwaway scope is fine.
            executionScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        )
        return context to resultBuilder
    }

    fun testPrintlnVarargs() {
        val (context, builder) = createContext()

        // Test varargs println
        context.println("Hello", "World", 42)
        context.println()  // Empty line
        context.println(null, "test")

        assertEquals("Should have 3 messages", 3, builder.messages.size)
        assertEquals("Hello World 42", builder.messages[0])
        assertEquals("", builder.messages[1])  // Empty line
        assertEquals("null test", builder.messages[2])
    }

    fun testPrintlnSingleValue() {
        val (context, builder) = createContext()

        context.println("Single value")
        context.println(123)

        assertEquals(2, builder.messages.size)
        assertEquals("Single value", builder.messages[0])
        assertEquals("123", builder.messages[1])
    }

    fun testPrintJsonWithMap() {
        val (context, builder) = createContext()

        context.printJson(mapOf("name" to "test", "count" to 42))

        assertEquals(1, builder.messages.size)
        // Jackson output should contain the keys
        assertTrue(builder.messages[0].contains("\"name\""))
        assertTrue(builder.messages[0].contains("\"test\""))
        assertTrue(builder.messages[0].contains("\"count\""))
        assertTrue(builder.messages[0].contains("42"))
    }

    fun testPrintJsonWithNull() {
        val (context, builder) = createContext()

        context.printJson(null)

        assertEquals(1, builder.messages.size)
        assertEquals("null", builder.messages[0])
    }

    fun testPrintException() {
        val (context, builder) = createContext()

        val exception = RuntimeException("Test error")
        context.printException("Something failed", exception)

        assertEquals(1, builder.exceptions.size)
        assertEquals("Something failed", builder.exceptions[0].first)
        assertEquals(exception, builder.exceptions[0].second)
    }

    fun testProgressReporting() {
        val (context, builder) = createContext()

        context.progress("Starting work...")
        context.progress("Processing...")

        assertEquals(2, builder.progressMessages.size)
        assertEquals("Starting work...", builder.progressMessages[0])
        assertEquals("Processing...", builder.progressMessages[1])
    }

    fun testProjectAccess() {
        val (context, _) = createContext()

        assertEquals(project, context.project)
    }

    fun testDisposedContextRejectsOutput() {
        val (context, _) = createContext()
        // Dispose the context
        Disposer.dispose(context.disposable)

        try {
            context.println("Should fail")
            fail("Should throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("disposed") == true)
        }
    }

    fun testIsDisposedFlag() {
        val (context, _) = createContext()

        assertFalse("Should not be disposed initially", context.isDisposed)

        Disposer.dispose(context.disposable)

        assertTrue("Should be disposed after dispose()", context.isDisposed)
    }

    fun testFindProjectFilesByGlob(): Unit = timeoutRunBlocking(30.seconds) {
        val (context, _) = createContext()

        myFixture.addFileToProject("src/main/kotlin/demo/First.kt", "package demo\nclass First")
        myFixture.addFileToProject("src/main/kotlin/demo/Second.kt", "package demo\nclass Second")
        myFixture.addFileToProject("src/main/resources/demo.txt", "demo")

        val files = context.findProjectFiles("**/*.kt")
        val names = files.map { it.name }.sorted()

        assertEquals(listOf("First.kt", "Second.kt"), names)
    }

    // ============================================================
    // #156 — findProjectFile / findFile path contract + refresh
    // ============================================================

    /** Creates a real file under project.basePath through VFS and returns its VirtualFile. */
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

    fun testFindProjectFileRelativePath() {
        val (context, _) = createContext()
        createProjectFile("cfg/app.properties", "key=value")

        val vf = context.findProjectFile("cfg/app.properties")
        assertNotNull("relative path must resolve", vf)
        assertEquals("app.properties", vf!!.name)
    }

    fun testFindProjectFileAbsolutePathUnderRoot() {
        // The exact eval failure shape from issue #156: an absolute path under the
        // project root passed to findProjectFile used to double the basePath → null.
        val (context, _) = createContext()
        val created = createProjectFile("mod/build.gradle", "plugins {}")

        val vf = context.findProjectFile(created.path)
        assertNotNull("absolute path under project root must resolve", vf)
        assertEquals(created.path, vf!!.path)
    }

    fun testFindProjectFileAbsolutePathOutsideRoot() {
        val (context, _) = createContext()
        val outside = java.nio.file.Files.createTempFile("mcp-steroid-156-", ".txt")
        try {
            java.nio.file.Files.writeString(outside, "outside")
            val vf = context.findProjectFile(outside.toString())
            assertNotNull("absolute path outside project resolves like findFile", vf)
        } finally {
            java.nio.file.Files.deleteIfExists(outside)
        }
    }

    fun testFindProjectFileBlankResolvesProjectRoot() {
        // Pins the pre-#156 behavior: "" joins to "$basePath/" → the project root.
        val (context, _) = createContext()
        val vf = context.findProjectFile("")
        assertNotNull("blank path must keep resolving the project root", vf)
        assertEquals(project.basePath, vf!!.path.trimEnd('/'))
    }

    fun testFindProjectFileMalformedInputReturnsNull() {
        val (context, _) = createContext()
        // NUL is invalid in paths on every supported OS — must return null, never throw
        // (covers the whole findProjectFile path incl. the isAbsolutePath Path.of guard).
        assertNull(context.findProjectFile("bad\u0000name"))
    }

    fun testFindProjectPsiFileAbsolutePathDelegates(): Unit = timeoutRunBlocking(30.seconds) {
        val (context, _) = createContext()
        val created = createProjectFile("docs/readme.txt", "hello")

        val psi = context.findProjectPsiFile(created.path)
        assertNotNull("findProjectPsiFile must accept absolute paths via delegation", psi)
    }

    /**
     * Creates a sibling file through VFS and caches the parent directory's children in
     * the VFS snapshot. Without this, a never-cached directory loads its children
     * LAZILY from disk on first lookup, and an externally created file would be found
     * even without any refresh — masking exactly the behavior these tests pin.
     */
    private fun cachedExternalDir(dirName: String): java.nio.file.Path {
        val sibling = createProjectFile("$dirName/sibling.txt", "sibling")
        sibling.parent.children // cache the children list in the snapshot
        return sibling.parent.toNioPath()
    }

    fun testFindFileSeesExternallyCreatedFile() {
        // Refresh behavior (a): a file created on disk BEHIND the VFS (plain nio write,
        // no VFS API, parent children already cached) must be visible to a top-level
        // findFile call via its refresh.
        val (context, _) = createContext()
        val dir = cachedExternalDir("ext-created")
        val nioPath = dir.resolve("new-file.txt")
        java.nio.file.Files.writeString(nioPath, "created externally")

        val vf = context.findFile(nioPath.toString())
        assertNotNull("externally created file must be found via refresh", vf)
        assertEquals("created externally", String(vf!!.contentsToByteArray(), vf.charset))
    }

    fun testFindFileSeesExternallyModifiedContent() {
        // Refresh behavior (b) — the always-refresh upgrade: a snapshot HIT whose disk
        // content changed externally must return the NEW content. Different content
        // lengths defend against filesystem timestamp granularity.
        val (context, _) = createContext()
        val created = createProjectFile("ext-modified/file.txt", "old content")
        assertEquals("old content", String(created.contentsToByteArray(), created.charset))

        java.nio.file.Files.writeString(created.toNioPath(), "new content, longer than before")

        val vf = context.findFile(created.path)
        assertNotNull(vf)
        assertEquals("new content, longer than before", String(vf!!.contentsToByteArray(), vf.charset))
    }

    fun testFindFileDetectsExternalDeletion() {
        // Refresh behavior (c): a file deleted on disk behind the VFS must yield null,
        // not a stale VirtualFile.
        val (context, _) = createContext()
        val created = createProjectFile("ext-deleted/file.txt", "doomed")
        java.nio.file.Files.delete(created.toNioPath())

        assertNull("externally deleted file must not resolve", context.findFile(created.path))
    }

    fun testFindFileInsideReadActionIsSnapshotOnly(): Unit = timeoutRunBlocking(30.seconds) {
        // Guard: under a read action the helper must NOT refresh (sync refresh there
        // deadlocks) — an externally created file stays invisible, and the call returns.
        val (context, _) = createContext()
        val dir = cachedExternalDir("ext-read-guarded")
        val nioPath = dir.resolve("guarded.txt")
        java.nio.file.Files.writeString(nioPath, "invisible under read lock")

        val underRead = context.readAction { context.findFile(nioPath.toString()) }
        assertNull("read action must be snapshot-only (no refresh, no deadlock)", underRead)

        // Sanity: the same lookup at top level DOES refresh and finds the file.
        assertNotNull("top-level lookup must find it via refresh", context.findFile(nioPath.toString()))
    }

    fun testFindFileInsideWriteActionIsSnapshotOnly() {
        // Guard: write actions report read access in the current lock model, so the
        // helper must be snapshot-only there too.
        val (context, _) = createContext()
        val dir = cachedExternalDir("ext-write-guarded")
        val nioPath = dir.resolve("guarded.txt")
        java.nio.file.Files.writeString(nioPath, "invisible under write lock")

        val underWrite = WriteAction.computeAndWait<VirtualFile?, RuntimeException> {
            context.findFile(nioPath.toString())
        }
        assertNull("write action must be snapshot-only (no refresh)", underWrite)
    }
}
