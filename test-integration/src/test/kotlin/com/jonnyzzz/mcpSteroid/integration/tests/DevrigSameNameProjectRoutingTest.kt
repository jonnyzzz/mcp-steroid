/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import com.jonnyzzz.mcpSteroid.aiAgents.StdioMcpCommand
import com.jonnyzzz.mcpSteroid.integration.infra.AiMode
import com.jonnyzzz.mcpSteroid.integration.infra.DevrigSteroidDriver
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainer
import com.jonnyzzz.mcpSteroid.integration.infra.IntelliJContainerOpts
import com.jonnyzzz.mcpSteroid.integration.infra.create
import com.jonnyzzz.mcpSteroid.integration.infra.waitForProjectReady
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStack
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackDriver
import com.jonnyzzz.mcpSteroid.testHelper.CloseableStackHost
import com.jonnyzzz.mcpSteroid.testHelper.StdioMcpProcess
import com.jonnyzzz.mcpSteroid.testHelper.docker.startProcessInContainer
import com.jonnyzzz.mcpSteroid.testHelper.startStdioMcpProcess
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Reproducer for #92 — `steroid_execute_code` routes to the WRONG project when two projects with the
 * **same IntelliJ name** but **different paths** are open in one IDE (the canonical case: a main checkout
 * and a git worktree whose leaf dir matches).
 *
 * The contract under test: an agent only ever passes devrig's **discovery `project_name`** — the
 * hash-disambiguated value from `steroid_list_projects` (e.g. `dupproj-AbCd1234`). devrig must route THAT
 * exact identity to THAT exact project; it must never collapse it back to a bare, guessable name.
 *
 * The original bug (#92): devrig forwarded the bare folder name and the IDE matched `Project.name`
 * first-wins, so the second same-named project was unreachable. The fix: the IDE owns within-IDE-unique
 * names — `OpenProjectsService.resolveProject` matches the recomputed `<name>-<hash>` from
 * `ProjectNameService` (no raw-name fallback) — and devrig forwards that same unique name
 * (`route.exposedProjectName`, the shared scheme) instead of the bare name. This test guards that.
 *
 * This test opens two same-named projects, then runs `steroid_execute_code` (via the real `devrig mpc`
 * stdio bridge) against EACH project's disambiguated `project_name`, asking each run which project it
 * actually landed in (it reads a `which.txt` marker holding that project's own path from
 * `project.basePath`). Correct routing → each run reads its own path. The #92 bug → the second project's
 * call lands in the first, reading the wrong path.
 */
class DevrigSameNameProjectRoutingTest {

    private lateinit var methodStack: CloseableStackDriver

    @BeforeEach
    fun setUpMethodStack() {
        methodStack = lifetime.nestedStack("devrig-mpc-per-method")
    }

    @AfterEach
    fun tearDownMethodStack() {
        methodStack.closeAllStacks()
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun devrigRoutesExecuteCodeToTheCorrectSameNamedProject() {
        val diagnostics = session.diagnosticsSummary()

        // 1. Two projects with the SAME leaf dir name ("dupproj") under different parents → identical
        //    IntelliJ project name, distinct paths. Each carries a `which.txt` holding its own path so a
        //    run can prove WHICH project it actually executed in (independent of path canonicalization).
        val paths = listOf("/home/agent/dup-a/$DUP_LEAF", "/home/agent/dup-b/$DUP_LEAF")
        val pathsLiteral = paths.joinToString(", ") { "\"$it\"" }
        session.mcpSteroid.mcpExecuteCode(
            taskId = "dup-setup",
            reason = "create two same-named project dirs for the #92 routing reproducer",
            // The inner script refers to `p` and "which.txt" bare (no $-interpolation); only $pathsLiteral
            // is interpolated test-side into the script text.
            code = """
                listOf($pathsLiteral).forEach { p ->
                    val d = java.io.File(p)
                    d.mkdirs()
                    java.io.File(d, "which.txt").writeText(p)
                }
                println("DUP_DIRS_READY")
            """.trimIndent(),
        )

        // 2. Open BOTH in the single running IDE (order matters: A is opened first, so the buggy
        //    find-first-by-name resolves every "dupproj" request to A).
        paths.forEach { session.mcpSteroid.mcpOpenProject(it) }

        // 3. Real devrig stdio MCP bridge against that IDE.
        val devrigCommand = DevrigSteroidDriver.deploy(session.scope, session.mcpSteroid).devrigCommand
        val process = startContainerStdioMcp(methodStack, devrigCommand)
        process.initialize()

        // 4. Discover the two same-named projects through devrig: each has a hash-disambiguated
        //    `project_name` + its real `path`.
        val routes = waitForSameNamedRoutes(process, expectedPaths = paths, diagnostics = diagnostics)

        // 5. For EACH discovered project, run execute_code against its disambiguated project_name and ask
        //    which project it landed in. Correct routing → reads its own path back.
        val mismatches = mutableListOf<String>()
        for ((projectName, path) in routes) {
            val result = toolCall(
                process = process,
                name = "steroid_execute_code",
                arguments = buildJsonObject {
                    put("project_name", projectName)
                    put("task_id", "dup-route-check")
                    put("reason", "verify devrig routes the disambiguated project_name to the right project (#92)")
                    put(
                        "code",
                        "val f = java.io.File(project.basePath, \"which.txt\"); " +
                            "println(\"WHICH_PROJECT=\" + (if (f.exists()) f.readText().trim() else \"MISSING@\" + project.basePath))",
                    )
                },
                diagnostics = diagnostics,
            )
            assertFalse(isToolError(result), "execute_code returned a tool error for $projectName\n$diagnostics\n$result")
            val landed = WHICH.find(textContent(result))?.groupValues?.get(1)
                ?: error("execute_code output missing WHICH_PROJECT marker for $projectName\n$diagnostics\n${textContent(result)}")
            if (landed != path) {
                mismatches += "project_name='$projectName' targeted path '$path' but execute_code ran in '$landed'"
            }
        }

        assertTrue(
            mismatches.isEmpty(),
            "#92 reproduced — devrig mis-routed execute_code across same-named projects:\n" +
                mismatches.joinToString("\n") { "  - $it" } +
                "\n(discovered routes: $routes)\n$diagnostics",
        )
    }

    /** Polls devrig `steroid_list_projects` until both same-named projects are discovered; returns
     *  (disambiguated `project_name` → real `path`) for the entries whose raw folder name is [DUP_LEAF]. */
    private fun waitForSameNamedRoutes(
        process: StdioMcpProcess,
        expectedPaths: List<String>,
        diagnostics: String,
    ): Map<String, String> {
        repeat(120) {
            val result = toolCall(process, "steroid_list_projects", buildJsonObject {}, diagnostics)
            assertFalse(isToolError(result), "list_projects returned a tool error\n$diagnostics\n$result")
            val projects = json.parseToJsonElement(textContent(result)).jsonObject["projects"]?.jsonArray.orEmpty()
            val dup = projects.map { it.jsonObject }.filter { it["name"]?.jsonPrimitive?.contentOrNull == DUP_LEAF }
            val byPath = dup.mapNotNull { p ->
                val name = p["project_name"]?.jsonPrimitive?.contentOrNull
                val path = p["path"]?.jsonPrimitive?.contentOrNull
                if (name != null && path != null) name to path else null
            }.toMap()
            if (byPath.values.toSet().containsAll(expectedPaths)) {
                // Distinct disambiguated names, one per path — the discovery contract this test relies on.
                assertEquals(
                    expectedPaths.size, byPath.size,
                    "expected one disambiguated project_name per same-named project\n$diagnostics\n$byPath",
                )
                return byPath.entries.associate { (name, path) -> name to path }
            }
            Thread.sleep(250)
        }
        error("Timed out waiting for devrig to discover both same-named projects ($expectedPaths)\n$diagnostics")
    }

    private fun startContainerStdioMcp(stack: CloseableStack, devrigCommand: StdioMcpCommand): StdioMcpProcess =
        startStdioMcpProcess(lifetime = stack, resourceName = "container-devrig") { stdin: Flow<ByteArray> ->
            session.scope.startProcessInContainer {
                args(listOf(devrigCommand.command) + devrigCommand.args)
                    .interactive()
                    .stdin(stdin)
                    .timeoutSeconds(300)
                    .description("devrig stdio MCP for same-name routing repro")
                    .quietly()
            }
        }

    private fun toolCall(process: StdioMcpProcess, name: String, arguments: JsonObject, diagnostics: String): JsonObject {
        val response = process.request(
            "tools/call",
            buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
            timeoutMillis = 60_000,
        )
        assertNull(response["error"], "tools/call returned JSON-RPC error\n$diagnostics\n$response")
        return response["result"]?.jsonObject ?: error("tools/call response missing result\n$diagnostics\n$response")
    }

    private fun isToolError(result: JsonObject): Boolean =
        result["isError"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true

    private fun textContent(result: JsonObject): String =
        result["content"]?.jsonArray
            ?.joinToString("\n") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            ?: error("tool result missing content: $result")

    companion object {
        private const val DUP_LEAF = "dupproj"
        private val WHICH = Regex("WHICH_PROJECT=(.+)")
        private val json = Json { ignoreUnknownKeys = true }
        private val lifetime by lazy { CloseableStackHost(DevrigSameNameProjectRoutingTest::class.java.simpleName) }
        private val session by lazy {
            IntelliJContainer.create(
                lifetime,
                IntelliJContainerOpts(consoleTitle = "devrig same-name project routing (#92)", aiMode = AiMode.NONE),
            ).waitForProjectReady()
        }

        @BeforeAll
        @JvmStatic
        fun beforeAll() {
            session.toString()
        }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            lifetime.closeAllStacks()
        }
    }
}
