/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.infra

import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.docker.ContainerDriver
import com.jonnyzzz.mcpSteroid.testHelper.docker.ExecContainerProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.docker.runInContainerDetached
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.docker.writeFileInContainer
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode

data class WindowRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class WindowInfo(
    val id: String,
    val title: String,
    val rect: WindowRect,
    val pid: Long?,
)

class XcvbWindowDriver(
    private val lifetime: CloseableStack,
    private val driver: ContainerDriver,
    private val wholeScreen: WindowRect,
) {
    fun startWindowManager() {
        // Override the Debian fluxbox style wallpaper (which references an
        // image from desktop-base that isn't installed) with our own.
        driver.writeFileInContainer(
            "/home/agent/.fluxbox/overlay",
            "background: fullscreen\nbackground.pixmap: /usr/share/images/mcp-steroid-wallpaper.jpg\n",
        )

        // Disable the fluxbox toolbar (the task bar at the bottom of the screen).
        // Without this, fluxbox reserves ~20px at the top and ~20px at the bottom,
        // reporting a reduced _NET_WORKAREA and visually cutting the windows.
        // We want windows to cover the full 3840×2160 display.
        driver.writeFileInContainer(
            "/home/agent/.fluxbox/init",
            "session.screen0.toolbar.visible: false\n",
        )

        // Remove WM title bars for IntelliJ IDEA and xterm windows.
        // Without a WM title bar, _NET_FRAME_EXTENTS=(0,0,0,0) and IntelliJ's new UI
        // (Islands theme) positions its integrated title bar at y=0 instead of y=-20,
        // making the toolbar fully visible. xterm has no need for a WM title bar either.
        driver.writeFileInContainer(
            "/home/agent/.fluxbox/apps",
            buildString {
                appendLine("[app] (name=jetbrains-idea)")
                appendLine("  [Decorations]  {NONE}")
                appendLine("[end]")
                appendLine("[app] (name=xterm)")
                appendLine("  [Decorations]  {NONE}")
                appendLine("[end]")
            },
        )

        println("[xcvb] Starting fluxbox...")
        val proc = driver.runInContainerDetached(
            listOf("fluxbox"),
        )

        lifetime.registerCleanupAction {
            proc.kill()
        }
    }

    fun listWindows(quietly : Boolean = true): List<WindowInfo> {
        // Run a shell script to list all windows with their geometry and title efficiently
        // Output format: ID|X|Y|WIDTH|HEIGHT|PID|TITLE
        val d = '$'
        val script = """
            for id in ${d}(xdotool search --onlyvisible --name "." 2>/dev/null || true); do
              unset X Y WIDTH HEIGHT pid
              name=${d}(xdotool getwindowname "${d}id" 2>/dev/null)
              eval ${d}(xdotool getwindowgeometry --shell "${d}id" 2>/dev/null)
              pid=${d}(xdotool getwindowpid "${d}id" 2>/dev/null)
              if [ -n "${d}X" ] && [ -n "${d}Y" ] && [ -n "${d}WIDTH" ] && [ -n "${d}HEIGHT" ]; then
                echo "${d}id|${d}X|${d}Y|${d}WIDTH|${d}HEIGHT|${d}{pid:--1}|${d}name"
              fi
            done
        """.trimIndent()

        val result = driver.startProcessInContainer {
            this
                .args("bash", "-c", script)
                .timeoutSeconds(15)
                .quietly(quietly)
                .description("listWindows")
        }.awaitForProcessFinish()
        if (result.exitCode != 0) return emptyList()

        return result.stdout.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split('|', limit = 7)
                if (parts.size < 7) return@mapNotNull null
                val id = parts[0]
                val x = parts[1].toIntOrNull() ?: return@mapNotNull null
                val y = parts[2].toIntOrNull() ?: return@mapNotNull null
                val width = parts[3].toIntOrNull() ?: return@mapNotNull null
                val height = parts[4].toIntOrNull() ?: return@mapNotNull null
                val pid = parts[5].toLongOrNull()?.takeIf { it > 0 }
                val title = parts[6]

                WindowInfo(id, title, WindowRect(x, y, width, height), pid)
            }
            .toList()
    }

    fun getDisplayArea(): WindowRect = wholeScreen

    /**
     * Return the usable screen area for window layout.
     *
     * The fluxbox toolbar is disabled (session.screen0.toolbar.visible: false in
     * ~/.fluxbox/init), so the work area equals the full display. We return the
     * known display dimensions with a 2px inset on all sides — this leaves a visible
     * border around both windows so screenshots can confirm correct edge alignment.
     * An extra 100px is subtracted from the bottom so the IntelliJ status bar
     * remains visible and is not cut off at the screen edge.
     */
    fun getWorkArea(): WindowRect = WindowRect(
        x = wholeScreen.x + 2,
        y = wholeScreen.y + 2,
        width = wholeScreen.width - 4,
        height = wholeScreen.height - 104,
    )


    fun updateLayout(window: WindowInfo, rect: WindowRect) {
        val windowId = window.id

        // Note: --sync is intentionally omitted. It makes xdotool wait for the window
        // to acknowledge the resize via WM size hints, but xterm does not use size hints,
        // causing a 5-second timeout. Without --sync the operation fires and returns
        // immediately, which is sufficient for our layout purposes.
        driver.startProcessInContainer {
            this
                .args("xdotool", "windowsize", windowId, rect.width.toString(), rect.height.toString())
                .timeoutSeconds(5)
                .description("xdotool windowsize $windowId")
        }.assertExitCode(0) { "xdotool windowsize failed" }

        driver.startProcessInContainer {
            this
                .args("xdotool", "windowmove", windowId, rect.x.toString(), rect.y.toString())
                .timeoutSeconds(5)
                .description("xdotool windowmove $windowId")
        }.assertExitCode(0) { "xdotool windowmove failed" }

        driver.startProcessInContainer {
            this
                .args("xdotool", "windowraise", windowId)
                .timeoutSeconds(5)
                .description("xdotool windowraise $windowId")
        }.assertExitCode(0) { "xdotool windowraise failed" }
    }

    /**
     * Force AWT to re-layout by nudging the window width by 1px then restoring it.
     *
     * When a window is moved externally via xdotool, IntelliJ's AWT may not update its
     * internal rendering origin. Symptom: ~50px blank gap at top, status bar clipped at
     * bottom (AWT renders as if still at the old position). Sending a second ConfigureNotify
     * with a slightly different size forces AWT to process the new window origin correctly.
     *
     * Only use this for AWT windows (IntelliJ). Do NOT call for xterm — xterm does not
     * use size hints and a nudge there is unnecessary.
     */
    fun forceRelayout(window: WindowInfo, rect: WindowRect) {
        val windowId = window.id
        driver.startProcessInContainer {
            this
                .args("xdotool", "windowsize", windowId, (rect.width - 1).toString(), rect.height.toString())
                .timeoutSeconds(5)
                .description("xdotool windowsize nudge $windowId")
        }.assertExitCode(0) { "xdotool windowsize nudge failed" }

        driver.startProcessInContainer {
            this
                .args("xdotool", "windowsize", windowId, rect.width.toString(), rect.height.toString())
                .timeoutSeconds(5)
                .description("xdotool windowsize restore $windowId")
        }.assertExitCode(0) { "xdotool windowsize restore failed" }
    }
}
