/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Version-skew warnings compare **version bases** (`major.minor`, the `version-base` notion from
 * `version.json`), fire on every exec_code call (no de-dup), and go to STDERR (never stdout).
 */
class BackendVersionSkewTest {
    private fun captureStderr(block: () -> Unit): String {
        val original = System.err
        val buf = ByteArrayOutputStream()
        System.setErr(PrintStream(buf, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return buf.toString(Charsets.UTF_8)
    }

    @Test
    fun `versionBase extracts the leading major-minor prefix`() {
        assertEquals("0.100", BackendVersionSkew.versionBase("0.100-409f23a2"))
        assertEquals("0.100", BackendVersionSkew.versionBase("0.100.19999-SNAPSHOT-9b6783a6"))
        assertEquals("0.102", BackendVersionSkew.versionBase("0.102.0-r-40690055")) // 0.102+ release lane (#360)
        assertEquals("0.101", BackendVersionSkew.versionBase("0.101"))
        assertEquals("1.2", BackendVersionSkew.versionBase(" 1.2.3 "))
        assertNull(BackendVersionSkew.versionBase(""))
        assertNull(BackendVersionSkew.versionBase("garbage"))
        assertNull(BackendVersionSkew.versionBase("v0.100")) // no leading digits
    }

    @Test
    fun `same version base is not skewed even when suffixes differ`() {
        // The exact strings that made the old exact-match scheme warn spuriously.
        assertFalse(BackendVersionSkew.isSkewed("0.100.19999-SNAPSHOT-9b6783a6", "0.100-409f23a2"))
        assertFalse(BackendVersionSkew.isSkewed("0.101", "0.101"))
        assertFalse(BackendVersionSkew.isSkewed("0.102.0-r-40690055", "0.102.566-jb-90ac87b"))
    }

    @Test
    fun `different version bases are skewed`() {
        assertTrue(BackendVersionSkew.isSkewed("0.100-409f23a2", "0.101-deadbeef"))
        assertTrue(BackendVersionSkew.isSkewed("1.0", "0.100"))
    }

    @Test
    fun `unparseable versions never report skew`() {
        assertFalse(BackendVersionSkew.isSkewed("", "0.101"))
        assertFalse(BackendVersionSkew.isSkewed("garbage", "0.101"))
        assertFalse(BackendVersionSkew.isSkewed("0.100", ""))
    }

    @Test
    fun `warnOnExecCode warns on stderr on EVERY skewed call - no de-dup`() {
        val pid = 918_273_001L
        val stderr = captureStderr {
            BackendVersionSkew.warnOnExecCode(pid = pid, pluginVersion = "0.100-aaa", devrigVersion = "0.101-bbb")
            BackendVersionSkew.warnOnExecCode(pid = pid, pluginVersion = "0.100-aaa", devrigVersion = "0.101-bbb")
        }
        val warnings = stderr.lines().filter { it.contains("version bases differ") }
        assertEquals(2, warnings.size, stderr)
        assertTrue(warnings.first().startsWith("WARN:"), warnings.first())
        assertTrue(warnings.first().contains("devrig 0.101-bbb"), warnings.first())
        assertTrue(warnings.first().contains("MCP Steroid 0.100-aaa"), warnings.first())
        assertTrue(warnings.first().contains("pid $pid"), warnings.first())
        assertTrue(warnings.first().contains("0.101 vs 0.100"), warnings.first())
    }

    @Test
    fun `warnOnExecCode is silent when bases match or version is unparseable`() {
        val stderr = captureStderr {
            BackendVersionSkew.warnOnExecCode(pid = 1L, pluginVersion = "0.101.5-SNAPSHOT", devrigVersion = "0.101-abc")
            BackendVersionSkew.warnOnExecCode(pid = 2L, pluginVersion = "", devrigVersion = "0.101")
        }
        assertEquals(emptyList<String>(), stderr.lines().filter { it.contains("WARN:") })
    }
}
