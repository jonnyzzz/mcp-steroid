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
        val cmd = takeScreenshotRunTool(
            projectName = "k", taskId = "t", reason = "r", out = outFile.toString(),
        )
        val run = runCliCommand(homePaths()) { runGeneratedToolCommand(cmd, fakeTools(VisionScreenshotToolHandler::class.java to rec)) }
        assertEquals(CliExit.OK, run.exit)
        assertTrue(Files.exists(outFile), "parent dirs must be created and file written")
        assertArrayEquals(raw, Files.readAllBytes(outFile))
        assertTrue(run.stderr.contains(outFile.toAbsolutePath().toString()), run.stderr)
    }

    @Test
    fun `console --out writes only the out file, never a redundant tmp image or a Saved image line`() {
        // Regression: with the console image branch now writing files, renderScreenshotSaved must NOT
        // re-materialize the already-saved image into ~/.mcp-steroid/tmp — the --out path is the only
        // file the user asked for. It must print "Saved --out:", never "Saved image:".
        val raw = ByteArray(16) { it.toByte() }
        val b64 = Base64.getEncoder().encodeToString(raw)
        val rec = RecordingScreenshot(
            ToolCallResult(content = listOf(ContentItem.Image(data = b64, mimeType = "image/png"))),
        )
        val outFile = home.resolve("shots/only.png")
        val hp = homePaths()
        val cmd = takeScreenshotRunTool(
            projectName = "k", taskId = "t", reason = "r", out = outFile.toString(),
        )
        val run = runCliCommand(hp) { runGeneratedToolCommand(cmd, fakeTools(VisionScreenshotToolHandler::class.java to rec)) }
        assertEquals(CliExit.OK, run.exit)
        assertTrue(Files.exists(outFile), "the --out file must be written")
        assertArrayEquals(raw, Files.readAllBytes(outFile))
        // No redundant tmp copy: the tmp dir must contain no `image-*` file (may not exist at all).
        val tmpDir = home.resolve("tmp")
        val tmpImages = if (Files.isDirectory(tmpDir)) {
            Files.list(tmpDir).use { s -> s.filter { it.fileName.toString().startsWith("image-") }.toList() }
        } else {
            emptyList()
        }
        assertTrue(tmpImages.isEmpty(), "console --out must not create a redundant tmp image, found: $tmpImages")
        assertFalse(run.stdout.contains("Saved image:"), run.stdout)
        assertTrue(run.stdout.contains("Saved --out:"), run.stdout)
    }

    @Test
    fun `--out with no image in result is a data error, not a silent success`() {
        // #6: requesting a file via --out means exit 0 requires a file to actually be written. A result
        // with no image cannot satisfy that, so it is a DATA_ERROR — the old "note it but succeed"
        // behavior masked a failed request.
        val rec = RecordingScreenshot(ToolCallResult(content = listOf(ContentItem.Text("no image here"))))
        val cmd = takeScreenshotRunTool(
            projectName = "k", taskId = "t", reason = "r", out = home.resolve("y.png").toString(),
        )
        val run = runCliCommand(homePaths()) { runGeneratedToolCommand(cmd, fakeTools(VisionScreenshotToolHandler::class.java to rec)) }
        assertEquals(CliExit.DATA_ERROR, run.exit)
        assertTrue(run.stderr.contains("no image to save"), run.stderr)
        assertFalse(Files.exists(home.resolve("y.png")))
    }

    // ------------------- --project_name cwd inference (issue #266 part 2) -------------------

    @Test
    fun `screenshot explicit project_name overrides cwd inference`() {
        val rec = RecordingScreenshot(ToolCallResult(content = listOf(ContentItem.Text("ok"))))
        val cmd = takeScreenshotRunTool(
            projectName = "explicit-key", taskId = "t", reason = "r",
        )
        val run = runCliCommand(homePaths()) {
            runGeneratedToolCommand(
                cmd, fakeTools(VisionScreenshotToolHandler::class.java to rec),
                cwd = Path.of("/home/u/proj"), routes = listOf(fakeRoute("/home/u/proj", "cwd-key")),
            )
        }
        assertEquals(CliExit.OK, run.exit)
        assertEquals("explicit-key", rec.projectName)
    }

    @Test
    fun `screenshot blank project_name infers the single containing project`() {
        val rec = RecordingScreenshot(ToolCallResult(content = listOf(ContentItem.Text("ok"))))
        val cmd = takeScreenshotRunTool(
            projectName = null, taskId = "t", reason = "r",
        )
        val run = runCliCommand(homePaths()) {
            runGeneratedToolCommand(
                cmd, fakeTools(VisionScreenshotToolHandler::class.java to rec),
                cwd = Path.of("/home/u/proj/src"), routes = listOf(fakeRoute("/home/u/proj", "proj-abc")),
            )
        }
        assertEquals(CliExit.OK, run.exit)
        assertEquals("proj-abc", rec.projectName)
    }

    @Test
    fun `screenshot no containing project fails with a candidate-listing usage error`() {
        val rec = RecordingScreenshot(ToolCallResult(content = listOf(ContentItem.Text("ok"))))
        val cmd = takeScreenshotRunTool(
            projectName = null, taskId = "t", reason = "r",
        )
        val run = runCliCommand(homePaths()) {
            runGeneratedToolCommand(
                cmd, fakeTools(VisionScreenshotToolHandler::class.java to rec),
                cwd = Path.of("/tmp/elsewhere"), routes = listOf(fakeRoute("/home/u/proj", "proj-abc")),
            )
        }
        assertEquals(CliExit.USAGE, run.exit)
        assertTrue(run.stderr.contains("proj-abc"), run.stderr)
        assertTrue(rec.projectName == null, "handler must never be invoked on a usage error")
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
        val cmd = openProjectRunTool(
            projectPath = home.toString(), taskId = "t", reason = "r", wait = false,
        )
        val run = runCliCommand(homePaths()) {
            runGeneratedToolCommand(cmd, fakeTools(
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
        val cmd = openProjectRunTool(
            projectPath = path, taskId = "t", reason = "r", wait = true,
        )
        val run = runCliCommand(homePaths()) {
            runGeneratedToolCommand(cmd, fakeTools(
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
        val cmd = openProjectRunTool(
            projectPath = path, taskId = "t", reason = "r", wait = true,
        )
        val run = runCliCommand(homePaths()) {
            runGeneratedToolCommand(cmd, fakeTools(
                OpenProjectToolHandler::class.java to open,
                ListWindowsToolHandler::class.java to windows,
            ), waitAttempts = 2, waitIntervalMs = 5)
        }
        assertEquals(CliExit.UNAVAILABLE, run.exit)
        assertEquals(2, windows.calls)
        assertTrue(run.stderr.contains("timed out"), run.stderr)
    }
}
