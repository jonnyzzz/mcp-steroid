/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.util.text.DevrigVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Marker files under `~/.mcp-steroid/update/` — see docs/updates-check/devrig-auto-update.md.
 * No lock: each process announces with its own `update-<pid>-version-<v>` file, cleans up files of
 * dead processes, and yields to live ones. Coordination reads only filenames and mtime; file
 * contents are write-only debugging JSON.
 *
 * Lives in `:devrig-common` rather than with devrig's updater because the IDE plugin installs devrig too
 * (`ij-plugin` `onboarding/DevrigSetup.kt`). Both halves must see the same markers, or a devrig
 * session and an IDE could each start a ~611 MB install of the same thing at the same time. Nothing
 * here is IDE- or CLI-specific: file names, pids and mtimes.
 */
class UpdateCoordination(
    val updateDir: Path,
    val ownPid: Long = ProcessHandle.current().pid(),
    val clock: () -> Long = System::currentTimeMillis,
    val isPidAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).isPresent },
) {
    fun inProgressMarker(version: String): Path =
        updateDir.resolve("update-$ownPid-version-" + version.substringBefore('-').substringBefore('/'))

    fun updatedMarker(version: String): Path =
        updateDir.resolve("updated-" + version.substringBefore('-').substringBefore('/'))

    fun scriptFile(isWin: Boolean): Path = updateDir.resolve("install-$ownPid." + if (isWin) "ps1" else "sh")

    fun writeInProgressMarker(version: String, info: UpdateStateInfo) =
        writeJsonAtomically(inProgressMarker(version), updateJson.encodeToString(UpdateStateInfo.serializer(), info))

    fun deleteInProgressMarker(version: String) {
        try {
            Files.deleteIfExists(inProgressMarker(version))
        } catch (e: Exception) {
            System.err.println("[mcp-steroid] could not delete ${inProgressMarker(version)}: $e")
        }
    }

    /** Filename pids of every live `update-<pid>-version-*` marker (own included). */
    fun liveInProgressPids(): List<Long> = listMarkerFiles().mapNotNull { file ->
        parseInProgressMarkerName(file.name)?.first?.takeIf { pid -> !isPidStale(pid, file) }
    }

    fun anyLiveInProgressMarker(): Boolean = liveInProgressPids().isNotEmpty()

    fun isUpdateInFlight(): Boolean = anyLiveInProgressMarker()

    fun writeUpdatedMarker(version: String, info: UpdateStateInfo) =
        writeJsonAtomically(updatedMarker(version), updateJson.encodeToString(UpdateStateInfo.serializer(), info))

    fun hasUpdatedMarker(version: String): Boolean = updatedMarker(version).exists()

    /**
     * Per-tick sweep. Never runs on SNAPSHOT builds (caller gates). `updated-<v>` is deleted below
     * `min(current, promoted)` — the bound includes `promoted` so a session newer than the promoted
     * version (post-rollback) keeps the record older sessions rely on. Stale per-pid markers,
     * orphaned scripts and `.tmp.` staging follow [isPidStale]; logs older than 30 days go.
     */
    fun gc(current: DevrigVersion, promoted: DevrigVersion, logsDir: Path) {
        val bound = minOf(current, promoted)
        for (file in listMarkerFiles()) {
            val name = file.name
            val markerPid = parseInProgressMarkerName(name)?.first
            val scriptPid = parseScriptFilePid(name)
            val stagingPid = parseStagingFilePid(name)
            val obsolete = when {
                name.startsWith("updated-") -> DevrigVersion.parse(name.removePrefix("updated-")) < bound
                markerPid != null -> isPidStale(markerPid, file)
                scriptPid != null -> scriptPid != ownPid && isPidStale(scriptPid, file)
                stagingPid != null -> stagingPid != ownPid && isPidStale(stagingPid, file)
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
        val cutoff = clock() - UPDATE_LOG_RETENTION.inWholeMilliseconds
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

    /**
     * Stale = the filename pid is dead locally, OR the file mtime is older than 24 h (backstops PID
     * reuse and shared-NFS homes).
     */
    private fun isPidStale(pid: Long, file: Path): Boolean {
        if (!isPidAlive(pid)) return true
        val mtime = try {
            Files.getLastModifiedTime(file).toMillis()
        } catch (e: Exception) {
            return false // cannot stat — leave it for a later sweep
        }
        return clock() - mtime > UPDATE_STALE_AGE.inWholeMilliseconds
    }

    private fun listMarkerFiles(): List<Path> {
        if (!updateDir.exists()) return emptyList()
        return try {
            Files.newDirectoryStream(updateDir).use { it.toList() }
        } catch (e: Exception) {
            System.err.println("[mcp-steroid] could not list $updateDir: $e")
            emptyList()
        }
    }

    private fun writeJsonAtomically(target: Path, content: String) =
        writeTextAtomically(target, content, stagingPid = ownPid)
}

/**
 * Write [content] (UTF-8) so [target] is never observed half-written: stage into a
 * `.tmp.<pid>.<target-name>` sibling — the SAME directory, because a rename is only atomic within one
 * file system — then move it over the target, falling back to a plain move where the file system
 * cannot do an atomic replace. Creates [target]'s parent directory itself, so callers never have to
 * pre-create it. Content-agnostic: the update markers and the agents' own config files
 * (`~/.claude.json`, `~/.codex/config.toml`, `~/.gemini/settings.json`) all go through here. The
 * staging name matches [parseStagingFilePid], so a crash leftover inside the update dir is swept by
 * [UpdateCoordination.gc].
 */
fun writeTextAtomically(target: Path, content: String, stagingPid: Long = ProcessHandle.current().pid()) {
    target.parent?.let { Files.createDirectories(it) }
    val tmp = target.resolveSibling(".tmp.$stagingPid.${target.name}")
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

/** Marker/record content — WRITE-ONLY debugging JSON, never read back; format free to change. */
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

val UPDATE_STALE_AGE: Duration = 24.hours
val UPDATE_LOG_RETENTION: Duration = 30.days

/** User opt-out for the active updater; the passive notice remains. */
const val ENV_DEVRIG_NO_AUTO_UPDATE = "DEVRIG_NO_AUTO_UPDATE"

/** `yes/true/1/on` → true (same spellings as `ENV_BIN_NO_AUTO_REGISTER` in the devrig CLI). */
fun parseUpdateEnvFlag(value: String?): Boolean = when (value?.trim()?.lowercase()) {
    "yes", "true", "1", "on" -> true
    else -> false
}

/** Encode-only. */
val updateJson: Json = Json {
    prettyPrint = true
    encodeDefaults = true
}

/** `update-<pid>-version-<v>` → (pid, version); null for other names. */
fun parseInProgressMarkerName(name: String): Pair<Long, String>? {
    val match = Regex("""^update-(\d+)-version-(.+)$""").find(name) ?: return null
    val (pid, version) = match.destructured
    return pid.toLongOrNull()?.let { it to version }
}

/** `install-<pid>.sh|.ps1` → pid. */
fun parseScriptFilePid(name: String): Long? =
    Regex("""^install-(\d+)\.(sh|ps1)$""").find(name)?.groupValues?.get(1)?.toLongOrNull()

/** `.tmp.<pid>.<target-name>` (atomic-write staging) → pid. */
fun parseStagingFilePid(name: String): Long? =
    Regex("""^\.tmp\.(\d+)\.""").find(name)?.groupValues?.get(1)?.toLongOrNull()
