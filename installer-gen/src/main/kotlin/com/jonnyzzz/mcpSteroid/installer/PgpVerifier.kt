/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer

import org.bouncycastle.openpgp.PGPPublicKeyRingCollection
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureList
import org.bouncycastle.openpgp.PGPUtil
import org.bouncycastle.openpgp.jcajce.JcaPGPObjectFactory
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentVerifierBuilderProvider
import java.io.ByteArrayInputStream

/**
 * Verifies a detached OpenPGP signature against a vendor public key, using BouncyCastle. Used for the
 * vendor-natural validation of every JDK download: both Amazon Corretto (`<file>.sig` on the CDN) and
 * Azul Zulu (the Metadata API `signature-binary`) ship detached OpenPGP signatures.
 *
 * Both inputs are fed through [PGPUtil.getDecoderStream], which transparently handles binary OR
 * ASCII-armored encodings (the published keys are armored, the signatures are binary).
 *
 * The signing-key fingerprint is **pinned** by the caller ([expectedFingerprint]): the public key is
 * fetched live over HTTPS from the vendor, so without a pin a compromised key endpoint could serve an
 * attacker key + matching signature and pass verification. We therefore require the key that made the
 * signature to match a fingerprint hard-coded in the source before trusting the signature.
 */
object PgpVerifier {
    /**
     * Verify that [signature] (a detached OpenPGP signature) is a valid BINARY_DOCUMENT signature over
     * [data], made by the key in [publicKey] whose full fingerprint equals [expectedFingerprint] (hex,
     * case-insensitive, spaces ignored). Throws with a descriptive message on any failure — no soft mode.
     */
    fun verifyDetached(data: ByteArray, signature: ByteArray, publicKey: ByteArray, expectedFingerprint: String) {
        val keyRings = PGPPublicKeyRingCollection(
            PGPUtil.getDecoderStream(ByteArrayInputStream(publicKey)),
            JcaKeyFingerprintCalculator(),
        )

        val pgpObjects = JcaPGPObjectFactory(PGPUtil.getDecoderStream(ByteArrayInputStream(signature)))
        val signatures = pgpObjects.nextObject() as? PGPSignatureList
            ?: error("No OpenPGP signature packet found in the provided signature data")
        require(!signatures.isEmpty) { "Empty OpenPGP signature list" }

        val sig = signatures[0]
        require(sig.signatureType == PGPSignature.BINARY_DOCUMENT) {
            "Expected a BINARY_DOCUMENT (0x00) signature but got type 0x${sig.signatureType.toString(16)}"
        }

        val key = keyRings.getPublicKey(sig.keyID)
            ?: error("Signature was made by key 0x${sig.keyID.toString(16)}, which is not in the provided key set")

        // Pin the signing key: bind verification to a known vendor key, not "whatever the key URL served".
        val actualFingerprint = key.fingerprint.joinToString("") { "%02x".format(it) }
        val normalizedExpected = expectedFingerprint.replace(" ", "").lowercase()
        require(actualFingerprint == normalizedExpected) {
            "Signing key fingerprint $actualFingerprint does not match the pinned $normalizedExpected — " +
                    "refusing to trust the signature (possible compromised key endpoint)"
        }

        sig.init(JcaPGPContentVerifierBuilderProvider(), key)
        sig.update(data)
        check(sig.verify()) {
            "OpenPGP signature verification FAILED for pinned key $actualFingerprint " +
                    "(${key.userIDs.asSequence().firstOrNull() ?: "no uid"})"
        }
    }
}
