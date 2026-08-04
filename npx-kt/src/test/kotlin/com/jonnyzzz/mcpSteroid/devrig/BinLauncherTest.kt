/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import com.jonnyzzz.mcpSteroid.util.text.DevrigVersion
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BinLauncherTest {

    // ── env / version gate ──────────────────────────────────────────────────────────────────────
    // The test JVM's baked DevrigVersionMetadata depends on which lane BUILT it: a local/dev build bakes
    // "<base>.19999-SNAPSHOT-<hash>", TeamCity/GitHub bake "<base>.<counter>-(jb|gh)-<hash>", a release
    // bakes "<base>.0-r-<hash>" (root build.gradle.kts). The gate tests therefore inject one fixed version
    // per lane so BOTH sides of the SNAPSHOT default run deterministically on every machine (issue #410).

    private val snapshotVersion = "0.101.19999-SNAPSHOT-bf19795" // local/dev lane → passive default OFF
    private val tcCiVersion = "0.101.595-jb-bf19795"             // TeamCity CI lane → passive default ON
    private val ghCiVersion = "0.101.595-gh-bf19795"             // GitHub CI lane   → passive default ON
    private val releaseVersion = "0.101.0-r-bf19795"             // release lane     → passive default ON
    private val allLaneVersions = listOf(snapshotVersion, tcCiVersion, ghCiVersion, releaseVersion)

    @Test
    fun `env opt-out disables auto-registration regardless of build lane`() {
        for (version in allLaneVersions) {
            for (v in listOf("yes", "true", "1", "on", "YES", "True", " on ")) {
                assertFalse(shouldWriteLauncher(v, force = false, devrigVersion = version),
                    "value '$v' should disable on $version")
            }
        }
        // The production entry point (baked version) — opt-out is lane-independent, so this holds
        // no matter which lane built this test JVM.
        for (v in listOf("yes", "true", "1", "on", "YES", "True", " on ")) {
            assertFalse(binAutoRegisterEnabled(v), "value '$v' should disable")
        }
    }

    @Test
    fun `env opt-in enables auto-registration regardless of build lane`() {
        for (version in allLaneVersions) {
            for (v in listOf("no", "false", "0", "off", "NO", "False")) {
                assertTrue(shouldWriteLauncher(v, force = false, devrigVersion = version),
                    "value '$v' should enable on $version")
            }
        }
        // The production entry point (baked version) — opt-in overrides the default on every lane.
        for (v in listOf("no", "false", "0", "off", "NO", "False")) {
            assertTrue(binAutoRegisterEnabled(v), "value '$v' should enable")
        }
    }

    @Test
    fun `unset or unrecognized defaults to OFF on a SNAPSHOT build and ON on CI and release builds`() {
        for (v in listOf(null, "garbage-unrecognized")) {
            // Dev/SNAPSHOT: a passive start must never clobber the user's real launcher.
            assertFalse(shouldWriteLauncher(v, force = false, devrigVersion = snapshotVersion),
                "env '$v' must default OFF on $snapshotVersion")
            // CI and release dists: the binary owns bin/devrig, so passive self-heal is ON.
            assertTrue(shouldWriteLauncher(v, force = false, devrigVersion = tcCiVersion),
                "env '$v' must default ON on $tcCiVersion")
            assertTrue(shouldWriteLauncher(v, force = false, devrigVersion = ghCiVersion),
                "env '$v' must default ON on $ghCiVersion")
            assertTrue(shouldWriteLauncher(v, force = false, devrigVersion = releaseVersion),
                "env '$v' must default ON on $releaseVersion")
        }
    }

    @Test
    fun `the default parameter reads the baked build version - this build's lane decides the passive default`() {
        // End-to-end over the REAL generated DevrigVersionMetadata: derive this build's lane from the
        // baked version instead of assuming SNAPSHOT (TC bakes a -jb- version; locally it is a SNAPSHOT).
        val baked = DevrigVersionMetadata.getDevrigVersion()
        val passiveDefault = !baked.contains("SNAPSHOT", ignoreCase = true)
        assertEquals(passiveDefault, binAutoRegisterEnabled(null),
            "passive default must follow the baked version's lane: $baked")
        assertEquals(passiveDefault, shouldWriteLauncher("garbage-unrecognized", force = false),
            "an unrecognized env value must fall back to the baked lane default: $baked")
    }

    @Test
    fun `explicit install (force) writes on every lane, but an opt-out still wins`() {
        for (version in allLaneVersions) {
            // force = explicit `devrig install`: it must write the wrapper despite a SNAPSHOT default-off,
            // so it never registers a path it didn't create...
            assertTrue(shouldWriteLauncher(null, force = true, devrigVersion = version), "force on $version")
            assertTrue(shouldWriteLauncher("garbage", force = true, devrigVersion = version), "force on $version")
            // ...and an explicit opt-out wins even over force.
            assertFalse(shouldWriteLauncher("yes", force = true, devrigVersion = version), "opt-out beats force on $version")
            assertFalse(shouldWriteLauncher("true", force = true, devrigVersion = version), "opt-out beats force on $version")
            // An explicit opt-in enables even the passive path.
            assertTrue(shouldWriteLauncher("no", force = false, devrigVersion = version), "opt-in on $version")
        }
        // A passive start without an opt-in follows the lane: nothing on SNAPSHOT, self-heal on CI/release.
        assertFalse(shouldWriteLauncher(null, force = false, devrigVersion = snapshotVersion))
        assertTrue(shouldWriteLauncher(null, force = false, devrigVersion = tcCiVersion))
        assertTrue(shouldWriteLauncher(null, force = false, devrigVersion = ghCiVersion))
        assertTrue(shouldWriteLauncher(null, force = false, devrigVersion = releaseVersion))
    }

    // ── launcher rendering ──────────────────────────────────────────────────────────────────────

    @Test
    fun `posix launcher pins DEVRIG_JAVA_HOME (absolute) and execs the install-tree launcher`() {
        val text = renderPosixLauncher(Path.of("/tmp/devrig/bin/devrig"), Path.of("/tmp/jdk-25"))
        assertTrue(text.startsWith("#!/bin/sh\n"), text)
        assertTrue(text.contains("DEVRIG_JAVA_HOME=\"/tmp/jdk-25\"; export DEVRIG_JAVA_HOME"), text)
        assertTrue(text.contains("exec \"/tmp/devrig/bin/devrig\" \"\$@\""), text)
        assertFalse(text.contains("\r\n"), "POSIX launcher must be LF-only")
        assertFalse(text.contains("\$HOME"), "wrapper records absolute paths, not \$HOME-relative")
        // #373 header: version + source stamped as comments
        assertTrue(text.contains("# devrig launcher version: ${DevrigVersionMetadata.getDevrigVersion()}"), text)
        assertTrue(text.contains("# devrig launcher source: /tmp/devrig/bin/devrig"), text)
    }

    @Test
    fun `windows launcher is pure CRLF batch that pins DEVRIG_JAVA_HOME (absolute)`() {
        val text = renderWindowsCmd(Path.of("C:\\devrig\\bin\\devrig.bat"), Path.of("C:\\devrig\\jdk-25"))
        assertTrue(text.startsWith("@echo off\r\n"), text)
        assertTrue(text.contains("set \"DEVRIG_JAVA_HOME=C:\\devrig\\jdk-25\"\r\n"), text)
        assertTrue(text.contains("call \"C:\\devrig\\bin\\devrig.bat\" %*\r\n"), text)
        assertFalse(text.contains("powershell", ignoreCase = true), "windows launcher must not invoke PowerShell")
        // #373 header: AFTER `@echo off` (anything before it would echo to stdout — the MCP channel)
        assertTrue(text.contains("rem devrig launcher version: ${DevrigVersionMetadata.getDevrigVersion()}\r\n"), text)
        assertTrue(text.contains("rem devrig launcher source: \"C:\\devrig\\bin\\devrig.bat\"\r\n"), text)
        assertTrue(text.indexOf("@echo off") < text.indexOf("rem devrig launcher version:"), text)
    }

    @Test
    fun `normalizeLauncher is tolerant of CRLF and trailing newlines`() {
        assertEquals(normalizeLauncher("a\nb\n"), normalizeLauncher("a\r\nb\r\n\n"))
    }

    // ── launcher version header + regeneration guard (#373) ────────────────────────────────────

    @Test
    fun `parseLauncherVersion reads the posix and windows headers`() {
        val posix = renderPosixLauncher(Path.of("/tmp/devrig/bin/devrig"), Path.of("/tmp/jdk-25"), version = "0.102-abc1234")
        assertEquals("0.102-abc1234", parseLauncherVersion(posix)?.value)

        val cmd = renderWindowsCmd(Path.of("C:\\devrig\\bin\\devrig.bat"), Path.of("C:\\jdk"), version = "0.101.442-jb-fedcba9")
        assertEquals("0.101.442-jb-fedcba9", parseLauncherVersion(cmd)?.value)

        // hand-edited uppercase REM still parses
        assertEquals("0.99", parseLauncherVersion("@echo off\r\nREM devrig launcher version: 0.99\r\n")?.value)

        // pre-#373 launchers carry no header
        assertNull(parseLauncherVersion("#!/bin/sh\nexec \"/tmp/devrig/bin/devrig\" \"\$@\"\n"))
    }

    @Test
    fun `shouldKeepNewerLauncher keeps only strictly newer launchers`() {
        val current = DevrigVersion.parse("0.101-abc1234")
        fun launcherStamped(v: String) = renderPosixLauncher(Path.of("/x/bin/devrig"), Path.of("/x/jdk"), version = v)

        assertFalse(shouldKeepNewerLauncher(null, current), "missing launcher never blocks regeneration")
        assertFalse(shouldKeepNewerLauncher("#!/bin/sh\nexec x\n", current), "headerless launcher never blocks")
        assertFalse(shouldKeepNewerLauncher(launcherStamped("0.100-fff0000"), current), "older launcher is replaced")
        assertFalse(shouldKeepNewerLauncher(launcherStamped("0.101-fff0000"), current), "same version (different hash) is replaced")
        assertTrue(shouldKeepNewerLauncher(launcherStamped("0.102-fff0000"), current), "newer launcher is kept")

        // snapshot rules from DevrigVersion: a SNAPSHOT-stamped launcher outranks any release binary...
        assertTrue(shouldKeepNewerLauncher(launcherStamped("0.100.19999-SNAPSHOT-fff0000"), current))
        // ...while a SNAPSHOT binary replaces even a much larger release launcher
        val snapshotBinary = DevrigVersion.parse("0.100.19999-SNAPSHOT-fff0000")
        assertFalse(shouldKeepNewerLauncher(launcherStamped("999.0-abc1234"), snapshotBinary))
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `an older devrig never overwrites a newer launcher - a newer one regenerates it`(@TempDir tmp: Path) {
        val userHome = tmp.resolve("home")
        val home = HomePaths(userHome.resolve(".mcp-steroid"))
        fun run(ownBin: Path, version: String) = ensureBinLauncherCore(
            home, isWin = false, ownBin = ownBin, jdkHome = tmp.resolve("jdk"),
            userHome = userHome, pathDirs = emptyList(),
            buildVersion = DevrigVersion.parse(version),
        )

        run(tmp.resolve("opt/devrig-0.102/bin/devrig"), "0.102-abc1234")
        val launcher = home.binDir.resolve("devrig")
        val written = launcher.readText()
        assertTrue(written.contains("# devrig launcher version: 0.102-abc1234"), written)

        // an older binary starts: the newer launcher must be left byte-identical
        run(tmp.resolve("opt/devrig-0.101/bin/devrig"), "0.101-fff0000")
        assertEquals(written, launcher.readText())

        // a newer binary starts: the launcher is regenerated to point at it
        val newest = tmp.resolve("opt/devrig-0.103/bin/devrig")
        run(newest, "0.103-0123abc")
        val regenerated = launcher.readText()
        assertTrue(regenerated.contains("# devrig launcher version: 0.103-0123abc"), regenerated)
        assertTrue(regenerated.contains(newest.toString()), regenerated)
    }

    @Test
    fun `windows header quotes the source path so batch metacharacters stay inert`() {
        // `rem` does not neutralize `&` — an unquoted path would execute the tail as a command
        val text = renderWindowsCmd(Path.of("C:\\tools & extras\\devrig\\bin\\devrig.bat"), Path.of("C:\\jdk"), version = "0.101")
        assertTrue(text.contains("rem devrig launcher source: \"C:\\tools & extras\\devrig\\bin\\devrig.bat\"\r\n"), text)
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `header interpolation strips newlines so a value cannot escape its comment line`() {
        val evil = Path.of("/tmp/evil\ndir/devrig") // POSIX file names may legally contain a newline
        val text = renderPosixLauncher(evil, Path.of("/tmp/jdk"), version = "0.101")
        assertTrue(text.contains("# devrig launcher source: /tmp/evil dir/devrig\n"), text)
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `keeping a newer launcher still repairs a lost executable bit`(@TempDir tmp: Path) {
        val userHome = tmp.resolve("home")
        val home = HomePaths(userHome.resolve(".mcp-steroid"))
        fun run(version: String) = ensureBinLauncherCore(
            home, isWin = false, ownBin = tmp.resolve("opt/devrig/bin/devrig"), jdkHome = tmp.resolve("jdk"),
            userHome = userHome, pathDirs = emptyList(),
            buildVersion = DevrigVersion.parse(version),
        )

        run("0.102-abc1234")
        val launcher = home.binDir.resolve("devrig")
        val written = launcher.readText()
        val perms = Files.getPosixFilePermissions(launcher).toMutableSet()
        perms.removeAll(setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE))
        Files.setPosixFilePermissions(launcher, perms)

        // an OLDER binary keeps the newer launcher byte-identical, but must still restore +x
        run("0.101-fff0000")
        assertEquals(written, launcher.readText(), "newer launcher must not be rewritten")
        assertTrue(Files.isExecutable(launcher), "kept launcher must be usable again")
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `a headerless pre-existing launcher is regenerated`(@TempDir tmp: Path) {
        val userHome = tmp.resolve("home")
        val home = HomePaths(userHome.resolve(".mcp-steroid"))
        Files.createDirectories(home.binDir)
        val launcher = home.binDir.resolve("devrig")
        Files.writeString(launcher, "#!/bin/sh\nexec \"/somewhere/else/devrig\" \"\$@\"\n")

        ensureBinLauncherCore(
            home, isWin = false, ownBin = tmp.resolve("opt/devrig/bin/devrig"), jdkHome = tmp.resolve("jdk"),
            userHome = userHome, pathDirs = emptyList(),
            buildVersion = DevrigVersion.parse("0.101-abc1234"),
        )
        val text = launcher.readText()
        assertTrue(text.contains("# devrig launcher version: 0.101-abc1234"), text)
        assertTrue(Files.isExecutable(launcher), "regenerated launcher must be executable")
    }

    // ── core self-heal (POSIX) ──────────────────────────────────────────────────────────────────

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `writes an executable bin devrig pointing at the running install and JDK`(@TempDir tmp: Path) {
        val userHome = tmp.resolve("home")
        val home = HomePaths(userHome.resolve(".mcp-steroid"))
        val ownRoot = tmp.resolve("opt/devrig") // deliberately OUTSIDE the home — the launcher must still be written
        val ownJava = tmp.resolve("opt/jdk-25")

        ensureBinLauncher(home, isWin = false, ownRoot = ownRoot, ownJava = ownJava, userHome = userHome, pathDirs = emptyList())

        val launcher = home.binDir.resolve("devrig")
        assertTrue(Files.isRegularFile(launcher), "launcher should be written")
        assertTrue(Files.isExecutable(launcher), "launcher should be executable")
        assertEquals(
            renderPosixLauncher(ownRoot.resolve("bin/devrig"), ownJava),
            launcher.readText(),
        )
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `ensureBinLauncherCore registers the EXPLICIT install-script + jdk (devrig install devrig)`(@TempDir tmp: Path) {
        val userHome = tmp.resolve("home")
        val home = HomePaths(userHome.resolve(".mcp-steroid"))
        // The install-tree launcher carries the version+hash dir the script computed — NOT devrig-<version>.
        val installScript = tmp.resolve("binaries/devrig-0.100-abc1234/bin/devrig")
        val jdkHome = tmp.resolve("binaries/jdk-0.100-deadbeef/jdk")

        ensureBinLauncherCore(home, isWin = false, ownBin = installScript, jdkHome = jdkHome, userHome = userHome, pathDirs = emptyList())

        val launcher = home.binDir.resolve("devrig")
        assertTrue(Files.isExecutable(launcher), "launcher should be written + executable")
        // The wrapper pins the PASSED jdk + execs the PASSED install-script (the eugene-x220 path bug fix).
        assertEquals(renderPosixLauncher(installScript, jdkHome), launcher.readText())
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `rewriting is idempotent - second start leaves identical bytes`(@TempDir tmp: Path) {
        val userHome = tmp.resolve("home")
        val home = HomePaths(userHome.resolve(".mcp-steroid"))
        val ownRoot = tmp.resolve("opt/devrig")
        val ownJava = tmp.resolve("opt/jdk-25")
        fun run() = ensureBinLauncher(home, isWin = false, ownRoot = ownRoot, ownJava = ownJava, userHome = userHome, pathDirs = emptyList())

        run()
        val first = home.binDir.resolve("devrig").readText()
        run()
        assertEquals(first, home.binDir.resolve("devrig").readText())
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `restores a lost executable bit even when the content is unchanged`(@TempDir tmp: Path) {
        val userHome = tmp.resolve("home")
        val home = HomePaths(userHome.resolve(".mcp-steroid"))
        val ownRoot = tmp.resolve("opt/devrig")
        val ownJava = tmp.resolve("opt/jdk-25")
        fun run() = ensureBinLauncher(home, isWin = false, ownRoot = ownRoot, ownJava = ownJava, userHome = userHome, pathDirs = emptyList())

        run()
        val launcher = home.binDir.resolve("devrig")
        // Drop the executable bit (e.g. a copy/restore that lost perms) — content stays identical.
        val perms = Files.getPosixFilePermissions(launcher).toMutableSet()
        perms.removeAll(setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE))
        Files.setPosixFilePermissions(launcher, perms)
        assertFalse(Files.isExecutable(launcher), "precondition: +x removed")

        run()
        assertTrue(Files.isExecutable(launcher), "self-heal must restore +x even when bytes are unchanged")
    }

    // ── rename-based replace (Windows lock scenario exercised on POSIX via the move seam) ───────

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `a blocked direct move parks the original as old-pid, renames the new file into place, deletes the old`(@TempDir dir: Path) {
        val target = dir.resolve("devrig")
        Files.writeString(target, "old launcher\n")
        val old = dir.resolve("devrig.old${ProcessHandle.current().pid()}")

        // Model the Windows lock semantics: moving ONTO an existing (held-open) file fails; renames of
        // that file and moves to a free name succeed.
        var calls = 0
        replaceLauncherFile(target, "new launcher\n", executable = true) { from, to, _ ->
            calls++
            if (Files.exists(to)) throw IOException("simulated sharing violation: target is held open")
            if (to == target) {
                // The into-place move AFTER parking: the original must sit at old-pid with its bytes.
                assertEquals("old launcher\n", old.readText(), "the original must be parked before this move")
            }
            Files.move(from, to)
        }

        assertEquals(4, calls, "expected atomic + plain direct moves (fail) + park + into-place rename, got $calls")
        assertEquals("new launcher\n", target.readText())
        assertTrue(Files.isExecutable(target), "the executable bit set on the staged file must survive the rename")
        assertFalse(Files.exists(old), "the old-pid file must be deleted as the last step of the sequence")
        assertFalse(Files.exists(dir.resolve("devrig.new${ProcessHandle.current().pid()}")), "the staged file must be consumed")
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `a missing original converges on the first attempt - one atomic move, nothing to park or delete`(@TempDir dir: Path) {
        val target = dir.resolve("devrig")

        var calls = 0
        replaceLauncherFile(target, "new launcher\n", executable = true) { from, to, _ ->
            calls++
            Files.move(from, to)
        }

        assertEquals(1, calls, "the first atomic move must already succeed when the original is missing")
        assertEquals("new launcher\n", target.readText())
        assertTrue(Files.isExecutable(target))
        val leftovers = Files.list(dir).use { s -> s.filter { it != target }.toList() }
        assertTrue(leftovers.isEmpty(), "no staging or old-pid file may remain: $leftovers")
    }

    @Test
    fun `when every move keeps failing the 5 attempts give up loudly and the original stays untouched`(@TempDir dir: Path) {
        val target = dir.resolve("devrig")
        Files.writeString(target, "old launcher\n")

        var calls = 0
        val startNanos = System.nanoTime()
        val boom = assertFailsWith<IOException> {
            replaceLauncherFile(target, "new launcher\n", executable = false) { _, _, _ ->
                calls++
                throw IOException("simulated persistent lock")
            }
        }
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

        assertEquals("simulated persistent lock", boom.message, "the LAST failure must propagate unwrapped")
        assertEquals(15, calls, "each of the 5 attempts = atomic + plain direct moves + park, all failing")
        assertTrue(elapsedMs >= 40, "4 inter-attempt delays of 10 ms expected, took only ${elapsedMs} ms")
        assertEquals("old launcher\n", target.readText(), "a total failure must leave the original launcher in place")
        assertFalse(Files.exists(dir.resolve("devrig.old${ProcessHandle.current().pid()}")),
            "no old-pid file can exist when even the park move failed")
    }

    // ── PATH symlink (POSIX) ────────────────────────────────────────────────────────────────────

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `symlinks bin devrig into a writable PATH dir under the user home`(@TempDir userHome: Path) {
        val home = HomePaths(userHome.resolve(".mcp-steroid"))
        val localBin = Files.createDirectories(userHome.resolve(".local/bin"))

        ensureBinLauncher(
            home, isWin = false,
            ownRoot = home.home.resolve("binaries/devrig-abc"),
            ownJava = home.home.resolve("binaries/jdk-abc"),
            userHome = userHome,
            pathDirs = listOf("/usr/bin", localBin.toString()), // /usr/bin is outside home → skipped
        )

        val link = localBin.resolve("devrig")
        assertTrue(Files.isSymbolicLink(link), "should symlink into the writable PATH dir under home")
        assertEquals(home.binDir.resolve("devrig").toAbsolutePath().normalize(), Files.readSymbolicLink(link))
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `never clobbers a foreign devrig already on PATH`(@TempDir userHome: Path) {
        val home = HomePaths(userHome.resolve(".mcp-steroid"))
        val localBin = Files.createDirectories(userHome.resolve(".local/bin"))
        val foreign = localBin.resolve("devrig")
        Files.writeString(foreign, "#!/bin/sh\necho a different devrig\n") // a real file, not our symlink

        ensureBinLauncher(
            home, isWin = false,
            ownRoot = home.home.resolve("binaries/devrig-abc"),
            ownJava = home.home.resolve("binaries/jdk-abc"),
            userHome = userHome,
            pathDirs = listOf(localBin.toString()),
        )

        assertFalse(Files.isSymbolicLink(foreign), "a foreign devrig must be left untouched")
        assertTrue(foreign.readText().contains("a different devrig"))
    }

    @DisabledOnOs(OS.WINDOWS)
    @Test
    fun `recognizes a pre-existing RELATIVE symlink that points at our launcher`(@TempDir userHome: Path) {
        val home = HomePaths(userHome.resolve(".mcp-steroid"))
        val localBin = Files.createDirectories(userHome.resolve(".local/bin"))
        // A symlink whose target is RELATIVE (resolved against the link's own dir, not the process CWD)
        // but points at our launcher — an older install.sh could have created exactly this.
        val relTarget = Path.of("../../.mcp-steroid/bin/devrig")
        val link = Files.createSymbolicLink(localBin.resolve("devrig"), relTarget)

        ensureBinLauncher(
            home, isWin = false,
            ownRoot = home.home.resolve("binaries/devrig-abc"),
            ownJava = home.home.resolve("binaries/jdk-abc"),
            userHome = userHome,
            pathDirs = listOf(localBin.toString()),
        )

        // It must be recognized as ours and left exactly as-is (still the relative target), not recreated.
        assertTrue(Files.isSymbolicLink(link))
        assertEquals(relTarget, Files.readSymbolicLink(link), "the relative symlink should be left untouched")
    }
}
