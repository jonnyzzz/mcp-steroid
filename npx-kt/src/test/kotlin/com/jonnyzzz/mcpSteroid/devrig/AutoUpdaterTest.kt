/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.util.text.DevrigVersion
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
        current: String = "0.101.0",
        promoted: String? = "0.102.0",
        launcher: String? = "0.101.0",
        scriptBody: (String) -> String = { "VERSION='$it'\n" },
        installerExit: Int? = 0,
        installerWritesMarker: Boolean = true,
        selfHeals: MutableList<Unit> = mutableListOf(),
    ): Fixture {
        val home = HomePaths(tmp.resolve("home/.mcp-steroid"))
        home.mkdirsAll()
        val coordination = UpdateCoordination(
            updateDir = home.updateDir,
            ownPid = 4242L,
            hostname = "test-host",
            clock = { now },
            isPidAlive = { it in livePids },
        )
        val notices = mutableListOf<String>()
        val installerRuns = mutableListOf<Path>()
        val triggeredVersions = mutableListOf<String>()
        var launcherVersion = launcher
        val updater = AutoUpdater(
            homePaths = home,
            currentVersion = DevrigVersion.parse(current),
            isWin = false,
            coordination = coordination,
            notify = { notices += it },
            fetchPromoted = { promoted?.let { DevrigVersion.parse(it) } },
            downloadScript = { _, target ->
                Files.createDirectories(target.parent)
                Files.writeString(target, scriptBody(promoted ?: ""))
                true
            },
            launcherVersion = { launcherVersion?.let { DevrigVersion.parse(it) } },
            selfHealLauncher = { selfHeals += Unit },
            runInstaller = { script, logFile ->
                installerRuns.add(script) // .add, not `+=`: Path is Iterable<Path>, which makes plusAssign ambiguous
                Files.createDirectories(logFile.parent)
                Files.writeString(logFile, "fake installer ran\n")
                if (installerExit == 0 && installerWritesMarker) {
                    // the real `devrig install devrig` handoff attests the marker + repoints the launcher
                    val target = baseVersionString(promoted ?: "")
                    coordination.writeUpdatedMarker(
                        target,
                        UpdateStateInfo(pid = 1L, hostname = "test-host", currentVersion = target, targetVersion = target, startedAt = now),
                    )
                    launcherVersion = target
                }
                installerExit
            },
            noAutoUpdateEnv = null,
            binRegisterOptOutEnv = null,
            onUpdateTriggered = { triggeredVersions += it },
        )
        return Fixture(updater, home, notices, installerRuns, triggeredVersions)
    }

    @Test
    fun `happy path - installs, cleans up, and proposes a restart exactly once`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp)

        f.updater.tick()

        assertEquals(1, f.installerRuns.size)
        // telemetry fires once per actually-triggered update, with the raw version.json version
        assertEquals(listOf("0.102.0"), f.triggeredVersions)
        assertTrue(f.home.updateDir.resolve("updated-0.102").exists())
        assertFalse(f.home.updateDir.resolve("lock").exists(), "the lock is released in the finally")
        assertFalse(f.home.updateDir.resolve("update-4242-version-0.102").exists(), "the per-pid marker exists only while updating")
        assertFalse(f.home.updateDir.resolve("install-4242.sh").exists(), "the downloaded script is deleted after the run")
        assertEquals(1, f.notices.size)
        assertTrue(f.notices[0].contains("restart"), f.notices[0])
        assertTrue(f.notices[0].contains("0.102"), f.notices[0])

        // second tick: restart is pending, launcher confirms → no reinstall, no duplicate notice
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

        val release = fixture(tmp.resolve("b"), current = "0.101.0")
        assertTrue(release.updater.isActive())
        assertFalse(
            AutoUpdater(homePaths = release.home, currentVersion = DevrigVersion.parse("0.101.0"), noAutoUpdateEnv = "yes", binRegisterOptOutEnv = null).isActive(),
            "DEVRIG_NO_AUTO_UPDATE opts out",
        )
        assertFalse(
            AutoUpdater(homePaths = release.home, currentVersion = DevrigVersion.parse("0.101.0"), noAutoUpdateEnv = null, binRegisterOptOutEnv = "1").isActive(),
            "the launcher-write opt-out also disables the updater (every install would fail its verify step)",
        )
    }

    @Test
    fun `no update promoted - nothing happens`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp, current = "0.102.0", promoted = "0.102.0")
        f.updater.tick()
        assertEquals(0, f.installerRuns.size)
        assertEquals(0, f.notices.size)
        // a promoted version moving BACKWARD is never auto-applied either
        val back = fixture(tmp.resolve("b"), current = "0.102.0", promoted = "0.101.0")
        back.updater.tick()
        assertEquals(0, back.installerRuns.size)
    }

    @Test
    fun `in-flight lock elsewhere - stop silently, no notification`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp)
        livePids += 100L
        UpdateCoordination(f.home.updateDir, ownPid = 100L, hostname = "test-host", clock = { now }, isPidAlive = { it in livePids })
            .tryAcquireLock(UpdateStateInfo(pid = 100L, hostname = "test-host", currentVersion = "0.101.0", targetVersion = "0.102.0", startedAt = now))

        f.updater.tick()

        assertEquals(0, f.installerRuns.size)
        assertEquals(0, f.notices.size, "no update notification while an install is in flight")
    }

    @Test
    fun `torn marker - updated exists but the launcher is older - marker deleted and installer re-runs`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp, launcher = "0.101.0")
        // a flip-back landed after an earlier install: marker says 0.102.0, launcher still at 0.101.0
        Files.writeString(f.home.updateDir.resolve("updated-0.102"), "{}")

        f.updater.tick()

        assertEquals(1, f.installerRuns.size, "the torn state must self-heal by re-running the installer")
        assertTrue(f.notices.any { it.contains("restart") })
    }

    @Test
    fun `flip-back below current - launcher self-heal runs`(@TempDir tmp: Path) = runTest {
        val heals = mutableListOf<Unit>()
        val f = fixture(tmp, current = "0.102.0", promoted = "0.102.0", launcher = "0.101.0", selfHeals = heals)
        f.updater.tick()
        assertEquals(1, heals.size, "a launcher older than the RUNNING binary is re-healed on the spot")
    }

    @Test
    fun `skew - script serves a different version - quiet bounded retry, then the manual notice`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp, scriptBody = { "VERSION='0.101.9'\n" }) // CDN still serves the previous release

        f.updater.tick()
        assertEquals(0, f.installerRuns.size)
        assertEquals(0, f.triggeredVersions.size, "an aborted update must not report a trigger")
        assertEquals(0, f.notices.size, "skew aborts are quiet while bounded")
        assertNotNull(f.updater.coordination.readSkew("0.102.0"))

        f.updater.tick()
        f.updater.tick()
        assertEquals(0, f.installerRuns.size)
        // the 3rd recorded skew caps it → the NEXT tick reports the diagnosis
        f.updater.tick()
        assertEquals(1, f.notices.size)
        assertTrue(f.notices[0].contains("0.101.9"), "the skew diagnosis names the served version: ${f.notices[0]}")
    }

    @Test
    fun `unparsable script - counts as a failed attempt, not a silent loop`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp, scriptBody = { "#!/bin/sh\necho no version line here\n" })
        f.updater.tick()
        assertEquals(0, f.installerRuns.size)
        assertEquals(1, f.updater.coordination.readFailure("0.102.0")?.attempts)
    }

    @Test
    fun `failing installer - records the exit code and retries only after the 1h spacing`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp, installerExit = 7)

        f.updater.tick()
        assertEquals(1, f.installerRuns.size)
        val failure = f.updater.coordination.readFailure("0.102.0")
        assertEquals(7, failure?.lastExitCode)
        assertFalse(f.home.updateDir.resolve("lock").exists(), "the lock is released after a failure")

        f.updater.tick()
        assertEquals(1, f.installerRuns.size, "the 1h spacing blocks an immediate retry")
        now += UPDATE_MIN_RETRY_INTERVAL_MILLIS + 1
        f.updater.tick()
        assertEquals(2, f.installerRuns.size)
    }

    @Test
    fun `exit 0 without a marker - launcher stamp decides between fallback marker and failed attempt`(@TempDir tmp: Path) = runTest {
        // installer "succeeds" but never writes the marker AND the launcher still shows the old version
        val f = fixture(tmp, installerExit = 0, installerWritesMarker = false, launcher = "0.101.0")
        f.updater.tick()
        assertNull(f.updater.coordination.readUpdatedMarker("0.102.0"), "no unguarded fallback marker")
        assertEquals(0, f.updater.coordination.readFailure("0.102.0")?.lastExitCode)
        assertEquals(0, f.notices.size, "no spurious restart notice for an install that did not land")
    }

    @Test
    fun `gc keeps updated-current as flip-back evidence and drops older ones`(@TempDir tmp: Path) = runTest {
        val f = fixture(tmp, current = "0.101.0", promoted = "0.101.0")
        Files.writeString(f.home.updateDir.resolve("updated-0.100.0"), "{}")
        Files.writeString(f.home.updateDir.resolve("updated-0.101.0"), "{}")
        f.updater.tick()
        assertFalse(f.home.updateDir.resolve("updated-0.100.0").exists())
        assertTrue(f.home.updateDir.resolve("updated-0.101.0").exists())
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
        assertEquals("0.102.0", parseInstallScriptVersion(template.replace("@@VERSION@@", "0.102.0"), isWin = false))
    }

    @Test
    fun `ps1 pattern matches the real PADDED template line shape`() {
        val template = Files.readString(templatesDir().resolve("install.ps1.tmpl"))
        // the template pads with spaces before `=` — an exact-literal match would never fire
        assertEquals("@@VERSION@@", parseInstallScriptVersion(template, isWin = true))
        assertEquals("0.102.0", parseInstallScriptVersion(template.replace("@@VERSION@@", "0.102.0"), isWin = true))
    }

    @Test
    fun `blank or absent version lines do not parse`() {
        assertNull(parseInstallScriptVersion("#!/bin/sh\necho hi\n", isWin = false))
        assertNull(parseInstallScriptVersion("VERSION=''\n", isWin = false))
        assertNull(parseInstallScriptVersion("Write-Host hi\n", isWin = true))
    }
}

/** The passive-notice truth table (short CLI commands + opted-out sessions). */
class PassiveUpdateNoticeTest {

    private var now = 1_000_000_000_000L
    private val livePids = mutableSetOf(4242L)

    private fun coordination(dir: Path) = UpdateCoordination(
        updateDir = dir,
        ownPid = 4242L,
        hostname = "test-host",
        clock = { now },
        isPidAlive = { it in livePids },
    )

    @Test
    fun `in flight - silence`(@TempDir dir: Path) {
        val c = coordination(dir)
        livePids += 100L
        UpdateCoordination(dir, ownPid = 100L, hostname = "test-host", clock = { now }, isPidAlive = { it in livePids })
            .tryAcquireLock(UpdateStateInfo(pid = 100L, hostname = "test-host", currentVersion = "0.101.0", targetVersion = "0.102.0", startedAt = now))

        assertEquals(
            PassiveUpdateNotice.NONE,
            passiveUpdateNotice(DevrigVersion.parse("0.102.0"), c, launcherVersion = DevrigVersion.parse("0.101.0")),
        )
    }

    @Test
    fun `completed and launcher confirms - restart`(@TempDir dir: Path) {
        val c = coordination(dir)
        c.writeUpdatedMarker("0.102.0", UpdateStateInfo(pid = 1L, hostname = "test-host", currentVersion = "0.101.0", targetVersion = "0.102.0", startedAt = now))
        assertEquals(
            PassiveUpdateNotice.RESTART,
            passiveUpdateNotice(DevrigVersion.parse("0.102.0"), c, launcherVersion = DevrigVersion.parse("0.102.0")),
        )
    }

    @Test
    fun `completed but the launcher disagrees - banner, never trust the marker over the launcher`(@TempDir dir: Path) {
        val c = coordination(dir)
        c.writeUpdatedMarker("0.102.0", UpdateStateInfo(pid = 1L, hostname = "test-host", currentVersion = "0.101.0", targetVersion = "0.102.0", startedAt = now))
        assertEquals(
            PassiveUpdateNotice.DOWNLOAD_BANNER,
            passiveUpdateNotice(DevrigVersion.parse("0.102.0"), c, launcherVersion = DevrigVersion.parse("0.101.0")),
        )
    }

    @Test
    fun `nothing in flight, nothing completed - the plain banner`(@TempDir dir: Path) {
        assertEquals(
            PassiveUpdateNotice.DOWNLOAD_BANNER,
            passiveUpdateNotice(DevrigVersion.parse("0.102.0"), coordination(dir), launcherVersion = DevrigVersion.parse("0.101.0")),
        )
    }

    @Test
    fun `a DEAD updater does not silence the notice`(@TempDir dir: Path) {
        val c = coordination(dir)
        UpdateCoordination(dir, ownPid = 100L, hostname = "test-host", clock = { now }, isPidAlive = { it in livePids })
            .tryAcquireLock(UpdateStateInfo(pid = 100L, hostname = "test-host", currentVersion = "0.101.0", targetVersion = "0.102.0", startedAt = now))
        // pid 100 is NOT in livePids → the lock reads dead → not in flight
        assertEquals(
            PassiveUpdateNotice.DOWNLOAD_BANNER,
            passiveUpdateNotice(DevrigVersion.parse("0.102.0"), c, launcherVersion = DevrigVersion.parse("0.101.0")),
        )
    }
}
