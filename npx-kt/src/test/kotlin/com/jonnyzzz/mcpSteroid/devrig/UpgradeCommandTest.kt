/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/** Each OS / outcome is its own declared test (no parameterization) so a failure names the exact case. */
class UpgradeCommandTest {
    private val command = DevrigCommand.DevrigCommandUpgrade()

    @Test
    fun `posix invocation downloads to a temp file then runs it so a failed fetch propagates`() {
        val inv = upgradeInvocation(windows = false)
        assertEquals(listOf("sh", "-c"), inv.subList(0, 2))
        val script = inv[2]
        assertContains(script, "curl -fsSL https://mcp-steroid.jonnyzzz.com/install.sh -o")
        // The fetch and the run are &&-chained (NOT the failure-swallowing `curl … | sh` pipe), and the
        // installer's exit code is propagated via `exit $r`.
        assertContains(script, "&& sh ")
        assertContains(script, "exit \$r")
        assertFalse(script.contains("| sh"), "must not use the failure-swallowing pipe: $script")
    }

    @Test
    fun `windows invocation pipes Invoke-RestMethod into Invoke-Expression`() {
        val inv = upgradeInvocation(windows = true)
        assertEquals("powershell", inv[0])
        assertTrue(inv.any { it.contains("install.ps1") }, inv.toString())
        // Invoke-RestMethod (irm) returns the BODY string; Invoke-WebRequest would pipe a response object
        // and iex would never run the installer.
        assertTrue(inv.last().contains("Invoke-RestMethod") && inv.last().contains("Invoke-Expression"), inv.toString())
        assertFalse(inv.last().contains("Invoke-WebRequest"), inv.toString())
    }

    @Test
    fun `runUpgradeCommand runs the posix install script and returns its exit code`() {
        var ran: List<String>? = null
        val exit = run(command, windows = false) { ran = it; 0 }
        assertEquals(0, exit)
        assertEquals(upgradeInvocation(windows = false), ran)
    }

    @Test
    fun `runUpgradeCommand surfaces a non-zero install-script exit`() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val exit = runUpgradeCommand(command, ps(out), ps(err), { 7 }, windows = false)
        assertEquals(7, exit)
        assertContains(err.toString(Charsets.UTF_8), "FAILED")
    }

    @Test
    fun `runUpgradeCommand narrates the incremental guarantee by default`() {
        val out = ByteArrayOutputStream()
        val exit = run(command, windows = false, out = out) { 0 }
        assertEquals(0, exit)
        val text = out.toString(Charsets.UTF_8)
        assertContains(text, "incremental")
        assertContains(text, "only changed")
    }

    @Test
    fun `runUpgradeCommand stays quiet in json mode`() {
        val out = ByteArrayOutputStream()
        run(DevrigCommand.DevrigCommandUpgrade(json = true), windows = false, out = out) { 0 }
        assertFalse(out.toString(Charsets.UTF_8).contains("Upgrading devrig"), out.toString(Charsets.UTF_8))
    }

    private fun ps(b: ByteArrayOutputStream) = PrintStream(b, true, Charsets.UTF_8)

    private fun run(
        command: DevrigCommand.DevrigCommandUpgrade,
        windows: Boolean,
        out: ByteArrayOutputStream = ByteArrayOutputStream(),
        runner: UpgradeRunner,
    ): Int = runUpgradeCommand(command, ps(out), ps(ByteArrayOutputStream()), runner, windows)
}
