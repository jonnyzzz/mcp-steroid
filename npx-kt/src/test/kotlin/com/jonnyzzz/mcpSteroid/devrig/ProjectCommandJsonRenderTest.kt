/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.IdeProjectState
import com.jonnyzzz.mcpSteroid.devrig.server.ProjectRoute
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** Pins the schema of `devrig project --json` (renderProjectJson3) against the 3-source model. */
class ProjectCommandJsonRenderTest {

    private val parser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun render(routes: List<ProjectRoute> = emptyList()): JsonObject {
        val buf = ByteArrayOutputStream()
        renderProjectJson3(routes, PrintStream(buf, true, Charsets.UTF_8))
        val text = buf.toString(Charsets.UTF_8)
        return parser.parseToJsonElement(text).jsonObject
    }

    private fun markerIde(
        pid: Long = 1234L,
        build: String = "IU-253.21581.142",
        backendName: String = "mock-iu-abc",
    ): DiscoveredIde {
        val ideInfo = IdeInfo(name = "IntelliJ IDEA", version = "2025.3", build = build)
        val pluginInfo = PluginInfo(id = "com.jonnyzzz.mcp-steroid", name = "MCP Steroid", version = "0.0.0-test")
        return DiscoveredIde(
            pid = pid,
            rpcBaseUrl = testDevrigEndpoint("http://127.0.0.1:65000/mcp").rpcBaseUrl,
            bridgeHeaders = emptyMap(),
            ide = ideInfo,
            plugin = pluginInfo,
            backendName = backendName,
        )
    }

    private fun route(
        exposedProjectName: String,
        originalName: String = exposedProjectName,
        path: String = "/projects/$exposedProjectName",
        backendName: String = "mock-iu-abc",
    ): ProjectRoute {
        val ide = markerIde(backendName = backendName)
        return ProjectRoute(
            route = ide,
            projectInfo = IdeProjectState(name = originalName, projectPath = path),
            exposedProjectName = exposedProjectName,
            projectPath = path,
        )
    }

    // ----------------------------- top-level shape -------------------------

    @Test
    fun `output is valid JSON with exactly tool and projects at the top level`() {
        val root = render()
        assertEquals(setOf("tool", "projects"), root.keys,
            "renderProjectJson3 must emit exactly {tool, projects} — no mcpSteroidBackends, backends, or other keys")
    }

    @Test
    fun `output does not contain mcpSteroidBackends key`() {
        val buf = ByteArrayOutputStream()
        renderProjectJson3(listOf(route("alpha-abc1")), PrintStream(buf, true, Charsets.UTF_8))
        val text = buf.toString(Charsets.UTF_8)
        assertFalse(text.contains("mcpSteroidBackends"),
            "project JSON must not contain the mcpSteroidBackends key; got:\n$text")
    }

    @Test
    fun `tool object has name and version`() {
        val root = render()
        val tool = root["tool"]!!.jsonObject
        assertEquals("devrig", tool["name"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(tool["version"]?.jsonPrimitive?.contentOrNull,
            "tool.version must be non-null")
    }

    @Test
    fun `empty routes yield empty projects array`() {
        val root = render()
        assertEquals(0, root["projects"]!!.jsonArray.size)
    }

    @Test
    fun `output is pure JSON -- starts with open brace no banner`() {
        val buf = ByteArrayOutputStream()
        renderProjectJson3(emptyList(), PrintStream(buf, true, Charsets.UTF_8))
        val text = buf.toString(Charsets.UTF_8).trim()
        assertTrue(text.startsWith("{"),
            "output must start with '{'; got: '${text.take(60)}'")
    }

    // ----------------------------- project entries -------------------------

    @Test
    fun `single project entry has project_name name path and backend_name`() {
        val r = route("alpha-abc1", originalName = "alpha", path = "/projects/alpha", backendName = "iu-abc1")
        val root = render(listOf(r))
        val projects = root["projects"]!!.jsonArray
        assertEquals(1, projects.size)
        val entry = projects.single().jsonObject

        assertEquals("alpha-abc1", entry["project_name"]?.jsonPrimitive?.contentOrNull,
            "project_name must equal exposedProjectName")
        assertEquals("alpha", entry["name"]?.jsonPrimitive?.contentOrNull,
            "name must equal the original (ide-side) project name")
        assertEquals("/projects/alpha", entry["path"]?.jsonPrimitive?.contentOrNull,
            "path must equal the real project path")
        assertEquals("iu-abc1", entry["backend_name"]?.jsonPrimitive?.contentOrNull,
            "backend_name must equal the route's exposedBackendName (= route.route.backendName)")
    }

    @Test
    fun `multiple projects are sorted by project_name`() {
        // Same ordering as the MCP steroid_list_projects response (#155), whatever the input order.
        val r1 = route("alpha-aaa", originalName = "alpha", path = "/p/alpha", backendName = "iu-aaa")
        val r2 = route("bravo-bbb", originalName = "bravo", path = "/p/bravo", backendName = "iu-bbb")
        val projects = render(listOf(r2, r1))["projects"]!!.jsonArray
        assertEquals(2, projects.size)
        assertEquals("alpha-aaa", projects[0].jsonObject["project_name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("bravo-bbb", projects[1].jsonObject["project_name"]?.jsonPrimitive?.contentOrNull)
    }
}
