package com.jonnyzzz.mcpSteroid.windows

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Validates the generated Windows installer (`install.ps1`, produced by :installer-gen) against a REAL
 * Windows PowerShell, on a Windows agent. Windows-only; gated OFF elsewhere at the Gradle task level
 * (see build.gradle.kts) — no runtime skips.
 *
 * Scope: it renders the `install.ps1.tmpl` template with placeholder values and asserts the result
 * (a) is ASCII-only (Windows PowerShell 5.1 misreads UTF-8 — the generator enforces this; we verify the
 * rendered instance), and (b) PARSES with zero syntax errors under Windows PowerShell 5.1 via the
 * PowerShell AST parser. A full ~611 MB end-to-end install (real devrig + JDK) is intentionally NOT run
 * here — that belongs with the sh/PowerShell polyglot verification in jonnyzzz/mcp-steroid#254.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstallerScriptWindowsTest {

    private fun renderInstallPs1(): String {
        val templatePath = Path.of(System.getProperty("windows.installer.ps1.template"))
        val sha = "a".repeat(64)
        // Placeholder values chosen only to be syntactically valid + ASCII; the script is parsed, not run.
        val platformTable = buildString {
            append("\$Platforms = @{ ")
            append("'win32-x64' = @{ url = 'https://example.invalid/jdk-x64.zip'; sha256 = '$sha'; binsub = 'jdk\\bin' }; ")
            append("'win32-arm64' = @{ url = 'https://example.invalid/jdk-arm64.zip'; sha256 = '$sha'; binsub = 'jdk\\bin' } }")
        }
        return templatePath.readText()
            .replace("@@VERSION@@", "0.0.0-test")
            .replace("@@DEVRIG_URL@@", "https://example.invalid/devrig.zip")
            .replace("@@DEVRIG_SHA256@@", sha)
            .replace("@@DEVRIG_FORMAT@@", "zip")
            .replace("@@DEVRIG_BINSUB@@", "devrig\\bin\\devrig.bat")
            .replace("@@PLATFORM_TABLE_PS@@", platformTable)
    }

    @Test
    fun `rendered install_ps1 is ASCII-only`() {
        val rendered = renderInstallPs1()
        val firstNonAscii = rendered.indexOfFirst { it.code > 0x7F }
        assertEquals(-1, firstNonAscii) {
            "install.ps1 must be ASCII-only (Windows PowerShell 5.1 misreads UTF-8). First non-ASCII char " +
                "at index $firstNonAscii: '${rendered.getOrNull(firstNonAscii)}'"
        }
    }

    @Test
    fun `generated install_ps1 parses under Windows PowerShell 5_1`() {
        val cacheDir = Path.of(System.getProperty("windows.test.cache.dir")).also { it.createDirectories() }
        val script = cacheDir.resolve("install-rendered.ps1")
        script.writeText(renderInstallPs1())

        // Parse (do NOT execute) via the PowerShell AST parser; non-zero exit == syntax errors.
        val parseCmd =
            "\$errs = \$null; " +
                "[System.Management.Automation.Language.Parser]::ParseFile('${script.absolutePathString().replace("\\", "\\\\")}', [ref]\$null, [ref]\$errs); " +
                "if (\$errs -and \$errs.Count -gt 0) { \$errs | ForEach-Object { [Console]::Error.WriteLine(\$_.ToString()) }; exit 1 } else { exit 0 }"

        val out = cacheDir.resolve("installer-parse-out.txt")
        val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", parseCmd)
            .redirectErrorStream(true)
            .redirectOutput(out.toFile())
            .start()
        val finished = proc.waitFor(2, java.util.concurrent.TimeUnit.MINUTES)
        assertTrue(finished) { "PowerShell parse of install.ps1 did not finish in time" }
        val exit = proc.exitValue()
        val output = if (Files.exists(out)) out.readText() else ""
        assertEquals(0, exit) { "install.ps1 failed to parse under Windows PowerShell 5.1:\n$output" }
    }

    @Test
    fun `rendered install_ps1 bakes the Windows platform table`() {
        val rendered = renderInstallPs1()
        assertTrue(rendered.contains("win32-x64")) { "install.ps1 should reference the win32-x64 platform" }
        assertTrue(rendered.contains("\$Version")) { "install.ps1 should define \$Version" }
    }
}
