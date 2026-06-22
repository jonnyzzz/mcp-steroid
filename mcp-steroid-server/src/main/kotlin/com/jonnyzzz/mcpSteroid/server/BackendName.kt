/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

/**
 * Extracts the product code prefix from an IntelliJ build string, e.g. `IU-261.1234` -> `IU`. Returns
 * `null` when [buildNumber] is null or has no `^[A-Z]+-` prefix. Shared so the IDE self-id and devrig
 * compute the same [backendNameFor] product segment for the same build.
 */
fun productCodeFromBuild(buildNumber: String?): String? =
    buildNumber?.let { Regex("""^([A-Z]+)-""").find(it)?.groupValues?.get(1) }

/**
 * R3.3 — the ONE backend_name formula, shared by the in-IDE plugin self-id and devrig's discovery.
 *
 * ```
 * backend_name = "<productCodeLower>-<hash8>"
 *   productCodeLower = productCodeFromBuild(build)?.lowercase() ?: "ide"   // "IU" -> "iu"; fallback "ide"
 *   hash8            = base62Sha256(sourceKey).take(8)
 *   sourceKey        = "pid:<pid>" | "port:<port>" | "managed:<managedId>"
 * ```
 *
 * The pid/port/source/routability live as their own [BackendInfo] fields — never encoded into the id
 * shape. Deterministic and round-trippable: devrig recomputes it per discovered backend to resolve
 * `backend_name -> backend`. One definition for both modules; devrig's `backendNameForMarker/Port/Managed`
 * delegate here.
 */
fun backendNameFor(sourceKey: String, build: String?): String {
    val productCodeLower = productCodeFromBuild(build)?.lowercase() ?: "ide"
    val hash8 = base36FixedWidth(sourceKey, build).take(8)
    return "$productCodeLower-$hash8"
}

/** Marker-IDE backend_name: keyed by the IDE's real pid (the only open_project-routable source). */
fun backendNameForMarker(pid: Long, build: String?): String =
    backendNameFor(sourceKey = "pid:$pid", build = build)
