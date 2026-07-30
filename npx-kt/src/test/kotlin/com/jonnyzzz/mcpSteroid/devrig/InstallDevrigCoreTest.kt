/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.util.text.DevrigVersion
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

/**
 * `devrig install devrig` as the update authority (docs/updates-check/devrig-auto-update.md):
 * verify-don't-assume, the `updated-<v>` completion marker, and the DEVRIG_AUTO_UPDATE intent split.
 */
@DisabledOnOs(OS.WINDOWS)
class InstallDevrigCoreTest {

    private fun home(tmp: Path) = HomePaths(tmp.resolve("home/.mcp-steroid"))

    /** A writeLauncher lambda backed by the REAL launcher-writing core, registering [version]. */
    private fun realWriter(home: HomePaths, tmp: Path, script: Path, version: String): (Boolean) -> LauncherWriteOutcome? = { bypass ->
        ensureBinLauncherCore(
            home = home, isWin = false, ownBin = script, jdkHome = tmp.resolve("jdk"),
            userHome = tmp.resolve("home"), pathDirs = emptyList(),
            registeringVersion = version, bypassNoDowngradeGuard = bypass,
        )
    }

    @Test
    fun `manual install writes launcher, verifies it, and attests the updated marker`(@TempDir tmp: Path) {
        val home = home(tmp)
        val script = tmp.resolve("binaries/devrig-linux-x64-0.101.0-abc123456789/devrig-0.101.0-abc1234/bin/devrig")
        val version = DevrigVersion.parse("0.101.0-gh-abc1234")

        val exit = runInstallDevrigCore(
            homePaths = home, installScript = script, ownVersion = version,
            autoUpdateRun = false, isWin = false,
            writeLauncher = realWriter(home, tmp, script, version.value),
        )

        assertEquals(0, exit)
        assertTrue(home.binDir.resolve("devrig").readText().contains(script.toString()), "launcher must exec the install script")
        val marker = home.updateDir.resolve("updated-0.101.0")
        assertTrue(marker.exists(), "the completion marker is attested by install devrig itself")
        assertTrue(marker.readText().contains("\"targetVersion\": \"0.101.0\""), marker.readText())
    }

    @Test
    fun `manual install (rollback) sweeps newer markers and bypasses the guard`(@TempDir tmp: Path) {
        val home = home(tmp)
        Files.createDirectories(home.updateDir)
        Files.writeString(home.updateDir.resolve("updated-0.102.0"), "{}")
        Files.writeString(home.updateDir.resolve("update-failed-0.102.0"), "{}")
        Files.writeString(home.updateDir.resolve("update-skew-0.102.0"), "{}")
        val script = tmp.resolve("binaries/devrig-linux-x64-0.101.0-abc123456789/devrig-0.101.0-abc1234/bin/devrig")

        val exit = runInstallDevrigCore(
            homePaths = home, installScript = script, ownVersion = DevrigVersion.parse("0.101.0"),
            autoUpdateRun = false, isWin = false,
            writeLauncher = realWriter(home, tmp, script, "0.101.0"),
        )

        assertEquals(0, exit)
        assertFalse(home.updateDir.resolve("updated-0.102.0").exists(), "explicit rollback clears the newer completion marker")
        assertFalse(home.updateDir.resolve("update-failed-0.102.0").exists())
        assertFalse(home.updateDir.resolve("update-skew-0.102.0").exists())
        assertTrue(home.updateDir.resolve("updated-0.101.0").exists(), "the rollback attests its own version")
        assertTrue(home.binDir.resolve("devrig").readText().contains("devrig-version: 0.101.0"))
    }

    @Test
    fun `auto-update run refuses to downgrade past a newer completed update`(@TempDir tmp: Path) {
        val home = home(tmp)
        Files.createDirectories(home.updateDir)
        Files.writeString(home.updateDir.resolve("updated-0.102.0"), "{}")
        val script = tmp.resolve("tree/bin/devrig")
        var writerCalled = false

        val exit = runInstallDevrigCore(
            homePaths = home, installScript = script, ownVersion = DevrigVersion.parse("0.101.0"),
            autoUpdateRun = true, isWin = false,
            writeLauncher = { writerCalled = true; LauncherWriteOutcome.WRITTEN },
        )

        assertEquals(INSTALL_DEVRIG_NO_DOWNGRADE_EXIT_CODE, exit)
        assertFalse(writerCalled, "the launcher writer must not even be attempted")
        assertTrue(home.updateDir.resolve("updated-0.102.0").exists(), "an auto-update run never sweeps markers")
    }

    @Test
    fun `verification fails when the launcher on disk does not reference the registered script`(@TempDir tmp: Path) {
        val home = home(tmp)
        Files.createDirectories(home.binDir)
        // simulate the swallowed-write bug: the writer claims success but the OLD launcher stays
        Files.writeString(home.binDir.resolve("devrig"), "#!/bin/sh\nexec \"/old/tree/bin/devrig\" \"\$@\"\n")

        val exit = runInstallDevrigCore(
            homePaths = home, installScript = tmp.resolve("new/bin/devrig"), ownVersion = DevrigVersion.parse("0.101.0"),
            autoUpdateRun = false, isWin = false,
            writeLauncher = { LauncherWriteOutcome.WRITTEN },
        )

        assertEquals(64, exit)
        assertFalse(home.updateDir.resolve("updated-0.101.0").exists(), "no marker may be attested for a failed rewrite")
    }

    @Test
    fun `a throwing launcher write is NOT swallowed - it becomes a non-zero exit`(@TempDir tmp: Path) {
        val home = home(tmp)
        val exit = runInstallDevrigCore(
            homePaths = home, installScript = tmp.resolve("new/bin/devrig"), ownVersion = DevrigVersion.parse("0.101.0"),
            autoUpdateRun = false, isWin = false,
            writeLauncher = { throw java.nio.file.AccessDeniedException("devrig.cmd is locked by another process") },
        )
        assertEquals(64, exit)
        assertFalse(home.updateDir.resolve("updated-0.101.0").exists())
    }

    @Test
    fun `a SNAPSHOT build never attests a completion marker`(@TempDir tmp: Path) {
        val home = home(tmp)
        val script = tmp.resolve("dev/bin/devrig")
        val snapshot = DevrigVersion.parse("0.101.19999-SNAPSHOT-abc")

        val exit = runInstallDevrigCore(
            homePaths = home, installScript = script, ownVersion = snapshot,
            autoUpdateRun = false, isWin = false,
            writeLauncher = realWriter(home, tmp, script, snapshot.value),
        )

        assertEquals(0, exit)
        assertFalse(home.updateDir.exists() && Files.list(home.updateDir).use { s -> s.anyMatch { it.fileName.toString().startsWith("updated-") } },
            "a SNAPSHOT marker would compare above every release and block self-heal")
    }

    @Test
    fun `launcher opt-out keeps an existing launcher and attests nothing`(@TempDir tmp: Path) {
        val home = home(tmp)
        Files.createDirectories(home.binDir)
        Files.writeString(home.binDir.resolve("devrig"), "#!/bin/sh\nexec \"/user/managed/devrig\" \"\$@\"\n")

        val exit = runInstallDevrigCore(
            homePaths = home, installScript = tmp.resolve("new/bin/devrig"), ownVersion = DevrigVersion.parse("0.101.0"),
            autoUpdateRun = false, isWin = false,
            writeLauncher = { null }, // DEVRIG_BIN_NO_AUTO_REGISTER opt-out
        )

        assertEquals(0, exit)
        assertTrue(home.binDir.resolve("devrig").readText().contains("/user/managed/devrig"), "opt-out leaves the launcher alone")
        assertFalse(home.updateDir.resolve("updated-0.101.0").exists())
    }

    @Test
    fun `launcher opt-out with NO existing launcher is a hard error`(@TempDir tmp: Path) {
        val home = home(tmp)
        val exit = runInstallDevrigCore(
            homePaths = home, installScript = tmp.resolve("new/bin/devrig"), ownVersion = DevrigVersion.parse("0.101.0"),
            autoUpdateRun = false, isWin = false,
            writeLauncher = { null },
        )
        assertEquals(64, exit)
    }
}
