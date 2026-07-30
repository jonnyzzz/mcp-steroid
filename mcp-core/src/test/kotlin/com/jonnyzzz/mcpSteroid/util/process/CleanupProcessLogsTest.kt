/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.util.process

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.io.path.exists
import kotlin.time.Duration.Companion.days
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CleanupProcessLogsTest {
    @TempDir
    lateinit var logsDir: Path

    private val nowMillis = System.currentTimeMillis()

    // UTC — matches the production filename-timestamp contract (local zones are DST-ambiguous).
    private val nameFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT).withZone(ZoneOffset.UTC)

    /** Creates one run's files following the runner's filename grammar, aged [ageMillis] back from now. */
    private fun mkRun(tag: String, ageMillis: Long, seq: Int, vararg suffixes: String = arrayOf("command", "stdout")): List<Path> {
        val ts = nameFormat.format(Instant.ofEpochMilli(nowMillis - ageMillis))
        return suffixes.map { suffix ->
            val f = logsDir.resolve("process-$tag-$ts-11111-$seq-$suffix.log")
            Files.writeString(f, "x")
            f
        }
    }

    private fun hours(h: Long) = h * 3_600_000L
    private fun daysMs(d: Long) = d * 24 * 3_600_000L

    @Test
    fun `age pass deletes runs older than maxAge`() {
        val ancient = mkRun("ancient", ageMillis = daysMs(45), seq = 1)
        val fresh = mkRun("fresh", ageMillis = daysMs(1), seq = 2)

        val deleted = cleanupProcessLogs(logsDir, maxFiles = 100, trimTo = 100, maxAge = 30.days, nowMillis = nowMillis)

        assertEquals(2, deleted)
        ancient.forEach { assertFalse(it.exists(), "45-day-old $it must be deleted") }
        fresh.forEach { assertTrue(it.exists()) }
    }

    @Test
    fun `count pass trims oldest runs to trimTo keeping triples together`() {
        val oldest = mkRun("r1", ageMillis = hours(40), seq = 1)
        val older = mkRun("r2", ageMillis = hours(30), seq = 2)
        val newer = mkRun("r3", ageMillis = hours(20), seq = 3)
        val newest = mkRun("r4", ageMillis = hours(10), seq = 4)
        // 8 files total; maxFiles=6 triggers, trimTo=4 → the two oldest runs go, as whole pairs.

        cleanupProcessLogs(logsDir, maxFiles = 6, trimTo = 4, maxAge = 30.days, nowMillis = nowMillis)

        (oldest + older).forEach { assertFalse(it.exists(), "$it must be trimmed") }
        (newer + newest).forEach { assertTrue(it.exists(), "$it must survive") }
    }

    @Test
    fun `count pass is skipped below the maxFiles watermark`() {
        val runs = (1..3).flatMap { mkRun("r$it", ageMillis = hours(10L * it), seq = it) }
        cleanupProcessLogs(logsDir, maxFiles = 6, trimTo = 2, maxAge = 30.days, nowMillis = nowMillis)
        runs.forEach { assertTrue(it.exists(), "under the watermark nothing is trimmed: $it") }
    }

    @Test
    fun `count pass never deletes runs younger than the age floor`() {
        val young = (1..4).flatMap { mkRun("young$it", ageMillis = 60_000L * it, seq = it) } // minutes old

        cleanupProcessLogs(logsDir, maxFiles = 2, trimTo = 1, maxAge = 30.days, nowMillis = nowMillis)

        young.forEach { assertTrue(it.exists(), "sub-age-floor file must never be count-deleted: $it") }
    }

    @Test
    fun `ordering uses the filename timestamp not the file mtime`() {
        val oldByName = mkRun("oldname", ageMillis = hours(40), seq = 1)
        val newByName = mkRun("newname", ageMillis = hours(10), seq = 2)
        // Invert mtimes: the newest-named file gets the oldest mtime.
        newByName.forEach { Files.setLastModifiedTime(it, FileTime.fromMillis(nowMillis - daysMs(20))) }
        oldByName.forEach { Files.setLastModifiedTime(it, FileTime.fromMillis(nowMillis)) }

        cleanupProcessLogs(logsDir, maxFiles = 3, trimTo = 2, maxAge = 30.days, nowMillis = nowMillis)

        oldByName.forEach { assertFalse(it.exists(), "oldest BY NAME must be deleted despite fresh mtime") }
        newByName.forEach { assertTrue(it.exists(), "newest BY NAME must survive despite old mtime") }
    }

    @Test
    fun `equal-timestamp runs trim deterministically by pid then seq`() {
        // Same timestamp for all: ordering must fall back to pid, then seq — never Files.list order.
        val ts = nameFormat.format(Instant.ofEpochMilli(nowMillis - hours(20)))
        fun mkExact(pid: Long, seq: Int): Path =
            Files.writeString(logsDir.resolve("process-tie-$ts-$pid-$seq-command.log"), "x")
        val oldestPid = mkExact(pid = 100, seq = 9)
        val midSeq = mkExact(pid = 200, seq = 1)
        val newestSeq = mkExact(pid = 200, seq = 2)

        cleanupProcessLogs(logsDir, maxFiles = 2, trimTo = 2, maxAge = 30.days, nowMillis = nowMillis)

        assertFalse(oldestPid.exists(), "lowest pid trims first on a timestamp tie")
        assertTrue(midSeq.exists())
        assertTrue(newestSeq.exists())
    }

    @Test
    fun `marker throttle - scan is due without a marker, not due right after touching it`() {
        assertTrue(shouldRunProcessLogCleanup(logsDir, nowMillis), "no marker → a scan is due")
        touchProcessLogCleanupMarker(logsDir, nowMillis)
        assertFalse(shouldRunProcessLogCleanup(logsDir, nowMillis), "fresh marker → throttled")
        // Backdate the marker beyond the 4h throttle window.
        Files.setLastModifiedTime(logsDir.resolve(".process-cleanup-stamp"), FileTime.fromMillis(nowMillis - hours(5)))
        assertTrue(shouldRunProcessLogCleanup(logsDir, nowMillis), "stale marker → a scan is due again")
    }

    @Test
    fun `cleanup never touches files outside the runner grammar`() {
        val foreign = listOf(
            logsDir.resolve("devrig.log"),
            logsDir.resolve("process-foo.log"),           // no timestamp grammar
            logsDir.resolve("process-x.txt"),             // wrong extension
            logsDir.resolve(".process-cleanup-stamp"),    // the throttle marker itself
        ).map { Files.writeString(it, "x") }
        foreign.forEach { Files.setLastModifiedTime(it, FileTime.fromMillis(nowMillis - daysMs(400))) }
        val nestedDir = Files.createDirectory(logsDir.resolve("nested"))
        val nested = Files.writeString(
            nestedDir.resolve("process-deep-20200101-010101-000-1-1-command.log"), "x",
        )
        mkRun("real", ageMillis = daysMs(45), seq = 1)

        val deleted = cleanupProcessLogs(logsDir, maxFiles = 1, trimTo = 1, maxAge = 30.days, nowMillis = nowMillis)

        assertEquals(2, deleted, "only the one real ancient run (2 files) may be deleted")
        foreign.forEach { assertTrue(it.exists(), "foreign file must never be touched: $it") }
        assertTrue(nested.exists(), "cleanup must not recurse")
    }
}
