/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import com.jonnyzzz.mcpSteroid.server.backendNameFor

// R3.3 — the shared backend_name formula (backendNameFor + backendNameForMarker) lives in
// mcp-steroid-server (com.jonnyzzz.mcpSteroid.server.BackendName) so the in-IDE plugin and devrig
// recompute the same id for the same input. The port variant below is devrig-only.

/** Port-discovered backend_name: keyed by the scanned port. */
fun backendNameForPort(port: Int, build: String?): String =
    backendNameFor(sourceKey = "port:$port", build = build)

/**
 * Strip a leading product-code prefix (letters + hyphen, e.g. `IU-`, `PC-`,
 * `GO-`) so marker builds (`IU-261.23567.138`) compare equal to `/api/about`
 * builds (`261.23567.138`). Returns `null` for null/blank input so callers
 * can use it as a Map key without further filtering.
 */
fun normaliseBuildForDedup(build: String?): String? {
    if (build.isNullOrBlank()) return null
    return build.replaceFirst(Regex("^[A-Z]+-"), "")
}

/**
 * True when a bare platform baseline was given — `262`, `IC-262` — with no build/patch segments.
 * Used where the resolved build is no longer at hand (a persisted descriptor); a live resolution
 * carries [BackendDownloadResolution.buildIsBaseline] instead of guessing from the string shape.
 */
fun isPlatformBaselineOnly(build: String?): Boolean {
    val baseline = normaliseBuildForDedup(build)?.toIntOrNull() ?: return false
    return baseline in 1..999
}

/**
 * True when the build [actual] reported by a real install (`product-info.json`, a marker file) is the
 * build that was requested. Product-code prefixes are ignored on both sides, so `IC-262.8665.258`
 * and `262.8665.258` are the same build.
 *
 * Some feeds only publish the platform baseline: GitHub Community releases and the Android Studio
 * canary page resolve to `262` while the artifact reports `262.8665.258`. Those resolutions set
 * [expectedIsBaseline], and then [expected] matches the FIRST dot-separated segment of [actual] —
 * segment-wise, not a raw string prefix, so `26` never matches `262.8665.258`.
 */
fun ideBuildMatches(actual: String?, expected: String?, expectedIsBaseline: Boolean): Boolean {
    val normalisedActual = normaliseBuildForDedup(actual) ?: return false
    val normalisedExpected = normaliseBuildForDedup(expected) ?: return false
    if (normalisedActual == normalisedExpected) return true
    return expectedIsBaseline && normalisedActual.substringBefore('.') == normalisedExpected
}
