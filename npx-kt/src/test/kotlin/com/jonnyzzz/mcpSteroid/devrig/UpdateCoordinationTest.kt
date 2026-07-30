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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class UpdateCoordinationTest {

    private var now: Long = 1_000_000_000_000L
    private val livePids = mutableSetOf(4242L)

    private fun coordination(dir: Path, pid: Long = 4242L) = UpdateCoordination(
        updateDir = dir,
        ownPid = pid,
        clock = { now },
        isPidAlive = { it in livePids },
    )

    private fun stateInfo(pid: Long = 4242L, target: String = "0.102") = UpdateStateInfo(
        pid = pid,
        currentVersion = "0.101",
        targetVersion = target,
        startedAt = now,
    )

    // ── filenames + canonical version ────────────────────────────────────────────────────────────

    @Test
    fun `marker filenames use the canonical base version and parse round-trip`(@TempDir dir: Path) {
        val c = coordination(dir)
        // `devrig install devrig` runs as the full build string (incl. the #360 release lane
        // `<base>.0-r-<hash>`); the loop sees version.json's base form — both must land on the SAME file.
        assertEquals(c.updatedMarker("0.102.0"), c.updatedMarker("0.102.0-gh-abc1234"))
        assertEquals(c.updatedMarker("0.102"), c.updatedMarker("0.102.0-r-abc1234"))
        assertEquals("updated-0.102", c.updatedMarker("0.102.0-gh-abc1234").fileName.toString())
        assertEquals("update-4242-version-0.102", c.inProgressMarker("0.102.0").fileName.toString())

        assertEquals(4242L to "0.102", parseInProgressMarkerName("update-4242-version-0.102"))
        // versions with dots and dashes still parse: pid stops at the fixed `-version-` separator
        assertEquals(77L to "1.2.3-rc-1", parseInProgressMarkerName("update-77-version-1.2.3-rc-1"))
        // the sibling marker families never parse as in-progress markers
        assertNull(parseInProgressMarkerName("updated-0.102"))
        assertNull(parseInProgressMarkerName("update-failed-0.102"))

        assertEquals(4242L, parseScriptFilePid("install-4242.sh"))
        assertEquals(4242L, parseScriptFilePid("install-4242.ps1"))
        assertNull(parseScriptFilePid("install-4242.txt"))
    }

    @Test
    fun `base version strips build metadata, trailing zeros, and never takes the snapshot shortcut`() {
        assertEquals("0.101.441", baseVersionString("0.101.441-gh-abc1234"))
        assertEquals("0.101.441", baseVersionString("0.101.441"))
        // trailing zero components are canonicalized away: the #360 release build `<base>.0-r-<hash>`
        // and version.json's plain `<base>` must produce the SAME marker name
        assertEquals("0.102", baseVersionString("0.102.0-r-abc1234"))
        assertEquals("0.102", baseVersionString("0.102"))
        assertEquals("0.95", baseVersionString("0.95.0"))
        assertEquals("0.100", baseVersionString("0.100"))

        val snapshotBase = baseVersion("0.101.19999-SNAPSHOT-abc")
        assertFalse(snapshotBase.isSnapshotBuild)
        // with the shortcut stripped, plain numeric ordering applies: 0.102 wins over 0.101.19999
        assertTrue(baseVersion("0.102") > snapshotBase)
        // whereas the unstripped parse WOULD take the snapshot shortcut
        assertTrue(DevrigVersion.parse("0.101.19999-SNAPSHOT-abc") > DevrigVersion.parse("0.102"))
    }

    // ── the staleness rule (the only liveness machinery) ─────────────────────────────────────────

    @Test
    fun `in-progress marker liveness - live pid blocks, dead pid does not`(@TempDir dir: Path) {
        val other = coordination(dir, pid = 100L)
        livePids += 100L
        other.writeInProgressMarker("0.102", stateInfo(pid = 100L))

        val c = coordination(dir, pid = 200L)
        assertTrue(c.anyLiveInProgressMarker())
        assertTrue(c.isUpdateInFlight())

        livePids -= 100L
        assertFalse(c.anyLiveInProgressMarker(), "a dead-pid marker must not read as live")
    }

    @Test
    fun `the 24h age bound overrides a live-looking pid (PID reuse, NFS)`(@TempDir dir: Path) {
        val other = coordination(dir, pid = 100L)
        livePids += 100L
        other.writeInProgressMarker("0.102", stateInfo(pid = 100L))

        val c = coordination(dir, pid = 200L)
        assertTrue(c.anyLiveInProgressMarker())
        now += UPDATE_STALE_AGE_MILLIS + 1
        assertFalse(c.anyLiveInProgressMarker(), "the age bound applies EVEN when the pid reads live")
    }

    @Test
    fun `unparsable marker content falls back to mtime age`(@TempDir dir: Path) {
        Files.createDirectories(dir)
        val raw = dir.resolve("update-999-version-0.102")
        Files.writeString(raw, "not json")
        Files.setLastModifiedTime(raw, java.nio.file.attribute.FileTime.fromMillis(now))

        val c = coordination(dir, pid = 200L)
        assertTrue(c.anyLiveInProgressMarker(), "young unparsable marker is treated as live")
        now += UPDATE_STALE_AGE_MILLIS + 1
        assertFalse(c.anyLiveInProgressMarker())
    }

    // ── completion record ────────────────────────────────────────────────────────────────────────

    @Test
    fun `hasUpdatedMarker is ordering-based - any alias of the same release counts`(@TempDir dir: Path) {
        val c = coordination(dir)
        c.writeUpdatedMarker("0.102.0-r-abc1234", stateInfo(target = "0.102"))
        assertTrue(c.hasUpdatedMarker("0.102"))
        assertTrue(c.hasUpdatedMarker("0.102.0"))
        assertFalse(c.hasUpdatedMarker("0.103"))

        // a marker written under a NON-canonical alias still resolves
        Files.writeString(dir.resolve("updated-0.104.0"), "{}")
        assertTrue(c.hasUpdatedMarker("0.104"))
    }

    // ── failure counter ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `failure cap - 3 attempts per version, no spacing arm`(@TempDir dir: Path) {
        val c = coordination(dir)
        val v = "0.102"

        assertFalse(c.isFailureCapped(v))
        c.recordFailure(v, exitCode = 1)
        assertFalse(c.isFailureCapped(v), "no retry-spacing arm: one failure does not cap")
        c.recordFailure(v, exitCode = 1)
        assertFalse(c.isFailureCapped(v))
        c.recordFailure(v, exitCode = 7)
        assertTrue(c.isFailureCapped(v), "3 attempts cap the version")
        assertEquals(7, c.readFailure(v)?.lastExitCode)

        c.clearFailure(v)
        assertFalse(c.isFailureCapped(v))
    }

    // ── GC ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `gc bound is min(current, promoted) - a newer session never deletes the promoted records`(@TempDir dir: Path) {
        val logsDir = dir.resolve("logs")
        Files.createDirectories(logsDir)
        val c = coordination(dir)
        // the post-rollback state: this session runs 0.103, but version.json was pulled back to 0.102
        c.writeUpdatedMarker("0.102", stateInfo(target = "0.102"))
        Files.writeString(dir.resolve("update-failed-0.102"), "{}")
        c.writeUpdatedMarker("0.101", stateInfo(target = "0.101"))

        c.gc(current = DevrigVersion.parse("0.103.0-r-abc"), promoted = DevrigVersion.parse("0.102"), logsDir = logsDir)

        assertTrue(c.updatedMarker("0.102").exists(), "updated-<promoted> must survive a newer session's GC (rollback keep-case)")
        assertTrue(dir.resolve("update-failed-0.102").exists(), "update-failed-<promoted> must survive too")
        assertFalse(c.updatedMarker("0.101").exists(), "records below min(current, promoted) are swept")
    }

    @Test
    fun `gc sweeps stale per-pid files, superseded records, and old logs`(@TempDir dir: Path) {
        val logsDir = dir.resolve("logs")
        Files.createDirectories(logsDir)
        val c = coordination(dir, pid = 4242L)

        c.writeUpdatedMarker("0.100", stateInfo(target = "0.100"))
        c.writeUpdatedMarker("0.101", stateInfo(target = "0.101")) // == current: kept one release
        c.writeUpdatedMarker("0.102", stateInfo(target = "0.102")) // pending restart: kept

        val deadPidMarker = dir.resolve("update-999-version-0.102")
        Files.writeString(deadPidMarker, updateJson.encodeToString(UpdateStateInfo.serializer(), stateInfo(pid = 999L)))
        val deadPidScript = dir.resolve("install-999.sh")
        Files.writeString(deadPidScript, "#!/bin/sh")
        val ownScript = dir.resolve("install-4242.sh")
        Files.writeString(ownScript, "#!/bin/sh")

        val oldLog = logsDir.resolve("update-1-0.99.log")
        Files.writeString(oldLog, "old")
        Files.setLastModifiedTime(oldLog, java.nio.file.attribute.FileTime.fromMillis(now - UPDATE_LOG_RETENTION_MILLIS - 1))
        val freshLog = logsDir.resolve("update-2-0.101.log")
        Files.writeString(freshLog, "fresh")
        Files.setLastModifiedTime(freshLog, java.nio.file.attribute.FileTime.fromMillis(now))

        c.gc(current = DevrigVersion.parse("0.101.0-r-abc1234"), promoted = DevrigVersion.parse("0.102"), logsDir = logsDir)

        assertFalse(c.updatedMarker("0.100").exists(), "strictly older updated marker is swept")
        assertTrue(c.updatedMarker("0.101").exists(), "updated-<current> is kept one release")
        assertTrue(c.updatedMarker("0.102").exists(), "pending-restart marker is kept")
        assertFalse(deadPidMarker.exists(), "dead-pid in-progress marker is swept")
        assertFalse(deadPidScript.exists(), "dead-pid script is swept")
        assertTrue(ownScript.exists(), "own script must survive the sweep")
        assertFalse(oldLog.exists(), "log older than 30 days is swept")
        assertTrue(freshLog.exists())
    }
}
