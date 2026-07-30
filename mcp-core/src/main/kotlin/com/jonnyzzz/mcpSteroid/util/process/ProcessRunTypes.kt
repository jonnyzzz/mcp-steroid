/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.util.process

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.time.Duration
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.util.process.ProcessRunLogs")

/** Suffix appended by the bounded reads when the file exceeded the limit. Never ends in a digit. */
const val PROCESS_READ_TRUNCATION_MARKER: String = " …[truncated by ProcessRunner]"

/**
 * Production process runner for mcp-core — the file-redirect model. See
 * docs/design/process-runner-api.md (v6, the quorum-reviewed implementation
 * contract) for full semantics.
 *
 * ALL production process launches go through [runProcess]; the single
 * documented exception is npx-kt's detached IDE spawn
 * (`ManagedBackend.spawnIdeProcess`), which never reads its streams.
 * The runner NEVER writes to System.out — in `devrig mcp` mode stdout is
 * the MCP JSON-RPC channel.
 */

/** Which stream of the child process a line was attributed to. */
enum class ProcessStream { STDOUT, STDERR }

/** One line of child output (no trailing terminator). In merged mode every line is tagged [ProcessStream.STDOUT]. */
data class ProcessLine(val stream: ProcessStream, val text: String)

/** Default bound for [ProcessRunLogs.readStdout]/[ProcessRunLogs.readStderr]: 32 MiB. */
const val DEFAULT_PROCESS_READ_LIMIT: Int = 32 * 1024 * 1024

/**
 * Everything needed to run one child process to completion. Validation
 * happens at construction — an invalid spec fails before anything starts.
 *
 * @param command argv list (executable first); must be non-empty.
 * @param timeout wall-clock budget from spawn to exit; finite and positive.
 *   On expiry the whole child process tree is killed (bounded, best-effort)
 *   and [ProcessTimeoutException] is thrown.
 * @param name filename-safe tag for the log files (sanitized to
 *   `[A-Za-z0-9._-]`, max 40 chars, falls back to "process" when nothing
 *   survives sanitization); defaults to the executable's file name. The
 *   full argv is recorded in the command log file, never in messages.
 * @param workingDir child working directory (inherited if null).
 * @param environment entries ADDED on top of the inherited environment.
 *   Only the KEYS are recorded in the command log — values may be secrets.
 * @param mergeStderrIntoStdout true (default): `redirectErrorStream(true)`,
 *   ONE output file with exact interleaving. false: separate stdout/stderr
 *   files, for callers that must distinguish stderr.
 * @param logsDir where the log files go; default `~/.mcp-steroid/logs`
 *   (devrig's `HomePaths.logsDir`). Overridable for tests and embeddings.
 */
class ProcessRunSpec(
    val command: List<String>,
    val timeout: Duration,
    val name: String? = null,
    val workingDir: Path? = null,
    val environment: Map<String, String> = emptyMap(),
    val mergeStderrIntoStdout: Boolean = true,
    val logsDir: Path = defaultProcessLogsDir(),
) {
    init {
        require(command.isNotEmpty()) { "command must not be empty" }
        require(timeout.isFinite() && timeout.isPositive()) { "timeout must be finite and positive: $timeout" }
    }
}

/** `~/.mcp-steroid/logs` — the same location as devrig's `HomePaths.logsDir`. */
fun defaultProcessLogsDir(): Path =
    Path.of(System.getProperty("user.home"), ".mcp-steroid", "logs").toAbsolutePath().normalize()

/**
 * Handle on the files one run produced:
 * `process-<name>-<yyyyMMdd-HHmmss-SSS>-<pid>-<seq>-{command,stdout,stderr}.log`
 * in the spec's logsDir. [commandLog] records the invocation (argv JSON,
 * workdir, sorted env KEYS, merge flag) and the completion (pid, exit code /
 * TIMEOUT / INTERRUPTED / START-FAILED, duration).
 */
class ProcessRunLogs(
    val commandLog: Path,
    /** Child stdout — BOTH streams when merged. */
    val stdoutLog: Path,
    /** Child stderr; null in merged mode. */
    val stderrLog: Path?,
) {
    /**
     * Bounded UTF-8 read of [stdoutLog] (both streams when merged). Streams
     * at most [maxChars] BYTES from the file head (UTF-8 decodes N bytes to
     * <= N chars, so the char bound holds); a longer file gets
     * " …[truncated by ProcessRunner]" APPENDED on top of the cap (the
     * marker never ends in a digit — PID-parsing callers rely on that) and
     * a WARN. Returns "" when the file is missing (after [delete] or
     * cleanup); other I/O errors propagate.
     */
    fun readStdout(maxChars: Int = DEFAULT_PROCESS_READ_LIMIT): String = readBounded(stdoutLog, maxChars)

    /** Same contract as [readStdout]; "" when merged (no stderr file) or missing. */
    fun readStderr(maxChars: Int = DEFAULT_PROCESS_READ_LIMIT): String {
        require(maxChars > 0) { "maxChars must be positive: $maxChars" }
        return stderrLog?.let { readBounded(it, maxChars) } ?: ""
    }

    /**
     * Deletes this run's files — for callers that know the run is fine and
     * the files are noise. Best-effort and idempotent: already-missing
     * files are success; real failures are WARN-logged, never thrown.
     */
    fun delete() {
        for (file in listOfNotNull(commandLog, stdoutLog, stderrLog)) {
            try {
                Files.deleteIfExists(file)
            } catch (e: Exception) {
                logger.warn("could not delete process log {}: {}", file, e.toString())
            }
        }
    }

    private fun readBounded(file: Path, maxChars: Int): String {
        require(maxChars > 0) { "maxChars must be positive: $maxChars" }
        // Read one byte beyond the cap from the SAME stream: truncation is detected without a second
        // Files.size() stat, which could race with a concurrent delete()/cleanup and leak
        // NoSuchFileException after a successful read.
        val probeLength = if (maxChars < Int.MAX_VALUE) maxChars + 1 else maxChars
        val bytes = try {
            Files.newInputStream(file).use { it.readNBytes(probeLength) }
        } catch (e: NoSuchFileException) {
            logger.debug("process log {} is gone (deleted or cleaned up); returning empty", file)
            return ""
        }
        // String(bytes, UTF_8) replaces malformed sequences with U+FFFD, never throws.
        if (bytes.size <= maxChars) return String(bytes, Charsets.UTF_8)
        logger.warn("process log {} exceeds the {}-char read limit; returning a truncated head", file, maxChars)
        return String(bytes, 0, maxChars, Charsets.UTF_8) + PROCESS_READ_TRUNCATION_MARKER
    }
}

/** Successful completion (the child exited on its own within the timeout). */
class ProcessRunResult(
    val exitCode: Int,
    /** Spawn-to-exit wall time (nanoTime based; excludes kill/cleanup phases). */
    val duration: Duration,
    val logs: ProcessRunLogs,
)

/**
 * Family supertype of the runner's typed failures — best-effort call sites
 * catch ONE clause (`catch (e: ProcessRunException)`) and cover timeout AND
 * start failure. (Caller interruption intentionally stays outside:
 * `InterruptedException` propagates.) The child tree, if it ever existed,
 * has been killed (bounded, best-effort).
 *
 * [outputTail] holds the last lines read back from the log files (bounded
 * 100 lines / 8 KiB; tagged STDOUT-only in merged mode — attribution is
 * unrecoverable post-merge). The tail is NEVER interpolated into the
 * message — messages get logged and output may contain secrets; callers
 * opt in by reading [outputTail]. [pid] is -1 when the child never started.
 * [logs] points at the kept files for diagnosis.
 */
sealed class ProcessRunException(
    message: String,
    val processName: String,
    val pid: Long,
    val outputTail: List<ProcessLine>,
    val logs: ProcessRunLogs,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** [ProcessRunSpec.timeout] elapsed; the child tree has been killed; files kept. */
class ProcessTimeoutException(
    message: String,
    processName: String,
    pid: Long,
    val timeout: Duration,
    outputTail: List<ProcessLine>,
    logs: ProcessRunLogs,
) : ProcessRunException(message, processName, pid, outputTail, logs)

/**
 * `ProcessBuilder.start()` failed (missing binary, bad working directory).
 * [cause] is the original [IOException]; the command log is kept with a
 * START-FAILED record.
 */
class ProcessStartException(
    message: String,
    processName: String,
    logs: ProcessRunLogs,
    cause: IOException,
) : ProcessRunException(message, processName, pid = -1, outputTail = emptyList(), logs = logs, cause = cause)

/**
 * The OS null device: `NUL` on Windows, `/dev/null` elsewhere. The runner
 * redirects the child's stdin from it; also used by npx-kt's detached IDE
 * spawn, which stays on raw ProcessBuilder by design.
 */
fun nullDevice(): File =
    if (System.getProperty("os.name").startsWith("Windows")) File("NUL") else File("/dev/null")
