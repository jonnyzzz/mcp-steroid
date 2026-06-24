/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode


class XcvbInputDriver(
    private val driver: ContainerDriver,
) {
    private data class MouseLocation(
        val x: Int,
        val y: Int,
    )

    private fun xdotool(vararg args: String): String {
        return driver.startProcessInContainer {
            this
                .args(listOf("xdotool") + args.toList())
                .timeoutSeconds(10)
                .quietly()
                .description("xdotool ${args.joinToString(" ")}")
        }.assertExitCode(0) { "[xcvb] xdotool ${args.joinToString(" ")} failed" }
            .stdout.trim()
    }

    private fun getMouseLocationOrNull(): MouseLocation? {
        val result = driver.startProcessInContainer {
            this
                .args("xdotool", "getmouselocation", "--shell")
                .timeoutSeconds(5)
                .quietly()
                .description("xdotool getmouselocation --shell")
        }.awaitForProcessFinish()
        if (result.exitCode != 0) return null

        var x: Int? = null
        var y: Int? = null
        for (line in result.stdout.lineSequence()) {
            val separator = line.indexOf('=')
            if (separator < 0) continue

            when (line.substring(0, separator)) {
                "X" -> x = line.substring(separator + 1).toIntOrNull()
                "Y" -> y = line.substring(separator + 1).toIntOrNull()
            }
        }

        return if (x != null && y != null) MouseLocation(x, y) else null
    }

    /** Copy text to the X11 clipboard. */
    fun clipboardCopy(text: String) {
        driver.startProcessInContainer {
            this
                .args("bash", "-c", "echo -n ${shellEscape(text)} | xclip -selection clipboard")
                .timeoutSeconds(5)
                .quietly()
                .description("clipboardCopy")
        }.assertExitCode(0) { "[xcvb] clipboardCopy failed" }
    }

    /** Read text from the X11 clipboard. */
    fun clipboardPaste(): String {
        return driver.startProcessInContainer {
            this
                .args("xclip", "-selection", "clipboard", "-o")
                .timeoutSeconds(5)
                .quietly()
                .description("clipboardPaste")
        }.assertExitCode(0) { "[xcvb] clipboardPaste failed" }.stdout.trim()
    }

    /** Move the mouse cursor to the given display coordinates. */
    fun mouseMove(x: Int, y: Int) {
        val current = getMouseLocationOrNull()
        if (current != null && current.x == x && current.y == y) return
        xdotool("mousemove", "--sync", x.toString(), y.toString())
    }

    /** Move the mouse to (x, y) and click the given button (1=left, 2=middle, 3=right). */
    fun mouseClick(x: Int, y: Int, button: Int = 1) {
        mouseMove(x, y)
        xdotool("click", button.toString())
    }

    /** Move the mouse to (x, y) and double-click. */
    fun mouseDoubleClick(x: Int, y: Int) {
        mouseMove(x, y)
        xdotool("click", "--repeat", "2", "1")
    }

    /**
     * Press a key or key combination.
     * Examples: `"Return"`, `"Tab"`, `"ctrl+s"`, `"alt+F4"`, `"shift+ctrl+p"`.
     */
    fun keyPress(key: String) {
        xdotool("key", key)
    }

    /** Type a text string character by character with a small inter-key delay. */
    fun typeText(text: String) {
        xdotool("type", "--delay", "50", "--", text)
    }

    /** Return the window ID of the currently active (focused) window. */
    fun getActiveWindowId(): String {
        return xdotool("getactivewindow").trim()
    }

    /** Activate (focus + raise) a window found by name pattern. */
    fun activateWindow(namePattern: String) {
        xdotool("search", "--name", namePattern, "windowactivate")
    }
}
