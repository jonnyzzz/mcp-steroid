/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.aiAgents.AiAgentCli
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the Tenet-3 boundary (#9): the thin, stateless MCP-as-CLI tool facades must NOT perform the
 * on-start `~/.mcp-steroid/bin` launcher + PATH self-heal (a persistent on-disk side effect), while the
 * lifecycle commands (mcp / install / backend / project / help / version) still do.
 */
class LauncherSelfHealPredicateTest {

    private val toolFacades: List<DevrigCommand> = listOf(
        DevrigCommand.RunTool(toolName = "steroid_fetch_resource", commandName = "fetch_resource", arguments = JsonObject(emptyMap())),
        DevrigCommand.RunTool(toolName = "steroid_open_project", commandName = "open_project", arguments = JsonObject(emptyMap())),
        DevrigCommand.RunTool(toolName = "steroid_take_screenshot", commandName = "take_screenshot", arguments = JsonObject(emptyMap())),
        DevrigCommand.RunTool(toolName = "steroid_input", commandName = "input", arguments = JsonObject(emptyMap())),
        DevrigCommand.RunTool(toolName = "steroid_execute_code", commandName = "execute_code", arguments = JsonObject(emptyMap())),
        DevrigCommand.RunTool(toolName = "steroid_execute_feedback", commandName = "execute_feedback", arguments = JsonObject(emptyMap())),
        DevrigCommand.RunTool(toolName = "steroid_list_windows", commandName = "list_windows", arguments = JsonObject(emptyMap())),
    )

    private val lifecycleCommands: List<DevrigCommand> = listOf(
        DevrigCommand.MCP(),
        DevrigCommand.DevrigCommandBackend(),
        DevrigCommand.DevrigCommandBackendDownload(),
        DevrigCommand.DevrigCommandBackendStart(),
        DevrigCommand.DevrigCommandBackendStop(),
        DevrigCommand.DevrigCommandBackendProvision(),
        DevrigCommand.DevrigCommandProject(),
        DevrigCommand.DevrigCommandInstall(AiAgentCli.CLAUDE),
        DevrigCommand.DevrigCommandInstallDevrig(),
        DevrigCommand.DevrigCommandHelp(),
        DevrigCommand.DevrigCommandVersion(),
        DevrigCommand.DevrigCommandParseError(text = "oops"),
    )

    @Test
    fun `tool facades never self-heal the launcher`() {
        for (command in toolFacades) {
            assertFalse(command.selfHealsLauncherOnStart(), "$command must not self-heal (Tenet 3)")
        }
    }

    @Test
    fun `lifecycle commands still self-heal the launcher`() {
        for (command in lifecycleCommands) {
            assertTrue(command.selfHealsLauncherOnStart(), "$command must keep the bootstrap self-heal")
        }
    }
}
