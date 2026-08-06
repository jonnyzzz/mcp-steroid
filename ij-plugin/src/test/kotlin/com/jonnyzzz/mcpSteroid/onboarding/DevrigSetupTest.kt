/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.jonnyzzz.mcpSteroid.devrig.devrigLauncherDisplayPath
import com.jonnyzzz.mcpSteroid.onboarding.DevrigSetupRunner.Companion.devrigBinPath
import com.jonnyzzz.mcpSteroid.onboarding.DevrigSetupRunner.Companion.devrigInstalled
import com.jonnyzzz.mcpSteroid.onboarding.DevrigSetupRunner.Companion.parseInstallerLine
import com.jonnyzzz.mcpSteroid.onboarding.DevrigSetupRunner.Companion.startDownloadPoller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files
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

    @Test
    fun `devrigInstalled checks the per-OS launcher file`() {
        val home = Files.createTempDirectory("home")
        assertFalse(devrigInstalled(home, windows = false))
        val bin = Files.createDirectories(home.resolve(".mcp-steroid").resolve("bin"))
        Files.createFile(bin.resolve("devrig"))
        assertTrue(devrigInstalled(home, windows = false))
        // Windows looks for devrig.cmd, not devrig.
        assertFalse(devrigInstalled(home, windows = true))
        Files.createFile(bin.resolve("devrig.cmd"))
        assertTrue(devrigInstalled(home, windows = true))
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
        val buffer = DevrigSetupRunner.ProcessLineBuffer { lines += it }
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
     * with no trailing newline. Before [DevrigSetupRunner.ProcessLineBuffer.flush] existed it was silently
     * dropped and the user got the generic exit-code message instead of the reason.
     */
    @Test
    fun `flush emits the trailing ERROR line that has no newline`() {
        val lines = mutableListOf<String>()
        val buffer = DevrigSetupRunner.ProcessLineBuffer { lines += it }
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

    // --- parseInstallerLine: the installer's output as a wire contract for the progress UI ---
    // These are real lines from `installer-gen/src/main/resources/templates/install.sh.tmpl` (and the
    // matching `install.ps1.tmpl` wording). If the templates change their phrasing, these tests fail
    // instead of the progress bar silently going blank.

    @Test
    fun `downloading line yields the phase and the total size`() {
        val step = parseInstallerLine("[mcp-steroid] downloading jdk (~385 MB) from https://example.com/jdk.tar.gz ...")
        assertTrue(step!!.text, step.text.contains("Downloading jdk"))
        assertTrue(step.text, step.text.contains("385 MB"))
        assertEquals(385L * 1024 * 1024, step.totalBytes)
        assertEquals(false, step.isError)
    }

    @Test
    fun `devrig download is recognised too`() {
        val step = parseInstallerLine("[mcp-steroid] downloading devrig (~226 MB) from https://example.com/devrig.zip ...")
        assertEquals(226L * 1024 * 1024, step!!.totalBytes)
        assertTrue(step.text, step.text.contains("devrig"))
    }

    @Test
    fun `retry, verify, reuse, register and ready lines map to phases without a size`() {
        val cases = mapOf(
            "[mcp-steroid] attempt 2/3 failed (curl exited 28); retrying in 4s..." to "2/3",
            "[mcp-steroid] SHA-256 verified: abc123" to "Verifying",
            "[mcp-steroid] already installed: devrig-macos-arm64-0.101-abc123def456" to "reusing",
            "[mcp-steroid] another install finished first; using existing tree" to "reusing",
            "[mcp-steroid] registering devrig (devrig install devrig)..." to "Registering",
            "[mcp-steroid] devrig binary is ready." to "installed",
            "[mcp-steroid] platform: macos-arm64" to "macos-arm64",
        )
        for ((line, expected) in cases) {
            val step = parseInstallerLine(line)
            assertTrue("$line -> ${step?.text}", step != null && step.text.contains(expected, ignoreCase = true))
            assertNull("$line must not carry a size", step!!.totalBytes)
            assertEquals(line, false, step.isError)
        }
    }

    @Test
    fun `the installer's own ERROR line is surfaced as the failure reason`() {
        val step = parseInstallerLine("[mcp-steroid] ERROR: insufficient disk space in /home/u/.mcp-steroid/binaries: need ~1800 MB")
        assertTrue(step!!.isError)
        assertTrue(step.text, step.text.startsWith("insufficient disk space"))
    }

    @Test
    fun `lines that are not ours, or carry no step, are ignored`() {
        // curl's progress bar, shell noise, and the installer's help text must not overwrite the phase.
        assertNull(parseInstallerLine("######################################                     54.2%"))
        assertNull(parseInstallerLine("bash: line 3: warning: something"))
        assertNull(parseInstallerLine("[mcp-steroid] "))
        assertNull(parseInstallerLine("[mcp-steroid]     Debian/Ubuntu:  sudo apt-get install -y curl unzip tar"))
        assertNull(parseInstallerLine(""))
    }

    @Test
    fun `leading and trailing whitespace does not hide a step`() {
        val step = parseInstallerLine("  [mcp-steroid] registering devrig (devrig install devrig)...  \r")
        assertTrue(step?.text ?: "null", step != null && step.text.contains("Registering"))
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
