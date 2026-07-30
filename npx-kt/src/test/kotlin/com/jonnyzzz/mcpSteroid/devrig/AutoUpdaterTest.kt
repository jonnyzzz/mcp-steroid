/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.util.text.DevrigVersion
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The tick decision tree of docs/updates-check/devrig-auto-update.md, driven with injected fakes. */
class AutoUpdaterTest {

    private var now = 1_000_000_000_000L
    private val livePids = mutableSetOf(4242L)

    private class Fixture(
        val updater: AutoUpdater,
        val home: HomePaths,
        val notices: MutableList<String>,
        val installerRuns: MutableList<Path>,
        val triggeredVersions: MutableList<String>,
    )

    private fun fixture(
        tmp: Path,
        current: String = "0.101",
        promoted: String? = "0.102",
        scriptBody: (String) -> String = { "VERSION='$it'\n" },
        downloadSucceeds: Boolean = true,
        installerExit: Int? = 0,
    ): Fixture {
        val home = HomePaths(tmp.resolve("home/.mcp-steroid"))
        home.mkdirsAll()
        val coordination = UpdateCoordination(
            updateDir = home.updateDir,
            ownPid = 4242L,
            clock = { now },
            isPidAlive = { it in livePids },
        )
        val notices = mutableListOf<String>()
        val installerRuns = mutableListOf<Path>()
        val triggeredVersions = mutableListOf<String>()
        val updater = AutoUpdater(
            homePaths = home,
            currentVersion = DevrigVersion.parse(current),
            isWin = false,
            coordination = coordination,
            notify = { notices += it },
            fetchPromoted = { promoted?.let { DevrigVersion.parse(it) } },
            downloadScript = { _, target ->
                if (downloadSucceeds) {
                    Files.createDirectories(target.parent)
                    Files.writeString(target, scriptBody(promoted ?: ""))
                }
                downloadSucceeds
            },
            runInstaller = { script, logFile ->
                installerRuns.add(script) // .add, not `+=`: Path is Iterable<Path>, which makes plusAssign ambiguous
                Files.createDirectories(logFile.parent)
                Files.writeString(logFile, "fake installer ran\n")
                installerExit
            },
            noAutoUpdateEnv = null,
            binRegisterOptOutEnv = null,
            onUpdateTriggered = { triggeredVersions += it },
        )
        return Fixture(updater, home, notices, installerRuns, triggeredVersions)
    }

    @Test
    fun `happy path - installs, supervisor writes the record, cleans up, proposes a restart once`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp)

        f.updater.tick()

        assertEquals(1, f.installerRuns.size)
        // telemetry fires once per actually-triggered update, with the raw version.json version
        assertEquals(listOf("0.102"), f.triggeredVersions)
        // the SUPERVISOR writes the completion record after exit 0 (nothing else does)
        assertTrue(f.home.updateDir.resolve("updated-0.102").exists())
        assertFalse(f.home.updateDir.resolve("update-4242-version-0.102").exists(), "the per-pid marker exists only while updating")
        assertFalse(f.home.updateDir.resolve("install-4242.sh").exists(), "the downloaded script is deleted after the run")
        assertEquals(1, f.notices.size)
        assertTrue(f.notices[0].contains("restart"), f.notices[0])
        assertTrue(f.notices[0].contains("0.102"), f.notices[0])

        // second tick: step 6 short-circuits on the record → no reinstall, no duplicate notice
        f.updater.tick()
        assertEquals(1, f.installerRuns.size)
        assertEquals(1, f.notices.size, "restart is notified once per process")
    }

    @Test
    fun `gate - SNAPSHOT builds and env opt-outs never tick`(@TempDir tmp: Path) = runTest {
        val snapshot = fixture(tmp, current = "0.101.19999-SNAPSHOT-abc")
        assertFalse(snapshot.updater.isActive())
        snapshot.updater.tick()
        assertEquals(0, snapshot.installerRuns.size)

        val release = fixture(tmp.resolve("b"), current = "0.101")
        assertTrue(release.updater.isActive())
        assertFalse(
            AutoUpdater(homePaths = release.home, currentVersion = DevrigVersion.parse("0.101"), noAutoUpdateEnv = "yes", binRegisterOptOutEnv = null).isActive(),
            "DEVRIG_NO_AUTO_UPDATE opts out",
        )
        assertFalse(
            AutoUpdater(homePaths = release.home, currentVersion = DevrigVersion.parse("0.101"), noAutoUpdateEnv = null, binRegisterOptOutEnv = "1").isActive(),
            "the launcher-write opt-out also disables the updater (an install could never take effect)",
        )
    }

    @Test
    fun `no update promoted - nothing happens, and a backward promotion never downgrades`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp, current = "0.102", promoted = "0.102")
        f.updater.tick()
        assertEquals(0, f.installerRuns.size)
        assertEquals(0, f.notices.size)

        val back = fixture(tmp.resolve("b"), current = "0.102", promoted = "0.101")
        back.updater.tick()
        assertEquals(0, back.installerRuns.size)
    }

    @Test
    fun `a live in-progress marker from another process - yield silently`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp)
        livePids += 100L
        UpdateCoordination(f.home.updateDir, ownPid = 100L, clock = { now }, isPidAlive = { it in livePids })
            .writeInProgressMarker("0.102", UpdateStateInfo(pid = 100L, currentVersion = "0.101", targetVersion = "0.102", startedAt = now))

        f.updater.tick()

        assertEquals(0, f.installerRuns.size)
        assertEquals(0, f.notices.size, "no update notification while an install is in flight")

        // once the owner dies, the next tick cleans the marker and proceeds
        livePids -= 100L
        f.updater.tick()
        assertEquals(1, f.installerRuns.size)
    }

    @Test
    fun `download failure - quiet retry, no state left behind`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp, downloadSucceeds = false)
        f.updater.tick()
        f.updater.tick()
        assertEquals(0, f.installerRuns.size)
        assertEquals(0, f.notices.size)
        assertFalse(f.home.updateDir.resolve("update-4242-version-0.102").exists(), "the marker is cleaned in the finally")
    }

    @Test
    fun `skew - script serves a different version - quiet retry`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp, scriptBody = { "VERSION='0.101.9'\n" }) // CDN still serves the previous release
        f.updater.tick()
        f.updater.tick()
        assertEquals(0, f.installerRuns.size)
        assertEquals(0, f.notices.size, "skew retries are quiet; surfacing belongs to the release process")
        assertEquals(0, f.triggeredVersions.size, "an aborted update must not report a trigger")
    }

    @Test
    fun `unparsable script - quiet retry, the installer never runs on an unverified script`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp, scriptBody = { "#!/bin/sh\necho no version line here\n" })
        f.updater.tick()
        f.updater.tick()
        assertEquals(0, f.installerRuns.size)
        assertEquals(0, f.notices.size)
    }

    @Test
    fun `failing installer - retries on EVERY tick, never gives up, never nags`(@TempDir tmp: Path) = runTest {
        // No failure tracking by design: too many transient root causes; the schedule (3-8h ticks)
        // is the pacing, and the goal is to keep users up to date.
        val f = fixture(tmp, installerExit = 7)

        repeat(5) { f.updater.tick() }

        assertEquals(5, f.installerRuns.size, "every scheduled tick retries; there is no cap")
        assertEquals(0, f.notices.size, "failures never produce a user-facing notice (stderr + logs only)")
        assertFalse(f.home.updateDir.resolve("update-failed-0.102").exists(), "no failure state is ever written")
    }

    @Test
    fun `rollback keep-case - a session newer than promoted never deletes the promoted record`(@TempDir tmp: Path) = runTest {
        // this session runs 0.103; version.json was pulled back to 0.102, which an older session installed
        val f = fixture(tmp, current = "0.103", promoted = "0.102")
        Files.writeString(f.home.updateDir.resolve("updated-0.102"), "{}")

        f.updater.tick()

        assertTrue(f.home.updateDir.resolve("updated-0.102").exists(), "GC bound is min(current, promoted)")
        assertEquals(0, f.installerRuns.size, "a backward promotion is never applied")
    }

    @Test
    fun `gc ages out updated-below-current on the steady-state tick`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp, current = "0.102", promoted = "0.102")
        Files.writeString(f.home.updateDir.resolve("updated-0.101"), "{}")
        Files.writeString(f.home.updateDir.resolve("updated-0.102"), "{}")
        f.updater.tick()
        assertFalse(f.home.updateDir.resolve("updated-0.101").exists())
        assertTrue(f.home.updateDir.resolve("updated-0.102").exists(), "updated-<current> is kept one release")
    }
}

/** The skew-guard extraction contract, checked against the REAL installer-gen templates. */
class InstallScriptVersionParseTest {

    private fun templatesDir(): Path {
        // npx-kt tests run with user.dir = the module dir; the templates are a sibling module's
        // SOURCES (not build outputs), so this is a source-contract check, not a cross-build reach.
        var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("installer-gen/src/main/resources/templates")
            if (Files.isDirectory(candidate)) return candidate
            dir = dir.parent
        }
        throw IllegalStateException("installer-gen templates not found above ${System.getProperty("user.dir")}")
    }

    @Test
    fun `sh pattern matches the real template line shape`() {
        val template = Files.readString(templatesDir().resolve("install.sh.tmpl"))
        assertEquals("@@VERSION@@", parseInstallScriptVersion(template, isWin = false), "the anchored VERSION=' line must match the template")
        assertEquals("0.102", parseInstallScriptVersion(template.replace("@@VERSION@@", "0.102"), isWin = false))
    }

    @Test
    fun `ps1 pattern matches the real PADDED template line shape`() {
        val template = Files.readString(templatesDir().resolve("install.ps1.tmpl"))
        // the template pads with spaces before `=` — an exact-literal match would never fire
        assertEquals("@@VERSION@@", parseInstallScriptVersion(template, isWin = true))
        assertEquals("0.102", parseInstallScriptVersion(template.replace("@@VERSION@@", "0.102"), isWin = true))
    }

    @Test
    fun `blank or absent version lines do not parse`() {
        assertNull(parseInstallScriptVersion("#!/bin/sh\necho hi\n", isWin = false))
        assertNull(parseInstallScriptVersion("VERSION=''\n", isWin = false))
        assertNull(parseInstallScriptVersion("Write-Host hi\n", isWin = true))
    }
}

/** The passive-notice truth table (short CLI commands + opted-out sessions): exactly two file checks. */
class PassiveUpdateNoticeTest {

    private var now = 1_000_000_000_000L
    private val livePids = mutableSetOf(4242L)

    private fun coordination(dir: Path, pid: Long = 4242L) = UpdateCoordination(
        updateDir = dir,
        ownPid = pid,
        clock = { now },
        isPidAlive = { it in livePids },
    )

    @Test
    fun `in flight - silence`(@TempDir dir: Path) {
        livePids += 100L
        coordination(dir, pid = 100L)
            .writeInProgressMarker("0.102", UpdateStateInfo(pid = 100L, currentVersion = "0.101", targetVersion = "0.102", startedAt = now))

        assertEquals(
            PassiveUpdateNotice.NONE,
            passiveUpdateNotice(DevrigVersion.parse("0.102"), coordination(dir)),
        )
    }

    @Test
    fun `completed - restart notice`(@TempDir dir: Path) {
        val c = coordination(dir)
        c.writeUpdatedMarker("0.102", UpdateStateInfo(pid = 1L, currentVersion = "0.101", targetVersion = "0.102", startedAt = now))
        assertEquals(PassiveUpdateNotice.RESTART, passiveUpdateNotice(DevrigVersion.parse("0.102"), c))
    }

    @Test
    fun `nothing in flight, nothing completed - the plain banner`(@TempDir dir: Path) {
        assertEquals(
            PassiveUpdateNotice.DOWNLOAD_BANNER,
            passiveUpdateNotice(DevrigVersion.parse("0.102"), coordination(dir)),
        )
    }

    @Test
    fun `a DEAD updater does not silence the notice`(@TempDir dir: Path) {
        coordination(dir, pid = 100L) // pid 100 is NOT in livePids
            .writeInProgressMarker("0.102", UpdateStateInfo(pid = 100L, currentVersion = "0.101", targetVersion = "0.102", startedAt = now))
        assertEquals(
            PassiveUpdateNotice.DOWNLOAD_BANNER,
            passiveUpdateNotice(DevrigVersion.parse("0.102"), coordination(dir)),
        )
    }
}
