/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.IdeInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Presentation-only identity of an IntelliJ-Platform IDE, as exposed on the MCP tool responses
 * (`backends[]`, #155). Dedicated to this surface by design: it is NOT the marker/wire [IdeInfo] —
 * the marker type can evolve (per #151) without leaking new fields here, and this type never
 * crosses the devrig<->IDE wire. Exactly the three fields the historic consumers read; adding a
 * field requires a concrete consumer need and a `BackendRefSerializationTest` drift-gate update —
 * inventory extras (pid, port, endpoints, plugins) belong to `devrig backend --json` (#151),
 * never here. Naming note: the JSON field is `intellij` = "the IntelliJ-*Platform* IDE" — a GoLand
 * or PyCharm backend still nests under `intellij`; the product is identified by [name]/[build].
 */
@Serializable
data class IntelliJInfo(
    /** e.g. "IntelliJ IDEA 2026.1.3" (`ApplicationInfo.fullApplicationName`). */
    val name: String,
    /** e.g. "2026.1.3" (`ApplicationInfo.fullVersion`). */
    val version: String,
    /** e.g. "IU-261.25134.95" (`ApplicationInfo.build.asString()`). */
    val build: String,
)

/** The single shared projection both surfaces use — keeps the mapping identical by construction. */
fun IdeInfo.toIntelliJInfo(): IntelliJInfo = IntelliJInfo(name = name, version = version, build = build)

/**
 * MCP-only lookup element: resolves a `backend_name` seen on `projects[]`/`windows[]`/
 * `backgroundTasks[]` entries to the owning IDE's identity. Never crosses the devrig<->IDE wire.
 */
@Serializable
data class BackendRef(
    @SerialName("backend_name") val backendName: String,
    val intellij: IntelliJInfo,
)

/**
 * Builds a response's `backends[]` resolution table: de-duplicated by `backend_name` (keep-first)
 * and sorted by it — deterministic regardless of snapshot/scan order. Shared by the direct in-IDE
 * handlers and devrig's aggregating handlers so the table rule cannot diverge.
 */
fun backendsTable(refs: Iterable<BackendRef>): List<BackendRef> =
    refs.distinctBy { it.backendName }.sortedBy { it.backendName }
