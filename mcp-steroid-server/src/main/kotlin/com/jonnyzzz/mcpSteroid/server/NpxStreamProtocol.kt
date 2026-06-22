/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Wire-protocol envelope for the `/npx/v1/projects/stream` NDJSON response.
 *
 * Backward/forward compatibility: decoders MUST use [NpxStreamJson] (which
 * sets `ignoreUnknownKeys = true`). New optional fields SHOULD be added with
 * a default so old writers stay compatible.
 *
 * Envelope shape uses one struct with optional fields rather than a sealed
 * polymorphic hierarchy — that keeps the wire format trivial to extend
 * without breaking strict decoders.
 */
@Serializable
data class NpxStreamEnvelope(
    /** "snapshot" | "ping" — newer servers may add types; clients ignore unknown values. */
    val type: String,
    val seq: Long,
    val sentAt: String,
    /** Stable per-IDE-process identifier so clients can multiplex concurrent IDE connections. */
    val instanceId: String,
    /** OS pid of the IDE process. */
    val pid: Long,
    /** Present when `type == "snapshot"`. Absent (null) for pings and other events. */
    val projects: List<ProjectInfo>? = null,
)

/**
 * Tolerant codec for the wire protocol — `ignoreUnknownKeys = true` is the
 * forward-compat hook used on both ends of the stream.
 */
object NpxStreamJson {
    val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    fun encodeJsonObject(obj: JsonObject): String =
        json.encodeToString(JsonObject.serializer(), obj)

    fun decodeJsonObject(text: String): JsonObject =
        json.decodeFromString(JsonObject.serializer(), text)

    fun encodeEnvelope(envelope: NpxStreamEnvelope): String =
        json.encodeToString(NpxStreamEnvelope.serializer(), envelope)

    fun decodeEnvelope(line: String): NpxStreamEnvelope =
        json.decodeFromString(NpxStreamEnvelope.serializer(), line)

    fun encodeObject(obj: JsonObject): String =
        json.encodeToString(JsonObject.serializer(), obj)
}

/**
 * Path prefix of the devrig bridge RPC, served by the plugin (currently on its own Ktor server, but the
 * `/api/...` shape is safe whatever webserver ever hosts it). This is the devrig transport — split at the
 * protocol level from the `/mcp` MCP-client endpoint. The plugin advertises the FULL base URL
 * (`http://host:port$DEVRIG_RPC_PATH_PREFIX`) in the marker's `devrigEndpoint`, so devrig never has to
 * know or hardcode this prefix; it lives here only for the plugin's own route + URL construction.
 */
const val DEVRIG_RPC_PATH_PREFIX: String = "/api/jonnyzzz/mcp-steroid/v1"

/** MIME type the IDE responds with on the projects-stream endpoint. */
const val NPX_NDJSON_MIME_TYPE: String = "application/x-ndjson"

/**
 * Keepalive cadence for npx bridge NDJSON streams.
 *
 * The project stream sends `ping`; the tool-call stream sends `heartbeat`.
 * The names intentionally match the existing per-stream protocols.
 */
const val NPX_STREAM_KEEPALIVE_INTERVAL_SECONDS: Int = 10

/** Client idle timeout budget: five missed keepalives. */
const val NPX_STREAM_IDLE_TIMEOUT_MILLIS: Long = 50_000
