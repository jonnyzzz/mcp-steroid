/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.server.ListProjectsResponse
import com.jonnyzzz.mcpSteroid.server.ListProjectsToolHandler
import com.jonnyzzz.mcpSteroid.server.ListWindowsResponse
import com.jonnyzzz.mcpSteroid.server.ListWindowsToolHandler
import com.jonnyzzz.mcpSteroid.server.ListedProject
import com.jonnyzzz.mcpSteroid.server.ListedWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** Glue tests for `devrig list_windows` and `devrig list_projects` — the unified envelope + human render. */
class ListCommandsTest {

    @TempDir lateinit var home: Path
    private fun homePaths() = HomePaths(home).also { it.mkdirsAll() }

    private class FakeListProjects(val resp: ListProjectsResponse) : ListProjectsToolHandler {
        override suspend fun collectListProjectsResponse() = resp
    }

    private fun window() = ListedWindow(
        projectName = "app-key", projectPath = "/p/app", title = "app", isActive = true, isVisible = true,
        bounds = null, windowId = "win-1", modalDialogShowing = false, indexingInProgress = false,
        projectInitialized = true, backendName = "iu-abc",
    )

    // ------------------------------ list_windows ------------------------------

    @Test
    fun `list_windows --json uses the unified envelope`() {
        val windows = SequencedListWindows(listOf(ListWindowsResponse(listOf(window()), emptyList())))
        val run = runCliCommand(homePaths()) {
            runListWindowsCommand(
                DevrigCommand.DevrigCommandListWindows(json = true),
                fakeTools(ListWindowsToolHandler::class.java to windows),
            )
        }
        assertEquals(CliExit.OK, run.exit)
        val obj = Json.parseToJsonElement(run.stdout).jsonObject
        assertEquals("devrig", obj["tool"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("list_windows", obj["command"]!!.jsonPrimitive.content)
        val wins = obj["data"]!!.jsonObject["windows"]!!.jsonArray
        assertEquals("win-1", wins.first().jsonObject["windowId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `list_windows human output lists window_id and state`() {
        val windows = SequencedListWindows(listOf(ListWindowsResponse(listOf(window()), emptyList())))
        val run = runCliCommand(homePaths()) {
            runListWindowsCommand(
                DevrigCommand.DevrigCommandListWindows(json = false),
                fakeTools(ListWindowsToolHandler::class.java to windows),
            )
        }
        assertEquals(CliExit.OK, run.exit)
        assertTrue(run.stdout.contains("window_id=win-1"), run.stdout)
        assertTrue(run.stdout.contains("initialized"), run.stdout)
    }

    // ------------------------------ list_projects ------------------------------

    @Test
    fun `list_projects --json envelope exposes project_name`() {
        val fake = FakeListProjects(ListProjectsResponse(listOf(
            ListedProject(projectName = "app-9fk2", name = "app", path = "/p/app", backendName = "iu-abc"),
        )))
        val run = runCliCommand(homePaths()) {
            runListProjectsCommand(
                DevrigCommand.DevrigCommandListProjects(json = true),
                fakeTools(ListProjectsToolHandler::class.java to fake),
            )
        }
        assertEquals(CliExit.OK, run.exit)
        val obj = Json.parseToJsonElement(run.stdout).jsonObject
        assertEquals("list_projects", obj["command"]!!.jsonPrimitive.content)
        val projects = obj["data"]!!.jsonObject["projects"]!!.jsonArray
        val p = projects.first().jsonObject
        assertEquals("app-9fk2", p["project_name"]!!.jsonPrimitive.content)
        assertEquals("app", p["name"]!!.jsonPrimitive.content)
        assertEquals("/p/app", p["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun `list_projects human path runs without an IDE`() {
        // No fake needed: the human path delegates to `devrig project` (real routing, empty here).
        val run = runCliCommand(homePaths()) {
            runListProjectsCommand(DevrigCommand.DevrigCommandListProjects(json = false))
        }
        assertEquals(CliExit.OK, run.exit)
        assertTrue(run.stdout.isNotBlank(), "human path should print a listing/empty message")
    }
}
