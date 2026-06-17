/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer

import com.jonnyzzz.mcpSteroid.installer.resolver.parseVendorSha
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

/** Each vendor's sha-response shape is its own declared test (no parameterization) — a failure names the
 *  exact vendor body it parsed. The HTTP fetch is a separate injectable seam; this pins the PARSING. */
class VendorShaTest {
    private val sha = "00486fa402136f8d40512b101c645dd4db9be2b5535171530ad241cd96c1223d"

    @Test
    fun `corretto body is bare hex with an optional trailing newline`() {
        assertEquals(sha, parseVendorSha("corretto", "$sha\n"))
        assertEquals(sha, parseVendorSha("corretto", sha)) // live body has no trailing newline
    }

    @Test
    fun `azul-zulu body is JSON with sha256_hash`() {
        val body = """{"name":"zulu25...win_aarch64.zip","download_url":"https://cdn.azul.com/...","sha256_hash":"$sha"}"""
        assertEquals(sha, parseVendorSha("azul-zulu", body))
    }

    @Test
    fun `microsoft body is a sha256sum line - sha then filename`() {
        assertEquals(sha, parseVendorSha("microsoft", "$sha  microsoft-jdk-25.0.3-windows-x64.zip\n"))
    }

    @Test
    fun `uppercase vendor hex is normalized to lowercase`() {
        assertEquals(sha, parseVendorSha("corretto", sha.uppercase() + "\n"))
    }

    @Test
    fun `an unknown vendor fails fast`() {
        assertFailsWith<IllegalStateException> { parseVendorSha("oracle", sha) }
    }

    @Test
    fun `azul body without sha256_hash fails fast`() {
        assertFailsWith<IllegalStateException> { parseVendorSha("azul-zulu", """{"name":"x","download_url":"y"}""") }
    }

    @Test
    fun `a non-64-hex body fails fast`() {
        assertFailsWith<IllegalArgumentException> { parseVendorSha("corretto", "not-a-sha\n") }
    }
}
