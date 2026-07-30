/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinVersionCompatibilityTest {
    @Test
    fun parseStrictVersionParsesMajorMinorPatch() {
        assertEquals(
            KotlinVersion(2, 3, 10),
            KotlinVersionCompatibility.parseStrictVersion("2.3.10")
        )
    }

    @Test
    fun parseVersionFromTextFindsEmbeddedVersion() {
        assertEquals(
            KotlinVersion(2, 2, 0),
            KotlinVersionCompatibility.parseVersionFromText("random text 2.2.0 more random text")
        )
    }

    @Test
    fun compatibilityAcceptsOneMinorDifference() {
        assertTrue(
            KotlinVersionCompatibility.isCompatible(
                ideKotlin = KotlinVersion(2, 2, 21),
                bundledKotlin = KotlinVersion(2, 3, 10),
            )
        )
        assertTrue(
            KotlinVersionCompatibility.isCompatible(
                ideKotlin = KotlinVersion(2, 3, 10),
                bundledKotlin = KotlinVersion(2, 2, 21),
            )
        )
    }

    @Test
    fun compatibilityRejectsMajorOrTooFarMinorDifference() {
        assertFalse(
            KotlinVersionCompatibility.isCompatible(
                ideKotlin = KotlinVersion(2, 2, 21),
                bundledKotlin = KotlinVersion(1, 9, 24),
            )
        )
        assertFalse(
            KotlinVersionCompatibility.isCompatible(
                ideKotlin = KotlinVersion(2, 1, 21),
                bundledKotlin = KotlinVersion(2, 3, 10),
            )
        )
    }

    @Test
    fun pluginVersionNotNewerThanIde() {
        // plugin == IDE → OK
        assertTrue(KotlinVersion(2, 3, 10) <= KotlinVersion(2, 3, 10))
        // plugin older than IDE → OK
        assertTrue(KotlinVersion(2, 2, 21) <= KotlinVersion(2, 3, 10))
        // plugin newer than IDE → FAIL
        assertFalse(KotlinVersion(2, 3, 10) <= KotlinVersion(2, 2, 21))
        // plugin newer by patch only → FAIL
        assertFalse(KotlinVersion(2, 3, 11) <= KotlinVersion(2, 3, 10))
    }
}
