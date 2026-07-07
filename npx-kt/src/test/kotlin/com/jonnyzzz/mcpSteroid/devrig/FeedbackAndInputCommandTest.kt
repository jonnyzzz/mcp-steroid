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
    fun `input forwards the raw sequence verbatim and leaves parsing to the IDE`() {
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
        assertTrue(rec.params!!.sequence.isEmpty(), "the CLI does not pre-parse; the IDE re-parses rawSequence")
    }
}
