/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The installer's output is a wire contract for the progress UI: these are real lines from
 * `installer-gen/src/main/resources/templates/install.sh.tmpl` (and the matching `install.ps1.tmpl`
 * wording). If the templates change their phrasing, these tests fail instead of the progress bar
 * silently going blank.
 */
class InstallerProgressTest {
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
}
