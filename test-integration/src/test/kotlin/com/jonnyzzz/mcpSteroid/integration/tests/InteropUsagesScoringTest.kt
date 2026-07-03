/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.integration.tests

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure, local tests for [scoreInteropUsages] — the found/missed/false-positive verdict for the
 * Kotlin↔Java cross-language find-usages A/B (OkHttp `RecordedRequest.requestLine`).
 *
 * The interop trap being scored: a Kotlin `val requestLine` is consumed from Java as
 * `getRequestLine()`, so a case-sensitive text search for the declared name finds ZERO of the
 * Java call sites, while a loose search for the property name over-matches unrelated local
 * variables that merely share the identifier (`val requestLine = "$method $path HTTP/1.1"`).
 * Only resolve-based find-usages crosses the language boundary exactly. No IDE/Docker needed —
 * scored on text only.
 */
class InteropUsagesScoringTest {

    // A toy ground truth mirroring the okhttp shape: Java call sites of the generated getter
    // (cross-language — REQUIRED) plus a Kotlin property read in another file (also REQUIRED),
    // and the declaring-file internal reads (OPTIONAL — reported or not, never penalized).
    private val required = mapOf(
        "okhttp/src/test/java/okhttp3/CacheTest.java" to setOf(385, 392, 400),
        "mockwebserver/src/main/kotlin/okhttp3/mockwebserver/QueueDispatcher.kt" to setOf(34),
    )
    private val optional = mapOf(
        "mockwebserver/src/main/kotlin/okhttp3/mockwebserver/RecordedRequest.kt" to setOf(33, 96, 137),
    )

    private fun score(output: String) = scoreInteropUsages(output, required, optional)

    @Test
    fun `exact enumeration scores complete and exact`() {
        val output = """
            USAGES_FOUND: 4
            USAGE: okhttp/src/test/java/okhttp3/CacheTest.java:385
            USAGE: okhttp/src/test/java/okhttp3/CacheTest.java:392
            USAGE: okhttp/src/test/java/okhttp3/CacheTest.java:400
            USAGE: mockwebserver/src/main/kotlin/okhttp3/mockwebserver/QueueDispatcher.kt:34
        """.trimIndent()
        val s = score(output)
        assertTrue(s.complete, "missed=${s.missedRequired}")
        assertTrue(s.exact, "falsePositives=${s.falsePositives}")
        assertEquals(4, s.reportedCount)
        assertEquals(4, s.reportedPairCount)
        assertEquals(required, s.foundRequired)
        assertTrue(s.missedRequired.isEmpty())
        assertTrue(s.falsePositives.isEmpty())
    }

    @Test
    fun `getter-only grep misses the Kotlin property read`() {
        // A baseline that only searched for "getRequestLine" finds every Java call site but
        // no Kotlin property-syntax usage.
        val output = """
            USAGES_FOUND: 3
            USAGE: okhttp/src/test/java/okhttp3/CacheTest.java:385
            USAGE: okhttp/src/test/java/okhttp3/CacheTest.java:392
            USAGE: okhttp/src/test/java/okhttp3/CacheTest.java:400
        """.trimIndent()
        val s = score(output)
        assertFalse(s.complete)
        assertEquals(
            mapOf("mockwebserver/src/main/kotlin/okhttp3/mockwebserver/QueueDispatcher.kt" to setOf(34)),
            s.missedRequired,
        )
        assertTrue(s.falsePositives.isEmpty())
    }

    @Test
    fun `property-name grep over-matches unrelated locals and misses Java sites`() {
        // A baseline that only searched for "requestLine" reports same-named LOCAL VARIABLES
        // (not property usages) and finds zero Java getter call sites.
        val output = """
            USAGES_FOUND: 3
            USAGE: mockwebserver/src/main/kotlin/okhttp3/mockwebserver/QueueDispatcher.kt:34
            USAGE: mockwebserver/src/main/kotlin/okhttp3/mockwebserver/MockWebServer.kt:1037
            USAGE: okhttp/src/main/kotlin/okhttp3/internal/http1/Http1ExchangeCodec.kt:118
        """.trimIndent()
        val s = score(output)
        assertFalse(s.complete)
        assertEquals(setOf("okhttp/src/test/java/okhttp3/CacheTest.java"), s.missedRequired.keys)
        assertEquals(
            setOf(
                "mockwebserver/src/main/kotlin/okhttp3/mockwebserver/MockWebServer.kt:1037",
                "okhttp/src/main/kotlin/okhttp3/internal/http1/Http1ExchangeCodec.kt:118",
            ),
            s.falsePositives,
        )
        assertFalse(s.exact)
    }

    @Test
    fun `markdown decoration and absolute container paths are normalized`() {
        val output = """
            **USAGES_FOUND**: 4
            - **USAGE:** `/home/agent/project/okhttp/src/test/java/okhttp3/CacheTest.java:385`
            - USAGE: `/home/agent/project/okhttp/src/test/java/okhttp3/CacheTest.java:392`
            > USAGE: /home/agent/project/okhttp/src/test/java/okhttp3/CacheTest.java:400
            USAGE: mockwebserver/src/main/kotlin/okhttp3/mockwebserver/QueueDispatcher.kt:34
        """.trimIndent()
        val s = score(output)
        assertTrue(s.complete, "missed=${s.missedRequired}")
        assertTrue(s.exact, "falsePositives=${s.falsePositives}")
        assertEquals(4, s.reportedCount)
    }

    @Test
    fun `one line of tolerance is allowed for multi-line expressions`() {
        val output = """
            USAGE: okhttp/src/test/java/okhttp3/CacheTest.java:386
            USAGE: okhttp/src/test/java/okhttp3/CacheTest.java:392
            USAGE: okhttp/src/test/java/okhttp3/CacheTest.java:400
            USAGE: mockwebserver/src/main/kotlin/okhttp3/mockwebserver/QueueDispatcher.kt:34
        """.trimIndent()
        val s = score(output)
        assertTrue(s.complete, "missed=${s.missedRequired}")
        assertTrue(s.exact, "falsePositives=${s.falsePositives}")
    }

    @Test
    fun `adjacent ground-truth lines are not double-claimed by one reported line`() {
        // Two required lines 1 apart must need two reported lines: one report cannot satisfy both.
        val tightTruth = mapOf("a/B.java" to setOf(10, 11))
        val s = scoreInteropUsages("USAGE: a/B.java:10", tightTruth)
        assertFalse(s.complete)
        assertEquals(mapOf("a/B.java" to setOf(11)), s.missedRequired)
    }

    @Test
    fun `declaring-file internal reads are optional - neither required nor penalized`() {
        val output = """
            USAGE: okhttp/src/test/java/okhttp3/CacheTest.java:385
            USAGE: okhttp/src/test/java/okhttp3/CacheTest.java:392
            USAGE: okhttp/src/test/java/okhttp3/CacheTest.java:400
            USAGE: mockwebserver/src/main/kotlin/okhttp3/mockwebserver/QueueDispatcher.kt:34
            USAGE: mockwebserver/src/main/kotlin/okhttp3/mockwebserver/RecordedRequest.kt:96
            USAGE: mockwebserver/src/main/kotlin/okhttp3/mockwebserver/RecordedRequest.kt:137
        """.trimIndent()
        val s = score(output)
        assertTrue(s.complete)
        assertTrue(s.exact, "falsePositives=${s.falsePositives}")
    }

    @Test
    fun `non-source paths are ignored - a README mention is neither found nor penalized`() {
        val output = """
            USAGE: okhttp/src/test/java/okhttp3/CacheTest.java:385
            USAGE: okhttp/src/test/java/okhttp3/CacheTest.java:392
            USAGE: okhttp/src/test/java/okhttp3/CacheTest.java:400
            USAGE: mockwebserver/src/main/kotlin/okhttp3/mockwebserver/QueueDispatcher.kt:34
            USAGE: mockwebserver/README.md:109
        """.trimIndent()
        val s = score(output)
        assertTrue(s.complete)
        assertTrue(s.exact, "falsePositives=${s.falsePositives}")
        assertEquals(4, s.reportedPairCount)
    }

    @Test
    fun `duplicate USAGE lines are deduplicated`() {
        val output = """
            USAGE: okhttp/src/test/java/okhttp3/CacheTest.java:385
            USAGE: okhttp/src/test/java/okhttp3/CacheTest.java:385
        """.trimIndent()
        val s = score(output)
        assertEquals(1, s.reportedPairCount)
        assertEquals(mapOf("okhttp/src/test/java/okhttp3/CacheTest.java" to setOf(385)), s.foundRequired)
        assertTrue(s.falsePositives.isEmpty())
    }

    @Test
    fun `a shorter but unambiguous relative path still counts`() {
        val output = """
            USAGE: okhttp3/CacheTest.java:385
            USAGE: okhttp3/CacheTest.java:392
            USAGE: okhttp3/CacheTest.java:400
            USAGE: okhttp3/mockwebserver/QueueDispatcher.kt:34
        """.trimIndent()
        val s = score(output)
        assertTrue(s.complete, "missed=${s.missedRequired}")
        assertTrue(s.exact, "falsePositives=${s.falsePositives}")
    }

    @Test
    fun `no USAGE markers at all scores everything missed and no count`() {
        val s = score("I searched but could not enumerate the usages.")
        assertFalse(s.complete)
        assertFalse(s.exact)
        assertEquals(required, s.missedRequired)
        assertEquals(null, s.reportedCount)
        assertEquals(0, s.reportedPairCount)
    }

    @Test
    fun `USAGES_FOUND count line is not mis-parsed as a USAGE marker`() {
        val s = score("USAGES_FOUND: 60")
        assertEquals(60, s.reportedCount)
        assertEquals(0, s.reportedPairCount)
    }
}
