/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.installer

import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags
import org.bouncycastle.bcpg.PublicKeyPacket
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.PGPPublicKeyRing
import org.bouncycastle.openpgp.PGPSecretKey
import org.bouncycastle.openpgp.PGPSignature
import org.bouncycastle.openpgp.PGPSignatureGenerator
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.Security
import java.util.Date

/**
 * Test-only OpenPGP keypair: generates a real RSA key with BouncyCastle so tests can produce detached
 * signatures exactly like Corretto/Azul do, and exercise [PgpVerifier] hermetically (no network).
 */
class TestPgp private constructor(
    val publicKeyRing: ByteArray,
    /** Hex fingerprint of the signing key — pass to [PgpVerifier.verifyDetached] / resolveAllJdks. */
    val fingerprint: String,
    private val secretKey: PGPSecretKey,
    private val passphrase: CharArray,
) {
    /** Produce a detached binary OpenPGP signature over [data], signed by this keypair. */
    fun signDetached(data: ByteArray): ByteArray {
        val privateKey = secretKey.extractPrivateKey(
            JcePBESecretKeyDecryptorBuilder(JcaPGPDigestCalculatorProviderBuilder().setProvider("BC").build())
                .setProvider("BC").build(passphrase)
        )
        val generator = PGPSignatureGenerator(
            JcaPGPContentSignerBuilder(secretKey.publicKey.algorithm, HashAlgorithmTags.SHA256).setProvider("BC"),
            secretKey.publicKey,
        )
        generator.init(PGPSignature.BINARY_DOCUMENT, privateKey)
        generator.update(data)
        return ByteArrayOutputStream().also { generator.generate().encode(it) }.toByteArray()
    }

    companion object {
        init {
            if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
        }

        fun generate(uid: String = "test <test@example.com>"): TestPgp {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            // Fixed Date keeps signatures deterministic; the value is irrelevant to verification.
            val pgpKeyPair = JcaPGPKeyPair(PublicKeyPacket.VERSION_4, PublicKeyAlgorithmTags.RSA_GENERAL, keyPair, Date(0))
            val sha1 = JcaPGPDigestCalculatorProviderBuilder().setProvider("BC").build().get(HashAlgorithmTags.SHA1)
            val passphrase = "test-passphrase".toCharArray()
            val secretKey = PGPSecretKey(
                PGPSignature.DEFAULT_CERTIFICATION,
                pgpKeyPair,
                uid,
                sha1,
                null,
                null,
                JcaPGPContentSignerBuilder(pgpKeyPair.publicKey.algorithm, HashAlgorithmTags.SHA256).setProvider("BC"),
                JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1).setProvider("BC").build(passphrase),
            )
            val pubRing = PGPPublicKeyRing(secretKey.publicKey.encoded, JcaKeyFingerprintCalculator())
            val fingerprint = secretKey.publicKey.fingerprint.joinToString("") { "%02x".format(it) }
            return TestPgp(pubRing.encoded, fingerprint, secretKey, passphrase)
        }
    }
}
