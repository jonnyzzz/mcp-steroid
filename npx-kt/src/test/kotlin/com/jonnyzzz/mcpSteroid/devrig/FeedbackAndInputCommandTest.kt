/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.server.ExecuteFeedbackToolHandler
import com.jonnyzzz.mcpSteroid.server.VisionInputToolHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Glue tests for `devrig execute_feedback` and `devrig input`. */
class FeedbackAndInputCommandTest {

    @TempDir lateinit var home: Path
    private fun homePaths() = HomePaths(home).also { it.mkdirsAll() }

    @Test
    fun `feedback maps rating, explanation and inline code`() {
        val rec = RecordingFeedback()
        val cmd = DevrigCommand.DevrigCommandFeedback(
            projectName = "k", taskId = "t1", successRating = 0.75, explanation = "worked", code = "val x = 1",
        )
        val run = runCliCommand(homePaths()) { runFeedbackCommand(cmd, fakeTools(ExecuteFeedbackToolHandler::class.java to rec)) }
        assertEquals(CliExit.OK, run.exit)
        assertEquals("k", rec.projectName)
        assertEquals("t1", rec.params!!.taskId)
        assertEquals(0.75, rec.params!!.successRating)
        assertEquals("worked", rec.params!!.explanation)
        assertEquals("val x = 1", rec.params!!.code)
    }

    @Test
    fun `feedback reads --code-file`() {
        val snippet = home.resolve("s.kts")
        Files.writeString(snippet, "// illustrative")
        val rec = RecordingFeedback()
        val cmd = DevrigCommand.DevrigCommandFeedback(
            projectName = "k", taskId = "t", successRating = 1.0, explanation = "e", codeFile = snippet.toString(),
        )
        runCliCommand(homePaths()) { runFeedbackCommand(cmd, fakeTools(ExecuteFeedbackToolHandler::class.java to rec)) }
        assertEquals("// illustrative", rec.params!!.code)
    }

    @Test
    fun `input forwards the raw sequence verbatim for plugin version skew`() {
        val rec = RecordingInput()
        val cmd = DevrigCommand.DevrigCommandInput(
            projectName = "k", windowId = "win-1", taskId = "t", reason = "r",
            sequence = "press:CTRL+P, type:Main, delay:200, press:ENTER",
        )
        val run = runCliCommand(homePaths()) { runInputCommand(cmd, fakeTools(VisionInputToolHandler::class.java to rec)) }
        assertEquals(CliExit.OK, run.exit)
        assertEquals("k", rec.projectName)
        assertEquals("win-1", rec.params!!.windowId)
        assertEquals("press:CTRL+P, type:Main, delay:200, press:ENTER", rec.params!!.rawSequence)
        assertTrue(rec.params!!.sequence.isEmpty(), "the plugin parses rawSequence using its own version")
    }

    // ------------------------- --project_name cwd inference (issue #266 part 2) -------------------------

    @Test
    fun `feedback explicit project_name overrides cwd inference`() {
        val rec = RecordingFeedback()
        val cmd = DevrigCommand.DevrigCommandFeedback(
            projectName = "explicit-key", taskId = "t", successRating = 1.0, explanation = "e",
        )
        val run = runCliCommand(homePaths()) {
            runFeedbackCommand(
                cmd, fakeTools(ExecuteFeedbackToolHandler::class.java to rec),
                cwd = Path.of("/home/u/proj"), routes = listOf(fakeRoute("/home/u/proj", "cwd-key")),
            )
        }
        assertEquals(CliExit.OK, run.exit)
        assertEquals("explicit-key", rec.projectName)
    }

    @Test
    fun `feedback blank project_name infers the single containing project`() {
        val rec = RecordingFeedback()
        val cmd = DevrigCommand.DevrigCommandFeedback(
            projectName = null, taskId = "t", successRating = 1.0, explanation = "e",
        )
        val run = runCliCommand(homePaths()) {
            runFeedbackCommand(
                cmd, fakeTools(ExecuteFeedbackToolHandler::class.java to rec),
                cwd = Path.of("/home/u/proj/src"), routes = listOf(fakeRoute("/home/u/proj", "proj-abc")),
            )
        }
        assertEquals(CliExit.OK, run.exit)
        assertEquals("proj-abc", rec.projectName)
    }

    @Test
    fun `feedback no containing project fails with a candidate-listing usage error`() {
        val rec = RecordingFeedback()
        val cmd = DevrigCommand.DevrigCommandFeedback(
            projectName = null, taskId = "t", successRating = 1.0, explanation = "e",
        )
        val run = runCliCommand(homePaths()) {
            runFeedbackCommand(
                cmd, fakeTools(ExecuteFeedbackToolHandler::class.java to rec),
                cwd = Path.of("/tmp/elsewhere"), routes = listOf(fakeRoute("/home/u/proj", "proj-abc")),
            )
        }
        assertEquals(CliExit.USAGE, run.exit)
        assertTrue(run.stderr.contains("proj-abc"), run.stderr)
        assertTrue(rec.projectName == null, "handler must never be invoked on a usage error")
    }

    @Test
    fun `input explicit project_name overrides cwd inference`() {
        val rec = RecordingInput()
        val cmd = DevrigCommand.DevrigCommandInput(
            projectName = "explicit-key", windowId = "win-1", taskId = "t", reason = "r", sequence = "press:ENTER",
        )
        val run = runCliCommand(homePaths()) {
            runInputCommand(
                cmd, fakeTools(VisionInputToolHandler::class.java to rec),
                cwd = Path.of("/home/u/proj"), routes = listOf(fakeRoute("/home/u/proj", "cwd-key")),
            )
        }
        assertEquals(CliExit.OK, run.exit)
        assertEquals("explicit-key", rec.projectName)
    }

    @Test
    fun `input blank project_name infers the single containing project`() {
        val rec = RecordingInput()
        val cmd = DevrigCommand.DevrigCommandInput(
            projectName = null, windowId = "win-1", taskId = "t", reason = "r", sequence = "press:ENTER",
        )
        val run = runCliCommand(homePaths()) {
            runInputCommand(
                cmd, fakeTools(VisionInputToolHandler::class.java to rec),
                cwd = Path.of("/home/u/proj/src"), routes = listOf(fakeRoute("/home/u/proj", "proj-abc")),
            )
        }
        assertEquals(CliExit.OK, run.exit)
        assertEquals("proj-abc", rec.projectName)
    }

    @Test
    fun `input no containing project fails with a candidate-listing usage error`() {
        val rec = RecordingInput()
        val cmd = DevrigCommand.DevrigCommandInput(
            projectName = null, windowId = "win-1", taskId = "t", reason = "r", sequence = "press:ENTER",
        )
        val run = runCliCommand(homePaths()) {
            runInputCommand(
                cmd, fakeTools(VisionInputToolHandler::class.java to rec),
                cwd = Path.of("/tmp/elsewhere"), routes = listOf(fakeRoute("/home/u/proj", "proj-abc")),
            )
        }
        assertEquals(CliExit.USAGE, run.exit)
        assertTrue(run.stderr.contains("proj-abc"), run.stderr)
        assertTrue(rec.projectName == null, "handler must never be invoked on a usage error")
    }
}
