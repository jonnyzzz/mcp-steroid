/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.PidMarker
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Name of the devrig home directory under the user's home: `~/.mcp-steroid`.
 *
 * The ONLY place production code spells the home directory literal. Every path under the devrig
 * home resolves through [resolveHomePaths] / [HomePaths], or through [PidMarker.markerDirectory]
 * (which uses this constant) for the plugin-side marker contract. The `.mcp-steroid` marker
 * FILENAME suffix in [PidMarker] is a separate contract (a file extension, not this directory)
 * and deliberately stays spelled there.
 */
const val DEVRIG_HOME_DIR_NAME: String = ".mcp-steroid"

class HomePaths(val home: Path) {
    val logsDir: Path get() = home.resolve("logs")
    val backendsDir: Path get() = home.resolve("backends")
    val cachesDir: Path get() = home.resolve("caches")
    val downloadsDir: Path get() = home.resolve("downloads")
    val stateDir: Path get() = home.resolve("state")
    val executionStorageDir: Path get() = home.resolve("runs")

    /**
     * Auto-update coordination files (`lock`, `update-<pid>-version-<v>`, `updated-<v>`, counters,
     * downloaded install scripts) — the contract in `docs/updates-check/devrig-auto-update.md`.
     */
    val updateDir: Path get() = home.resolve("update")

    /**
     * Directory holding the stable, user-facing devrig launcher (`bin/devrig` on POSIX, `bin/devrig.cmd`
     * on Windows). The devrig binary OWNS this directory: it (re)writes the launcher on every start (see
     * `ensureBinLauncher` in the devrig CLI) and points agent MCP registrations + the user-PATH symlink at `bin/devrig`,
     * never at the content-addressed install tree (which changes on every upgrade).
     */
    val binDir: Path get() = home.resolve("bin")

    /**
     * Directory where the IDE plugin writes per-pid markers and devrig reads them from — always
     * `~/.mcp-steroid/markers`, the same fixed location [home] resolves to. This is the plugin↔devrig
     * contract for marker discovery, so it must never be relocated.
     */
    val markersDir: Path get() = PidMarker.markerDirectory(Path.of(System.getProperty("user.home")))

    fun backendDir(id: String): Path = backendsDir.resolve(id)
    fun cacheDir(id: String): Path = cachesDir.resolve(id)
    fun pidFile(id: String): Path = stateDir.resolve("$id.pid")

    /**
     * Scratch directory (`~/.mcp-steroid/tmp`) for files devrig materializes for the caller to look
     * at — today, images decoded out of a [com.jonnyzzz.mcpSteroid.mcp.ContentItem.Image] returned by
     * ANY tool. Not part of [mkdirsAll]'s fixed layout: it is created here, on first use, rather than
     * unconditionally on every devrig start, because most invocations never render an image.
     */
    fun tmpDir(): Path = home.resolve("tmp").also { Files.createDirectories(it) }

    fun mkdirsAll() {
        listOf(logsDir, backendsDir, cachesDir, downloadsDir, stateDir, binDir, updateDir).forEach { Files.createDirectories(it) }
    }
}

/**
 * devrig's home is hardcoded to `~/.mcp-steroid` and is NOT configurable — there is no `DEVRIG_HOME`
 * override (it was removed; the plugin↔devrig marker contract pins the location anyway). To sandbox the
 * home in a test, launch the devrig process with a redirected `HOME` (which sets the JVM's `user.home`).
 */
fun resolveHomePaths(): HomePaths =
    HomePaths(Path.of(System.getProperty("user.home"), DEVRIG_HOME_DIR_NAME).toAbsolutePath().normalize())

fun resolveHomePathsOrDie(): HomePaths {
    try {
        val homePaths = resolveHomePaths()
        homePaths.mkdirsAll()
        return homePaths
    } catch (e: Throwable) {
        System.err.println("Startup failure: ${e.message}")
        e.printStackTrace(System.err)
        exitProcess(64)
    }
}
