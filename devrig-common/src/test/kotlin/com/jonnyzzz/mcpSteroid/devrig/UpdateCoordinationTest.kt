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

    // ── filenames ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `marker filenames use the version as-is, prefix before dash or slash`(@TempDir dir: Path) {
        val c = coordination(dir)
        assertEquals("updated-0.102", c.updatedMarker("0.102").fileName.toString())
        assertEquals("updated-0.102.0", c.updatedMarker("0.102.0-r-abc1234").fileName.toString())
        assertEquals("update-4242-version-0.102", c.inProgressMarker("0.102").fileName.toString())

        assertEquals(4242L to "0.102", parseInProgressMarkerName("update-4242-version-0.102"))
        // the sibling marker families never parse as in-progress markers
        assertNull(parseInProgressMarkerName("updated-0.102"))

        assertEquals(4242L, parseScriptFilePid("install-4242.sh"))
        assertEquals(4242L, parseScriptFilePid("install-4242.ps1"))
        assertNull(parseScriptFilePid("install-4242.txt"))
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
        assertEquals(listOf(100L), c.liveInProgressPids(), "live filename pids, any version — the step-8 recheck input")

        livePids -= 100L
        assertFalse(c.anyLiveInProgressMarker(), "a dead-pid marker must not read as live")
        assertEquals(emptyList(), c.liveInProgressPids())
    }

    @Test
    fun `the 24h age bound overrides a live-looking pid (PID reuse, NFS)`(@TempDir dir: Path) {
        val other = coordination(dir, pid = 100L)
        livePids += 100L
        other.writeInProgressMarker("0.102", stateInfo(pid = 100L))
        Files.setLastModifiedTime(other.inProgressMarker("0.102"), java.nio.file.attribute.FileTime.fromMillis(now))

        val c = coordination(dir, pid = 200L)
        assertTrue(c.anyLiveInProgressMarker())
        now += UPDATE_STALE_AGE.inWholeMilliseconds + 1
        assertFalse(c.anyLiveInProgressMarker(), "the age bound applies EVEN when the pid reads live")
    }

    @Test
    fun `marker contents are never read - an unparsable file behaves exactly like a valid one`(@TempDir dir: Path) {
        // Liveness is decided ONLY by the pid in the FILENAME plus the file mtime; the JSON body is
        // write-only debugging information, so garbage content changes nothing.
        Files.createDirectories(dir)
        val raw = dir.resolve("update-999-version-0.102")
        Files.writeString(raw, "not json")
        Files.setLastModifiedTime(raw, java.nio.file.attribute.FileTime.fromMillis(now))
        livePids += 999L

        val c = coordination(dir, pid = 200L)
        assertTrue(c.anyLiveInProgressMarker(), "live filename pid + young mtime → live, content notwithstanding")
        now += UPDATE_STALE_AGE.inWholeMilliseconds + 1
        assertFalse(c.anyLiveInProgressMarker(), "the mtime age bound retires it")
        now -= UPDATE_STALE_AGE.inWholeMilliseconds + 1
        livePids -= 999L
        assertFalse(c.anyLiveInProgressMarker(), "a dead filename pid retires it even when young")
    }

    // ── the shared atomic write behind every marker ──────────────────────────────────────────────

    @Test
    fun `a marker write replaces the target and cleans its staging sibling up`(@TempDir dir: Path) {
        val c = coordination(dir)
        val target = c.updatedMarker("0.102")
        Files.writeString(target, "old")

        c.writeUpdatedMarker("0.102", stateInfo(target = "0.102"))

        assertEquals(updateJson.encodeToString(UpdateStateInfo.serializer(), stateInfo(target = "0.102")), Files.readString(target))
        val leftovers = Files.list(dir).use { paths ->
            paths.map { it.fileName.toString() }.filter { it != target.fileName.toString() }.toList()
        }
        assertEquals(emptyList(), leftovers, "no staging residue may remain next to the target")
        // A crash leftover inside the update dir must stay recognizable to the GC sweep.
        assertEquals(4242L, parseStagingFilePid(".tmp.4242.updated-0.102"))
    }

    @Test
    fun `a marker write creates the update directory itself`(@TempDir dir: Path) {
        // Callers (the devrig updater, the IDE plugin's DevrigSetup) must not have to pre-create
        // ~/.mcp-steroid/update — the first marker write is what brings the directory into being.
        val updateDir = dir.resolve("not").resolve("yet")
        assertFalse(updateDir.exists())
        val c = coordination(updateDir)

        c.writeInProgressMarker("0.102", stateInfo())

        assertEquals(updateJson.encodeToString(UpdateStateInfo.serializer(), stateInfo()), Files.readString(c.inProgressMarker("0.102")))
    }

    // ── completion record ────────────────────────────────────────────────────────────────────────

    @Test
    fun `hasUpdatedMarker is a plain name check`(@TempDir dir: Path) {
        val c = coordination(dir)
        c.writeUpdatedMarker("0.102", stateInfo(target = "0.102"))
        assertTrue(c.hasUpdatedMarker("0.102"))
        assertFalse(c.hasUpdatedMarker("0.103"))
    }

    // ── GC ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `gc bound is min(current, promoted) - a newer session never deletes the promoted record`(@TempDir dir: Path) {
        val logsDir = dir.resolve("logs")
        Files.createDirectories(logsDir)
        val c = coordination(dir)
        // the post-rollback state: this session runs 0.103, but version.json was pulled back to 0.102
        c.writeUpdatedMarker("0.102", stateInfo(target = "0.102"))
        c.writeUpdatedMarker("0.101", stateInfo(target = "0.101"))

        c.gc(current = DevrigVersion.parse("0.103.0-r-abc"), promoted = DevrigVersion.parse("0.102"), logsDir = logsDir)

        assertTrue(c.updatedMarker("0.102").exists(), "updated-<promoted> must survive a newer session's GC (rollback keep-case)")
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
        Files.setLastModifiedTime(oldLog, java.nio.file.attribute.FileTime.fromMillis(now - UPDATE_LOG_RETENTION.inWholeMilliseconds - 1))
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
