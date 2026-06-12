/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackendNameTest {
    @Test
    fun `hash8 is deterministic`() {
        assertEquals(hash8("pid:1234"), hash8("pid:1234"))
    }

    @Test
    fun `hash8 differs for different inputs`() {
        assertNotEquals(hash8("pid:1234"), hash8("pid:1235"))
        assertNotEquals(hash8("pid:1234"), hash8("port:1234"))
    }

    @Test
    fun `hash8 is exactly 8 alphanumeric chars`() {
        val hash = hash8("managed:some-managed-id")
        assertEquals(8, hash.length, hash)
        assertTrue(hash.all { it.isLetterOrDigit() }, "expected alphanumeric but was: $hash")
        // base62 alphabet excludes URL-unsafe '-'/'_'.
        assertTrue(hash.none { it == '-' || it == '_' }, "must not contain '-'/'_': $hash")
    }

    @Test
    fun `base62FixedWidth pads to the requested width`() {
        // A single low byte renders to far fewer than 8 base62 digits; fixed width zero-pads.
        val hash = base62FixedWidth(byteArrayOf(1), 8)
        assertEquals(8, hash.length)
        assertTrue(hash.all { it.isLetterOrDigit() }, hash)
    }

    @Test
    fun `productCodeFromBuild extracts the verbatim capital build prefix`() {
        assertEquals("IU", productCodeFromBuild("IU-261.25134.95"))
        assertEquals("IC", productCodeFromBuild("IC-261.1"))
        assertEquals("GO", productCodeFromBuild("GO-261.1"))
        assertEquals("PC", productCodeFromBuild("PC-261.1"))
        assertNull(productCodeFromBuild(null))
        assertNull(productCodeFromBuild(""))
        // Lowercase prefixes are not IDE product codes.
        assertNull(productCodeFromBuild("iu-261.1"))
        // A bare build number ("253.x" from a port /api/about) has no prefix.
        assertNull(productCodeFromBuild("253.21581.142"))
    }

    @Test
    fun `backend_name keeps the product code capitals verbatim`() {
        val name = backendNameFor(productCode = productCodeFromBuild("IU-261.25134.95"), sourceKey = "pid:42")
        assertEquals("IU-${hash8("pid:42")}", name)
        assertTrue(name.startsWith("IU-"), "product segment must stay capital, was: $name")
    }

    @Test
    fun `backend_name falls back to IDE when no product code is known`() {
        assertEquals("IDE-${hash8("pid:42")}", backendNameFor(productCode = null, sourceKey = "pid:42"))
        assertEquals("IDE-${hash8("pid:42")}", backendNameFor(productCode = "", sourceKey = "pid:42"))
    }

    @Test
    fun `backendNameForMarker delegates to the one formula`() {
        assertEquals(
            backendNameFor(productCode = "IU", sourceKey = "pid:24017"),
            backendNameForMarker(pid = 24017L, build = "IU-261.23567.138"),
        )
    }
}
