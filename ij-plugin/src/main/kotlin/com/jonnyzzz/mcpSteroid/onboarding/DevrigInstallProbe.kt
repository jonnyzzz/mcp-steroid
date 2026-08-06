/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path

/**
 * The one fact the plugin's devrig surfaces render: is the bridge installed — true iff the stable
 * launcher ([devrigBinPath], `~/.mcp-steroid/bin`) exists for this OS.
 *
 * Deliberately nothing else. There is no version to show and no "outdated" axis — devrig updates
 * itself (its supervisor re-runs the installer, see `docs/updates-check/devrig-auto-update.md`), so
 * the IDE never has a reason to name or nag about the build it found. And there is no cache: every
 * surface computes this fresh, off the EDT, at the one moment it is about to be shown.
 *
 * **Does file I/O — background threads only.** Parameters exist for tests; production callers take
 * the defaults.
 */
fun devrigInstalled(
    userHome: Path = Path.of(System.getProperty("user.home")),
    windows: Boolean = SystemInfo.isWindows,
): Boolean = Files.isRegularFile(devrigBinPath(userHome, windows))
