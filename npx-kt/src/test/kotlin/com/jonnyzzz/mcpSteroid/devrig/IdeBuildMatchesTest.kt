/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.devrig

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class IdeBuildMatchesTest {

    @Test
    fun `a full build matches itself, prefixed or not`() {
        assertTrue(ideBuildMatches("262.8665.258", "262.8665.258", expectedIsBaseline = false))
        assertTrue(ideBuildMatches("IC-262.8665.258", "262.8665.258", expectedIsBaseline = false))
        assertTrue(ideBuildMatches("262.8665.258", "IU-262.8665.258", expectedIsBaseline = false))
    }

    @Test
    fun `a full build does not match a different build`() {
        assertFalse(ideBuildMatches("262.8665.258", "262.8665.337", expectedIsBaseline = false))
        assertFalse(ideBuildMatches("263.1.1", "262.8665.258", expectedIsBaseline = false))
    }

    @Test
    fun `a baseline expectation matches the full build on that baseline`() {
        // #423: idea-community resolves to baseline 262 and the artifact reports 262.8665.258.
        assertTrue(ideBuildMatches("262.8665.258", "262", expectedIsBaseline = true))
        assertTrue(ideBuildMatches("IC-262.8665.258", "262", expectedIsBaseline = true))
        assertTrue(ideBuildMatches("AI-262.8665.258.2621.14049965", "262", expectedIsBaseline = true))
        assertTrue(ideBuildMatches("262", "262", expectedIsBaseline = true))
    }

    @Test
    fun `a baseline expectation is not a raw string prefix`() {
        assertFalse(ideBuildMatches("262.8665.258", "26", expectedIsBaseline = true))
        assertFalse(ideBuildMatches("2620.1.1", "262", expectedIsBaseline = true))
        assertFalse(ideBuildMatches("263.8665.258", "262", expectedIsBaseline = true))
    }

    @Test
    fun `a baseline is only tolerated when the resolution says so`() {
        assertFalse(ideBuildMatches("262.8665.258", "262", expectedIsBaseline = false))
    }

    @Test
    fun `missing builds never match`() {
        assertFalse(ideBuildMatches(null, "262", expectedIsBaseline = true))
        assertFalse(ideBuildMatches("262.8665.258", null, expectedIsBaseline = true))
        assertFalse(ideBuildMatches("  ", "262", expectedIsBaseline = true))
    }

    @Test
    fun `isPlatformBaselineOnly recognises a bare baseline`() {
        assertTrue(isPlatformBaselineOnly("262"))
        assertTrue(isPlatformBaselineOnly("IC-262"))
        assertFalse(isPlatformBaselineOnly("262.8665.258"))
        assertFalse(isPlatformBaselineOnly("2026.1.2"))
        assertFalse(isPlatformBaselineOnly(null))
    }
}
