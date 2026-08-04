/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DevrigInstallProbeTest {

    @Test
    fun `installedDevrigVersion reads the version out of the launcher script`() {
        // Exactly what devrig's BinLauncher.renderPosixLauncher writes.
        val posix = """
            #!/bin/sh
            # devrig launcher
            DEVRIG_JAVA_HOME="/Users/u/.mcp-steroid/binaries/jdk-macos-arm64-0.101-aaaaaaaaaaaa/jdk"; export DEVRIG_JAVA_HOME
            exec "/Users/u/.mcp-steroid/binaries/devrig-macos-arm64-0.101-bbbbbbbbbbbb/devrig-0.101/bin/devrig" "${'$'}@"
        """.trimIndent()
        assertEquals("0.101", installedDevrigVersion(posix))

        // A snapshot build (what a local :npx-kt:installDist produces) keeps its full version string.
        val snapshot = """exec "/home/u/.mcp-steroid/binaries/devrig-linux-x64-0.100-cccccccccccc/devrig-0.100.19999-SNAPSHOT-c6568a61/bin/devrig" "${'$'}@""""
        assertEquals("0.100.19999-SNAPSHOT-c6568a61", installedDevrigVersion(snapshot))

        // Windows `.cmd` hands off with `call` and backslashes.
        val windows = """
            @echo off
            set "DEVRIG_JAVA_HOME=C:\Users\u\.mcp-steroid\binaries\jdk-windows-x64-0.101-aaaaaaaaaaaa\jdk"
            call "C:\Users\u\.mcp-steroid\binaries\devrig-windows-x64-0.101-bbbbbbbbbbbb\devrig-0.101\bin\devrig.bat" %*
        """.trimIndent()
        assertEquals("0.101", installedDevrigVersion(windows))
    }

    @Test
    fun `installedDevrigVersion returns null instead of guessing`() {
        assertNull(installedDevrigVersion(null))
        assertNull(installedDevrigVersion(""))
        assertNull(installedDevrigVersion("   "))
        // No exec/call handoff at all.
        assertNull(installedDevrigVersion("#!/bin/sh\necho hello\n"))
        // Handoff present, but the tree is not the versioned layout we know.
        assertNull(installedDevrigVersion("""exec "/usr/local/bin/devrig" "${'$'}@""""))
        assertNull(installedDevrigVersion("""exec "/opt/tools/custom/bin/devrig" "${'$'}@""""))
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

    @Test
    fun `probeDevrigInstallState combines the two file reads`() {
        val home = Files.createTempDirectory("home")
        assertEquals(
            DevrigInstallState(installed = false, version = null),
            probeDevrigInstallState(home, windows = false),
        )

        val bin = Files.createDirectories(home.resolve(".mcp-steroid").resolve("bin"))
        val launcher = bin.resolve("devrig")
        Files.writeString(
            launcher,
            """exec "/home/u/.mcp-steroid/binaries/devrig-linux-x64-0.101-bbbbbbbbbbbb/devrig-0.101/bin/devrig" "${'$'}@"""",
        )
        assertEquals(
            DevrigInstallState(installed = true, version = "0.101"),
            probeDevrigInstallState(home, windows = false),
        )

        // An unrecognised launcher is "installed, version unknown" — never a guess.
        Files.writeString(launcher, "#!/bin/sh\necho hello\n")
        assertEquals(
            DevrigInstallState(installed = true, version = null),
            probeDevrigInstallState(home, windows = false),
        )
    }
}
