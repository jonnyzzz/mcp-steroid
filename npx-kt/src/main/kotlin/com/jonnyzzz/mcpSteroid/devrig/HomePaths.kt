/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.PidMarker
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

class HomePaths(val home: Path) {
    val logsDir: Path get() = home.resolve("logs")
    val backendsDir: Path get() = home.resolve("backends")
    val cachesDir: Path get() = home.resolve("caches")
    val downloadsDir: Path get() = home.resolve("downloads")
    val stateDir: Path get() = home.resolve("state")
    val executionStorageDir: Path get() = home.resolve("execution-storage")

    /**
     * Directory holding the stable, user-facing devrig launcher (`bin/devrig` on POSIX, `bin/devrig.cmd`
     * on Windows). The devrig binary OWNS this directory: it (re)writes the launcher on every start (see
     * [ensureBinLauncher]) and points agent MCP registrations + the user-PATH symlink at `bin/devrig`,
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

    fun mkdirsAll() {
        listOf(logsDir, backendsDir, cachesDir, downloadsDir, stateDir, binDir).forEach { Files.createDirectories(it) }
    }
}

/**
 * devrig's home is hardcoded to `~/.mcp-steroid` and is NOT configurable — there is no `DEVRIG_HOME`
 * override (it was removed; the plugin↔devrig marker contract pins the location anyway). To sandbox the
 * home in a test, launch the devrig process with a redirected `HOME` (which sets the JVM's `user.home`).
 */
fun resolveHomePaths(): HomePaths =
    HomePaths(Path.of(System.getProperty("user.home"), ".mcp-steroid").toAbsolutePath().normalize())

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
