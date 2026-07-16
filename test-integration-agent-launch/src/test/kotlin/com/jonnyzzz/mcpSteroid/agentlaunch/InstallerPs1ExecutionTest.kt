/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.agentlaunch

import com.jonnyzzz.mcpSteroid.installer.ALL_PLATFORMS
import com.jonnyzzz.mcpSteroid.installer.DevrigEntry
import com.jonnyzzz.mcpSteroid.installer.JdkScriptEntry
import com.jonnyzzz.mcpSteroid.installer.writeInstallerScripts
import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.assertExitCode
import com.jonnyzzz.mcpSteroid.testHelper.process.assertNoMessageInOutput
import com.jonnyzzz.mcpSteroid.testHelper.process.assertOutputContains
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes

/**
 * Windows-native end-to-end run of the generated `install.ps1`. Complementary to
 * [InstallerScriptTest] (which only PARSES the script under PowerShell 5.1) and to
 * `InstallerBootstrapPs1Test` in `:installer-gen` (which executes it under `pwsh` 7.4 on Ubuntu
 * Docker) — this test executes it under **Windows PowerShell 5.1** (`powershell.exe`), the default
 * shell shipped on Windows 10 and 11. PS 5.1 is where jonnyzzz/mcp-steroid#273 actually manifests:
 * `[RuntimeInformation]::OSArchitecture` is missing on the .NET Framework 4.6.x that base Windows
 * 10 ships, and `Set-StrictMode Latest` turns that into a hard abort. If `pwsh` is also on PATH
 * (the standard GitHub windows-latest runner has both), it is exercised too.
 *
 * No `DEVRIG_OS` / `DEVRIG_CPU` env vars: the whole point is to cover the auto-detect path the
 * shipped `irm | iex` bootstrap hits. The test overrides `USERPROFILE` to a per-invocation temp dir
 * to isolate content-addressed install state — that override is test hygiene, unrelated to the
 * detection paths in the script.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstallerPs1ExecutionTest {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun `install_ps1 runs end-to-end under Windows PowerShell 5-1 (powershell_exe)`() {
        // Task-level `enabled = runsHere` already keeps this test off macOS. The class-level gate
        // makes it a NO-OP on the Linux side of the cross-OS matrix — the Docker pwsh path is what
        // covers Linux, not powershell.exe.
        assumeTrue(isWindows) { "InstallerPs1ExecutionTest exercises native powershell.exe; skipping on non-Windows host" }
        runInstallEndToEnd("powershell", extraArgs = emptyList(), shellDescription = "powershell.exe (Windows PowerShell 5.1)")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    fun `install_ps1 runs end-to-end under PowerShell Core (pwsh) when available on PATH`() {
        assumeTrue(isWindows) { "pwsh coverage on Linux lives in :installer-gen InstallerBootstrapPs1Test (Docker)" }
        val pwshOnPath = isCommandOnPath("pwsh")
        assumeTrue(pwshOnPath) { "pwsh (PowerShell Core) not on PATH; only powershell.exe (5.1) will be exercised" }
        runInstallEndToEnd("pwsh", extraArgs = emptyList(), shellDescription = "pwsh (PowerShell Core 7.x)")
    }

    // ── shared driver ────────────────────────────────────────────────────────────────────────────

    private fun runInstallEndToEnd(shellCommand: String, extraArgs: List<String>, shellDescription: String) {
        val workDir = cacheDir().resolve("ps1-exec-${System.nanoTime()}").also { it.createDirectories() }
        val fixturesDir = workDir.resolve("fixtures").also { it.createDirectories() }
        val genDir = workDir.resolve("gen").also { it.createDirectories() }
        val fakeHome = workDir.resolve("home").also { it.createDirectories() }

        // Build fixtures.
        val devrigZip = fixturesDir.resolve("devrig.zip")
        buildFakeDevrigZipForWindows(devrigZip)
        val jdkZip = fixturesDir.resolve("jdk.zip")
        buildFakeJdkZipForWindows(jdkZip)
        val devrigSha = sha256(devrigZip)
        val jdkSha = sha256(jdkZip)

        // Serve fixtures over real HTTP. The install.ps1 uses Invoke-WebRequest which needs an
        // http/https URL — file:// is not accepted. Bind to 127.0.0.1 on an ephemeral port so
        // the test cannot collide with anything else on the runner.
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/devrig.zip") { ex ->
                ex.sendResponseHeaders(200, devrigZip.readBytes().size.toLong())
                ex.responseBody.use { it.write(devrigZip.readBytes()) }
            }
            createContext("/jdk.zip") { ex ->
                ex.sendResponseHeaders(200, jdkZip.readBytes().size.toLong())
                ex.responseBody.use { it.write(jdkZip.readBytes()) }
            }
            start()
        }
        try {
            val port = server.address.port
            val baseUrl = "http://127.0.0.1:$port"

            // Render install.ps1 through the SHIPPING code path (writeInstallerScripts). All 5
            // platforms must be present in the table (installer-gen contract), but install.ps1
            // reads only its own Windows platform hashtable at runtime.
            val jdkEntryWin = JdkScriptEntry("$baseUrl/jdk.zip", jdkSha, "zip", "jdk")
            val jdkEntryPosix = JdkScriptEntry("$baseUrl/jdk.zip", jdkSha, "tar.gz", "jdk")
            val table = ALL_PLATFORMS.associateWith { key ->
                if (key.startsWith("windows-")) jdkEntryWin else jdkEntryPosix
            }
            val devrig = DevrigEntry(
                url = "$baseUrl/devrig.zip", sha256 = devrigSha,
                launcherPosix = "devrig-$VERSION/bin/devrig",
                launcherWindows = "devrig-$VERSION/bin/devrig.bat",
            )
            writeInstallerScripts(genDir, table, devrig, VERSION)
            val ps1 = genDir.resolve("install.ps1")
            check(Files.isRegularFile(ps1)) { "did not produce install.ps1 in $genDir" }

            // Isolate content-addressed install state from any previous run: point USERPROFILE at
            // a per-invocation temp dir. This is test hygiene, not detection-path steering — no
            // DEVRIG_* var is set, so the script must self-detect the platform on real Windows.
            val env = mapOf("USERPROFILE" to fakeHome.absolutePathString())

            // ── run #1: clean home → downloads both, delegates to devrig install devrig ──
            val run1 = RunProcessRequest(
                args = listOf(shellCommand, "-NoProfile", "-NonInteractive") + extraArgs +
                    listOf("-File", ps1.absolutePathString()),
                environment = env,
                logPrefix = "ps1-run1-$shellCommand",
                description = "install.ps1 under $shellDescription (run 1)",
                timeout = Duration.ofMinutes(5),
            ).startProcess().awaitForProcessFinish()
                .assertExitCode(0) { "install.ps1 run #1 under $shellDescription failed:\n$this" }
                .assertOutputContains("platform: windows-", message = "must auto-detect windows-<cpu> platform")
                .assertOutputContains("downloading devrig", "downloading jdk", message = "run #1 must download both")
                .assertOutputContains(
                    "DEVRIG_INSTALL_DEVRIG",
                    message = "install.ps1 must delegate to `devrig install devrig`",
                )
                .assertOutputContains("devrig binary is ready", "devrig install", message = "must report ready + how to register with agents")
            // Regression guard for jonnyzzz/mcp-steroid#273: RuntimeInformation lookup must not
            // surface as a strict-mode abort on Windows PowerShell 5.1.
            run1.assertNoMessageInOutput("The property 'OSArchitecture' cannot be found")
            run1.assertNoMessageInOutput("PropertyNotFoundStrict")
            // And must NOT auto-register with an agent — that is a separate explicit user step.
            run1.assertNoMessageInOutput("DEVRIG_INSTALL_CALLED")

            // ── run #2: idempotent — reuses content-addressed dirs, downloads NOTHING ──
            val run2 = RunProcessRequest(
                args = listOf(shellCommand, "-NoProfile", "-NonInteractive") + extraArgs +
                    listOf("-File", ps1.absolutePathString()),
                environment = env,
                logPrefix = "ps1-run2-$shellCommand",
                description = "install.ps1 under $shellDescription (run 2, idempotent)",
                timeout = Duration.ofMinutes(2),
            ).startProcess().awaitForProcessFinish()
                .assertExitCode(0) { "install.ps1 run #2 (idempotent) under $shellDescription failed:\n$this" }
                .assertOutputContains("already installed", message = "re-run must report 'already installed'")
            run2.assertNoMessageInOutput("downloading devrig")
            run2.assertNoMessageInOutput("downloading jdk")
        } finally {
            server.stop(0)
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private fun cacheDir(): Path =
        Path.of(System.getProperty("agent.launch.cache.dir")).also { it.createDirectories() }

    private fun isCommandOnPath(cmd: String): Boolean {
        // Both cmd.exe `where` and PS `Get-Command` do the resolution; `where` is cheap and always
        // present on Windows. Exit 0 → found. On non-Windows this method is never reached (gated by
        // the assumeTrue(isWindows)).
        return try {
            val proc = ProcessBuilder("where", cmd).redirectErrorStream(true).start()
            proc.inputStream.readAllBytes() // drain so waitFor doesn't block on buffered output
            proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun sha256(file: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Fake devrig dist zip. Top dir `devrig-<version>/`; inside sits `bin/devrig.bat` — a genuine
     * cmd batch on Windows. Records what install.ps1 delegated: `install devrig …` (new flow) vs
     * `install <agent>` (the would-be auto-register, which install.ps1 must NOT do) vs anything
     * else. Mirrors the output tokens the sibling Docker tests already assert on.
     */
    private fun buildFakeDevrigZipForWindows(target: Path) {
        val batchScript = buildString {
            appendLine("@echo off")
            appendLine("if /I \"%~1\"==\"install\" if /I \"%~2\"==\"devrig\" (")
            appendLine("  echo DEVRIG_INSTALL_DEVRIG %*")
            appendLine("  exit /b 0")
            appendLine(")")
            appendLine("if /I \"%~1\"==\"install\" (")
            appendLine("  echo DEVRIG_INSTALL_CALLED jdk=%DEVRIG_JAVA_HOME%")
            appendLine("  exit /b 0")
            appendLine(")")
            appendLine("echo DEVRIG_RAN %*")
            appendLine("echo DEVRIG_JAVA_HOME=%DEVRIG_JAVA_HOME%")
            appendLine("exit /b 0")
        }.toByteArray(Charsets.US_ASCII)
        ZipOutputStream(FileOutputStream(target.toFile())).use { zip ->
            zip.putNextEntry(ZipEntry("devrig-$VERSION/")); zip.closeEntry()
            zip.putNextEntry(ZipEntry("devrig-$VERSION/bin/")); zip.closeEntry()
            zip.putNextEntry(ZipEntry("devrig-$VERSION/bin/devrig.bat"))
            zip.write(batchScript)
            zip.closeEntry()
        }
    }

    /**
     * Fake JDK zip. Top dir `jdk/` (matches `javaHome="jdk"`), with a `bin/java.exe` stub so
     * install.ps1's `Test-Path (Join-Path $jdkHome 'bin\java.exe')` check succeeds. The stub is a
     * one-line cmd batch renamed to .exe — install.ps1 never EXECUTES it, only tests existence.
     */
    private fun buildFakeJdkZipForWindows(target: Path) {
        val javaStub = "@echo java-stub 25\r\n@exit /b 0\r\n".toByteArray(Charsets.US_ASCII)
        ZipOutputStream(FileOutputStream(target.toFile())).use { zip ->
            zip.putNextEntry(ZipEntry("jdk/")); zip.closeEntry()
            zip.putNextEntry(ZipEntry("jdk/bin/")); zip.closeEntry()
            zip.putNextEntry(ZipEntry("jdk/bin/java.exe"))
            zip.write(javaStub)
            zip.closeEntry()
        }
    }

    companion object {
        const val VERSION = "0.0.0-test"
    }
}
