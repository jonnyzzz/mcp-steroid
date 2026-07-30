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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class UpdateCoordinationTest {

    private var now: Long = 1_000_000_000_000L
    private val livePids = mutableSetOf<Long>(4242L)

    private fun coordination(dir: Path, pid: Long = 4242L, host: String = "test-host") = UpdateCoordination(
        updateDir = dir,
        ownPid = pid,
        hostname = host,
        clock = { now },
        isPidAlive = { it in livePids },
    )

    private fun stateInfo(pid: Long = 4242L, host: String = "test-host", target: String = "0.102.0") = UpdateStateInfo(
        pid = pid,
        hostname = host,
        currentVersion = "0.101.0",
        targetVersion = target,
        startedAt = now,
    )

    // ── filenames ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `marker filenames use the canonical base version and parse round-trip`(@TempDir dir: Path) {
        val c = coordination(dir)
        // `devrig install devrig` sees the full build string; the loop sees version.json's base form —
        // both must land on the SAME file.
        assertEquals(c.updatedMarker("0.102.0"), c.updatedMarker("0.102.0-gh-abc1234"))
        assertEquals("updated-0.102.0", c.updatedMarker("0.102.0-gh-abc1234").fileName.toString())
        assertEquals("update-4242-version-0.102.0", c.inProgressMarker("0.102.0").fileName.toString())

        assertEquals(4242L to "0.102.0", parseInProgressMarkerName("update-4242-version-0.102.0"))
        // versions with dots and dashes still parse: pid stops at the fixed `-version-` separator
        assertEquals(77L to "1.2.3-rc-1", parseInProgressMarkerName("update-77-version-1.2.3-rc-1"))
        // the sibling marker families never parse as in-progress markers
        assertNull(parseInProgressMarkerName("updated-0.102.0"))
        assertNull(parseInProgressMarkerName("update-failed-0.102.0"))
        assertNull(parseInProgressMarkerName("update-skew-0.102.0"))
        assertNull(parseInProgressMarkerName("lock"))

        assertEquals(4242L, parseScriptFilePid("install-4242.sh"))
        assertEquals(4242L, parseScriptFilePid("install-4242.ps1"))
        assertNull(parseScriptFilePid("install-4242.txt"))
    }

    // ── the lock ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `exclusive create - the second acquirer loses while the first owner is alive`(@TempDir dir: Path) {
        val a = coordination(dir, pid = 100L)
        val b = coordination(dir, pid = 200L)
        livePids += setOf(100L, 200L)

        assertTrue(a.tryAcquireLock(stateInfo(pid = 100L)))
        assertFalse(b.tryAcquireLock(stateInfo(pid = 200L)))

        // release is pid-checked: B must not delete A's lock
        b.releaseLock()
        assertTrue(dir.resolve("lock").exists(), "a foreign release must not delete the lock")
        a.releaseLock()
        assertFalse(dir.resolve("lock").exists())
    }

    @Test
    fun `dead same-host owner is reclaimed and the lock re-acquired`(@TempDir dir: Path) {
        val dead = coordination(dir, pid = 100L)
        livePids += 100L
        assertTrue(dead.tryAcquireLock(stateInfo(pid = 100L)))
        livePids -= 100L // owner crashed

        val next = coordination(dir, pid = 200L)
        livePids += 200L
        assertTrue(next.tryAcquireLock(stateInfo(pid = 200L)), "dead-pid lock must be reclaimed")
        assertEquals(200L, next.readLockInfo()?.pid)
    }

    @Test
    fun `live same-host owner is NOT reclaimed before the age bound, and IS after 24h`(@TempDir dir: Path) {
        val a = coordination(dir, pid = 100L)
        livePids += setOf(100L, 200L)
        assertTrue(a.tryAcquireLock(stateInfo(pid = 100L)))

        val b = coordination(dir, pid = 200L)
        assertFalse(b.tryAcquireLock(stateInfo(pid = 200L)))

        // the age bound applies EVEN when the pid reads live (PID reuse / wedged owner)
        now += UPDATE_STALE_AGE_MILLIS + 1
        assertTrue(b.tryAcquireLock(stateInfo(pid = 200L)))
        assertEquals(200L, b.readLockInfo()?.pid)
    }

    @Test
    fun `foreign-host lock ignores pid liveness and falls back to the age bound`(@TempDir dir: Path) {
        val remote = coordination(dir, pid = 100L, host = "other-host")
        assertTrue(remote.tryAcquireLock(stateInfo(pid = 100L, host = "other-host")))
        livePids -= 100L // pid 100 is dead LOCALLY, but the lock is from another host

        val local = coordination(dir, pid = 200L, host = "test-host")
        livePids += 200L
        assertFalse(local.tryAcquireLock(stateInfo(pid = 200L)), "foreign-host locks must not be liveness-reclaimed")
        now += UPDATE_STALE_AGE_MILLIS + 1
        assertTrue(local.tryAcquireLock(stateInfo(pid = 200L)))
    }

    @Test
    fun `unparsable lock content is reclaimed only via mtime age`(@TempDir dir: Path) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("lock"), "not json at all")
        // pin the mtime to "now" so the age math is deterministic
        Files.setLastModifiedTime(dir.resolve("lock"), java.nio.file.attribute.FileTime.fromMillis(now))

        val c = coordination(dir, pid = 200L)
        livePids += 200L
        assertFalse(c.tryAcquireLock(stateInfo(pid = 200L)), "young unparsable lock is treated as held")
        now += UPDATE_STALE_AGE_MILLIS + 1
        assertTrue(c.tryAcquireLock(stateInfo(pid = 200L)))
    }

    // ── completion markers ───────────────────────────────────────────────────────────────────────

    @Test
    fun `updated markers order by base version and read back their state`(@TempDir dir: Path) {
        val c = coordination(dir)
        c.writeUpdatedMarker("0.102.0-gh-abc", stateInfo(target = "0.102.0"))
        c.writeUpdatedMarker("0.101.5", stateInfo(target = "0.101.5"))

        assertEquals("0.102.0", c.newestUpdatedVersion()?.value)
        assertEquals(2, c.updatedVersions().size)

        c.deleteUpdatedMarker("0.102.0")
        assertEquals("0.101.5", c.newestUpdatedVersion()?.value)
    }

    // ── in-progress markers ──────────────────────────────────────────────────────────────────────

    @Test
    fun `in-progress marker liveness - live pid blocks, dead pid does not`(@TempDir dir: Path) {
        val other = coordination(dir, pid = 100L)
        livePids += 100L
        other.writeInProgressMarker("0.102.0", stateInfo(pid = 100L))

        val c = coordination(dir, pid = 200L)
        assertTrue(c.anyLiveInProgressMarker())

        livePids -= 100L
        assertFalse(c.anyLiveInProgressMarker(), "a dead-pid marker must not read as live")
    }

    // ── failure + skew counters ──────────────────────────────────────────────────────────────────

    @Test
    fun `failure cap - 3 per window with 1h spacing, decay resets the window, lifetime cap persists`(@TempDir dir: Path) {
        val c = coordination(dir)
        val v = "0.102.0"

        assertFalse(c.isFailureCapped(v))
        c.recordFailure(v, exitCode = 1)
        assertTrue(c.isFailureCapped(v), "a just-failed attempt is capped by the 1h spacing")
        now += UPDATE_MIN_RETRY_INTERVAL_MILLIS + 1
        assertFalse(c.isFailureCapped(v))

        c.recordFailure(v, exitCode = 1)
        now += UPDATE_MIN_RETRY_INTERVAL_MILLIS + 1
        c.recordFailure(v, exitCode = 1)
        now += UPDATE_MIN_RETRY_INTERVAL_MILLIS + 1
        assertTrue(c.isFailureCapped(v), "3 attempts in the window cap the version")

        // the 7-day decay re-opens the window…
        now += UPDATE_FAILURE_WINDOW_MILLIS + 1
        assertFalse(c.isFailureCapped(v))

        // …but lifetimeAttempts survives the decay and caps permanently at 9
        repeat(6) {
            c.recordFailure(v, exitCode = 1)
            now += UPDATE_FAILURE_WINDOW_MILLIS + 1
        }
        assertEquals(9, c.readFailure(v)?.lifetimeAttempts)
        assertTrue(c.isFailureCapped(v), "9 lifetime attempts cap the version for good")

        c.clearFailure(v)
        assertFalse(c.isFailureCapped(v))
    }

    @Test
    fun `skew cap - 3 attempts or 24h since first seen`(@TempDir dir: Path) {
        val c = coordination(dir)
        val v = "0.102.0"

        assertFalse(c.isSkewCapped(v))
        c.recordSkew(v, parsedVersion = "0.101.9")
        c.recordSkew(v, parsedVersion = "0.101.9")
        assertFalse(c.isSkewCapped(v))
        c.recordSkew(v, parsedVersion = "0.101.9")
        assertTrue(c.isSkewCapped(v))
        assertEquals("0.101.9", c.readSkew(v)?.parsedVersion)

        c.clearSkew(v)
        assertFalse(c.isSkewCapped(v))
        c.recordSkew(v, parsedVersion = null)
        now += UPDATE_STALE_AGE_MILLIS + 1
        assertTrue(c.isSkewCapped(v), "24h since firstSeenAt caps even a single skew")
    }

    // ── GC ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `gc deletes strictly-older updated markers, superseded counters, stale per-pid files and old logs`(@TempDir dir: Path) {
        val logsDir = dir.resolve("logs")
        Files.createDirectories(logsDir)
        val c = coordination(dir, pid = 4242L)

        c.writeUpdatedMarker("0.100.0", stateInfo(target = "0.100.0"))
        c.writeUpdatedMarker("0.101.0", stateInfo(target = "0.101.0")) // == current: kept as flip-back evidence
        c.writeUpdatedMarker("0.102.0", stateInfo(target = "0.102.0")) // pending restart: kept
        c.recordFailure("0.100.0", exitCode = 1)
        c.recordSkew("0.100.0", parsedVersion = null)

        val deadPidMarker = dir.resolve("update-999-version-0.102.0")
        Files.writeString(deadPidMarker, updateJson.encodeToString(UpdateStateInfo.serializer(), stateInfo(pid = 999L)))
        val deadPidScript = dir.resolve("install-999.sh")
        Files.writeString(deadPidScript, "#!/bin/sh")
        val ownScript = dir.resolve("install-4242.sh")
        Files.writeString(ownScript, "#!/bin/sh")

        val oldLog = logsDir.resolve("update-1-0.99.0.log")
        Files.writeString(oldLog, "old")
        Files.setLastModifiedTime(oldLog, java.nio.file.attribute.FileTime.fromMillis(now - UPDATE_LOG_RETENTION_MILLIS - 1))
        val freshLog = logsDir.resolve("update-2-0.101.0.log")
        Files.writeString(freshLog, "fresh")
        Files.setLastModifiedTime(freshLog, java.nio.file.attribute.FileTime.fromMillis(now))

        c.gc(current = DevrigVersion.parse("0.101.0-gh-abc1234"), logsDir = logsDir)

        assertFalse(c.updatedMarker("0.100.0").exists(), "strictly older updated marker is swept")
        assertTrue(c.updatedMarker("0.101.0").exists(), "updated-<current> is kept one release")
        assertTrue(c.updatedMarker("0.102.0").exists(), "pending-restart marker is kept")
        assertFalse(c.failureMarker("0.100.0").exists(), "superseded failure counter is swept")
        assertFalse(c.skewMarker("0.100.0").exists(), "superseded skew counter is swept")
        assertFalse(deadPidMarker.exists(), "dead-pid in-progress marker is swept")
        assertFalse(deadPidScript.exists(), "dead-pid script is swept")
        assertTrue(ownScript.exists(), "own script must survive the sweep")
        assertFalse(oldLog.exists(), "log older than 30 days is swept")
        assertTrue(freshLog.exists())
    }

    // ── base-version helpers ─────────────────────────────────────────────────────────────────────

    @Test
    fun `base version strips build metadata and never takes the snapshot shortcut`() {
        assertEquals("0.101.441", baseVersionString("0.101.441-gh-abc1234"))
        assertEquals("0.101.441", baseVersionString("0.101.441"))
        // A SNAPSHOT build's base must NOT compare as "newer than everything" in marker ordering —
        // that shortcut would let a dev build shadow real releases.
        val snapshotBase = baseVersion("0.101.19999-SNAPSHOT-abc")
        assertFalse(snapshotBase.isSnapshotBuild)
        // with the shortcut stripped, plain numeric ordering applies: 0.102.0 wins over 0.101.19999
        assertTrue(baseVersion("0.102.0") > snapshotBase)
        // whereas the unstripped parse WOULD take the snapshot shortcut
        assertTrue(DevrigVersion.parse("0.101.19999-SNAPSHOT-abc") > DevrigVersion.parse("0.102.0"))
        assertNotNull(snapshotBase)
    }
}
