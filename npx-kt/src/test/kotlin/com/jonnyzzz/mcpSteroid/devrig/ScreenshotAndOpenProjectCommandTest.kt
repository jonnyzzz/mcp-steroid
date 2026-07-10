/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.mcp.ContentItem
import com.jonnyzzz.mcpSteroid.mcp.ToolCallResult
import com.jonnyzzz.mcpSteroid.server.ListWindowsResponse
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.ListedWindow
import com.jonnyzzz.mcpSteroid.server.OpenProjectToolHandler
import com.jonnyzzz.mcpSteroid.server.VisionScreenshotToolHandler
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

class ScreenshotAndOpenProjectCommandTest {

    @TempDir lateinit var home: Path
    private fun homePaths() = HomePaths(home).also { it.mkdirsAll() }

    // ------------------------------ take_screenshot ------------------------------

    @Test
    fun `--out writes decoded PNG bytes and notes the path on stderr`() {
        val raw = ByteArray(16) { it.toByte() }
        val b64 = Base64.getEncoder().encodeToString(raw)
        val rec = RecordingScreenshot(
            ToolCallResult(content = listOf(ContentItem.Image(data = b64, mimeType = "image/png"))),
        )
        val outFile = home.resolve("shots/x.png") // parent dir does not exist yet
        val cmd = DevrigCommand.DevrigCommandScreenshot(
            projectName = "k", taskId = "t", reason = "r", out = outFile.toString(),
        )
        val run = runCliCommand(homePaths()) { runScreenshotCommand(cmd, fakeTools(VisionScreenshotToolHandler::class.java to rec)) }
        assertEquals(CliExit.OK, run.exit)
        assertTrue(Files.exists(outFile), "parent dirs must be created and file written")
        assertArrayEquals(raw, Files.readAllBytes(outFile))
        assertTrue(run.stderr.contains(outFile.toAbsolutePath().toString()), run.stderr)
    }

    @Test
    fun `--out with no image in result is a data error, not a silent success`() {
        // #6: requesting a file via --out means exit 0 requires a file to actually be written. A result
        // with no image cannot satisfy that, so it is a DATA_ERROR — the old "note it but succeed"
        // behavior masked a failed request.
        val rec = RecordingScreenshot(ToolCallResult(content = listOf(ContentItem.Text("no image here"))))
        val cmd = DevrigCommand.DevrigCommandScreenshot(
            projectName = "k", taskId = "t", reason = "r", out = home.resolve("y.png").toString(),
        )
        val run = runCliCommand(homePaths()) { runScreenshotCommand(cmd, fakeTools(VisionScreenshotToolHandler::class.java to rec)) }
        assertEquals(CliExit.DATA_ERROR, run.exit)
        assertTrue(run.stderr.contains("no image to save"), run.stderr)
        assertFalse(Files.exists(home.resolve("y.png")))
    }

    // ------------------------------ open_project ------------------------------

    private fun readyWindow(path: String) = ListedWindow(
        projectName = "k", projectPath = path, title = null, isActive = false, isVisible = true,
        bounds = null, windowId = "w", modalDialogShowing = false, indexingInProgress = false,
        projectInitialized = true,
    )

    private fun notReadyWindow(path: String) = readyWindow(path).copy(projectInitialized = false)

    @Test
    fun `without --wait open_project returns immediately and does not poll windows`() {
        val open = RecordingOpenProject()
        val windows = SequencedListWindows(listOf(ListWindowsResponse(emptyList(), emptyList())))
        val cmd = DevrigCommand.DevrigCommandOpenProject(
            projectPath = home.toString(), taskId = "t", reason = "r", wait = false,
        )
        val run = runCliCommand(homePaths()) {
            runOpenProjectCommand(cmd, fakeTools(
                OpenProjectToolHandler::class.java to open,
                ListWindowsToolHandler::class.java to windows,
            ))
        }
        assertEquals(CliExit.OK, run.exit)
        assertEquals(home.toString(), open.params!!.projectPath)
        assertEquals(0, windows.calls, "no --wait means no polling")
    }

    @Test
    fun `--wait polls until the project is initialized`() {
        val open = RecordingOpenProject()
        val path = home.toString()
        val windows = SequencedListWindows(listOf(
            ListWindowsResponse(listOf(notReadyWindow(path)), emptyList()),
            ListWindowsResponse(listOf(readyWindow(path)), emptyList()),
        ))
        val cmd = DevrigCommand.DevrigCommandOpenProject(
            projectPath = path, taskId = "t", reason = "r", wait = true,
        )
        val run = runCliCommand(homePaths()) {
            runOpenProjectCommand(cmd, fakeTools(
                OpenProjectToolHandler::class.java to open,
                ListWindowsToolHandler::class.java to windows,
            ), waitAttempts = 5, waitIntervalMs = 10)
        }
        assertEquals(CliExit.OK, run.exit)
        assertTrue(windows.calls >= 2, "should poll again after not-ready (was ${windows.calls})")
        assertTrue(run.stderr.contains("ready"), run.stderr)
    }

    @Test
    fun `--wait times out to UNAVAILABLE when never ready`() {
        val open = RecordingOpenProject()
        val path = home.toString()
        val windows = SequencedListWindows(listOf(ListWindowsResponse(listOf(notReadyWindow(path)), emptyList())))
        val cmd = DevrigCommand.DevrigCommandOpenProject(
            projectPath = path, taskId = "t", reason = "r", wait = true,
        )
        val run = runCliCommand(homePaths()) {
            runOpenProjectCommand(cmd, fakeTools(
                OpenProjectToolHandler::class.java to open,
                ListWindowsToolHandler::class.java to windows,
            ), waitAttempts = 2, waitIntervalMs = 5)
        }
        assertEquals(CliExit.UNAVAILABLE, run.exit)
        assertEquals(2, windows.calls)
        assertTrue(run.stderr.contains("timed out"), run.stderr)
    }
}
