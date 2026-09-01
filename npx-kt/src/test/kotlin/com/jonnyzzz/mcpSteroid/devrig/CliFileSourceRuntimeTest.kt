/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.ExecCodeParams
import com.jonnyzzz.mcpSteroid.server.ExecuteCodeToolHandler
import com.jonnyzzz.mcpSteroid.server.McpProgressReporter
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The runtime half of a declared [com.jonnyzzz.mcpSteroid.mcp.CliFileSource]. The parse phase must never
 * touch the filesystem, so it records only the PATH a file-source flag was given
 * ([GeneratedToolInvocation.fileSources]); reading it — from a file, or from standard input when the path is
 * `-` — happens here, once, for every tool, driven by the declaration and never by the tool name.
 *
 * Driven through `execute_code`, the tool that declares `--code-file`. The two listers declare no file
 * source at all, so this is deliberately the only place the path is exercised: a per-tool shortcut would be
 * cheaper to write and is exactly what the generic substitution exists to prevent.
 */
class CliFileSourceRuntimeTest {

    @TempDir
    lateinit var home: Path

    @TempDir
    lateinit var work: Path

    /** EF BB BF — the UTF-8 encoding of U+FEFF. */
    private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** Records the `code` the tool spec finally parsed, so a test can assert what the substitution produced. */
    private class RecordingExecuteCode : ExecuteCodeToolHandler {
        var seenCode: String? = null

        override suspend fun executeCode(
            projectName: String,
            execCodeParams: ExecCodeParams,
            callProgress: McpProgressReporter,
        ): ToolCallResult {
            seenCode = execCodeParams.code
            return ToolCallResult(content = listOf(ContentItem.Text("ok")))
        }
    }

    private fun runExecuteCode(
        codeFile: String,
        stdin: ByteArray = ByteArray(0),
        json: Boolean = true,
    ): Pair<GeneratedToolRun, String?> {
        val handler = RecordingExecuteCode()
        val tools = FakeMcpSteroidTools().with(ExecuteCodeToolHandler::class.java, handler)
        val args = buildList {
            add("execute_code")
            if (json) add("--json")
            addAll(listOf("--project_name=demo", "--code-file=$codeFile", "--task_id=t", "--reason=r"))
        }
        val command = parseRunTool(*args.toTypedArray())
        return runGeneratedToolForTest(home, command, tools, stdin) to handler.seenCode
    }

    private fun GeneratedToolRun.errorMessage(): String =
        envelope().getValue("data").jsonObject.getValue("content").jsonArray
            .single().jsonObject.getValue("text").jsonPrimitive.content

    // ------------------------------- reading the declared source -------------------------------

    @Test
    fun `a file source is read and substituted into its parameter`() {
        val file = work.resolve("repro.kts")
        Files.writeString(file, "println(\"hello from a file\")\n")

        val (run, seenCode) = runExecuteCode(file.toString())

        assertEquals(CliExit.OK, run.exit, "stdout was:\n${run.stdout}")
        assertEquals("println(\"hello from a file\")\n", seenCode, "the file's content must reach the tool as `code`")
    }

    @Test
    fun `a file source of - is read from standard input`() {
        val (run, seenCode) = runExecuteCode("-", stdin = "println(\"piped\")\n".toByteArray())

        assertEquals(CliExit.OK, run.exit, "stdout was:\n${run.stdout}")
        assertEquals("println(\"piped\")\n", seenCode)
    }

    @Test
    fun `reading standard input is announced on stderr before it blocks`() {
        // An agent that runs devrig non-interactively and pipes nothing would otherwise see the process sit
        // silent with no way to tell whether it is waiting on stdin or on the IDE. The note is printed
        // BEFORE the read, so it is visible even when the read never returns.
        val (run, _) = runExecuteCode("-", stdin = "x\n".toByteArray(), json = false)

        assertTrue(
            run.stderr.contains("standard input"),
            "stderr must say it is reading standard input; got:\n${run.stderr}",
        )
        assertTrue(run.stderr.contains("code"), "the note must name the parameter it is filling; got:\n${run.stderr}")
    }

    @Test
    fun `json standard input keeps stderr clean`() {
        val (run, seenCode) = runExecuteCode("-", stdin = "println(1)\n".toByteArray())

        assertEquals(CliExit.OK, run.exit)
        assertEquals("println(1)\n", seenCode)
        assertEquals("", run.stderr)
    }

    // ------------------------------- diagnosable failures -------------------------------

    @Test
    fun `standard input with nothing piped fails as a usage error instead of hanging silently`() {
        // Nothing piped means EOF at once, so `code` would be the empty string: the tool would then fail
        // with its own confusing complaint about an empty script. Reporting it here as a usage error names
        // both the cause and the two fixes, and keeps the exit code the one a caller can act on.
        val (run, seenCode) = runExecuteCode("-", stdin = ByteArray(0))

        assertEquals(CliExit.USAGE, run.exit, "stdout was:\n${run.stdout}")
        assertNull(seenCode, "the tool must not be called with an empty value read from an empty stdin")
        // Whole string: this message is the entire answer a hung-looking non-interactive caller gets, so its
        // wording is the contract, not an implementation detail. Three defects on this branch reached the
        // binary through substring assertions.
        assertEquals(
            "'code' was to be read from standard input ('-' given) but nothing was piped in; " +
                "pipe the value or pass a file path instead",
            run.errorMessage(),
        )
    }

    @Test
    fun `an empty file source fails as a usage error instead of forwarding an empty value`() {
        // Mirrors the empty-stdin contract: the same parameter read from an empty FILE must not be
        // forwarded for the tool to answer with its own confusing complaint about empty input.
        val file = work.resolve("empty.kts")
        Files.writeString(file, "")

        val (run, seenCode) = runExecuteCode(file.toString())

        assertEquals(CliExit.USAGE, run.exit, "stdout was:\n${run.stdout}")
        assertNull(seenCode, "the tool must not be called with an empty value read from an empty file")
        assertEquals(
            "'code' was to be read from '$file', which is blank; put the value in the file or pass it directly",
            run.errorMessage(),
        )
    }

    @Test
    fun `a whitespace-only file source fails as a usage error like the empty file`() {
        // #460: a file holding only whitespace is the empty file in disguise — the blank payload the
        // parse layer refuses inline (--code=) must not slip through the --code-file spelling.
        val file = work.resolve("blank.kts")
        Files.writeString(file, " \n\t\n")

        val (run, seenCode) = runExecuteCode(file.toString())

        assertEquals(CliExit.USAGE, run.exit, "stdout was:\n${run.stdout}")
        assertNull(seenCode, "the tool must not be called with a blank value read from a whitespace-only file")
        assertEquals(
            "'code' was to be read from '$file', which is blank; put the value in the file or pass it directly",
            run.errorMessage(),
        )
    }

    @Test
    fun `whitespace-only standard input fails as a usage error like empty stdin`() {
        // #460: same rule through the stdin spelling — whitespace is not a script.
        val (run, seenCode) = runExecuteCode("-", stdin = " \n\t\n".toByteArray())

        assertEquals(CliExit.USAGE, run.exit, "stdout was:\n${run.stdout}")
        assertNull(seenCode, "the tool must not be called with a blank value piped through stdin")
        assertEquals(
            "'code' was to be read from standard input ('-' given) but the piped input is blank; " +
                "pipe the value or pass a file path instead",
            run.errorMessage(),
        )
    }

    @Test
    fun `BOM-only standard input is blank, not content`() {
        // #460 hardening: the same Windows encoding artifact through the stdin spelling.
        val (run, seenCode) = runExecuteCode("-", stdin = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))

        assertEquals(CliExit.USAGE, run.exit, "stdout was:\n${run.stdout}")
        assertNull(seenCode, "BOM-only stdin must never ship as a script")
        assertEquals(
            "'code' was to be read from standard input ('-' given) but the piped input is blank; " +
                "pipe the value or pass a file path instead",
            run.errorMessage(),
        )
    }

    @Test
    fun `a double-BOM-only file is still blank`() {
        // Files re-saved through two BOM-adding tools stack the marks; the strip is idempotent.
        val file = work.resolve("double-bom.kts")
        Files.write(file, utf8Bom + utf8Bom)

        val (run, seenCode) = runExecuteCode(file.toString())

        assertEquals(CliExit.USAGE, run.exit, "stdout was:\n${run.stdout}")
        assertNull(seenCode, "a double-BOM-only file must never ship as a script")
        assertEquals(
            "'code' was to be read from '$file', which is blank; put the value in the file or pass it directly",
            run.errorMessage(),
        )
    }

    @Test
    fun `a BOM-only file source is blank, not content`() {
        // #460 hardening: U+FEFF is NOT whitespace, so isBlank alone misses it — a PowerShell
        // redirect or Notepad save of an "empty" file writes exactly this. The shared decoder strips
        // the BOM, so the payload registers as blank.
        val file = work.resolve("bom-only.kts")
        Files.write(file, utf8Bom)

        val (run, seenCode) = runExecuteCode(file.toString())

        assertEquals(CliExit.USAGE, run.exit, "stdout was:\n${run.stdout}")
        assertNull(seenCode, "a BOM-only file must never ship as a script")
        assertEquals(
            "'code' was to be read from '$file', which is blank; put the value in the file or pass it directly",
            run.errorMessage(),
        )
    }

    @Test
    fun `a BOM-prefixed script ships without the BOM`() {
        val file = work.resolve("bom-code.kts")
        Files.write(file, utf8Bom + "println(1)".toByteArray())

        val (run, seenCode) = runExecuteCode(file.toString())

        assertEquals(CliExit.OK, run.exit, "stdout was:\n${run.stdout}")
        assertEquals("println(1)", seenCode, "the encoding artifact must not reach the compiler")
    }

    @Test
    fun `binary standard input fails loudly instead of being silently mangled`() {
        // Files.readString throws MalformedInputException on these bytes; the same bytes piped through
        // `-` must fail the same way, not decay to U+FFFD and send the tool a corrupted script.
        val (run, seenCode) = runExecuteCode("-", stdin = byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte(), 0xFE.toByte()))

        assertEquals(CliExit.IO_ERROR, run.exit, "stdout was:\n${run.stdout}")
        assertNull(seenCode, "the tool must not be called with a corrupted value")
        assertTrue("not valid UTF-8" in run.errorMessage(), "got: ${run.errorMessage()}")
    }

    @Test
    fun `a missing file exits 74`() {
        val absent = work.resolve("absent.kts")
        val (run, seenCode) = runExecuteCode(absent.toString())

        assertEquals(CliExit.IO_ERROR, run.exit, "stdout was:\n${run.stdout}")
        assertNull(seenCode)
        assertEquals(
            "'code' was to be read from '$absent', which is not an existing regular file",
            run.errorMessage(),
        )
    }

    @Test
    fun `a directory given where a file is expected exits 74`() {
        val (run, _) = runExecuteCode(work.toString())

        assertEquals(CliExit.IO_ERROR, run.exit, "stdout was:\n${run.stdout}")
        assertEquals(
            "'code' was to be read from '$work', which is not an existing regular file",
            run.errorMessage(),
        )
    }

    @Test
    fun `a malformed path string exits 64`() {
        // A NUL byte cannot appear in a path on any supported platform, so this is the caller's typo — a
        // fixable invocation mistake — not a filesystem failure.
        val (run, _) = runExecuteCode("bad\u0000name.kts")

        assertEquals(CliExit.USAGE, run.exit, "stdout was:\n${run.stdout}")
        // The trailing InvalidPathException.reason is the JDK's wording, so only devrig's own half is
        // pinned — but pinned from the start of the string, which still catches a doubled prefix.
        assertTrue(
            run.errorMessage().startsWith("'code' was given the path 'bad"),
            "got: ${run.errorMessage()}",
        )
        assertTrue(run.errorMessage().contains("is not a valid path: "), "got: ${run.errorMessage()}")
    }

    // ------------------------------- declaration order -------------------------------

    @Test
    fun `the substituted parameter keeps its declared position instead of being appended`() {
        val file = work.resolve("ordered.kts")
        Files.writeString(file, "1")
        val spec = devrigCliTools().single { it.name == "steroid_execute_code" }
        val command = parseRunTool(
            "execute_code", "--project_name=demo", "--code-file=$file", "--task_id=t", "--reason=r",
        )

        // Before substitution `code` is absent, so appending it would put it after `reason`.
        assertEquals(listOf("project_name", "task_id", "reason"), command.arguments.keys.toList())

        val arguments = command.argumentsWithFileSources(spec, ByteArrayInputStream(ByteArray(0)))

        assertEquals(
            listOf("project_name", "code", "task_id", "reason"),
            arguments.keys.toList(),
            "the tool call must stay in the schema's declaration order after a file source is substituted",
        )
        assertEquals("1", arguments.getValue("code").jsonPrimitive.content)
    }

    @Test
    fun `a tool call with no file source is passed through unchanged`() {
        val spec = devrigCliTools().single { it.name == "steroid_execute_code" }
        val command = parseRunTool(
            "execute_code", "--project_name=demo", "--code=inline", "--task_id=t", "--reason=r",
        )

        val arguments = command.argumentsWithFileSources(spec, ByteArrayInputStream(ByteArray(0)))

        assertEquals(command.arguments, arguments, "with no file source the parsed arguments are the tool call")
    }
}
