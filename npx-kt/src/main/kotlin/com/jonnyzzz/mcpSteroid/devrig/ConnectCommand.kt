/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Absolute path to the user's Claude Code settings file. */
fun claudeSettingsPath(): Path =
    Path.of(System.getProperty("user.home"), ".claude", "settings.json")

fun DevrigServices.runConnectClaudeCommand(command: DevrigCommand.DevrigCommandConnectClaude): Int =
    runConnectClaude(claudeSettingsPath(), mcpStdout, System.err)

/**
 * Enable the marketplace devrig plugin in Claude Code by merging our two keys into [settingsPath]
 * (idempotent — see [enableClaudePluginInSettings]). Writes atomically (temp file + ATOMIC_MOVE) so a
 * concurrent Claude read never sees a torn file. Best-effort narration to [out]; failures throw.
 */
fun runConnectClaude(settingsPath: Path, out: PrintStream, err: PrintStream): Int {
    val current = if (settingsPath.isRegularFile()) settingsPath.readText() else null
    if (isClaudePluginEnabled(current)) {
        out.println("Claude Code plugin '$CLAUDE_DEVRIG_PLUGIN_KEY' is already enabled in $settingsPath.")
        return 0
    }
    val updated = enableClaudePluginInSettings(current)
    Files.createDirectories(settingsPath.parent)
    val tmp = Files.createTempFile(settingsPath.parent, "settings", ".json.tmp")
    Files.writeString(tmp, updated)
    try {
        Files.move(tmp, settingsPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
        err.println("[devrig] atomic move unavailable (${e.message}); falling back to a plain replace.")
        Files.move(tmp, settingsPath, StandardCopyOption.REPLACE_EXISTING)
    }
    out.println("enabled the devrig plugin for Claude Code in $settingsPath.")
    out.println("Restart Claude Code (or start a new session) to load it.")
    return 0
}

fun DevrigServices.runConnectIdeCommand(command: DevrigCommand.DevrigCommandConnectIde): Int =
    runBlocking(Dispatchers.IO) {
        val discovered = portDiscovery.stateSnapshot()
        val client = InstallPluginClient { url ->
            val response = commandHttpClient.get(url) {
                // /api/installPlugin trusts localhost callers (RestService.isHostTrusted -> isLocalhost);
                // devrig is a localhost tool, so we declare our real localhost origin rather than spoof a
                // marketplace one. Without any Origin the endpoint blocks on an "unknown host" dialog.
                header(HttpHeaders.Origin, "http://127.0.0.1")
            }
            InstallPluginResponse(response.status.value, response.bodyAsText())
        }
        runConnectIde(discovered, client, mcpStdout, System.err)
    }
