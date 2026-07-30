/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.util.text.DevrigVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.name

/**
 * Cross-process coordination for devrig auto-update — the `~/.mcp-steroid/update/` contract from
 * [docs/updates-check/devrig-auto-update.md]. All marker filenames carry the CANONICAL base version
 * ([DevrigVersion.comparableVersion]): `devrig install devrig` writes markers from its full build
 * string while the update loop works from version.json's base string, and without canonicalization
 * one release would produce two textually different marker files.
 *
 * Mutual exclusion is the fixed-name `update/lock` (atomic exclusive create). The per-pid
 * `update-<pid>-version-<v>` markers are the human-readable "who is updating, with what state"
 * files — observability, not the lock.
 */
class UpdateCoordination(
    val updateDir: Path,
    val ownPid: Long = ProcessHandle.current().pid(),
    val hostname: String = localHostnameOrUnknown(),
    val clock: () -> Long = System::currentTimeMillis,
    val isPidAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).isPresent },
) {
    val lockFile: Path get() = updateDir.resolve(UPDATE_LOCK_NAME)

    fun inProgressMarker(version: String): Path = updateDir.resolve("update-$ownPid-version-${baseVersionString(version)}")
    fun updatedMarker(version: String): Path = updateDir.resolve("updated-${baseVersionString(version)}")
    fun failureMarker(version: String): Path = updateDir.resolve("update-failed-${baseVersionString(version)}")
    fun skewMarker(version: String): Path = updateDir.resolve("update-skew-${baseVersionString(version)}")
    fun scriptFile(isWin: Boolean): Path = updateDir.resolve("install-$ownPid." + if (isWin) "ps1" else "sh")

    // ── the lock ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Atomically acquire `update/lock` (CREATE_NEW — the creator wins). On contention, one stale-lock
     * reclaim is attempted (see [reclaimStaleLock]) followed by a single retry. Returns false when the
     * lock is genuinely held by a live updater.
     */
    fun tryAcquireLock(info: UpdateStateInfo): Boolean {
        Files.createDirectories(updateDir)
        for (attempt in 0..1) {
            try {
                Files.write(lockFile, updateJson.encodeToString(UpdateStateInfo.serializer(), info).toByteArray(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                return true
            } catch (e: FileAlreadyExistsException) {
                if (attempt == 0 && reclaimStaleLock()) continue
                return false
            } catch (e: Exception) {
                System.err.println("[mcp-steroid] could not create the update lock $lockFile: $e")
                return false
            }
        }
        return false
    }

    /**
     * Release only OUR lock: a reclaimed-and-replaced lock must never be deleted by the old owner, so
     * the content pid+hostname are checked before the delete. An unparsable or foreign lock is left
     * alone (the age bound cleans it up).
     */
    fun releaseLock() {
        val info = readLockInfo() ?: return
        if (info.pid == ownPid && info.hostname == hostname) {
            try {
                Files.deleteIfExists(lockFile)
            } catch (e: Exception) {
                System.err.println("[mcp-steroid] could not release the update lock $lockFile: $e")
            }
        } else {
            System.err.println("[mcp-steroid] update lock $lockFile is owned by pid ${info.pid}@${info.hostname}, not releasing")
        }
    }

    fun readLockInfo(): UpdateStateInfo? = readStateInfo(lockFile)

    /**
     * A lock is stale when its same-host owner pid is dead, or — liveness-independent — when it is
     * older than [UPDATE_STALE_AGE_MILLIS] (24 h; covers PID reuse, wedged owners, and foreign NFS
     * hosts; safe because no genuine update holds the lock longer than the 30 min supervise timeout).
     * The reclaim is race-free via atomic rename: exactly one of two concurrent reclaimers wins the
     * `Files.move`; the loser sees [NoSuchFileException] and treats the lock as already gone.
     */
    fun reclaimStaleLock(): Boolean {
        if (!lockFile.exists()) return true
        if (isLockHeldLive()) return false
        val reclaimed = updateDir.resolve("lock.reclaimed.$ownPid")
        val staleOwner = readLockInfo()
        return try {
            Files.move(lockFile, reclaimed, StandardCopyOption.ATOMIC_MOVE)
            Files.deleteIfExists(reclaimed)
            System.err.println("[mcp-steroid] reclaimed a stale update lock (owner pid ${staleOwner?.pid ?: "?"}@${staleOwner?.hostname ?: "?"})")
            true
        } catch (e: NoSuchFileException) {
            true // another reclaimer won the rename — the lock is gone either way
        } catch (e: Exception) {
            System.err.println("[mcp-steroid] could not reclaim the stale update lock $lockFile: $e")
            false
        }
    }

    /**
     * Read-only lock liveness: held by a live same-host pid, or foreign/unparsable and younger than the
     * 24 h bound. The age bound applies EVEN when the pid reads live — it covers PID reuse and wedged
     * owners, and is safe because no genuine update holds the lock longer than the 30 min supervise
     * timeout.
     */
    fun isLockHeldLive(): Boolean {
        if (!lockFile.exists()) return false
        val info = readLockInfo()
        if (info != null && info.hostname == hostname && !isPidAlive(info.pid)) return false
        val ref = info?.startedAt ?: lockFileMtime() ?: return false
        return clock() - ref <= UPDATE_STALE_AGE_MILLIS
    }

    /** The passive-notice "install in flight" probe: a live lock or any live per-pid marker. */
    fun isUpdateInFlight(): Boolean = isLockHeldLive() || anyLiveInProgressMarker()

    private fun lockFileMtime(): Long? = try {
        Files.getLastModifiedTime(lockFile).toMillis()
    } catch (e: NoSuchFileException) {
        null
    } catch (e: Exception) {
        System.err.println("[mcp-steroid] could not stat the update lock $lockFile: $e")
        null
    }

    // ── in-progress + completion markers ─────────────────────────────────────────────────────────

    fun writeInProgressMarker(version: String, info: UpdateStateInfo) =
        writeJsonAtomically(inProgressMarker(version), updateJson.encodeToString(UpdateStateInfo.serializer(), info))

    fun deleteInProgressMarker(version: String) {
        try {
            Files.deleteIfExists(inProgressMarker(version))
        } catch (e: Exception) {
            System.err.println("[mcp-steroid] could not delete ${inProgressMarker(version)}: $e")
        }
    }

    /** Any `update-<pid>-version-<v>` marker whose owner is live (same-host pid alive, or foreign/unparsable and younger than 24 h). */
    fun anyLiveInProgressMarker(): Boolean = listMarkerFiles().any { file ->
        parseInProgressMarkerName(file.name) != null && !isMarkerStale(file)
    }

    fun writeUpdatedMarker(version: String, info: UpdateStateInfo) =
        writeJsonAtomically(updatedMarker(version), updateJson.encodeToString(UpdateStateInfo.serializer(), info))

    /**
     * Deletes every `updated-*` marker whose parsed version EQUALS [version] (ordering-based, not
     * name-based): a marker written under a non-canonical alias (e.g. `updated-0.102.0` vs
     * `updated-0.102`) must still be deletable, or a torn marker could survive its own repair.
     */
    fun deleteUpdatedMarker(version: String) {
        val target = baseVersion(version)
        for ((file, v) in updatedMarkerFiles()) {
            if (v.compareTo(target) != 0) continue
            try {
                Files.deleteIfExists(file)
            } catch (e: Exception) {
                System.err.println("[mcp-steroid] could not delete $file: $e")
            }
        }
    }

    /** All `updated-<v>` marker files with their parsed base-form versions. */
    fun updatedMarkerFiles(): List<Pair<Path, DevrigVersion>> = listMarkerFiles().mapNotNull { file ->
        file.name.removePrefix("updated-").takeIf { it != file.name && it.isNotBlank() }?.let { file to baseVersion(it) }
    }

    fun updatedVersions(): List<DevrigVersion> = updatedMarkerFiles().map { it.second }

    fun newestUpdatedVersion(): DevrigVersion? = updatedVersions().maxOrNull()

    /** The `update-<pid>-version-<v>` marker written by the lock winner. */
    fun readInProgressMarker(version: String): UpdateStateInfo? = readStateInfo(inProgressMarker(version))

    /** Ordering-based like [deleteUpdatedMarker]: any alias of [version] counts. */
    fun readUpdatedMarker(version: String): UpdateStateInfo? {
        val target = baseVersion(version)
        return updatedMarkerFiles().firstOrNull { it.second.compareTo(target) == 0 }?.let { readStateInfo(it.first) }
    }

    // ── failure + skew counters (single writer: only under the lock) ─────────────────────────────

    fun readFailure(version: String): UpdateFailureInfo? = readJson(failureMarker(version), UpdateFailureInfo.serializer())

    fun recordFailure(version: String, exitCode: Int?): UpdateFailureInfo {
        val now = clock()
        val prev = readFailure(version)
        val next = if (prev != null && now - prev.windowStartedAt <= UPDATE_FAILURE_WINDOW_MILLIS) {
            prev.copy(attempts = prev.attempts + 1, lifetimeAttempts = prev.lifetimeAttempts + 1, lastAttemptAt = now, lastExitCode = exitCode)
        } else {
            UpdateFailureInfo(
                targetVersion = baseVersionString(version),
                attempts = 1,
                lifetimeAttempts = (prev?.lifetimeAttempts ?: 0) + 1,
                windowStartedAt = now,
                lastAttemptAt = now,
                lastExitCode = exitCode,
            )
        }
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
     * The step-5 cap: 3 attempts inside the 7-day window, 9 attempts per version lifetime (a
     * deterministic failure must not re-download hundreds of MB weekly forever), and at least 1 h
     * between attempts.
     */
    fun isFailureCapped(version: String): Boolean {
        val f = readFailure(version) ?: return false
        val now = clock()
        val windowAttempts = if (now - f.windowStartedAt <= UPDATE_FAILURE_WINDOW_MILLIS) f.attempts else 0
        return windowAttempts >= UPDATE_MAX_ATTEMPTS_PER_WINDOW ||
            f.lifetimeAttempts >= UPDATE_MAX_LIFETIME_ATTEMPTS ||
            now - f.lastAttemptAt < UPDATE_MIN_RETRY_INTERVAL_MILLIS
    }

    fun readSkew(version: String): UpdateSkewInfo? = readJson(skewMarker(version), UpdateSkewInfo.serializer())

    fun recordSkew(version: String, parsedVersion: String?): UpdateSkewInfo {
        val prev = readSkew(version)
        val next = UpdateSkewInfo(
            targetVersion = baseVersionString(version),
            firstSeenAt = prev?.firstSeenAt ?: clock(),
            attempts = (prev?.attempts ?: 0) + 1,
            parsedVersion = parsedVersion,
        )
        writeJsonAtomically(skewMarker(version), updateJson.encodeToString(UpdateSkewInfo.serializer(), next))
        return next
    }

    fun clearSkew(version: String) {
        try {
            Files.deleteIfExists(skewMarker(version))
        } catch (e: Exception) {
            System.err.println("[mcp-steroid] could not delete ${skewMarker(version)}: $e")
        }
    }

    /** The step-5 skew bound: ≥ 3 quiet aborts or first seen more than 24 h ago. */
    fun isSkewCapped(version: String): Boolean {
        val s = readSkew(version) ?: return false
        return s.attempts >= UPDATE_MAX_SKEW_ATTEMPTS || clock() - s.firstSeenAt > UPDATE_STALE_AGE_MILLIS
    }

    /**
     * The explicit-intent sweep of `devrig install devrig` (manual `curl | sh`, including rollback):
     * delete every `updated-`/`update-failed-`/`update-skew-` marker for a version NEWER than the one
     * being installed, so they cannot re-block the launcher self-heal afterwards.
     */
    fun sweepMarkersNewerThan(version: String) {
        val base = baseVersion(version)
        for (file in listMarkerFiles()) {
            val name = file.name
            val markerVersion = when {
                name.startsWith("update-failed-") -> name.removePrefix("update-failed-")
                name.startsWith("update-skew-") -> name.removePrefix("update-skew-")
                name.startsWith("updated-") -> name.removePrefix("updated-")
                else -> null
            } ?: continue
            if (baseVersion(markerVersion) > base) {
                try {
                    Files.deleteIfExists(file)
                    System.err.println("[mcp-steroid] explicit install of $version cleared $name")
                } catch (e: Exception) {
                    System.err.println("[mcp-steroid] could not clear $file: $e")
                }
            }
        }
    }

    // ── GC (step 2) ──────────────────────────────────────────────────────────────────────────────

    /**
     * The cheap per-tick sweep. MUST NOT run on SNAPSHOT builds (the caller gates): a SNAPSHOT
     * compares newer than every promoted version and would delete every `updated-*` marker — the
     * restart-pending signal. Deletions compare base versions only ([baseVersion]).
     *
     * - `updated-<v>` with v strictly below the current build (`updated-<current>` is kept one
     *   release as flip-back evidence for the no-downgrade guard);
     * - `update-failed-<v>` / `update-skew-<v>` for superseded versions (the windowed decay of the
     *   failure counter lives in [isFailureCapped]; `lifetimeAttempts` survives inside the file);
     * - stale `update-<pid>-…` markers and orphaned `install-<pid>.*` scripts (liveness-else-age);
     * - `logs/update-*.log` older than 30 days.
     */
    fun gc(current: DevrigVersion, logsDir: Path) {
        val currentBase = baseVersion(current.value)
        for (file in listMarkerFiles()) {
            val name = file.name
            val scriptPid = parseScriptFilePid(name)
            val obsolete = when {
                name.startsWith("update-failed-") -> baseVersion(name.removePrefix("update-failed-")) < currentBase
                name.startsWith("update-skew-") -> baseVersion(name.removePrefix("update-skew-")) < currentBase
                name.startsWith("updated-") -> baseVersion(name.removePrefix("updated-")) < currentBase
                parseInProgressMarkerName(name) != null -> isMarkerStale(file)
                scriptPid != null -> scriptPid != ownPid && isPidStaleForFile(scriptPid, file)
                name.startsWith("lock.reclaimed.") -> true // leftover from a crashed reclaimer
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

    /** Liveness-else-age for a per-pid file: same-host liveness comes from the marker JSON when parseable, else file mtime age. */
    private fun isMarkerStale(file: Path): Boolean {
        val info = readStateInfo(file)
        if (info != null && info.hostname == hostname) return !isPidAlive(info.pid)
        val startedAt = info?.startedAt ?: try {
            Files.getLastModifiedTime(file).toMillis()
        } catch (e: Exception) {
            return false // cannot stat — leave it for a later sweep
        }
        return clock() - startedAt > UPDATE_STALE_AGE_MILLIS
    }

    private fun isPidStaleForFile(pid: Long, file: Path): Boolean {
        if (isPidAlive(pid)) {
            val mtime = try {
                Files.getLastModifiedTime(file).toMillis()
            } catch (e: Exception) {
                return false
            }
            return clock() - mtime > UPDATE_STALE_AGE_MILLIS
        }
        return true
    }

    private fun readStateInfo(file: Path): UpdateStateInfo? = readJson(file, UpdateStateInfo.serializer())

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

/** JSON payload of the lock, the per-pid in-progress marker, and the `updated-<v>` completion marker. */
@Serializable
data class UpdateStateInfo(
    val pid: Long,
    val hostname: String,
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
    val lifetimeAttempts: Int,
    val windowStartedAt: Long,
    val lastAttemptAt: Long,
    val lastExitCode: Int? = null,
)

@Serializable
data class UpdateSkewInfo(
    val targetVersion: String,
    val firstSeenAt: Long,
    val attempts: Int,
    val parsedVersion: String? = null,
)

const val UPDATE_LOCK_NAME = "lock"

/** Set (to `1`) by the update loop when spawning the install script; `devrig install devrig` honors the no-downgrade guard only under it. */
const val ENV_DEVRIG_AUTO_UPDATE = "DEVRIG_AUTO_UPDATE"

/** User opt-out for the active updater (`yes/true/1/on`); the passive marker-aware notice remains. */
const val ENV_DEVRIG_NO_AUTO_UPDATE = "DEVRIG_NO_AUTO_UPDATE"

/** `yes/true/1/on` → true; anything else (incl. unset) → false. Same accepted spellings as [ENV_BIN_NO_AUTO_REGISTER]. */
fun parseUpdateEnvFlag(value: String?): Boolean = when (value?.trim()?.lowercase()) {
    "yes", "true", "1", "on" -> true
    else -> false
}
const val UPDATE_STALE_AGE_MILLIS: Long = 24L * 60 * 60 * 1000
const val UPDATE_FAILURE_WINDOW_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
const val UPDATE_MIN_RETRY_INTERVAL_MILLIS: Long = 60L * 60 * 1000
const val UPDATE_LOG_RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
const val UPDATE_MAX_ATTEMPTS_PER_WINDOW = 3
const val UPDATE_MAX_LIFETIME_ATTEMPTS = 9
const val UPDATE_MAX_SKEW_ATTEMPTS = 3

/** Human-readable marker content: the files ARE the user-facing state ("the PID, the version, all the relevant state"). */
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

/**
 * The newest `updated-<v>` marker version by NAME ONLY — no JSON reads, no hostname resolution — so
 * the launcher no-downgrade guard can run on the latency-sensitive every-start path.
 */
fun newestUpdatedMarkerVersion(updateDir: Path): DevrigVersion? {
    if (!updateDir.exists()) return null
    return try {
        Files.newDirectoryStream(updateDir, "updated-*").use { stream ->
            stream.mapNotNull { file ->
                file.name.removePrefix("updated-").takeIf { it.isNotBlank() }?.let { baseVersion(it) }
            }.maxOrNull()
        }
    } catch (e: Exception) {
        System.err.println("[mcp-steroid] could not scan $updateDir for updated markers: $e")
        null
    }
}

fun localHostnameOrUnknown(): String = try {
    InetAddress.getLocalHost().hostName
} catch (e: Exception) {
    System.err.println("[mcp-steroid] could not resolve the local hostname: $e")
    "unknown"
}
