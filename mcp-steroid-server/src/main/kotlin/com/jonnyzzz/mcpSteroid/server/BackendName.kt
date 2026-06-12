/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import java.math.BigInteger
import java.security.MessageDigest

/**
 * base62 alphabet (alphanumeric, no `-`/`_`). Unlike URL-safe Base64 the result can never contain or
 * end with `-`, so the hash is safe to embed into ids and names without quoting.
 */
private const val BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

/**
 * THE shared 8-char id hash: `sha256(input.utf8)` rendered as fixed-width base62 (see
 * [base62FixedWidth]). One helper for every devrig-exposed id — backend names ([backendNameFor]) and
 * project names (`DevrigProjectRoutingService.projectHash`) — so the IDE self-id and devrig recompute
 * the same value for the same input. The full 256-bit digest feeds the encoder; only the rendered
 * string is taken to 8 chars, nothing is truncated before hashing.
 */
fun hash8(input: String): String =
    base62FixedWidth(MessageDigest.getInstance("SHA-256").digest(input.encodeToByteArray()), 8)

/**
 * Renders [bytes] as a fixed-[width] base62 string, least-significant digit first, zero-padded. This is
 * the historical `DevrigProjectRoutingService.projectHash` rendering — kept byte-identical so existing
 * exposed project names do not change. [hash8] is the canonical entry point for new opaque ids.
 */
fun base62FixedWidth(bytes: ByteArray, width: Int): String {
    var value = BigInteger(1, bytes)
    val base = BigInteger.valueOf(62L)
    val sb = StringBuilder(width)
    repeat(width) {
        val (q, r) = value.divideAndRemainder(base)
        sb.append(BASE62[r.toInt()])
        value = q
    }
    return sb.toString()
}

/**
 * Extracts the product code prefix from an IntelliJ build string, e.g. `IU-261.1234` -> `IU`. Returns
 * `null` when [buildNumber] is null or has no `^[A-Z]+-` prefix. Shared so the IDE self-id and devrig
 * compute the same [backendNameFor] product segment for the same build.
 */
fun productCodeFromBuild(buildNumber: String?): String? =
    buildNumber?.let { Regex("""^([A-Z]+)-""").find(it)?.groupValues?.get(1) }

/**
 * The ONE backend_name formula, shared by the in-IDE plugin self-id and devrig's discovery — for
 * marker, port, AND managed rows alike. There is no other code path producing a backend_name.
 *
 * ```
 * backend_name = "<PRODUCTCODE>-<hash8>"
 *   PRODUCTCODE = the verbatim build-number prefix, capitals as-is ("IU-261.1" -> "IU"); fallback "IDE"
 *   hash8       = hash8(sourceKey)   // the same sha256->base62(8) helper project names use
 *   sourceKey   = "pid:<pid>" | "port:<port>" | "managed:<managedId>"
 * ```
 *
 * [productCode] is the verbatim build prefix: marker/port callers derive it via [productCodeFromBuild];
 * managed callers pass the catalog's `productCode`, which is the same value the build prefix carries
 * (`product-info.json` productCode == the prefix of its buildNumber). Never lowercased.
 *
 * The pid/port/source/routability live as their own [BackendInfo] fields — never encoded into the id
 * shape. Deterministic and round-trippable: devrig recomputes it per discovered backend to resolve
 * `backend_name -> backend`. One definition for both modules; devrig's `backendNameForMarker/Port/Managed`
 * delegate here.
 */
fun backendNameFor(productCode: String?, sourceKey: String): String {
    val code = productCode?.takeIf { it.isNotBlank() } ?: "IDE"
    return "$code-${hash8(sourceKey)}"
}

/** Marker-IDE backend_name: keyed by the IDE's real pid (the only open_project-routable source). */
fun backendNameForMarker(pid: Long, build: String?): String =
    backendNameFor(productCode = productCodeFromBuild(build), sourceKey = "pid:$pid")
