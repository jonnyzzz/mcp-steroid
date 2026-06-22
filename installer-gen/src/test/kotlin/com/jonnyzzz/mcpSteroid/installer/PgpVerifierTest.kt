/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PgpVerifierTest {
    private val data = "the JDK archive bytes".encodeToByteArray()

    @Test
    fun `accepts a valid detached signature from the pinned key`() {
        val keys = TestPgp.generate()
        PgpVerifier.verifyDetached(data, keys.signDetached(data), keys.publicKeyRing, keys.fingerprint)
    }

    @Test
    fun `rejects when the signed data was tampered with`() {
        val keys = TestPgp.generate()
        val signature = keys.signDetached(data)
        val tampered = data.copyOf().also { it[0] = (it[0] + 1).toByte() }

        val ex = assertFailsWith<IllegalStateException> {
            PgpVerifier.verifyDetached(tampered, signature, keys.publicKeyRing, keys.fingerprint)
        }
        assertTrue(ex.message!!.contains("FAILED"), ex.message!!)
    }

    @Test
    fun `rejects a signature made by a key not in the key set`() {
        val signer = TestPgp.generate()
        val other = TestPgp.generate()

        // Signature is valid, but verified against the WRONG public key set -> key not found.
        assertFailsWith<IllegalStateException> {
            PgpVerifier.verifyDetached(data, signer.signDetached(data), other.publicKeyRing, signer.fingerprint)
        }
    }

    @Test
    fun `rejects a valid signature when the key fingerprint is not the pinned one`() {
        val keys = TestPgp.generate()
        // The signature verifies cryptographically, but the caller pins a DIFFERENT fingerprint — this is
        // the compromised-key-endpoint case (attacker serves their own key + matching signature).
        val wrongPin = "0".repeat(40)

        val ex = assertFailsWith<IllegalArgumentException> {
            PgpVerifier.verifyDetached(data, keys.signDetached(data), keys.publicKeyRing, wrongPin)
        }
        assertTrue(ex.message!!.contains("does not match the pinned"), ex.message!!)
    }

    @Test
    fun `fingerprint match ignores case and spaces`() {
        val keys = TestPgp.generate()
        val spacedUpper = keys.fingerprint.uppercase().chunked(4).joinToString(" ")
        PgpVerifier.verifyDetached(data, keys.signDetached(data), keys.publicKeyRing, spacedUpper)
    }
}
