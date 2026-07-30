/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.util.process

import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.nanoseconds
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.util.process.ProcessRun")

private val runSequence = AtomicInteger()
private val cleanupTriggered = AtomicBoolean()

private val FILE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT)

/** The complete filename grammar of this runner's files — cleanup deletes NOTHING else. */
private val RUNNER_FILE_GRAMMAR = Regex("^process-.+-(\\d{8}-\\d{6}-\\d{3})-\\d+-\\d+-(command|stdout|stderr)\\.log$")

private const val CLEANUP_MARKER_FILE = ".process-cleanup-stamp"
private val CLEANUP_THROTTLE = 4.hours

/** The count pass never deletes runs younger than this — protects live concurrent runs. */
private val COUNT_PASS_AGE_FLOOR = 1.hours

/**
 * Runs the process to completion. Blocking; safe to call from any thread.
 * See docs/design/process-runner-api.md (v6) for the contract:
 * - stdin from [nullDevice] (the child sees immediate EOF);
 * - stdout/stderr redirected to log files in [ProcessRunSpec.logsDir]
 *   (merged into one file by default);
 * - the command log records the invocation before start and the outcome
 *   after; the runner references it via slf4j (DEBUG lifecycle, WARN for
 *   kills/losses); System.out is never touched;
 * - `waitFor(timeout)` IS the timeout enforcement — on expiry the child
 *   tree is killed (bounded, best-effort) and [ProcessTimeoutException]
 *   is thrown with the files kept;
 * - on caller interruption the child tree is killed, the interrupt flag is
 *   restored, and the [InterruptedException] propagates;
 * - log-folder cleanup runs at most once per JVM, throttled by a marker
 *   file — see [cleanupProcessLogs].
 *
 * @throws ProcessTimeoutException on timeout (child tree killed, files kept)
 * @throws ProcessStartException when the process cannot be started
 * @throws InterruptedException when the calling thread is interrupted
 * @throws java.io.IOException only for pre-start infrastructure failure
 *   (logsDir / command log not creatable — nothing was written)
 */
fun runProcess(spec: ProcessRunSpec): ProcessRunResult {
    maybeCleanupOncePerJvm(spec.logsDir)

    val name = sanitizeProcessName(spec.name ?: Path.of(spec.command.first()).fileName?.toString().orEmpty())
    val logs = allocateLogFiles(spec, name)
    log.debug("starting process {}; command log: {}", name, logs.commandLog)

    val builder = ProcessBuilder(spec.command)
    spec.workingDir?.let { builder.directory(it.toFile()) }
    builder.environment().putAll(spec.environment)
    builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
    builder.redirectOutput(ProcessBuilder.Redirect.to(logs.stdoutLog.toFile()))
    if (spec.mergeStderrIntoStdout) {
        builder.redirectErrorStream(true)
    } else {
        builder.redirectError(ProcessBuilder.Redirect.to(checkNotNull(logs.stderrLog).toFile()))
    }

    val process = try {
        builder.start()
    } catch (e: IOException) {
        appendCommandLog(logs, "exit:     START-FAILED: ${e.message}")
        log.warn("process {} failed to start ({}); command log kept: {}", name, e.toString(), logs.commandLog)
        throw ProcessStartException("process '$name' failed to start: ${e.message}", name, logs, e)
    }
    val pid = process.pid()
    val startNanos = System.nanoTime()

    try {
        val finished = process.waitFor(spec.timeout.inWholeNanoseconds, TimeUnit.NANOSECONDS)
        val duration = (System.nanoTime() - startNanos).nanoseconds
        if (!finished) {
            log.warn("process {} (pid {}) exceeded its {} timeout; killing the process tree; logs: {}", name, pid, spec.timeout, logs.commandLog)
            killProcessTree(process, name)
            appendCommandLog(logs, "pid:      $pid\nexit:     TIMEOUT after ${spec.timeout}\nduration: $duration")
            throw ProcessTimeoutException(
                "process '$name' (pid $pid) timed out after ${spec.timeout}; logs kept at ${logs.commandLog}",
                name, pid, spec.timeout, readOutputTail(logs), logs,
            )
        }
        val exitCode = process.exitValue()
        appendCommandLog(logs, "pid:      $pid\nexit:     $exitCode\nduration: $duration")
        log.debug("process {} (pid {}) exited with {} in {}; command log: {}", name, pid, exitCode, duration, logs.commandLog)
        return ProcessRunResult(exitCode, duration, logs)
    } catch (e: InterruptedException) {
        // The interrupt flag is cleared right now, so the bounded waits inside the
        // kill sequence still work; the flag is restored before rethrowing.
        killProcessTree(process, name)
        appendCommandLog(logs, "pid:      $pid\nexit:     INTERRUPTED\nduration: ${(System.nanoTime() - startNanos).nanoseconds}")
        log.warn("process {} (pid {}) interrupted by the caller; process tree killed; logs: {}", name, pid, logs.commandLog)
        Thread.currentThread().interrupt()
        throw e
    }
}

/**
 * Deletes this runner's old log files in [logsDir] — and ONLY files
 * matching the runner's full filename grammar (other logs live in the same
 * folder and are never touched); non-recursive, regular files only.
 *
 * Two passes, both keyed on the FILENAME timestamp (not mtime — the
 * completion append bumps the command log's mtime and would split a run's
 * file group):
 * 1. age: delete runs older than [maxAge];
 * 2. count: if more than [maxFiles] files remain, delete oldest runs (kept
 *    together as whole groups) down to [trimTo] — hysteresis, so the trim
 *    runs once per ~([maxFiles]-[trimTo]) runs, not on every start. Runs
 *    younger than one hour are never count-deleted (protects live
 *    concurrent runs).
 *
 * Concurrent cleanups from parallel devrig JVMs are fine: already-deleted
 * files count as success. Failures are WARN-logged and never thrown.
 *
 * Public and parameterized for direct testing; [runProcess] invokes it at
 * most once per JVM and only when the `.process-cleanup-stamp` marker file
 * is older than 4 hours.
 *
 * @return number of files deleted
 */
fun cleanupProcessLogs(
    logsDir: Path,
    maxFiles: Int = 10_000,
    trimTo: Int = 8_000,
    maxAge: Duration = 30.days,
    nowMillis: Long = System.currentTimeMillis(),
): Int {
    require(trimTo in 0..maxFiles) { "trimTo ($trimTo) must be within 0..maxFiles ($maxFiles)" }

    class RunnerFile(val path: Path, val timestampMillis: Long, val runKey: String)

    val files = try {
        Files.list(logsDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.toList()
        }
    } catch (e: Exception) {
        log.warn("process-log cleanup could not list {}: {}", logsDir, e.toString())
        return 0
    }.mapNotNull { path ->
        val fileName = path.fileName.toString()
        val match = RUNNER_FILE_GRAMMAR.matchEntire(fileName) ?: return@mapNotNull null
        val timestamp = parseFileTimestamp(match.groupValues[1]) ?: return@mapNotNull null
        RunnerFile(path, timestamp, fileName.removeSuffix("-${match.groupValues[2]}.log"))
    }

    var deleted = 0
    fun deleteCounted(path: Path) {
        try {
            if (Files.deleteIfExists(path)) deleted++
        } catch (e: NoSuchFileException) {
            // a concurrent cleanup got there first — success
        } catch (e: Exception) {
            log.warn("process-log cleanup could not delete {}: {}", path, e.toString())
        }
    }

    val ageCutoff = nowMillis - maxAge.inWholeMilliseconds
    val (expired, remaining) = files.partition { it.timestampMillis < ageCutoff }
    expired.forEach { deleteCounted(it.path) }

    if (remaining.size > maxFiles) {
        val ageFloorCutoff = nowMillis - COUNT_PASS_AGE_FLOOR.inWholeMilliseconds
        val groupsOldestFirst = remaining.groupBy { it.runKey }.values.sortedBy { group -> group.minOf { it.timestampMillis } }
        var count = remaining.size
        for (group in groupsOldestFirst) {
            if (count <= trimTo) break
            if (group.any { it.timestampMillis > ageFloorCutoff }) continue
            group.forEach { deleteCounted(it.path) }
            count -= group.size
        }
    }

    if (deleted > 0) log.warn("process-log cleanup removed {} file(s) from {}", deleted, logsDir)
    return deleted
}

private fun parseFileTimestamp(text: String): Long? = try {
    LocalDateTime.parse(text, FILE_TIMESTAMP_FORMAT).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
} catch (e: Exception) {
    log.warn("process-log cleanup could not parse timestamp '{}': {}", text, e.toString())
    null
}

private fun maybeCleanupOncePerJvm(logsDir: Path) {
    if (!cleanupTriggered.compareAndSet(false, true)) return
    try {
        val marker = logsDir.resolve(CLEANUP_MARKER_FILE)
        val now = System.currentTimeMillis()
        val markerAgeMillis = try {
            now - Files.getLastModifiedTime(marker).toMillis()
        } catch (e: IOException) {
            Long.MAX_VALUE // no marker yet (or unreadable) — run the scan
        }
        if (markerAgeMillis < CLEANUP_THROTTLE.inWholeMilliseconds) return
        cleanupProcessLogs(logsDir, nowMillis = now)
        Files.createDirectories(logsDir)
        Files.writeString(marker, now.toString())
    } catch (e: Exception) {
        log.warn("process-log cleanup skipped: {}", e.toString())
    }
}

/** Sanitizes a run name for use inside a file name: [A-Za-z0-9._-], max 40 chars, "process" fallback. */
private fun sanitizeProcessName(raw: String): String {
    val cleaned = raw.map { if (it.isLetterOrDigit() && it.code < 128 || it in "._-") it else '-' }
        .joinToString("")
        .replace(Regex("-{2,}"), "-")
        .trim('-', '.')
        .take(40)
    return cleaned.ifBlank { "process" }
}

private fun allocateLogFiles(spec: ProcessRunSpec, name: String): ProcessRunLogs {
    Files.createDirectories(spec.logsDir)
    val jvmPid = ProcessHandle.current().pid()
    repeat(100) {
        val timestamp = FILE_TIMESTAMP_FORMAT.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(System.currentTimeMillis()))
        val base = "process-$name-$timestamp-$jvmPid-${runSequence.incrementAndGet()}"
        val commandLog = spec.logsDir.resolve("$base-command.log")
        try {
            createOwnerOnlyFile(commandLog) // CREATE_NEW: the atomic uniqueness reservation
        } catch (e: FileAlreadyExistsException) {
            return@repeat // collision (another JVM, same millisecond) — bump seq and retry
        }
        Files.writeString(commandLog, commandLogHeader(spec), StandardOpenOption.WRITE)
        val stdoutLog = spec.logsDir.resolve("$base-stdout.log")
        val stderrLog = if (spec.mergeStderrIntoStdout) null else spec.logsDir.resolve("$base-stderr.log")
        precreateOwnerOnly(stdoutLog)
        stderrLog?.let { precreateOwnerOnly(it) }
        return ProcessRunLogs(commandLog, stdoutLog, stderrLog)
    }
    throw IOException("could not allocate unique process log file names in ${spec.logsDir}")
}

private fun commandLogHeader(spec: ProcessRunSpec): String = buildString {
    append("started:  ").append(OffsetDateTime.now()).append('\n')
    append("command:  ").append(Json.encodeToString(spec.command)).append('\n')
    append("workdir:  ").append(spec.workingDir?.let { Json.encodeToString(it.toString()) } ?: "inherited").append('\n')
    // KEYS only — environment values may carry secrets and must never reach the log.
    append("env-keys: ").append(Json.encodeToString(spec.environment.keys.sorted())).append('\n')
    append("merged:   ").append(spec.mergeStderrIntoStdout).append('\n')
}

private fun appendCommandLog(logs: ProcessRunLogs, text: String) {
    try {
        Files.writeString(logs.commandLog, text + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    } catch (e: Exception) {
        log.warn("could not append to command log {}: {}", logs.commandLog, e.toString())
    }
}

/** command.log carries the full argv — keep it owner-only where the platform can express that. */
private fun createOwnerOnlyFile(path: Path) {
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    } else {
        Files.createFile(path)
    }
}

private fun precreateOwnerOnly(path: Path) {
    try {
        createOwnerOnlyFile(path)
    } catch (e: FileAlreadyExistsException) {
        // stale leftover with the same reserved base name; Redirect.to truncates it anyway
    }
}

/** Last lines of the run's output for [ProcessRunException.outputTail]: bounded 8 KiB per file, 100 lines total. */
private fun readOutputTail(logs: ProcessRunLogs): List<ProcessLine> = try {
    fun tailOf(file: Path, stream: ProcessStream): List<ProcessLine> {
        val size = try {
            Files.size(file)
        } catch (e: NoSuchFileException) {
            return emptyList()
        }
        val skip = maxOf(0L, size - 8192)
        val bytes = Files.newInputStream(file).use { input ->
            input.skipNBytes(skip)
            input.readNBytes(8192)
        }
        val lines = String(bytes, Charsets.UTF_8).lines()
        // The first line is likely a partial one when we started mid-file.
        return (if (skip > 0 && lines.size > 1) lines.drop(1) else lines)
            .filter { it.isNotEmpty() }
            .map { ProcessLine(stream, it) }
    }

    val tail = tailOf(logs.stdoutLog, ProcessStream.STDOUT) +
        (logs.stderrLog?.let { tailOf(it, ProcessStream.STDERR) } ?: emptyList())
    tail.takeLast(100)
} catch (e: Exception) {
    // The tail is diagnostics for an exception that is already being thrown — never mask it.
    log.warn("could not read the output tail: {}", e.toString())
    emptyList()
}

/**
 * Bounded, best-effort kill of the whole child process tree (the v4/v6
 * design-doc state machine): capture descendants while the root is alive,
 * destroy() the root, re-capture (a TERM handler may have spawned more),
 * force-kill everything captured, escalate on the root, then a final sweep
 * over the captured live handles' own descendants. Survivors are
 * WARN-logged. Handles that reparent/detach (e.g. WMI-created processes)
 * are out of reach by design.
 */
private fun killProcessTree(process: Process, name: String) {
    val captured = LinkedHashMap<Long, ProcessHandle>()
    fun capture() {
        try {
            process.toHandle().descendants().forEach { captured.putIfAbsent(it.pid(), it) }
        } catch (e: Exception) {
            log.warn("process {}: could not enumerate descendants: {}", name, e.toString())
        }
    }

    capture()
    process.destroy()
    capture() // union right after destroy: a TERM handler can spawn a child even as the root exits
    captured.values.forEach { it.destroyForcibly() }

    if (!quietWaitFor(process, 1_000)) {
        capture() // root still alive — a fresh snapshot is still meaningful
        process.destroyForcibly()
        captured.values.forEach { it.destroyForcibly() }
        if (!quietWaitFor(process, 1_000)) {
            log.warn("process {}: root pid {} would not die; proceeding", name, process.pid())
        }
    }

    captured.values.filter { it.isAlive }.forEach { handle ->
        try {
            handle.descendants().forEach { d ->
                captured.putIfAbsent(d.pid(), d)
                d.destroyForcibly()
            }
        } catch (e: Exception) {
            log.warn("process {}: final descendant sweep failed for pid {}: {}", name, handle.pid(), e.toString())
        }
        handle.destroyForcibly()
    }

    val deadlineNanos = System.nanoTime() + 1_000_000_000L
    while (captured.values.any { it.isAlive } && System.nanoTime() < deadlineNanos) {
        try {
            Thread.sleep(20)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            break
        }
    }
    val survivors = captured.values.filter { it.isAlive }.map { it.pid() }
    if (survivors.isNotEmpty()) {
        log.warn("process {}: descendant pid(s) still alive after the kill sweep: {}", name, survivors)
    }
}

private fun quietWaitFor(process: Process, millis: Long): Boolean = try {
    process.waitFor(millis, TimeUnit.MILLISECONDS)
} catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    !process.isAlive
}
