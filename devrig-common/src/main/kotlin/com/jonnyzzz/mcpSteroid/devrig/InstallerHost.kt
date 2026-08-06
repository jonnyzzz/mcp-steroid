/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.exists

/** The published installers. Both halves of the product install devrig by running exactly these. */
const val DEVRIG_INSTALL_SH_URL = "https://devrig.dev/install.sh"
const val DEVRIG_INSTALL_PS1_URL = "https://devrig.dev/install.ps1"

/** The installer to fetch for this OS. */
fun devrigInstallerUrl(isWin: Boolean): String = if (isWin) DEVRIG_INSTALL_PS1_URL else DEVRIG_INSTALL_SH_URL

/** Most reliable first: absolute System32 PowerShell (agents often carry stripped PATHs), then PATH lookups. */
fun windowsInstallerHostCandidates(systemRoot: String? = System.getenv("SystemRoot")): List<String> {
    val root = systemRoot?.takeIf { it.isNotBlank() } ?: "C:\\Windows"
    val system32 = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
    return buildList {
        if (system32.exists()) add(system32.toString())
        add("powershell")
        add("pwsh")
    }
}

/**
 * Command lines that run an already-downloaded install script, most reliable host first — try each in
 * order until one starts.
 *
 * Downloading first and running a file, rather than piping `curl … | sh` / `irm … | iex`, is what makes
 * the fallback list possible at all: with a pipeline there is one shell and it has to be on PATH. It also
 * drops the dependency on `curl` being installed.
 */
fun installerCommands(script: Path, isWin: Boolean): List<List<String>> = when {
    isWin -> windowsInstallerHostCandidates().map {
        listOf(it, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString())
    }
    else -> listOf(listOf("/bin/sh", script.toString()))
}

/**
 * Download the install script at [url] into [target]; false on any failure — never throws, each
 * caller reports and retries its own way (devrig's updater on its next tick, the IDE via its
 * failure notification's Retry).
 *
 * The ONE download implementation for both halves of the product, next to the URLs it fetches and
 * the [installerCommands] that run the result. Fetching the script and running the file — rather
 * than piping `curl … | sh` / `irm … | iex` — needs no `curl` and lets the run fall back across
 * PowerShell hosts on Windows. Plain JDK HTTP on purpose: this module links into the `:ij-plugin`
 * runtime classpath, where devrig's Ktor client does not exist.
 *
 * [userAgent] identifies the calling half in the server logs (`devrig/<v>` vs the IDE plugin).
 */
fun downloadInstallerScript(url: String, target: Path, userAgent: String): Boolean {
    // Cache-buster: a retry must see a server-side fix, not Cloudflare's cached copy
    // (query strings bypass the edge cache — same pattern as the release verification).
    val request = HttpRequest.newBuilder(URI.create("$url?_=${System.currentTimeMillis()}"))
        .timeout(Duration.ofSeconds(60))
        .header("User-Agent", userAgent)
        .header("Cache-Control", "no-cache")
        .GET()
        .build()
    return try {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
            .use { client ->
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    System.err.println("[mcp-steroid] GET $url returned ${response.statusCode()}")
                    return false
                }
                Files.createDirectories(target.parent)
                Files.writeString(target, response.body())
                true
            }
    } catch (e: Exception) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        System.err.println("[mcp-steroid] could not download $url: $e")
        false
    }
}
