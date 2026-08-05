/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.devrig.devrigLauncherDisplayPath
import com.jonnyzzz.mcpSteroid.aiAgents.stdioMcpServersJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

class DevrigSetupTest {
    // The update directory needs no test of its own here anymore: the installer uses
    // `resolveHomePaths(userHome).updateDir` directly, and that layout is pinned in
    // devrig-common's HomePathsTest — both halves share it by construction.

    @Test
    fun `devrig bin path is per-OS`() {
        val home = Path.of("/home/u")
        assertEquals(Path.of("/home/u/.mcp-steroid/bin/devrig"), devrigBinPath(home, windows = false))
        assertEquals(Path.of("/home/u/.mcp-steroid/bin/devrig.cmd"), devrigBinPath(home, windows = true))
    }

    /**
     * The launcher path the settings page RENDERS and the path this module CHECKS on disk are built by two
     * functions in two modules — they must name the same file, or the page shows a command that does not
     * run. POSIX only: on this (POSIX) JVM, `Path.resolve` cannot produce a backslash-joined Windows path,
     * so the Windows rendering is pinned string-only in `McpServerConfigTest`.
     */
    @Test
    fun `the displayed launcher path and the filesystem launcher path agree`() {
        assertEquals(
            devrigBinPath(Path.of("/home/u"), windows = false).toString(),
            devrigLauncherDisplayPath("/home/u", windows = false),
        )
    }

    /**
     * What the settings page offers to paste into Cursor (or any other client devrig has no CLI for) must be
     * the registration devrig itself writes — same launcher, same `mcp` subcommand — or the manual path
     * silently stops matching the automatic one.
     */
    @Test
    fun `the stdio snippet points at the stable launcher and matches what devrig registers`() {
        val home = Path.of("/home/u")

        val posix = devrigStdioMcpConfigJson(home, windows = false)
        assertEquals(
            stdioMcpServersJson(StdioMcpCommand("/home/u/.mcp-steroid/bin/devrig", listOf("mcp"))),
            posix,
        )

        // The Windows case pins the cmd.exe wrapping and the quoting, not the path separator: this test
        // also runs on POSIX, where Path.resolve joins with '/'. Separators are the bin-path test's job.
        val winHome = Path.of("C:\\Users\\u")
        val launcher = devrigBinPath(winHome, windows = true).toString()
        assertEquals(
            stdioMcpServersJson(StdioMcpCommand("cmd.exe", listOf("/d", "/c", "\"$launcher\" mcp"))),
            devrigStdioMcpConfigJson(winHome, windows = true),
        )
    }

    // --- the download poller (coroutine, replacing scheduleWithFixedDelay) ---

    @Test
    fun `download poller updates the indicator and cancellation actually stops it`() {
        val indicator = RecordingIndicator()
        val total = AtomicLong(100L * 1024 * 1024)
        val polls = AtomicInteger()
        runBlocking {
            val scope = CoroutineScope(Job())
            val poller = scope.startDownloadPoller(indicator, total, pollInterval = 5.milliseconds) {
                polls.incrementAndGet()
                50L * 1024 * 1024
            }
            // Wait on text2, the LAST field the poller writes: waiting on fraction raced the poller
            // between its two indicator writes and could observe text2 still null. text2 is a volatile
            // write after the fraction write, so seeing it guarantees the fraction is visible too.
            waitUntil("the poller reports the staged size") { indicator.text2Value == "50 MB of 100 MB" }
            assertEquals(0.5, indicator.fractionValue, 0.0)

            // The while(isActive) + delay loop must end on cancel, not spin on — this join hangs if it does.
            withTimeout(10_000) { poller.cancelAndJoin() }
            val pollsAfterCancel = polls.get()
            Thread.sleep(50)
            assertEquals("a cancelled poller must not poll again", pollsAfterCancel.toLong(), polls.get().toLong())
            scope.cancel()
        }
    }

    /**
     * The regression the coroutine poller exists to fix: with `scheduleWithFixedDelay`, ANY exception
     * escaping one run silently cancelled every future run (the executor's own contract) — a frozen
     * progress bar for the rest of a 30-minute install. One bad poll must cost one tick, not the poller.
     */
    @Test
    fun `one failing poll does not stop the poller`() {
        val indicator = RecordingIndicator()
        val total = AtomicLong(100L * 1024 * 1024)
        val failFirst = AtomicBoolean(true)
        runBlocking {
            val scope = CoroutineScope(Job())
            val poller = scope.startDownloadPoller(indicator, total, pollInterval = 5.milliseconds) {
                if (failFirst.getAndSet(false)) throw IllegalStateException("staging dir vanished mid-poll")
                25L * 1024 * 1024
            }
            // This update can only come from a poll AFTER the throwing one — the loop survived it.
            waitUntil("a poll after the failing one updates the indicator") { indicator.fractionValue == 0.25 }
            withTimeout(10_000) { poller.cancelAndJoin() }
            scope.cancel()
        }
    }

    @Test
    fun `ProcessCanceledException from a poll ends the poller as cancellation`() {
        val indicator = RecordingIndicator()
        val total = AtomicLong(100L * 1024 * 1024)
        runBlocking {
            val scope = CoroutineScope(Job())
            val poller = scope.startDownloadPoller(indicator, total, pollInterval = 5.milliseconds) {
                throw ProcessCanceledException()
            }
            // PCE means "this computation is being cancelled": the poller must END (join returns), and it
            // must not poison anything else running on the same scope.
            withTimeout(10_000) { poller.join() }
            assertTrue(scope.isActive)
            scope.cancel()
        }
    }

    // --- ProcessLineBuffer: chunk reassembly, and the last-line flush ---

    @Test
    fun `line buffer reassembles chunks and emits only completed lines`() {
        val lines = mutableListOf<String>()
        val buffer = ProcessLineBuffer { lines += it }
        buffer.append("[mcp-steroid] down")
        buffer.append("loading devrig (~226 MB) ...\n[mcp-steroid] SHA")
        assertEquals(listOf("[mcp-steroid] downloading devrig (~226 MB) ..."), lines)
        buffer.append("-256 verified: abc\n")
        assertEquals(
            listOf("[mcp-steroid] downloading devrig (~226 MB) ...", "[mcp-steroid] SHA-256 verified: abc"),
            lines,
        )
    }

    /**
     * The installer's final line — the `ERROR: …` reason on the failures that matter most — can arrive
     * with no trailing newline. Before [ProcessLineBuffer.flush] existed it was silently dropped and the
     * user got the generic exit-code message instead of the reason.
     */
    @Test
    fun `flush emits the trailing ERROR line that has no newline`() {
        val lines = mutableListOf<String>()
        val buffer = ProcessLineBuffer { lines += it }
        buffer.append("[mcp-steroid] platform: macos-arm64\n[mcp-steroid] ERROR: no space left on device")
        assertEquals(listOf("[mcp-steroid] platform: macos-arm64"), lines)

        buffer.flush()
        assertEquals(2, lines.size)
        assertEquals("[mcp-steroid] ERROR: no space left on device", lines.last())
        // And the flushed line is one the progress parser surfaces as the failure reason.
        val step = parseInstallerLine(lines.last())
        assertTrue(step != null && step.isError)

        buffer.flush() // nothing left: flushing again must not re-emit
        assertEquals(2, lines.size)
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        fail("timed out waiting for: $what")
    }
}

/**
 * A [ProgressIndicator] that only records what the poller writes. The platform's `EmptyProgressIndicator`
 * records nothing, and the full progress machinery needs an `Application` this plain unit test does not
 * have. Methods the poller never touches throw or no-op.
 */
private class RecordingIndicator : ProgressIndicator {
    @Volatile
    var fractionValue: Double = -1.0

    @Volatile
    var text2Value: String? = null

    @Volatile
    private var cancelled = false

    override fun start() {}
    override fun stop() {}
    override fun isRunning(): Boolean = true
    override fun cancel() {
        cancelled = true
    }

    override fun isCanceled(): Boolean = cancelled
    override fun setText(text: String?) {}
    override fun getText(): String? = null
    override fun setText2(text: String?) {
        text2Value = text
    }

    override fun getText2(): String? = text2Value
    override fun getFraction(): Double = fractionValue
    override fun setFraction(fraction: Double) {
        fractionValue = fraction
    }

    override fun pushState() {}
    override fun popState() {}
    override fun isModal(): Boolean = false
    override fun getModalityState(): ModalityState =
        throw UnsupportedOperationException("not used by the download poller")

    override fun setModalityProgress(modalityProgress: ProgressIndicator?) {}
    override fun isIndeterminate(): Boolean = false
    override fun setIndeterminate(indeterminate: Boolean) {}
    override fun checkCanceled() {}
    override fun isPopupWasShown(): Boolean = false
    override fun isShowing(): Boolean = false
}
