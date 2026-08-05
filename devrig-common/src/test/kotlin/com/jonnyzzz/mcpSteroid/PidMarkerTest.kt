package com.jonnyzzz.mcpSteroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PidMarkerTest {

    @Test
    fun `roundtrip preserves all fields`() {
        val original = samplePidMarker()
        val decoded = PidMarkerJson.decode(PidMarkerJson.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `encoded form is pretty-printed JSON with mcpSteroidServer sub-object`() {
        val text = PidMarkerJson.encode(samplePidMarker())
        assertTrue(text.startsWith("{\n"), "expected pretty-printed start, got: $text")
        assertTrue(text.contains("\"schema\": 1"), "schema field missing: $text")
        assertTrue(text.contains("\"pid\": 12345"), "pid field missing: $text")
        assertTrue(text.contains("\"mcpSteroidServer\""), "mcpSteroidServer field missing: $text")
        assertTrue(text.contains("\"mcpUrl\": \"http://localhost:64531/mcp\""), "mcpUrl field missing: $text")
        assertTrue(text.contains("\"devrigEndpoint\""), "devrigEndpoint field missing: $text")
        assertTrue(
            text.contains("\"rpcBaseUrl\": \"http://localhost:64531/api/jonnyzzz/mcp-steroid/v1\""),
            "devrigEndpoint.rpcBaseUrl missing: $text",
        )
        assertTrue(text.contains("\"Authorization\": \"Bearer deadbeefcafebabe\""), "headers missing: $text")
    }

    @Test
    fun `intellijMcpServer roundtrips when present`() {
        val withIde = samplePidMarker().copy(
            intellijMcpServer = IntelliJMcpServerInfo(
                enabled = true,
                port = 64342,
                streamUrl = "http://127.0.0.1:64342/stream",
                sseUrl = "http://127.0.0.1:64342/sse",
                headers = emptyMap(),
            )
        )
        val text = PidMarkerJson.encode(withIde)
        val decoded = PidMarkerJson.decode(text)
        assertEquals(withIde, decoded)
        assertTrue(text.contains("\"intellijMcpServer\""), "intellijMcpServer field missing: $text")
        assertTrue(text.contains("\"streamUrl\": \"http://127.0.0.1:64342/stream\""), "streamUrl missing")
    }

    @Test
    fun `intellijWebServer roundtrips when present`() {
        val withWebServer = samplePidMarker().copy(
            intellijWebServer = IntelliJWebServerInfo(
                enabled = true,
                host = "127.0.0.1",
                port = 63342,
                baseUrl = "http://127.0.0.1:63342",
                aboutUrl = "http://127.0.0.1:63342/api/about?_ijt=token",
                headers = mapOf("x-ijt" to "token"),
            )
        )
        val text = PidMarkerJson.encode(withWebServer)
        val decoded = PidMarkerJson.decode(text)
        assertEquals(withWebServer, decoded)
        assertTrue(text.contains("\"intellijWebServer\""), "intellijWebServer field missing: $text")
        assertTrue(text.contains("\"x-ijt\": \"token\""), "x-ijt header missing")
    }

    @Test
    fun `decode roundtrips when intellij sub-objects are absent (null)`() {
        val text = PidMarkerJson.encode(samplePidMarker())
        val decoded = PidMarkerJson.decode(text)
        assertNull(decoded.intellijWebServer)
        assertNull(decoded.intellijMcpServer)
    }

    @Test
    fun `decode tolerates unknown future fields (forward-compat)`() {
        val futureJson = """
            {
              "schema": 7,
              "pid": 12345,
              "mcpSteroidServer": {
                "mcpUrl": "http://localhost:64531/mcp",
                "port": 64531,
                "headers": {"Authorization": "Bearer t"}
              },
              "ide": {"name":"IntelliJ IDEA","version":"2025.3.3","build":"IU-253.1.1"},
              "plugin": {"id":"x","name":"y","version":"z"},
              "createdAt": "2026-05-10T12:34:56Z",
              "intellijWebServer": null,
              "intellijMcpServer": null,
              "futureField": {"nested": [1,2,3]},
              "anotherFuture": "ignored"
            }
        """.trimIndent()
        val decoded = PidMarkerJson.decode(futureJson)
        assertEquals(7, decoded.schema)
        assertEquals(12345L, decoded.pid)
    }

    @Test
    fun `decode rejects required-field omission`() {
        val badJson = """
            {
              "pid": 12345,
              "mcpSteroidServer": {"mcpUrl":"http://localhost:64531/mcp"}
            }
        """.trimIndent()
        assertThrows(Exception::class.java) { PidMarkerJson.decode(badJson) }
    }

    @Test
    fun `pluginPath roundtrips on McpSteroidServerInfo when present`() {
        val withPluginPath = samplePidMarker().copy(
            mcpSteroidServer = McpSteroidServerInfo(
                mcpUrl = "http://localhost:64531/mcp",
                headers = mapOf("Authorization" to "Bearer deadbeefcafebabe"),
                pluginPath = "/opt/idea/plugins/mcp-steroid",
            )
        )
        val text = PidMarkerJson.encode(withPluginPath)
        val decoded = PidMarkerJson.decode(text)
        assertEquals(withPluginPath, decoded)
        assertEquals("/opt/idea/plugins/mcp-steroid", decoded.mcpSteroidServer?.pluginPath)
        assertTrue(text.contains("\"pluginPath\": \"/opt/idea/plugins/mcp-steroid\""), "pluginPath missing from JSON: $text")
    }

    @Test
    fun `pluginPath on McpSteroidServerInfo decodes as null when absent (older plugin compat)`() {
        val jsonWithoutPluginPath = """
            {
              "schema": 1,
              "pid": 12345,
              "mcpSteroidServer": {
                "mcpUrl": "http://localhost:64531/mcp",
                "headers": {"Authorization": "Bearer t"}
              },
              "ide": {"name":"IntelliJ IDEA","version":"2025.3.3","build":"IU-253.1.1"},
              "plugin": {"id":"x","name":"y","version":"z"},
              "createdAt": "2026-05-10T12:34:56Z"
            }
        """.trimIndent()
        val decoded = PidMarkerJson.decode(jsonWithoutPluginPath)
        assertNull(decoded.mcpSteroidServer?.pluginPath)
    }

    @Test
    fun `ideHome is optional and decodes when present and absent`() {
        val withHome = PidMarkerJson.decode(
            """{"schema":1,"pid":7,"ide":{"name":"X","version":"1","build":"IU-1"},
               "plugin":{"id":"p","name":"P","version":"v"},"createdAt":"t",
               "ideHome":"/opt/idea"}""".trimIndent()
        )
        assertEquals("/opt/idea", withHome.ideHome)

        val withoutHome = PidMarkerJson.decode(
            """{"schema":1,"pid":7,"ide":{"name":"X","version":"1","build":"IU-1"},
               "plugin":{"id":"p","name":"P","version":"v"},"createdAt":"t"}""".trimIndent()
        )
        assertNull(withoutHome.ideHome)
    }

    @Test
    fun `file name parsing accepts the canonical pid layout`() {
        assertEquals("12345.mcp-steroid", PidMarker.markerFileNameFor(12345))
        assertEquals(12345L, PidMarker.pidFromFileName("12345.mcp-steroid"))
        assertNull(PidMarker.pidFromFileName(".12345.mcp-steroid"))
        assertNull(PidMarker.pidFromFileName(".mcp-steroid"))
        assertNull(PidMarker.pidFromFileName("12345.mcp-steroid.json"))
    }

    private fun samplePidMarker(): PidMarker = PidMarker(
        schema = PidMarker.SCHEMA_VERSION,
        pid = 12345,
        mcpSteroidServer = McpSteroidServerInfo(
            mcpUrl = "http://localhost:64531/mcp",
            headers = mapOf("Authorization" to "Bearer deadbeefcafebabe"),
        ),
        devrigEndpoint = DevrigEndpointInfo(
            rpcBaseUrl = "http://localhost:64531/api/jonnyzzz/mcp-steroid/v1",
            headers = mapOf("Authorization" to "Bearer deadbeefcafebabe"),
        ),
        ide = IdeInfo(name = "IntelliJ IDEA", version = "2025.3.3", build = "IU-253.1.1"),
        plugin = PluginInfo(id = "com.jonnyzzz.mcpSteroid", name = "MCP Steroid", version = "1.0.0"),
        createdAt = "2026-05-10T12:34:56Z",
        intellijWebServer = null,
        intellijMcpServer = null,
    )
}
