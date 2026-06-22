/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.exists
import kotlin.io.path.readText

internal fun isWindows(): Boolean =
    System.getProperty("os.name").lowercase().contains("windows")

/**
 * Undocumented escape hatch governing the on-each-start launcher self-heal ([ensureBinLauncher]).
 * Intentionally NOT mentioned in `devrig --help` / docs — it exists for tests and for power users who
 * manage `~/.mcp-steroid/bin/devrig` themselves.
 */
internal const val ENV_BIN_NO_AUTO_REGISTER = "DEVRIG_BIN_NO_AUTO_REGISTER"

/**
 * Whether the binary should own (write + PATH-link) `~/.mcp-steroid/bin/devrig` on this start.
 *
 *  - [ENV_BIN_NO_AUTO_REGISTER] = `yes`/`true`/`1`/`on`  → OFF (explicit opt-out).
 *  - [ENV_BIN_NO_AUTO_REGISTER] = `no`/`false`/`0`/`off` → ON  (explicit opt-in — overrides the default,
 *    which is how the integration test enables it on a SNAPSHOT build).
 *  - unset / unrecognized → ON for release builds, OFF for SNAPSHOT/dev builds (and thus for tests,
 *    whose devrig is always a SNAPSHOT) so a dev build never clobbers the user's real launcher.
 *
 * The build version is read straight from the generated [DevrigVersionMetadata]; only the env value is a
 * parameter, so the env-override branches stay unit-testable without faking the version.
 */
internal fun binAutoRegisterEnabled(envValue: String? = System.getenv(ENV_BIN_NO_AUTO_REGISTER)): Boolean =
    shouldWriteLauncher(envValue, force = false)

/**
 * Whether to (re)write the launcher. Explicit env wins both ways. With no env: a passive start follows
 * the SNAPSHOT default (off for dev/test, on for release); an explicit `devrig install` ([force]) writes
 * regardless of that default — install is explicit user intent, so it must never leave a dangling
 * registration (a wrapper path registered for the agent but never written) on a dev/SNAPSHOT dist. An
 * explicit opt-out (`DEVRIG_BIN_NO_AUTO_REGISTER=yes`) still wins, even over [force].
 */
internal fun shouldWriteLauncher(envValue: String?, force: Boolean): Boolean =
    when (parseBinAutoRegisterOptOut(envValue)) {
        true -> false
        false -> true
        null -> force || !DevrigVersionMetadata.getDevrigVersion().contains("SNAPSHOT", ignoreCase = true)
    }

/** `true` = opt-out (disable), `false` = opt-in (enable), `null` = unset/unrecognized (use the default). */
private fun parseBinAutoRegisterOptOut(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
    "yes", "true", "1", "on" -> true
    "no", "false", "0", "off" -> false
    else -> null
}

/**
 * Self-registration of the user-facing `~/.mcp-steroid/bin` launcher AND its reachability on the user's
 * PATH. **The devrig binary owns both** — the install script does neither.
 *
 * On EVERY devrig start this ensures:
 *  1. `~/.mcp-steroid/bin/devrig` (POSIX) / `~/.mcp-steroid/bin/devrig.cmd` (Windows) exists and points
 *     at devrig's OWN current install tree and the JDK it is running under — so the launcher self-heals
 *     if it is missing or stale (e.g. after an upgrade repointed the install tree).
 *  2. that launcher is reachable on PATH — POSIX symlinks it into a writable PATH dir under `$HOME`
 *     (pure Java, no subprocess). Windows registers the bin dir on the user PATH via a marker-gated
 *     PowerShell call, but only when [registerWindowsPath] is true — callers pass false for the
 *     `devrig mcp` start so PowerShell never blocks the latency-sensitive MCP serve path (the agent
 *     launches the wrapper by absolute path anyway); interactive/`install` invocations pass true.
 *
 * The launcher is rewritten ONLY when its content actually changed (normalized comparison), never on
 * every launch — and writes are atomic (temp file + atomic move), so a concurrent agent that is mid-read
 * of the file (the contract: it can change WHILE the binary runs) never sees a torn launcher. When the
 * content already matches, a lost executable bit is still repaired.
 *
 * [force] = an explicit `devrig install`: write regardless of the SNAPSHOT/dev passive-start default
 * (but still honoring an explicit opt-out), so install never registers a wrapper it didn't write.
 *
 * Best-effort: any failure to resolve devrig's root, write the launcher, or touch PATH is logged to
 * stderr and swallowed — it must never prevent `devrig mcp` from serving. All output goes to stderr;
 * stdout is the MCP JSON-RPC channel.
 */
fun ensureBinLauncher(home: HomePaths, force: Boolean = false, registerWindowsPath: Boolean = true) {
    if (!shouldWriteLauncher(System.getenv(ENV_BIN_NO_AUTO_REGISTER), force)) {
        return
    }
    try {
        ensureBinLauncher(
            home = home,
            isWin = isWindows(),
            ownRoot = DevrigRoot.path,
            ownJava = Path.of(System.getProperty("java.home")).toAbsolutePath().normalize(),
            userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize(),
            pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparatorChar),
            registerWindowsPath = registerWindowsPath,
        )
    } catch (e: Exception) {
        System.err.println("[mcp-steroid] could not (re)write the devrig launcher: $e")
    }
}

/**
 * Testable core — every input is explicit (no environment lookups), so the OS branch and path logic are
 * unit-testable without touching `os.name` / `user.home` / `java.home` / `PATH`.
 *
 * There is NO "only when installed under ~/.mcp-steroid" guard: the contract is that the launcher is
 * (re)written on EVERY start, pointing at wherever this binary currently runs from. The safety against a
 * dev build clobbering a real launcher is the SNAPSHOT/env gate in [binAutoRegisterEnabled], not a
 * location check — so the wrapper records ABSOLUTE paths and works from any install location (including
 * the `/tmp/devrig` the integration test copies the dist to).
 */
internal fun ensureBinLauncher(
    home: HomePaths,
    isWin: Boolean,
    ownRoot: Path,
    ownJava: Path,
    userHome: Path,
    pathDirs: List<String>,
    registerWindowsPath: Boolean = true,
) {
    val ownBin = ownRoot.resolve("bin").resolve(if (isWin) "devrig.bat" else "devrig").toAbsolutePath().normalize()
    ensureBinLauncherCore(home, isWin, ownBin, ownJava.toAbsolutePath().normalize(), userHome, pathDirs, registerWindowsPath)
}

/**
 * The launcher-writing core: write `~/.mcp-steroid/bin/devrig`(`.cmd`) so it pins [jdkHome] via
 * `DEVRIG_JAVA_HOME` and execs the install-tree launcher [ownBin], then ensure it is on PATH. Both inputs
 * are EXPLICIT (no `DevrigRoot`/`java.home` lookups) so `devrig install devrig` can register the exact
 * launcher + JDK the install script computed and passed in.
 */
internal fun ensureBinLauncherCore(
    home: HomePaths,
    isWin: Boolean,
    ownBin: Path,
    jdkHome: Path,
    userHome: Path,
    pathDirs: List<String>,
    registerWindowsPath: Boolean = true,
) {
    if (isWin) {
        // CMD-only launcher: a single self-contained devrig.cmd. No PowerShell at launch — PS is only
        // needed by the install SCRIPT, not the launcher.
        val cmd = home.binDir.resolve("devrig.cmd")
        writeIfChanged(home.binDir, cmd, renderWindowsCmd(ownBin, jdkHome), executable = false, ownBin = ownBin)
        // PATH registration spawns PowerShell, so skip it on the `devrig mcp` hot path (registerWindowsPath
        // = false) — it would block the first serve until the marker exists. Interactive/install pass true.
        if (registerWindowsPath) ensureWindowsPathEntry(home.binDir)
    } else {
        val devrig = home.binDir.resolve("devrig")
        writeIfChanged(home.binDir, devrig, renderPosixLauncher(ownBin, jdkHome), executable = true, ownBin = ownBin)
        ensurePosixPathSymlink(home.binDir, devrig, userHome, pathDirs)
    }
}

/**
 * Register `~/.mcp-steroid/bin/devrig`(`.cmd`) + PATH for an EXPLICIT install-tree launcher [installScript]
 * and [jdkHome] (rather than deriving them from the running process). This is what `devrig install devrig`
 * uses: the install script unpacks devrig + a JDK and passes their paths in. [force] defaults true
 * (explicit user intent — writes even on a SNAPSHOT/dev dist; an explicit `DEVRIG_BIN_NO_AUTO_REGISTER`
 * opt-out still wins).
 */
fun ensureBinLauncherForInstallScript(
    home: HomePaths,
    installScript: Path,
    jdkHome: Path,
    force: Boolean = true,
    registerWindowsPath: Boolean = true,
) {
    if (!shouldWriteLauncher(System.getenv(ENV_BIN_NO_AUTO_REGISTER), force)) return
    try {
        ensureBinLauncherCore(
            home = home,
            isWin = isWindows(),
            ownBin = installScript.toAbsolutePath().normalize(),
            jdkHome = jdkHome.toAbsolutePath().normalize(),
            userHome = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize(),
            pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparatorChar),
            registerWindowsPath = registerWindowsPath,
        )
    } catch (e: Exception) {
        System.err.println("[mcp-steroid] could not (re)write the devrig launcher: $e")
    }
}

/** The POSIX wrapper: pins the JDK devrig runs under via DEVRIG_JAVA_HOME, then execs the install-tree launcher. */
internal fun renderPosixLauncher(launcher: Path, jdkHome: Path): String =
    "#!/bin/sh\n" +
        "# devrig launcher — managed by the devrig binary. Writes nothing to stdout (MCP stdio channel).\n" +
        "# Pins the JDK devrig runs under via DEVRIG_JAVA_HOME (its supported runtime), then hands off to\n" +
        "# the install-tree devrig launcher.\n" +
        "DEVRIG_JAVA_HOME=\"$jdkHome\"; export DEVRIG_JAVA_HOME\n" +
        "exec \"$launcher\" \"\$@\"\n"

/**
 * The self-contained Windows launcher: pure batch, NO PowerShell at launch. It ALWAYS pins the JDK devrig
 * runs under via DEVRIG_JAVA_HOME (its supported runtime), then `call`s the install-tree devrig.bat.
 * STDOUT cleanliness (the MCP JSON-RPC channel): `@echo off` + `set`/`call` emit nothing to stdout; only
 * the inner devrig.bat → java does. The agent invokes this via `cmd.exe /d /c` — see
 * [DevrigUserLauncher.invocation].
 */
internal fun renderWindowsCmd(launcher: Path, jdkHome: Path): String =
    "@echo off\r\n" +
        "set \"DEVRIG_JAVA_HOME=$jdkHome\"\r\n" +
        "call \"$launcher\" %*\r\n"

private fun writeIfChanged(dir: Path, target: Path, desired: String, executable: Boolean, ownBin: Path) {
    // An unreadable existing launcher (non-UTF-8 bytes, wrong file type, transient IO) counts as
    // "changed" so a corrupt launcher self-heals rather than being left in place.
    val current = if (!target.exists()) null else try {
        target.readText()
    } catch (e: Exception) {
        System.err.println("[mcp-steroid] existing launcher $target is unreadable ($e); rewriting it")
        null
    }
    if (current != null && normalizeLauncher(current) == normalizeLauncher(desired)) {
        // Content already correct — but a launcher that lost its executable bit (e.g. a copy that dropped
        // perms) must still self-heal, so re-set +x in place without rewriting the bytes.
        if (executable && !Files.isExecutable(target)) {
            setExecutable(target)
            System.err.println("[mcp-steroid] restored the executable bit on $target")
        }
        return
    }
    writeAtomically(dir, target, desired, executable)
    System.err.println("[mcp-steroid] (re)wrote $target -> $ownBin")
}

/** Tolerant of CRLF↔LF and trailing-newline differences so we rewrite ONLY on a real content change. */
internal fun normalizeLauncher(s: String): String = s.replace("\r\n", "\n").trimEnd('\n')

/**
 * Make `bin/devrig` reachable on PATH by symlinking it into the first writable PATH directory under
 * `$HOME`. Mirrors the install.sh logic that this now replaces: never edits shell profiles, never
 * touches system dirs, never clobbers a `devrig` that is not already our own symlink, and never
 * self-links the bin dir. Best-effort — a missing PATH dir is fine; the launcher still works by full path.
 */
internal fun ensurePosixPathSymlink(binDir: Path, binDevrig: Path, userHome: Path, pathDirs: List<String>) {
    val target = binDevrig.toAbsolutePath().normalize()
    val binDirNorm = binDir.toAbsolutePath().normalize()
    val homeNorm = userHome.toAbsolutePath().normalize()
    for (entry in pathDirs) {
        if (entry.isBlank()) continue
        val dir = try {
            Path.of(entry).toAbsolutePath().normalize()
        } catch (e: Exception) {
            System.err.println("[mcp-steroid] skipping malformed PATH entry '$entry': $e")
            continue
        }
        if (dir == binDirNorm) continue                 // never self-link
        if (!dir.startsWith(homeNorm)) continue          // only directories under your home
        if (!Files.isDirectory(dir) || !Files.isWritable(dir)) continue
        val link = dir.resolve("devrig")
        // Never clobber a `devrig` we did not create: a real (non-symlink) file, or a symlink pointing
        // elsewhere. readSymbolicLink is only called once we know it IS a symlink, so it cannot throw
        // NotLinkException; a rare IO error bubbles to ensureBinLauncher's best-effort catch (logged).
        if (Files.isSymbolicLink(link)) {
            // Already OUR symlink → nothing to do; return silently so we don't churn the FS or log on
            // every start (the on-each-start contract means this is the steady-state path). resolveSibling
            // handles a RELATIVE link target (resolve against the link's own dir, not the process CWD).
            if (link.resolveSibling(Files.readSymbolicLink(link)).normalize() == target) return
            continue // foreign symlink — do not clobber
        }
        if (link.exists()) continue // foreign real file — do not clobber
        try {
            // We only reach here when nothing exists at `link`, so no deleteIfExists is needed.
            Files.createSymbolicLink(link, target)
            System.err.println("[mcp-steroid] linked $link -> $target")
            return
        } catch (e: Exception) {
            System.err.println("[mcp-steroid] could not symlink $link -> $target ($e); trying the next PATH dir")
        }
    }
    System.err.println(
        "[mcp-steroid] devrig is installed at $target but no writable PATH dir under \$HOME was found; " +
            "add it to PATH manually: export PATH=\"\$HOME/.mcp-steroid/bin:\$PATH\"",
    )
}

/**
 * Register `bin/devrig.cmd` on the **user** PATH so `devrig` is runnable directly from a terminal
 * (the POSIX side does the equivalent with a symlink). The JDK cannot persist the user environment in
 * pure Java, so we use PowerShell to update `HKCU\Environment\Path` — but **only once per install**: a
 * marker file gates it so we do NOT spawn PowerShell on every `devrig mcp` / `version` / `install` start
 * (the bin dir is stable across upgrades, so one registration lasts). The PowerShell **deduplicates**:
 * it strips every existing entry equal to the bin dir and appends exactly one, so re-runs (or a stale
 * entry from an earlier install) never accumulate duplicates. No `setx` (it truncates PATH at 1024
 * chars). stdout is discarded (the MCP JSON-RPC channel); it narrates to stderr. Agents launch the
 * wrapper by ABSOLUTE path (see [DevrigUserLauncher.invocation]), so MCP works even before a new shell
 * picks up the updated PATH. Best-effort: a missing marker re-runs it; any failure is logged and ignored.
 */
internal fun ensureWindowsPathEntry(binDir: Path) {
    val binDirNorm = binDir.toAbsolutePath().normalize()
    val marker = binDirNorm.resolve(".user-path-registered")
    if (Files.exists(marker)) return
    val bin = binDirNorm.toString()
    // De-dup: drop blanks and any existing == bin entry, then append exactly one bin entry.
    val script =
        "\$d = '${bin.replace("'", "''")}'; " +
            "\$p = [Environment]::GetEnvironmentVariable('Path','User'); if (\$null -eq \$p) { \$p = '' }; " +
            "\$parts = @(\$p -split ';' | Where-Object { \$_ -ne '' -and \$_ -ne \$d }); " +
            "\$new = (\$parts + \$d) -join ';'; " +
            "[Environment]::SetEnvironmentVariable('Path', \$new, 'User'); " +
            "[Console]::Error.WriteLine('[mcp-steroid] registered ' + \$d + ' on the user PATH (1 entry; open a new terminal to use it)')"
    try {
        val exit = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
            .redirectErrorStream(false)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD) // keep the MCP JSON-RPC channel clean
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
            .waitFor()
        if (exit == 0) {
            try {
                Files.writeString(marker, bin)
            } catch (e: Exception) {
                System.err.println("[mcp-steroid] could not write the user-PATH marker $marker: $e")
            }
        } else {
            System.err.println("[mcp-steroid] PowerShell PATH registration exited $exit; will retry next start")
        }
    } catch (e: Exception) {
        System.err.println(
            "[mcp-steroid] could not register $bin on the user PATH ($e); add it manually via " +
                "System Properties -> Environment Variables (User PATH), or run devrig by full path",
        )
    }
}

private fun writeAtomically(dir: Path, target: Path, content: String, executable: Boolean) {
    Files.createDirectories(dir)
    val tmp = dir.resolve(".tmp.${ProcessHandle.current().pid()}.${target.fileName}")
    try {
        Files.writeString(tmp, content)
        // Set the executable bit on the staging file BEFORE the move, so the final launcher is never
        // momentarily non-executable (mirrors install.sh: chmod +x then mv).
        if (executable) setExecutable(tmp)
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            System.err.println("[mcp-steroid] atomic move unsupported (${e.message}); using a plain move")
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        // On success the move consumed tmp (this is a no-op); on any failure above, sweep the orphan
        // staging file instead of leaking a `.tmp.<pid>.devrig` into the bin dir.
        if (Files.exists(tmp)) {
            try {
                Files.deleteIfExists(tmp)
            } catch (e: Exception) {
                System.err.println("[mcp-steroid] could not remove staging file $tmp: $e")
            }
        }
    }
}

private fun setExecutable(file: Path) {
    try {
        val perms = Files.getPosixFilePermissions(file).toMutableSet()
        perms += PosixFilePermission.OWNER_EXECUTE
        perms += PosixFilePermission.GROUP_EXECUTE
        perms += PosixFilePermission.OTHERS_EXECUTE
        Files.setPosixFilePermissions(file, perms)
    } catch (e: UnsupportedOperationException) {
        // Non-POSIX filesystem (e.g. Windows): fall back to the File API.
        System.err.println("[mcp-steroid] POSIX permissions unsupported (${e.message}); using File.setExecutable")
        file.toFile().setExecutable(true, false)
    }
}
