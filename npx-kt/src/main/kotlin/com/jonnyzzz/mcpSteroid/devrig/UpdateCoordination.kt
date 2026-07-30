/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.util.text.DevrigVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * Cross-process coordination for devrig auto-update — the `~/.mcp-steroid/update/` contract from
 * [docs/updates-check/devrig-auto-update.md]. There is deliberately NO lock: each process announces
 * itself with its own `update-<pid>-version-<v>` marker, cleans up files from dead processes, and
 * yields when someone else's live marker exists (best-effort mutual exclusion; the double-run race
 * is an accepted tradeoff — the install scripts are concurrency-tolerant).
 *
 * All marker filenames carry the CANONICAL base version ([baseVersionString]): `devrig install
 * devrig` runs as the full build string while the update loop works from version.json's base string,
 * and without canonicalization one release would produce two textually different marker files.
 */
class UpdateCoordination(
    val updateDir: Path,
    val ownPid: Long = ProcessHandle.current().pid(),
    val clock: () -> Long = System::currentTimeMillis,
    val isPidAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).isPresent },
) {
    fun inProgressMarker(version: String): Path = updateDir.resolve("update-$ownPid-version-${baseVersionString(version)}")
    fun updatedMarker(version: String): Path = updateDir.resolve("updated-${baseVersionString(version)}")
    fun failureMarker(version: String): Path = updateDir.resolve("update-failed-${baseVersionString(version)}")
    fun scriptFile(isWin: Boolean): Path = updateDir.resolve("install-$ownPid." + if (isWin) "ps1" else "sh")

    // ── in-progress markers (the only coordination) ──────────────────────────────────────────────

    fun writeInProgressMarker(version: String, info: UpdateStateInfo) =
        writeJsonAtomically(inProgressMarker(version), updateJson.encodeToString(UpdateStateInfo.serializer(), info))

    fun deleteInProgressMarker(version: String) {
        try {
            Files.deleteIfExists(inProgressMarker(version))
        } catch (e: Exception) {
            System.err.println("[mcp-steroid] could not delete ${inProgressMarker(version)}: $e")
        }
    }

    /** Any `update-<pid>-version-<v>` marker whose owner is live per the [isFileStale] rule. */
    fun anyLiveInProgressMarker(): Boolean = listMarkerFiles().any { file ->
        parseInProgressMarkerName(file.name) != null && !isFileStale(file)
    }

    /** The passive-notice "install in flight" probe. */
    fun isUpdateInFlight(): Boolean = anyLiveInProgressMarker()

    // ── completion record ────────────────────────────────────────────────────────────────────────

    fun writeUpdatedMarker(version: String, info: UpdateStateInfo) =
        writeJsonAtomically(updatedMarker(version), updateJson.encodeToString(UpdateStateInfo.serializer(), info))

    /** Ordering-based, not name-based: an alias like `updated-0.102.0` still counts for `0.102`. */
    fun hasUpdatedMarker(version: String): Boolean {
        val target = baseVersion(version)
        return updatedMarkerFiles().any { it.second.compareTo(target) == 0 }
    }

    /** All `updated-<v>` marker files with their parsed base-form versions. */
    fun updatedMarkerFiles(): List<Pair<Path, DevrigVersion>> = listMarkerFiles().mapNotNull { file ->
        file.name.removePrefix("updated-").takeIf { it != file.name && it.isNotBlank() }?.let { file to baseVersion(it) }
    }

    // ── failure counter (bounded retries) ────────────────────────────────────────────────────────

    fun readFailure(version: String): UpdateFailureInfo? = readJson(failureMarker(version), UpdateFailureInfo.serializer())

    fun recordFailure(version: String, exitCode: Int?): UpdateFailureInfo {
        val next = UpdateFailureInfo(
            targetVersion = baseVersionString(version),
            attempts = (readFailure(version)?.attempts ?: 0) + 1,
            lastAttemptAt = clock(),
            lastExitCode = exitCode,
        )
        writeJsonAtomically(failureMarker(version), updateJson.encodeToString(UpdateFailureInfo.serializer(), next))
        return next
    }

    fun clearFailure(version: String) {
        try {
            Files.deleteIfExists(failureMarker(version))
        } catch (e: Exception) {
            System.err.println("[mcp-steroid] could not delete ${failureMarker(version)}: $e")
        }
    }

    /**
     * The step-7 cap: 3 attempts per version, no spacing arm (ticks are already 30–60 min apart, and
     * a pacing condition must never fire the user-facing manual banner). The counter dies with its
     * version via [gc]; the next release resets naturally.
     */
    fun isFailureCapped(version: String): Boolean =
        (readFailure(version)?.attempts ?: 0) >= UPDATE_MAX_ATTEMPTS

    // ── GC (step 3) ──────────────────────────────────────────────────────────────────────────────

    /**
     * The cheap per-tick sweep. MUST NOT run on SNAPSHOT builds (the caller gates — a SNAPSHOT
     * `current` would poison the bound). Version-keyed records are deleted strictly below
     * `min(current, promoted)`: the bound includes `promoted` so a session running NEWER than the
     * promoted version (the post-rollback state) never deletes the `updated-<promoted>` /
     * `update-failed-<promoted>` records that older sessions rely on — they would reinstall every
     * tick otherwise. `updated-<current>` still ages out one release later.
     */
    fun gc(current: DevrigVersion, promoted: DevrigVersion, logsDir: Path) {
        val bound = minOf(baseVersion(current.value), baseVersion(promoted.value))
        for (file in listMarkerFiles()) {
            val name = file.name
            val scriptPid = parseScriptFilePid(name)
            val obsolete = when {
                name.startsWith("update-failed-") -> baseVersion(name.removePrefix("update-failed-")) < bound
                name.startsWith("updated-") -> baseVersion(name.removePrefix("updated-")) < bound
                parseInProgressMarkerName(name) != null -> isFileStale(file)
                scriptPid != null -> scriptPid != ownPid && isPidStale(scriptPid, file)
                else -> false
            }
            if (obsolete) {
                try {
                    Files.deleteIfExists(file)
                } catch (e: Exception) {
                    System.err.println("[mcp-steroid] update GC could not delete $file: $e")
                }
            }
        }
        sweepOldUpdateLogs(logsDir)
    }

    private fun sweepOldUpdateLogs(logsDir: Path) {
        if (!logsDir.exists()) return
        val cutoff = clock() - UPDATE_LOG_RETENTION_MILLIS
        try {
            Files.newDirectoryStream(logsDir, "update-*.log").use { stream ->
                for (log in stream) {
                    try {
                        if (Files.getLastModifiedTime(log).toMillis() < cutoff) Files.deleteIfExists(log)
                    } catch (e: Exception) {
                        System.err.println("[mcp-steroid] update GC could not sweep $log: $e")
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[mcp-steroid] update GC could not list $logsDir: $e")
        }
    }

    // ── the staleness rule (the only liveness machinery) ─────────────────────────────────────────

    /**
     * Uniform for every per-pid file: stale = the pid is dead in the LOCAL pid table, OR the file is
     * older than 24 h. The age bound applies even to live-looking pids — it backstops PID reuse and
     * shared-NFS homes alike (a foreign host's pid probe is meaningless locally).
     */
    private fun isFileStale(file: Path): Boolean {
        val info = readJson(file, UpdateStateInfo.serializer())
        if (info != null && !isPidAlive(info.pid)) return true
        val startedAt = info?.startedAt ?: try {
            Files.getLastModifiedTime(file).toMillis()
        } catch (e: Exception) {
            return false // cannot stat — leave it for a later sweep
        }
        return clock() - startedAt > UPDATE_STALE_AGE_MILLIS
    }

    private fun isPidStale(pid: Long, file: Path): Boolean {
        if (!isPidAlive(pid)) return true
        val mtime = try {
            Files.getLastModifiedTime(file).toMillis()
        } catch (e: Exception) {
            return false
        }
        return clock() - mtime > UPDATE_STALE_AGE_MILLIS
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private fun listMarkerFiles(): List<Path> {
        if (!updateDir.exists()) return emptyList()
        return try {
            Files.newDirectoryStream(updateDir).use { it.toList() }
        } catch (e: Exception) {
            System.err.println("[mcp-steroid] could not list $updateDir: $e")
            emptyList()
        }
    }

    private fun <T> readJson(file: Path, serializer: kotlinx.serialization.KSerializer<T>): T? {
        val text = try {
            Files.readString(file)
        } catch (e: NoSuchFileException) {
            return null
        } catch (e: Exception) {
            System.err.println("[mcp-steroid] could not read $file: $e")
            return null
        }
        return try {
            updateJson.decodeFromString(serializer, text)
        } catch (e: Exception) {
            System.err.println("[mcp-steroid] could not parse $file: $e")
            null
        }
    }

    private fun writeJsonAtomically(target: Path, content: String) {
        Files.createDirectories(updateDir)
        val tmp = updateDir.resolve(".tmp.$ownPid.${target.name}")
        try {
            Files.writeString(tmp, content)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                System.err.println("[mcp-steroid] atomic move unsupported (${e.message}); using a plain move")
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (Files.exists(tmp)) {
                try {
                    Files.deleteIfExists(tmp)
                } catch (e: Exception) {
                    System.err.println("[mcp-steroid] could not remove staging file $tmp: $e")
                }
            }
        }
    }
}

/**
 * JSON payload of the per-pid in-progress marker and the `updated-<v>` completion record — the
 * user-facing state ("the PID, the version, all the relevant state").
 */
@Serializable
data class UpdateStateInfo(
    val pid: Long,
    val currentVersion: String,
    val targetVersion: String,
    val startedAt: Long,
    val completedAt: Long? = null,
    val logFile: String? = null,
    val scriptUrl: String? = null,
    val installerHost: String? = null,
)

@Serializable
data class UpdateFailureInfo(
    val targetVersion: String,
    val attempts: Int,
    val lastAttemptAt: Long,
    val lastExitCode: Int? = null,
)

const val UPDATE_STALE_AGE_MILLIS: Long = 24L * 60 * 60 * 1000
const val UPDATE_LOG_RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
const val UPDATE_MAX_ATTEMPTS = 3

/** User opt-out for the active updater (`yes/true/1/on`); the passive marker-aware notice remains. */
const val ENV_DEVRIG_NO_AUTO_UPDATE = "DEVRIG_NO_AUTO_UPDATE"

/** `yes/true/1/on` → true; anything else (incl. unset) → false. Same accepted spellings as [ENV_BIN_NO_AUTO_REGISTER]. */
fun parseUpdateEnvFlag(value: String?): Boolean = when (value?.trim()?.lowercase()) {
    "yes", "true", "1", "on" -> true
    else -> false
}

/** Human-readable marker content: the files ARE the user-facing state. */
val updateJson: Json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
}

/** `update-<pid>-version-<v>` → (pid, version); null for every other name (incl. `updated-…`, `update-failed-…`). */
fun parseInProgressMarkerName(name: String): Pair<Long, String>? {
    val match = Regex("""^update-(\d+)-version-(.+)$""").find(name) ?: return null
    val (pid, version) = match.destructured
    return pid.toLongOrNull()?.let { it to version }
}

/** `install-<pid>.sh|.ps1` → pid. */
fun parseScriptFilePid(name: String): Long? =
    Regex("""^install-(\d+)\.(sh|ps1)$""").find(name)?.groupValues?.get(1)?.toLongOrNull()

/**
 * The canonical base form used in every marker filename: strip build metadata, then strip trailing
 * `.0` components — `0.101.441-gh-abc1234` → `0.101.441`, and crucially `0.102.0-r-abc1234` (the
 * #360 release-lane build version) → `0.102`, the SAME text as version.json's `version-base`
 * (`0.102`). VersionComparatorUtil treats trailing zeros as equal, so the canonical name must too —
 * one release must never yield two textually different marker files.
 */
fun baseVersionString(version: String): String {
    var base = DevrigVersion.parse(version).comparableVersion
    while (base.endsWith(".0")) base = base.removeSuffix(".0")
    return base
}

/**
 * Base-form [DevrigVersion] for marker ordering: strips build metadata AND the snapshot flag, so the
 * comparison never takes the isSnapshotBuild short-circuit (a SNAPSHOT must not read as "newer" here).
 */
fun baseVersion(version: String): DevrigVersion =
    DevrigVersion(baseVersionString(version), isSnapshotBuild = false)
