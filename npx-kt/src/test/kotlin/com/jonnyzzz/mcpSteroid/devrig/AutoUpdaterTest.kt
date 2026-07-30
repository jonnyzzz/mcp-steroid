/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.util.text.DevrigVersion
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        val installerLogs: MutableList<Path>,
        val updateEvents: MutableList<String>,
    )

    private fun fixture(
        tmp: Path,
        current: String = "0.101",
        promoted: String? = "0.102",
        downloadSucceeds: Boolean = true,
        installerExit: Int? = 0,
        pid: Long = 4242L,
        clock: () -> Long = { now },
        home: HomePaths = HomePaths(tmp.resolve("home/.mcp-steroid")),
        downloadScript: (suspend (String, Path) -> Boolean)? = null,
    ): Fixture {
        home.mkdirsAll()
        val coordination = UpdateCoordination(
            updateDir = home.updateDir,
            ownPid = pid,
            clock = clock,
            isPidAlive = { it in livePids },
        )
        val notices = mutableListOf<String>()
        val installerRuns = mutableListOf<Path>()
        val installerLogs = mutableListOf<Path>()
        val updateEvents = mutableListOf<String>()
        val updater = AutoUpdater(
            homePaths = home,
            currentVersion = DevrigVersion.parse(current),
            isWin = false,
            coordination = coordination,
            notify = { notices += it },
            fetchPromoted = { promoted?.let { DevrigVersion.parse(it) } },
            downloadScript = downloadScript ?: { _, target ->
                if (downloadSucceeds) {
                    Files.createDirectories(target.parent)
                    // the content is opaque to the updater — it is never parsed, only executed
                    Files.writeString(target, "#!/bin/sh\necho fake install script\n")
                }
                downloadSucceeds
            },
            runInstaller = { script, logFile ->
                installerRuns.add(script) // .add, not `+=`: Path is Iterable<Path>, which makes plusAssign ambiguous
                installerLogs.add(logFile)
                Files.createDirectories(logFile.parent)
                Files.writeString(logFile, "fake installer ran\n")
                installerExit
            },
            noAutoUpdateEnv = null,
            binRegisterOptOutEnv = null,
            onUpdateEvent = { phase, promoted, exitCode -> updateEvents += "$phase:$promoted" + (exitCode?.let { ":$it" } ?: "") },
        )
        return Fixture(updater, home, notices, installerRuns, installerLogs, updateEvents)
    }

    @Test
    fun `happy path - installs, supervisor writes the record, cleans up, proposes a restart once`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp)

        f.updater.tick()

        assertEquals(1, f.installerRuns.size)
        // the per-pid log naming keeps concurrent devrig processes clash-free
        assertEquals(listOf(f.home.logsDir.resolve("update-4242-0.102.log")), f.installerLogs)
        // telemetry lifecycle: started when the installer spawns, completed on exit 0
        assertEquals(listOf("started:0.102", "completed:0.102:0"), f.updateEvents)
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
        // a record far below any GC bound: a gated tick must not GC either (the WHOLE tick is off)
        Files.writeString(snapshot.home.updateDir.resolve("updated-0.001"), "{}")
        snapshot.updater.tick()
        assertEquals(0, snapshot.installerRuns.size)
        assertTrue(snapshot.home.updateDir.resolve("updated-0.001").exists(), "a SNAPSHOT build never GCs")

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
    fun `announce race - both announce in the same window, the lowest pid wins`(@TempDir tmp: Path) = runTest {
        livePids += 100L

        // Higher pid (4242) loses. The rival (pid 100) announces inside the step-5 → step-8
        // window — simulated at the tick's FIRST clock read, which is step 7's `startedAt`: after
        // the step-5 scan, before our own marker hits the disk. logsDir is removed so the step-3
        // log sweep does not read the clock earlier (that would announce the rival BEFORE step 5
        // and exercise the step-5 yield instead of the step-8 recheck).
        val home = HomePaths(tmp.resolve("home/.mcp-steroid"))
        val rival = UpdateCoordination(home.updateDir, ownPid = 100L, clock = { now }, isPidAlive = { it in livePids })
        var rivalAnnounced = false
        val higher = fixture(tmp, clock = {
            if (!rivalAnnounced) {
                rivalAnnounced = true
                rival.writeInProgressMarker("0.102", UpdateStateInfo(pid = 100L, currentVersion = "0.101", targetVersion = "0.102", startedAt = now))
            }
            now
        })
        Files.delete(higher.home.logsDir)

        higher.updater.tick()

        assertTrue(rivalAnnounced, "the tick must have passed step 5 and reached its own announce")
        assertEquals(0, higher.installerRuns.size, "the higher pid yields to the lower-pid announcer")
        assertEquals(0, higher.notices.size, "the loser yields silently")
        assertEquals(0, higher.updateEvents.size, "a yielded update must not report any lifecycle event")
        assertFalse(higher.home.updateDir.resolve("update-4242-version-0.102").exists(), "the loser deletes its OWN marker")
        assertTrue(higher.home.updateDir.resolve("update-100-version-0.102").exists(), "the winner's marker is never touched")

        // Lower pid (100) wins: the higher-pid rival (4242) announces in the same window; the
        // recheck sees no LOWER live pid and proceeds with the install.
        val homeB = HomePaths(tmp.resolve("b/home/.mcp-steroid"))
        val rivalB = UpdateCoordination(homeB.updateDir, ownPid = 4242L, clock = { now }, isPidAlive = { it in livePids })
        var rivalBAnnounced = false
        val lower = fixture(tmp.resolve("b"), pid = 100L, clock = {
            if (!rivalBAnnounced) {
                rivalBAnnounced = true
                rivalB.writeInProgressMarker("0.102", UpdateStateInfo(pid = 4242L, currentVersion = "0.101", targetVersion = "0.102", startedAt = now))
            }
            now
        })
        Files.delete(lower.home.logsDir)

        lower.updater.tick()

        assertTrue(rivalBAnnounced)
        assertEquals(1, lower.installerRuns.size, "the lowest announced pid proceeds")
        assertTrue(lower.home.updateDir.resolve("updated-0.102").exists())
        assertEquals(1, lower.notices.size)
    }

    @Test
    fun `download failure - quiet retry, no state left behind`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp, downloadSucceeds = false)
        f.updater.tick()
        f.updater.tick()
        assertEquals(0, f.installerRuns.size)
        assertEquals(0, f.notices.size)
        assertEquals(0, f.updateEvents.size, "an aborted update must not report any lifecycle event")
        assertFalse(f.home.updateDir.resolve("update-4242-version-0.102").exists(), "the marker is cleaned in the finally")
    }

    @Test
    fun `failing installer - retries on EVERY tick, never gives up, never nags`(@TempDir tmp: Path) = runTest {
        // No failure tracking by design: too many transient root causes; the schedule (3-8h ticks)
        // is the pacing, and the goal is to keep users up to date.
        val f = fixture(tmp, installerExit = 7)

        repeat(5) { f.updater.tick() }

        assertEquals(5, f.installerRuns.size, "every scheduled tick retries; there is no cap")
        assertEquals(List(5) { "started:0.102" to "failed:0.102:7" }.flatMap { it.toList() }, f.updateEvents,
            "each attempt reports started then failed with the exit code")
        assertEquals(0, f.notices.size, "failures never produce a user-facing notice (stderr + logs only)")
        assertFalse(f.home.updateDir.resolve("update-failed-0.102").exists(), "no failure state is ever written")
    }

    @Test
    fun `a lower-pid rival announcing DURING the download wins - installer never spawns`(@TempDir tmp: Path) = runTest {
        val home = HomePaths(tmp.resolve("home/.mcp-steroid"))
        val rival = UpdateCoordination(home.updateDir, ownPid = 100L, clock = { now }, isPidAlive = { it in livePids })
        livePids += 100L
        val f = fixture(tmp, home = home, downloadScript = { _, target ->
            // the rival announces while OUR download is in flight
            rival.writeInProgressMarker("0.102", UpdateStateInfo(pid = 100L, currentVersion = "0.101", targetVersion = "0.102", startedAt = now))
            Files.createDirectories(target.parent)
            Files.writeString(target, "#!/bin/sh\necho fake install script\n")
            true
        })

        f.updater.tick()

        assertEquals(0, f.installerRuns.size, "the post-download recheck must yield before spawning")
        assertEquals(0, f.updateEvents.size, "no lifecycle event for a yielded update")
        assertFalse(f.home.updateDir.resolve("update-4242-version-0.102").exists(), "own marker cleaned on yield")
        assertFalse(f.home.updateDir.resolve("install-4242.sh").exists(), "downloaded script cleaned on yield")
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
