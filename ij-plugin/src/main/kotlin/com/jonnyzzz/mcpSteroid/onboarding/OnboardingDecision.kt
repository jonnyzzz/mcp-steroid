/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.onboarding

import com.jonnyzzz.mcpSteroid.util.text.DevrigVersion
import java.nio.file.Files
import java.nio.file.Path

/**
 * What the IDE can offer about the bridge, given the detected state.
 *
 * The scope is deliberately narrow: **whether devrig is installed and current**, nothing about agents.
 * The plugin installs the bridge; wiring an agent to it is a separate, explicit act — and there are
 * several agents (Claude Code, Codex, Gemini), so singling one out to detect would be arbitrary anyway.
 */
enum class OnboardingDecision { DEVRIG_READY, OFFER_INSTALL, OFFER_UPDATE }

/**
 * Decide what to offer. Missing devrig → offer the install. Present but stale → offer the update, because
 * the plugin's job is to get the user onto a *current* devrig, not merely onto some devrig. Otherwise
 * there is nothing to say.
 */
fun decideOnboarding(
    devrigInstalled: Boolean,
    devrigOutdated: Boolean = false,
): OnboardingDecision = when {
    !devrigInstalled -> OnboardingDecision.OFFER_INSTALL
    devrigOutdated -> OnboardingDecision.OFFER_UPDATE
    else -> OnboardingDecision.DEVRIG_READY
}

/** True iff the stable devrig launcher exists under ~/.mcp-steroid/bin for this OS. */
fun devrigInstalled(userHome: Path, windows: Boolean): Boolean {
    val name = if (windows) "devrig.cmd" else "devrig"
    return Files.isRegularFile(userHome.resolve(".mcp-steroid").resolve("bin").resolve(name))
}

/** `exec "<path>"` (POSIX) / `call "<path>"` (Windows `.cmd`) — the install-tree launcher the wrapper runs. */
private val LAUNCHER_TARGET = Regex("""(?:^|\s)(?:exec|call)\s+"([^"]+)"""", RegexOption.MULTILINE)

/**
 * The devrig version currently installed, read from the text of the stable `~/.mcp-steroid/bin/devrig`
 * wrapper — no process spawn. The wrapper (written by devrig's own `BinLauncher.renderPosixLauncher` /
 * `renderWindowsCmd`) hands off to the content-addressed install tree, whose distribution directory
 * carries the version: `…/binaries/devrig-<key>-<version>-<sha12>/devrig-<version>/bin/devrig`.
 *
 * Returns null when the wrapper is absent or its shape is not recognised — callers must treat that as
 * "version unknown", NOT as outdated, so a launcher we cannot parse never produces a false update nag.
 */
fun installedDevrigVersion(launcherText: String?): String? {
    val text = launcherText?.takeIf { it.isNotBlank() } ?: return null
    val target = LAUNCHER_TARGET.find(text)?.groupValues?.get(1) ?: return null
    // …/<distDir>/bin/devrig(.bat) — drop the trailing "bin/<launcher>" to land on the distribution dir.
    val distDir = target.replace('\\', '/').trimEnd('/').split('/').dropLast(2).lastOrNull() ?: return null
    if (!distDir.startsWith("devrig-")) return null
    return distDir.removePrefix("devrig-").takeIf { it.isNotBlank() }
}

/**
 * True iff the installed devrig is behind [latestBaseVersion] (`version-base` from `version.json`).
 *
 * Defers to [DevrigVersion.isUpdateAvailable] — the one gate devrig's own updater and the plugin's
 * update check already share — instead of comparing strings here. Two consequences worth knowing: the
 * comparison is semantic, not textual, and a SNAPSHOT install (someone's local build) is never reported
 * as stale, because it is by definition ahead of anything published.
 *
 * Unknown inputs are never "outdated" — we only nag when we actually know the user is behind.
 */
fun isDevrigOutdated(installedVersion: String?, latestBaseVersion: String?): Boolean {
    val installed = installedVersion?.takeIf { it.isNotBlank() } ?: return false
    val latest = latestBaseVersion?.takeIf { it.isNotBlank() } ?: return false
    return DevrigVersion.isUpdateAvailable(
        current = DevrigVersion.parse(installed),
        promoted = DevrigVersion.parse(latest),
    )
}
