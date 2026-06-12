/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.jonnyzzz.mcpSteroid.IdeInfo
import com.jonnyzzz.mcpSteroid.McpSteroidServerInfo
import com.jonnyzzz.mcpSteroid.PidMarker
import com.jonnyzzz.mcpSteroid.PluginInfo
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIde
import com.jonnyzzz.mcpSteroid.devrig.monitor.DiscoveredIdeByPort
import com.jonnyzzz.mcpSteroid.server.ProjectInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

/** Pins the schema of `devrig backend --json` against the shared R3.4 BackendInfo / ListedProject model. */
class BackendCommandJsonRenderTest {

    private val parser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun render(rows: List<BackendRow>): JsonObject {
        val buf = ByteArrayOutputStream()
        renderBackendJson(rows, PrintStream(buf, true, Charsets.UTF_8))
        val text = buf.toString(Charsets.UTF_8)
        return parser.parseToJsonElement(text).jsonObject
    }

    private fun backendNames(root: JsonObject): List<String> =
        root["backends"]!!.jsonArray.map { it.jsonObject["backend_name"]!!.jsonPrimitive.content }

    private fun jsonStrings(element: JsonElement): List<String> = when (element) {
        is JsonObject -> element.values.flatMap(::jsonStrings)
        is JsonArray -> element.flatMap(::jsonStrings)
        is JsonPrimitive -> element.contentOrNull?.let { listOf(it) }.orEmpty()
    }

    private fun captureBackendCommandLogs(action: () -> JsonObject): Pair<JsonObject, List<ILoggingEvent>> {
        val logger = LoggerFactory.getLogger("com.jonnyzzz.mcpSteroid.devrig.BackendCommand") as Logger
        val appender = ListAppender<ILoggingEvent>().apply {
            context = logger.loggerContext
            start()
        }
        logger.addAppender(appender)
        try {
            return action() to appender.list.toList()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun markerIde(
        name: String = "IntelliJ IDEA",
        version: String = "2025.3.3",
        pid: Long = 1234L,
        build: String = "IU-253.21581.142",
        mcpUrl: String = "http://localhost:6315/mcp",
        token: String = "",
    ): DiscoveredIde {
        val ideInfo = IdeInfo(name = name, version = version, build = build)
        val pluginInfo = PluginInfo(id = "com.jonnyzzz.mcp-steroid", name = "MCP Steroid", version = "0.0.0-test")
        val marker = PidMarker(
            schema = PidMarker.SCHEMA_VERSION,
            pid = pid,
            mcpSteroidServer = McpSteroidServerInfo(
                mcpUrl = mcpUrl,
                headers = mapOf("Authorization" to "Bearer $token"),
            ),
            devrigEndpoint = testDevrigEndpoint(mcpUrl, mapOf("Authorization" to "Bearer $token")),
            ide = ideInfo,
            plugin = pluginInfo,
            createdAt = "1970-01-01T00:00:00Z",
            intellijWebServer = null,
            intellijMcpServer = null,
        )
        return DiscoveredIde(
            pid = pid,
            rpcBaseUrl = testDevrigEndpoint(mcpUrl).rpcBaseUrl,
            bridgeHeaders = mapOf("Authorization" to "Bearer $token"),
            markerPath = "/tmp/$pid.mcp-steroid",
            marker = marker,
        )
    }

    private fun portIde(
        port: Int = 63342,
        productFullName: String? = "IntelliJ IDEA Ultimate",
        productName: String? = "IDEA",
        buildNumber: String? = "IU-253.21581.142",
        baselineVersion: Int? = 253,
        edition: String? = "IU",
    ) = DiscoveredIdeByPort(
        port = port,
        baseUrl = "http://127.0.0.1:$port",
        productName = productName,
        productFullName = productFullName,
        edition = edition,
        baselineVersion = baselineVersion,
        buildNumber = buildNumber,
    )

    private fun managedInfo(
        id: String = "idea-community-2025.2.6.2",
        state: ManagedBackendState = ManagedBackendState.INSTALLED,
        runningPid: Long? = null,
    ) = ManagedBackendInfo(
        id = id,
        productKey = id.substringBeforeLast('-'),
        productCode = "IC",
        version = id.substringAfterLast('-'),
        buildNumber = "IC-252.1",
        installPath = Path.of("/managed/$id"),
        cachePath = Path.of("/caches/$id"),
        runningPid = runningPid,
        state = state,
    )

    // ----------------------------- top-level shape -------------------------

    @Test
    fun `output is valid JSON with exactly tool backends and projects at the top level`() {
        val root = render(emptyList())
        assertEquals(setOf("tool", "backends", "projects"), root.keys)
        val tool = root["tool"]!!.jsonObject
        assertEquals("devrig", tool["name"]?.jsonPrimitive?.contentOrNull)
        assertNotNull(tool["version"]?.jsonPrimitive?.contentOrNull,
            "tool.version must be non-null so scripts can correlate against changelog")
    }

    @Test
    fun `empty list produces empty backends and projects arrays`() {
        val root = render(emptyList())
        assertEquals(0, root["backends"]!!.jsonArray.size,
            "empty discovery must serialise as [] so consumers can always iterate")
        assertEquals(0, root["projects"]!!.jsonArray.size,
            "no reachable marker backends means no projects")
    }

    @Test
    fun `every backend entry carries the intellij type discriminator`() {
        val rows = listOf(
            BackendRow.FromMarker(markerIde(pid = 1L), emptyList()),
            BackendRow.FromPort(portIde(port = 63342)),
            BackendRow.FromMarker(markerIde(pid = 2L), null, errorMessage = "x"),
        )
        val backends = render(rows)["backends"]!!.jsonArray
        assertEquals(rows.size, backends.size)
        assertTrue(backends.all { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "intellij" },
            "every current backend is an IntelliJ-family backend: $backends")
    }

    // -------------------------- marker variant -----------------------------

    @Test
    fun `marker row serialises shared fields plus ide identity, plugin and flat projects`() {
        val row = BackendRow.FromMarker(
            ide = markerIde(pid = 1234, mcpUrl = "http://localhost:6315/mcp"),
            projects = listOf(ProjectInfo("my-app", "/Users/x/my-app")),
        )
        val root = render(listOf(row))
        val backend = root["backends"]!!.jsonArray.single().jsonObject

        assertEquals(backendNameForRow(row), backend["backend_name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("intellij", backend["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("marker", backend["source"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, backend["routable"]?.jsonPrimitive?.boolean)
        assertEquals(true, backend["reachable"]?.jsonPrimitive?.boolean)
        assertEquals(backendDisplayName(row), backend["displayName"]?.jsonPrimitive?.contentOrNull)
        assertEquals(backendLocatorLabel(row), backend["locator"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1234L, backend["pid"]?.jsonPrimitive?.long)
        assertEquals("IU", backend["ideProductCode"]?.jsonPrimitive?.contentOrNull)
        assertEquals("IU-253.21581.142", backend["build"]?.jsonPrimitive?.contentOrNull)

        // The marker's IdeInfo type stays on the disk/wire side: the agent schema carries the
        // identity only as displayName/build/ideProductCode — no embedded `ide` object.
        assertNull(backend["ide"], "agent schema must not embed the marker IdeInfo: " + backend)

        // plugins[] carries one entry tagged kind=mcp-steroid (replaces the old `plugin` block + flag).
        val plugin = backend["plugins"]!!.jsonArray.single().jsonObject
        assertEquals("com.jonnyzzz.mcp-steroid", plugin["id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("MCP Steroid", plugin["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("0.0.0-test", plugin["version"]?.jsonPrimitive?.contentOrNull)
        assertEquals("mcp-steroid", plugin["kind"]?.jsonPrimitive?.contentOrNull)
        assertNull(backend["mcpSteroidPluginInstalled"], "the boolean flag is replaced by plugins[]: $backend")
        assertNull(backend["actions"], "actions[] was dropped from BackendInfo: $backend")
        assertNull(backend["error"], "reachable marker rows must not carry an error: $backend")
        assertNull(backend["portDetail"], "marker row carries no port detail: $backend")
        assertNull(backend["managedDetail"], "marker row carries no managed detail: $backend")

        // The marker's projects are embedded under the backend AND flattened at the top level.
        assertEquals(1, backend["openProjects"]!!.jsonArray.size)
        val projects = root["projects"]!!.jsonArray
        assertEquals(1, projects.size)
        val p = projects.single().jsonObject
        assertEquals(backendNameForRow(row), p["backend_name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("my-app", p["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("/Users/x/my-app", p["path"]?.jsonPrimitive?.contentOrNull)
        // project_name is the devrig-exposed name (raw name + a hash suffix) — keeps `name` for jq consumers.
        val projectName = p["project_name"]?.jsonPrimitive?.contentOrNull
        assertNotNull(projectName)
        assertTrue(projectName!!.startsWith("my-app"), projectName)
    }

    @Test
    fun `marker row with empty projects list is reachable and contributes no project rows`() {
        val row = BackendRow.FromMarker(markerIde(), projects = emptyList())
        val root = render(listOf(row))
        val backend = root["backends"]!!.jsonArray.single().jsonObject
        assertEquals(true, backend["reachable"]?.jsonPrimitive?.boolean)
        assertNull(backend["error"], "empty projects is a successful snapshot, not an error")
        assertEquals(0, root["projects"]!!.jsonArray.size)
    }

    @Test
    fun `unreachable marker row serialises reachable=false error and no project references`() {
        val row = BackendRow.FromMarker(markerIde(), projects = null, errorMessage = "connect refused")
        val root = render(listOf(row))
        val backend = root["backends"]!!.jsonArray.single().jsonObject
        val backendName = backend["backend_name"]!!.jsonPrimitive.content
        assertEquals("marker", backend["source"]?.jsonPrimitive?.contentOrNull)
        assertEquals(false, backend["reachable"]?.jsonPrimitive?.boolean)
        assertEquals(false, backend["routable"]?.jsonPrimitive?.boolean,
            "an unreachable marker has no live bridge, so it is not routable")
        assertEquals("connect refused", backend["error"]?.jsonPrimitive?.contentOrNull)
        val projectRefs = root["projects"]!!.jsonArray.map { it.jsonObject["backend_name"]!!.jsonPrimitive.content }
        assertTrue(backendName !in projectRefs,
            "unreachable marker backend must not contribute projects; refs=$projectRefs backend=$backend")
    }

    @Test
    fun `unreachable marker row with null errorMessage gets a non-null fallback error string`() {
        val row = BackendRow.FromMarker(markerIde(), projects = null, errorMessage = null)
        val backend = render(listOf(row))["backends"]!!.jsonArray.single().jsonObject
        val error = backend["error"]?.jsonPrimitive?.contentOrNull
        assertNotNull(error, "error must always be a string when reachable=false")
        assertTrue(error!!.isNotBlank(), "fallback error must be non-empty")
    }

    // --------------------------- port variant ------------------------------

    @Test
    fun `port row serialises shared fields plus a portDetail block and a provision action with argv`() {
        val row = BackendRow.FromPort(portIde(port = 63342))
        val backend = render(listOf(row))["backends"]!!.jsonArray.single().jsonObject

        assertEquals(backendNameForRow(row), backend["backend_name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("intellij", backend["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("port", backend["source"]?.jsonPrimitive?.contentOrNull)
        assertEquals(false, backend["routable"]?.jsonPrimitive?.boolean, "port rows have no bridge: $backend")
        assertEquals(true, backend["reachable"]?.jsonPrimitive?.boolean)
        assertEquals(backendDisplayName(row), backend["displayName"]?.jsonPrimitive?.contentOrNull)
        assertEquals(backendLocatorLabel(row), backend["locator"]?.jsonPrimitive?.contentOrNull)
        assertEquals(63342, backend["port"]?.jsonPrimitive?.int)

        val detail = backend["portDetail"]!!.jsonObject
        assertEquals("http://127.0.0.1:63342", detail["baseUrl"]?.jsonPrimitive?.contentOrNull)
        assertEquals("IDEA", detail["productName"]?.jsonPrimitive?.contentOrNull)
        assertEquals("IntelliJ IDEA Ultimate", detail["productFullName"]?.jsonPrimitive?.contentOrNull)
        assertEquals("IU", detail["edition"]?.jsonPrimitive?.contentOrNull)
        assertEquals(253, detail["baselineVersion"]?.jsonPrimitive?.int)
        assertEquals("IU-253.21581.142", detail["buildNumber"]?.jsonPrimitive?.contentOrNull)

        assertEquals(0, backend["plugins"]!!.jsonArray.size, "port row has no plugins: $backend")
        assertNull(backend["actions"], "actions[] was dropped from BackendInfo: $backend")
        assertNull(backend["pid"], "port row must not carry a pid: $backend")
        assertNull(backend["ide"], "port row carries no marker ide identity: $backend")
    }

    @Test
    fun `port row omits about-fields that came back as null from api-about`() {
        val row = BackendRow.FromPort(portIde(productFullName = null, edition = null, baselineVersion = null))
        val detail = render(listOf(row))["backends"]!!.jsonArray.single().jsonObject["portDetail"]!!.jsonObject
        assertNull(detail["productFullName"], "absent fields must NOT serialise as null: $detail")
        assertNull(detail["edition"], "absent fields must NOT serialise as null: $detail")
        assertNull(detail["baselineVersion"], "absent fields must NOT serialise as null: $detail")
        assertEquals("IDEA", detail["productName"]?.jsonPrimitive?.contentOrNull)
    }

    // -------------------------- managed variant ----------------------------

    @Test
    fun `managed row serialises a managedDetail block`() {
        val row = BackendRow.FromManaged(
            managedInfo(id = "idea-community-2025.2.6.2", state = ManagedBackendState.RUNNING, runningPid = 44),
        )
        val backend = render(listOf(row))["backends"]!!.jsonArray.single().jsonObject
        assertEquals("managed", backend["source"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, backend["managed"]?.jsonPrimitive?.boolean)
        assertEquals(false, backend["routable"]?.jsonPrimitive?.boolean)
        // Managed rows carry no plugin info (no marker yet) — plugins[] must be empty, not absent-by-luck.
        assertEquals(0, backend["plugins"]!!.jsonArray.size)
        val detail = backend["managedDetail"]!!.jsonObject
        assertEquals("idea-community-2025.2.6.2", detail["managedId"]?.jsonPrimitive?.contentOrNull)
        assertEquals("2025.2.6.2", detail["version"]?.jsonPrimitive?.contentOrNull)
        assertEquals("running", detail["state"]?.jsonPrimitive?.contentOrNull)
        assertEquals("/managed/idea-community-2025.2.6.2", detail["installPath"]?.jsonPrimitive?.contentOrNull)
        assertEquals("/caches/idea-community-2025.2.6.2", detail["cachePath"]?.jsonPrimitive?.contentOrNull)
        assertEquals(44L, detail["runningPid"]?.jsonPrimitive?.long)
    }

    // ---------------------------- mixed list -------------------------------

    @Test
    fun `mixed list keeps input order and assigns backend names from the uniform hash scheme`() {
        val rows = listOf(
            BackendRow.FromMarker(markerIde(pid = 1L), emptyList()),
            BackendRow.FromPort(portIde(port = 63342)),
            BackendRow.FromMarker(markerIde(pid = 2L), null, errorMessage = "x"),
        )
        val backends = render(rows)["backends"]!!.jsonArray
        assertEquals(3, backends.size)
        assertEquals(rows.map { backendNameForRow(it) }, backends.map { it.jsonObject["backend_name"]!!.jsonPrimitive.content })
        assertEquals("marker", backends[0].jsonObject["source"]?.jsonPrimitive?.contentOrNull)
        assertEquals("port", backends[1].jsonObject["source"]?.jsonPrimitive?.contentOrNull)
        assertEquals("marker", backends[2].jsonObject["source"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `top-level projects reference valid backend names`() {
        val rows = listOf(
            BackendRow.FromMarker(markerIde(pid = 1L), listOf(ProjectInfo("a", "/a"), ProjectInfo("b", "/b"))),
            BackendRow.FromPort(portIde(port = 63342)),
            BackendRow.FromMarker(markerIde(pid = 2L), listOf(ProjectInfo("c", "/c"))),
        )
        val root = render(rows)
        val names = root["backends"]!!.jsonArray.map { it.jsonObject["backend_name"]!!.jsonPrimitive.content }.toSet()
        val refs = root["projects"]!!.jsonArray.map { it.jsonObject["backend_name"]!!.jsonPrimitive.content }
        assertEquals(3, refs.size)
        assertTrue(refs.all { it in names }, "every projects[].backend_name must resolve to a backend; names=$names refs=$refs")
    }

    @Test
    fun `backend names stay stable when discovery rows are reordered`() {
        val marker = BackendRow.FromMarker(markerIde(pid = 200L), emptyList())
        val port = BackendRow.FromPort(portIde(port = 65432))
        val managed = BackendRow.FromManaged(managedInfo(id = "idea-community-2025.2.6.2"))
        val expected = mapOf(
            "marker" to backendNameForRow(marker),
            "port" to backendNameForRow(port),
            "managed" to backendNameForRow(managed),
        )

        fun namesBySource(rows: List<BackendRow>) = render(rows)["backends"]!!.jsonArray.associate { element ->
            val backend = element.jsonObject
            backend["source"]!!.jsonPrimitive.content to backend["backend_name"]!!.jsonPrimitive.content
        }

        assertEquals(expected, namesBySource(listOf(marker, port, managed)))
        assertEquals(expected, namesBySource(listOf(managed, marker, port)))
    }

    @Test
    fun `duplicate backend names log warning and keep first row`() {
        // Same pid + same build => same backend_name. Keep-first de-dup, WARN logged.
        val first = BackendRow.FromMarker(markerIde(pid = 777L), listOf(ProjectInfo("first", "/first")))
        val second = BackendRow.FromMarker(
            markerIde(pid = 777L, mcpUrl = "http://localhost:7778/mcp"),
            listOf(ProjectInfo("second", "/second")),
        )
        val expectedName = backendNameForRow(first)

        val (root, events) = captureBackendCommandLogs { render(listOf(first, second)) }

        val names = backendNames(root)
        assertEquals(1, names.count { it == expectedName })
        assertEquals(listOf(expectedName), names)
        val projects = root["projects"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("first"), projects.map { it["name"]!!.jsonPrimitive.content })
        assertEquals(listOf(expectedName), projects.map { it["backend_name"]!!.jsonPrimitive.content })

        val warnMessages = events.filter { it.level == Level.WARN }.map { it.formattedMessage }
        assertTrue(
            warnMessages.any { it.contains("Duplicate backend_name in backend --json output") && it.contains(expectedName) },
            "expected a WARN mentioning duplicate $expectedName; got $warnMessages",
        )
    }

    @Test
    fun `output has no banner -- pure JSON on stdout for safe piping`() {
        val buf = ByteArrayOutputStream()
        renderBackendJson(emptyList(), PrintStream(buf, true, Charsets.UTF_8))
        val text = buf.toString(Charsets.UTF_8).trim()
        assertTrue(text.startsWith("{"),
            "output must start with '{' (the JSON document); got first 60 chars: '${text.take(60)}'")
        assertTrue(text.endsWith("}"),
            "output must end with '}' — no trailing banner allowed; got last 60 chars: '${text.takeLast(60)}'")
    }
}
