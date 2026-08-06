/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.util.text.DevrigVersion
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText

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
 *  - unset / unrecognized → ON for CI/release builds, OFF for SNAPSHOT/dev builds so a dev build never
 *    clobbers the user's real launcher.
 *
 * The build version defaults to the generated [DevrigVersionMetadata], so production callers never fake
 * it; [shouldWriteLauncher]'s explicit `devrigVersion` parameter lets tests pin either lane
 * deterministically (the baked version is SNAPSHOT locally but a `-jb-`/`-gh-` CI version on TeamCity —
 * see the root build.gradle.kts BUILD_NUMBER handling).
 */
internal fun binAutoRegisterEnabled(envValue: String? = System.getenv(ENV_BIN_NO_AUTO_REGISTER)): Boolean =
    shouldWriteLauncher(envValue, force = false)

/**
 * Whether to (re)write the launcher. Explicit env wins both ways. With no env: a passive start follows
 * the SNAPSHOT default (off for dev/test, on for CI/release); an explicit `devrig install` ([force])
 * writes regardless of that default — install is explicit user intent, so it must never leave a dangling
 * registration (a wrapper path registered for the agent but never written) on a dev/SNAPSHOT dist. An
 * explicit opt-out (`DEVRIG_BIN_NO_AUTO_REGISTER=yes`) still wins, even over [force].
 *
 * [devrigVersion] defaults to the baked build version; tests inject a fixed version per lane so the
 * whole matrix runs on every machine regardless of which lane built the test JVM.
 */
internal fun shouldWriteLauncher(
    envValue: String?,
    force: Boolean,
    devrigVersion: String = DevrigVersionMetadata.getDevrigVersion(),
): Boolean =
    when (parseBinAutoRegisterOptOut(envValue)) {
        true -> false
        false -> true
        null -> force || !devrigVersion.contains("SNAPSHOT", ignoreCase = true)
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
 *     PowerShell call: once the marker exists every later start returns without spawning anything, so
 *     registration is not worth branching on per command.
 *
 * The launcher is rewritten ONLY when its content actually changed (normalized comparison), never on
 * every launch — and writes are atomic (temp file + atomic move), so a concurrent agent that is mid-read
 * of the file (the contract: it can change WHILE the binary runs) never sees a torn launcher. When the
 * content already matches, a lost executable bit is still repaired. An existing launcher whose stamped
 * header version is strictly newer than this binary is never overwritten at all — see
 * [shouldKeepNewerLauncher] (#373).
 *
 * [force] = an explicit `devrig install`: write regardless of the SNAPSHOT/dev passive-start default
 * (but still honoring an explicit opt-out), so install never registers a wrapper it didn't write.
 *
 * Best-effort: any failure to resolve devrig's root, write the launcher, or touch PATH is logged to
 * stderr and swallowed — it must never prevent `devrig mcp` from serving. All output goes to stderr;
 * stdout is the MCP JSON-RPC channel.
 */
fun ensureBinLauncher(home: HomePaths, force: Boolean = false) {
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
) {
    val ownBin = ownRoot.resolve("bin").resolve(if (isWin) "devrig.bat" else "devrig").toAbsolutePath().normalize()
    ensureBinLauncherCore(home, isWin, ownBin, ownJava.toAbsolutePath().normalize(), userHome, pathDirs)
}

/**
 * The launcher-writing core: write `~/.mcp-steroid/bin/devrig`(`.cmd`) so it pins [jdkHome] via
 * `DEVRIG_JAVA_HOME` and execs the install-tree launcher [ownBin], then ensure it is on PATH. Both inputs
 * are EXPLICIT (no `DevrigRoot`/`java.home` lookups) for testability.
 */
internal fun ensureBinLauncherCore(
    home: HomePaths,
    isWin: Boolean,
    ownBin: Path,
    jdkHome: Path,
    userHome: Path,
    pathDirs: List<String>,
    buildVersion: DevrigVersion = DevrigVersionMetadata.getBuildVersion(),
) {
    if (isWin) {
        // CMD-only launcher: a single self-contained devrig.cmd. No PowerShell at launch — PS is only
        // needed by the install SCRIPT, not the launcher.
        val cmd = home.binDir.resolve("devrig.cmd")
        writeIfChanged(home.binDir, cmd, renderWindowsCmd(ownBin, jdkHome, buildVersion.value), executable = false, ownBin = ownBin, buildVersion = buildVersion)
        ensureWindowsPathEntry(home.binDir)
    } else {
        val devrig = home.binDir.resolve("devrig")
        writeIfChanged(home.binDir, devrig, renderPosixLauncher(ownBin, jdkHome, buildVersion.value), executable = true, ownBin = ownBin, buildVersion = buildVersion)
        ensurePosixPathSymlink(home.binDir, devrig, userHome, pathDirs)
    }
}

/**
 * The POSIX wrapper: pins the JDK devrig runs under via DEVRIG_JAVA_HOME, then execs the install-tree
 * launcher. The header stamps the generating devrig [version] and the source install tree — the version
 * line is machine-parseable (see [parseLauncherVersion]) and drives the regeneration guard (#373).
 */
internal fun renderPosixLauncher(
    launcher: Path,
    jdkHome: Path,
    version: String = DevrigVersionMetadata.getDevrigVersion(),
): String {
    val launcherStr = launcher.toString().replace('\\', '/')
    val jdkStr = jdkHome.toString().replace('\\', '/')
    return "#!/bin/sh\n" +
        "# devrig launcher version: ${headerSafe(version)}\n" +
        "# devrig launcher source: ${headerSafe(launcherStr)}\n" +
        "# devrig launcher — managed by the devrig binary. Writes nothing to stdout (MCP stdio channel).\n" +
        "# Pins the JDK devrig runs under via DEVRIG_JAVA_HOME (its supported runtime), then hands off to\n" +
        "# the install-tree devrig launcher.\n" +
        "DEVRIG_JAVA_HOME=\"$jdkStr\"; export DEVRIG_JAVA_HOME\n" +
        "exec \"$launcherStr\" \"\$@\"\n"
}

/**
 * The self-contained Windows launcher: pure batch, NO PowerShell at launch. It ALWAYS pins the JDK devrig
 * runs under via DEVRIG_JAVA_HOME (its supported runtime), then `call`s the install-tree devrig.bat.
 * STDOUT cleanliness (the MCP JSON-RPC channel): `@echo off` + `set`/`call` emit nothing to stdout; only
 * the inner devrig.bat → java does. The agent invokes this via `cmd.exe /d /c` — see
 * [DevrigUserLauncher.invocation].
 */
internal fun renderWindowsCmd(
    launcher: Path,
    jdkHome: Path,
    version: String = DevrigVersionMetadata.getDevrigVersion(),
): String =
    // The header sits AFTER `@echo off`: anything before it would echo to stdout — the MCP JSON-RPC channel.
    // The source path is QUOTED: `rem` does not neutralize batch metacharacters, so an unquoted install
    // path containing `&` would terminate the rem and execute the rest as a command.
    "@echo off\r\n" +
        "rem devrig launcher version: ${headerSafe(version)}\r\n" +
        "rem devrig launcher source: \"${headerSafe(launcher.toString())}\"\r\n" +
        "set \"DEVRIG_JAVA_HOME=$jdkHome\"\r\n" +
        "call \"$launcher\" %*\r\n"

/** Header fields must stay on their own comment line: CR/LF in interpolated values become spaces. */
private fun headerSafe(value: String): String = value.replace('\r', ' ').replace('\n', ' ')

/**
 * Matches the machine-parseable version header stamped into generated launchers — POSIX
 * (`# devrig launcher version: …`) and Windows (`rem devrig launcher version: …`).
 */
private val LAUNCHER_VERSION_HEADER =
    Regex("""^(?:#|rem)\s+devrig launcher version:\s*(\S+)""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))

/** Version stamped in a launcher's header, or null when the header is absent (pre-#373 launchers). */
fun parseLauncherVersion(content: String): DevrigVersion? =
    LAUNCHER_VERSION_HEADER.find(content)?.let { DevrigVersion.parse(it.groupValues[1]) }

/**
 * The regeneration guard (#373): an existing launcher stamped with a STRICTLY NEWER version than the
 * running binary must not be overwritten — an older devrig process never clobbers a newer launcher.
 * Snapshot rule (from [DevrigVersion]): a SNAPSHOT-stamped launcher counts as newer than any release,
 * so a release binary never overwrites a deliberately installed dev launcher. A missing or headerless
 * launcher never blocks regeneration (migration + corrupt-file self-heal keep working).
 */
fun shouldKeepNewerLauncher(existingContent: String?, currentVersion: DevrigVersion): Boolean {
    val existing = existingContent?.let { parseLauncherVersion(it) } ?: return false
    return existing > currentVersion
}

private fun writeIfChanged(dir: Path, target: Path, desired: String, executable: Boolean, ownBin: Path, buildVersion: DevrigVersion) {
    Files.createDirectories(dir)
    // An unreadable existing launcher (non-UTF-8 bytes, wrong file type, transient IO) counts as
    // "changed" so a corrupt launcher self-heals rather than being left in place.
    val current = if (!target.exists()) null else try {
        target.readText()
    } catch (e: Exception) {
        System.err.println("[mcp-steroid] existing launcher $target is unreadable ($e); rewriting it")
        null
    }
    // The #373 guard: never overwrite a launcher stamped with a strictly newer version — an older
    // devrig start (a stale install still registered somewhere) must not re-register itself over a
    // newer launcher. No cross-process lock around read→check→write: replacement is an atomic
    // same-directory move (see replaceLauncherFile), so a concurrent racing start can at worst make a
    // stale-read guard decision — never a torn file — and the newer devrig re-heals the launcher on
    // its next start anyway.
    if (shouldKeepNewerLauncher(current, buildVersion)) {
        val existing = current?.let { parseLauncherVersion(it) }
        System.err.println(
            "[mcp-steroid] keeping $target: its launcher version ${existing?.value} " +
                "is newer than this devrig ${buildVersion.value}",
        )
        // Content-neutral healing still applies: a kept NEWER launcher that lost its executable bit
        // would otherwise stay unusable forever (no devrig would ever rewrite it).
        if (executable && !Files.isExecutable(target)) {
            setExecutable(target)
            System.err.println("[mcp-steroid] restored the executable bit on $target")
        }
        return
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
            "add it to PATH manually: export PATH=\"\$HOME/$DEVRIG_HOME_DIR_NAME/bin:\$PATH\"",
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
        val process = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
            .redirectErrorStream(false)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD) // keep the MCP JSON-RPC channel clean
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        runCatching { process.outputStream.close() } // stdin: immediate EOF — never an open pipe the child could block on
        // This runs on the devrig start-up path: an unbounded waitFor() would let a stuck
        // powershell block every `devrig mcp` start forever. Bounded + killed instead.
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            System.err.println("[mcp-steroid] PowerShell PATH registration timed out after 60s and was killed; will retry next start")
        } else if (process.exitValue() == 0) {
            try {
                Files.writeString(marker, bin)
            } catch (e: Exception) {
                System.err.println("[mcp-steroid] could not write the user-PATH marker $marker: $e")
            }
        } else {
            System.err.println("[mcp-steroid] PowerShell PATH registration exited ${process.exitValue()}; will retry next start")
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
    replaceLauncherFile(target, content, executable)
}

/**
 * Replace [target] with [content] via a sibling `.new<pid>` staging file: move it onto the target
 * (atomic, then plain); if blocked (Windows holds the launcher open), rename the original to `.old<pid>`
 * — NTFS allows renaming an open file — and move again; delete the `.old<pid>`. Any failure retries the
 * whole sequence after 10 ms, up to 5 attempts; then the last failure propagates to the caller's
 * best-effort catch. Crash leftovers (`.new<pid>`/`.old<pid>`) are accepted and never swept.
 */
fun replaceLauncherFile(
    target: Path,
    content: String,
    executable: Boolean,
    move: (from: Path, to: Path, atomic: Boolean) -> Unit = ::moveFile,
) {
    val pid = ProcessHandle.current().pid()
    val fresh = target.resolveSibling("${target.fileName}.new$pid")
    val old = target.resolveSibling("${target.fileName}.old$pid")
    var lastFailure: Exception? = null
    for (attempt in 1..5) {
        if (attempt > 1) Thread.sleep(10)
        try {
            Files.writeString(fresh, content)
            if (executable) setExecutable(fresh) // +x BEFORE any move: the launcher is never non-executable
            try {
                moveOnto(fresh, target, move)
            } catch (blocked: Exception) {
                System.err.println("[mcp-steroid] $target is held open ($blocked); renaming it aside")
                move(target, old, true) // park the held-open original; its open handles follow the rename
                moveOnto(fresh, target, move)
            }
            Files.deleteIfExists(old)
            return
        } catch (e: Exception) {
            lastFailure = e
            System.err.println("[mcp-steroid] replace of $target, attempt $attempt/5 failed: $e")
        }
    }
    throw lastFailure ?: IllegalStateException("no replace attempt was made for $target")
}

/** The two move attempts of the sequence: atomic first, plain second; the plain failure propagates. */
private fun moveOnto(from: Path, to: Path, move: (from: Path, to: Path, atomic: Boolean) -> Unit) {
    try {
        move(from, to, true)
        return
    } catch (e: Exception) {
        System.err.println("[mcp-steroid] atomic move $from -> $to failed ($e); trying a plain move")
    }
    move(from, to, false)
}

/** Default production move; the [replaceLauncherFile] seam lets tests inject failures. */
private fun moveFile(from: Path, to: Path, atomic: Boolean) {
    if (atomic) {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } else {
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
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
