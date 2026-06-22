package com.jonnyzzz.mcpSteroid.server

import java.math.BigInteger
import java.math.BigInteger.valueOf
import java.security.MessageDigest
import kotlin.collections.component1
import kotlin.collections.component2

fun base36FixedWidth(vararg args: Any?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("42-1_".encodeToByteArray())
    for (arg in args) {
        if (arg != null) {
            digest.update(arg.toString().encodeToByteArray())
        }
        digest.update(0.toByte())
    }

    // base62 (alphanumeric) over the full salted digest, fixed 8 chars. Unlike URL-safe
    // Base64 the alphabet has no '-'/'_', so the suffix can never contain or end with '-';
    // the whole 256-bit digest feeds the result, nothing is truncated before hashing. The
    // (home, pid) salting stays local; only the base62 rendering is shared (base62FixedWidth).
    var value = BigInteger(1, digest.digest())
    val sb = StringBuilder(8)

    val BASE36 = "0123456789abcdefghijklmnopqrstuvwxyz"
    repeat(8) {
        val (q, r) = value.divideAndRemainder(valueOf(BASE36.length.toLong()))
        sb.append(BASE36[r.toInt()])
        value = q
    }
    return sb.toString()
}

