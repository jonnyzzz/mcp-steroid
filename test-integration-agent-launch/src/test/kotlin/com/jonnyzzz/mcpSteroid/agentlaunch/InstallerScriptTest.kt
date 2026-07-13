package com.jonnyzzz.mcpSteroid.agentlaunch

import com.jonnyzzz.mcpSteroid.testHelper.process.RunProcessRequest
import com.jonnyzzz.mcpSteroid.testHelper.process.startProcess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Validates the generated installer for the HOST OS is syntactically valid, using the real system
 * shell/interpreter: on Windows the `install.ps1` parses under Windows PowerShell 5.1; on Linux the
 * `install.sh` parses under `sh -n`. Windows/Linux-only (gated at the task level). Renders the
 * `:installer-gen` template with placeholder values (the script is parsed, not executed).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstallerScriptTest {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val sha = "a".repeat(64)

    private fun renderPs1(): String {
        val tmpl = Path.of(System.getProperty("installer.ps1.template")).readText()
        val table = "\$Platforms = @{ 'win32-x64' = @{ url = 'https://example.invalid/j.zip'; sha256 = '$sha'; binsub = 'jdk\\bin' } }"
        return tmpl
            .replace("@@VERSION@@", "0.0.0-test")
            .replace("@@DEVRIG_URL@@", "https://example.invalid/devrig.zip")
            .replace("@@DEVRIG_SHA256@@", sha)
            .replace("@@DEVRIG_FORMAT@@", "zip")
            .replace("@@DEVRIG_BINSUB@@", "devrig\\bin\\devrig.bat")
            .replace("@@PLATFORM_TABLE_PS@@", table)
    }

    private fun renderSh(): String {
        val tmpl = Path.of(System.getProperty("installer.sh.template")).readText()
        val case = "    linux-x64) jdk_url='https://example.invalid/j.tgz'; jdk_sha256='$sha'; jdk_format='tar.gz'; jdk_javahome='jdk';;"
        return tmpl
            .replace("@@VERSION@@", "0.0.0-test")
            .replace("@@DEVRIG_URL@@", "https://example.invalid/devrig.tgz")
            .replace("@@DEVRIG_FORMAT@@", "tar.gz")
            .replace("@@DEVRIG_BINSUB@@", "devrig/bin/devrig")
            .replace("@@PLATFORM_CASE_SH@@", case)
    }

    private fun cacheDir(): Path =
        Path.of(System.getProperty("agent.launch.cache.dir")).also { it.createDirectories() }

    @Test
    fun `generated installer for this OS is syntactically valid`() {
        val (script, args, prefix) = if (isWindows) {
            val ps1 = renderPs1()
            // PS 5.1 misreads UTF-8 → the generator (and this rendered instance) must be ASCII-only.
            val firstNonAscii = ps1.indexOfFirst { it.code > 0x7F }
            assertEquals(-1, firstNonAscii) { "install.ps1 must be ASCII-only; first non-ASCII at $firstNonAscii" }
            val f = cacheDir().resolve("install-rendered.ps1").also { it.writeText(ps1) }
            val parseCmd =
                "\$errs = \$null; " +
                    "[System.Management.Automation.Language.Parser]::ParseFile('${f.absolutePathString().replace("\\", "\\\\")}', [ref]\$null, [ref]\$errs); " +
                    "if (\$errs -and \$errs.Count -gt 0) { \$errs | ForEach-Object { [Console]::Error.WriteLine(\$_.ToString()) }; exit 1 } else { exit 0 }"
            Triple(f, listOf("powershell", "-NoProfile", "-NonInteractive", "-Command", parseCmd), "ps-parse")
        } else {
            val f = cacheDir().resolve("install-rendered.sh").also { it.writeText(renderSh()) }
            Triple(f, listOf("sh", "-n", f.absolutePathString()), "sh-parse")
        }
        val result = RunProcessRequest(args = args)
            .withTimeout(Duration.ofMinutes(2))
            .withLogPrefix(prefix)
            .withDescription("syntax-check ${script.fileName}")
            .startProcess()
            .awaitForProcessFinish()
        assertEquals(0, result.exitCode) {
            "Generated installer failed to parse:\n${result.stdout}\n${result.stderr}"
        }
    }
}
