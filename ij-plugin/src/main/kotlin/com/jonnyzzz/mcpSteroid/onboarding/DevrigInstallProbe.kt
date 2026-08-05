/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path

/**
 * The facts the plugin's two devrig surfaces render: is the bridge installed, and which version.
 *
 * Deliberately nothing else. There is no "outdated" axis — devrig updates itself (its supervisor
 * re-runs the installer, see `docs/updates-check/devrig-auto-update.md`), so the IDE never has to
 * nag about a stale one. And there is no cache: every surface computes this fresh, off the EDT,
 * at the one moment it is about to be shown.
 */
data class DevrigInstallState(
    val installed: Boolean,
    /** Version read out of the stable launcher, or null when unknown (see [installedDevrigVersion]). */
    val version: String?,
)

/**
 * The one-shot probe behind [DevrigInstallState]: two file reads. **Does file I/O — background
 * threads only.** Parameters exist for tests; production callers take the defaults.
 */
fun probeDevrigInstallState(
    userHome: Path = Path.of(System.getProperty("user.home")),
    windows: Boolean = SystemInfo.isWindows,
): DevrigInstallState {
    val installed = devrigInstalled(userHome, windows)
    val launcherText = if (installed) readTextOrNull(devrigBinPath(userHome, windows)) else null
    return DevrigInstallState(installed = installed, version = installedDevrigVersion(launcherText))
}

private fun readTextOrNull(path: Path): String? = try {
    if (Files.isRegularFile(path)) Files.readString(path) else null
} catch (e: Exception) {
    Logger.getInstance("com.jonnyzzz.mcpSteroid.onboarding.DevrigInstallProbe")
        .debug("cannot read $path: ${e.message}")
    null
}

/** True iff the stable devrig launcher ([devrigBinPath], `~/.mcp-steroid/bin`) exists for this OS. */
fun devrigInstalled(userHome: Path, windows: Boolean): Boolean =
    Files.isRegularFile(devrigBinPath(userHome, windows))

/** `exec "<path>"` (POSIX) / `call "<path>"` (Windows `.cmd`) — the install-tree launcher the wrapper runs. */
private val LAUNCHER_TARGET = Regex("""(?:^|\s)(?:exec|call)\s+"([^"]+)"""", RegexOption.MULTILINE)

/**
 * The devrig version currently installed, read from the text of the stable `~/.mcp-steroid/bin/devrig`
 * wrapper — no process spawn. The wrapper (written by devrig's own `BinLauncher.renderPosixLauncher` /
 * `renderWindowsCmd`) hands off to the content-addressed install tree, whose distribution directory
 * carries the version: `…/binaries/devrig-<key>-<version>-<sha12>/devrig-<version>/bin/devrig`.
 *
 * Returns null when the wrapper is absent or its shape is not recognised — callers must treat that as
 * "version unknown", never guess.
 */
fun installedDevrigVersion(launcherText: String?): String? {
    val text = launcherText?.takeIf { it.isNotBlank() } ?: return null
    val target = LAUNCHER_TARGET.find(text)?.groupValues?.get(1) ?: return null
    // …/<distDir>/bin/devrig(.bat) — drop the trailing "bin/<launcher>" to land on the distribution dir.
    val distDir = target.replace('\\', '/').trimEnd('/').split('/').dropLast(2).lastOrNull() ?: return null
    if (!distDir.startsWith("devrig-")) return null
    return distDir.removePrefix("devrig-").takeIf { it.isNotBlank() }
}
