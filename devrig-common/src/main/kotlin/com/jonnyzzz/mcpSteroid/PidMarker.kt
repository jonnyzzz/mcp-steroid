package com.jonnyzzz.mcpSteroid

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path

/**
 * Schema-versioned JSON document written to
 * `~/.mcp-steroid/markers/<pid>.mcp-steroid` by every IDE that runs the
 * MCP Steroid plugin. Discovered by external monitors, including devrig,
 * to learn where the IDE's MCP server is reachable.
 *
 * Forward compatibility: readers MUST decode with [PidMarkerJson], which
 * is configured to ignore unknown JSON keys so new optional fields stay
 * backwards-readable.
 *
 * The REQUIRED fields ([schema], [pid], [ide], [plugin], [createdAt]) have no defaults, so every writer
 * must set them and decoding rejects their omission. The OPTIONAL sub-objects default to `null`: this is
 * the backward/forward-compat hook — a marker written by an older or newer plugin that omits one of them
 * still decodes (kotlinx.serialization treats a nullable field WITHOUT a default as required, which would
 * throw on absence). Writers still set them explicitly (`encodeDefaults = true` keeps them in the output).
 */
@Serializable
data class PidMarker(
    val schema: Int,
    val pid: Long,
    val ide: IdeInfo,
    val plugin: PluginInfo,
    val createdAt: String,
    /** Absolute IDE install home (`PathManager.getHomePath()`); identifies the install across restarts. */
    val ideHome: String? = null,
    // Both transports are optional and independent: the `/mcp` MCP-client endpoint and the devrig bridge
    // endpoint are split at the protocol level. A marker may advertise only one of them — e.g. only
    // [devrigEndpoint] with no [mcpSteroidServer]. devrig reads ONLY [devrigEndpoint] and never touches MCP.
    val mcpSteroidServer: McpSteroidServerInfo? = null,
    val devrigEndpoint: DevrigEndpointInfo? = null,
    val intellijWebServer: IntelliJWebServerInfo? = null,
    val intellijMcpServer: IntelliJMcpServerInfo? = null,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1

        /** Directory the plugin writes markers into: `<userHome>/.mcp-steroid/markers`. */
        fun markerDirectory(userHome: Path): Path =
            userHome.resolve(".mcp-steroid").resolve("markers")

        /** Filename inside [markerDirectory] for a running IDE pid. */
        fun markerFileNameFor(pid: Long): String = "$pid.mcp-steroid"

        /** Parses the pid out of a marker filename, or returns `null` for an unrelated file. */
        fun pidFromFileName(fileName: String): Long? {
            val match = FILE_NAME_REGEX.matchEntire(fileName) ?: return null
            return match.groupValues[1].toLongOrNull()
        }

        private val FILE_NAME_REGEX = Regex("""^(\d+)\.mcp-steroid$""")
    }
}

/**
 * Tolerant JSON codec for [PidMarker]. `ignoreUnknownKeys = true` is the
 * forward-compat hook; pretty-print keeps the file useful when a human
 * `cat`s it.
 */
object PidMarkerJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun encode(marker: PidMarker): String = json.encodeToString(PidMarker.serializer(), marker)

    fun decode(text: String): PidMarker = json.decodeFromString(PidMarker.serializer(), text)
}
